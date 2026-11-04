import { createStore } from "../lib/store";
import { settings, updateSettings } from "../settings/store";
import { onHotkey, setHotkeys } from "../platform/native";
import { applyDisplay } from "./drag";
import { hiddenMode } from "./focus";

/*
 * The user's own shortcuts (Settings → General):
 *   center  puts the notch back in the middle of the top edge
 *   hide    hides it completely / brings it back (sounds keep playing while hidden)
 */

/** Actions whose shortcut couldn't be registered (another app has it). */
export const hotkeyErrors = createStore<string[]>([]);

let last = "";
function register() {
  const h = settings.get().hotkeys;
  const key = `${h.center}|${h.hide}`;
  if (key === last) return;
  last = key;
  void setHotkeys([
    ["center", h.center],
    ["hide", h.hide],
  ]).then((failed) => hotkeyErrors.set(failed));
}

export function startHotkeys() {
  register();
  settings.subscribe(register);
  onHotkey((id) => {
    if (id === "center") {
      updateSettings((cur) => ({ display: { ...cur.display, offset: 0 } }));
      applyDisplay(0);
      hiddenMode.set(false);
    } else if (id === "hide") hiddenMode.set(!hiddenMode.get());
  });
}

/** "Ctrl+Alt+H" from a key event (null while only modifiers are held). */
export function comboFrom(e: KeyboardEvent): string | null {
  const k = e.code;
  let key: string | null = null;
  if (/^Key[A-Z]$/.test(k)) key = k.slice(3);
  else if (/^Digit\d$/.test(k)) key = k.slice(5);
  else if (/^F\d{1,2}$/.test(k)) key = k;
  else if (k === "Space") key = "Space";
  if (!key) return null;
  const mods = [e.ctrlKey && "Ctrl", e.altKey && "Alt", e.shiftKey && "Shift", e.metaKey && "Super"].filter(Boolean);
  // A plain letter would fire while typing anywhere: require Ctrl, Alt or Win.
  if (!e.ctrlKey && !e.altKey && !e.metaKey) return null;
  return [...mods, key].join("+");
}
