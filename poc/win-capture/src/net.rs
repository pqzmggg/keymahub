//! Sender thread: owns the TCP connection to the receiver, coalesces mouse motion,
//! reconnects on failure and measures round-trip time with Ping/Pong.

use keymahub_proto::{read_msg, split_motion, write_msg, Msg, WIRE_VERSION};
use std::io::{self, BufReader, BufWriter, Write};
use std::net::{SocketAddr, TcpStream};
use std::sync::mpsc::{Receiver, RecvTimeoutError};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

const PING_EVERY: Duration = Duration::from_secs(1);
const REPORT_EVERY: Duration = Duration::from_secs(10);

/// Runs forever. `on_link(true/false)` is called whenever the connection comes up or drops.
pub fn run(target: SocketAddr, name: String, rx: Receiver<Msg>, on_link: impl Fn(bool)) {
    let base = Instant::now();
    loop {
        match TcpStream::connect_timeout(&target, Duration::from_secs(2)) {
            Ok(stream) => {
                println!("[net] connected to {target}");
                // Drop anything queued while we were offline.
                while rx.try_recv().is_ok() {}
                on_link(true);
                if let Err(e) = session(stream, &name, &rx, base) {
                    println!("[net] link lost: {e}");
                }
                on_link(false);
            }
            Err(e) => println!("[net] connect {target} failed: {e}"),
        }
        thread::sleep(Duration::from_secs(1));
    }
}

fn session(stream: TcpStream, name: &str, rx: &Receiver<Msg>, base: Instant) -> io::Result<()> {
    stream.set_nodelay(true)?;
    let rtts = Arc::new(Mutex::new(Vec::<u64>::new()));
    let reader = {
        let stream = stream.try_clone()?;
        let rtts = rtts.clone();
        thread::spawn(move || -> io::Result<()> {
            let mut r = BufReader::new(stream);
            while let Some(m) = read_msg(&mut r)? {
                match m {
                    Msg::Pong { t_us } => {
                        let now = base.elapsed().as_micros() as u64;
                        rtts.lock().unwrap().push(now.saturating_sub(t_us));
                    }
                    Msg::Hello { name, version } => {
                        println!("[net] receiver: {name} (wire v{version})")
                    }
                    other => println!("[net] <- {other:?}"),
                }
            }
            Err(io::ErrorKind::UnexpectedEof.into())
        })
    };

    let mut w = BufWriter::new(stream);
    write_msg(
        &mut w,
        &Msg::Hello {
            version: WIRE_VERSION,
            name: name.to_owned(),
        },
    )?;
    w.flush()?;
    let (mut last_ping, mut last_report) = (Instant::now(), Instant::now());
    let mut pending: Option<Msg> = None;

    loop {
        if reader.is_finished() {
            return Err(io::ErrorKind::ConnectionReset.into());
        }
        let first = match pending.take() {
            Some(m) => Some(m),
            None => match rx.recv_timeout(PING_EVERY) {
                Ok(m) => Some(m),
                Err(RecvTimeoutError::Timeout) => None,
                Err(RecvTimeoutError::Disconnected) => return Ok(()),
            },
        };
        if let Some(m) = first {
            // Coalesce a burst of motion into one frame; stop at the first other message.
            if let Msg::MouseMove { dx, dy } = m {
                let (mut sx, mut sy) = (dx as i32, dy as i32);
                while let Ok(next) = rx.try_recv() {
                    match next {
                        Msg::MouseMove { dx, dy } => {
                            sx += dx as i32;
                            sy += dy as i32;
                        }
                        other => {
                            pending = Some(other);
                            break;
                        }
                    }
                }
                let mut err = Ok(());
                split_motion(sx, sy, |m| {
                    if err.is_ok() {
                        err = write_msg(&mut w, &m);
                    }
                });
                err?;
            } else {
                write_msg(&mut w, &m)?;
            }
        }
        if last_ping.elapsed() >= PING_EVERY {
            write_msg(
                &mut w,
                &Msg::Ping {
                    t_us: base.elapsed().as_micros() as u64,
                },
            )?;
            last_ping = Instant::now();
        }
        w.flush()?;
        if last_report.elapsed() >= REPORT_EVERY {
            report(&mut rtts.lock().unwrap());
            last_report = Instant::now();
        }
    }
}

fn report(rtts: &mut Vec<u64>) {
    if rtts.is_empty() {
        return;
    }
    rtts.sort_unstable();
    let ms = |us: u64| us as f64 / 1000.0;
    println!(
        "[net] rtt over {} pings: min {:.2} / p50 {:.2} / max {:.2} ms",
        rtts.len(),
        ms(rtts[0]),
        ms(rtts[rtts.len() / 2]),
        ms(rtts[rtts.len() - 1])
    );
    rtts.clear();
}

/// `--dry-run`: print what would be sent.
pub fn print_only(rx: Receiver<Msg>) {
    let base = Instant::now();
    let (mut mx, mut my, mut last) = (0i32, 0i32, Instant::now());
    for m in rx {
        match m {
            Msg::MouseMove { dx, dy } => {
                mx += dx as i32;
                my += dy as i32;
                if last.elapsed() >= Duration::from_millis(250) {
                    println!(
                        "{:>8.3}  motion ({mx:+}, {my:+})",
                        base.elapsed().as_secs_f64()
                    );
                    (mx, my, last) = (0, 0, Instant::now());
                }
            }
            Msg::Key {
                usage,
                down,
                repeat,
            } => println!(
                "{:>8.3}  key {:<14} {}{}",
                base.elapsed().as_secs_f64(),
                keymahub_keymap::name(usage),
                if down { "down" } else { "up" },
                if repeat { " (repeat)" } else { "" }
            ),
            other => println!("{:>8.3}  {other:?}", base.elapsed().as_secs_f64()),
        }
    }
}
