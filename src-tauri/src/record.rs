//! Screen recording from the notch, with ffmpeg (gdigrab: the whole desktop, 30 fps,
//! H.264). Video only: Windows has no standard device for "what the speakers play" that
//! ffmpeg can open without extra drivers. Files go to Videos\Notch Recordings.

use std::io::Write;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use std::time::{Duration, Instant, SystemTime};

struct Recording {
    child: Child,
    path: PathBuf,
    started_ms: u64,
}

pub struct RecordState(Mutex<Option<Recording>>);

impl Default for RecordState {
    fn default() -> Self {
        RecordState(Mutex::new(None))
    }
}

#[derive(serde::Serialize)]
pub struct RecordStatus {
    recording: bool,
    /// Start time, ms since the Unix epoch (for the running timer).
    started: Option<u64>,
    path: Option<String>,
    /// ffmpeg found (PATH or winget).
    ffmpeg: bool,
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(SystemTime::UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

fn out_dir() -> Result<PathBuf, String> {
    let home = std::env::var_os("USERPROFILE").ok_or("no USERPROFILE")?;
    let dir = PathBuf::from(home).join("Videos").join("Notch Recordings");
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    Ok(dir)
}

/// "Recording 2026-11-04 21-30-05.mp4" in local time (sortable, no characters Windows refuses).
fn file_name() -> String {
    #[cfg(windows)]
    {
        let t = unsafe { windows::Win32::System::SystemInformation::GetLocalTime() };
        format!("Recording {:04}-{:02}-{:02} {:02}-{:02}-{:02}.mp4", t.wYear, t.wMonth, t.wDay, t.wHour, t.wMinute, t.wSecond)
    }
    #[cfg(not(windows))]
    {
        format!("Recording {}.mp4", now_ms() / 1000)
    }
}

#[tauri::command]
pub fn record_status(state: tauri::State<'_, RecordState>) -> RecordStatus {
    let mut guard = state.0.lock().unwrap();
    // ffmpeg ended on its own (error, disk full): not recording any more.
    if let Some(r) = guard.as_mut() {
        if let Ok(Some(_)) = r.child.try_wait() {
            *guard = None;
        }
    }
    let ffmpeg = crate::convert::ffmpeg().is_some();
    match guard.as_ref() {
        Some(r) => RecordStatus { recording: true, started: Some(r.started_ms), path: Some(r.path.display().to_string()), ffmpeg },
        None => RecordStatus { recording: false, started: None, path: None, ffmpeg },
    }
}

#[tauri::command]
pub fn record_start(state: tauri::State<'_, RecordState>) -> Result<u64, String> {
    let mut guard = state.0.lock().unwrap();
    if let Some(r) = guard.as_ref() {
        return Ok(r.started_ms);
    }
    let ffmpeg = crate::convert::ffmpeg().ok_or("ffmpeg missing")?;
    let path = out_dir()?.join(file_name());
    let mut cmd = Command::new(ffmpeg);
    cmd.args(["-hide_banner", "-loglevel", "error", "-y", "-f", "gdigrab", "-framerate", "30", "-draw_mouse", "1", "-i", "desktop"])
        .args(["-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p", "-movflags", "+faststart"])
        // gdigrab needs even dimensions for yuv420p.
        .args(["-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2"])
        .arg(&path)
        .stdin(Stdio::piped())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    #[cfg(windows)]
    crate::convert::hide_console(&mut cmd);
    let child = cmd.spawn().map_err(|e| format!("ffmpeg: {e}"))?;
    let started_ms = now_ms();
    *guard = Some(Recording { child, path, started_ms });
    Ok(started_ms)
}

/// Stop and finish the file ("q" lets ffmpeg write the index; killing would leave it broken).
#[tauri::command]
pub fn record_stop(state: tauri::State<'_, RecordState>) -> Result<String, String> {
    let Some(mut r) = state.0.lock().unwrap().take() else {
        return Err("not recording".into());
    };
    if let Some(stdin) = r.child.stdin.as_mut() {
        let _ = stdin.write_all(b"q");
        let _ = stdin.flush();
    }
    let deadline = Instant::now() + Duration::from_secs(8);
    loop {
        match r.child.try_wait() {
            Ok(Some(_)) => break,
            Ok(None) if Instant::now() < deadline => std::thread::sleep(Duration::from_millis(100)),
            _ => {
                let _ = r.child.kill();
                break;
            }
        }
    }
    Ok(r.path.display().to_string())
}

/// Install ffmpeg with winget in a visible console (the user sees progress and prompts).
#[tauri::command]
pub fn ffmpeg_install() -> Result<(), String> {
    Command::new("cmd")
        .args(["/c", "start", "Install ffmpeg", "winget", "install", "--id", "Gyan.FFmpeg", "-e", "--accept-source-agreements", "--accept-package-agreements"])
        .spawn()
        .map(|_| ())
        .map_err(|e| e.to_string())
}
