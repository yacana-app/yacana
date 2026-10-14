// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

//! Live stacking: each good light frame is aligned on the first one by its stars,
//! warped into its grid and added to a running mean, per pixel and channel, that rejects what is
//! far from the rest (satellites, planes) once a few frames are in. The stack shows with its
//! background neutral, its white balanced on its own stars and one stretch for the three channels,
//! and saves as a linear 32-bit FITS and as a JPG.
//!
//! The alignment matches triangles of the brightest stars, as ASTAP and Siril do (Valdes et al.
//! 1995): their shapes do not change with a shift, a turn or a change of scale, so the field
//! rotation of an alt-az mount and the dither between frames are both found.

use std::io::Write;
use std::sync::{Arc, Mutex};

use rayon::prelude::*;

use crate::EngineError;
use crate::fits::{self, HeaderCard};
use crate::image::{FULL_SCALE_U16, Image, NinaStretch, mtf_lookup};
use crate::master::{Master, MasterFrame};
use crate::preview::{PreviewImage, Region};

/// A star the app found in a frame, as the stack needs it: where it is, how bright, how wide.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct StackStar {
    pub x: f32,
    pub y: f32,
    /// What NINA ranks stars by: the mean light above the background in its box.
    pub brightness: f64,
    pub hfr: f64,
}

/// What adding a frame did: whether it went in, and how it was aligned on the first.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct StackAddition {
    pub added: bool,
    /// The frames in the stack now.
    pub frames: u32,
    /// How the frame was turned from the first one, in degrees, and how far it was shifted, in pixels.
    pub rotation_deg: f64,
    pub shift_px: f64,
    /// The stars of the frame matched with the first one's.
    pub matched_stars: u32,
    /// Whether its master dark was taken away, and whether it was divided by its master flat.
    pub dark: bool,
    pub flat: bool,
}

/// What the stack holds now, and how its colour is balanced.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct StackInfo {
    pub width: u32,
    pub height: u32,
    pub frames: u32,
    pub exposure_seconds: f64,
    /// The gains that make its typical star white, green kept at 1; 1 each until enough stars are measured.
    pub red_gain: f64,
    pub green_gain: f64,
    pub blue_gain: f64,
    /// The stars measured for the white balance.
    pub balance_stars: u32,
}

/// A stack, in native memory: for each pixel and channel, how many frames it has, their mean and
/// the sum of the squares of their differences from it (Welford's running variance).
#[derive(Debug, uniffi::Object)]
pub struct LiveStack {
    state: Mutex<Option<State>>,
}

#[derive(Debug)]
struct State {
    width: usize,
    height: usize,
    channels: usize,
    /// The first frame's stars, the brightest first: the reference the others align on.
    reference: Vec<StackStar>,
    triangles: Vec<Triangle>,
    /// Each channel's background in the first frame, which the others are brought to.
    reference_background: [f32; 3],
    count: Vec<u16>,
    mean: Vec<f32>,
    m2: Vec<f32>,
    frames: u32,
    exposure_seconds: f64,
    /// The colour model, worked out again only when frames have come since.
    colour: Option<(u32, Colour)>,
    /// What the app said the stack is, at its last checkpoint: to know it again after a restore.
    label: String,
}

impl Default for LiveStack {
    fn default() -> Self {
        LiveStack {
            state: Mutex::new(None),
        }
    }
}

/// A stack's pixels, linear: red, green and blue of each pixel in turn, row by row, in ADU; NaN
/// where no frame reaches. The values go as the bytes of little-endian 32-bit floats, which cross
/// to Kotlin as one array rather than a list of boxed numbers.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct LinearImage {
    pub width: u32,
    pub height: u32,
    pub values: Vec<u8>,
}

impl LinearImage {
    fn of(width: usize, height: usize, values: &[f32]) -> LinearImage {
        LinearImage {
            width: width as u32,
            height: height as u32,
            values: values.iter().flat_map(|v| v.to_le_bytes()).collect(),
        }
    }

    #[cfg(test)]
    fn floats(&self) -> Vec<f32> {
        self.values
            .chunks(4)
            .map(|b| f32::from_le_bytes([b[0], b[1], b[2], b[3]]))
            .collect()
    }
}

#[uniffi::export]
impl LiveStack {
    /// An empty stack: the first frame added becomes its reference.
    #[uniffi::constructor]
    pub fn new() -> Arc<LiveStack> {
        Arc::new(LiveStack::default())
    }

    /// The stack [LiveStack::checkpoint] wrote at `path`, to go on after the app was closed, with its [LiveStack::label].
    #[uniffi::constructor]
    pub fn restore(path: String) -> Result<Arc<LiveStack>, EngineError> {
        let bytes = std::fs::read(&path).map_err(|e| EngineError::File {
            reason: format!("{path}: {e}"),
        })?;
        let state = State::read(&bytes).ok_or_else(|| EngineError::Format {
            reason: format!("{path} is not a stack"),
        })?;
        Ok(Arc::new(LiveStack {
            state: Mutex::new(Some(state)),
        }))
    }

    /// Adds `image`, whose `stars` the app found, `exposure_seconds` long, calibrated with
    /// `dark`, a master dark of its exposure, and `flat`, the master flat of its filter, when
    /// given and of its size. The first frame sets the grid; a later one goes in only if its stars
    /// match the first one's. Blocks for a fraction of a second to a couple of seconds: call it
    /// off the main thread.
    pub fn add(
        &self,
        image: Arc<Image>,
        stars: Vec<StackStar>,
        exposure_seconds: f64,
        dark: Option<Arc<MasterFrame>>,
        flat: Option<Arc<MasterFrame>>,
    ) -> StackAddition {
        let mut guard = self.state.lock().unwrap();
        let mut stars = stars;
        stars.sort_by(|a, b| b.brightness.total_cmp(&a.brightness));
        let dark = dark.as_ref().map(|d| d.state.lock().unwrap());
        let flat = flat.as_ref().map(|f| f.state.lock().unwrap());
        let (planes, dark, flat) = Planes::of(&image, dark.as_deref(), flat.as_deref());
        let Some(state) = guard.as_mut() else {
            let state = State::first(&planes, stars, exposure_seconds);
            let frames = state.frames;
            *guard = Some(state);
            return StackAddition {
                added: true,
                frames,
                rotation_deg: 0.0,
                shift_px: 0.0,
                matched_stars: 0,
                dark,
                flat,
            };
        };
        let refused = |matched| StackAddition {
            added: false,
            frames: state.frames,
            rotation_deg: 0.0,
            shift_px: 0.0,
            matched_stars: matched,
            dark,
            flat,
        };
        if planes.width != state.width
            || planes.height != state.height
            || planes.channels != state.channels
        {
            return refused(0);
        }
        let Some((transform, matched)) = align(&state.reference, &state.triangles, &stars) else {
            return refused(0);
        };
        state.accumulate(&planes, &transform);
        state.exposure_seconds += exposure_seconds;
        StackAddition {
            added: true,
            frames: state.frames,
            rotation_deg: transform.rotation_deg(),
            shift_px: transform.shift(state.width, state.height),
            matched_stars: matched as u32,
            dark,
            flat,
        }
    }

    /// What the app called the stack at its last checkpoint; empty before one.
    pub fn label(&self) -> String {
        self.state
            .lock()
            .unwrap()
            .as_ref()
            .map(|state| state.label.clone())
            .unwrap_or_default()
    }

    pub fn info(&self) -> StackInfo {
        let mut guard = self.state.lock().unwrap();
        match guard.as_mut() {
            None => StackInfo {
                width: 0,
                height: 0,
                frames: 0,
                exposure_seconds: 0.0,
                red_gain: 1.0,
                green_gain: 1.0,
                blue_gain: 1.0,
                balance_stars: 0,
            },
            Some(state) => {
                let colour = state.colour();
                StackInfo {
                    width: state.width as u32,
                    height: state.height as u32,
                    frames: state.frames,
                    exposure_seconds: state.exposure_seconds,
                    red_gain: colour.gains[0] as f64,
                    green_gain: colour.gains[1] as f64,
                    blue_gain: colour.gains[2] as f64,
                    balance_stars: colour.stars,
                }
            }
        }
    }

    /// `region` of the stack drawn into `width` × `height` RGBA pixels: with its background
    /// neutral and its white balanced when `balanced`, else as the camera gave it; one stretch for
    /// all three channels, so the colours stay as they are. Black before the first frame.
    pub fn preview(&self, balanced: bool, region: Region, width: u32, height: u32) -> PreviewImage {
        let mut guard = self.state.lock().unwrap();
        let (width, height) = (width as usize, height as usize);
        match guard.as_mut() {
            None => PreviewImage {
                width: width as u32,
                height: height as u32,
                rgba: [0, 0, 0, 255].repeat(width * height),
            },
            Some(state) => state.render(balanced, region, width, height),
        }
    }

    /// The whole stack at `width` × `height`, linear and with the camera's colour: each pixel the
    /// mean of the stack's values under it, red, green and blue in turn, in ADU; NaN where no frame
    /// reaches. For the live mosaic, which matches, blends and stretches its panels itself.
    pub fn linear(&self, width: u32, height: u32) -> LinearImage {
        let guard = self.state.lock().unwrap();
        let (width, height) = (width as usize, height as usize);
        match guard.as_ref() {
            None => LinearImage::of(width, height, &vec![f32::NAN; width * height * 3]),
            Some(state) => state.linear(width, height),
        }
    }

    /// Saves the stack at `path` as a 32-bit floating point FITS, linear and with the camera's
    /// colour, its three channels as planes and values from 0 to 1, through `path.part`.
    pub fn save(&self, path: String, headers: Vec<HeaderCard>) -> Result<(), EngineError> {
        let guard = self.state.lock().unwrap();
        let state = guard.as_ref().ok_or_else(|| EngineError::Format {
            reason: "the stack is empty".into(),
        })?;
        write_through_part(&path, |out| state.write_fits(&headers, out))
    }

    /// Writes the FITS of [LiveStack::save] into the open file `fd`, which this call takes over and closes.
    pub fn write_to_descriptor(
        &self,
        fd: i32,
        headers: Vec<HeaderCard>,
    ) -> Result<(), EngineError> {
        let guard = self.state.lock().unwrap();
        let state = guard.as_ref().ok_or_else(|| EngineError::Format {
            reason: "the stack is empty".into(),
        })?;
        let mut file = crate::descriptor_file(fd)?;
        let mut out = std::io::BufWriter::with_capacity(1 << 20, &mut file);
        state.write_fits(&headers, &mut out).map_err(file_error)?;
        out.flush().map_err(file_error)?;
        drop(out);
        file.sync_all().map_err(file_error)
    }

    /// Saves the stack at `path` as a JPG as the screen shows it, cropped to where at least half
    /// the frames reach, through `path.part`.
    pub fn save_jpeg(&self, path: String, balanced: bool) -> Result<(), EngineError> {
        let mut guard = self.state.lock().unwrap();
        let state = guard.as_mut().ok_or_else(|| EngineError::Format {
            reason: "the stack is empty".into(),
        })?;
        let jpeg = state.jpeg(balanced)?;
        write_through_part(&path, |out| out.write_all(&jpeg))
    }

    /// Writes the JPG of [LiveStack::save_jpeg] into the open file `fd`, which this call takes over and closes.
    pub fn write_jpeg_to_descriptor(&self, fd: i32, balanced: bool) -> Result<(), EngineError> {
        let mut guard = self.state.lock().unwrap();
        let state = guard.as_mut().ok_or_else(|| EngineError::Format {
            reason: "the stack is empty".into(),
        })?;
        let jpeg = state.jpeg(balanced)?;
        let mut file = crate::descriptor_file(fd)?;
        file.write_all(&jpeg).map_err(file_error)?;
        file.sync_all().map_err(file_error)
    }

    /// Writes everything the stack holds at `path`, through `path.part`, for [LiveStack::restore],
    /// with `label` to know it by.
    pub fn checkpoint(&self, path: String, label: String) -> Result<(), EngineError> {
        let mut guard = self.state.lock().unwrap();
        let state = guard.as_mut().ok_or_else(|| EngineError::Format {
            reason: "the stack is empty".into(),
        })?;
        state.label = label;
        write_through_part(&path, |out| state.write(out))
    }
}

fn file_error(e: std::io::Error) -> EngineError {
    EngineError::File {
        reason: e.to_string(),
    }
}

/// Writes through `path.part`, flushed and then renamed, so that a crash never leaves half a file.
pub(crate) fn write_through_part(
    path: &str,
    write: impl FnOnce(&mut dyn Write) -> std::io::Result<()>,
) -> Result<(), EngineError> {
    let error = |e: std::io::Error| EngineError::File {
        reason: format!("{path}: {e}"),
    };
    let part = format!("{path}.part");
    let file = std::fs::File::create(&part).map_err(error)?;
    let mut out = std::io::BufWriter::with_capacity(1 << 20, file);
    write(&mut out).map_err(error)?;
    let file = out.into_inner().map_err(|e| error(e.into_error()))?;
    file.sync_all().map_err(error)?;
    drop(file);
    std::fs::rename(&part, path).map_err(error)
}

/// A frame as its channels, debayered as NINA does, each value in ADU: one plane for a mono frame, three for colour.
struct Planes {
    width: usize,
    height: usize,
    channels: usize,
    values: Vec<f32>,
}

impl Planes {
    /// `image` calibrated and debayered as NINA does, each pixel the mean of its colour in the 3 × 3
    /// around it. With `dark`, it is taken away; with `flat`, divided by it, after the dark or,
    /// without one, after the flat's own bias level. Says whether each was used: neither is when it
    /// is of another size, and the flat isn't without a dark or a bias level, which it would turn
    /// into the vignetting upside down.
    fn of(image: &Image, dark: Option<&Master>, flat: Option<&Master>) -> (Planes, bool, bool) {
        let (width, height) = (image.width, image.height);
        let dark = dark.filter(|d| d.fits(width, height));
        let flat = flat.filter(|f| f.fits(width, height) && (dark.is_some() || f.offset.is_some()));
        let raw: Vec<f32> = image
            .pixels
            .par_iter()
            .enumerate()
            .map(|(i, &value)| {
                let mut value = value as f32;
                match (dark, flat) {
                    (Some(dark), _) => value -= dark.values[i],
                    (None, Some(flat)) => value -= flat.offset.unwrap_or(0.0),
                    (None, None) => {}
                }
                if let Some(flat) = flat {
                    value /= flat.values[i];
                }
                value
            })
            .collect();
        let used = (dark.is_some(), flat.is_some());
        let Some(bayer) = image.bayer else {
            return (
                Planes {
                    width,
                    height,
                    channels: 1,
                    values: raw,
                },
                used.0,
                used.1,
            );
        };
        let mut values = vec![0f32; 3 * width * height];
        let (red, rest) = values.split_at_mut(width * height);
        let (green, blue) = rest.split_at_mut(width * height);
        red.par_chunks_mut(width)
            .zip(green.par_chunks_mut(width))
            .zip(blue.par_chunks_mut(width))
            .enumerate()
            .for_each(|(y, ((r, g), b))| {
                for x in 0..width {
                    let mut sums = [0f32; 3];
                    let mut counts = [0u32; 3];
                    for yy in y.saturating_sub(1)..=(y + 1).min(height - 1) {
                        for xx in x.saturating_sub(1)..=(x + 1).min(width - 1) {
                            let channel = bayer.channel(xx, yy);
                            sums[channel] += raw[yy * width + xx];
                            counts[channel] += 1;
                        }
                    }
                    r[x] = sums[0] / counts[0] as f32;
                    g[x] = sums[1] / counts[1] as f32;
                    b[x] = sums[2] / counts[2] as f32;
                }
            });
        (
            Planes {
                width,
                height,
                channels: 3,
                values,
            },
            used.0,
            used.1,
        )
    }

    fn plane(&self, channel: usize) -> &[f32] {
        &self.values[channel * self.width * self.height..(channel + 1) * self.width * self.height]
    }

    /// Each channel's background: the median of every 16th pixel.
    fn backgrounds(&self) -> [f32; 3] {
        let mut out = [0f32; 3];
        for (c, slot) in out.iter_mut().enumerate().take(self.channels) {
            *slot = median_of(
                self.plane(c)
                    .iter()
                    .step_by(BACKGROUND_STRIDE)
                    .copied()
                    .collect(),
            );
        }
        if self.channels == 1 {
            out = [out[0]; 3];
        }
        out
    }

    /// The value of `channel` at `x`, `y` between pixels, from its four neighbours; None outside.
    fn sample(&self, channel: usize, x: f64, y: f64) -> Option<f32> {
        if x < 0.0 || y < 0.0 || x > (self.width - 1) as f64 || y > (self.height - 1) as f64 {
            return None;
        }
        let (x0, y0) = (x.floor() as usize, y.floor() as usize);
        let (x1, y1) = ((x0 + 1).min(self.width - 1), (y0 + 1).min(self.height - 1));
        let (fx, fy) = ((x - x0 as f64) as f32, (y - y0 as f64) as f32);
        let plane = self.plane(channel);
        let at = |x: usize, y: usize| plane[y * self.width + x];
        let top = at(x0, y0) + (at(x1, y0) - at(x0, y0)) * fx;
        let bottom = at(x0, y1) + (at(x1, y1) - at(x0, y1)) * fx;
        Some(top + (bottom - top) * fy)
    }
}

impl State {
    fn first(planes: &Planes, stars: Vec<StackStar>, exposure_seconds: f64) -> State {
        let size = planes.channels * planes.width * planes.height;
        let reference: Vec<StackStar> = stars.into_iter().take(REFERENCE_STARS).collect();
        let triangles = triangles(&reference[..reference.len().min(TRIANGLE_STARS)]);
        State {
            width: planes.width,
            height: planes.height,
            channels: planes.channels,
            reference,
            triangles,
            reference_background: planes.backgrounds(),
            count: vec![1; size],
            mean: planes.values.clone(),
            m2: vec![0.0; size],
            frames: 1,
            exposure_seconds,
            colour: None,
            label: String::new(),
        }
    }

    /// Adds `planes` warped by `transform`, brought to the first frame's background, with each
    /// value far from the mean of its pixel left out once enough frames are in.
    fn accumulate(&mut self, planes: &Planes, transform: &Transform) {
        let width = self.width;
        let backgrounds = planes.backgrounds();
        let pixels = width * self.height;
        let references = self.reference_background;
        for (c, (reference, background)) in references
            .iter()
            .zip(backgrounds)
            .enumerate()
            .take(self.channels)
        {
            let offset = reference - background;
            let range = c * pixels..(c + 1) * pixels;
            let count = &mut self.count[range.clone()];
            let mean = &mut self.mean[range.clone()];
            let m2 = &mut self.m2[range];
            count
                .par_chunks_mut(width)
                .zip(mean.par_chunks_mut(width))
                .zip(m2.par_chunks_mut(width))
                .enumerate()
                .for_each(|(y, ((count, mean), m2))| {
                    for x in 0..width {
                        let (fx, fy) = transform.apply(x as f64, y as f64);
                        let Some(value) = planes.sample(c, fx, fy) else {
                            continue;
                        };
                        let value = value + offset;
                        let n = count[x];
                        if n as u32 >= CLIP_FROM {
                            let sigma = (m2[x] / (n as f32 - 1.0)).sqrt();
                            if sigma > 0.0 && (value - mean[x]).abs() > KAPPA * sigma {
                                continue;
                            }
                        }
                        let n = n.saturating_add(1);
                        let delta = value - mean[x];
                        mean[x] += delta / n as f32;
                        m2[x] += delta * (value - mean[x]);
                        count[x] = n;
                    }
                });
        }
        self.frames += 1;
    }

    /// The colour model of the stack as it is: each channel's background and the gains that make
    /// its typical star white, from its own stars.
    fn colour(&mut self) -> Colour {
        if let Some((frames, colour)) = &self.colour
            && *frames == self.frames
        {
            return colour.clone();
        }
        let colour = Colour::of(self);
        self.colour = Some((self.frames, colour.clone()));
        colour
    }

    fn mean_plane(&self, channel: usize) -> &[f32] {
        let pixels = self.width * self.height;
        let c = channel.min(self.channels - 1);
        &self.mean[c * pixels..(c + 1) * pixels]
    }

    fn count_plane(&self, channel: usize) -> &[u16] {
        let pixels = self.width * self.height;
        let c = channel.min(self.channels - 1);
        &self.count[c * pixels..(c + 1) * pixels]
    }

    /// The screen value of each channel of every stack value, after the colour model: one lookup
    /// table for the three, from NINA's stretch of the balanced green, or the green as it is.
    fn lookup(&mut self, balanced: bool) -> (Colour, Vec<u8>) {
        let colour = if balanced {
            self.colour()
        } else {
            Colour::neutral()
        };
        let mut counts = vec![0u64; 1 << 16];
        let green = self.mean_plane(1);
        let coverage = self.count_plane(1);
        let mut total = 0u64;
        for (i, &value) in green.iter().enumerate().step_by(BACKGROUND_STRIDE) {
            if coverage[i] > 0 {
                counts[colour.apply(1, value).round().clamp(0.0, FULL_SCALE_U16) as usize] += 1;
                total += 1;
            }
        }
        let stretch = NinaStretch::from_counts(&counts, total.max(1));
        (colour, mtf_lookup(stretch))
    }

    fn render(
        &mut self,
        balanced: bool,
        region: Region,
        width: usize,
        height: usize,
    ) -> PreviewImage {
        let (colour, lut) = self.lookup(balanced);
        let scale_x = region.width / width.max(1) as f64;
        let scale_y = region.height / height.max(1) as f64;
        let mut rgba = vec![0u8; width * height * 4];
        let state = &*self;
        rgba.par_chunks_mut(width.max(1) * 4)
            .enumerate()
            .for_each(|(out_y, row)| {
                let y0 = region.y + out_y as f64 * scale_y;
                for out_x in 0..width {
                    let x0 = region.x + out_x as f64 * scale_x;
                    let pixel = &mut row[out_x * 4..out_x * 4 + 4];
                    pixel.copy_from_slice(
                        &match state.average(x0, y0, x0 + scale_x, y0 + scale_y) {
                            Some(values) => {
                                let screen = |c: usize| {
                                    lut[colour
                                        .apply(c, values[c])
                                        .round()
                                        .clamp(0.0, FULL_SCALE_U16)
                                        as usize]
                                };
                                [screen(0), screen(1), screen(2), 255]
                            }
                            None => [0, 0, 0, 255],
                        },
                    );
                }
            });
        PreviewImage {
            width: width as u32,
            height: height as u32,
            rgba,
        }
    }

    fn linear(&self, width: usize, height: usize) -> LinearImage {
        let scale_x = self.width as f64 / width.max(1) as f64;
        let scale_y = self.height as f64 / height.max(1) as f64;
        let mut values = vec![f32::NAN; width * height * 3];
        values
            .par_chunks_mut(width.max(1) * 3)
            .enumerate()
            .for_each(|(out_y, row)| {
                let y0 = out_y as f64 * scale_y;
                for out_x in 0..width {
                    let x0 = out_x as f64 * scale_x;
                    if let Some(mean) = self.average(x0, y0, x0 + scale_x, y0 + scale_y) {
                        row[out_x * 3..out_x * 3 + 3].copy_from_slice(&mean);
                    }
                }
            });
        LinearImage::of(width, height, &values)
    }

    /// The mean of each channel under the footprint `x0..x1` × `y0..y1` where frames reach; None outside.
    fn average(&self, x0: f64, y0: f64, x1: f64, y1: f64) -> Option<[f32; 3]> {
        let (w, h) = (self.width as f64, self.height as f64);
        if x1 <= 0.0 || y1 <= 0.0 || x0 >= w || y0 >= h {
            return None;
        }
        let ix0 = x0.max(0.0).floor() as usize;
        let iy0 = y0.max(0.0).floor() as usize;
        let ix1 = (x1.min(w).ceil() as usize).max(ix0 + 1).min(self.width);
        let iy1 = (y1.min(h).ceil() as usize).max(iy0 + 1).min(self.height);
        // Zoomed out, a footprint can hold thousands of pixels: a few rows and columns of it are enough.
        let step_x = ((ix1 - ix0) / FOOTPRINT_SAMPLES).max(1);
        let step_y = ((iy1 - iy0) / FOOTPRINT_SAMPLES).max(1);
        let mut out = [0f32; 3];
        for (c, slot) in out.iter_mut().enumerate() {
            let (plane, coverage) = (self.mean_plane(c), self.count_plane(c));
            let (mut sum, mut n) = (0f64, 0u32);
            for y in (iy0..iy1).step_by(step_y) {
                for x in (ix0..ix1).step_by(step_x) {
                    let i = y * self.width + x;
                    if coverage[i] > 0 {
                        sum += plane[i] as f64;
                        n += 1;
                    }
                }
            }
            if n == 0 {
                return None;
            }
            *slot = (sum / n as f64) as f32;
        }
        Some(out)
    }

    /// The rectangle where at least half the frames reach, so that the JPG has no thin edges.
    fn covered(&self) -> (usize, usize, usize, usize) {
        let coverage = self.count_plane(1);
        let half = (self.frames / 2).max(1) as u16;
        let row = |y: usize| coverage[y * self.width + self.width / 2] >= half;
        let column = |x: usize| coverage[(self.height / 2) * self.width + x] >= half;
        let top = (0..self.height).find(|&y| row(y)).unwrap_or(0);
        let bottom = (0..self.height)
            .rfind(|&y| row(y))
            .unwrap_or(self.height - 1);
        let left = (0..self.width).find(|&x| column(x)).unwrap_or(0);
        let right = (0..self.width)
            .rfind(|&x| column(x))
            .unwrap_or(self.width - 1);
        (left, top, right + 1 - left, bottom + 1 - top)
    }

    fn jpeg(&mut self, balanced: bool) -> Result<Vec<u8>, EngineError> {
        let (left, top, width, height) = self.covered();
        let region = Region {
            x: left as f64,
            y: top as f64,
            width: width as f64,
            height: height as f64,
        };
        let preview = self.render(balanced, region, width, height);
        let rgb: Vec<u8> = preview
            .rgba
            .chunks(4)
            .flat_map(|p| [p[0], p[1], p[2]])
            .collect();
        let mut out = Vec::new();
        jpeg_encoder::Encoder::new(&mut out, JPEG_QUALITY)
            .encode(
                &rgb,
                width as u16,
                height as u16,
                jpeg_encoder::ColorType::Rgb,
            )
            .map_err(|e| EngineError::Format {
                reason: e.to_string(),
            })?;
        Ok(out)
    }

    fn write_fits(&self, headers: &[HeaderCard], out: &mut dyn Write) -> std::io::Result<()> {
        let scale = 1.0 / FULL_SCALE_U16;
        let values: Vec<f32> = self
            .mean
            .iter()
            .map(|&v| (v * scale).clamp(0.0, 1.0))
            .collect();
        fits::write_float_planes(
            self.width,
            self.height,
            self.channels,
            &values,
            headers,
            out,
        )
    }

    /// Everything, little-endian after a magic word, for [LiveStack::restore].
    fn write(&self, out: &mut dyn Write) -> std::io::Result<()> {
        out.write_all(MAGIC)?;
        out.write_all(&(self.label.len() as u32).to_le_bytes())?;
        out.write_all(self.label.as_bytes())?;
        for value in [self.width, self.height, self.channels, self.reference.len()] {
            out.write_all(&(value as u32).to_le_bytes())?;
        }
        out.write_all(&self.frames.to_le_bytes())?;
        out.write_all(&self.exposure_seconds.to_le_bytes())?;
        for b in self.reference_background {
            out.write_all(&b.to_le_bytes())?;
        }
        for star in &self.reference {
            out.write_all(&star.x.to_le_bytes())?;
            out.write_all(&star.y.to_le_bytes())?;
            out.write_all(&star.brightness.to_le_bytes())?;
            out.write_all(&star.hfr.to_le_bytes())?;
        }
        let mut bytes = Vec::with_capacity(self.count.len() * 10);
        self.count
            .iter()
            .for_each(|v| bytes.extend(v.to_le_bytes()));
        self.mean.iter().for_each(|v| bytes.extend(v.to_le_bytes()));
        self.m2.iter().for_each(|v| bytes.extend(v.to_le_bytes()));
        out.write_all(&bytes)
    }

    fn read(bytes: &[u8]) -> Option<State> {
        let mut input = bytes.strip_prefix(MAGIC)?;
        let length = u32::from_le_bytes(take(&mut input)?) as usize;
        let (label, rest) = input.split_at_checked(length)?;
        let label = String::from_utf8(label.to_vec()).ok()?;
        input = rest;
        let mut u32s = [0u32; 4];
        for v in &mut u32s {
            *v = u32::from_le_bytes(take(&mut input)?);
        }
        let [width, height, channels, stars] = u32s.map(|v| v as usize);
        if !(channels == 1 || channels == 3) || width == 0 || height == 0 {
            return None;
        }
        let frames = u32::from_le_bytes(take(&mut input)?);
        let exposure_seconds = f64::from_le_bytes(take(&mut input)?);
        let mut reference_background = [0f32; 3];
        for b in &mut reference_background {
            *b = f32::from_le_bytes(take(&mut input)?);
        }
        let mut reference = Vec::with_capacity(stars);
        for _ in 0..stars {
            reference.push(StackStar {
                x: f32::from_le_bytes(take(&mut input)?),
                y: f32::from_le_bytes(take(&mut input)?),
                brightness: f64::from_le_bytes(take(&mut input)?),
                hfr: f64::from_le_bytes(take(&mut input)?),
            });
        }
        let size = channels * width * height;
        if input.len() != size * 10 {
            return None;
        }
        let (counts, rest) = input.split_at(size * 2);
        let (means, m2s) = rest.split_at(size * 4);
        let count = counts
            .chunks(2)
            .map(|b| u16::from_le_bytes([b[0], b[1]]))
            .collect();
        let floats = |bytes: &[u8]| {
            bytes
                .chunks(4)
                .map(|b| f32::from_le_bytes([b[0], b[1], b[2], b[3]]))
                .collect::<Vec<_>>()
        };
        let triangles = triangles(&reference[..reference.len().min(TRIANGLE_STARS)]);
        Some(State {
            width,
            height,
            channels,
            reference,
            triangles,
            reference_background,
            count,
            mean: floats(means),
            m2: floats(m2s),
            frames,
            exposure_seconds,
            colour: None,
            label,
        })
    }
}

fn take<const N: usize>(input: &mut &[u8]) -> Option<[u8; N]> {
    let (head, rest) = input.split_at_checked(N)?;
    *input = rest;
    head.try_into().ok()
}

/// How the stack's colour is shown: each channel's background brought to the green's, and scaled by its gain.
#[derive(Debug, Clone)]
struct Colour {
    backgrounds: [f32; 3],
    gains: [f32; 3],
    stars: u32,
}

impl Colour {
    /// As the camera gave it.
    fn neutral() -> Colour {
        Colour {
            backgrounds: [0.0; 3],
            gains: [1.0; 3],
            stars: 0,
        }
    }

    /// The backgrounds of the covered stack, and the gains that make the median star white: for each
    /// of the first frame's stars away from the edges and below saturation, its light in each channel
    /// above the background within a few HFRs, and the median of red and blue over green.
    fn of(state: &State) -> Colour {
        if state.channels == 1 {
            return Colour::neutral();
        }
        let backgrounds = [0, 1, 2].map(|c| {
            let (plane, coverage) = (state.mean_plane(c), state.count_plane(c));
            median_of(
                plane
                    .iter()
                    .zip(coverage)
                    .step_by(BACKGROUND_STRIDE)
                    .filter(|(_, n)| **n > 0)
                    .map(|(v, _)| *v)
                    .collect(),
            )
        });
        let mut red = Vec::new();
        let mut blue = Vec::new();
        for star in state.reference.iter().take(BALANCE_STARS) {
            let radius = (star.hfr * APERTURE_HFRS).clamp(MIN_APERTURE, MAX_APERTURE);
            let (cx, cy) = (star.x as f64, star.y as f64);
            let reach = radius.ceil() as isize;
            if cx < radius + 1.0
                || cy < radius + 1.0
                || cx + radius + 1.0 >= state.width as f64
                || cy + radius + 1.0 >= state.height as f64
            {
                continue;
            }
            let mut flux = [0f64; 3];
            let mut saturated = false;
            for dy in -reach..=reach {
                for dx in -reach..=reach {
                    if ((dx * dx + dy * dy) as f64) > radius * radius {
                        continue;
                    }
                    let i = (cy as isize + dy) as usize * state.width + (cx as isize + dx) as usize;
                    for (c, total) in flux.iter_mut().enumerate() {
                        let value = state.mean_plane(c)[i];
                        saturated |= value > SATURATION;
                        *total += (value - backgrounds[c]) as f64;
                    }
                }
            }
            if !saturated && flux.iter().all(|&f| f > 0.0) {
                red.push((flux[0] / flux[1]) as f32);
                blue.push((flux[2] / flux[1]) as f32);
            }
        }
        let stars = red.len() as u32;
        let gains = if red.len() >= MIN_BALANCE_STARS {
            let gain = |ratios: Vec<f32>| (1.0 / median_of(ratios)).clamp(MIN_GAIN, MAX_GAIN);
            [gain(red), 1.0, gain(blue)]
        } else {
            [1.0; 3]
        };
        Colour {
            backgrounds,
            gains,
            stars,
        }
    }

    /// The shown value of `channel` for the stack value `value`: above the background, by its gain,
    /// on the green's background.
    fn apply(&self, channel: usize, value: f32) -> f32 {
        (value - self.backgrounds[channel]) * self.gains[channel] + self.backgrounds[1]
    }
}

pub(crate) fn median_of(mut values: Vec<f32>) -> f32 {
    if values.is_empty() {
        return 0.0;
    }
    let middle = values.len() / 2;
    let (_, median, _) = values.select_nth_unstable_by(middle, |a, b| a.total_cmp(b));
    *median
}

/// A point of the first frame and where it is in another.
type PointPair = ((f64, f64), (f64, f64));

/// Where a point of the first frame falls in another: `x' = a x − b y + tx`, `y' = b x + a y + ty`,
/// a turn by atan2(b, a) and a scale by the length of (a, b), then a shift.
#[derive(Debug, Clone, Copy, PartialEq)]
struct Transform {
    a: f64,
    b: f64,
    tx: f64,
    ty: f64,
}

impl Transform {
    fn apply(&self, x: f64, y: f64) -> (f64, f64) {
        (
            self.a * x - self.b * y + self.tx,
            self.b * x + self.a * y + self.ty,
        )
    }

    fn rotation_deg(&self) -> f64 {
        self.b.atan2(self.a).to_degrees()
    }

    /// How far the middle of the frame moved.
    fn shift(&self, width: usize, height: usize) -> f64 {
        let (cx, cy) = (width as f64 / 2.0, height as f64 / 2.0);
        let (x, y) = self.apply(cx, cy);
        (x - cx).hypot(y - cy)
    }

    /// The similarity that takes `from` closest to `to` in the least-squares sense (Umeyama, in
    /// complex numbers: the mean of `to` minus A times the mean of `from`, A the covariance over the spread).
    fn fit(pairs: &[PointPair]) -> Option<Transform> {
        let n = pairs.len() as f64;
        if pairs.len() < 2 {
            return None;
        }
        let (mut fx, mut fy, mut tx, mut ty) = (0.0, 0.0, 0.0, 0.0);
        for ((x, y), (u, v)) in pairs {
            fx += x;
            fy += y;
            tx += u;
            ty += v;
        }
        let (fx, fy, tx, ty) = (fx / n, fy / n, tx / n, ty / n);
        let (mut re, mut im, mut spread) = (0.0, 0.0, 0.0);
        for ((x, y), (u, v)) in pairs {
            let (px, py, qx, qy) = (x - fx, y - fy, u - tx, v - ty);
            // q times the conjugate of p.
            re += qx * px + qy * py;
            im += qy * px - qx * py;
            spread += px * px + py * py;
        }
        if spread <= 0.0 {
            return None;
        }
        let (a, b) = (re / spread, im / spread);
        Some(Transform {
            a,
            b,
            tx: tx - (a * fx - b * fy),
            ty: ty - (b * fx + a * fy),
        })
    }
}

/// A triangle of three stars by their indices, as its shape: the middle and shortest sides over
/// the longest, which a shift, a turn or a change of scale do not change. Its vertices are in a
/// fixed order, opposite the longest side first, so that matching ones correspond.
#[derive(Debug, Clone, Copy)]
struct Triangle {
    stars: [u8; 3],
    shape: (f32, f32),
}

/// The triangles of `stars` whose shape is clear: no two sides too alike, none too thin.
fn triangles(stars: &[StackStar]) -> Vec<Triangle> {
    let n = stars.len().min(u8::MAX as usize);
    let mut out = Vec::new();
    for i in 0..n {
        for j in i + 1..n {
            for k in j + 1..n {
                if let Some(triangle) = triangle(stars, [i, j, k]) {
                    out.push(triangle);
                }
            }
        }
    }
    out.sort_by(|a, b| a.shape.0.total_cmp(&b.shape.0));
    out
}

fn triangle(stars: &[StackStar], idx: [usize; 3]) -> Option<Triangle> {
    let point = |i: usize| (stars[i].x as f64, stars[i].y as f64);
    let length = |i: usize, j: usize| {
        let ((x1, y1), (x2, y2)) = (point(i), point(j));
        (x1 - x2).hypot(y1 - y2)
    };
    // Each side with the vertex opposite it.
    let mut sides = [
        (length(idx[1], idx[2]), idx[0]),
        (length(idx[0], idx[2]), idx[1]),
        (length(idx[0], idx[1]), idx[2]),
    ];
    sides.sort_by(|a, b| b.0.total_cmp(&a.0));
    let [(long, a), (middle, b), (short, c)] = sides;
    if long < MIN_SIDE
        || short / long < MIN_SHAPE
        || (long - middle) / long < DISTINCT
        || (middle - short) / long < DISTINCT
    {
        return None;
    }
    Some(Triangle {
        stars: [a as u8, b as u8, c as u8],
        shape: ((middle / long) as f32, (short / long) as f32),
    })
}

/// Aligns `stars` of a frame on the first frame's: the triangles of both that have the same shape
/// vote for which star is which, the pairs with the most votes give a first transform by
/// consensus, and every star close to where it maps refines it. None without enough stars that agree.
///
/// The brightest stars of two frames are not quite the same ones, as the seeing and the dither
/// change which ones are brightest and which are in the field; when too few of them are shared, it
/// tries again with twice as many.
fn align(
    reference: &[StackStar],
    reference_triangles: &[Triangle],
    stars: &[StackStar],
) -> Option<(Transform, usize)> {
    align_brightest(reference, reference_triangles, stars, TRIANGLE_STARS).or_else(|| {
        let more = &reference[..reference.len().min(MORE_TRIANGLE_STARS)];
        align_brightest(reference, &triangles(more), stars, MORE_TRIANGLE_STARS)
    })
}

/// [align] with the triangles of the `count` brightest stars of each frame.
fn align_brightest(
    reference: &[StackStar],
    reference_triangles: &[Triangle],
    stars: &[StackStar],
    count: usize,
) -> Option<(Transform, usize)> {
    let frame_stars = &stars[..stars.len().min(count)];
    let frame_triangles = triangles(frame_stars);
    let (nr, nf) = (reference.len().min(count), frame_stars.len());
    if nr < MIN_MATCHES || nf < MIN_MATCHES {
        return None;
    }
    let mut votes = vec![0u32; nr * nf];
    for t in &frame_triangles {
        let from = reference_triangles.partition_point(|r| r.shape.0 < t.shape.0 - SHAPE_TOLERANCE);
        for r in &reference_triangles[from..] {
            if r.shape.0 > t.shape.0 + SHAPE_TOLERANCE {
                break;
            }
            if (r.shape.1 - t.shape.1).abs() <= SHAPE_TOLERANCE {
                for v in 0..3 {
                    votes[r.stars[v] as usize * nf + t.stars[v] as usize] += 1;
                }
            }
        }
    }
    // The pairs that are each other's best.
    let mut candidates = Vec::new();
    for i in 0..nr {
        let row = &votes[i * nf..(i + 1) * nf];
        let Some((j, &best)) = row.iter().enumerate().max_by_key(|(_, v)| **v) else {
            continue;
        };
        let column_best = (0..nr).map(|k| votes[k * nf + j]).max().unwrap_or(0);
        if best >= MIN_VOTES && best == column_best {
            candidates.push((i, j, best));
        }
    }
    candidates.sort_by_key(|candidate| std::cmp::Reverse(candidate.2));
    candidates.truncate(MAX_CANDIDATES);
    let point = |s: &StackStar| (s.x as f64, s.y as f64);
    let mut best: Option<(Transform, Vec<(usize, usize)>)> = None;
    for p in 0..candidates.len() {
        for q in p + 1..candidates.len() {
            let pairs = [
                (
                    point(&reference[candidates[p].0]),
                    point(&frame_stars[candidates[p].1]),
                ),
                (
                    point(&reference[candidates[q].0]),
                    point(&frame_stars[candidates[q].1]),
                ),
            ];
            let Some(transform) = Transform::fit(&pairs) else {
                continue;
            };
            let scale = transform.a.hypot(transform.b);
            if (scale - 1.0).abs() > MAX_SCALE_CHANGE {
                continue;
            }
            let inliers: Vec<(usize, usize)> = candidates
                .iter()
                .filter(|(i, j, _)| {
                    let (x, y) = transform.apply(reference[*i].x as f64, reference[*i].y as f64);
                    (x - frame_stars[*j].x as f64).hypot(y - frame_stars[*j].y as f64) <= INLIER_PX
                })
                .map(|(i, j, _)| (*i, *j))
                .collect();
            if best.as_ref().is_none_or(|(_, b)| inliers.len() > b.len()) {
                best = Some((transform, inliers));
            }
        }
    }
    let (transform, inliers) = best?;
    if inliers.len() < MIN_MATCHES {
        return None;
    }
    // Refined on every reference star with a frame star where it maps.
    let mut pairs: Vec<PointPair> = Vec::new();
    for r in reference {
        let (x, y) = transform.apply(r.x as f64, r.y as f64);
        if let Some(s) = stars.iter().min_by(|a, b| {
            (a.x as f64 - x)
                .hypot(a.y as f64 - y)
                .total_cmp(&(b.x as f64 - x).hypot(b.y as f64 - y))
        }) && (s.x as f64 - x).hypot(s.y as f64 - y) <= INLIER_PX
        {
            pairs.push((point(r), point(s)));
        }
    }
    let refined = if pairs.len() >= MIN_MATCHES {
        Transform::fit(&pairs)?
    } else {
        transform
    };
    Some((refined, pairs.len().max(inliers.len())))
}

const MAGIC: &[u8] = b"YACANA-STACK-1\n";
/// The first frame's stars kept: the brightest, for the alignment and the white balance.
const REFERENCE_STARS: usize = 120;
/// The brightest stars whose triangles are matched: C(25, 3) = 2,300 triangles each.
const TRIANGLE_STARS: usize = 25;
/// When those are not enough: C(50, 3) = 19,600 triangles each.
const MORE_TRIANGLE_STARS: usize = 50;
const BALANCE_STARS: usize = 120;
const MIN_BALANCE_STARS: usize = 10;
const MIN_MATCHES: usize = 6;
const MIN_VOTES: u32 = 2;
const MAX_CANDIDATES: usize = 30;
const SHAPE_TOLERANCE: f32 = 0.008;
const MIN_SIDE: f64 = 20.0;
const MIN_SHAPE: f64 = 0.1;
const DISTINCT: f64 = 0.03;
const INLIER_PX: f64 = 3.0;
const MAX_SCALE_CHANGE: f64 = 0.02;
/// From this many frames on, a value further than KAPPA standard deviations from its pixel's mean is left out.
const CLIP_FROM: u32 = 5;
const KAPPA: f32 = 3.0;
const BACKGROUND_STRIDE: usize = 16;
const FOOTPRINT_SAMPLES: usize = 4;
const APERTURE_HFRS: f64 = 2.5;
const MIN_APERTURE: f64 = 3.0;
const MAX_APERTURE: f64 = 12.0;
const SATURATION: f32 = 60_000.0;
const MIN_GAIN: f32 = 0.25;
const MAX_GAIN: f32 = 4.0;
const JPEG_QUALITY: u8 = 92;

#[cfg(test)]
mod tests {
    use super::*;
    use crate::image::BayerPattern;

    /// A pseudo-random generator, the same each run.
    struct Random(u64);

    impl Random {
        fn next(&mut self) -> f64 {
            self.0 ^= self.0 << 13;
            self.0 ^= self.0 >> 7;
            self.0 ^= self.0 << 17;
            (self.0 >> 11) as f64 / (1u64 << 53) as f64
        }
    }

    fn field(count: usize, width: f64, height: f64, seed: u64) -> Vec<StackStar> {
        let mut random = Random(seed);
        (0..count)
            .map(|_| StackStar {
                x: (random.next() * width) as f32,
                y: (random.next() * height) as f32,
                brightness: 100.0 + random.next() * 5_000.0,
                hfr: 2.0,
            })
            .collect()
    }

    fn moved(stars: &[StackStar], transform: &Transform) -> Vec<StackStar> {
        stars
            .iter()
            .map(|s| {
                let (x, y) = transform.apply(s.x as f64, s.y as f64);
                StackStar {
                    x: x as f32,
                    y: y as f32,
                    ..*s
                }
            })
            .collect()
    }

    #[test]
    fn a_turned_and_shifted_field_is_aligned() {
        let reference = field(60, 2000.0, 3000.0, 7);
        let mut sorted = reference.clone();
        sorted.sort_by(|a, b| b.brightness.total_cmp(&a.brightness));
        let triangles = triangles(&sorted[..TRIANGLE_STARS]);
        let angle = 1.7f64.to_radians();
        let truth = Transform {
            a: angle.cos(),
            b: angle.sin(),
            tx: 14.3,
            ty: -9.6,
        };
        // A few stars lost and a few new ones, as between two real frames.
        let mut stars = moved(&sorted[3..], &truth);
        stars.extend(field(5, 2000.0, 3000.0, 99));
        stars.sort_by(|a, b| b.brightness.total_cmp(&a.brightness));

        let (found, matched) = align(&sorted, &triangles, &stars).expect("aligned");

        assert!(matched >= 40, "{matched} matched");
        assert!(
            (found.rotation_deg() - 1.7).abs() < 0.01,
            "{}°",
            found.rotation_deg()
        );
        assert!(
            (found.tx - truth.tx).abs() < 0.05 && (found.ty - truth.ty).abs() < 0.05,
            "{found:?}"
        );
    }

    #[test]
    fn a_field_whose_brightest_stars_changed_is_aligned_with_more_of_them() {
        let reference = field(80, 2000.0, 3000.0, 12);
        let mut sorted = reference.clone();
        sorted.sort_by(|a, b| b.brightness.total_cmp(&a.brightness));
        let triangles = triangles(&sorted[..TRIANGLE_STARS]);
        let truth = Transform {
            a: 1.0,
            b: 0.0,
            tx: -61.0,
            ty: 38.0,
        };
        // The 50 brightest in the opposite order: none of the first frame's 25 brightest is among this one's.
        let mut stars = moved(&sorted, &truth);
        let brightness: Vec<f64> = stars[..MORE_TRIANGLE_STARS]
            .iter()
            .map(|s| s.brightness)
            .collect();
        for (star, b) in stars[..MORE_TRIANGLE_STARS]
            .iter_mut()
            .zip(brightness.iter().rev())
        {
            star.brightness = *b;
        }
        stars.sort_by(|a, b| b.brightness.total_cmp(&a.brightness));

        assert!(align_brightest(&sorted, &triangles, &stars, TRIANGLE_STARS).is_none());
        let (found, matched) = align(&sorted, &triangles, &stars).expect("aligned");

        assert!(matched >= 40, "{matched} matched");
        assert!(
            (found.tx - truth.tx).abs() < 0.05 && (found.ty - truth.ty).abs() < 0.05,
            "{found:?}"
        );
    }

    #[test]
    fn a_field_of_other_stars_is_not_aligned() {
        let mut reference = field(60, 2000.0, 3000.0, 1);
        reference.sort_by(|a, b| b.brightness.total_cmp(&a.brightness));
        let mut other = field(60, 2000.0, 3000.0, 2);
        other.sort_by(|a, b| b.brightness.total_cmp(&a.brightness));

        assert!(align(&reference, &triangles(&reference[..TRIANGLE_STARS]), &other).is_none());
    }

    /// A mono frame of `stars` on a background of 1000 ADU with noise, and a bright line across
    /// it if `trail`, as a satellite leaves.
    fn frame(stars: &[StackStar], size: usize, seed: u64, trail: bool) -> Image {
        let mut random = Random(seed);
        let mut pixels: Vec<f64> = (0..size * size)
            .map(|_| 1000.0 + (random.next() - 0.5) * 200.0)
            .collect();
        for s in stars {
            for y in 0..size {
                for x in 0..size {
                    let d2 = (x as f64 - s.x as f64).powi(2) + (y as f64 - s.y as f64).powi(2);
                    if d2 < 40.0 {
                        pixels[y * size + x] += s.brightness * (-d2 / 4.0).exp();
                    }
                }
            }
        }
        if trail {
            for x in 0..size {
                pixels[(size / 3) * size + x] += 20_000.0;
            }
        }
        Image::new(
            size,
            size,
            pixels
                .iter()
                .map(|v| v.clamp(0.0, 65_535.0) as u16)
                .collect(),
            None,
        )
    }

    #[test]
    fn frames_stack_into_less_noise_and_without_a_satellite() {
        let size = 300;
        // The stars on the right, so that the corner on the left is only sky.
        let mut stars: Vec<StackStar> = field(40, 180.0, size as f64, 3)
            .into_iter()
            .map(|s| StackStar {
                x: s.x + 110.0,
                ..s
            })
            .collect();
        stars.sort_by(|a, b| b.brightness.total_cmp(&a.brightness));
        let stack = LiveStack::default();
        for n in 0..12u64 {
            let shift = Transform {
                a: 1.0,
                b: 0.0,
                tx: (n % 3) as f64,
                ty: (n % 4) as f64,
            };
            let these = moved(&stars, &shift);
            let added = stack.add(
                Arc::new(frame(&these, size, 50 + n, n == 9)),
                these,
                10.0,
                None,
                None,
            );
            assert!(added.added, "frame {n}");
        }
        let guard = stack.state.lock().unwrap();
        let state = guard.as_ref().unwrap();
        assert_eq!(state.frames, 12);
        assert_eq!(state.exposure_seconds, 120.0);
        // The background's spread, in a corner with no stars, is a third of a frame's or less.
        let corner: Vec<f32> = (200..290)
            .flat_map(|y| (5..60).map(move |x| (x, y)))
            .map(|(x, y)| state.mean[y * size + x])
            .collect();
        let mean = corner.iter().sum::<f32>() / corner.len() as f32;
        let spread =
            (corner.iter().map(|v| (v - mean).powi(2)).sum::<f32>() / corner.len() as f32).sqrt();
        assert!(spread < 58.0 / 2.5, "spread {spread}");
        // The satellite's line is gone: its row is the background.
        let row: f32 = (5..100)
            .map(|x| state.mean[(size / 3) * size + x])
            .sum::<f32>()
            / 95.0;
        assert!(
            (row - mean).abs() < 120.0,
            "row at {row}, background at {mean}"
        );
    }

    #[test]
    fn the_white_balance_makes_the_typical_star_white() {
        let size = 400;
        let mut stars = field(60, size as f64, size as f64, 11);
        stars.sort_by(|a, b| b.brightness.total_cmp(&a.brightness));
        // Stars twice as red and half as blue as they are green, on a grey sky, in an RGGB mosaic.
        let mono = frame(&stars, size, 5, false);
        let pixels = mono
            .pixels
            .iter()
            .enumerate()
            .map(|(i, &v)| {
                let above = v as f64 - 1000.0;
                let factor = match BayerPattern::Rggb.channel(i % size, i / size) {
                    0 => 2.0,
                    1 => 1.0,
                    _ => 0.5,
                };
                (1000.0 + above * factor).clamp(0.0, 65_535.0) as u16
            })
            .collect();
        let stack = LiveStack::default();
        stack.add(
            Arc::new(Image::new(size, size, pixels, Some(BayerPattern::Rggb))),
            stars,
            10.0,
            None,
            None,
        );

        let info = stack.info();

        assert!(info.balance_stars >= MIN_BALANCE_STARS as u32, "{info:?}");
        assert!(
            (info.red_gain - 0.5).abs() < 0.05 && (info.blue_gain - 2.0).abs() < 0.2,
            "{info:?}"
        );
    }

    #[test]
    fn a_frame_loses_its_dark_and_its_vignetting_with_its_masters() {
        let size = 32;
        // A sky of 500 ADU, vignetted to half at the left, over a bias of 1000 with a hot pixel.
        let vignette = |x: usize| if x < 8 { 0.5 } else { 1.0 };
        let hot = |x: usize, y: usize| if (x, y) == (20, 12) { 3000.0 } else { 0.0 };
        let raw = |sky: f64| {
            let pixels = (0..size * size)
                .map(|i| (1000.0 + hot(i % size, i / size) + sky * vignette(i % size)) as u16)
                .collect();
            Arc::new(Image::new(size, size, pixels, Some(BayerPattern::Rggb)))
        };
        let dark = MasterFrame::new();
        dark.add(raw(0.0));
        let flats = MasterFrame::new();
        flats.add(raw(20_000.0));
        let bias = MasterFrame::new();
        bias.add(Arc::new(Image::new(
            size,
            size,
            vec![1000; size * size],
            Some(BayerPattern::Rggb),
        )));
        let flat = flats.flat(Some(bias)).unwrap();
        let light = raw(500.0);

        let (planes, with_dark, with_flat) = Planes::of(
            &light,
            Some(&dark.state.lock().unwrap()),
            Some(&flat.state.lock().unwrap()),
        );
        assert!(with_dark && with_flat);
        let green = planes.plane(1);
        for (x, y) in [(2, 16), (16, 16), (20, 12), (28, 4)] {
            assert!(
                (green[y * size + x] - 500.0).abs() < 30.0,
                "({x}, {y}): {}",
                green[y * size + x]
            );
        }

        // Without a dark, the flat goes after its bias level; without either, it is not used.
        let (planes, with_dark, with_flat) =
            Planes::of(&light, None, Some(&flat.state.lock().unwrap()));
        assert!(!with_dark && with_flat);
        assert!((planes.plane(1)[16 * size + 2] - 500.0).abs() < 30.0);
        let unbiased = flats.flat(None).unwrap();
        let (_, _, with_flat) = Planes::of(&light, None, Some(&unbiased.state.lock().unwrap()));
        assert!(!with_flat, "the vignetting would come back upside down");
    }

    #[test]
    fn a_checkpoint_comes_back_as_it_was() {
        let size = 120;
        let stars = field(30, size as f64, size as f64, 21);
        let stack = LiveStack::default();
        stack.add(
            Arc::new(frame(&stars, size, 1, false)),
            stars.clone(),
            20.0,
            None,
            None,
        );
        let path = std::env::temp_dir().join(format!("yacana-stack-{}.bin", std::process::id()));

        assert_eq!(stack.label(), "");
        stack
            .checkpoint(path.to_string_lossy().into(), "C 20 · LP".into())
            .unwrap();
        let back = LiveStack::restore(path.to_string_lossy().into()).unwrap();

        assert_eq!(back.label(), "C 20 · LP");

        let (a, b) = (stack.state.lock().unwrap(), back.state.lock().unwrap());
        let (a, b) = (a.as_ref().unwrap(), b.as_ref().unwrap());
        assert_eq!(
            (a.width, a.height, a.frames, a.exposure_seconds),
            (b.width, b.height, b.frames, b.exposure_seconds)
        );
        assert_eq!(a.mean, b.mean);
        assert_eq!(a.reference, b.reference);
        assert!(
            LiveStack::restore(path.with_extension("missing").to_string_lossy().into()).is_err()
        );
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn its_linear_image_is_the_mean_of_the_stack_under_each_pixel() {
        let size = 64;
        let stars = field(20, size as f64, size as f64, 4);
        let stack = LiveStack::default();
        assert!(
            stack.linear(4, 4).floats().iter().all(|v| v.is_nan()),
            "empty, nothing reaches"
        );
        stack.add(
            Arc::new(frame(&stars, size, 2, false)),
            stars,
            20.0,
            None,
            None,
        );

        let small = stack.linear(16, 16);
        let values = small.floats();

        assert_eq!(
            (small.width, small.height, values.len()),
            (16, 16, 16 * 16 * 3)
        );
        let guard = stack.state.lock().unwrap();
        let state = guard.as_ref().unwrap();
        // Its first pixel is the mean of the stack's top-left 4 × 4, channel by channel.
        for (c, &value) in values.iter().enumerate().take(3) {
            let plane = state.mean_plane(c);
            let mean = (0..4)
                .flat_map(|y| (0..4).map(move |x| (x, y)))
                .map(|(x, y)| plane[y * size + x] as f64)
                .sum::<f64>()
                / 16.0;
            assert!((value as f64 - mean).abs() < 1e-2, "{c}: {value} vs {mean}");
        }
    }

    #[test]
    fn it_saves_as_a_linear_float_fits_and_a_jpeg() {
        let size = 64;
        let stars = field(20, size as f64, size as f64, 4);
        let stack = LiveStack::default();
        stack.add(
            Arc::new(frame(&stars, size, 2, false)),
            stars,
            20.0,
            None,
            None,
        );
        let dir = std::env::temp_dir().join(format!("yacana-stack-save-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let (fits, jpeg) = (dir.join("stack.fits"), dir.join("stack.jpg"));

        stack.save(fits.to_string_lossy().into(), vec![]).unwrap();
        stack
            .save_jpeg(jpeg.to_string_lossy().into(), true)
            .unwrap();

        let bytes = std::fs::read(&fits).unwrap();
        let header = String::from_utf8_lossy(&bytes[..2880]);
        assert!(
            header.contains("BITPIX  =                  -32"),
            "{header}"
        );
        assert!(header.contains("NAXIS   =                    2"));
        assert_eq!(bytes.len(), 2880 + (size * size * 4).div_ceil(2880) * 2880);
        let jpeg = std::fs::read(&jpeg).unwrap();
        assert_eq!(&jpeg[..2], &[0xFF, 0xD8], "a JPEG");
        std::fs::remove_dir_all(dir).unwrap();
    }

    /// Stacks the real frames in `YACANA_STACK_DIR` (a night of the Seestar's), the first
    /// `YACANA_STACK_FRAMES` of them, and writes `stack.jpg` and `stack-camera.jpg` in
    /// `YACANA_STACK_OUT`, never among the frames; prints how each one went.
    #[test]
    #[ignore = "needs real frames: YACANA_STACK_DIR=<folder> cargo test --release stacks_a_real_night -- --ignored --nocapture"]
    fn stacks_a_real_night() {
        let dir = std::env::var("YACANA_STACK_DIR").expect("YACANA_STACK_DIR");
        let limit: usize = std::env::var("YACANA_STACK_FRAMES")
            .ok()
            .and_then(|n| n.parse().ok())
            .unwrap_or(30);
        let mut files: Vec<_> = std::fs::read_dir(&dir)
            .unwrap()
            .flatten()
            .map(|e| e.path())
            .filter(|p| p.extension().is_some_and(|e| e == "fit" || e == "fits"))
            .collect();
        files.sort();
        let stack = LiveStack::default();
        let started = std::time::Instant::now();
        for path in files.iter().take(limit) {
            let (image, pixel, focal) = crate::fits::read_for_tests(&path.to_string_lossy());
            let t = std::time::Instant::now();
            let detection = image.detect_stars(crate::stars::StarSensitivity::Normal, pixel, focal);
            let detect_ms = t.elapsed().as_millis();
            let stars = detection
                .stars
                .iter()
                .map(|s| StackStar {
                    x: s.x,
                    y: s.y,
                    brightness: s.average_brightness,
                    hfr: s.hfr,
                })
                .collect();
            let t = std::time::Instant::now();
            let added = stack.add(Arc::new(image), stars, 20.0, None, None);
            println!(
                "{}: {} stars in {detect_ms} ms, added {} in {} ms, {:.3}°, {:.1} px, {} matched",
                path.file_name().unwrap().to_string_lossy(),
                detection.stars.len(),
                added.added,
                t.elapsed().as_millis(),
                added.rotation_deg,
                added.shift_px,
                added.matched_stars
            );
        }
        println!("{:?} in {:?}", stack.info(), started.elapsed());
        let out = std::env::var("YACANA_STACK_OUT").expect("YACANA_STACK_OUT");
        stack.save_jpeg(format!("{out}/stack.jpg"), true).unwrap();
        stack
            .save_jpeg(format!("{out}/stack-camera.jpg"), false)
            .unwrap();
    }
}
