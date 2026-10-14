// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

//! A star's FWHM as the Hocus Focus plugin for N.I.N.A. measures it with its default settings
//!: an elliptical Moffat profile with β = 4, fitted by least squares to samples of the
//! star's box, kept when it explains at least 90 % of their variance (R² ≥ 0.9).
//!
//! Ported from Hocus Focus 4.0's `PSFModeler.cs`, `MoffatPSFType.cs` and `HotpixelFiltering.cs` (MPL-2.0, Copyright ©
//! 2021-2026 George Hilios). The plugin solves with ALGLIB's Levenberg-Marquardt within bounds;
//! here a Levenberg-Marquardt of its own reaches the same minimum.

use rayon::prelude::*;

use crate::image::{BayerPattern, Image, nina_debayer_pixel};

/// The Moffat exponent of Hocus Focus's default fit.
const BETA: f64 = 4.0;
/// Samples across the star's box, each way.
const RESOLUTION: f64 = 10.0;
/// Values at or above this fraction of the full range are saturated and left out.
const SATURATION: f64 = 0.99;
const MIN_SAMPLES: usize = 10;
const MIN_R_SQUARED: f64 = 0.9;
const FULL_SCALE: f64 = u16::MAX as f64;
/// The plugin's hot pixel threshold: 0.001 of the full range, in ADU as it rounds it for a mosaic.
const HOT_PIXEL_THRESHOLD: f64 = 0.001;

/// The image Hocus Focus fits star profiles on with its default settings: hot pixels filtered,
/// then a colour frame debayered as N.I.N.A. does and averaged to luminance. Its filter replaces
/// any pixel that differs from the median of its same-colour neighbours by 0.001 of the range or
/// more, which also flattens the peaks of small stars; the plugin's FWHM includes that.
pub(crate) fn measurement_image(image: &Image) -> Vec<u16> {
    match image.bayer {
        Some(bayer) => {
            let mut mosaic = image.pixels.clone();
            cfa_hot_pixel_filter(&mut mosaic, image.width, image.height, bayer);
            let filtered = Image::new(image.width, image.height, mosaic, Some(bayer));
            (0..image.height)
                .into_par_iter()
                .flat_map_iter(|y| {
                    let filtered = &filtered;
                    (0..image.width).map(move |x| {
                        let [r, g, b] = nina_debayer_pixel(filtered, bayer, x, y);
                        ((r as u32 + g as u32 + b as u32) as f64 / 3.0).floor() as u16
                    })
                })
                .collect()
        }
        None => median_hot_pixel_filter(&image.pixels, image.width, image.height),
    }
}

/// The plugin's hot pixel filter on a Bayer mosaic, in place and in reading order, as it runs: each
/// pixel against the median of itself and its four diagonal neighbours of the same colour (one
/// pixel away for green, two for red and blue, fewer at the edges).
fn cfa_hot_pixel_filter(pixels: &mut [u16], width: usize, height: usize, bayer: BayerPattern) {
    let threshold = (HOT_PIXEL_THRESHOLD * 65_536.0) as u16 as i32;
    let median3 = |a: u16, b: u16, c: u16| a.min(b).max(c.min(a.max(b)));
    let median5 = |a: u16, b: u16, c: u16, d: u16, e: u16| {
        let f = a.min(b).max(c.min(d));
        let g = a.max(b).min(c.max(d));
        median3(e, f, g)
    };
    for y in 0..height {
        for x in 0..width {
            let distance = if bayer.channel(x, y) == 1 { 1 } else { 2 };
            let at = |dx: i64, dy: i64, pixels: &[u16]| {
                pixels[(y as i64 + dy) as usize * width + (x as i64 + dx) as usize]
            };
            let centre = pixels[y * width + x];
            let d = distance as i64;
            let (left, right) = (x < distance, x >= width - distance);
            let (top, bottom) = (y < distance, y >= height - distance);
            let median = match (left, right, top, bottom) {
                (true, _, true, _) => centre.min(at(d, d, pixels)),
                (true, _, _, true) => centre.min(at(d, -d, pixels)),
                (true, _, _, _) => median3(centre, at(d, -d, pixels), at(d, d, pixels)),
                (_, true, true, _) => centre.min(at(-d, d, pixels)),
                (_, true, _, true) => centre.min(at(-d, -d, pixels)),
                (_, true, _, _) => median3(centre, at(-d, -d, pixels), at(-d, d, pixels)),
                (_, _, true, _) => median3(centre, at(-d, d, pixels), at(d, d, pixels)),
                (_, _, _, true) => median3(centre, at(-d, -d, pixels), at(d, -d, pixels)),
                _ => median5(
                    centre,
                    at(-d, -d, pixels),
                    at(d, -d, pixels),
                    at(-d, d, pixels),
                    at(d, d, pixels),
                ),
            };
            if (median as i32 - centre as i32).abs() >= threshold {
                pixels[y * width + x] = median;
            }
        }
    }
}

/// The plugin's hot pixel filter on a mono frame: a pixel that differs from the median of its 3 × 3
/// neighbourhood (edges repeated) by more than the threshold takes that median.
fn median_hot_pixel_filter(pixels: &[u16], width: usize, height: usize) -> Vec<u16> {
    let threshold = HOT_PIXEL_THRESHOLD * FULL_SCALE;
    let mut out = vec![0u16; width * height];
    out.par_chunks_mut(width).enumerate().for_each(|(y, row)| {
        for (x, value) in row.iter_mut().enumerate() {
            let mut window = [0u16; 9];
            let mut k = 0;
            for dy in -1i64..=1 {
                for dx in -1i64..=1 {
                    let yy = (y as i64 + dy).clamp(0, height as i64 - 1) as usize;
                    let xx = (x as i64 + dx).clamp(0, width as i64 - 1) as usize;
                    window[k] = pixels[yy * width + xx];
                    k += 1;
                }
            }
            window.sort_unstable();
            let centre = pixels[y * width + x];
            *value = if (centre as f64 - window[4] as f64).abs() > threshold {
                window[4]
            } else {
                centre
            };
        }
    });
    out
}

/// The fitted profile: its FWHM along its major and minor axes and their geometric mean, in pixels.
#[derive(Debug, Clone, Copy, PartialEq)]
pub(crate) struct Psf {
    pub(crate) fwhm_x: f64,
    pub(crate) fwhm_y: f64,
    pub(crate) fwhm: f64,
    pub(crate) r_squared: f64,
}

/// The box a star was measured in, in image pixels.
#[derive(Debug, Clone, Copy)]
pub(crate) struct StarBox {
    pub(crate) x: f64,
    pub(crate) y: f64,
    pub(crate) width: f64,
    pub(crate) height: f64,
}

/// Fits the profile of the star centred at `centre` in `box_` of `image` (`width` pixels wide),
/// over a local `background` in ADU. None when there are too few unsaturated samples, the fit
/// fails, or it explains less than 90 % of the variance.
pub(crate) fn fit(
    image: &[u16],
    width: usize,
    height: usize,
    centre: (f64, f64),
    box_: StarBox,
    background: f64,
) -> Option<Psf> {
    let sample = |x: f64, y: f64| bilinear(image, width, height, x, y) / FULL_SCALE;
    let background = background / FULL_SCALE;
    let nominal = (box_.width * box_.height).sqrt();
    let step = nominal / RESOLUTION;
    if step <= 0.0 {
        return None;
    }
    let start_x = centre.0 - step * ((centre.0 - box_.x) / step).floor();
    let start_y = centre.1 - step * ((centre.1 - box_.y) / step).floor();
    let (end_x, end_y) = (box_.x + box_.width, box_.y + box_.height);
    let mut inputs = Vec::new();
    let mut outputs = Vec::new();
    let mut y = start_y;
    while y < end_y {
        let mut x = start_x;
        while x < end_x {
            let value = sample(x, y);
            if value < SATURATION {
                inputs.push((x - centre.0, y - centre.1));
                outputs.push(value);
            }
            x += step;
        }
        y += step;
    }
    if inputs.len() < MIN_SAMPLES {
        return None;
    }
    let centroid = sample(centre.0, centre.1);
    let model = Moffat {
        inputs: &inputs,
        outputs: &outputs,
    };
    let (sigma_x, sigma_y) = model.second_moment_sigmas(background, box_);
    let (mut sigma_x, sigma_y) = if sigma_x < sigma_y {
        (sigma_y, sigma_x)
    } else {
        (sigma_x, sigma_y)
    };
    // As the plugin: a seed just off the circle keeps the major axis on x.
    sigma_x *= 1.001;
    let sigma_max = (box_.width * box_.width + box_.height * box_.height).sqrt() / 2.0;
    let initial = [
        (centroid - background).max(0.0),
        background,
        0.0,
        0.0,
        sigma_x,
        sigma_y,
        0.0,
    ];
    let lower = [
        0.0,
        0.0,
        -box_.width / 2.0,
        -box_.height / 2.0,
        0.0,
        0.0,
        -std::f64::consts::FRAC_PI_2,
    ];
    let upper = [
        2.0,
        1.0,
        box_.width / 2.0,
        box_.height / 2.0,
        sigma_max,
        sigma_max,
        std::f64::consts::FRAC_PI_2,
    ];
    let solution = levenberg_marquardt(&model, initial, lower, upper)?;
    let (mut sx, mut sy) = (solution[4], solution[5]);
    if sx.is_nan() || sy.is_nan() {
        return None;
    }
    if sy > sx {
        std::mem::swap(&mut sx, &mut sy);
    }
    let r_squared = model.r_squared(&solution);
    if r_squared.is_nan() || r_squared < MIN_R_SQUARED {
        return None;
    }
    let to_fwhm = |sigma: f64| sigma * 2.0 * (2f64.powf(1.0 / BETA) - 1.0).sqrt();
    let (fwhm_x, fwhm_y) = (to_fwhm(sx), to_fwhm(sy));
    Some(Psf {
        fwhm_x,
        fwhm_y,
        fwhm: (fwhm_x * fwhm_y).sqrt(),
        r_squared,
    })
}

/// The image's value at `x`, `y` between pixel centres, as OpenCV's bilinear sampling in the plugin.
fn bilinear(image: &[u16], width: usize, height: usize, x: f64, y: f64) -> f64 {
    let x0 = (x.floor().max(0.0) as usize).min(width - 1);
    let y0 = (y.floor().max(0.0) as usize).min(height - 1);
    let x1 = (x0 + 1).min(width - 1);
    let y1 = (y0 + 1).min(height - 1);
    let (fx, fy) = (x - x0 as f64, y - y0 as f64);
    let p = |x: usize, y: usize| image[y * width + x] as f64;
    let top = p(x0, y0) + fx * (p(x1, y0) - p(x0, y0));
    let bottom = p(x0, y1) + fx * (p(x1, y1) - p(x0, y1));
    top + fy * (bottom - top)
}

/// The elliptical Moffat profile over the samples. Parameters: amplitude A, background B, centre
/// offset x0 and y0, widths U and V along the rotated axes, and the angle θ.
struct Moffat<'a> {
    inputs: &'a [(f64, f64)],
    outputs: &'a [f64],
}

impl Moffat<'_> {
    fn value(p: &[f64; 7], (x, y): (f64, f64)) -> f64 {
        let (cos, sin) = (p[6].cos(), p[6].sin());
        let rx = (x - p[2]) * cos + (y - p[3]) * sin;
        let ry = -(x - p[2]) * sin + (y - p[3]) * cos;
        let d = 1.0 + rx * rx / (p[4] * p[4]) + ry * ry / (p[5] * p[5]);
        p[1] + p[0] / d.powf(BETA)
    }

    /// The partial derivatives of the profile at one sample, as the plugin derives them.
    fn gradient(p: &[f64; 7], (x, y): (f64, f64)) -> [f64; 7] {
        let (a, u, v) = (p[0], p[4], p[5]);
        let (cos, sin) = (p[6].cos(), p[6].sin());
        let rx = (x - p[2]) * cos + (y - p[3]) * sin;
        let ry = -(x - p[2]) * sin + (y - p[3]) * cos;
        let (rx2, ry2, u2, v2) = (rx * rx, ry * ry, u * u, v * v);
        let d = rx2 / u2 + ry2 / v2 + 1.0;
        let d_beta1 = d.powf(-BETA - 1.0);
        let d_x0 = -a * BETA * (2.0 * sin * ry / v2 - 2.0 * cos * rx / u2) * d_beta1;
        let d_y0 = -a * BETA * (-2.0 * sin * rx / u2 - 2.0 * cos * ry / v2) * d_beta1;
        let rxy2 = 2.0 * ry * rx;
        let d_theta = -a * BETA * (rxy2 / u2 - rxy2 / v2) * d_beta1;
        let ab2 = 2.0 * a * BETA;
        let d_u = ab2 / (u2 * u) * rx2 * d_beta1;
        let d_v = ab2 / (v2 * v) * ry2 * d_beta1;
        [d.powf(-BETA), 1.0, d_x0, d_y0, d_u, d_v, d_theta]
    }

    /// The seed widths from the second moments of the light above the background, as the plugin
    /// takes them, kept between 0.5 and the box's longer side.
    fn second_moment_sigmas(&self, background: f64, box_: StarBox) -> (f64, f64) {
        let (mut sum, mut sx2, mut sy2) = (0.0, 0.0, 0.0);
        for (&(dx, dy), &value) in self.inputs.iter().zip(self.outputs) {
            let weight = value - background;
            if weight > 0.0 {
                sum += weight;
                sx2 += weight * dx * dx;
                sy2 += weight * dy * dy;
            }
        }
        let (fallback_x, fallback_y) = (box_.width / 3.0, box_.height / 3.0);
        let (sx, sy) = if sum <= 0.0 || sx2 <= 0.0 || sy2 <= 0.0 {
            (fallback_x, fallback_y)
        } else {
            ((sx2 / sum).sqrt(), (sy2 / sum).sqrt())
        };
        let max = box_.width.max(box_.height);
        (sx.clamp(0.5, max.max(0.5)), sy.clamp(0.5, max.max(0.5)))
    }

    fn cost(&self, p: &[f64; 7]) -> f64 {
        self.inputs
            .iter()
            .zip(self.outputs)
            .map(|(&input, &observed)| (Self::value(p, input) - observed).powi(2))
            .sum()
    }

    fn r_squared(&self, p: &[f64; 7]) -> f64 {
        let mean = self.outputs.iter().sum::<f64>() / self.outputs.len() as f64;
        let total: f64 = self.outputs.iter().map(|o| (o - mean).powi(2)).sum();
        1.0 - self.cost(p) / total
    }
}

#[allow(clippy::needless_range_loop)] // Small dense matrices read best with indices.
/// Levenberg-Marquardt within bounds: each step solves the damped normal equations and is pulled
/// back inside the bounds; it stops when the steps become negligible.
fn levenberg_marquardt(
    model: &Moffat,
    initial: [f64; 7],
    lower: [f64; 7],
    upper: [f64; 7],
) -> Option<[f64; 7]> {
    const MAX_ITERATIONS: usize = 500;
    // The plugin's variable scales, for when a step counts as negligible.
    const SCALE: [f64; 7] = [0.01, 0.01, 0.1, 0.1, 1.0, 1.0, 1.0];
    const TOLERANCE: f64 = 1e-8;
    let mut p = initial;
    for i in 0..7 {
        p[i] = p[i].clamp(lower[i], upper[i]);
    }
    let mut cost = model.cost(&p);
    if !cost.is_finite() {
        return None;
    }
    let mut lambda = 1e-3;
    for _ in 0..MAX_ITERATIONS {
        let mut jtj = [[0.0f64; 7]; 7];
        let mut jtr = [0.0f64; 7];
        for (&input, &observed) in model.inputs.iter().zip(model.outputs) {
            let residual = Moffat::value(&p, input) - observed;
            let g = Moffat::gradient(&p, input);
            for i in 0..7 {
                jtr[i] += g[i] * residual;
                for j in 0..=i {
                    jtj[i][j] += g[i] * g[j];
                }
            }
        }
        for i in 0..7 {
            for j in 0..i {
                jtj[j][i] = jtj[i][j];
            }
        }
        let mut improved = false;
        while lambda < 1e16 {
            let mut a = jtj;
            for (i, row) in a.iter_mut().enumerate() {
                let diagonal = if jtj[i][i] > 0.0 {
                    jtj[i][i]
                } else {
                    1.0 / (SCALE[i] * SCALE[i])
                };
                row[i] += lambda * diagonal;
            }
            let Some(delta) = solve(a, jtr.map(|v| -v)) else {
                lambda *= 10.0;
                continue;
            };
            let mut candidate = p;
            for i in 0..7 {
                candidate[i] = (p[i] + delta[i]).clamp(lower[i], upper[i]);
            }
            let candidate_cost = model.cost(&candidate);
            if candidate_cost.is_finite() && candidate_cost <= cost {
                let step = (0..7)
                    .map(|i| ((candidate[i] - p[i]) / SCALE[i]).abs())
                    .fold(0.0, f64::max);
                p = candidate;
                let previous = cost;
                cost = candidate_cost;
                lambda = (lambda / 10.0).max(1e-12);
                improved = true;
                if step <= TOLERANCE || previous - cost <= previous * 1e-15 {
                    return Some(p);
                }
                break;
            }
            lambda *= 10.0;
        }
        if !improved {
            return Some(p);
        }
    }
    Some(p)
}

#[allow(clippy::needless_range_loop)]
/// Solves `a x = b` for a symmetric 7 × 7 system by Gaussian elimination with partial pivoting.
fn solve(mut a: [[f64; 7]; 7], mut b: [f64; 7]) -> Option<[f64; 7]> {
    for col in 0..7 {
        let pivot = (col..7).max_by(|&i, &j| a[i][col].abs().total_cmp(&a[j][col].abs()))?;
        if a[pivot][col].abs() < 1e-300 {
            return None;
        }
        a.swap(col, pivot);
        b.swap(col, pivot);
        for row in col + 1..7 {
            let factor = a[row][col] / a[col][col];
            for k in col..7 {
                a[row][k] -= factor * a[col][k];
            }
            b[row] -= factor * b[col];
        }
    }
    let mut x = [0.0; 7];
    for row in (0..7).rev() {
        let sum: f64 = (row + 1..7).map(|k| a[row][k] * x[k]).sum();
        x[row] = (b[row] - sum) / a[row][row];
    }
    x.iter().all(|v| v.is_finite()).then_some(x)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A Moffat star with β = 4 and the given FWHM along x and y, on a flat background.
    fn star(fwhm_x: f64, fwhm_y: f64) -> (Vec<u16>, usize, usize) {
        let (w, h) = (40, 40);
        let alpha = |fwhm: f64| fwhm / (2.0 * (2f64.powf(1.0 / BETA) - 1.0).sqrt());
        let (ax, ay) = (alpha(fwhm_x), alpha(fwhm_y));
        let pixels = (0..w * h)
            .map(|i| {
                let (x, y) = ((i % w) as f64 - 20.3, (i / w) as f64 - 19.6);
                let d = 1.0 + x * x / (ax * ax) + y * y / (ay * ay);
                (1500.0 + 30_000.0 / d.powf(BETA)) as u16
            })
            .collect();
        (pixels, w, h)
    }

    fn whole_box() -> StarBox {
        StarBox {
            x: 8.0,
            y: 8.0,
            width: 24.0,
            height: 24.0,
        }
    }

    // Sampling between pixel centres smooths the peak a little, so the fit comes out about 3 %
    // wide on a 5-pixel star, as in the plugin, which samples the same way.
    #[test]
    fn a_round_star_gives_its_fwhm() {
        let (pixels, w, h) = star(5.0, 5.0);
        let psf = fit(&pixels, w, h, (20.3, 19.6), whole_box(), 1500.0).expect("a fit");
        assert!((psf.fwhm / 5.0 - 1.0).abs() < 0.04, "FWHM {}", psf.fwhm);
        assert!(psf.r_squared > 0.99);
    }

    #[test]
    fn an_elongated_star_gives_both_axes() {
        let (pixels, w, h) = star(6.0, 4.0);
        let psf = fit(&pixels, w, h, (20.3, 19.6), whole_box(), 1500.0).expect("a fit");
        assert!(
            (psf.fwhm_x / 6.0 - 1.0).abs() < 0.04 && (psf.fwhm_y / 4.0 - 1.0).abs() < 0.05,
            "{psf:?}"
        );
        assert!(psf.fwhm_x > psf.fwhm_y);
    }

    #[test]
    fn noise_is_not_a_star() {
        let mut seed = 7u64;
        let pixels: Vec<u16> = (0..40 * 40)
            .map(|_| {
                seed = seed
                    .wrapping_mul(6364136223846793005)
                    .wrapping_add(1442695040888963407);
                1500 + ((seed >> 33) % 60) as u16
            })
            .collect();
        assert_eq!(
            fit(&pixels, 40, 40, (20.0, 20.0), whole_box(), 1530.0),
            None
        );
    }
}

#[cfg(test)]
mod hocus_image {
    use super::*;

    /// Writes the image Hocus Focus measures on for YACANA_FITS as raw 16-bit values at YACANA_OUT,
    /// to compare with the plugin's own `02-src-image-preparation.tif`.
    #[test]
    #[ignore = "writes an image to compare with the plugin's"]
    fn write_the_measurement_image() {
        let (image, _, _) = crate::fits::read_for_tests(&std::env::var("YACANA_FITS").unwrap());
        let measured = measurement_image(&image);
        let bytes: Vec<u8> = measured.iter().flat_map(|v| v.to_le_bytes()).collect();
        std::fs::write(std::env::var("YACANA_OUT").unwrap(), bytes).unwrap();
    }
}
