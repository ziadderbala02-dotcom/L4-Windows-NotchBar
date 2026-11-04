import { invoke } from "@tauri-apps/api/core";
import { createStore } from "../../lib/store";
import { isNative } from "../../platform/native";
import { settings } from "../../settings/store";
import { playFor } from "../../lib/sounds";

/*
 * Windows notifications (WhatsApp, Outlook, Gmail in the browser, …) in the notch.
 * Rust reads them from Windows' own notification database (notifications.rs); here they
 * are polled, shown one at a time in the closed notch, and kept in a history so what
 * popped up while you were away can be read later in the Notifications tool.
 */

export interface Notif {
  id: number;
  aumid: string;
  app: string;
  title: string;
  body: string;
  at: number;
}

/** Everything that came in, newest first (kept across restarts). */
export const history = createStore<Notif[]>([], { persist: "notifications-history" });
/** IDs the user hasn't looked at in the tool yet. */
export const unread = createStore<number>(0, { persist: "notifications-unread" });
/** The notification shown in the closed notch right now. */
export const notifNotice = createStore<Notif | null>(null);
/** Display time, from the settings (3–15 s). */
const notifMs = () => Math.min(15, Math.max(3, settings.get().notifSeconds ?? 5)) * 1000;
const HISTORY_MAX = 150;
const POLL_MS = 2000;

/** Our own toasts and Windows' system chatter don't belong in the notch. */
const IGNORE = [/notch/i, /Windows\.SystemToast/i, /Microsoft\.Windows\.Explorer/i, /Windows\.Defender/i];

function enabled(): boolean {
  const s = settings.get();
  return s.notifications !== false && s.tools.some((t) => t.id === "notifications" && t.enabled);
}

let last = -1;
let error: string | null = null;
export const status = createStore<{ ok: boolean; error: string | null }>({ ok: true, error: null });

async function poll() {
  if (!isNative || !enabled()) return;
  try {
    const r = await invoke<{ items: Notif[]; last: number; error: string | null }>("notifications_poll", { after: last });
    if (r.error !== error) {
      error = r.error;
      status.set({ ok: !r.error, error: r.error });
    }
    const fresh = last >= 0 ? r.items.filter((n) => !IGNORE.some((re) => re.test(n.aumid))) : [];
    last = r.last;
    if (!fresh.length) return;
    history.set((h) => [...[...fresh].reverse(), ...h].slice(0, HISTORY_MAX));
    unread.set((u) => u + fresh.length);
    // A burst (a group chat waking up): the newest three, the rest are in the history.
    queue.push(...fresh.slice(-3));
    showNext();
  } catch (e) {
    status.set({ ok: false, error: String(e) });
  }
}

const queue: Notif[] = [];
function showNext() {
  if (notifNotice.get() || !queue.length) return;
  const n = queue.shift()!;
  notifNotice.set(n);
  playFor("notif");
  window.setTimeout(() => {
    if (notifNotice.get() === n) notifNotice.set(null);
    window.setTimeout(showNext, 400);
  }, notifMs());
}

/** Bring the sending app to the front (WhatsApp on its chat list, Outlook, …). */
export function openApp(n: Notif) {
  if (isNative) void invoke("notifications_open", { aumid: n.aumid }).catch(() => {});
}

export function clearHistory() {
  history.set([]);
  unread.set(0);
  queue.length = 0;
  notifNotice.set(null);
}

export function markRead() {
  if (unread.get()) unread.set(0);
}

window.setInterval(() => void poll(), POLL_MS);
void poll();

// Browser preview: `?notif` sends a sample WhatsApp message every 12 s.
if (!isNative && new URLSearchParams(location.search).has("notif")) {
  let i = 0;
  const samples = [
    { app: "WhatsApp", title: "أحمد", body: "يا عمر الماتش بكرة الساعة كام؟ وهنتفرج فين؟" },
    { app: "Outlook", title: "Mona Ali", body: "Proposal review – can we move the call to 3 PM tomorrow?" },
  ];
  const push = () => {
    const s = samples[i++ % samples.length];
    const n: Notif = { id: Date.now(), aumid: s.app, at: Date.now(), ...s };
    history.set((h) => [n, ...h].slice(0, HISTORY_MAX));
    unread.set((u) => u + 1);
    queue.push(n);
    showNext();
  };
  window.setTimeout(push, 1200);
  window.setInterval(push, 12000);
}
