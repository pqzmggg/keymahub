//! Win32 glue: low-level keyboard/mouse hooks decide pass/block, Raw Input provides
//! unaccelerated relative motion (Raw Input keeps flowing while the hook blocks movement).
//! Everything below runs on the main thread's message loop.

use crate::net;
use crate::router::{Focus, Router, Verdict};
use keymahub_proto::{Button, Msg};
use std::cell::RefCell;
use std::net::SocketAddr;
use std::sync::mpsc::{self, Sender};
use std::{mem, ptr, thread};
use windows_sys::Win32::Foundation::{HWND, LPARAM, LRESULT, WPARAM};
use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
use windows_sys::Win32::System::Threading::GetCurrentThreadId;
use windows_sys::Win32::UI::Input::KeyboardAndMouse::{VK_HANGUL, VK_HANJA, VK_NUMLOCK, VK_PAUSE};
use windows_sys::Win32::UI::Input::{
    GetRawInputData, RegisterRawInputDevices, HRAWINPUT, MOUSE_MOVE_ABSOLUTE,
    MOUSE_VIRTUAL_DESKTOP, RAWINPUT, RAWINPUTDEVICE, RAWINPUTHEADER, RIDEV_INPUTSINK, RID_INPUT,
    RIM_TYPEMOUSE,
};
use windows_sys::Win32::UI::WindowsAndMessaging::*;

const WM_APP_LINK: u32 = WM_APP + 1;

struct State {
    router: Router,
    tx: Sender<Msg>,
    last_abs: Option<(i32, i32)>,
}

thread_local! {
    static STATE: RefCell<Option<State>> = const { RefCell::new(None) };
}

/// Runs `f` on the state and forwards whatever the router produced.
fn with_state<R>(default: R, f: impl FnOnce(&mut State) -> R) -> R {
    STATE.with(|s| {
        let Ok(mut guard) = s.try_borrow_mut() else {
            return default;
        };
        let Some(st) = guard.as_mut() else {
            return default;
        };
        let before = st.router.focus();
        let r = f(st);
        let after = st.router.focus();
        for m in st.router.drain() {
            let _ = st.tx.send(m);
        }
        if before != after {
            match after {
                Focus::Remote => {
                    println!(">>> REMOTE  (Ctrl+Alt+Left or Ctrl+Alt+Shift+Esc to come back)")
                }
                Focus::Local => println!("<<< LOCAL"),
            }
        }
        r
    })
}

fn verdict_result(v: Verdict, code: i32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    match v {
        Verdict::Block => 1,
        Verdict::Pass => unsafe { CallNextHookEx(ptr::null_mut(), code, wparam, lparam) },
    }
}

fn vk_to_hid(vk: u32, sc: u32, extended: bool) -> Option<u16> {
    // Keys whose hook scan codes are ambiguous or layout-dependent.
    match vk as u16 {
        VK_PAUSE => return Some(0x48),
        VK_NUMLOCK => return Some(0x53),
        VK_HANGUL => return Some(keymahub_keymap::HID_LANG1_HANGUL),
        VK_HANJA => return Some(keymahub_keymap::HID_LANG2_HANJA),
        _ => {}
    }
    keymahub_keymap::win_to_hid(sc as u16, extended)
}

unsafe extern "system" fn keyboard_proc(code: i32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    if code != HC_ACTION as i32 {
        return CallNextHookEx(ptr::null_mut(), code, wparam, lparam);
    }
    let k = &*(lparam as *const KBDLLHOOKSTRUCT);
    if k.flags & LLKHF_INJECTED != 0 {
        return CallNextHookEx(ptr::null_mut(), code, wparam, lparam);
    }
    let down = matches!(wparam as u32, WM_KEYDOWN | WM_SYSKEYDOWN);
    let hid = vk_to_hid(k.vkCode, k.scanCode, k.flags & LLKHF_EXTENDED != 0);
    let v = with_state(Verdict::Pass, |s| s.router.on_key(k.vkCode, hid, down));
    verdict_result(v, code, wparam, lparam)
}

unsafe extern "system" fn mouse_proc(code: i32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    if code != HC_ACTION as i32 {
        return CallNextHookEx(ptr::null_mut(), code, wparam, lparam);
    }
    let m = &*(lparam as *const MSLLHOOKSTRUCT);
    if m.flags & LLMHF_INJECTED != 0 {
        return CallNextHookEx(ptr::null_mut(), code, wparam, lparam);
    }
    let hi = (m.mouseData >> 16) as u16;
    let x_button = if hi == XBUTTON1 {
        Button::Back
    } else {
        Button::Forward
    };
    let v = with_state(Verdict::Pass, |s| {
        let r = &mut s.router;
        match wparam as u32 {
            WM_MOUSEMOVE => r.on_pointer_move(),
            WM_LBUTTONDOWN => r.on_button(Button::Left, true),
            WM_LBUTTONUP => r.on_button(Button::Left, false),
            WM_RBUTTONDOWN => r.on_button(Button::Right, true),
            WM_RBUTTONUP => r.on_button(Button::Right, false),
            WM_MBUTTONDOWN => r.on_button(Button::Middle, true),
            WM_MBUTTONUP => r.on_button(Button::Middle, false),
            WM_XBUTTONDOWN => r.on_button(x_button, true),
            WM_XBUTTONUP => r.on_button(x_button, false),
            WM_MOUSEWHEEL => r.on_wheel(hi as i16, 0),
            WM_MOUSEHWHEEL => r.on_wheel(0, hi as i16),
            _ => Verdict::Pass,
        }
    });
    verdict_result(v, code, wparam, lparam)
}

unsafe fn on_raw_input(lparam: LPARAM) {
    let mut raw: RAWINPUT = mem::zeroed();
    let mut size = mem::size_of::<RAWINPUT>() as u32;
    let got = GetRawInputData(
        lparam as HRAWINPUT,
        RID_INPUT,
        &mut raw as *mut _ as *mut _,
        &mut size,
        mem::size_of::<RAWINPUTHEADER>() as u32,
    );
    if got == u32::MAX || raw.header.dwType != RIM_TYPEMOUSE {
        return;
    }
    let mouse = raw.data.mouse;
    with_state((), |s| {
        if mouse.usFlags & MOUSE_MOVE_ABSOLUTE != 0 {
            // Tablets, RDP and VMs report absolute 0..65535 coordinates; turn them into deltas.
            let (w, h) = if mouse.usFlags & MOUSE_VIRTUAL_DESKTOP != 0 {
                (
                    GetSystemMetrics(SM_CXVIRTUALSCREEN),
                    GetSystemMetrics(SM_CYVIRTUALSCREEN),
                )
            } else {
                (GetSystemMetrics(SM_CXSCREEN), GetSystemMetrics(SM_CYSCREEN))
            };
            let (x, y) = (mouse.lLastX * w / 65535, mouse.lLastY * h / 65535);
            if let Some((lx, ly)) = s.last_abs.replace((x, y)) {
                s.router.on_raw_motion(x - lx, y - ly);
            }
        } else {
            s.last_abs = None;
            s.router.on_raw_motion(mouse.lLastX, mouse.lLastY);
        }
    });
}

unsafe extern "system" fn wnd_proc(
    hwnd: HWND,
    msg: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    if msg == WM_INPUT {
        on_raw_input(lparam);
    }
    DefWindowProcW(hwnd, msg, wparam, lparam)
}

fn wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(Some(0)).collect()
}

pub fn run(target: Option<SocketAddr>, name: String) {
    let (tx, rx) = mpsc::channel::<Msg>();
    let main_tid = unsafe { GetCurrentThreadId() };
    let mut router = Router::new();

    match target {
        Some(addr) => {
            thread::spawn(move || {
                net::run(addr, name, rx, |up| unsafe {
                    PostThreadMessageW(main_tid, WM_APP_LINK, up as usize, 0);
                })
            });
        }
        None => {
            router.set_connected(true);
            thread::spawn(move || net::print_only(rx));
        }
    }
    STATE.with(|s| {
        *s.borrow_mut() = Some(State {
            router,
            tx,
            last_abs: None,
        })
    });

    unsafe {
        let hinst = GetModuleHandleW(ptr::null());
        let class = wide("keymahub-win-capture");
        let wc = WNDCLASSW {
            lpfnWndProc: Some(wnd_proc),
            hInstance: hinst,
            lpszClassName: class.as_ptr(),
            ..Default::default()
        };
        if RegisterClassW(&wc) == 0 {
            panic!("RegisterClassW failed");
        }
        let hwnd = CreateWindowExW(
            0,
            class.as_ptr(),
            ptr::null(),
            0,
            0,
            0,
            0,
            0,
            HWND_MESSAGE,
            ptr::null_mut(),
            hinst,
            ptr::null(),
        );
        assert!(!hwnd.is_null(), "CreateWindowExW failed");

        let rid = RAWINPUTDEVICE {
            usUsagePage: 0x01, // Generic Desktop
            usUsage: 0x02,     // Mouse
            dwFlags: RIDEV_INPUTSINK,
            hwndTarget: hwnd,
        };
        if RegisterRawInputDevices(&rid, 1, mem::size_of::<RAWINPUTDEVICE>() as u32) == 0 {
            panic!("RegisterRawInputDevices failed");
        }

        let kb = SetWindowsHookExW(WH_KEYBOARD_LL, Some(keyboard_proc), hinst, 0);
        let ms = SetWindowsHookExW(WH_MOUSE_LL, Some(mouse_proc), hinst, 0);
        assert!(!kb.is_null() && !ms.is_null(), "SetWindowsHookExW failed");

        println!("hooks installed. Ctrl+Alt+Right = send to receiver, Ctrl+Alt+Left = back,");
        println!("Ctrl+Alt+Shift+Esc = emergency back. Close this window (or Ctrl+C) to quit.");

        let mut msg: MSG = mem::zeroed();
        while GetMessageW(&mut msg, ptr::null_mut(), 0, 0) > 0 {
            if msg.hwnd.is_null() && msg.message == WM_APP_LINK {
                let up = msg.wParam != 0;
                with_state((), |s| s.router.set_connected(up));
                continue;
            }
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
        UnhookWindowsHookEx(kb);
        UnhookWindowsHookEx(ms);
    }
}
