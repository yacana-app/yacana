// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

//! Master calibration frames for the live stack: the mean of a set of darks, bias
//! or flats, pixel by pixel on the sensor's own mosaic, and the master flat made from a set of
//! flats and its bias. The stack takes a frame's master dark away and divides by the master flat
//! before the frame is debayered. A master flat is kept as a FITS file of 32-bit floats, which
//! Siril and PixInsight read too, and any program's flat FITS can be read back as one.

use std::io::{Read, Write};
use std::sync::{Arc, Mutex};

use rayon::prelude::*;

use crate::EngineError;
use crate::fits::{HeaderCard, HeaderValue};
use crate::image::{BayerPattern, Image};
use crate::stack::write_through_part;

/// A master frame in native memory: a Seestar's takes some 33 MB.
#[derive(Debug, Default, uniffi::Object)]
pub struct MasterFrame {
    pub(crate) state: Mutex<Master>,
}

#[derive(Debug, Default)]
pub(crate) struct Master {
    pub(crate) width: usize,
    pub(crate) height: usize,
    bayer: Option<BayerPattern>,
    pub(crate) frames: u32,
    /// The mean of the frames' raw values, in ADU; for a master flat, how much of the light reaches each pixel.
    pub(crate) values: Vec<f32>,
    /// A master flat's bias level, in ADU, for a frame without a dark: the median of the bias it was made with.
    pub(crate) offset: Option<f32>,
    /// A flat read from a FITS file: its DATE-OBS, as written there.
    label: String,
}

#[uniffi::export]
impl MasterFrame {
    /// An empty master: the first frame added sets its size.
    #[uniffi::constructor]
    pub fn new() -> Arc<MasterFrame> {
        Arc::new(MasterFrame::default())
    }

    /// The flat in the FITS file at `path`, as [MasterFrame::save_fits] writes it or as another
    /// program saves its master flat: see [Master::from_fits].
    #[uniffi::constructor]
    pub fn load_fits(path: String) -> Result<Arc<MasterFrame>, EngineError> {
        let bytes = std::fs::read(&path).map_err(|e| EngineError::File {
            reason: format!("{path}: {e}"),
        })?;
        let master = Master::from_fits(&bytes).map_err(|reason| EngineError::Format {
            reason: format!("{path}: {reason}"),
        })?;
        Ok(Arc::new(MasterFrame {
            state: Mutex::new(master),
        }))
    }

    /// The flat of [MasterFrame::load_fits] from the open file `fd`, which this call takes over and
    /// closes, such as a file of the folder the user picked through Android's file picker.
    #[uniffi::constructor]
    pub fn read_fits_from_descriptor(fd: i32) -> Result<Arc<MasterFrame>, EngineError> {
        let mut bytes = Vec::new();
        crate::descriptor_file(fd)?
            .read_to_end(&mut bytes)
            .map_err(|e| EngineError::File {
                reason: e.to_string(),
            })?;
        let master = Master::from_fits(&bytes).map_err(|reason| EngineError::Format { reason })?;
        Ok(Arc::new(MasterFrame {
            state: Mutex::new(master),
        }))
    }

    /// Adds `image` to the mean; a frame of another size is left out. Returns how many frames it holds.
    pub fn add(&self, image: Arc<Image>) -> u32 {
        let mut master = self.state.lock().unwrap();
        if master.frames == 0 {
            master.width = image.width;
            master.height = image.height;
            master.bayer = image.bayer;
            master.values = vec![0.0; image.width * image.height];
        } else if image.width != master.width || image.height != master.height {
            return master.frames;
        }
        master.frames += 1;
        let n = master.frames as f32;
        master
            .values
            .par_iter_mut()
            .zip(image.pixels.par_iter())
            .for_each(|(mean, &value)| *mean += (value as f32 - *mean) / n);
        master.frames
    }

    pub fn frames(&self) -> u32 {
        self.state.lock().unwrap().frames
    }

    /// A master flat from this mean of flats: `bias` taken away, then each channel of the mosaic
    /// over its own mean in the middle of the frame, so that each pixel says how much of the light
    /// reaches it and the camera's colour stays as it was.
    pub fn flat(&self, bias: Option<Arc<MasterFrame>>) -> Result<Arc<MasterFrame>, EngineError> {
        let master = self.state.lock().unwrap();
        if master.frames == 0 {
            return Err(EngineError::Format {
                reason: "the master has no frames".into(),
            });
        }
        let bias = bias.as_ref().map(|b| b.state.lock().unwrap());
        let bias = bias
            .as_deref()
            .filter(|b| b.frames > 0 && b.width == master.width && b.height == master.height);
        let (width, height) = (master.width, master.height);
        let values: Vec<f32> = match bias {
            Some(bias) => master
                .values
                .par_iter()
                .zip(bias.values.par_iter())
                .map(|(&f, &b)| f - b)
                .collect(),
            None => master.values.clone(),
        };
        let values = normalized(values, width, height, master.bayer)
            .map_err(|reason| EngineError::Format { reason })?;
        let offset = bias.map(|b| {
            crate::stack::median_of(b.values.iter().step_by(OFFSET_STRIDE).copied().collect())
        });
        Ok(Arc::new(MasterFrame {
            state: Mutex::new(Master {
                width,
                height,
                bayer: master.bayer,
                frames: master.frames,
                values,
                offset,
                label: String::new(),
            }),
        }))
    }

    /// Writes it at `path`, through `path.part`, as a FITS file of 32-bit floats with `headers`.
    pub fn save_fits(&self, path: String, headers: Vec<HeaderCard>) -> Result<(), EngineError> {
        let master = self.state.lock().unwrap();
        master.check()?;
        write_through_part(&path, |out| master.write_fits(&headers, out))
    }

    /// Writes the FITS of [MasterFrame::save_fits] into the open file `fd`, which this call takes over and closes.
    pub fn write_fits_to_descriptor(
        &self,
        fd: i32,
        headers: Vec<HeaderCard>,
    ) -> Result<(), EngineError> {
        let master = self.state.lock().unwrap();
        master.check()?;
        let error = |e: std::io::Error| EngineError::File {
            reason: e.to_string(),
        };
        let mut out = std::io::BufWriter::with_capacity(1 << 20, crate::descriptor_file(fd)?);
        master.write_fits(&headers, &mut out).map_err(error)?;
        out.into_inner()
            .map_err(|e| error(e.into_error()))?
            .sync_all()
            .map_err(error)
    }

    /// A flat read from a FITS file: its DATE-OBS as written there; empty for one made here.
    pub fn label(&self) -> String {
        self.state.lock().unwrap().label.clone()
    }
}

impl Master {
    fn check(&self) -> Result<(), EngineError> {
        if self.frames == 0 {
            return Err(EngineError::Format {
                reason: "the master has no frames".into(),
            });
        }
        Ok(())
    }

    /// The values as one plane of 32-bit floats, with `headers` and the Bayer pattern if they lack it.
    fn write_fits(&self, headers: &[HeaderCard], out: &mut dyn Write) -> std::io::Result<()> {
        let mut headers = headers.to_vec();
        // The bias level the flat was made with, so that a frame without a dark can still be divided by it.
        if let Some(offset) = self.offset {
            headers.push(HeaderCard {
                key: BIAS_LEVEL.into(),
                value: HeaderValue::Real {
                    value: offset as f64,
                },
                comment: "[ADU] Bias level of the flats".into(),
            });
        }
        if let Some(bayer) = self.bayer
            && !headers
                .iter()
                .any(|h| h.key.eq_ignore_ascii_case("BAYERPAT"))
        {
            headers.push(HeaderCard {
                key: "BAYERPAT".into(),
                value: HeaderValue::Text {
                    value: bayer_name(bayer).into(),
                },
                comment: "Sensor Bayer pattern".into(),
            });
        }
        crate::fits::write_float_planes(self.width, self.height, 1, &self.values, &headers, out)
    }

    /// A master flat from a FITS file of one plane, 8, 16 or 32-bit integers or 32 or 64-bit
    /// floats, with its BZERO and BSCALE, its Bayer pattern and its rows top-down: each colour over
    /// its own mean in the middle of the frame, whether it was written so, as by
    /// [MasterFrame::save_fits], or in ADU, as another program's master flat may be. A flat
    /// debayered into three planes cannot calibrate a frame before it is debayered, and is refused.
    fn from_fits(bytes: &[u8]) -> Result<Master, String> {
        let (keys, start) = crate::fits::header(bytes).ok_or("not a FITS file")?;
        let number = |key: &str| keys.get(key).and_then(|v| v.parse::<f64>().ok());
        let axes = number("NAXIS").unwrap_or(0.0) as usize;
        if !(axes == 2 || (axes == 3 && number("NAXIS3") == Some(1.0))) {
            return Err(
                "not a flat of one plane: a colour flat, already debayered, cannot be used".into(),
            );
        }
        let width = number("NAXIS1").ok_or("no width")? as usize;
        let height = number("NAXIS2").ok_or("no height")? as usize;
        let bitpix = number("BITPIX").ok_or("no BITPIX")? as i32;
        let (zero, scale) = (
            number("BZERO").unwrap_or(0.0),
            number("BSCALE").unwrap_or(1.0),
        );
        let size = (bitpix.unsigned_abs() / 8) as usize;
        let data = bytes
            .get(start..start + width * height * size)
            .ok_or("the file is cut short")?;
        let raw: Vec<f64> = match bitpix {
            8 => data.iter().map(|&b| b as f64).collect(),
            16 => data
                .chunks(2)
                .map(|b| i16::from_be_bytes([b[0], b[1]]) as f64)
                .collect(),
            32 => data
                .chunks(4)
                .map(|b| i32::from_be_bytes([b[0], b[1], b[2], b[3]]) as f64)
                .collect(),
            -32 => data
                .chunks(4)
                .map(|b| f32::from_be_bytes([b[0], b[1], b[2], b[3]]) as f64)
                .collect(),
            -64 => data
                .chunks(8)
                .map(|b| f64::from_be_bytes(b.try_into().unwrap()))
                .collect(),
            _ => return Err(format!("BITPIX {bitpix} is not an image")),
        };
        let mut values: Vec<f32> = raw.iter().map(|v| (zero + scale * v) as f32).collect();
        let mut bayer = keys.get("BAYERPAT").and_then(|p| match p.as_str() {
            "RGGB" => Some(BayerPattern::Rggb),
            "GRBG" => Some(BayerPattern::Grbg),
            "GBRG" => Some(BayerPattern::Gbrg),
            "BGGR" => Some(BayerPattern::Bggr),
            _ => None,
        });
        // Stored bottom-up: turned over, as the frames are top-down, and so is its mosaic.
        if keys.get("ROWORDER").is_some_and(|o| o == "BOTTOM-UP") {
            values = values.chunks(width).rev().flatten().copied().collect();
            bayer = bayer.map(|b| match b {
                BayerPattern::Rggb => BayerPattern::Gbrg,
                BayerPattern::Gbrg => BayerPattern::Rggb,
                BayerPattern::Grbg => BayerPattern::Bggr,
                BayerPattern::Bggr => BayerPattern::Grbg,
            });
        }
        let frames = number("NCOMBINE").map_or(1, |n| n.max(1.0) as u32);
        Ok(Master {
            width,
            height,
            bayer,
            frames,
            values: normalized(values, width, height, bayer)?,
            offset: number(BIAS_LEVEL).map(|level| level as f32),
            label: keys.get("DATE-OBS").cloned().unwrap_or_default(),
        })
    }

    /// Whether it is a master for a `width` × `height` frame.
    pub(crate) fn fits(&self, width: usize, height: usize) -> bool {
        self.frames > 0 && self.width == width && self.height == height
    }
}

/// Each channel of the mosaic over its own mean in the middle half of the frame, away from the
/// vignetted corners, so that each pixel says how much of the light reaches it and the camera's
/// colour stays as it was; no pixel below [MIN_FLAT].
fn normalized(
    mut values: Vec<f32>,
    width: usize,
    height: usize,
    bayer: Option<BayerPattern>,
) -> Result<Vec<f32>, String> {
    let channel = |x: usize, y: usize| bayer.map_or(0, |bayer| bayer.channel(x, y));
    let mut sums = [0f64; 3];
    let mut counts = [0u64; 3];
    for y in height / 4..height * 3 / 4 {
        for x in width / 4..width * 3 / 4 {
            let c = channel(x, y);
            sums[c] += values[y * width + x] as f64;
            counts[c] += 1;
        }
    }
    let means = [0, 1, 2].map(|c| {
        if counts[c] > 0 {
            (sums[c] / counts[c] as f64) as f32
        } else {
            1.0
        }
    });
    if means
        .iter()
        .take(if bayer.is_some() { 3 } else { 1 })
        .any(|&m| m <= 0.0)
    {
        return Err("the flats have no light".into());
    }
    values
        .par_chunks_mut(width)
        .enumerate()
        .for_each(|(y, row)| {
            for (x, value) in row.iter_mut().enumerate() {
                *value = (*value / means[channel(x, y)]).max(MIN_FLAT);
            }
        });
    Ok(values)
}

fn bayer_name(bayer: BayerPattern) -> &'static str {
    match bayer {
        BayerPattern::Rggb => "RGGB",
        BayerPattern::Grbg => "GRBG",
        BayerPattern::Gbrg => "GBRG",
        BayerPattern::Bggr => "BGGR",
    }
}

/// No pixel gets more than 100 times its light, however dark the flat is there.
const MIN_FLAT: f32 = 0.01;
const OFFSET_STRIDE: usize = 16;
/// The FITS keyword of a master flat's bias level, in ADU.
const BIAS_LEVEL: &str = "BIASLVL";

#[cfg(test)]
mod tests {
    use super::*;

    fn image(width: usize, height: usize, value: impl Fn(usize, usize) -> u16) -> Arc<Image> {
        let pixels = (0..height)
            .flat_map(|y| (0..width).map(move |x| (x, y)))
            .map(|(x, y)| value(x, y))
            .collect();
        Arc::new(Image::new(width, height, pixels, Some(BayerPattern::Grbg)))
    }

    #[test]
    fn a_master_is_the_mean_of_its_frames_and_leaves_out_another_size() {
        let master = MasterFrame::new();
        master.add(image(8, 8, |_, _| 100));
        assert_eq!(master.add(image(8, 8, |_, _| 120)), 2);
        assert_eq!(
            master.add(image(4, 4, |_, _| 500)),
            2,
            "another size is left out"
        );
        assert!(
            master
                .state
                .lock()
                .unwrap()
                .values
                .iter()
                .all(|&v| (v - 110.0).abs() < 1e-3)
        );
    }

    #[test]
    fn a_master_flat_says_how_much_light_reaches_each_pixel_of_each_colour() {
        let (width, height) = (16, 16);
        // Vignetted at the left, green brighter than red and blue, over a bias of 1000.
        let flats = MasterFrame::new();
        let light = |x: usize, y: usize| {
            let colour = BayerPattern::Grbg.channel(x, y);
            let level = if colour == 1 { 20_000.0 } else { 10_000.0 };
            (1000.0 + level * if x < 4 { 0.5 } else { 1.0 }) as u16
        };
        flats.add(image(width, height, light));
        let bias = MasterFrame::new();
        bias.add(image(width, height, |_, _| 1000));

        let flat = flats.flat(Some(bias)).unwrap();

        let master = flat.state.lock().unwrap();
        assert_eq!(master.offset, Some(1000.0));
        assert!(
            (master.values[8 * width + 8] - 1.0).abs() < 1e-3,
            "{}",
            master.values[8 * width + 8]
        );
        assert!(
            (master.values[8 * width + 9] - 1.0).abs() < 1e-3,
            "each colour over its own mean"
        );
        assert!(
            (master.values[8 * width + 1] - 0.5).abs() < 1e-3,
            "half the light at the edge"
        );
    }

    fn text(key: &str, value: &str) -> HeaderCard {
        HeaderCard {
            key: key.into(),
            value: HeaderValue::Text {
                value: value.into(),
            },
            comment: String::new(),
        }
    }

    fn temp(name: &str) -> String {
        std::env::temp_dir()
            .join(format!("yacana-{name}-{}.fits", std::process::id()))
            .to_string_lossy()
            .into()
    }

    #[test]
    fn a_master_flat_comes_back_from_its_fits_as_it_was() {
        let flats = MasterFrame::new();
        flats.add(image(8, 6, |x, y| (1500 + x * 100 + y * 10) as u16));
        flats.add(image(8, 6, |x, y| (1500 + x * 100 + y * 10) as u16));
        let bias = MasterFrame::new();
        bias.add(image(8, 6, |_, _| 1000));
        let flat = flats.flat(Some(bias)).unwrap();
        let path = temp("master-flat");

        flat.save_fits(
            path.clone(),
            vec![
                text("FILTER", "IR"),
                text("DATE-OBS", "2026-10-11T15:02:03.000"),
                HeaderCard {
                    key: "NCOMBINE".into(),
                    value: HeaderValue::Integer { value: 2 },
                    comment: String::new(),
                },
            ],
        )
        .unwrap();
        let back = MasterFrame::load_fits(path.clone()).unwrap();

        assert_eq!(back.label(), "2026-10-11T15:02:03.000");
        let (a, b) = (flat.state.lock().unwrap(), back.state.lock().unwrap());
        assert_eq!(
            (a.width, a.height, a.frames, a.bayer, a.offset),
            (b.width, b.height, b.frames, b.bayer, b.offset),
            "its bias level too, for a frame without a dark"
        );
        assert_eq!(b.offset, Some(1000.0));
        assert!(
            a.values
                .iter()
                .zip(&b.values)
                .all(|(x, y)| (x - y).abs() < 1e-5),
            "the same flat"
        );
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn another_programs_flat_in_adu_says_how_much_light_reaches_each_pixel() {
        let (width, height) = (16, 16);
        // A 16-bit flat, vignetted at the left, green brighter than red and blue.
        let flat = image(width, height, |x, y| {
            let level = if BayerPattern::Grbg.channel(x, y) == 1 {
                30_000.0
            } else {
                15_000.0
            };
            (level * if x < 4 { 0.5 } else { 1.0 }) as u16
        });
        let path = temp("adu-flat");
        flat.save(path.clone(), vec![text("BAYERPAT", "GRBG")])
            .unwrap();

        let master = MasterFrame::load_fits(path.clone()).unwrap();

        let master = master.state.lock().unwrap();
        assert_eq!((master.frames, master.bayer), (1, Some(BayerPattern::Grbg)));
        assert!((master.values[8 * width + 8] - 1.0).abs() < 1e-3);
        assert!(
            (master.values[8 * width + 9] - 1.0).abs() < 1e-3,
            "each colour over its own mean"
        );
        assert!(
            (master.values[8 * width + 1] - 0.5).abs() < 1e-3,
            "half the light at the edge"
        );
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn a_colour_flat_already_debayered_is_refused() {
        let path = temp("colour-flat");
        let mut out = Vec::new();
        crate::fits::write_float_planes(4, 4, 3, &[1.0; 48], &[], &mut out).unwrap();
        std::fs::write(&path, out).unwrap();

        let error = MasterFrame::load_fits(path.clone()).unwrap_err();

        assert!(format!("{error:?}").contains("colour flat"), "{error:?}");
        std::fs::remove_file(path).unwrap();
    }
}
