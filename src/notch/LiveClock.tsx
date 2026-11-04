import { useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { motion } from "motion/react";
import {
  BatteryCharging,
  BatteryEmpty,
  BatteryFull,
  BatteryHigh,
  BatteryLow,
  BatteryMedium,
  CalendarBlank,
} from "@phosphor-icons/react";
import { content } from "../design/motion";
import { formatTimeParts } from "../lib/format";
import { locale } from "../i18n";
import { isNative } from "../platform/native";
import { settings } from "../settings/store";
import { LiveEdge } from "./liveEdges";

/*
 * Idle closed notch: the time on the left, battery on the right (or the date on PCs
 * without a battery). Lowest priority: any live activity (match, music, timer) wins.
 */

interface Battery {
  percent: number | null;
  charging: boolean;
  plugged: boolean;
}

async function readBattery(): Promise<Battery | null> {
  if (isNative) return invoke<Battery | null>("battery_get");
  // Browser preview: the Battery Status API where available, else a sample.
  const nav = navigator as Navigator & { getBattery?: () => Promise<{ level: number; charging: boolean }> };
  if (nav.getBattery) {
    const b = await nav.getBattery();
    return { percent: Math.round(b.level * 100), charging: b.charging, plugged: b.charging };
  }
  return { percent: 76, charging: false, plugged: false };
}

function useBattery(): Battery | null | undefined {
  const [b, setB] = useState<Battery | null | undefined>(undefined);
  useEffect(() => {
    let alive = true;
    const load = () => void readBattery().then((v) => alive && setB(v)).catch(() => alive && setB(null));
    load();
    const id = window.setInterval(load, 30_000);
    return () => {
      alive = false;
      window.clearInterval(id);
    };
  }, []);
  return b;
}

/** Re-render on the minute boundary, not every second. */
function useMinute(): number {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    let id = 0;
    const arm = () => {
      id = window.setTimeout(() => {
        setNow(Date.now());
        arm();
      }, 60_000 - (Date.now() % 60_000) + 50);
    };
    arm();
    return () => window.clearTimeout(id);
  }, []);
  return now;
}

export function useClockLive(): boolean {
  return settings.use().idleClock !== false;
}

export function LiveClock() {
  const now = useMinute();
  const battery = useBattery();
  settings.use(); // 12/24 h switch re-renders
  const { time, period } = formatTimeParts(now);

  return (
    <motion.div
      className="absolute inset-0 flex items-center justify-between px-[12px]"
      initial={{ opacity: 0, filter: "blur(4px)" }}
      animate={{ opacity: 1, filter: "blur(0px)", transition: content.enter }}
      exit={{ opacity: 0, filter: "blur(4px)", transition: content.exit }}
    >
      <LiveEdge side="left" className="flex items-baseline gap-[3px]">
        <span className="tabular text-footnote font-semibold text-label">{time}</span>
        {period && <span className="text-[9px] font-semibold text-label-2">{period}</span>}
      </LiveEdge>
      <LiveEdge side="right" className="flex items-center gap-1">
        {battery === undefined ? null : battery ? <BatteryReadout b={battery} /> : <DateReadout now={now} />}
      </LiveEdge>
    </motion.div>
  );
}

function BatteryReadout({ b }: { b: Battery }) {
  const p = b.percent ?? 0;
  const Icon = b.charging ? BatteryCharging : p >= 90 ? BatteryFull : p >= 60 ? BatteryHigh : p >= 35 ? BatteryMedium : p >= 12 ? BatteryLow : BatteryEmpty;
  const color = b.charging ? "var(--color-green)" : p < 20 ? "var(--color-red)" : "var(--color-label)";
  return (
    <>
      {b.percent !== null && <span className="tabular text-caption font-semibold text-label-2">{b.percent}%</span>}
      <Icon size={18} weight={b.charging || p < 20 ? "fill" : "regular"} style={{ color }} />
    </>
  );
}

function DateReadout({ now }: { now: number }) {
  return (
    <>
      <CalendarBlank size={12} weight="bold" className="text-label-3" />
      <span className="text-caption font-semibold text-label-2">
        {new Intl.DateTimeFormat(locale(), { weekday: "short", day: "numeric" }).format(now)}
      </span>
    </>
  );
}
