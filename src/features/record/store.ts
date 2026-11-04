import { invoke } from "@tauri-apps/api/core";
import { createStore } from "../../lib/store";
import { isNative } from "../../platform/native";

/*
 * Screen recording (Rust: record.rs, ffmpeg). Start from the record button in the open
 * notch; while it runs the closed notch shows a red dot and the time, with a stop button.
 * Saved to Videos\Notch Recordings, then a short "Saved" notice opens the folder.
 */

export interface RecordState {
  recording: boolean;
  started: number | null;
  /** null = not checked yet. */
  ffmpeg: boolean | null;
  error: string | null;
}

export const rec = createStore<RecordState>({ recording: false, started: null, ffmpeg: null, error: null });
/** The file just saved, shown for a few seconds in the closed notch. */
export const saved = createStore<{ path: string; at: number } | null>(null);
const SAVED_MS = 6000;

export async function refreshRecord() {
  if (!isNative) return rec.set((s) => ({ ...s, ffmpeg: true }));
  try {
    const r = await invoke<{ recording: boolean; started: number | null; ffmpeg: boolean }>("record_status");
    rec.set((s) => ({ ...s, recording: r.recording, started: r.started, ffmpeg: r.ffmpeg }));
  } catch {
    // Keep what we have.
  }
}

export async function startRecording() {
  if (!isNative) return rec.set({ recording: true, started: Date.now(), ffmpeg: true, error: null });
  try {
    const started = await invoke<number>("record_start");
    rec.set((s) => ({ ...s, recording: true, started, error: null }));
  } catch (e) {
    rec.set((s) => ({ ...s, error: String(e), ffmpeg: String(e).includes("ffmpeg missing") ? false : s.ffmpeg }));
  }
}

export async function stopRecording() {
  if (!isNative) {
    rec.set((s) => ({ ...s, recording: false, started: null }));
    return showSaved("C:\\Users\\Demo\\Videos\\Notch Recordings\\Recording.mp4");
  }
  try {
    const path = await invoke<string>("record_stop");
    rec.set((s) => ({ ...s, recording: false, started: null }));
    showSaved(path);
  } catch (e) {
    rec.set((s) => ({ ...s, recording: false, started: null, error: String(e) }));
  }
}

function showSaved(path: string) {
  const v = { path, at: Date.now() };
  saved.set(v);
  window.setTimeout(() => saved.get() === v && saved.set(null), SAVED_MS);
}

export function revealRecording(path: string) {
  if (isNative) void invoke("reveal_file", { path });
}

export function installFfmpeg() {
  if (isNative) void invoke("ffmpeg_install");
  // Check again a few times while winget runs.
  let n = 0;
  const id = window.setInterval(() => {
    void refreshRecord();
    if (++n > 40 || rec.get().ffmpeg) window.clearInterval(id);
  }, 6000);
}

void refreshRecord();
// ffmpeg can stop on its own (disk full, display change): pick that up.
window.setInterval(() => rec.get().recording && void refreshRecord(), 5000);
