// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

//! Star detection and half-flux radius (HFR) as N.I.N.A. 3.2 computes them with its default image
//! settings, so that a frame gives the same numbers here as in NINA.
//!
//! The steps are ported from NINA's `StarDetection.cs` and `BayerFilter16bpp.cs` (MPL-2.0,
//! Copyright © 2016-2024 Stefan Berg and the N.I.N.A. contributors). The image filters NINA takes
//! from Accord.NET (bicubic resize, Canny edges, SIS threshold, binary dilation, blob counting and
//! the circle check) are written here from their published algorithms, with the same parameters.
//!
//! A colour frame is debayered (each pixel averages its 3 × 3 neighbours per channel), each
//! channel gets NINA's automatic stretch, the channels are mixed to grey and cut to 8 bits, a wide
//! frame is shrunk, and edges, a threshold, a dilation and a blob count find the structures. Each
//! structure that looks like a star is then measured at full size on the debayered luminance.
//! `tools/nina-reference` prints NINA's own numbers for a frame, to compare.

use rayon::prelude::*;

use crate::image::{Image, NinaStretch, nina_debayer_pixel};
use crate::psf;

/// How hard NINA looks for faint stars. NINA's default is `High`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum StarSensitivity {
    Normal,
    High,
    Highest,
}

/// One star: its centroid in image pixels from the top-left corner, its HFR in pixels, the local
/// background around it and its brightest pixel, in ADU, and its FWHM in pixels as Hocus Focus fits
/// it (none when the fit fails or explains too little).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct DetectedStar {
    pub x: f32,
    pub y: f32,
    pub hfr: f64,
    pub background: f64,
    pub max_brightness: f64,
    /// The mean light above the background over its box, which NINA ranks stars by to mark them.
    pub average_brightness: f64,
    /// The box around the star that was measured, in image pixels, as NINA draws it.
    pub box_x: i32,
    pub box_y: i32,
    pub box_width: i32,
    pub box_height: i32,
    pub fwhm: Option<f64>,
}

/// The stars of a frame and their mean HFR and its standard deviation, as NINA gives them, and the
/// median FWHM of the stars with a fit, as Hocus Focus gives it, in pixels; 0 without stars.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct StarDetection {
    pub stars: Vec<DetectedStar>,
    pub average_hfr: f64,
    pub hfr_std_dev: f64,
    pub fwhm: f64,
}

#[uniffi::export]
impl Image {
    /// The stars of the frame and their HFR, as NINA finds them. `pixel_size_um` and
    /// `focal_length_mm` give the image scale that the `High` sensitivity uses to choose how much
    /// to shrink a wide frame; without them it shrinks as NINA does without a scale.
    pub fn detect_stars(
        &self,
        sensitivity: StarSensitivity,
        pixel_size_um: Option<f64>,
        focal_length_mm: Option<f64>,
    ) -> StarDetection {
        let scale = match (pixel_size_um, focal_length_mm) {
            (Some(pixel), Some(focal)) if focal > 0.0 => Some(pixel / focal * ARCSEC_PER_PIXEL),
            _ => None,
        };
        detect(self, sensitivity, scale)
    }
}

/// NINA's factor from microns per millimetre to arcseconds: the arcseconds in a radian / 1000.
const ARCSEC_PER_PIXEL: f64 = 206.264_806_247_096_36;
/// Frames wider than this are shrunk to find the structures.
const MAX_WIDTH: usize = 1552;
const CANNY_LOW: u8 = 10;
const CANNY_HIGH: u8 = 80;

pub(crate) fn detect(
    image: &Image,
    sensitivity: StarSensitivity,
    arcsec_per_pixel: Option<f64>,
) -> StarDetection {
    let (width, height) = (image.width, image.height);
    let (luminance, grey) = prepare(image);
    let resize = resize_factor(width, sensitivity, arcsec_per_pixel);
    let (small_width, small_height, mut small) = if width > MAX_WIDTH {
        let w = (width as f64 * resize).floor() as usize;
        let h = (height as f64 * resize).floor() as usize;
        (w, h, resize_bicubic(&grey, width, height, w, h))
    } else {
        (width, height, grey)
    };
    canny(&mut small, small_width, small_height);
    sis_threshold(&mut small, small_width, small_height);
    let small = dilate(&small, small_width, small_height);
    let blobs = Blobs::count(&small, small_width, small_height);
    let luminance = luminance.as_deref().unwrap_or(&image.pixels[..]);
    identify(image, luminance, width, height, &blobs, resize, sensitivity)
}

/// The luminance to measure on (none for a mono frame: its own pixels) and the 8-bit grey image to
/// find structures in, both at full size.
fn prepare(image: &Image) -> (Option<Vec<u16>>, Vec<u8>) {
    match image.bayer {
        Some(bayer) => {
            let channels = debayer(image, bayer);
            let maps: Vec<Vec<u16>> = channels.colours.iter().map(|c| stretch_map(c)).collect();
            let [red, green, blue] = &channels.colours;
            let grey = (0..image.pixels.len())
                .into_par_iter()
                .map(|i| {
                    // Accord's grey mix, with NINA's channels as they lie in its 48-bit bitmap.
                    let r = maps[0][red[i] as usize] as f64;
                    let g = maps[1][green[i] as usize] as f64;
                    let b = maps[2][blue[i] as usize] as f64;
                    let grey16 = (0.2125 * b + 0.7154 * g + 0.0721 * r) as u16;
                    (grey16 >> 8) as u8
                })
                .collect();
            (Some(channels.luminance), grey)
        }
        None => {
            let map = stretch_map(&image.pixels);
            let grey = image
                .pixels
                .par_iter()
                .map(|&p| (map[p as usize] >> 8) as u8)
                .collect();
            (None, grey)
        }
    }
}

/// How much NINA shrinks a frame of `width` pixels before looking for structures.
fn resize_factor(width: usize, sensitivity: StarSensitivity, arcsec_per_pixel: Option<f64>) -> f64 {
    if width <= MAX_WIDTH {
        return 1.0;
    }
    let fit = MAX_WIDTH as f64 / width as f64;
    match sensitivity {
        StarSensitivity::Highest => (2.0 / 3.0f64).max(fit),
        StarSensitivity::Normal => fit,
        StarSensitivity::High => match arcsec_per_pixel.filter(|s| !s.is_nan()) {
            None => 0.5f64.max(fit),
            Some(scale) => {
                let mut factor = 1.0;
                if scale < 0.5 {
                    factor = 1.0 / 4.0;
                }
                if (0.5..=1.5).contains(&scale) {
                    factor = 1.0 / 3.0;
                }
                if (1.5..=2.5).contains(&scale) {
                    factor = 1.0 / 2.0;
                }
                if scale > 1.5 {
                    factor = (2.0 / 3.0f64).max(fit);
                }
                factor
            }
        },
    }
}

struct Debayered {
    /// Red, green and blue at full size.
    colours: [Vec<u16>; 3],
    /// The mean of the three, rounded down.
    luminance: Vec<u16>,
}

/// NINA's debayer of the whole frame, keeping the luminance (the mean of the three, rounded down).
fn debayer(image: &Image, bayer: crate::image::BayerPattern) -> Debayered {
    let (width, height) = (image.width, image.height);
    let mut red = vec![0u16; width * height];
    let mut green = vec![0u16; width * height];
    let mut blue = vec![0u16; width * height];
    let mut luminance = vec![0u16; width * height];
    red.par_chunks_mut(width)
        .zip(green.par_chunks_mut(width))
        .zip(blue.par_chunks_mut(width))
        .zip(luminance.par_chunks_mut(width))
        .enumerate()
        .for_each(|(y, (((r, g), b), l))| {
            for x in 0..width {
                let [vr, vg, vb] = nina_debayer_pixel(image, bayer, x, y);
                r[x] = vr;
                g[x] = vg;
                b[x] = vb;
                l[x] = ((vr as u32 + vg as u32 + vb as u32) as f64 / 3.0).floor() as u16;
            }
        });
    Debayered {
        colours: [red, green, blue],
        luminance,
    }
}

fn value_counts(pixels: &[u16]) -> Vec<u64> {
    let mut counts = vec![0u64; 1 << 16];
    for &p in pixels {
        counts[p as usize] += 1;
    }
    counts
}

/// NINA's automatic stretch of one channel as a table from each 16-bit value.
fn stretch_map(pixels: &[u16]) -> Vec<u16> {
    let stretch = NinaStretch::from_counts(&value_counts(pixels), pixels.len() as u64);
    (0..=u16::MAX).map(|v| stretch.apply(v)).collect()
}

/// The cubic convolution kernel with a = -0.5.
fn bicubic_kernel(x: f64) -> f64 {
    let x = x.abs();
    if x <= 1.0 {
        (1.5 * x - 2.5) * x * x + 1.0
    } else if x < 2.0 {
        ((-0.5 * x + 2.5) * x - 4.0) * x + 2.0
    } else {
        0.0
    }
}

/// Bicubic resize of an 8-bit image, sampling 4 × 4 source pixels around each target pixel.
fn resize_bicubic(
    src: &[u8],
    width: usize,
    height: usize,
    new_width: usize,
    new_height: usize,
) -> Vec<u8> {
    let x_factor = width as f64 / new_width as f64;
    let y_factor = height as f64 / new_height as f64;
    let (x_max, y_max) = (width as i64 - 1, height as i64 - 1);
    let mut dst = vec![0u8; new_width * new_height];
    dst.par_chunks_mut(new_width)
        .enumerate()
        .for_each(|(y, row)| {
            let oy1 = y as f64 * y_factor - 0.5;
            let oy = oy1 as i64;
            let dy = oy1 - oy as f64;
            for (x, out) in row.iter_mut().enumerate() {
                let ox1 = x as f64 * x_factor - 0.5;
                let ox = ox1 as i64;
                let dx = ox1 - ox as f64;
                let mut g = 0.0;
                for n in -1i64..3 {
                    let k1 = bicubic_kernel(dy - n as f64);
                    let oy2 = (oy + n).clamp(0, y_max) as usize;
                    for m in -1i64..3 {
                        let k2 = k1 * bicubic_kernel(m as f64 - dx);
                        let ox2 = (ox + m).clamp(0, x_max) as usize;
                        g += k2 * src[oy2 * width + ox2] as f64;
                    }
                }
                *out = g.clamp(0.0, 255.0) as u8;
            }
        });
    dst
}

/// A 5 × 5 Gaussian blur with σ = 1.4 and integer weights, dividing by the weights inside the
/// image at its edges.
fn gaussian_blur(src: &[u8], width: usize, height: usize) -> Vec<u8> {
    const SIZE: usize = 5;
    const RADIUS: i64 = 2;
    let sigma2 = 1.4f64 * 1.4;
    let weight = |x: i64, y: i64| {
        ((x * x + y * y) as f64 / (-2.0 * sigma2)).exp() / (2.0 * std::f64::consts::PI * sigma2)
    };
    let corner = weight(-RADIUS, -RADIUS);
    let mut kernel = [[0i64; SIZE]; SIZE];
    for (i, row) in kernel.iter_mut().enumerate() {
        for (j, k) in row.iter_mut().enumerate() {
            let v = (weight(j as i64 - RADIUS, i as i64 - RADIUS) / corner).min(u16::MAX as f64);
            *k = v as i64;
        }
    }
    let mut dst = vec![0u8; width * height];
    dst.par_chunks_mut(width).enumerate().for_each(|(y, row)| {
        for (x, out) in row.iter_mut().enumerate() {
            let (mut g, mut div) = (0i64, 0i64);
            for (i, krow) in kernel.iter().enumerate() {
                let t = y as i64 + i as i64 - RADIUS;
                if t < 0 {
                    continue;
                }
                if t >= height as i64 {
                    break;
                }
                for (j, &k) in krow.iter().enumerate() {
                    let u = x as i64 + j as i64 - RADIUS;
                    if u >= 0 && u < width as i64 {
                        div += k;
                        g += k * src[t as usize * width + u as usize] as i64;
                    }
                }
            }
            if div != 0 {
                g /= div;
            }
            *out = g.clamp(0, 255) as u8;
        }
    });
    dst
}

/// Canny edges in place: blur, Sobel gradients, thinning to the local maxima along the gradient,
/// and hysteresis between 10 and 80. The 1-pixel frame ends black.
fn canny(image: &mut [u8], width: usize, height: usize) {
    if width < 3 || height < 3 {
        image.fill(0);
        return;
    }
    let blurred = gaussian_blur(image, width, height);
    let mut gradients = vec![0f32; width * height];
    let mut orientations = vec![0u8; width * height];
    gradients
        .par_chunks_mut(width)
        .zip(orientations.par_chunks_mut(width))
        .enumerate()
        .filter(|(y, _)| *y >= 1 && *y < height - 1)
        .for_each(|(y, (grow, orow))| {
            let p = |dx: i64, dy: i64, x: usize| {
                blurred[((y as i64 + dy) as usize) * width + (x as i64 + dx) as usize] as i32
            };
            for x in 1..width - 1 {
                let gx = p(1, -1, x) + p(1, 1, x) - p(-1, -1, x) - p(-1, 1, x)
                    + 2 * (p(1, 0, x) - p(-1, 0, x));
                let gy = p(-1, -1, x) + p(1, -1, x) - p(-1, 1, x) - p(1, 1, x)
                    + 2 * (p(0, -1, x) - p(0, 1, x));
                grow[x] = ((gx * gx + gy * gy) as f64).sqrt() as f32;
                let orientation = if gx == 0 {
                    if gy == 0 { 0.0 } else { 90.0 }
                } else {
                    let div = gy as f64 / gx as f64;
                    if div < 0.0 {
                        180.0 - (-div).atan().to_degrees()
                    } else {
                        div.atan().to_degrees()
                    }
                };
                orow[x] = if orientation < 22.5 {
                    0
                } else if orientation < 67.5 {
                    45
                } else if orientation < 112.5 {
                    90
                } else if orientation < 157.5 {
                    135
                } else {
                    0
                };
            }
        });
    let max_gradient = gradients
        .par_iter()
        .copied()
        .reduce(|| f32::NEG_INFINITY, f32::max);
    // Thinning writes over the frame's own pixels; its border keeps them until the end, as the
    // filter Canny copies from does.
    let mut thin = image.to_vec();
    thin.par_chunks_mut(width)
        .enumerate()
        .filter(|(y, _)| *y >= 1 && *y < height - 1)
        .for_each(|(y, row)| {
            let g = |x: usize, y: usize| gradients[y * width + x];
            for x in 1..width - 1 {
                let (left, right) = match orientations[y * width + x] {
                    0 => (g(x - 1, y), g(x + 1, y)),
                    45 => (g(x - 1, y + 1), g(x + 1, y - 1)),
                    90 => (g(x, y + 1), g(x, y - 1)),
                    _ => (g(x + 1, y + 1), g(x - 1, y - 1)),
                };
                let value = g(x, y);
                row[x] = if value < left || value < right {
                    0
                } else {
                    let scaled = value / max_gradient * 255.0;
                    if scaled.is_nan() { 0 } else { scaled as u8 }
                };
            }
        });
    // Hysteresis: weak edges stay only next to a strong one. Strong values never change, so each
    // pixel can be decided from the thinned image alone.
    let thin = &thin;
    image
        .par_chunks_mut(width)
        .enumerate()
        .for_each(|(y, row)| {
            for (x, out) in row.iter_mut().enumerate() {
                if y == 0 || y == height - 1 || x == 0 || x == width - 1 {
                    *out = 0;
                    continue;
                }
                let v = thin[y * width + x];
                *out = if v >= CANNY_HIGH {
                    v
                } else if v < CANNY_LOW {
                    0
                } else {
                    let strong = [
                        (x - 1, y),
                        (x + 1, y),
                        (x - 1, y - 1),
                        (x, y - 1),
                        (x + 1, y - 1),
                        (x - 1, y + 1),
                        (x, y + 1),
                        (x + 1, y + 1),
                    ]
                    .iter()
                    .any(|&(nx, ny)| thin[ny * width + nx] >= CANNY_HIGH);
                    if strong { v } else { 0 }
                };
            }
        });
}

/// The SIS threshold in place: the mean of the pixels weighted by their local gradient becomes the
/// threshold, and pixels at or above it turn white, the rest black.
fn sis_threshold(image: &mut [u8], width: usize, height: usize) {
    let (mut weight_total, mut total) = (0.0f64, 0.0f64);
    for y in 1..height.saturating_sub(1) {
        for x in 1..width - 1 {
            let i = y * width + x;
            let ex = (image[i + 1] as i32 - image[i - 1] as i32).abs() as f64;
            let ey = (image[i + width] as i32 - image[i - width] as i32).abs() as f64;
            let weight = if ex > ey { ex } else { ey };
            weight_total += weight;
            total += weight * image[i] as f64;
        }
    }
    let threshold = if weight_total == 0.0 {
        0
    } else {
        (total / weight_total) as u8
    };
    image
        .par_iter_mut()
        .for_each(|p| *p = if *p >= threshold { 255 } else { 0 });
}

/// Binary dilation with a 3 × 3 square: a pixel turns white when any neighbour is white.
fn dilate(image: &[u8], width: usize, height: usize) -> Vec<u8> {
    let mut dst = vec![0u8; width * height];
    dst.par_chunks_mut(width).enumerate().for_each(|(y, row)| {
        let y0 = y.saturating_sub(1);
        let y1 = (y + 1).min(height - 1);
        for (x, out) in row.iter_mut().enumerate() {
            let x0 = x.saturating_sub(1);
            let x1 = (x + 1).min(width - 1);
            let mut value = 0u8;
            for yy in y0..=y1 {
                for xx in x0..=x1 {
                    value |= image[yy * width + xx];
                }
            }
            *out = value;
        }
    });
    dst
}

/// The rectangle of a blob or a star, in pixels.
#[derive(Debug, Clone, Copy, PartialEq)]
struct Rect {
    x: i64,
    y: i64,
    width: i64,
    height: i64,
}

/// The connected white regions of a binary image, 8-connected, with their label map.
struct Blobs {
    width: usize,
    labels: Vec<u32>,
    rects: Vec<Rect>,
}

impl Blobs {
    fn count(image: &[u8], width: usize, height: usize) -> Blobs {
        let mut labels = vec![0u32; width * height];
        let mut parent: Vec<u32> = vec![0];
        fn find(parent: &mut [u32], mut a: u32) -> u32 {
            while parent[a as usize] != a {
                parent[a as usize] = parent[parent[a as usize] as usize];
                a = parent[a as usize];
            }
            a
        }
        for y in 0..height {
            for x in 0..width {
                let i = y * width + x;
                if image[i] == 0 {
                    continue;
                }
                let mut neighbours = [0u32; 4];
                if x > 0 {
                    neighbours[0] = labels[i - 1];
                }
                if y > 0 {
                    if x > 0 {
                        neighbours[1] = labels[i - width - 1];
                    }
                    neighbours[2] = labels[i - width];
                    if x + 1 < width {
                        neighbours[3] = labels[i - width + 1];
                    }
                }
                let mut label = 0u32;
                for &n in neighbours.iter().filter(|&&n| n != 0) {
                    let root = find(&mut parent, n);
                    if label == 0 {
                        label = root;
                    } else if root != label {
                        let (low, high) = (label.min(root), label.max(root));
                        parent[high as usize] = low;
                        label = low;
                    }
                }
                if label == 0 {
                    label = parent.len() as u32;
                    parent.push(label);
                }
                labels[i] = label;
            }
        }
        // Number the regions 1, 2, … in the order they are first met.
        let mut ids = vec![0u32; parent.len()];
        let mut next = 0u32;
        for root in 1..parent.len() as u32 {
            let r = find(&mut parent, root);
            if r == root {
                next += 1;
                ids[root as usize] = next;
            }
        }
        let mut rects: Vec<Option<(i64, i64, i64, i64)>> = vec![None; next as usize + 1];
        for y in 0..height {
            for x in 0..width {
                let i = y * width + x;
                if labels[i] == 0 {
                    continue;
                }
                let id = ids[find(&mut parent, labels[i]) as usize];
                labels[i] = id;
                let (x, y) = (x as i64, y as i64);
                let r = rects[id as usize].get_or_insert((x, y, x, y));
                r.0 = r.0.min(x);
                r.1 = r.1.min(y);
                r.2 = r.2.max(x);
                r.3 = r.3.max(y);
            }
        }
        let rects = rects
            .into_iter()
            .skip(1)
            .flatten()
            // As Accord's blob counter, which NINA uses: the size is last minus first, one pixel
            // short of the region, and the edge points leave out its last column and row.
            .map(|(x0, y0, x1, y1)| Rect {
                x: x0,
                y: y0,
                width: x1 - x0,
                height: y1 - y0,
            })
            .collect();
        Blobs {
            width,
            labels,
            rects,
        }
    }

    /// The blob's edge: the first and last pixel of each row, then the first and last of each
    /// column when not already taken from its row.
    fn edge_points(&self, index: usize) -> Vec<(i64, i64)> {
        let rect = self.rects[index];
        let label = index as u32 + 1;
        let at = |x: i64, y: i64| self.labels[y as usize * self.width + x as usize] == label;
        let (x_min, x_max) = (rect.x, rect.x + rect.width - 1);
        let (y_min, y_max) = (rect.y, rect.y + rect.height - 1);
        let mut points = Vec::new();
        let mut left = vec![0i64; rect.height as usize];
        let mut right = vec![0i64; rect.height as usize];
        for y in y_min..=y_max {
            let row = (y - y_min) as usize;
            if let Some(x) = (x_min..=x_max).find(|&x| at(x, y)) {
                points.push((x, y));
                left[row] = x;
            }
            if let Some(x) = (x_min..=x_max).rev().find(|&x| at(x, y)) {
                if left[row] != x {
                    points.push((x, y));
                }
                right[row] = x;
            }
        }
        for x in x_min..=x_max {
            if let Some(y) = (y_min..=y_max).find(|&y| at(x, y)) {
                let row = (y - y_min) as usize;
                if left[row] != x && right[row] != x {
                    points.push((x, y));
                }
            }
            if let Some(y) = (y_min..=y_max).rev().find(|&y| at(x, y)) {
                let row = (y - y_min) as usize;
                if left[row] != x && right[row] != x {
                    points.push((x, y));
                }
            }
        }
        points
    }
}

/// Whether edge points lie on a circle, with the circle's centre and radius taken from their
/// bounding box; the centre is given even when they do not.
fn is_circle(points: &[(i64, i64)]) -> (bool, (f32, f32), f32) {
    const MIN_ACCEPTABLE_DISTORTION: f32 = 0.5;
    const RELATIVE_DISTORTION_LIMIT: f32 = 0.03;
    if points.len() < 8 {
        return (false, (0.0, 0.0), 0.0);
    }
    let (min_x, max_x) = points
        .iter()
        .fold((i64::MAX, i64::MIN), |(a, b), p| (a.min(p.0), b.max(p.0)));
    let (min_y, max_y) = points
        .iter()
        .fold((i64::MAX, i64::MIN), |(a, b), p| (a.min(p.1), b.max(p.1)));
    let (size_x, size_y) = ((max_x - min_x) as f32, (max_y - min_y) as f32);
    let centre = (min_x as f32 + size_x / 2.0, min_y as f32 + size_y / 2.0);
    let radius = (size_x + size_y) / 4.0;
    let mut mean_distance = 0f32;
    for &(x, y) in points {
        let dx = centre.0 - x as f32;
        let dy = centre.1 - y as f32;
        mean_distance += ((dx * dx + dy * dy).sqrt() - radius).abs();
    }
    mean_distance /= points.len() as f32;
    let max_distance =
        MIN_ACCEPTABLE_DISTORTION.max((size_x + size_y) / 2.0 * RELATIVE_DISTORTION_LIMIT);
    (mean_distance <= max_distance, centre, radius)
}

/// A star candidate while it is measured.
struct Star {
    radius: f64,
    rect: Rect,
    mean_brightness: f64,
    surrounding_mean: f64,
    max_pixel: f64,
    position: (f32, f32),
    hfr: f64,
    /// The mean light above the background over the star's box, which NINA ranks stars by.
    average: f64,
}

impl Star {
    fn inside_circle(x: f64, y: f64, cx: f64, cy: f64, radius: f64) -> bool {
        (x - cx).powi(2) + (y - cy).powi(2) <= radius.powi(2)
    }

    /// The HFR over the pixels within 1.2 radii of the centre, background subtracted, and the
    /// centroid of that light.
    fn calculate(&mut self, pixels: &[(i64, i64, f64)]) {
        let mut hfr = 0.0;
        if !pixels.is_empty() {
            let outer = self.radius * 1.2;
            let (mut sum, mut sum_dist, mut sum_x, mut sum_y) = (0.0, 0.0, 0.0, 0.0);
            let mut all_sum = 0.0;
            let (cx, cy) = (self.position.0 as f64, self.position.1 as f64);
            for &(x, y, v) in pixels {
                let value = (v - self.surrounding_mean).round_ties_even().max(0.0);
                all_sum += value;
                let (x, y) = (x as f64, y as f64);
                if Self::inside_circle(x, y, cx, cy, outer) {
                    sum += value;
                    sum_dist += value * ((x - cx).powi(2) + (y - cy).powi(2)).sqrt();
                    sum_x += (x - self.rect.x as f64) * value;
                    sum_y += (y - self.rect.y as f64) * value;
                }
            }
            hfr = if sum > 0.0 {
                sum_dist / sum
            } else {
                std::f64::consts::SQRT_2 * outer
            };
            self.average = all_sum / pixels.len() as f64;
            if sum != 0.0 {
                self.position = (
                    (sum_x / sum) as f32 + self.rect.x as f32,
                    (sum_y / sum) as f32 + self.rect.y as f32,
                );
            }
        }
        self.hfr = hfr;
    }
}

/// NINA's star identification: each blob of a plausible size that is round enough, brighter than
/// its surroundings and not touching its box becomes a star; then stars whose radius is far from
/// the mean are dropped.
fn identify(
    image: &Image,
    luminance: &[u16],
    width: usize,
    height: usize,
    blobs: &Blobs,
    resize: f64,
    sensitivity: StarSensitivity,
) -> StarDetection {
    let inverse = 1.0 / resize;
    let min_star = ((5.0 * resize).floor() as i64).max(2);
    let max_star = (150.0 * resize).ceil() as i64;
    let minimum_bright_pixels = (width.max(height) as f64 / 1000.0).ceil() as usize;
    let (w, h) = (width as i64, height as i64);

    let candidates: Vec<Star> = (0..blobs.rects.len())
        .into_par_iter()
        .filter_map(|index| {
            let blob = blobs.rects[index];
            if blob.width > max_star
                || blob.height > max_star
                || blob.width < min_star
                || blob.height < min_star
            {
                return None;
            }
            let points = blobs.edge_points(index);
            let rect = Rect {
                x: (blob.x as f64 * inverse).floor() as i64,
                y: (blob.y as f64 * inverse).floor() as i64,
                width: (blob.width as f64 * inverse).ceil() as i64,
                height: (blob.height as f64 * inverse).ceil() as i64,
            };
            let large_x = (rect.x - rect.width).max(0);
            let large_y = (rect.y - rect.height).max(0);
            let mut large_width = rect.width * 3;
            if large_x + large_width > w {
                large_width = w - large_x;
            }
            let mut large_height = rect.height * 3;
            if large_y + large_height > h {
                large_height = h - large_y;
            }

            let (circle, centre, radius) = is_circle(&points);
            let inv = inverse as f32;
            let position = (centre.0 * inv, centre.1 * inv);
            let star_radius = if circle {
                radius as f64 * inverse
            } else {
                let (a, b) = (
                    rect.width.max(rect.height) as f64,
                    rect.width.min(rect.height) as f64,
                );
                let eccentricity = (a * a - b * b).sqrt() / a;
                if eccentricity > 0.8 {
                    return None;
                }
                (rect.width.max(rect.height) / 2) as f64
            };
            let mut star = Star {
                radius: star_radius,
                rect,
                mean_brightness: 0.0,
                surrounding_mean: 0.0,
                max_pixel: 0.0,
                position,
                hfr: 0.0,
                average: 0.0,
            };

            let (mut star_sum, mut star_count) = (0.0f64, 0usize);
            let (mut large_sum, mut large_squares) = (0.0f64, 0.0f64);
            let mut inner = Vec::new();
            let mut pixels = Vec::new();
            let (px, py) = (star.position.0 as f64, star.position.1 as f64);
            for x in large_x..large_x + large_width {
                for y in large_y..large_y + large_height {
                    let value = luminance[(x + w * y) as usize];
                    if x >= rect.x
                        && x < rect.x + rect.width
                        && y >= rect.y
                        && y < rect.y + rect.height
                    {
                        if Star::inside_circle(x as f64, y as f64, px, py, star.radius) {
                            star_sum += value as f64;
                            star_count += 1;
                            inner.push(value);
                            star.max_pixel = star.max_pixel.max(value as f64);
                        }
                        pixels.push((x, y, value as f64));
                    } else {
                        large_sum += value as f64;
                        // NINA squares the value as a 32-bit integer, which wraps above 46 340.
                        large_squares += (value as i32).wrapping_mul(value as i32) as f64;
                    }
                }
            }
            star.mean_brightness = star_sum / star_count as f64;
            let large_count = (large_height * large_width - rect.height * rect.width) as f64;
            let large_mean = large_sum / large_count;
            star.surrounding_mean = large_mean;
            let large_stdev =
                ((large_squares - large_count * large_mean * large_mean) / large_count).sqrt();
            let bright = inner
                .iter()
                .filter(|&&v| v as f64 > large_mean + 1.5 * large_stdev)
                .count();
            if star.mean_brightness >= large_mean + (0.1 * large_mean).min(large_stdev)
                && bright > minimum_bright_pixels
            {
                star.calculate(&pixels);
                let (x, y) = (star.position.0 as f64, star.position.1 as f64);
                // As NINA: the last two compare the centroid with itself, so only the first two bite.
                if x > (rect.x + 1) as f64
                    && y > (rect.y + 1) as f64
                    && x < x + (rect.width - 2) as f64
                    && y < y + (rect.height - 2) as f64
                {
                    return Some(star);
                }
            }
            None
        })
        .collect();

    if candidates.is_empty() {
        return StarDetection {
            stars: Vec::new(),
            average_hfr: 0.0,
            hfr_std_dev: 0.0,
            fwhm: 0.0,
        };
    }
    let n = candidates.len() as f64;
    let sum_radius: f64 = candidates.iter().map(|s| s.radius).sum();
    let sum_squares: f64 = candidates.iter().map(|s| s.radius * s.radius).sum();
    let avg = sum_radius / n;
    let stdev = ((sum_squares - n * avg * avg) / n).sqrt();
    let upper = if sensitivity == StarSensitivity::Highest {
        2.0
    } else {
        1.5
    };
    // Hocus Focus fits profiles on its own filtered image.
    let measured = psf::measurement_image(image);
    let stars: Vec<DetectedStar> = candidates
        .into_par_iter()
        .filter(|s| s.radius <= avg + upper * stdev && s.radius >= avg - 1.5 * stdev)
        .map(|s| {
            let box_ = psf::StarBox {
                x: s.rect.x as f64,
                y: s.rect.y as f64,
                width: s.rect.width as f64,
                height: s.rect.height as f64,
            };
            let centre = (s.position.0 as f64, s.position.1 as f64);
            let fitted = psf::fit(&measured, width, height, centre, box_, s.surrounding_mean);
            DetectedStar {
                x: s.position.0,
                y: s.position.1,
                hfr: s.hfr,
                background: s.surrounding_mean,
                max_brightness: s.max_pixel,
                average_brightness: s.average,
                box_x: s.rect.x as i32,
                box_y: s.rect.y as i32,
                box_width: s.rect.width as i32,
                box_height: s.rect.height as i32,
                fwhm: fitted.map(|p| p.fwhm),
            }
        })
        .collect();
    let fwhm = median(stars.iter().filter_map(|s| s.fwhm).collect());
    let (average_hfr, hfr_std_dev) = if stars.is_empty() {
        (0.0, 0.0)
    } else {
        let mean = stars.iter().map(|s| s.hfr).sum::<f64>() / stars.len() as f64;
        let std = if stars.len() > 1 {
            (stars.iter().map(|s| (s.hfr - mean).powi(2)).sum::<f64>() / (stars.len() - 1) as f64)
                .sqrt()
        } else {
            0.0
        };
        (mean, std)
    };
    StarDetection {
        stars,
        average_hfr,
        hfr_std_dev,
        fwhm,
    }
}

/// The median, the mean of the two middle values when even, as Hocus Focus takes it; 0 when empty.
fn median(mut values: Vec<f64>) -> f64 {
    if values.is_empty() {
        return 0.0;
    }
    values.sort_by(f64::total_cmp);
    let n = values.len();
    if n.is_multiple_of(2) {
        (values[n / 2 - 1] + values[n / 2]) / 2.0
    } else {
        values[n / 2]
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::image::BayerPattern;

    /// A dark sky with noise and Gaussian stars of standard deviation `sigma`.
    fn sky(
        width: usize,
        height: usize,
        stars: &[(f64, f64)],
        sigma: f64,
        bayer: Option<BayerPattern>,
    ) -> Image {
        let mut seed = 12345u64;
        let mut noise = || {
            seed = seed
                .wrapping_mul(6364136223846793005)
                .wrapping_add(1442695040888963407);
            ((seed >> 33) % 41) as f64 - 20.0
        };
        let mut pixels = vec![0u16; width * height];
        for y in 0..height {
            for x in 0..width {
                let mut v = 1000.0 + noise();
                for &(sx, sy) in stars {
                    let d2 = (x as f64 - sx).powi(2) + (y as f64 - sy).powi(2);
                    v += 20_000.0 * (-d2 / (2.0 * sigma * sigma)).exp();
                }
                pixels[y * width + x] = v.min(65535.0) as u16;
            }
        }
        Image::new(width, height, pixels, bayer)
    }

    fn grid(width: usize, height: usize, step: usize) -> Vec<(f64, f64)> {
        let mut stars = Vec::new();
        for y in (step..height - step / 2).step_by(step) {
            for x in (step..width - step / 2).step_by(step) {
                stars.push((x as f64 + 0.3, y as f64 + 0.6));
            }
        }
        stars
    }

    #[test]
    fn it_finds_every_star_on_a_clean_sky() {
        let stars = grid(600, 400, 50);
        let image = sky(600, 400, &stars, 1.6, None);
        let result = image.detect_stars(StarSensitivity::High, None, None);
        assert_eq!(result.stars.len(), stars.len());
        for s in &result.stars {
            let nearest = stars
                .iter()
                .map(|&(x, y)| ((x - s.x as f64).powi(2) + (y - s.y as f64).powi(2)).sqrt())
                .fold(f64::MAX, f64::min);
            assert!(nearest < 0.5, "a star {nearest} px off");
        }
    }

    #[test]
    fn the_hfr_grows_with_the_star() {
        let stars = grid(600, 400, 60);
        let hfr = |sigma| {
            sky(600, 400, &stars, sigma, None)
                .detect_stars(StarSensitivity::High, None, None)
                .average_hfr
        };
        let (small, large) = (hfr(1.2), hfr(2.4));
        assert!(
            small > 0.5 && large > small * 1.5,
            "HFR {small} then {large}"
        );
    }

    #[test]
    fn a_colour_frame_is_measured_on_its_luminance() {
        let stars = grid(600, 400, 50);
        let result = sky(600, 400, &stars, 1.6, Some(BayerPattern::Grbg)).detect_stars(
            StarSensitivity::High,
            None,
            None,
        );
        assert_eq!(result.stars.len(), stars.len());
    }

    #[test]
    fn a_blank_frame_has_no_stars() {
        let image = Image::new(300, 200, vec![1000; 60_000], None);
        let result = image.detect_stars(StarSensitivity::High, None, None);
        assert!(result.stars.is_empty());
        assert_eq!(result.average_hfr, 0.0);
    }

    #[test]
    fn wide_frames_shrink_as_nina_does() {
        // The Seestar: 2.9 µm pixels at 260 mm give 2.3″/px, so High shrinks to 1552 px wide.
        let scale = 2.9 / 260.0 * ARCSEC_PER_PIXEL;
        assert!(
            (resize_factor(2160, StarSensitivity::High, Some(scale)) - 1552.0 / 2160.0).abs()
                < 1e-12
        );
        assert_eq!(resize_factor(1000, StarSensitivity::High, Some(scale)), 1.0);
        assert_eq!(
            resize_factor(6000, StarSensitivity::High, Some(1.0)),
            1.0 / 3.0
        );
        assert_eq!(resize_factor(6000, StarSensitivity::High, None), 0.5);
        assert_eq!(
            resize_factor(6000, StarSensitivity::Highest, None),
            2.0 / 3.0
        );
    }

    /// Compares with NINA on real frames, and with Hocus Focus when the tool had it: set
    /// YACANA_FITS_DIR to a folder of 16-bit FITS frames and YACANA_NINA_JSON to what
    /// `tools/nina-reference/run.sh` printed for them.
    #[test]
    #[ignore = "needs frames and NINA's numbers for them"]
    fn it_matches_nina_on_real_frames() {
        let dir = std::env::var("YACANA_FITS_DIR").expect("YACANA_FITS_DIR");
        let json =
            std::fs::read_to_string(std::env::var("YACANA_NINA_JSON").expect("YACANA_NINA_JSON"))
                .unwrap();
        let mut worst: (f64, f64) = (0.0, 0.0);
        let (mut worst_fwhm, mut fwhm_errors) = (0.0f64, Vec::new());
        for entry in json.split('{').skip(1) {
            let field = |name: &str| -> f64 {
                let at = entry.find(&format!("\"{name}\": ")).unwrap() + name.len() + 4;
                entry[at..]
                    .split([',', '}'])
                    .next()
                    .unwrap()
                    .trim()
                    .parse()
                    .unwrap()
            };
            let file_at = entry.find("\"file\": \"").unwrap() + 9;
            let file = &entry[file_at..file_at + entry[file_at..].find('"').unwrap()];
            let (image, pixel, focal) = crate::fits::read_for_tests(&format!("{dir}/{file}"));
            let stats = image.statistics();
            assert_eq!(
                (stats.median, stats.mad),
                (field("median"), field("mad")),
                "{file}"
            );
            assert!(
                (stats.mean / field("mean") - 1.0).abs() < 1e-12,
                "{file}: mean {}",
                stats.mean
            );
            assert!(
                (stats.std_dev / field("stdDev") - 1.0).abs() < 1e-9,
                "{file}: deviation {}",
                stats.std_dev
            );
            let started = std::time::Instant::now();
            let result = image.detect_stars(StarSensitivity::High, pixel, focal);
            let stars_error = (result.stars.len() as f64 / field("stars") - 1.0) * 100.0;
            let hfr_error = (result.average_hfr / field("hfr") - 1.0) * 100.0;
            // With Hocus Focus installed, the tool also gives the plugin's FWHM.
            let fwhm = if entry.contains("\"hocusFwhmPixels\"") {
                let error = (result.fwhm / field("hocusFwhmPixels") - 1.0) * 100.0;
                worst_fwhm = worst_fwhm.max(error.abs());
                fwhm_errors.push(error);
                format!(", FWHM {:.3} px ({error:+.1} %)", result.fwhm)
            } else {
                String::new()
            };
            println!(
                "{file}: {} stars ({stars_error:+.1} %), HFR {:.6} ({hfr_error:+.2e} %){fwhm}, {} ms",
                result.stars.len(),
                result.average_hfr,
                started.elapsed().as_millis()
            );
            worst.0 = worst.0.max(stars_error.abs());
            worst.1 = worst.1.max(hfr_error.abs());
        }
        println!("worst: stars {:.1} %, HFR {:.1} %", worst.0, worst.1);
        if !fwhm_errors.is_empty() {
            println!(
                "FWHM against Hocus Focus: median {:+.1} %, worst {worst_fwhm:.1} %",
                median(fwhm_errors)
            );
            assert!(
                worst_fwhm <= 5.0,
                "FWHM {worst_fwhm:.1} % from Hocus Focus's"
            );
        }
        assert!(
            worst.0 <= 5.0 && worst.1 <= 5.0,
            "worst: stars {:.1} %, HFR {:.1} %",
            worst.0,
            worst.1
        );
    }
}

#[cfg(test)]
mod hocus_comparison {
    use super::*;

    /// Fits the stars Hocus Focus found (YACANA_HOCUS_CSV, from `tools/nina-reference --stars`) in
    /// YACANA_FITS with the same centre, box and background, and compares each FWHM with the plugin's.
    #[test]
    #[ignore = "needs a frame and the plugin's stars for it"]
    fn the_fit_matches_hocus_focus_star_by_star() {
        let (image, _, _) = crate::fits::read_for_tests(&std::env::var("YACANA_FITS").unwrap());
        let luminance = psf::measurement_image(&image);
        let csv = std::fs::read_to_string(std::env::var("YACANA_HOCUS_CSV").unwrap()).unwrap();
        let mut ratios = Vec::new();
        for line in csv.lines().skip(1) {
            let f: Vec<&str> = line.split(',').collect();
            if f[8].is_empty() {
                continue;
            }
            let n = |i: usize| f[i].parse::<f64>().unwrap();
            let box_ = psf::StarBox {
                x: n(2),
                y: n(3),
                width: n(4),
                height: n(5),
            };
            let fitted = psf::fit(
                &luminance,
                image.width,
                image.height,
                (n(0), n(1)),
                box_,
                n(6) * 65535.0,
            );
            if let Some(p) = fitted {
                ratios.push(p.fwhm / n(8));
            }
        }
        let fitted = ratios.len();
        let ratio = median(ratios.clone());
        let within = ratios.iter().filter(|r| (*r - 1.0).abs() <= 0.02).count();
        println!(
            "{fitted} stars fitted; median ours / Hocus Focus {ratio:.4}; {within} within 2 %"
        );
    }
}
