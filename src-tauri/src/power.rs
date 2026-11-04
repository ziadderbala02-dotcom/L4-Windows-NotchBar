//! Battery level and charging state for the clock shown in the idle notch.

#[derive(serde::Serialize)]
pub struct Battery {
    /// 0–100, or null when unknown.
    percent: Option<u8>,
    charging: bool,
    /// On mains power (also true on desktops without a battery).
    plugged: bool,
}

/// None on machines without a battery (desktop PCs).
#[tauri::command]
pub fn battery_get() -> Option<Battery> {
    #[cfg(windows)]
    {
        use windows::Win32::System::Power::{GetSystemPowerStatus, SYSTEM_POWER_STATUS};
        let mut s = SYSTEM_POWER_STATUS::default();
        unsafe { GetSystemPowerStatus(&mut s) }.ok()?;
        // 128 = no system battery, 255 = unknown status.
        if s.BatteryFlag == 128 || s.BatteryFlag == 255 {
            return None;
        }
        Some(Battery {
            percent: (s.BatteryLifePercent <= 100).then_some(s.BatteryLifePercent),
            charging: s.BatteryFlag & 8 != 0,
            plugged: s.ACLineStatus == 1,
        })
    }
    #[cfg(not(windows))]
    {
        None
    }
}
