//! Football data for the live scoreboard (src/features/football).
//!
//! The WebView could fetch these itself, but not every provider sends CORS headers,
//! so requests go through here. Only a fixed list of hosts is allowed: this is not a
//! general proxy for the frontend.

use std::sync::OnceLock;
use std::time::Duration;

const ALLOWED_HOSTS: &[&str] = &[
    "site.api.espn.com",
    "site.web.api.espn.com",
    "sports.core.api.espn.com",
    "api.sofascore.com",
    "www.sofascore.com",
    "news.google.com",
    "webws.365scores.com",
];

fn client() -> Result<&'static reqwest::Client, String> {
    static CLIENT: OnceLock<reqwest::Client> = OnceLock::new();
    if let Some(c) = CLIENT.get() {
        return Ok(c);
    }
    let c = reqwest::Client::builder()
        .timeout(Duration::from_secs(10))
        // Some providers turn away requests that don't look like a browser.
        .user_agent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36 Edg/130.0")
        .build()
        .map_err(|e| e.to_string())?;
    Ok(CLIENT.get_or_init(|| c))
}

fn host_of(url: &str) -> Option<&str> {
    let rest = url.strip_prefix("https://")?;
    let end = rest.find(['/', '?', '#', ':']).unwrap_or(rest.len());
    Some(&rest[..end])
}

/// GET a provider URL and return the body as text (JSON or RSS).
#[tauri::command]
pub async fn football_get(url: String) -> Result<String, String> {
    let host = host_of(&url).ok_or_else(|| "only https".to_string())?;
    if !ALLOWED_HOSTS.contains(&host) {
        return Err(format!("host not allowed: {host}"));
    }
    let res = client()?
        .get(&url)
        .header("Accept", "application/json, application/rss+xml, text/xml;q=0.9, */*;q=0.8")
        .header("Accept-Language", "en-US,en;q=0.9,ar;q=0.8")
        .send()
        .await
        .map_err(|e| format!("network:{e}"))?;
    let status = res.status();
    if !status.is_success() {
        return Err(format!("http:{}", status.as_u16()));
    }
    res.text().await.map_err(|e| e.to_string())
}

/// Open a news article or match page in the default browser (https only).
///
/// Through ShellExecute with the URL percent-encoded: handing `explorer` an Arabic
/// URL made it open the Documents folder instead of the browser.
#[tauri::command]
pub fn football_open(url: String) -> Result<(), String> {
    if !url.starts_with("https://") {
        return Err("only https".into());
    }
    let url = encode_url(&url);
    #[cfg(windows)]
    {
        use windows::core::{w, HSTRING};
        use windows::Win32::UI::Shell::ShellExecuteW;
        use windows::Win32::UI::WindowsAndMessaging::SW_SHOWNORMAL;
        let target = HSTRING::from(url.as_str());
        let r = unsafe { ShellExecuteW(None, w!("open"), &target, None, None, SW_SHOWNORMAL) };
        // Values above 32 mean success.
        if r.0 as isize > 32 {
            Ok(())
        } else {
            Err(format!("open failed ({})", r.0 as isize))
        }
    }
    #[cfg(not(windows))]
    {
        let _ = url;
        Ok(())
    }
}

/// Percent-encode everything outside printable ASCII (Arabic slugs, spaces), keep the rest.
fn encode_url(url: &str) -> String {
    let mut out = String::with_capacity(url.len());
    for b in url.bytes() {
        if b > 0x20 && b < 0x7f && b != b'"' {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::{encode_url, host_of};

    #[test]
    fn hosts() {
        assert_eq!(host_of("https://site.api.espn.com/apis/x"), Some("site.api.espn.com"));
        assert_eq!(host_of("https://api.sofascore.com?x=1"), Some("api.sofascore.com"));
        assert_eq!(host_of("http://site.api.espn.com/"), None);
        assert_eq!(encode_url("https://a.com/خبر x"), "https://a.com/%D8%AE%D8%A8%D8%B1%20x");
        assert_eq!(host_of("https://evil.com@site.api.espn.com/"), Some("evil.com@site.api.espn.com"));
    }
}
