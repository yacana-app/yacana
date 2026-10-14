// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

//! FITS files: a primary header of 80-character cards in 2,880-byte blocks, then the
//! pixels as big-endian 16-bit integers offset by `BZERO = 32768`, as cameras' unsigned data is
//! stored in FITS.

use std::io::Write;

use crate::image::Image;

/// One header keyword, as N.I.N.A. writes them.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct HeaderCard {
    pub key: String,
    pub value: HeaderValue,
    pub comment: String,
}

#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum HeaderValue {
    Text { value: String },
    Integer { value: i64 },
    Real { value: f64 },
    Logical { value: bool },
}

const BLOCK: usize = 2880;
const CARD: usize = 80;

/// The value as FITS writes it: strings quoted with single quotes doubled, reals with a decimal
/// point and an upper-case exponent, logicals as T or F.
pub(crate) fn fits_value(value: &HeaderValue) -> String {
    match value {
        HeaderValue::Text { value } => format!("'{:<8}'", value.replace('\'', "''")),
        HeaderValue::Integer { value } => value.to_string(),
        HeaderValue::Real { value } => real(*value),
        HeaderValue::Logical { value } => (if *value { "T" } else { "F" }).to_owned(),
    }
}

/// The shortest text that reads back as `value`, always with a decimal point (`2.0`, `1.5E-5`).
fn real(value: f64) -> String {
    let text = format!("{value:?}").replace('e', "E");
    if text.contains('.') || text.contains('E') || !value.is_finite() {
        text
    } else {
        format!("{text}.0")
    }
}

/// One 80-character card: the keyword in columns 1–8, `= ` in 9–10, numbers and logicals
/// right-aligned to column 30, strings from column 11, then ` / comment` if it fits.
pub(crate) fn card(key: &str, value: &HeaderValue, comment: &str) -> String {
    let key: String = key
        .to_ascii_uppercase()
        .chars()
        .filter(|c| c.is_ascii_graphic())
        .take(8)
        .collect();
    let text = fits_value(value);
    let value_field = match value {
        HeaderValue::Text { .. } => format!("{text:<20}"),
        _ => format!("{text:>20}"),
    };
    let mut line = format!("{key:<8}= {value_field}");
    if !comment.is_empty() {
        line.push_str(" / ");
        line.push_str(comment);
    }
    let line: String = line
        .chars()
        .map(|c| {
            if c.is_ascii() && !c.is_ascii_control() {
                c
            } else {
                '?'
            }
        })
        .take(CARD)
        .collect();
    format!("{line:<CARD$}")
}

/// Writes `image` as a FITS file with the mandatory keywords first, then `headers`.
pub(crate) fn write(
    image: &Image,
    headers: &[HeaderCard],
    out: &mut impl Write,
) -> std::io::Result<()> {
    let int = |value: i64| HeaderValue::Integer { value };
    let mut cards = vec![
        card(
            "SIMPLE",
            &HeaderValue::Logical { value: true },
            "conforms to the FITS standard",
        ),
        card("BITPIX", &int(16), "16-bit integers"),
        card("NAXIS", &int(2), "two axes"),
        card("NAXIS1", &int(image.width as i64), "width in pixels"),
        card("NAXIS2", &int(image.height as i64), "height in pixels"),
        card(
            "BZERO",
            &HeaderValue::Real { value: 32768.0 },
            "unsigned 16-bit data",
        ),
        card("BSCALE", &HeaderValue::Real { value: 1.0 }, ""),
    ];
    const MANDATORY: [&str; 7] = [
        "SIMPLE", "BITPIX", "NAXIS", "NAXIS1", "NAXIS2", "BZERO", "BSCALE",
    ];
    for header in headers {
        if !MANDATORY.contains(&header.key.to_ascii_uppercase().as_str()) && header.key != "END" {
            cards.push(card(&header.key, &header.value, &header.comment));
        }
    }
    cards.push(format!("{:<CARD$}", "END"));
    let mut header = cards.concat().into_bytes();
    header.resize(header.len().div_ceil(BLOCK) * BLOCK, b' ');
    out.write_all(&header)?;

    let mut data = Vec::with_capacity(image.pixels.len() * 2);
    for &value in &image.pixels {
        data.extend(((value as i32 - 32768) as i16).to_be_bytes());
    }
    data.resize(data.len().div_ceil(BLOCK) * BLOCK, 0);
    out.write_all(&data)
}

/// The primary header's keywords and their values, strings without their quotes, and where the
/// data begins; None if `bytes` is not a FITS file.
pub(crate) fn header(bytes: &[u8]) -> Option<(std::collections::HashMap<String, String>, usize)> {
    let mut keys = std::collections::HashMap::new();
    let mut offset = 0;
    loop {
        let block = bytes.get(offset..offset + BLOCK)?;
        offset += BLOCK;
        for card in block.chunks(CARD) {
            let card = String::from_utf8_lossy(card);
            let key = card[..8].trim().to_owned();
            if key == "END" {
                return Some((keys, offset));
            }
            if card.as_bytes().get(8) == Some(&b'=') {
                let field = card[10..].trim();
                let value = if let Some(quoted) = field.strip_prefix('\'') {
                    quoted
                        .split('\'')
                        .next()
                        .unwrap_or("")
                        .trim_end()
                        .to_owned()
                } else {
                    field.split('/').next().unwrap_or("").trim().to_owned()
                };
                keys.insert(key, value);
            }
        }
    }
}

/// Writes `values`, `channels` planes of `width` × `height` from 0 to 1, as a FITS file of 32-bit
/// floats: one plane as an image, three as the third axis, as Siril and PixInsight read a colour
/// stack; then `headers`.
pub(crate) fn write_float_planes(
    width: usize,
    height: usize,
    channels: usize,
    values: &[f32],
    headers: &[HeaderCard],
    out: &mut dyn Write,
) -> std::io::Result<()> {
    let int = |value: i64| HeaderValue::Integer { value };
    let axes = if channels > 1 { 3 } else { 2 };
    let mut cards = vec![
        card(
            "SIMPLE",
            &HeaderValue::Logical { value: true },
            "conforms to the FITS standard",
        ),
        card("BITPIX", &int(-32), "32-bit floats"),
        card(
            "NAXIS",
            &int(axes),
            if axes == 3 { "three axes" } else { "two axes" },
        ),
        card("NAXIS1", &int(width as i64), "width in pixels"),
        card("NAXIS2", &int(height as i64), "height in pixels"),
    ];
    if axes == 3 {
        cards.push(card("NAXIS3", &int(channels as i64), "red, green and blue"));
    }
    const MANDATORY: [&str; 8] = [
        "SIMPLE", "BITPIX", "NAXIS", "NAXIS1", "NAXIS2", "NAXIS3", "BZERO", "BSCALE",
    ];
    for header in headers {
        if !MANDATORY.contains(&header.key.to_ascii_uppercase().as_str()) && header.key != "END" {
            cards.push(card(&header.key, &header.value, &header.comment));
        }
    }
    cards.push(format!("{:<CARD$}", "END"));
    let mut header = cards.concat().into_bytes();
    header.resize(header.len().div_ceil(BLOCK) * BLOCK, b' ');
    out.write_all(&header)?;
    let mut data = Vec::with_capacity(values.len() * 4);
    for &value in values {
        data.extend(value.to_be_bytes());
    }
    data.resize(data.len().div_ceil(BLOCK) * BLOCK, 0);
    out.write_all(&data)
}

/// Reads a 16-bit FITS frame as it is stored, row by row, with its Bayer pattern, pixel size
/// (µm) and focal length (mm), for tests that compare with real frames.
#[cfg(test)]
pub(crate) fn read_for_tests(path: &str) -> (Image, Option<f64>, Option<f64>) {
    use crate::image::BayerPattern;
    let bytes = std::fs::read(path).unwrap_or_else(|e| panic!("{path}: {e}"));
    let (keys, offset) = header(&bytes).unwrap_or_else(|| panic!("{path}: not a FITS file"));
    let number = |key: &str| keys.get(key).and_then(|v| v.parse::<f64>().ok());
    assert_eq!(number("BITPIX"), Some(16.0), "{path}: only 16-bit frames");
    let (width, height) = (
        number("NAXIS1").unwrap() as usize,
        number("NAXIS2").unwrap() as usize,
    );
    let zero = number("BZERO").unwrap_or(0.0);
    let pixels = bytes[offset..offset + width * height * 2]
        .chunks(2)
        .map(|b| (i16::from_be_bytes([b[0], b[1]]) as f64 + zero) as u16)
        .collect();
    let bayer = keys.get("BAYERPAT").map(|p| match p.as_str() {
        "RGGB" => BayerPattern::Rggb,
        "GRBG" => BayerPattern::Grbg,
        "GBRG" => BayerPattern::Gbrg,
        "BGGR" => BayerPattern::Bggr,
        other => panic!("{path}: Bayer pattern {other}"),
    });
    (
        Image::new(width, height, pixels, bayer),
        number("XPIXSZ"),
        number("FOCALLEN"),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::image::BayerPattern;

    #[test]
    fn cards_follow_the_fixed_format() {
        assert_eq!(
            card("EXPTIME", &HeaderValue::Real { value: 20.0 }, "seconds"),
            format!("{:<80}", "EXPTIME =                 20.0 / seconds")
        );
        assert_eq!(
            card(
                "BAYERPAT",
                &HeaderValue::Text {
                    value: "GRBG".into()
                },
                ""
            ),
            format!("{:<80}", "BAYERPAT= 'GRBG    '")
        );
        assert_eq!(
            card("SIMPLE", &HeaderValue::Logical { value: true }, "").len(),
            80
        );
        assert_eq!(
            &card("SIMPLE", &HeaderValue::Logical { value: true }, "")[..30],
            "SIMPLE  =                    T"
        );
        assert_eq!(
            fits_value(&HeaderValue::Text {
                value: "It's".into()
            }),
            "'It''s   '"
        );
        assert_eq!(fits_value(&HeaderValue::Real { value: 1.5e-5 }), "1.5E-5");
    }

    #[test]
    fn long_comments_are_cut_at_80_characters() {
        let line = card(
            "OBJECT",
            &HeaderValue::Text {
                value: "M 31".into(),
            },
            &"x".repeat(100),
        );
        assert_eq!(line.len(), 80);
    }

    #[test]
    fn writes_blocks_of_2880_bytes_with_offset_data() {
        let image = Image::new(
            3,
            2,
            vec![0, 32768, 65535, 1, 2, 3],
            Some(BayerPattern::Grbg),
        );
        let headers = [HeaderCard {
            key: "BAYERPAT".into(),
            value: HeaderValue::Text {
                value: "GRBG".into(),
            },
            comment: "".into(),
        }];
        let mut out = Vec::new();
        write(&image, &headers, &mut out).unwrap();
        assert_eq!(out.len(), 2 * BLOCK);
        let text = String::from_utf8_lossy(&out[..BLOCK]);
        assert!(text.starts_with("SIMPLE  ="));
        assert!(text.contains("NAXIS1  =                    3"));
        assert!(text.contains("BAYERPAT= 'GRBG    '"));
        let end = text.find("END     ").unwrap();
        assert_eq!(end % 80, 0);
        // 0 → -32768, 32768 → 0, 65535 → 32767, big-endian.
        assert_eq!(
            &out[BLOCK..BLOCK + 6],
            &[0x80, 0x00, 0x00, 0x00, 0x7F, 0xFF]
        );
    }
}
