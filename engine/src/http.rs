// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

//! A minimal HTTP/1.1 GET for image downloads, on a socket bound to the equipment's network.
//!
//! The Seestar's WiFi has no internet, so Android sends traffic through mobile data unless each
//! socket is bound to the WiFi network (ARCHITECTURE.md). Kotlin passes the network's
//! handle (`Network.getNetworkHandle()`), and this module applies `android_setsocknetwork()`
//! before connecting.

use std::io::{BufRead, BufReader, Read, Write};
use std::net::{SocketAddr, TcpStream, ToSocketAddrs};
use std::time::Duration;

use socket2::{Domain, Protocol, Socket, Type};

use crate::EngineError;

/// A response: status, the headers that matter here, and the body as one reader.
pub(crate) struct Response {
    pub status: u16,
    pub content_type: String,
    pub body: Box<dyn Read + Send>,
}

/// GETs `url`, a plain `http://host:port/path` URL as Alpaca uses on the local network.
pub(crate) fn get(
    url: &str,
    accept: &str,
    network: Option<u64>,
    timeout: Duration,
) -> Result<Response, EngineError> {
    let (host, port, path) = parse_url(url)?;
    let address = (host.trim_start_matches('[').trim_end_matches(']'), port)
        .to_socket_addrs()
        .map_err(|e| network_error(format!("cannot resolve {host}: {e}")))?
        .next()
        .ok_or_else(|| network_error(format!("no address for {host}")))?;
    let mut stream = connect(address, network, timeout)?;
    stream.set_read_timeout(Some(timeout)).map_err(io_error)?;
    stream.set_write_timeout(Some(timeout)).map_err(io_error)?;
    write!(
        stream,
        "GET {path} HTTP/1.1\r\nHost: {host}:{port}\r\nAccept: {accept}\r\nUser-Agent: Yacana\r\nConnection: close\r\n\r\n"
    )
    .map_err(io_error)?;

    let mut reader = BufReader::with_capacity(1 << 16, stream);
    let status_line = read_line(&mut reader)?;
    let status = status_line
        .split_whitespace()
        .nth(1)
        .and_then(|s| s.parse().ok())
        .ok_or_else(|| network_error(format!("not an HTTP response: {status_line}")))?;
    let (mut content_type, mut length, mut chunked) = (String::new(), None, false);
    loop {
        let line = read_line(&mut reader)?;
        if line.is_empty() {
            break;
        }
        let Some((name, value)) = line.split_once(':') else {
            continue;
        };
        let value = value.trim();
        match name.trim().to_ascii_lowercase().as_str() {
            "content-type" => content_type = value.to_ascii_lowercase(),
            "content-length" => length = value.parse::<u64>().ok(),
            "transfer-encoding" => chunked = value.to_ascii_lowercase().contains("chunked"),
            _ => {}
        }
    }
    let body: Box<dyn Read + Send> = if chunked {
        Box::new(Chunked {
            inner: reader,
            left: 0,
            done: false,
        })
    } else if let Some(length) = length {
        Box::new(reader.take(length))
    } else {
        Box::new(reader)
    };
    Ok(Response {
        status,
        content_type,
        body,
    })
}

fn connect(
    address: SocketAddr,
    network: Option<u64>,
    timeout: Duration,
) -> Result<TcpStream, EngineError> {
    let socket = Socket::new(
        Domain::for_address(address),
        Type::STREAM,
        Some(Protocol::TCP),
    )
    .map_err(io_error)?;
    if let Some(handle) = network {
        bind_to_network(&socket, handle)?;
    }
    socket.set_tcp_nodelay(true).map_err(io_error)?;
    socket
        .connect_timeout(&address.into(), timeout)
        .map_err(|e| network_error(format!("cannot connect to {address}: {e}")))?;
    Ok(socket.into())
}

#[cfg(target_os = "android")]
fn bind_to_network(socket: &Socket, handle: u64) -> Result<(), EngineError> {
    use std::os::fd::AsRawFd;
    #[link(name = "android")]
    unsafe extern "C" {
        fn android_setsocknetwork(network: u64, fd: std::ffi::c_int) -> std::ffi::c_int;
    }
    // SAFETY: the descriptor belongs to `socket`, which outlives the call.
    let result = unsafe { android_setsocknetwork(handle, socket.as_raw_fd()) };
    if result == 0 {
        Ok(())
    } else {
        Err(network_error(format!(
            "cannot bind the socket to the network: {}",
            std::io::Error::last_os_error()
        )))
    }
}

/// Off Android (tests on the PC) there is only the default network.
#[cfg(not(target_os = "android"))]
fn bind_to_network(_socket: &Socket, _handle: u64) -> Result<(), EngineError> {
    Ok(())
}

/// Splits `http://host:port/path` into its parts; the port defaults to 80.
fn parse_url(url: &str) -> Result<(String, u16, String), EngineError> {
    let rest = url
        .strip_prefix("http://")
        .ok_or_else(|| network_error(format!("only http:// URLs are supported: {url}")))?;
    let (authority, path) = match rest.find('/') {
        Some(i) => (&rest[..i], &rest[i..]),
        None => (rest, "/"),
    };
    let (host, port) = match authority.rfind(':') {
        Some(i) if !authority[i..].contains(']') => {
            let port = authority[i + 1..]
                .parse()
                .map_err(|_| network_error(format!("bad port in {url}")))?;
            (&authority[..i], port)
        }
        _ => (authority, 80),
    };
    Ok((host.to_owned(), port, path.to_owned()))
}

fn read_line(reader: &mut impl BufRead) -> Result<String, EngineError> {
    let mut line = String::new();
    reader.read_line(&mut line).map_err(io_error)?;
    Ok(line.trim_end_matches(['\r', '\n']).to_owned())
}

/// A body in chunked transfer encoding, which ASP.NET servers such as OmniSim may use.
struct Chunked<R> {
    inner: R,
    left: usize,
    done: bool,
}

impl<R: BufRead> Read for Chunked<R> {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        if self.done || buf.is_empty() {
            return Ok(0);
        }
        if self.left == 0 {
            let mut line = String::new();
            self.inner.read_line(&mut line)?;
            let size = line.trim().split(';').next().unwrap_or("");
            self.left = usize::from_str_radix(size, 16).map_err(|_| {
                std::io::Error::new(
                    std::io::ErrorKind::InvalidData,
                    format!("bad chunk size {size:?}"),
                )
            })?;
            if self.left == 0 {
                self.done = true;
                return Ok(0);
            }
        }
        let limit = buf.len().min(self.left);
        let n = self.inner.read(&mut buf[..limit])?;
        if n == 0 {
            return Err(std::io::ErrorKind::UnexpectedEof.into());
        }
        self.left -= n;
        if self.left == 0 {
            // The CRLF after each chunk.
            let mut crlf = [0u8; 2];
            self.inner.read_exact(&mut crlf)?;
        }
        Ok(n)
    }
}

fn network_error(message: String) -> EngineError {
    EngineError::Network { reason: message }
}

fn io_error(e: std::io::Error) -> EngineError {
    network_error(e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_alpaca_urls() {
        assert_eq!(
            parse_url("http://10.0.0.1:32323/api/v1/camera/0/imagearray").unwrap(),
            (
                "10.0.0.1".to_owned(),
                32323,
                "/api/v1/camera/0/imagearray".to_owned()
            )
        );
        assert_eq!(
            parse_url("http://[fd00::1]:11111/x").unwrap(),
            ("[fd00::1]".to_owned(), 11111, "/x".to_owned())
        );
        assert_eq!(
            parse_url("http://seestar").unwrap(),
            ("seestar".to_owned(), 80, "/".to_owned())
        );
        assert!(parse_url("https://seestar/").is_err());
    }

    #[test]
    fn reads_a_chunked_body() {
        let body = b"4\r\nWiki\r\n6\r\npedia \r\nE\r\nin \r\n\r\nchunks.\r\n0\r\n\r\n";
        let mut reader = Chunked {
            inner: &body[..],
            left: 0,
            done: false,
        };
        let mut text = String::new();
        reader.read_to_string(&mut text).unwrap();
        assert_eq!(text, "Wikipedia in \r\n\r\nchunks.");
    }
}
