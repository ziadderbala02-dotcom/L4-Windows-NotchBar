import { createStore } from "../../lib/store";
import { playFor } from "../../lib/sounds";
import { nextDue, type Repeat } from "./parse";

/*
 * To-dos with due times, reminders and repetition.
 *
 *   due + remind  → at that time the closed notch shows the task with Done / +10 min /
 *                   +1 h and plays the task sound (Settings → Sounds)
 *   due < 15 min  → the closed notch shows "in 12 min" beforehand
 *   repeat        → "done" moves it to the next day / week / workday instead
 */

export interface Todo {
  id: string;
  text: string;
  done: boolean;
  starred: boolean;
  due: number | null;
  remind: boolean;
  repeat: Repeat;
  note: string;
  /** The `due` a reminder already fired for (so it fires once per time). */
  firedFor?: number | null;
  doneAt?: number | null;
}

export const todos = createStore<Todo[]>([], { persist: "todos" });
// Older lists only had text / done / starred.
todos.set((l) =>
  l.map((t: Partial<Todo> & Pick<Todo, "id" | "text" | "done" | "starred">) => ({
    ...t,
    due: t.due ?? null,
    remind: t.remind ?? true,
    repeat: t.repeat ?? "none",
    note: t.note ?? "",
  })),
);

export function addTodo(p: { text: string; due: number | null; repeat: Repeat }) {
  todos.set((l) => [{ id: crypto.randomUUID(), text: p.text, done: false, starred: false, due: p.due, remind: p.due !== null, repeat: p.repeat, note: "" }, ...l]);
}

export function patchTodo(id: string, p: Partial<Todo>) {
  todos.set((l) => l.map((t) => (t.id === id ? { ...t, ...p } : t)));
}

export function removeTodo(id: string) {
  todos.set((l) => l.filter((t) => t.id !== id));
  if (alarm.get()?.id === id) alarm.set(null);
}

/** Done: a repeating task jumps to its next time, any other one is ticked off. */
export function completeTodo(id: string) {
  const t = todos.get().find((x) => x.id === id);
  if (!t) return;
  if (!t.done && t.repeat !== "none" && t.due) patchTodo(id, { due: nextDue(t.due, t.repeat), firedFor: null });
  else patchTodo(id, { done: !t.done, doneAt: t.done ? null : Date.now() });
  if (alarm.get()?.id === id) alarm.set(null);
}

export function snoozeTodo(id: string, minutes: number) {
  patchTodo(id, { due: Date.now() + minutes * 60_000, firedFor: null });
  if (alarm.get()?.id === id) alarm.set(null);
}

/* ---------- reminders ---------- */

/** The task ringing in the closed notch right now. */
export const alarm = createStore<Todo | null>(null);
/** Shown at most this long; it stays overdue (red) in the list afterwards. */
const ALARM_MS = 5 * 60_000;
export const SOON_MS = 15 * 60_000;

function check() {
  const now = Date.now();
  if (alarm.get()) return;
  const due = todos
    .get()
    .filter((t) => !t.done && t.remind && t.due !== null && t.due <= now && t.firedFor !== t.due && now - t.due < 6 * 3600_000)
    .sort((a, b) => a.due! - b.due!)[0];
  if (!due) return;
  patchTodo(due.id, { firedFor: due.due });
  alarm.set({ ...due });
  playFor("task");
  window.setTimeout(() => alarm.get()?.id === due.id && alarm.set(null), ALARM_MS);
}

window.setInterval(check, 5000);
window.setTimeout(check, 2000);

/** Next reminder within 15 min (closed notch shows it beforehand). */
export function upcoming(list: Todo[], now = Date.now()): Todo | null {
  return (
    list
      .filter((t) => !t.done && t.remind && t.due !== null && t.due > now && t.due - now <= SOON_MS)
      .sort((a, b) => a.due! - b.due!)[0] ?? null
  );
}

/** Open tasks due today or overdue (dock badge). */
export function dueToday(list: Todo[], now = new Date()): number {
  const end = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1).getTime();
  return list.filter((t) => !t.done && t.due !== null && t.due < end).length;
}
