// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

//! Alpaca's ImageBytes format (ASCOM Alpaca API, "ImageBytes"): a 44-byte header of
//! little-endian 32-bit integers, then the pixels.
//!
//! The pixels of a camera's `ImageArray[x, y]` come with the last index changing fastest, so
//! a column at a time. This module turns them into rows from the top-left corner.

use rayon::prelude::*;

use crate::EngineError;
use crate::image::{BayerPattern, Image};

pub(crate) const HEADER_LEN: usize = 44;

/// ASCOM's `ImageArrayElementTypes`, for the element type of the transmitted data.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ElementType {
    Int16 = 1,
    Int32 = 2,
    Double = 3,
    Single = 4,
    Byte = 6,
    UInt16 = 8,
    UInt32 = 9,
}

impl ElementType {
    fn from_code(code: i32) -> Option<ElementType> {
        Some(match code {
            1 => ElementType::Int16,
            2 => ElementType::Int32,
            3 => ElementType::Double,
            4 => ElementType::Single,
            6 => ElementType::Byte,
            8 => ElementType::UInt16,
            9 => ElementType::UInt32,
            _ => return None,
        })
    }

    fn size(self) -> usize {
        match self {
            ElementType::Byte => 1,
            ElementType::Int16 | ElementType::UInt16 => 2,
            ElementType::Int32 | ElementType::UInt32 | ElementType::Single => 4,
            ElementType::Double => 8,
        }
    }

    /// The element at `bytes` as a 16-bit pixel value, clamped to 0–65,535.
    fn read(self, bytes: &[u8]) -> u16 {
        let clamp = |v: f64| v.round().clamp(0.0, u16::MAX as f64) as u16;
        match self {
            ElementType::Byte => bytes[0] as u16,
            ElementType::UInt16 => u16::from_le_bytes([bytes[0], bytes[1]]),
            ElementType::Int16 => i16::from_le_bytes([bytes[0], bytes[1]]).max(0) as u16,
            ElementType::Int32 => {
                i32::from_le_bytes(bytes[..4].try_into().unwrap()).clamp(0, u16::MAX as i32) as u16
            }
            ElementType::UInt32 => {
                u32::from_le_bytes(bytes[..4].try_into().unwrap()).min(u16::MAX as u32) as u16
            }
            ElementType::Single => clamp(f32::from_le_bytes(bytes[..4].try_into().unwrap()) as f64),
            ElementType::Double => clamp(f64::from_le_bytes(bytes[..8].try_into().unwrap())),
        }
    }
}

/// What the header says.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct Header {
    pub error_number: i32,
    pub data_start: usize,
    pub transmission: i32,
    pub rank: i32,
    pub width: usize,
    pub height: usize,
    pub planes: usize,
}

pub(crate) fn parse_header(bytes: &[u8]) -> Result<Header, EngineError> {
    if bytes.len() < HEADER_LEN {
        return Err(format_error(format!(
            "ImageBytes header is {} bytes, not {HEADER_LEN}",
            bytes.len()
        )));
    }
    let field = |i: usize| i32::from_le_bytes(bytes[i * 4..i * 4 + 4].try_into().unwrap());
    let version = field(0);
    if version != 1 {
        return Err(format_error(format!(
            "unsupported ImageBytes metadata version {version}"
        )));
    }
    Ok(Header {
        error_number: field(1),
        data_start: field(4).max(HEADER_LEN as i32) as usize,
        transmission: field(6),
        rank: field(7),
        width: field(8).max(0) as usize,
        height: field(9).max(0) as usize,
        planes: field(10).max(1) as usize,
    })
}

/// The image in a whole ImageBytes body, header included.
pub(crate) fn decode(body: &[u8], bayer: Option<BayerPattern>) -> Result<Image, EngineError> {
    let header = parse_header(body)?;
    let data = body.get(header.data_start..).unwrap_or_default();
    if header.error_number != 0 {
        return Err(EngineError::Alpaca {
            number: header.error_number,
            reason: String::from_utf8_lossy(data).trim().to_owned(),
        });
    }
    if header.rank != 2 {
        return Err(format_error(format!(
            "only monochrome or Bayer image arrays are supported, not rank {}",
            header.rank
        )));
    }
    let element = ElementType::from_code(header.transmission)
        .ok_or_else(|| format_error(format!("unknown element type {}", header.transmission)))?;
    let (width, height, size) = (header.width, header.height, element.size());
    let expected = width * height * size;
    if data.len() < expected {
        return Err(format_error(format!(
            "the image is cut short: {} bytes of {expected}",
            data.len()
        )));
    }
    let mut pixels = vec![0u16; width * height];
    pixels
        .par_chunks_mut(width.max(1))
        .enumerate()
        .for_each(|(y, row)| {
            for (x, pixel) in row.iter_mut().enumerate() {
                let at = (x * height + y) * size;
                *pixel = element.read(&data[at..at + size]);
            }
        });
    Ok(Image::new(width, height, pixels, bayer))
}

fn format_error(message: String) -> EngineError {
    EngineError::Format { reason: message }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    /// An ImageBytes body for `columns`: one inner list per x, with one value per y.
    pub(crate) fn body(transmission: i32, element_size: usize, columns: &[Vec<i64>]) -> Vec<u8> {
        let (width, height) = (columns.len() as i32, columns[0].len() as i32);
        let mut bytes = Vec::new();
        for value in [
            1,
            0,
            7,
            7,
            HEADER_LEN as i32,
            2,
            transmission,
            2,
            width,
            height,
            0,
        ] {
            bytes.extend(value.to_le_bytes());
        }
        for column in columns {
            for &value in column {
                bytes.extend(&value.to_le_bytes()[..element_size]);
            }
        }
        bytes
    }

    #[test]
    fn columns_become_rows() {
        // ImageArray[x][y] for a 3 × 2 image: x = 0 is (1, 4), x = 1 is (2, 5), x = 2 is (3, 6).
        let columns = vec![vec![1, 4], vec![2, 5], vec![3, 6]];
        let image = decode(&body(8, 2, &columns), None).unwrap();
        assert_eq!((image.width, image.height), (3, 2));
        assert_eq!(image.pixels, vec![1, 2, 3, 4, 5, 6]);
    }

    #[test]
    fn int32_values_sent_as_16_bits_like_the_seestar() {
        // The Seestar declares Int32 elements and sends them as UInt16.
        let image = decode(
            &body(8, 2, &[vec![65535], vec![1234]]),
            Some(BayerPattern::Grbg),
        )
        .unwrap();
        assert_eq!(image.pixels, vec![65535, 1234]);
        assert_eq!(image.bayer, Some(BayerPattern::Grbg));
    }

    #[test]
    fn wider_types_are_clamped_to_16_bits() {
        let image = decode(&body(2, 4, &[vec![-5], vec![70_000], vec![300]]), None).unwrap();
        assert_eq!(image.pixels, vec![0, 65535, 300]);
    }

    #[test]
    fn a_device_error_comes_back_with_its_message() {
        let mut bytes = Vec::new();
        for value in [1, 0x40B, 0, 0, HEADER_LEN as i32, 0, 0, 0, 0, 0, 0] {
            bytes.extend(value.to_le_bytes());
        }
        bytes.extend(b"No image is ready");
        match decode(&bytes, None) {
            Err(EngineError::Alpaca { number, reason }) => {
                assert_eq!(number, 0x40B);
                assert_eq!(reason, "No image is ready");
            }
            other => panic!("expected an Alpaca error, got {other:?}"),
        }
    }

    #[test]
    fn a_short_body_is_an_error() {
        let mut bytes = body(8, 2, &[vec![1, 2], vec![3, 4]]);
        bytes.truncate(bytes.len() - 1);
        assert!(matches!(
            decode(&bytes, None),
            Err(EngineError::Format { .. })
        ));
        assert!(matches!(
            decode(&bytes[..10], None),
            Err(EngineError::Format { .. })
        ));
    }
}
