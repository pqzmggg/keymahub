//! keymahub wire protocol.
//!
//! P0 uses the *unencrypted* "PoC wire v0" defined here so that the
//! Android PoC (Kotlin) can decode it without a Rust dependency. P1 replaces the transport
//! with Noise_KK and may switch the payload encoding; the message set stays the same.
//!
//! Frame layout (big endian):
//!
//! ```text
//! u16 len   -- number of bytes that follow (type + payload)
//! u8  type
//! ..  payload (fixed layout per type, see `Msg`)
//! ```
//!
//! Unknown types are skipped using `len`, so newer peers can add messages.

use std::io::{self, Read, Write};

/// Default TCP port the receiver listens on.
pub const DEFAULT_PORT: u16 = 45877;
/// Wire version carried in `Hello`.
pub const WIRE_VERSION: u16 = 0;
/// One wheel notch, same unit as Windows `WHEEL_DELTA`.
pub const WHEEL_NOTCH: i16 = 120;
/// Upper bound for a single frame body; protects the reader from garbage lengths.
pub const MAX_FRAME: usize = 1024;

mod ty {
    pub const HELLO: u8 = 0x01;
    pub const PING: u8 = 0x02;
    pub const PONG: u8 = 0x03;
    pub const ENTER: u8 = 0x10;
    pub const LEAVE: u8 = 0x11;
    pub const KEY: u8 = 0x20;
    pub const MOUSE_MOVE: u8 = 0x21;
    pub const MOUSE_BUTTON: u8 = 0x22;
    pub const WHEEL: u8 = 0x23;
    pub const RELEASE_ALL: u8 = 0x24;
}

const KEY_DOWN: u8 = 0x01;
const KEY_REPEAT: u8 = 0x02;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[repr(u8)]
pub enum Button {
    Left = 1,
    Right = 2,
    Middle = 3,
    Back = 4,
    Forward = 5,
}

impl Button {
    pub const ALL: [Button; 5] = [
        Button::Left,
        Button::Right,
        Button::Middle,
        Button::Back,
        Button::Forward,
    ];

    pub fn from_u8(v: u8) -> Option<Button> {
        Button::ALL.into_iter().find(|b| *b as u8 == v)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Msg {
    /// `u16 version, u8 name_len, name (utf-8)` — both directions, first frame.
    Hello { version: u16, name: String },
    /// `u64 t_us` — sender's monotonic clock; peer echoes it back in `Pong`.
    Ping { t_us: u64 },
    /// `u64 t_us`
    Pong { t_us: u64 },
    /// Host → receiver: input focus moved to the receiver.
    Enter,
    /// Host → receiver: input focus left the receiver.
    Leave,
    /// `u16 usage (HID page 0x07), u8 flags (bit0 down, bit1 repeat)`
    Key {
        usage: u16,
        down: bool,
        repeat: bool,
    },
    /// `i16 dx, i16 dy` — relative, before any receiver-side speed scaling.
    MouseMove { dx: i16, dy: i16 },
    /// `u8 button, u8 down`
    MouseButton { button: Button, down: bool },
    /// `i16 v, i16 h` — in 1/120 notch units. Positive `v` = away from user (scroll up).
    Wheel { v: i16, h: i16 },
    /// Release every key and button the receiver is currently holding.
    ReleaseAll,
}

impl Msg {
    /// Appends one complete frame to `out`.
    pub fn encode(&self, out: &mut Vec<u8>) {
        let start = out.len();
        out.extend_from_slice(&[0, 0]);
        match self {
            Msg::Hello { version, name } => {
                out.push(ty::HELLO);
                out.extend_from_slice(&version.to_be_bytes());
                let name = truncate_utf8(name, u8::MAX as usize);
                out.push(name.len() as u8);
                out.extend_from_slice(name.as_bytes());
            }
            Msg::Ping { t_us } => {
                out.push(ty::PING);
                out.extend_from_slice(&t_us.to_be_bytes());
            }
            Msg::Pong { t_us } => {
                out.push(ty::PONG);
                out.extend_from_slice(&t_us.to_be_bytes());
            }
            Msg::Enter => out.push(ty::ENTER),
            Msg::Leave => out.push(ty::LEAVE),
            Msg::Key {
                usage,
                down,
                repeat,
            } => {
                out.push(ty::KEY);
                out.extend_from_slice(&usage.to_be_bytes());
                let mut flags = 0;
                if *down {
                    flags |= KEY_DOWN;
                }
                if *repeat {
                    flags |= KEY_REPEAT;
                }
                out.push(flags);
            }
            Msg::MouseMove { dx, dy } => {
                out.push(ty::MOUSE_MOVE);
                out.extend_from_slice(&dx.to_be_bytes());
                out.extend_from_slice(&dy.to_be_bytes());
            }
            Msg::MouseButton { button, down } => {
                out.push(ty::MOUSE_BUTTON);
                out.push(*button as u8);
                out.push(*down as u8);
            }
            Msg::Wheel { v, h } => {
                out.push(ty::WHEEL);
                out.extend_from_slice(&v.to_be_bytes());
                out.extend_from_slice(&h.to_be_bytes());
            }
            Msg::ReleaseAll => out.push(ty::RELEASE_ALL),
        }
        let len = (out.len() - start - 2) as u16;
        out[start..start + 2].copy_from_slice(&len.to_be_bytes());
    }

    pub fn to_frame(&self) -> Vec<u8> {
        let mut v = Vec::with_capacity(16);
        self.encode(&mut v);
        v
    }

    /// Decodes a frame body (type byte + payload, without the length prefix).
    /// Returns `Ok(None)` for an unknown type so callers can skip it.
    pub fn decode_body(body: &[u8]) -> Result<Option<Msg>, DecodeError> {
        let (&t, p) = body.split_first().ok_or(DecodeError::Empty)?;
        let mut c = Cursor(p);
        let msg = match t {
            ty::HELLO => {
                let version = c.u16()?;
                let n = c.u8()? as usize;
                let name = String::from_utf8_lossy(c.take(n)?).into_owned();
                Msg::Hello { version, name }
            }
            ty::PING => Msg::Ping { t_us: c.u64()? },
            ty::PONG => Msg::Pong { t_us: c.u64()? },
            ty::ENTER => Msg::Enter,
            ty::LEAVE => Msg::Leave,
            ty::KEY => {
                let usage = c.u16()?;
                let flags = c.u8()?;
                Msg::Key {
                    usage,
                    down: flags & KEY_DOWN != 0,
                    repeat: flags & KEY_REPEAT != 0,
                }
            }
            ty::MOUSE_MOVE => Msg::MouseMove {
                dx: c.u16()? as i16,
                dy: c.u16()? as i16,
            },
            ty::MOUSE_BUTTON => {
                let b = c.u8()?;
                let button = Button::from_u8(b).ok_or(DecodeError::BadButton(b))?;
                Msg::MouseButton {
                    button,
                    down: c.u8()? != 0,
                }
            }
            ty::WHEEL => Msg::Wheel {
                v: c.u16()? as i16,
                h: c.u16()? as i16,
            },
            ty::RELEASE_ALL => Msg::ReleaseAll,
            _ => return Ok(None),
        };
        Ok(Some(msg))
    }
}

/// Splits a large relative motion into frames whose deltas fit in `i16`.
pub fn split_motion(mut dx: i32, mut dy: i32, mut emit: impl FnMut(Msg)) {
    const M: i32 = i16::MAX as i32;
    while dx != 0 || dy != 0 {
        let sx = dx.clamp(-M, M);
        let sy = dy.clamp(-M, M);
        emit(Msg::MouseMove {
            dx: sx as i16,
            dy: sy as i16,
        });
        dx -= sx;
        dy -= sy;
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DecodeError {
    Empty,
    Truncated,
    TooLong(usize),
    BadButton(u8),
}

impl std::fmt::Display for DecodeError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            DecodeError::Empty => write!(f, "empty frame"),
            DecodeError::Truncated => write!(f, "truncated frame"),
            DecodeError::TooLong(n) => write!(f, "frame too long: {n}"),
            DecodeError::BadButton(b) => write!(f, "unknown mouse button {b}"),
        }
    }
}

impl std::error::Error for DecodeError {}

impl From<DecodeError> for io::Error {
    fn from(e: DecodeError) -> Self {
        io::Error::new(io::ErrorKind::InvalidData, e)
    }
}

/// Reads the next known message, skipping unknown types. `Ok(None)` means clean EOF.
pub fn read_msg<R: Read>(r: &mut R) -> io::Result<Option<Msg>> {
    let mut buf = [0u8; MAX_FRAME];
    loop {
        let mut len = [0u8; 2];
        match r.read_exact(&mut len) {
            Ok(()) => {}
            Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => return Ok(None),
            Err(e) => return Err(e),
        }
        let len = u16::from_be_bytes(len) as usize;
        if len > MAX_FRAME {
            return Err(DecodeError::TooLong(len).into());
        }
        r.read_exact(&mut buf[..len])?;
        if let Some(m) = Msg::decode_body(&buf[..len])? {
            return Ok(Some(m));
        }
    }
}

pub fn write_msg<W: Write>(w: &mut W, m: &Msg) -> io::Result<()> {
    w.write_all(&m.to_frame())
}

struct Cursor<'a>(&'a [u8]);

impl<'a> Cursor<'a> {
    fn take(&mut self, n: usize) -> Result<&'a [u8], DecodeError> {
        if self.0.len() < n {
            return Err(DecodeError::Truncated);
        }
        let (a, b) = self.0.split_at(n);
        self.0 = b;
        Ok(a)
    }
    fn u8(&mut self) -> Result<u8, DecodeError> {
        Ok(self.take(1)?[0])
    }
    fn u16(&mut self) -> Result<u16, DecodeError> {
        Ok(u16::from_be_bytes(self.take(2)?.try_into().unwrap()))
    }
    fn u64(&mut self) -> Result<u64, DecodeError> {
        Ok(u64::from_be_bytes(self.take(8)?.try_into().unwrap()))
    }
}

fn truncate_utf8(s: &str, max: usize) -> &str {
    if s.len() <= max {
        return s;
    }
    let mut end = max;
    while !s.is_char_boundary(end) {
        end -= 1;
    }
    &s[..end]
}

#[cfg(test)]
mod tests {
    use super::*;

    fn all_msgs() -> Vec<Msg> {
        vec![
            Msg::Hello {
                version: WIRE_VERSION,
                name: "데스크톱".into(),
            },
            Msg::Ping {
                t_us: 0x0102_0304_0506_0708,
            },
            Msg::Pong { t_us: u64::MAX },
            Msg::Enter,
            Msg::Leave,
            Msg::Key {
                usage: 0x04,
                down: true,
                repeat: false,
            },
            Msg::Key {
                usage: 0x90,
                down: true,
                repeat: true,
            },
            Msg::Key {
                usage: 0xE7,
                down: false,
                repeat: false,
            },
            Msg::MouseMove {
                dx: -300,
                dy: 32767,
            },
            Msg::MouseButton {
                button: Button::Forward,
                down: true,
            },
            Msg::Wheel { v: -240, h: 120 },
            Msg::ReleaseAll,
        ]
    }

    #[test]
    fn roundtrip_stream() {
        let msgs = all_msgs();
        let mut buf = Vec::new();
        for m in &msgs {
            m.encode(&mut buf);
        }
        let mut r = &buf[..];
        for m in &msgs {
            assert_eq!(read_msg(&mut r).unwrap().as_ref(), Some(m));
        }
        assert_eq!(read_msg(&mut r).unwrap(), None);
    }

    #[test]
    fn known_bytes() {
        // Pinned so the Kotlin decoder can use the same vectors.
        assert_eq!(
            Msg::Key {
                usage: 0x04,
                down: true,
                repeat: false
            }
            .to_frame(),
            [0x00, 0x04, 0x20, 0x00, 0x04, 0x01]
        );
        assert_eq!(
            Msg::MouseMove { dx: -1, dy: 2 }.to_frame(),
            [0x00, 0x05, 0x21, 0xFF, 0xFF, 0x00, 0x02]
        );
        assert_eq!(
            Msg::Wheel { v: -120, h: 0 }.to_frame(),
            [0x00, 0x05, 0x23, 0xFF, 0x88, 0x00, 0x00]
        );
    }

    #[test]
    fn skips_unknown_type() {
        let mut buf = vec![0x00, 0x03, 0x7F, 0xAA, 0xBB];
        Msg::Enter.encode(&mut buf);
        assert_eq!(read_msg(&mut &buf[..]).unwrap(), Some(Msg::Enter));
    }

    #[test]
    fn rejects_truncated_and_oversized() {
        assert_eq!(
            Msg::decode_body(&[ty::KEY, 0x00]),
            Err(DecodeError::Truncated)
        );
        let buf = [0xFF, 0xFF];
        assert!(read_msg(&mut &buf[..]).is_err());
    }

    #[test]
    fn hello_name_truncated_on_char_boundary() {
        let name = "가".repeat(100); // 300 bytes
        let f = Msg::Hello { version: 0, name }.to_frame();
        match read_msg(&mut &f[..]).unwrap() {
            Some(Msg::Hello { name, .. }) => assert_eq!(name, "가".repeat(85)),
            m => panic!("{m:?}"),
        }
    }

    #[test]
    fn split_motion_chunks() {
        let mut v = Vec::new();
        split_motion(70000, -5, |m| v.push(m));
        assert_eq!(
            v,
            [
                Msg::MouseMove { dx: 32767, dy: -5 },
                Msg::MouseMove { dx: 32767, dy: 0 },
                Msg::MouseMove { dx: 4466, dy: 0 },
            ]
        );
    }
}
