//! P0-1: capture keyboard and mouse on Windows, block them locally while focus is remote,
//! and forward them to a receiver (`kmc dump` or the Android PoC).
//!
//! ```text
//! win-capture <receiver-ip[:port]> [--name NAME]
//! win-capture --dry-run            # no network, print what would be sent
//! ```

#![cfg_attr(not(windows), allow(dead_code))]

mod net;
mod router;
#[cfg(windows)]
mod win;

#[cfg(windows)]
fn main() {
    use std::net::ToSocketAddrs;

    let args: Vec<String> = std::env::args().skip(1).collect();
    let name = args
        .iter()
        .position(|a| a == "--name")
        .and_then(|i| args.get(i + 1).cloned())
        .or_else(|| std::env::var("COMPUTERNAME").ok())
        .unwrap_or_else(|| "windows".into());

    let target = if args.iter().any(|a| a == "--dry-run") {
        None
    } else {
        let Some(raw) = args.first().filter(|a| !a.starts_with("--")) else {
            eprintln!(
                "usage: win-capture <receiver-ip[:port]> [--name NAME] | win-capture --dry-run"
            );
            std::process::exit(2);
        };
        let full = if raw.contains(':') {
            raw.clone()
        } else {
            format!("{raw}:{}", keymahub_proto::DEFAULT_PORT)
        };
        match full.to_socket_addrs().ok().and_then(|mut a| a.next()) {
            Some(a) => Some(a),
            None => {
                eprintln!("bad address {raw}");
                std::process::exit(2);
            }
        }
    };
    win::run(target, name);
}

#[cfg(not(windows))]
fn main() {
    eprintln!("win-capture only runs on Windows");
    std::process::exit(1);
}
