// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

//! A frame in native memory, with its statistics and the automatic stretch.
//!
//! The statistics (median and MAD), the automatic stretch and the debayering it shows through are
//! ported from N.I.N.A.'s `ImageStatistics.cs`, `ImageUtility.cs` and `BayerFilter16bpp.cs`
//! (MPL-2.0, Copyright © 2016-2026 Stefan Berg and the N.I.N.A. contributors).

use rayon::prelude::*;

use crate::preview::{self, PreviewImage, Region};

/// The colour filter pattern from the top-left pixel, as `CameraInfo.bayerPattern` gives it.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum BayerPattern {
    Rggb,
    Grbg,
    Gbrg,
    Bggr,
}

impl BayerPattern {
    /// The channel (0 red, 1 green, 2 blue) of the pixel at `x`, `y`.
    pub(crate) fn channel(self, x: usize, y: usize) -> usize {
        let (column, row) = (x & 1, y & 1);
        let (red, blue) = match self {
            BayerPattern::Rggb => ((0, 0), (1, 1)),
            BayerPattern::Grbg => ((1, 0), (0, 1)),
            BayerPattern::Gbrg => ((0, 1), (1, 0)),
            BayerPattern::Bggr => ((1, 1), (0, 0)),
        };
        if (column, row) == red {
            0
        } else if (column, row) == blue {
            2
        } else {
            1
        }
    }
}

/// How to map pixel values to the screen, as N.I.N.A. does, with values as fractions of the full
/// 16-bit range: a value becomes the midtones transfer function of `1 - highlights + value -
/// shadows`, so values below `shadows` are black and `midtones` is the balance that goes to 50 %
/// (0.5 is linear). `highlights` is 1 except for a frame brighter than half the range.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct Stretch {
    pub shadows: f32,
    pub midtones: f32,
    pub highlights: f32,
}

/// A stretch for each channel: the same three for a mono frame or a linked stretch, and one per
/// channel for N.I.N.A.'s unlinked stretch of a colour frame.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct ColourStretch {
    pub red: Stretch,
    pub green: Stretch,
    pub blue: Stretch,
}

impl ColourStretch {
    /// The same stretch for every channel.
    pub fn linked(stretch: Stretch) -> ColourStretch {
        ColourStretch {
            red: stretch,
            green: stretch,
            blue: stretch,
        }
    }
}

impl Stretch {
    /// No stretch: the values as they are.
    pub const LINEAR: Stretch = Stretch {
        shadows: 0.0,
        midtones: 0.5,
        highlights: 1.0,
    };
}

/// Statistics of all the pixels, mosaic included, in ADU.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct Statistics {
    pub min: u16,
    pub max: u16,
    pub mean: f64,
    pub median: f64,
    /// Median absolute deviation from the median.
    pub mad: f64,
    pub std_dev: f64,
}

/// One frame. Pixels live here and never cross into Kotlin (ARCHITECTURE.md).
#[derive(Debug, uniffi::Object)]
pub struct Image {
    pub(crate) width: usize,
    pub(crate) height: usize,
    /// Row by row from the top-left corner.
    pub(crate) pixels: Vec<u16>,
    pub(crate) bayer: Option<BayerPattern>,
}

impl Image {
    pub(crate) fn new(
        width: usize,
        height: usize,
        pixels: Vec<u16>,
        bayer: Option<BayerPattern>,
    ) -> Image {
        assert_eq!(pixels.len(), width * height, "pixel count");
        Image {
            width,
            height,
            pixels,
            bayer,
        }
    }

    /// An image from pixels already in Rust, row by row. Not exported: Kotlin never holds pixels.
    pub fn from_pixels(
        width: usize,
        height: usize,
        pixels: Vec<u16>,
        bayer: Option<BayerPattern>,
    ) -> Image {
        Image::new(width, height, pixels, bayer)
    }

    /// How many pixels have each value.
    fn value_counts(&self) -> Vec<u64> {
        self.pixels
            .par_chunks(1 << 16)
            .fold(
                || vec![0u64; 1 << 16],
                |mut counts, chunk| {
                    for &value in chunk {
                        counts[value as usize] += 1;
                    }
                    counts
                },
            )
            .reduce(
                || vec![0u64; 1 << 16],
                |mut a, b| {
                    a.iter_mut().zip(b).for_each(|(x, y)| *x += y);
                    a
                },
            )
    }
}

/// NINA's median and median absolute deviation, from the counts of each 16-bit value. The MAD
/// grows a window around the median one value at a time on both sides, as NINA does.
pub(crate) fn nina_median_mad(counts: &[u64], total: u64) -> (f64, f64) {
    let half = total as f64 / 2.0;
    let (mut median1, mut median2) = (0usize, 0usize);
    let mut seen = 0u64;
    for i in 0..(counts.len() - 1) {
        seen += counts[i];
        if seen as f64 > half {
            median1 = i;
            median2 = i;
            break;
        } else if seen as f64 == half {
            median1 = i;
            median2 = (i + 1..counts.len()).find(|&j| counts[j] > 0).unwrap_or(0);
            break;
        }
    }
    let median = (median1 + median2) as f64 / 2.0;
    let mut mad = 0.0;
    let mut seen = 0u64;
    let (mut down, mut up) = (median1 as i64, median2);
    loop {
        if down >= 0 && down as usize != up {
            seen += counts[down as usize] + counts[up];
        } else {
            seen += counts[up];
        }
        if seen as f64 > half {
            mad = (up as f64 - median).abs();
            break;
        }
        up += 1;
        down -= 1;
        if up >= counts.len() {
            break;
        }
    }
    (median, mad)
}

/// The midtones transfer function: maps `x` in 0..1 so that `midtones` goes to 0.5.
pub(crate) fn mtf(midtones: f64, x: f64) -> f64 {
    if x <= 0.0 {
        0.0
    } else if x >= 1.0 {
        1.0
    } else {
        ((midtones - 1.0) * x) / ((2.0 * midtones - 1.0) * x - midtones)
    }
}

/// N.I.N.A.'s defaults: the background goes to 20 % grey, and black is clipped 2.8 MADs
/// (normalised to a standard deviation) below the median.
const TARGET_BACKGROUND: f64 = 0.2;
const SHADOWS_CLIPPING: f64 = -2.8;
const MAD_TO_SIGMA: f64 = 1.4826;
const FULL_SCALE: f64 = u16::MAX as f64;

/// The 16-bit full scale, for values kept as floats, as a stack's.
pub(crate) const FULL_SCALE_U16: f32 = u16::MAX as f32;

/// The 8-bit screen value of every 16-bit value under `stretch`: NINA's stretched value, cut to its high byte.
pub(crate) fn mtf_lookup(stretch: NinaStretch) -> Vec<u8> {
    (0..=u16::MAX)
        .map(|value| (stretch.apply(value) >> 8) as u8)
        .collect()
}

/// N.I.N.A.'s automatic stretch of one channel in full precision, as `ImageUtility.GetStretchMap`
/// computes it from the channel's median and MAD.
#[derive(Debug, Clone, Copy, PartialEq)]
pub(crate) struct NinaStretch {
    pub(crate) shadows: f64,
    pub(crate) midtones: f64,
    pub(crate) highlights: f64,
}

impl NinaStretch {
    pub(crate) fn from_counts(counts: &[u64], total: u64) -> NinaStretch {
        let (median, mad) = nina_median_mad(counts, total);
        let (median, mad) = (median / FULL_SCALE, mad / FULL_SCALE);
        if median > 0.5 {
            // NINA takes such a frame as inverted or overexposed.
            let highlights = median - SHADOWS_CLIPPING * mad * MAD_TO_SIGMA;
            NinaStretch {
                shadows: 0.0,
                midtones: mtf(TARGET_BACKGROUND, 1.0 - (highlights - median)),
                highlights,
            }
        } else {
            let shadows = median + SHADOWS_CLIPPING * mad * MAD_TO_SIGMA;
            NinaStretch {
                shadows,
                midtones: mtf(TARGET_BACKGROUND, median - shadows),
                highlights: 1.0,
            }
        }
    }

    /// The stretched 16-bit value of `value`.
    pub(crate) fn apply(&self, value: u16) -> u16 {
        stretch_value(self.shadows, self.midtones, self.highlights, value)
    }

    fn to_stretch(self) -> Stretch {
        Stretch {
            shadows: self.shadows as f32,
            midtones: self.midtones as f32,
            highlights: self.highlights as f32,
        }
    }
}

/// N.I.N.A.'s stretched 16-bit value: the midtones function of `1 - highlights + value - shadows`.
pub(crate) fn stretch_value(shadows: f64, midtones: f64, highlights: f64, value: u16) -> u16 {
    let x = value as f64 / FULL_SCALE;
    let y = mtf(midtones, 1.0 - highlights + x - shadows);
    (y * FULL_SCALE + if y < 0.5 { 0.5 } else { 0.0 }) as u16
}

/// N.I.N.A.'s debayer of one pixel (`BayerFilter16bpp`): each channel is the mean of the pixels
/// of that colour in the 3 × 3 square around it, itself included, rounded down.
pub(crate) fn nina_debayer_pixel(
    image: &Image,
    bayer: BayerPattern,
    x: usize,
    y: usize,
) -> [u16; 3] {
    let mut values = [0u32; 3];
    let mut counts = [0u32; 3];
    for yy in y.saturating_sub(1)..=(y + 1).min(image.height - 1) {
        for xx in x.saturating_sub(1)..=(x + 1).min(image.width - 1) {
            let channel = bayer.channel(xx, yy);
            values[channel] += image.pixels[yy * image.width + xx] as u32;
            counts[channel] += 1;
        }
    }
    [0, 1, 2].map(|c| (values[c] / counts[c]) as u16)
}

/// How many pixels of each channel of the frame debayered as NINA does have each value.
fn debayered_counts(image: &Image, bayer: BayerPattern) -> [Vec<u64>; 3] {
    let empty = || {
        [
            vec![0u64; 1 << 16],
            vec![0u64; 1 << 16],
            vec![0u64; 1 << 16],
        ]
    };
    (0..image.height)
        .into_par_iter()
        .fold(empty, |mut counts, y| {
            for x in 0..image.width {
                let colour = nina_debayer_pixel(image, bayer, x, y);
                for (c, &value) in colour.iter().enumerate() {
                    counts[c][value as usize] += 1;
                }
            }
            counts
        })
        .reduce(empty, |mut a, b| {
            for (x, y) in a.iter_mut().zip(b) {
                x.iter_mut().zip(y).for_each(|(p, q)| *p += q);
            }
            a
        })
}

#[uniffi::export]
impl Image {
    pub fn width(&self) -> u32 {
        self.width as u32
    }

    pub fn height(&self) -> u32 {
        self.height as u32
    }

    pub fn bayer(&self) -> Option<BayerPattern> {
        self.bayer
    }

    /// Statistics of every pixel as NINA computes them: the median from a histogram of all 65,536
    /// values, and NINA's median absolute deviation.
    pub fn statistics(&self) -> Statistics {
        let counts = self.value_counts();
        let total = self.pixels.len() as u64;
        let min = counts.iter().position(|&c| c > 0).unwrap_or(0);
        let max = counts.iter().rposition(|&c| c > 0).unwrap_or(0);
        let sum: f64 = counts
            .iter()
            .enumerate()
            .map(|(v, &c)| v as f64 * c as f64)
            .sum();
        let mean = sum / total as f64;
        let variance = counts
            .iter()
            .enumerate()
            .map(|(v, &c)| (v as f64 - mean).powi(2) * c as f64)
            .sum::<f64>()
            / total as f64;
        let (median, mad) = nina_median_mad(&counts, total);
        Statistics {
            min: min as u16,
            max: max as u16,
            mean,
            median,
            mad,
            std_dev: variance.sqrt(),
        }
    }

    /// N.I.N.A.'s automatic stretch as its Imaging tab applies it by default: shadows 2.8
    /// normalised MADs below the median and midtones that put the median at 20 % grey. A colour
    /// frame is debayered and each channel gets its own stretch (unlinked), which keeps the
    /// background neutral; a mono frame gets one.
    pub fn auto_stretch(&self) -> ColourStretch {
        let total = self.pixels.len() as u64;
        match self.bayer {
            None => ColourStretch::linked(
                NinaStretch::from_counts(&self.value_counts(), total).to_stretch(),
            ),
            Some(bayer) => {
                let [red, green, blue] = debayered_counts(self, bayer)
                    .map(|counts| NinaStretch::from_counts(&counts, total).to_stretch());
                ColourStretch { red, green, blue }
            }
        }
    }

    /// Counts of the pixel values in `bins` equal bins over the full 16-bit range.
    pub fn histogram(&self, bins: u32) -> Vec<u64> {
        let bins = bins.clamp(1, 1 << 16) as usize;
        let counts = self.value_counts();
        let mut histogram = vec![0u64; bins];
        for (value, count) in counts.into_iter().enumerate() {
            histogram[(value * bins) >> 16] += count;
        }
        histogram
    }

    /// The `region` of the image, in image pixels, drawn into `width` × `height` RGBA pixels with
    /// `stretch` and, for a colour sensor, debayered (ARCHITECTURE.md). The result is never bigger than
    /// the screen, whatever the camera.
    pub fn preview(
        &self,
        stretch: ColourStretch,
        region: Region,
        width: u32,
        height: u32,
    ) -> PreviewImage {
        preview::render(self, stretch, region, width as usize, height as usize)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn image(width: usize, height: usize, pixels: Vec<u16>) -> Image {
        Image::new(width, height, pixels, None)
    }

    #[test]
    fn bayer_patterns_follow_the_top_left_pixel() {
        let grbg = BayerPattern::Grbg;
        assert_eq!(
            [
                grbg.channel(0, 0),
                grbg.channel(1, 0),
                grbg.channel(0, 1),
                grbg.channel(1, 1)
            ],
            [1, 0, 2, 1]
        );
        let rggb = BayerPattern::Rggb;
        assert_eq!(
            [rggb.channel(0, 0), rggb.channel(3, 1), rggb.channel(2, 2)],
            [0, 2, 0]
        );
    }

    #[test]
    fn statistics_are_exact() {
        let img = image(5, 1, vec![10, 20, 30, 40, 1000]);
        let stats = img.statistics();
        assert_eq!((stats.min, stats.max), (10, 1000));
        assert_eq!(stats.median, 30.0);
        assert_eq!(stats.mean, 220.0);
        // Deviations from 30: 20, 10, 0, 10, 970.
        assert_eq!(stats.mad, 10.0);
    }

    #[test]
    fn even_counts_average_the_middle() {
        let stats = image(4, 1, vec![1, 2, 3, 4]).statistics();
        assert_eq!(stats.median, 2.5);
        // NINA widens a window around 2 and 3 until it holds more than half: 1 to 4.
        assert_eq!(stats.mad, 1.5);
    }

    #[test]
    fn the_midtones_function_puts_its_balance_at_half() {
        assert!((mtf(0.25, 0.25) - 0.5).abs() < 1e-12);
        assert_eq!(mtf(0.25, 0.0), 0.0);
        assert_eq!(mtf(0.25, 1.0), 1.0);
        assert!((mtf(0.5, 0.3) - 0.3).abs() < 1e-12);
    }

    #[test]
    fn the_auto_stretch_puts_the_background_at_20_percent() {
        // A dark sky around 1000 ADU with a little noise, and a few stars.
        let mut pixels: Vec<u16> = (0..10_000).map(|i| 1000 + (i % 21) as u16).collect();
        pixels.extend([60_000; 20]);
        let img = image(10_020, 1, pixels);
        let stretch = img.auto_stretch();
        assert_eq!(stretch.red, stretch.blue, "a mono frame has one stretch");
        let s = stretch.green;
        let median = img.statistics().median / FULL_SCALE;
        let background = mtf(s.midtones as f64, median - s.shadows as f64);
        assert!(
            (background - TARGET_BACKGROUND).abs() < 1e-4,
            "background at {background}"
        );
        assert!(s.shadows > 0.0 && (s.shadows as f64) < median);
    }

    #[test]
    fn a_colour_frame_gets_a_stretch_per_channel() {
        // A GRBG sky whose red sits higher than green and blue, as through a light pollution filter.
        let pixels = (0..200 * 200)
            .map(|i| {
                let (x, y) = (i % 200, i / 200);
                let noise = ((i * 7919) % 31) as u16;
                match BayerPattern::Grbg.channel(x, y) {
                    0 => 3000 + noise,
                    1 => 1500 + noise,
                    _ => 1400 + noise,
                }
            })
            .collect();
        let stretch = Image::new(200, 200, pixels, Some(BayerPattern::Grbg)).auto_stretch();
        assert!(
            stretch.red.shadows > stretch.green.shadows
                && stretch.green.shadows > stretch.blue.shadows
        );
    }

    #[test]
    fn histogram_bins_cover_the_full_range() {
        let img = image(4, 1, vec![0, 255, 256, 65535]);
        let histogram = img.histogram(256);
        assert_eq!(histogram.len(), 256);
        assert_eq!(histogram[0], 2);
        assert_eq!(histogram[1], 1);
        assert_eq!(histogram[255], 1);
        assert_eq!(histogram.iter().sum::<u64>(), 4);
    }
}
