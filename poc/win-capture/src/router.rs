//! Decides, for every physical input event, whether it goes to the local OS or the
//! remote receiver, and handles the switch hotkeys.
//!
//! Key rule: a key-up (or button-up) always follows its key-down. If Ctrl was pressed
//! while focus was local, its release is passed to the local OS even after switching to
//! remote; otherwise the local OS would see Ctrl stuck. Keys that were held on the remote
//! side when focus returns are released on the remote and their physical release is
//! swallowed.

use keymahub_proto::{Button, Msg};
use std::collections::HashMap;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Focus {
    Local,
    Remote,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Verdict {
    Pass,
    Block,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Dest {
    Local,
    Remote,
    Swallowed,
}

impl Dest {
    fn verdict(self) -> Verdict {
        match self {
            Dest::Local => Verdict::Pass,
            Dest::Remote | Dest::Swallowed => Verdict::Block,
        }
    }
}

// Windows virtual-key codes the router needs (stable values from WinUser.h).
pub const VK_SHIFT: u32 = 0x10;
pub const VK_CONTROL: u32 = 0x11;
pub const VK_MENU: u32 = 0x12;
pub const VK_ESCAPE: u32 = 0x1B;
pub const VK_LEFT: u32 = 0x25;
pub const VK_RIGHT: u32 = 0x27;
pub const VK_LSHIFT: u32 = 0xA0;
pub const VK_RSHIFT: u32 = 0xA1;
pub const VK_LCONTROL: u32 = 0xA2;
pub const VK_RCONTROL: u32 = 0xA3;
pub const VK_LMENU: u32 = 0xA4;
pub const VK_RMENU: u32 = 0xA5;

pub struct Router {
    focus: Focus,
    connected: bool,
    keys: HashMap<u32, (Dest, Option<u16>)>,
    buttons: HashMap<Button, Dest>,
    out: Vec<Msg>,
}

impl Default for Router {
    fn default() -> Self {
        Router::new()
    }
}

impl Router {
    pub fn new() -> Router {
        Router {
            focus: Focus::Local,
            connected: false,
            keys: HashMap::new(),
            buttons: HashMap::new(),
            out: Vec::new(),
        }
    }

    pub fn focus(&self) -> Focus {
        self.focus
    }

    /// Messages produced since the last call, in order.
    pub fn drain(&mut self) -> std::vec::Drain<'_, Msg> {
        self.out.drain(..)
    }

    pub fn set_connected(&mut self, connected: bool) {
        self.connected = connected;
        if !connected {
            self.go_local();
        }
    }

    fn held(&self, vks: &[u32]) -> bool {
        vks.iter().any(|vk| self.keys.contains_key(vk))
    }

    /// `vk` identifies the physical key, `hid` is its usage if it has one.
    pub fn on_key(&mut self, vk: u32, hid: Option<u16>, down: bool) -> Verdict {
        if !down {
            return match self.keys.remove(&vk) {
                Some((Dest::Remote, Some(usage))) => {
                    self.out.push(Msg::Key {
                        usage,
                        down: false,
                        repeat: false,
                    });
                    Verdict::Block
                }
                Some((dest, _)) => dest.verdict(),
                // Down happened before we started: let the OS decide.
                None if self.focus == Focus::Local => Verdict::Pass,
                None => Verdict::Block,
            };
        }

        if let Some(&(dest, usage)) = self.keys.get(&vk) {
            // Auto-repeat: follows the original key-down.
            if let (Dest::Remote, Some(usage)) = (dest, usage) {
                self.out.push(Msg::Key {
                    usage,
                    down: true,
                    repeat: true,
                });
            }
            return dest.verdict();
        }

        let ctrl = self.held(&[VK_CONTROL, VK_LCONTROL, VK_RCONTROL]);
        let alt = self.held(&[VK_MENU, VK_LMENU, VK_RMENU]);
        let shift = self.held(&[VK_SHIFT, VK_LSHIFT, VK_RSHIFT]);
        if ctrl && alt {
            let hotkey = match vk {
                VK_ESCAPE if shift => Some(Focus::Local),
                VK_RIGHT if !shift => Some(Focus::Remote),
                VK_LEFT if !shift => Some(Focus::Local),
                _ => None,
            };
            if let Some(target) = hotkey {
                self.keys.insert(vk, (Dest::Swallowed, None));
                match target {
                    Focus::Local => self.go_local(),
                    Focus::Remote => self.go_remote(),
                }
                return Verdict::Block;
            }
        }

        let dest = match (self.focus, hid) {
            (Focus::Local, _) => Dest::Local,
            (Focus::Remote, Some(usage)) => {
                self.out.push(Msg::Key {
                    usage,
                    down: true,
                    repeat: false,
                });
                Dest::Remote
            }
            // No HID mapping: never leak it to the local OS while remote.
            (Focus::Remote, None) => Dest::Swallowed,
        };
        self.keys.insert(vk, (dest, hid));
        dest.verdict()
    }

    pub fn on_button(&mut self, button: Button, down: bool) -> Verdict {
        if !down {
            return match self.buttons.remove(&button) {
                Some(Dest::Remote) => {
                    self.out.push(Msg::MouseButton {
                        button,
                        down: false,
                    });
                    Verdict::Block
                }
                Some(dest) => dest.verdict(),
                None if self.focus == Focus::Local => Verdict::Pass,
                None => Verdict::Block,
            };
        }
        let dest = match self.focus {
            Focus::Local => Dest::Local,
            Focus::Remote => {
                self.out.push(Msg::MouseButton { button, down: true });
                Dest::Remote
            }
        };
        self.buttons.insert(button, dest);
        dest.verdict()
    }

    pub fn on_wheel(&mut self, v: i16, h: i16) -> Verdict {
        match self.focus {
            Focus::Local => Verdict::Pass,
            Focus::Remote => {
                self.out.push(Msg::Wheel { v, h });
                Verdict::Block
            }
        }
    }

    /// Cursor movement seen by the hook. Blocked while remote so the local cursor stays put.
    pub fn on_pointer_move(&self) -> Verdict {
        match self.focus {
            Focus::Local => Verdict::Pass,
            Focus::Remote => Verdict::Block,
        }
    }

    /// Relative motion from Raw Input (not affected by blocking in the hook).
    pub fn on_raw_motion(&mut self, dx: i32, dy: i32) {
        if self.focus == Focus::Remote && (dx != 0 || dy != 0) {
            keymahub_proto::split_motion(dx, dy, |m| self.out.push(m));
        }
    }

    fn go_remote(&mut self) {
        if self.focus == Focus::Remote || !self.connected {
            return;
        }
        self.focus = Focus::Remote;
        self.out.push(Msg::Enter);
    }

    fn go_local(&mut self) {
        if self.focus == Focus::Local {
            return;
        }
        self.focus = Focus::Local;
        for (dest, usage) in self.keys.values_mut() {
            if *dest == Dest::Remote {
                if let Some(usage) = *usage {
                    self.out.push(Msg::Key {
                        usage,
                        down: false,
                        repeat: false,
                    });
                }
                *dest = Dest::Swallowed;
            }
        }
        for (button, dest) in self.buttons.iter_mut() {
            if *dest == Dest::Remote {
                self.out.push(Msg::MouseButton {
                    button: *button,
                    down: false,
                });
                *dest = Dest::Swallowed;
            }
        }
        self.out.push(Msg::ReleaseAll);
        self.out.push(Msg::Leave);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use Verdict::*;

    const VK_A: u32 = 0x41;
    const HID_A: u16 = 0x04;
    const HID_LCTRL: u16 = 0xE0;
    const HID_LALT: u16 = 0xE2;

    fn connected() -> Router {
        let mut r = Router::new();
        r.set_connected(true);
        r
    }

    fn msgs(r: &mut Router) -> Vec<Msg> {
        r.drain().collect()
    }

    /// Presses Ctrl+Alt+<vk> and releases everything, returning the verdicts in order.
    fn hotkey(r: &mut Router, vk: u32) -> Vec<Verdict> {
        vec![
            r.on_key(VK_LCONTROL, Some(HID_LCTRL), true),
            r.on_key(VK_LMENU, Some(HID_LALT), true),
            r.on_key(vk, None, true),
            r.on_key(vk, None, false),
            r.on_key(VK_LMENU, Some(HID_LALT), false),
            r.on_key(VK_LCONTROL, Some(HID_LCTRL), false),
        ]
    }

    #[test]
    fn local_passes_everything() {
        let mut r = connected();
        assert_eq!(r.on_key(VK_A, Some(HID_A), true), Pass);
        assert_eq!(r.on_key(VK_A, Some(HID_A), false), Pass);
        assert_eq!(r.on_button(Button::Left, true), Pass);
        assert_eq!(r.on_wheel(120, 0), Pass);
        r.on_raw_motion(5, 5);
        assert!(msgs(&mut r).is_empty());
    }

    #[test]
    fn switch_to_remote_keeps_local_modifier_releases_local() {
        let mut r = connected();
        // Ctrl/Alt go down locally, arrow is swallowed, their releases must reach the local OS.
        assert_eq!(
            hotkey(&mut r, VK_RIGHT),
            [Pass, Pass, Block, Block, Pass, Pass]
        );
        assert_eq!(r.focus(), Focus::Remote);
        assert_eq!(msgs(&mut r), [Msg::Enter]);

        assert_eq!(r.on_key(VK_A, Some(HID_A), true), Block);
        assert_eq!(r.on_key(VK_A, Some(HID_A), true), Block);
        assert_eq!(r.on_key(VK_A, Some(HID_A), false), Block);
        r.on_raw_motion(3, -4);
        assert_eq!(r.on_pointer_move(), Block);
        assert_eq!(r.on_wheel(-120, 0), Block);
        assert_eq!(
            msgs(&mut r),
            [
                Msg::Key {
                    usage: HID_A,
                    down: true,
                    repeat: false
                },
                Msg::Key {
                    usage: HID_A,
                    down: true,
                    repeat: true
                },
                Msg::Key {
                    usage: HID_A,
                    down: false,
                    repeat: false
                },
                Msg::MouseMove { dx: 3, dy: -4 },
                Msg::Wheel { v: -120, h: 0 },
            ]
        );
    }

    #[test]
    fn switch_back_releases_remote_keys_and_swallows_their_physical_release() {
        let mut r = connected();
        hotkey(&mut r, VK_RIGHT);
        msgs(&mut r);

        // Hold A and the left button on the remote, then Ctrl+Alt+Left.
        r.on_key(VK_A, Some(HID_A), true);
        r.on_button(Button::Left, true);
        assert_eq!(r.on_key(VK_LCONTROL, Some(HID_LCTRL), true), Block);
        assert_eq!(r.on_key(VK_LMENU, Some(HID_LALT), true), Block);
        assert_eq!(r.on_key(VK_LEFT, None, true), Block);
        assert_eq!(r.focus(), Focus::Local);

        let out = msgs(&mut r);
        assert_eq!(&out[out.len() - 2..], [Msg::ReleaseAll, Msg::Leave]);
        for m in [
            Msg::Key {
                usage: HID_A,
                down: false,
                repeat: false,
            },
            Msg::Key {
                usage: HID_LCTRL,
                down: false,
                repeat: false,
            },
            Msg::Key {
                usage: HID_LALT,
                down: false,
                repeat: false,
            },
            Msg::MouseButton {
                button: Button::Left,
                down: false,
            },
        ] {
            assert!(out.contains(&m), "missing {m:?} in {out:?}");
        }

        // Physical releases of keys that went to the remote never reach the local OS.
        for (vk, hid) in [
            (VK_LEFT, None),
            (VK_LMENU, Some(HID_LALT)),
            (VK_LCONTROL, Some(HID_LCTRL)),
            (VK_A, Some(HID_A)),
        ] {
            assert_eq!(r.on_key(vk, hid, false), Block);
        }
        assert_eq!(r.on_button(Button::Left, false), Block);
        assert!(msgs(&mut r).is_empty());

        // And afterwards local input is normal again.
        assert_eq!(r.on_key(VK_A, Some(HID_A), true), Pass);
    }

    #[test]
    fn emergency_hotkey_and_disconnect_return_to_local() {
        let mut r = connected();
        hotkey(&mut r, VK_RIGHT);
        r.on_key(VK_LCONTROL, Some(HID_LCTRL), true);
        r.on_key(VK_LMENU, Some(HID_LALT), true);
        r.on_key(VK_LSHIFT, Some(0xE1), true);
        assert_eq!(r.on_key(VK_ESCAPE, None, true), Block);
        assert_eq!(r.focus(), Focus::Local);

        let mut r = connected();
        hotkey(&mut r, VK_RIGHT);
        msgs(&mut r);
        r.set_connected(false);
        assert_eq!(r.focus(), Focus::Local);
        assert_eq!(msgs(&mut r), [Msg::ReleaseAll, Msg::Leave]);
    }

    #[test]
    fn cannot_go_remote_when_disconnected() {
        let mut r = Router::new();
        assert_eq!(hotkey(&mut r, VK_RIGHT)[2], Block);
        assert_eq!(r.focus(), Focus::Local);
        assert!(msgs(&mut r).is_empty());
    }

    #[test]
    fn unmapped_key_while_remote_is_swallowed() {
        let mut r = connected();
        hotkey(&mut r, VK_RIGHT);
        msgs(&mut r);
        assert_eq!(r.on_key(0xFF, None, true), Block);
        assert_eq!(r.on_key(0xFF, None, false), Block);
        assert!(msgs(&mut r).is_empty());
    }

    #[test]
    fn button_pressed_locally_releases_locally_after_switch() {
        let mut r = connected();
        assert_eq!(r.on_button(Button::Right, true), Pass);
        hotkey(&mut r, VK_RIGHT);
        msgs(&mut r);
        assert_eq!(r.on_button(Button::Right, false), Pass);
        assert!(msgs(&mut r).is_empty());
    }
}
