//! USB HID keyboard usages (page 0x07) are keymanc's canonical key identity.
//! This crate maps them to and from platform key codes.
//!
//! * `evdev`: Linux `KEY_*` codes (input-event-codes.h). Android's kernel HID driver
//!   produces the same codes, so Android hosts reading `/dev/input` use this column too.
//! * `win`: PS/2 set-1 make codes as reported by Windows (`KBDLLHOOKSTRUCT::scanCode`),
//!   with `0xE000` added for `E0`-prefixed (extended) keys.
//!
//! The Android `KeyEvent.KEYCODE_*` table lives in the Android app for P0 and moves
//! here (exposed over FFI) in P1.

#[derive(Debug, Clone, Copy)]
pub struct Key {
    pub hid: u16,
    pub evdev: u16,
    pub win: u16,
    pub name: &'static str,
}

const fn k(hid: u16, evdev: u16, win: u16, name: &'static str) -> Key {
    Key {
        hid,
        evdev,
        win,
        name,
    }
}

/// Extended-key marker for the `win` column.
pub const WIN_EXT: u16 = 0xE000;
const X: u16 = WIN_EXT;

pub const HID_LANG1_HANGUL: u16 = 0x90;
pub const HID_LANG2_HANJA: u16 = 0x91;

#[rustfmt::skip]
pub static KEYS: &[Key] = &[
    k(0x04, 30, 0x1E, "A"), k(0x05, 48, 0x30, "B"), k(0x06, 46, 0x2E, "C"),
    k(0x07, 32, 0x20, "D"), k(0x08, 18, 0x12, "E"), k(0x09, 33, 0x21, "F"),
    k(0x0A, 34, 0x22, "G"), k(0x0B, 35, 0x23, "H"), k(0x0C, 23, 0x17, "I"),
    k(0x0D, 36, 0x24, "J"), k(0x0E, 37, 0x25, "K"), k(0x0F, 38, 0x26, "L"),
    k(0x10, 50, 0x32, "M"), k(0x11, 49, 0x31, "N"), k(0x12, 24, 0x18, "O"),
    k(0x13, 25, 0x19, "P"), k(0x14, 16, 0x10, "Q"), k(0x15, 19, 0x13, "R"),
    k(0x16, 31, 0x1F, "S"), k(0x17, 20, 0x14, "T"), k(0x18, 22, 0x16, "U"),
    k(0x19, 47, 0x2F, "V"), k(0x1A, 17, 0x11, "W"), k(0x1B, 45, 0x2D, "X"),
    k(0x1C, 21, 0x15, "Y"), k(0x1D, 44, 0x2C, "Z"),
    k(0x1E, 2, 0x02, "1"), k(0x1F, 3, 0x03, "2"), k(0x20, 4, 0x04, "3"),
    k(0x21, 5, 0x05, "4"), k(0x22, 6, 0x06, "5"), k(0x23, 7, 0x07, "6"),
    k(0x24, 8, 0x08, "7"), k(0x25, 9, 0x09, "8"), k(0x26, 10, 0x0A, "9"),
    k(0x27, 11, 0x0B, "0"),
    k(0x28, 28, 0x1C, "Enter"), k(0x29, 1, 0x01, "Escape"),
    k(0x2A, 14, 0x0E, "Backspace"), k(0x2B, 15, 0x0F, "Tab"),
    k(0x2C, 57, 0x39, "Space"), k(0x2D, 12, 0x0C, "Minus"),
    k(0x2E, 13, 0x0D, "Equal"), k(0x2F, 26, 0x1A, "BracketLeft"),
    k(0x30, 27, 0x1B, "BracketRight"), k(0x31, 43, 0x2B, "Backslash"),
    k(0x33, 39, 0x27, "Semicolon"), k(0x34, 40, 0x28, "Quote"),
    k(0x35, 41, 0x29, "Backquote"), k(0x36, 51, 0x33, "Comma"),
    k(0x37, 52, 0x34, "Period"), k(0x38, 53, 0x35, "Slash"),
    k(0x39, 58, 0x3A, "CapsLock"),
    k(0x3A, 59, 0x3B, "F1"), k(0x3B, 60, 0x3C, "F2"), k(0x3C, 61, 0x3D, "F3"),
    k(0x3D, 62, 0x3E, "F4"), k(0x3E, 63, 0x3F, "F5"), k(0x3F, 64, 0x40, "F6"),
    k(0x40, 65, 0x41, "F7"), k(0x41, 66, 0x42, "F8"), k(0x42, 67, 0x43, "F9"),
    k(0x43, 68, 0x44, "F10"), k(0x44, 87, 0x57, "F11"), k(0x45, 88, 0x58, "F12"),
    k(0x46, 99, X | 0x37, "PrintScreen"), k(0x47, 70, 0x46, "ScrollLock"),
    // Pause is E1 1D 45 on the wire; Windows hooks report it as 0x45 without E0 and
    // NumLock as E0 45. Callers on Windows should prefer the VK_PAUSE/VK_NUMLOCK path.
    k(0x48, 119, 0x45, "Pause"),
    k(0x49, 110, X | 0x52, "Insert"), k(0x4A, 102, X | 0x47, "Home"),
    k(0x4B, 104, X | 0x49, "PageUp"), k(0x4C, 111, X | 0x53, "Delete"),
    k(0x4D, 107, X | 0x4F, "End"), k(0x4E, 109, X | 0x51, "PageDown"),
    k(0x4F, 106, X | 0x4D, "ArrowRight"), k(0x50, 105, X | 0x4B, "ArrowLeft"),
    k(0x51, 108, X | 0x50, "ArrowDown"), k(0x52, 103, X | 0x48, "ArrowUp"),
    k(0x53, 69, X | 0x45, "NumLock"),
    k(0x54, 98, X | 0x35, "NumpadDivide"), k(0x55, 55, 0x37, "NumpadMultiply"),
    k(0x56, 74, 0x4A, "NumpadSubtract"), k(0x57, 78, 0x4E, "NumpadAdd"),
    k(0x58, 96, X | 0x1C, "NumpadEnter"),
    k(0x59, 79, 0x4F, "Numpad1"), k(0x5A, 80, 0x50, "Numpad2"), k(0x5B, 81, 0x51, "Numpad3"),
    k(0x5C, 75, 0x4B, "Numpad4"), k(0x5D, 76, 0x4C, "Numpad5"), k(0x5E, 77, 0x4D, "Numpad6"),
    k(0x5F, 71, 0x47, "Numpad7"), k(0x60, 72, 0x48, "Numpad8"), k(0x61, 73, 0x49, "Numpad9"),
    k(0x62, 82, 0x52, "Numpad0"), k(0x63, 83, 0x53, "NumpadDecimal"),
    k(0x64, 86, 0x56, "IntlBackslash"), k(0x65, 127, X | 0x5D, "ContextMenu"),
    k(0x66, 116, X | 0x5E, "Power"), k(0x67, 117, 0x59, "NumpadEqual"),
    k(0x68, 183, 0x64, "F13"), k(0x69, 184, 0x65, "F14"), k(0x6A, 185, 0x66, "F15"),
    k(0x6B, 186, 0x67, "F16"), k(0x6C, 187, 0x68, "F17"), k(0x6D, 188, 0x69, "F18"),
    k(0x6E, 189, 0x6A, "F19"), k(0x6F, 190, 0x6B, "F20"), k(0x70, 191, 0x6C, "F21"),
    k(0x71, 192, 0x6D, "F22"), k(0x72, 193, 0x6E, "F23"), k(0x73, 194, 0x76, "F24"),
    k(0x7F, 113, X | 0x20, "AudioVolumeMute"), k(0x80, 115, X | 0x30, "AudioVolumeUp"),
    k(0x81, 114, X | 0x2E, "AudioVolumeDown"), k(0x85, 121, 0x7E, "NumpadComma"),
    k(0x87, 89, 0x73, "IntlRo"), k(0x88, 93, 0x70, "KanaMode"),
    k(0x89, 124, 0x7D, "IntlYen"), k(0x8A, 92, 0x79, "Convert"),
    k(0x8B, 94, 0x7B, "NonConvert"),
    // Korean keys: set-1 sends F2/F1 without a break code; Windows reports 0x72/0x71.
    // Windows callers should prefer VK_HANGUL/VK_HANJA (right Alt is often VK_HANGUL).
    k(HID_LANG1_HANGUL, 122, 0x72, "Lang1(Hangul)"),
    k(HID_LANG2_HANJA, 123, 0x71, "Lang2(Hanja)"),
    k(0xE0, 29, 0x1D, "ControlLeft"), k(0xE1, 42, 0x2A, "ShiftLeft"),
    k(0xE2, 56, 0x38, "AltLeft"), k(0xE3, 125, X | 0x5B, "MetaLeft"),
    k(0xE4, 97, X | 0x1D, "ControlRight"), k(0xE5, 54, 0x36, "ShiftRight"),
    k(0xE6, 100, X | 0x38, "AltRight"), k(0xE7, 126, X | 0x5C, "MetaRight"),
];

pub fn by_hid(hid: u16) -> Option<&'static Key> {
    KEYS.iter().find(|e| e.hid == hid)
}

pub fn hid_to_evdev(hid: u16) -> Option<u16> {
    by_hid(hid).map(|e| e.evdev)
}

pub fn evdev_to_hid(code: u16) -> Option<u16> {
    KEYS.iter().find(|e| e.evdev == code).map(|e| e.hid)
}

pub fn hid_to_win(hid: u16) -> Option<u16> {
    by_hid(hid).map(|e| e.win)
}

/// `sc` is the set-1 make code, `extended` is `LLKHF_EXTENDED`.
pub fn win_to_hid(sc: u16, extended: bool) -> Option<u16> {
    let code = if extended { WIN_EXT | sc } else { sc };
    KEYS.iter().find(|e| e.win == code).map(|e| e.hid)
}

pub fn name(hid: u16) -> &'static str {
    by_hid(hid).map_or("?", |e| e.name)
}

/// Modifier usages are 0xE0..=0xE7; the bit index matches the HID boot report byte 0.
pub fn modifier_bit(hid: u16) -> Option<u8> {
    (0xE0..=0xE7).contains(&hid).then(|| 1 << (hid - 0xE0))
}

pub const MOD_CTRL: u8 = 0x11;
pub const MOD_SHIFT: u8 = 0x22;
pub const MOD_ALT: u8 = 0x44;
pub const MOD_META: u8 = 0x88;

/// US-layout ASCII → (usage, needs shift). Used by test tools to type text.
pub fn ascii_to_hid(c: char) -> Option<(u16, bool)> {
    const UNSHIFTED: &str = "1234567890\n\x1b\x08\t -=[]\\\0;'`,./";
    const SHIFTED: &str = "!@#$%^&*()\0\0\0\0\0_+{}|\0:\"~<>?";
    Some(match c {
        'a'..='z' => (0x04 + (c as u16 - 'a' as u16), false),
        'A'..='Z' => (0x04 + (c as u16 - 'A' as u16), true),
        _ => {
            if let Some(i) = UNSHIFTED.chars().position(|u| u == c && u != '\0') {
                (0x1E + i as u16, false)
            } else if let Some(i) = SHIFTED.chars().position(|u| u == c && u != '\0') {
                (0x1E + i as u16, true)
            } else {
                return None;
            }
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;

    #[test]
    fn columns_are_unique() {
        for (col, get) in [
            ("hid", (|e: &Key| e.hid) as fn(&Key) -> u16),
            ("evdev", |e: &Key| e.evdev),
            ("win", |e: &Key| e.win),
        ] {
            let mut seen = HashSet::new();
            for e in KEYS {
                assert!(seen.insert(get(e)), "duplicate {col} for {}", e.name);
            }
        }
    }

    #[test]
    fn roundtrips() {
        for e in KEYS {
            assert_eq!(evdev_to_hid(hid_to_evdev(e.hid).unwrap()), Some(e.hid));
            let w = hid_to_win(e.hid).unwrap();
            assert_eq!(
                win_to_hid(w & 0xFF, w & WIN_EXT != 0),
                Some(e.hid),
                "{}",
                e.name
            );
        }
    }

    #[test]
    fn spot_checks() {
        // Letters, digits and the main block share set-1 and evdev numbering.
        assert_eq!(hid_to_evdev(0x04), Some(30)); // KEY_A
        assert_eq!(hid_to_evdev(0x4F), Some(106)); // KEY_RIGHT
        assert_eq!(win_to_hid(0x4D, true), Some(0x4F)); // E0 4D = Right
        assert_eq!(win_to_hid(0x4D, false), Some(0x5E)); // 4D = Numpad6
        assert_eq!(win_to_hid(0x1D, true), Some(0xE4)); // RCtrl
        assert_eq!(hid_to_evdev(HID_LANG1_HANGUL), Some(122)); // KEY_HANGEUL
        assert_eq!(modifier_bit(0xE5), Some(0x20));
        assert_eq!(modifier_bit(0x04), None);
    }

    #[test]
    fn ascii() {
        assert_eq!(ascii_to_hid('a'), Some((0x04, false)));
        assert_eq!(ascii_to_hid('Z'), Some((0x1D, true)));
        assert_eq!(ascii_to_hid('0'), Some((0x27, false)));
        assert_eq!(ascii_to_hid(')'), Some((0x27, true)));
        assert_eq!(ascii_to_hid(' '), Some((0x2C, false)));
        assert_eq!(ascii_to_hid('\n'), Some((0x28, false)));
        assert_eq!(ascii_to_hid('?'), Some((0x38, true)));
        assert_eq!(ascii_to_hid('"'), Some((0x34, true)));
        assert_eq!(ascii_to_hid('|'), Some((0x31, true)));
        assert_eq!(ascii_to_hid('~'), Some((0x35, true)));
        assert_eq!(ascii_to_hid('한'), None);
    }
}
