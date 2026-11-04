//! Screen brightness, Night light, and a file picker for the converter and shelf.
//!
//! Brightness: WMI (laptop panels; most desktop monitors don't expose it there).
//! Night light: Windows has no API for it; its on/off state lives in a CloudStore
//! registry blob that Settings itself writes. The toggle flips that blob the same way;
//! if the blob doesn't look as expected, it opens Settings → Night light instead.

use std::process::Command;

fn powershell(script: &str) -> Result<String, String> {
    let mut cmd = Command::new("powershell");
    cmd.args(["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script]);
    #[cfg(windows)]
    crate::convert::hide_console(&mut cmd);
    let out = cmd.output().map_err(|e| e.to_string())?;
    if !out.status.success() {
        return Err(String::from_utf8_lossy(&out.stderr).lines().last().unwrap_or("powershell failed").to_string());
    }
    Ok(String::from_utf8_lossy(&out.stdout).trim().to_string())
}

/// Current brightness 0–100, or None when the display can't be dimmed this way.
#[tauri::command]
pub async fn brightness_get() -> Option<u8> {
    tauri::async_runtime::spawn_blocking(|| {
        powershell("(Get-CimInstance -Namespace root/wmi -ClassName WmiMonitorBrightness -ErrorAction Stop | Select-Object -First 1).CurrentBrightness")
            .ok()
            .and_then(|s| s.parse::<u8>().ok())
    })
    .await
    .ok()
    .flatten()
}

#[tauri::command]
pub async fn brightness_set(level: u8) -> Result<(), String> {
    let level = level.min(100);
    tauri::async_runtime::spawn_blocking(move || {
        powershell(&format!(
            "Get-CimInstance -Namespace root/wmi -ClassName WmiMonitorBrightnessMethods -ErrorAction Stop | Invoke-CimMethod -MethodName WmiSetBrightness -Arguments @{{Brightness=[byte]{level};Timeout=[uint32]0}} | Out-Null"
        ))
        .map(|_| ())
    })
    .await
    .map_err(|e| e.to_string())?
}

const NIGHT_KEY: &str = r"Software\Microsoft\Windows\CurrentVersion\CloudStore\Store\DefaultAccount\Current\default$windows.data.bluelightreduction.bluelightreductionstate\windows.data.bluelightreduction.bluelightreductionstate";

fn night_on(data: &[u8]) -> Option<bool> {
    match data.get(18) {
        Some(0x15) => Some(true),
        Some(0x13) => Some(false),
        _ => None,
    }
}

/// The blob with Night light flipped, or None if it doesn't have the known layout.
fn night_toggled(data: &[u8]) -> Option<Vec<u8>> {
    let mut out = match night_on(data)? {
        true => {
            // On: two extra bytes 0x10 0x00 after byte 22.
            if data.len() < 25 || data[23] != 0x10 || data[24] != 0x00 {
                return None;
            }
            let mut v = data[..23].to_vec();
            v.extend_from_slice(&data[25..]);
            v[18] = 0x13;
            v
        }
        false => {
            if data.len() < 24 {
                return None;
            }
            let mut v = data[..23].to_vec();
            v.extend_from_slice(&[0x10, 0x00]);
            v.extend_from_slice(&data[23..]);
            v[18] = 0x15;
            v
        }
    };
    // Bump the change counter so Windows picks the new state up.
    for b in out.iter_mut().take(15).skip(10) {
        if *b != 0xff {
            *b += 1;
            break;
        }
    }
    Some(out)
}

#[cfg(windows)]
fn read_night() -> Option<Vec<u8>> {
    use windows::core::{w, HSTRING};
    use windows::Win32::System::Registry::{RegGetValueW, HKEY_CURRENT_USER, RRF_RT_REG_BINARY};
    let key = HSTRING::from(NIGHT_KEY);
    let mut size = 0u32;
    unsafe {
        if RegGetValueW(HKEY_CURRENT_USER, &key, w!("Data"), RRF_RT_REG_BINARY, None, None, Some(&mut size)).is_err() || size == 0 {
            return None;
        }
        let mut buf = vec![0u8; size as usize];
        if RegGetValueW(HKEY_CURRENT_USER, &key, w!("Data"), RRF_RT_REG_BINARY, None, Some(buf.as_mut_ptr() as _), Some(&mut size)).is_err() {
            return None;
        }
        buf.truncate(size as usize);
        Some(buf)
    }
}

#[cfg(windows)]
fn write_night(data: &[u8]) -> bool {
    use windows::core::{w, HSTRING};
    use windows::Win32::System::Registry::{RegSetKeyValueW, HKEY_CURRENT_USER, REG_BINARY};
    let key = HSTRING::from(NIGHT_KEY);
    unsafe { RegSetKeyValueW(HKEY_CURRENT_USER, &key, w!("Data"), REG_BINARY.0, Some(data.as_ptr() as _), data.len() as u32).is_ok() }
}

/// Night light on? None = unknown (the toggle then opens Settings).
#[tauri::command]
pub fn nightlight_get() -> Option<bool> {
    #[cfg(windows)]
    {
        read_night().as_deref().and_then(night_on)
    }
    #[cfg(not(windows))]
    {
        None
    }
}

/// Flip Night light. Returns the new state, or None after opening Settings instead.
#[tauri::command]
pub fn nightlight_toggle() -> Option<bool> {
    #[cfg(windows)]
    {
        if let Some(new) = read_night().as_deref().and_then(night_toggled) {
            if write_night(&new) {
                return night_on(&new);
            }
        }
        let _ = Command::new("explorer").arg("ms-settings:nightlight").spawn();
        None
    }
    #[cfg(not(windows))]
    {
        None
    }
}

/// The Windows "Open" dialog (several files). Empty when cancelled.
#[tauri::command]
pub async fn pick_files() -> Result<Vec<String>, String> {
    tauri::async_runtime::spawn_blocking(|| {
        let script = "[Console]::OutputEncoding=[Text.Encoding]::UTF8; Add-Type -AssemblyName System.Windows.Forms; \
            $o=New-Object System.Windows.Forms.Form -Property @{TopMost=$true;ShowInTaskbar=$false;WindowState='Minimized'}; \
            $d=New-Object System.Windows.Forms.OpenFileDialog; $d.Multiselect=$true; \
            if($d.ShowDialog($o) -eq 'OK'){ $d.FileNames -join \"`n\" }";
        let mut cmd = Command::new("powershell");
        cmd.args(["-NoProfile", "-STA", "-ExecutionPolicy", "Bypass", "-Command", script]);
        #[cfg(windows)]
        crate::convert::hide_console(&mut cmd);
        let out = cmd.output().map_err(|e| e.to_string())?;
        Ok(String::from_utf8_lossy(&out.stdout).lines().map(|l| l.trim().to_string()).filter(|l| !l.is_empty()).collect())
    })
    .await
    .map_err(|e| e.to_string())?
}

/// A sound file picked by the user, as base64 (played through a blob URL). Max. 8 MB.
#[tauri::command]
pub fn sound_read(path: String) -> Result<String, String> {
    use base64::Engine;
    let ext = std::path::Path::new(&path).extension().map(|e| e.to_string_lossy().to_lowercase()).unwrap_or_default();
    if !["mp3", "wav", "ogg", "m4a", "aac", "flac", "wma"].contains(&ext.as_str()) {
        return Err("not an audio file".into());
    }
    let meta = std::fs::metadata(&path).map_err(|e| e.to_string())?;
    if meta.len() > 8 * 1024 * 1024 {
        return Err("too large".into());
    }
    let bytes = std::fs::read(&path).map_err(|e| e.to_string())?;
    Ok(base64::engine::general_purpose::STANDARD.encode(bytes))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn night_round_trip() {
        let mut off = vec![0u8; 41];
        off[18] = 0x13;
        off[10] = 5;
        off[23] = 0xAA;
        let on = night_toggled(&off).unwrap();
        assert_eq!(on.len(), 43);
        assert_eq!(on[18], 0x15);
        assert_eq!(&on[23..26], &[0x10, 0x00, 0xAA]);
        assert_eq!(on[10], 6);
        let back = night_toggled(&on).unwrap();
        assert_eq!(back.len(), 41);
        assert_eq!(back[18], 0x13);
        assert_eq!(back[23], 0xAA);
        assert!(night_toggled(&[0u8; 30]).is_none());
    }
}
