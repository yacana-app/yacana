// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

//! Screen-sized previews of a region of a frame, stretched and debayered (ARCHITECTURE.md).

use rayon::prelude::*;

use crate::image::{ColourStretch, Image, Stretch, nina_debayer_pixel, stretch_value};

/// A rectangle of the image, in image pixels from the top-left corner. It may reach outside the
/// image, which then draws black.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct Region {
    pub x: f64,
    pub y: f64,
    pub width: f64,
    pub height: f64,
}

/// Screen pixels, row by row, four bytes each: red, green, blue and alpha.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct PreviewImage {
    pub width: u32,
    pub height: u32,
    pub rgba: Vec<u8>,
}

pub(crate) fn render(
    image: &Image,
    stretch: ColourStretch,
    region: Region,
    width: usize,
    height: usize,
) -> PreviewImage {
    let luts = [stretch.red, stretch.green, stretch.blue].map(lookup_table);
    let scale_x = region.width / width.max(1) as f64;
    let scale_y = region.height / height.max(1) as f64;
    let mut rgba = vec![0u8; width * height * 4];
    rgba.par_chunks_mut(width.max(1) * 4)
        .enumerate()
        .for_each(|(out_y, row)| {
            let y0 = region.y + out_y as f64 * scale_y;
            for out_x in 0..width {
                let x0 = region.x + out_x as f64 * scale_x;
                let pixel = &mut row[out_x * 4..out_x * 4 + 4];
                match sample(image, x0, y0, x0 + scale_x, y0 + scale_y) {
                    Some([r, g, b]) => pixel.copy_from_slice(&[
                        luts[0][r as usize],
                        luts[1][g as usize],
                        luts[2][b as usize],
                        255,
                    ]),
                    None => pixel.copy_from_slice(&[0, 0, 0, 255]),
                }
            }
        });
    PreviewImage {
        width: width as u32,
        height: height as u32,
        rgba,
    }
}

/// The 8-bit screen value of every 16-bit pixel value under `stretch`: NINA's stretched 16-bit
/// value, cut to its high byte.
fn lookup_table(stretch: Stretch) -> Vec<u8> {
    let (shadows, midtones, highlights) = (
        stretch.shadows as f64,
        stretch.midtones as f64,
        stretch.highlights as f64,
    );
    (0..=u16::MAX)
        .map(|value| (stretch_value(shadows, midtones, highlights, value) >> 8) as u8)
        .collect()
}

/// The colour of the image under the footprint `x0..x1` × `y0..y1`, or None outside the image.
///
/// - Zoomed out, the footprint covers at least a whole Bayer cell, so each channel is the
///   average of its own pixels in it: a debayer and a downscale in one step.
/// - Zoomed in, each screen pixel shows one image pixel, its missing channels taken from the
///   neighbours of that colour.
fn sample(image: &Image, x0: f64, y0: f64, x1: f64, y1: f64) -> Option<[u16; 3]> {
    let (w, h) = (image.width as f64, image.height as f64);
    if x1 <= 0.0 || y1 <= 0.0 || x0 >= w || y0 >= h {
        return None;
    }
    let ix0 = x0.max(0.0).floor() as usize;
    let iy0 = y0.max(0.0).floor() as usize;
    let ix1 = (x1.min(w).ceil() as usize).max(ix0 + 1);
    let iy1 = (y1.min(h).ceil() as usize).max(iy0 + 1);
    let colour = match image.bayer {
        None => {
            let value = average(image, ix0, iy0, ix1, iy1, |_, _| true);
            [value, value, value]
        }
        Some(pattern) if ix1 - ix0 >= 2 && iy1 - iy0 >= 2 => [0, 1, 2].map(|channel| {
            average(image, ix0, iy0, ix1, iy1, |x, y| {
                pattern.channel(x, y) == channel
            })
        }),
        Some(_) => demosaic(image, (ix0 + ix1) / 2, (iy0 + iy1) / 2),
    };
    Some(colour)
}

/// The average of the pixels in `x0..x1` × `y0..y1` that `keep` accepts.
fn average(
    image: &Image,
    x0: usize,
    y0: usize,
    x1: usize,
    y1: usize,
    keep: impl Fn(usize, usize) -> bool,
) -> u16 {
    let (mut sum, mut count) = (0u64, 0u64);
    for y in y0..y1 {
        let row = &image.pixels[y * image.width..(y + 1) * image.width];
        for (x, &value) in row.iter().enumerate().take(x1).skip(x0) {
            if keep(x, y) {
                sum += value as u64;
                count += 1;
            }
        }
    }
    sum.checked_div(count).unwrap_or(0) as u16
}

/// The colour of one pixel of a Bayer mosaic, debayered as N.I.N.A. does, so a frame looks the
/// same at full size.
fn demosaic(image: &Image, x: usize, y: usize) -> [u16; 3] {
    nina_debayer_pixel(image, image.bayer.expect("a Bayer pattern"), x, y)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::image::BayerPattern;

    fn pixel(preview: &PreviewImage, x: usize, y: usize) -> [u8; 4] {
        let i = (y * preview.width as usize + x) * 4;
        preview.rgba[i..i + 4].try_into().unwrap()
    }

    #[test]
    fn a_linear_stretch_maps_the_full_range() {
        let lut = lookup_table(Stretch::LINEAR);
        assert_eq!((lut[0], lut[u16::MAX as usize]), (0, 255));
        assert_eq!(lut[32768], 128);
    }

    #[test]
    fn values_below_the_shadows_are_black() {
        let lut = lookup_table(Stretch {
            shadows: 0.25,
            midtones: 0.5,
            highlights: 1.0,
        });
        assert_eq!(lut[10_000], 0);
        // As NINA, the rest moves down by the shadows instead of being spread over the range.
        // 60 000 / 65 535 - 0.25 = 0.6655, 43 615 in 16 bits, whose high byte is 170.
        assert_eq!(lut[60_000], 170);
    }

    #[test]
    fn zoomed_out_a_bayer_cell_becomes_one_colour() {
        // One GRBG cell: green 100, red 40000, blue 20000, green 300.
        let image = Image::new(
            2,
            2,
            vec![100, 40_000, 20_000, 300],
            Some(BayerPattern::Grbg),
        );
        let region = Region {
            x: 0.0,
            y: 0.0,
            width: 2.0,
            height: 2.0,
        };
        let preview = render(&image, ColourStretch::linked(Stretch::LINEAR), region, 1, 1);
        // Green averages 200, whose high byte is 0, as NINA cuts 16 bits to 8.
        assert_eq!(pixel(&preview, 0, 0), [156, 0, 78, 255]);
    }

    #[test]
    fn zoomed_in_each_pixel_takes_its_missing_channels_from_its_neighbours() {
        // A red pixel in the middle of an RGGB mosaic, with blue corners and green sides.
        #[rustfmt::skip]
        let image = Image::new(3, 3, vec![
            65535, 0, 65535,
            0, 32768, 0,
            65535, 0, 65535,
        ], Some(BayerPattern::Bggr));
        assert_eq!(demosaic(&image, 1, 1), [32768, 0, 65535]);
    }

    #[test]
    fn a_mono_image_is_grey_and_outside_is_black() {
        let image = Image::new(2, 1, vec![0, 65535], None);
        let region = Region {
            x: -1.0,
            y: 0.0,
            width: 3.0,
            height: 1.0,
        };
        let preview = render(&image, ColourStretch::linked(Stretch::LINEAR), region, 3, 1);
        assert_eq!(pixel(&preview, 0, 0), [0, 0, 0, 255]);
        assert_eq!(pixel(&preview, 2, 0), [255, 255, 255, 255]);
        assert_eq!(preview.rgba.len(), 3 * 4);
    }
}

#[cfg(test)]
mod comparison {
    use crate::image::Image;
    use crate::preview::Region;

    /// Writes YACANA_FITS as the viewer draws it at full size, with the automatic stretch, as a PPM
    /// at YACANA_OUT, to compare with how NINA shows it (`tools/nina-reference --image`).
    #[test]
    #[ignore = "writes an image for a person to compare"]
    fn render_as_the_viewer_does() {
        let path = std::env::var("YACANA_FITS").expect("YACANA_FITS");
        let (image, _, _): (Image, _, _) = crate::fits::read_for_tests(&path);
        let (w, h) = (image.width as u32, image.height as u32);
        let full = Region {
            x: 0.0,
            y: 0.0,
            width: w as f64,
            height: h as f64,
        };
        let preview = image.preview(image.auto_stretch(), full, w, h);
        let mut out = format!("P6\n{} {}\n255\n", preview.width, preview.height).into_bytes();
        for pixel in preview.rgba.chunks(4) {
            out.extend_from_slice(&pixel[..3]);
        }
        std::fs::write(std::env::var("YACANA_OUT").expect("YACANA_OUT"), out).unwrap();
    }
}
