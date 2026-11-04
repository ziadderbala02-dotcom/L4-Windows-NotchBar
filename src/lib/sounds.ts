import { invoke } from "@tauri-apps/api/core";
import { createStore } from "./store";
import { isNative } from "../platform/native";
import { settings, updateSettings, type SoundKind, type SoundPref } from "../settings/store";
import { playShot, playUrl } from "./sound";

/*
 * Sounds for what pops up in the notch: notifications, headlines, task alarms, goals.
 * Each has its own switch, sound (built-in or a file) and volume; "mute all" silences
 * everything, and while Windows' Do not disturb / focus is on they stay quiet too.
 */

/** Windows focus / Do not disturb, polled (same source as the Overview tile). */
const dnd = createStore<boolean>(false);
async function pollDnd() {
  if (!isNative) return;
  try {
    const f = await invoke<{ active: boolean }>("focus_get");
    if (f.active !== dnd.get()) dnd.set(f.active);
  } catch {
    // Keep the last state.
  }
}
void pollDnd();
window.setInterval(() => void pollDnd(), 3000);
export const useDnd = () => dnd.use();

/** Custom files, loaded once into blob URLs. */
const custom = new Map<string, string>();
async function urlFor(path: string): Promise<string | null> {
  const hit = custom.get(path);
  if (hit) return hit;
  if (!isNative) return null;
  try {
    const b64 = await invoke<string>("sound_read", { path });
    const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
    const url = URL.createObjectURL(new Blob([bytes]));
    custom.set(path, url);
    return url;
  } catch {
    return null;
  }
}

/** Play one preference regardless of switches (settings preview). */
export async function previewSound(p: SoundPref) {
  if (p.sound === "custom" && p.file) {
    const url = await urlFor(p.file);
    if (url) return playUrl(url, p.volume);
  }
  playShot(p.sound === "custom" ? "pop" : p.sound, p.volume);
}

/** The sound for this kind of event, if sounds are on for it right now. */
export function playFor(kind: SoundKind) {
  const s = settings.get().sounds;
  if (!s || s.muteAll || dnd.get()) return;
  const p = s.items[kind];
  if (!p?.on) return;
  void previewSound(p);
}

/** Pick an audio file for a kind (Windows Open dialog). */
export async function pickSoundFile(kind: SoundKind): Promise<boolean> {
  if (!isNative) return false;
  const files = await invoke<string[]>("pick_files").catch(() => [] as string[]);
  const file = files.find((f) => /\.(mp3|wav|ogg|m4a|aac|flac|wma)$/i.test(f));
  if (!file) return false;
  updateSettings((cur) => ({ sounds: { ...cur.sounds, items: { ...cur.sounds.items, [kind]: { ...cur.sounds.items[kind], sound: "custom", file } } } }));
  return true;
}
