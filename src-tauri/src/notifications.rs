//! Windows notifications (WhatsApp, mail, …) in the notch.
//!
//! Windows keeps every toast in a per-user SQLite database
//! (%LOCALAPPDATA%\Microsoft\Windows\Notifications\wpndatabase.db). Reading it needs no
//! special permission, unlike UserNotificationListener, which unpackaged apps can't use.
//! The frontend polls `notifications_poll` with the last ID it has seen and gets the new
//! toasts with their app, title and text.

use serde::Serialize;
use std::path::PathBuf;

#[derive(Serialize, Clone)]
pub struct Notif {
    id: i64,
    /// App User Model ID, e.g. "5319275A.WhatsAppDesktop_cv1g1gvanyjgm!App" (to open the app).
    aumid: String,
    /// Readable app name ("WhatsApp", "Outlook", "Chrome").
    app: String,
    title: String,
    body: String,
    /// Arrival time, ms since the Unix epoch.
    at: i64,
}

#[derive(Serialize)]
pub struct Poll {
    items: Vec<Notif>,
    /// Highest notification ID in the database (pass it back next time).
    last: i64,
    /// Why nothing could be read, if so.
    error: Option<String>,
}

fn db_path() -> Option<PathBuf> {
    Some(PathBuf::from(std::env::var_os("LOCALAPPDATA")?).join("Microsoft\\Windows\\Notifications\\wpndatabase.db"))
}

/// New toasts since `after`. `after < 0` = first call: only report the current last ID, so
/// the notch doesn't replay the whole Action Center history on start.
#[tauri::command]
pub fn notifications_poll(after: i64) -> Poll {
    let Some(path) = db_path() else {
        return Poll { items: vec![], last: after, error: Some("no LOCALAPPDATA".into()) };
    };
    match read(&path, after) {
        Ok(p) => p,
        Err(e) => Poll { items: vec![], last: after, error: Some(e) },
    }
}

fn read(path: &std::path::Path, after: i64) -> Result<Poll, String> {
    let conn = open(path)?;
    let last: i64 = conn
        .query_row("SELECT COALESCE(MAX(Id), 0) FROM Notification", [], |r| r.get(0))
        .map_err(|e| e.to_string())?;
    if after < 0 {
        return Ok(Poll { items: vec![], last, error: None });
    }
    let mut stmt = conn
        .prepare(
            "SELECT n.Id, h.PrimaryId, n.Payload, n.ArrivalTime FROM Notification n \
             JOIN NotificationHandler h ON n.HandlerId = h.RecordId \
             WHERE n.Type = 'toast' AND n.Id > ?1 ORDER BY n.Id LIMIT 30",
        )
        .map_err(|e| e.to_string())?;
    let rows = stmt
        .query_map([after], |r| {
            let payload: Vec<u8> = r.get::<_, Option<Vec<u8>>>(2)?.unwrap_or_default();
            Ok((r.get::<_, i64>(0)?, r.get::<_, String>(1)?, payload, r.get::<_, Option<i64>>(3)?.unwrap_or(0)))
        })
        .map_err(|e| e.to_string())?;
    let mut items = vec![];
    for row in rows.flatten() {
        let (id, aumid, payload, arrival) = row;
        let texts = toast_texts(&String::from_utf8_lossy(&payload));
        if texts.is_empty() {
            continue;
        }
        let title = texts[0].clone();
        let body = texts[1..].join("\n");
        items.push(Notif { app: app_name(&aumid, &title, &body), aumid, title, body, at: filetime_to_ms(arrival), id });
    }
    Ok(Poll { items, last: last.max(after), error: None })
}

/// Read-only open; if Windows holds the file in a way that refuses that, read a copy.
fn open(path: &std::path::Path) -> Result<rusqlite::Connection, String> {
    use rusqlite::OpenFlags;
    let flags = OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX;
    if let Ok(c) = rusqlite::Connection::open_with_flags(path, flags) {
        if c.query_row("SELECT 1 FROM Notification LIMIT 1", [], |_| Ok(())).is_ok()
            || c.query_row("SELECT COUNT(*) FROM Notification", [], |_| Ok(())).is_ok()
        {
            return Ok(c);
        }
    }
    // Copy the database with its write-ahead log (recent toasts live there until a checkpoint).
    let dir = std::env::temp_dir().join("l4-notch-wpn");
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let copy = dir.join("wpndatabase.db");
    std::fs::copy(path, &copy).map_err(|e| format!("copy: {e}"))?;
    for ext in ["-wal", "-shm"] {
        let src = PathBuf::from(format!("{}{ext}", path.display()));
        let dst = PathBuf::from(format!("{}{ext}", copy.display()));
        let _ = std::fs::remove_file(&dst);
        if src.exists() {
            let _ = std::fs::copy(&src, &dst);
        }
    }
    rusqlite::Connection::open_with_flags(&copy, OpenFlags::SQLITE_OPEN_READ_WRITE | OpenFlags::SQLITE_OPEN_NO_MUTEX).map_err(|e| e.to_string())
}

/// The visible lines of a toast: every <text> element in order (attribution last).
fn toast_texts(xml: &str) -> Vec<String> {
    use quick_xml::events::Event;
    let mut reader = quick_xml::Reader::from_str(xml);
    let mut out = vec![];
    let mut attribution = vec![];
    let mut in_text = false;
    let mut is_attr = false;
    let mut cur = String::new();
    loop {
        match reader.read_event() {
            Ok(Event::Start(e)) if e.name().0 == "text" => {
                in_text = true;
                cur.clear();
                is_attr = e.attributes().flatten().any(|a| a.key.0 == "placement" && a.value == "attribution");
            }
            Ok(Event::Text(t)) if in_text => cur.push_str(&t.xml10_content()),
            Ok(Event::GeneralRef(r)) if in_text => {
                // &amp; &lt; … and numeric references inside the text.
                let name = r.into_inner().to_string();
                cur.push_str(match name.as_str() {
                    "amp" => "&",
                    "lt" => "<",
                    "gt" => ">",
                    "quot" => "\"",
                    "apos" => "'",
                    _ => "",
                });
                if let Some(code) = name.strip_prefix('#') {
                    let n = code.strip_prefix('x').map(|h| u32::from_str_radix(h, 16)).unwrap_or_else(|| code.parse());
                    if let Some(c) = n.ok().and_then(char::from_u32) {
                        cur.push(c);
                    }
                }
            }
            Ok(Event::End(e)) if e.name().0 == "text" => {
                in_text = false;
                let s = cur.trim().to_string();
                if !s.is_empty() {
                    if is_attr {
                        attribution.push(s);
                    } else {
                        out.push(s);
                    }
                }
            }
            Ok(Event::Eof) | Err(_) => break,
            _ => {}
        }
    }
    out.extend(attribution);
    out
}

fn app_name(aumid: &str, title: &str, body: &str) -> String {
    let a = aumid.to_lowercase();
    let text = format!("{title} {body}").to_lowercase();
    let name = if a.contains("whatsapp") {
        "WhatsApp"
    } else if a.contains("telegram") {
        "Telegram"
    } else if a.contains("outlook") || a.contains("windowscommunicationsapps") {
        "Outlook"
    } else if (a.contains("chrome") || a.contains("msedge") || a.contains("edge")) && text.contains("mail.google.com") {
        "Gmail"
    } else if a.contains("chrome") {
        "Chrome"
    } else if a.contains("msedge") || a.contains("edge") {
        "Edge"
    } else if a.contains("discord") {
        "Discord"
    } else if a.contains("teams") {
        "Teams"
    } else if a.contains("slack") {
        "Slack"
    } else {
        // "Publisher.AppName_hash!App" or "Vendor.App.exe" → "AppName".
        let base = aumid.split('!').next().unwrap_or(aumid);
        let base = base.split('_').next().unwrap_or(base);
        let part = base.split(['.', '\\']).filter(|p| !p.is_empty() && !p.eq_ignore_ascii_case("exe")).last().unwrap_or(base);
        return part.to_string();
    };
    name.to_string()
}

/// Windows FILETIME (100 ns since 1601) → ms since 1970.
fn filetime_to_ms(ft: i64) -> i64 {
    if ft <= 0 {
        return 0;
    }
    ft / 10_000 - 11_644_473_600_000
}

/// Open the app that sent a notification (by its App User Model ID).
#[tauri::command]
pub fn notifications_open(aumid: String) -> Result<(), String> {
    if aumid.is_empty() || aumid.contains(['"', '\n']) {
        return Err("bad id".into());
    }
    #[cfg(windows)]
    {
        use windows::core::{w, HSTRING};
        use windows::Win32::UI::Shell::ShellExecuteW;
        use windows::Win32::UI::WindowsAndMessaging::SW_SHOWNORMAL;
        let target = HSTRING::from(format!("shell:AppsFolder\\{aumid}"));
        let r = unsafe { ShellExecuteW(None, w!("open"), w!("explorer.exe"), &target, None, SW_SHOWNORMAL) };
        if r.0 as isize > 32 {
            Ok(())
        } else {
            Err(format!("open failed ({})", r.0 as isize))
        }
    }
    #[cfg(not(windows))]
    {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn texts() {
        let xml = r#"<toast><visual><binding template="ToastGeneric"><text>Ahmed</text><text>إزيك يا &amp; عمر &#x1F600;</text><text placement="attribution">via WhatsApp</text></binding></visual></toast>"#;
        assert_eq!(toast_texts(xml), vec!["Ahmed", "إزيك يا & عمر 😀", "via WhatsApp"]);
    }

    #[test]
    fn names() {
        assert_eq!(app_name("5319275A.WhatsAppDesktop_cv1g1gvanyjgm!App", "", ""), "WhatsApp");
        assert_eq!(app_name("Microsoft.Office.OUTLOOK.EXE.15", "", ""), "Outlook");
        assert_eq!(app_name("Chrome", "New mail", "mail.google.com"), "Gmail");
        assert_eq!(app_name("Contoso.Notes_abc!App", "", ""), "Notes");
    }

    #[test]
    fn time() {
        // 2024-01-01T00:00:00Z
        assert_eq!(filetime_to_ms(133_485_408_000_000_000), 1_704_067_200_000);
    }
}
