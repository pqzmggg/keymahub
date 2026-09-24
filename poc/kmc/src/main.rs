//! P0 test tool.
//!
//! ```text
//! kmc dump [--port P]                   receiver that prints every event and answers pings
//! kmc demo <ip[:port]> [--only mouse|keys|text]
//!                                       scripted sender: moves, clicks, scrolls, types
//! kmc ping <ip[:port]> [--count N]      round-trip latency to a receiver
//! ```

use keymahub_keymap as keymap;
use keymahub_proto::{read_msg, write_msg, Button, Msg, DEFAULT_PORT, WHEEL_NOTCH, WIRE_VERSION};
use std::io::{self, BufWriter, Write};
use std::net::{SocketAddr, TcpListener, TcpStream, ToSocketAddrs};
use std::thread;
use std::time::{Duration, Instant};

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let res = match args.first().map(String::as_str) {
        Some("dump") => {
            dump(opt(&args, "--port").map_or(DEFAULT_PORT, |p| p.parse().expect("port")))
        }
        Some("demo") => demo(&target(&args), opt(&args, "--only")),
        Some("ping") => ping(
            &target(&args),
            opt(&args, "--count").map_or(20, |n| n.parse().expect("count")),
        ),
        _ => {
            eprintln!("usage: kmc dump [--port P] | kmc demo <ip[:port]> [--only mouse|keys|text] | kmc ping <ip[:port]> [--count N]");
            std::process::exit(2);
        }
    };
    if let Err(e) = res {
        eprintln!("error: {e}");
        std::process::exit(1);
    }
}

fn opt<'a>(args: &'a [String], name: &str) -> Option<&'a str> {
    args.iter()
        .position(|a| a == name)
        .and_then(|i| args.get(i + 1))
        .map(String::as_str)
}

fn target(args: &[String]) -> SocketAddr {
    let raw = args
        .get(1)
        .filter(|a| !a.starts_with("--"))
        .unwrap_or_else(|| {
            eprintln!("missing <ip[:port]>");
            std::process::exit(2);
        });
    let with_port = if raw.contains(':') {
        raw.clone()
    } else {
        format!("{raw}:{DEFAULT_PORT}")
    };
    with_port
        .to_socket_addrs()
        .ok()
        .and_then(|mut a| a.next())
        .unwrap_or_else(|| {
            eprintln!("bad address {raw}");
            std::process::exit(2);
        })
}

/// Connects, turning the common failures into something actionable.
fn dial(addr: &SocketAddr) -> io::Result<TcpStream> {
    TcpStream::connect_timeout(addr, Duration::from_secs(3)).map_err(|e| {
        let hint = match e.kind() {
            io::ErrorKind::ConnectionRefused => {
                "the device is reachable but nothing listens on the port: \
                 start the receiver in the app (not the host) and check its log for \
                 'backend … unavailable'"
            }
            io::ErrorKind::TimedOut => {
                "no answer: check the IP, that both are on the same Wi-Fi, \
                 and that the router does not isolate clients"
            }
            _ => return e,
        };
        io::Error::new(e.kind(), format!("{addr}: {e} ({hint})"))
    })
}

fn hostname() -> String {
    std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME"))
        .unwrap_or_else(|_| "kmc".into())
}

fn hello() -> Msg {
    Msg::Hello {
        version: WIRE_VERSION,
        name: hostname(),
    }
}

// ---------------------------------------------------------------- dump

fn dump(port: u16) -> io::Result<()> {
    let listener = TcpListener::bind(("0.0.0.0", port))?;
    println!("listening on :{port}");
    for conn in listener.incoming() {
        let conn = conn?;
        let peer = conn.peer_addr()?;
        println!("== connected: {peer}");
        if let Err(e) = serve_dump(conn) {
            println!("== {peer}: {e}");
        }
        println!("== disconnected: {peer}");
    }
    Ok(())
}

fn serve_dump(conn: TcpStream) -> io::Result<()> {
    conn.set_nodelay(true)?;
    let mut w = conn.try_clone()?;
    let mut r = io::BufReader::new(conn);
    write_msg(&mut w, &hello())?;
    let start = Instant::now();
    let (mut moves, mut last_report) = (0u32, Instant::now());
    while let Some(m) = read_msg(&mut r)? {
        match &m {
            Msg::Ping { t_us } => {
                write_msg(&mut w, &Msg::Pong { t_us: *t_us })?;
                continue;
            }
            Msg::MouseMove { .. } => {
                moves += 1;
                if last_report.elapsed() >= Duration::from_secs(1) {
                    println!(
                        "{:>9.3}  mouse-move frames/s: {moves}",
                        start.elapsed().as_secs_f64()
                    );
                    moves = 0;
                    last_report = Instant::now();
                }
                continue;
            }
            _ => {}
        }
        println!("{:>9.3}  {}", start.elapsed().as_secs_f64(), describe(&m));
    }
    Ok(())
}

fn describe(m: &Msg) -> String {
    match m {
        Msg::Key {
            usage,
            down,
            repeat,
        } => format!(
            "key {:<14} 0x{usage:02X} {}{}",
            keymap::name(*usage),
            if *down { "down" } else { "up" },
            if *repeat { " (repeat)" } else { "" }
        ),
        other => format!("{other:?}"),
    }
}

// ---------------------------------------------------------------- demo

struct Sender {
    w: BufWriter<TcpStream>,
}

impl Sender {
    fn connect(addr: &SocketAddr) -> io::Result<Sender> {
        let s = dial(addr)?;
        s.set_nodelay(true)?;
        let mut sender = Sender {
            w: BufWriter::new(s),
        };
        sender.send(&hello())?;
        Ok(sender)
    }

    fn send(&mut self, m: &Msg) -> io::Result<()> {
        write_msg(&mut self.w, m)?;
        self.w.flush()
    }

    /// Half-closes and drains the peer's frames so the close is a clean FIN, not a reset.
    fn close(self) {
        let s = self.w.into_inner().ok();
        if let Some(mut s) = s {
            let _ = s.shutdown(std::net::Shutdown::Write);
            let _ = s.set_read_timeout(Some(Duration::from_secs(1)));
            let _ = io::copy(&mut s, &mut io::sink());
        }
    }

    fn key(&mut self, usage: u16, down: bool) -> io::Result<()> {
        self.send(&Msg::Key {
            usage,
            down,
            repeat: false,
        })
    }

    fn tap(&mut self, usage: u16) -> io::Result<()> {
        self.key(usage, true)?;
        pause(15);
        self.key(usage, false)?;
        pause(15);
        Ok(())
    }

    fn chord(&mut self, modifier: u16, usage: u16) -> io::Result<()> {
        self.key(modifier, true)?;
        self.tap(usage)?;
        self.key(modifier, false)
    }

    fn type_text(&mut self, text: &str) -> io::Result<()> {
        for c in text.chars() {
            let Some((usage, shift)) = keymap::ascii_to_hid(c) else {
                continue;
            };
            if shift {
                self.chord(0xE1, usage)?;
            } else {
                self.tap(usage)?;
            }
        }
        Ok(())
    }

    /// Moves in 125 Hz steps like a typical mouse.
    fn glide(&mut self, dx: i32, dy: i32, ms: u64) -> io::Result<()> {
        let steps = (ms / 8).max(1) as i32;
        let (mut sx, mut sy) = (0, 0);
        for i in 1..=steps {
            let (tx, ty) = (dx * i / steps, dy * i / steps);
            self.send(&Msg::MouseMove {
                dx: (tx - sx) as i16,
                dy: (ty - sy) as i16,
            })?;
            (sx, sy) = (tx, ty);
            pause(8);
        }
        Ok(())
    }

    fn click(&mut self, button: Button) -> io::Result<()> {
        self.send(&Msg::MouseButton { button, down: true })?;
        pause(40);
        self.send(&Msg::MouseButton {
            button,
            down: false,
        })?;
        pause(40);
        Ok(())
    }
}

fn pause(ms: u64) {
    thread::sleep(Duration::from_millis(ms));
}

fn demo(addr: &SocketAddr, only: Option<&str>) -> io::Result<()> {
    let mut s = Sender::connect(addr)?;
    let run = |part: &str| only.is_none_or(|o| o == part);
    s.send(&Msg::Enter)?;

    if run("mouse") {
        println!("mouse: square, click, drag, scroll");
        for (dx, dy) in [(300, 0), (0, 300), (-300, 0), (0, -300)] {
            s.glide(dx, dy, 400)?;
        }
        s.click(Button::Left)?;
        s.send(&Msg::MouseButton {
            button: Button::Left,
            down: true,
        })?;
        s.glide(0, -400, 500)?;
        s.send(&Msg::MouseButton {
            button: Button::Left,
            down: false,
        })?;
        pause(300);
        for v in [
            -WHEEL_NOTCH,
            -WHEEL_NOTCH,
            -WHEEL_NOTCH,
            WHEEL_NOTCH,
            WHEEL_NOTCH,
            WHEEL_NOTCH,
        ] {
            s.send(&Msg::Wheel { v, h: 0 })?;
            pause(120);
        }
    }

    if run("keys") {
        println!("keys: arrows, backspace, Ctrl+A");
        for usage in [0x52, 0x51, 0x50, 0x4F] {
            s.tap(usage)?;
        }
        s.chord(0xE0, 0x04)?; // Ctrl+A
        s.tap(0x2A)?; // Backspace
    }

    if run("text") {
        println!("text: ASCII, then Hangul toggle + 2-set 'gksrmf' (= 한글), toggle back");
        s.type_text("Hello keymahub 123!\n")?;
        s.tap(keymap::HID_LANG1_HANGUL)?;
        s.type_text("gksrmf")?;
        s.tap(keymap::HID_LANG1_HANGUL)?;
        s.type_text(" ok\n")?;
    }

    s.send(&Msg::ReleaseAll)?;
    s.send(&Msg::Leave)?;
    s.close();
    println!("done");
    Ok(())
}

// ---------------------------------------------------------------- ping

fn ping(addr: &SocketAddr, count: u32) -> io::Result<()> {
    let s = dial(addr)?;
    s.set_nodelay(true)?;
    s.set_read_timeout(Some(Duration::from_secs(2)))?;
    let mut w = s.try_clone()?;
    let mut r = io::BufReader::new(s);
    write_msg(&mut w, &hello())?;
    let base = Instant::now();
    let mut rtts = Vec::new();
    for _ in 0..count {
        let sent = base.elapsed().as_micros() as u64;
        write_msg(&mut w, &Msg::Ping { t_us: sent })?;
        loop {
            match read_msg(&mut r)? {
                Some(Msg::Pong { t_us }) if t_us == sent => break,
                Some(_) => continue,
                None => return Err(io::ErrorKind::UnexpectedEof.into()),
            }
        }
        let rtt = base.elapsed().as_micros() as u64 - sent;
        println!("rtt {:.2} ms", rtt as f64 / 1000.0);
        rtts.push(rtt);
        pause(100);
    }
    rtts.sort_unstable();
    if let (Some(min), Some(max)) = (rtts.first(), rtts.last()) {
        let avg = rtts.iter().sum::<u64>() / rtts.len() as u64;
        let p50 = rtts[rtts.len() / 2];
        println!(
            "min {:.2} / p50 {:.2} / avg {:.2} / max {:.2} ms",
            *min as f64 / 1e3,
            p50 as f64 / 1e3,
            avg as f64 / 1e3,
            *max as f64 / 1e3
        );
    }
    Ok(())
}
