// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

//! Yacana pixel engine.
//!
//! Pixels live here, in native memory, and never cross into Kotlin: the app
//! gets image handles and small results (ARCHITECTURE.md).
//! The API stays coarse: few calls that each do a lot of work.

use std::io::Read;
use std::sync::Arc;
use std::time::Duration;

mod fits;
mod http;
mod image;
mod imagebytes;
mod master;
mod preview;
mod psf;
mod stack;
mod stars;

pub use fits::{HeaderCard, HeaderValue};
pub use image::{BayerPattern, ColourStretch, Image, Statistics, Stretch};
pub use master::MasterFrame;
pub use preview::{PreviewImage, Region};
pub use stack::{LinearImage, LiveStack, StackAddition, StackInfo, StackStar};
pub use stars::{DetectedStar, StarDetection, StarSensitivity};

uniffi::setup_scaffolding!();

/// What the app needs to know about the engine it loaded.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct BuildInfo {
    /// Crate version, such as `0.1.0`.
    pub version: String,
    /// CPU architecture the library was built for, such as `aarch64`.
    pub arch: String,
}

/// Returns the version and architecture of this build of the engine.
/// `rgba` (`width` × `height` pixels, four bytes each) as a JPG, as the live mosaic is kept.
#[uniffi::export]
pub fn encode_jpeg(width: u32, height: u32, rgba: Vec<u8>) -> Result<Vec<u8>, EngineError> {
    let pixels = width as usize * height as usize;
    if rgba.len() != pixels * 4 || width > u16::MAX as u32 || height > u16::MAX as u32 {
        return Err(EngineError::Format {
            reason: "the image is not width × height pixels of four bytes".into(),
        });
    }
    let rgb: Vec<u8> = rgba.chunks(4).flat_map(|p| [p[0], p[1], p[2]]).collect();
    let mut out = Vec::new();
    jpeg_encoder::Encoder::new(&mut out, MOSAIC_JPEG_QUALITY)
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

/// As the stacks' JPGs.
const MOSAIC_JPEG_QUALITY: u8 = 92;

#[uniffi::export]
pub fn build_info() -> BuildInfo {
    BuildInfo {
        version: env!("CARGO_PKG_VERSION").to_owned(),
        arch: std::env::consts::ARCH.to_owned(),
    }
}

/// Why an engine call failed.
#[derive(Debug, Clone, PartialEq, uniffi::Error)]
pub enum EngineError {
    /// The equipment could not be reached, or the connection broke.
    Network { reason: String },
    /// The server answered with an HTTP error.
    Http { status: u16, reason: String },
    /// The device reported an Alpaca error, such as 0x40B when no image is ready.
    Alpaca { number: i32, reason: String },
    /// The answer was not an image this engine can read.
    Format { reason: String },
    /// A file could not be written.
    File { reason: String },
}

impl std::fmt::Display for EngineError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            EngineError::Network { reason } => write!(f, "network: {reason}"),
            EngineError::Http { status, reason } => write!(f, "HTTP {status}: {reason}"),
            EngineError::Alpaca { number, reason } => {
                write!(f, "Alpaca error 0x{number:X}: {reason}")
            }
            EngineError::Format { reason } => write!(f, "format: {reason}"),
            EngineError::File { reason } => write!(f, "file: {reason}"),
        }
    }
}

impl std::error::Error for EngineError {}

/// Downloads the last image of an Alpaca camera as ImageBytes, straight into native memory.
///
/// - `image_array_url` is the camera's `imagearray` URL, such as
///   `http://10.0.0.1:32323/api/v1/camera/0/imagearray`.
/// - `network_handle` is the equipment's `Network.getNetworkHandle()` on Android, so the socket
///   goes through its WiFi even without internet (ARCHITECTURE.md); null uses the default network.
/// - `bayer` is the camera's Bayer pattern from the top-left pixel, or null for a mono camera.
///
/// It blocks the calling thread for the download, about 2 s for a Seestar frame (spike S3):
/// call it from a background thread.
#[uniffi::export]
pub fn download_image(
    image_array_url: String,
    network_handle: Option<u64>,
    bayer: Option<BayerPattern>,
    timeout_ms: u32,
) -> Result<Arc<Image>, EngineError> {
    let timeout = Duration::from_millis(timeout_ms.max(1) as u64);
    let mut response = http::get(
        &image_array_url,
        "application/imagebytes",
        network_handle,
        timeout,
    )?;
    let mut body = Vec::new();
    response
        .body
        .read_to_end(&mut body)
        .map_err(|e| EngineError::Network {
            reason: e.to_string(),
        })?;
    if response.status != 200 {
        return Err(EngineError::Http {
            status: response.status,
            reason: String::from_utf8_lossy(&body).chars().take(200).collect(),
        });
    }
    if !response.content_type.starts_with("application/imagebytes") {
        return Err(EngineError::Format {
            reason: format!(
                "the camera sent {:?}, not ImageBytes",
                response.content_type
            ),
        });
    }
    Ok(Arc::new(imagebytes::decode(&body, bayer)?))
}

#[uniffi::export]
impl Image {
    /// Saves a FITS file at `path` without ever leaving half a file there: it
    /// writes `path.part`, flushes it to the storage, and only then renames it to `path`. A crash
    /// leaves at most a `.part` file, never a broken frame under the final name.
    pub fn save(&self, path: String, headers: Vec<HeaderCard>) -> Result<(), EngineError> {
        let file_error = |e: std::io::Error| EngineError::File {
            reason: format!("{path}: {e}"),
        };
        let part = format!("{path}.part");
        let file = std::fs::File::create(&part).map_err(file_error)?;
        let mut out = std::io::BufWriter::with_capacity(1 << 20, file);
        fits::write(self, &headers, &mut out).map_err(file_error)?;
        let file = out.into_inner().map_err(|e| file_error(e.into_error()))?;
        file.sync_all().map_err(file_error)?;
        drop(file);
        std::fs::rename(&part, &path).map_err(file_error)
    }

    /// Writes a FITS file into an open file that the caller hands over and this call closes, such
    /// as a file of the folder the user picked through Android's file picker. The caller writes to
    /// a temporary name and renames it after this returns, as [Image::save] does.
    pub fn write_to_descriptor(
        &self,
        fd: i32,
        headers: Vec<HeaderCard>,
    ) -> Result<(), EngineError> {
        let file = descriptor_file(fd)?;
        let mut out = std::io::BufWriter::with_capacity(1 << 20, file);
        let file_error = |e: std::io::Error| EngineError::File {
            reason: e.to_string(),
        };
        fits::write(self, &headers, &mut out).map_err(file_error)?;
        let file = out.into_inner().map_err(|e| file_error(e.into_error()))?;
        file.sync_all().map_err(file_error)
    }
}

#[cfg(unix)]
fn descriptor_file(fd: i32) -> Result<std::fs::File, EngineError> {
    use std::os::fd::FromRawFd;
    if fd < 0 {
        return Err(EngineError::File {
            reason: format!("bad file descriptor {fd}"),
        });
    }
    // SAFETY: the caller hands the descriptor over (ParcelFileDescriptor.detachFd()), so this
    // File is its only owner and closes it.
    Ok(unsafe { std::fs::File::from_raw_fd(fd) })
}

#[cfg(not(unix))]
fn descriptor_file(_fd: i32) -> Result<std::fs::File, EngineError> {
    Err(EngineError::File {
        reason: "file descriptors are only supported on Android".to_owned(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rgba_becomes_a_jpeg_and_a_wrong_size_is_refused() {
        let jpeg = encode_jpeg(2, 2, vec![255u8; 16]).unwrap();
        assert_eq!(&jpeg[..2], &[0xFF, 0xD8]);
        assert!(encode_jpeg(2, 2, vec![0u8; 12]).is_err());
    }

    #[test]
    fn build_info_reports_crate_version_and_arch() {
        let info = build_info();
        assert_eq!(info.version, env!("CARGO_PKG_VERSION"));
        assert_eq!(info.arch, std::env::consts::ARCH);
    }

    /// A download from a tiny HTTP server on this machine, in chunks as ASP.NET sends them.
    #[test]
    fn downloads_imagebytes_over_http() {
        use std::io::Write;
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let body = imagebytes::tests::body(8, 2, &[vec![10, 40], vec![20, 50], vec![30, 60]]);
        let server = std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            // The request may arrive in pieces: read up to the blank line that ends it.
            let mut request = Vec::new();
            let mut byte = [0u8; 1];
            while !request.ends_with(b"\r\n\r\n") {
                stream.read_exact(&mut byte).unwrap();
                request.push(byte[0]);
            }
            assert!(String::from_utf8_lossy(&request).contains("Accept: application/imagebytes"));
            write!(stream, "HTTP/1.1 200 OK\r\nContent-Type: application/imagebytes\r\nTransfer-Encoding: chunked\r\n\r\n").unwrap();
            for chunk in body.chunks(17) {
                write!(stream, "{:x}\r\n", chunk.len()).unwrap();
                stream.write_all(chunk).unwrap();
                write!(stream, "\r\n").unwrap();
            }
            write!(stream, "0\r\n\r\n").unwrap();
        });
        let url = format!("http://127.0.0.1:{port}/api/v1/camera/0/imagearray");
        let image = download_image(url, None, None, 5_000).unwrap();
        server.join().unwrap();
        assert_eq!((image.width(), image.height()), (3, 2));
        assert_eq!(image.pixels, vec![10, 20, 30, 40, 50, 60]);
    }

    /// The camera simulator of OmniSim, when OMNISIM_URL is set.
    #[test]
    fn downloads_from_omnisim() {
        let Ok(url) = std::env::var("OMNISIM_URL") else {
            println!("OMNISIM_URL is not set: skipping the OmniSim download test");
            return;
        };
        let url = url.trim_end_matches('/');
        let put = |member: &str, body: &str| {
            let address = url.trim_start_matches("http://");
            let mut stream = std::net::TcpStream::connect(address).unwrap();
            use std::io::Write;
            write!(
                stream,
                "PUT /api/v1/camera/0/{member} HTTP/1.1\r\nHost: {address}\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len()
            )
            .unwrap();
            let mut answer = String::new();
            stream.read_to_string(&mut answer).unwrap();
            assert!(answer.contains("\"ErrorNumber\":0"), "{member}: {answer}");
        };
        put("connected", "Connected=true");
        put("startexposure", "Duration=0.1&Light=true");
        std::thread::sleep(Duration::from_secs(2));
        let image = download_image(
            format!("{url}/api/v1/camera/0/imagearray"),
            None,
            None,
            30_000,
        )
        .unwrap();
        put("connected", "Connected=false");
        assert!(image.width() > 0 && image.height() > 0);
        let stats = image.statistics();
        assert!(stats.max > stats.min, "a flat image: {stats:?}");
    }

    fn headers() -> Vec<HeaderCard> {
        vec![HeaderCard {
            key: "EXPTIME".into(),
            value: HeaderValue::Real { value: 20.0 },
            comment: "seconds".into(),
        }]
    }

    #[test]
    fn saves_through_a_part_file() {
        let dir = std::env::temp_dir().join(format!("yacana-save-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let image = Image::new(4, 2, (0..8).collect(), Some(BayerPattern::Grbg));
        let path = dir.join("frame.fits");
        image
            .save(path.to_string_lossy().into(), headers())
            .unwrap();
        assert!(path.is_file());
        assert!(!dir.join("frame.fits.part").exists());
        let fits = std::fs::read(dir.join("frame.fits")).unwrap();
        assert_eq!(fits.len(), 2 * 2880);
        assert!(
            String::from_utf8_lossy(&fits[..2880])
                .contains("EXPTIME =                 20.0 / seconds")
        );
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn a_failed_save_leaves_no_file_under_the_final_name() {
        let image = Image::new(1, 1, vec![0], None);
        let missing = std::env::temp_dir()
            .join("yacana-no-such-folder")
            .join("frame.fits");
        let result = image.save(missing.to_string_lossy().into(), headers());
        assert!(matches!(result, Err(EngineError::File { .. })));
        assert!(!missing.exists());
    }
}
