import { useEffect, useMemo, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { Alarm, ArrowsClockwise, BellSlash, CalendarBlank, Check, NotePencil, Plus, Star, X } from "@phosphor-icons/react";
import { springs } from "../../design/motion";
import { formatTime } from "../../lib/format";
import { locale, t } from "../../i18n";
import { parseQuick, type Repeat } from "./parse";
import { addTodo, completeTodo, patchTodo, removeTodo, todos, type Todo } from "./store";

/*
 *   [+ Call Ahmed tomorrow 5pm                 ⏰ Thu 5:00 PM]   ← parsed while typing
 *   Today · Upcoming · All · Done
 *   ○ Call Ahmed            ⏰ 5:00 PM  ↻     ★ ✕
 *     note …
 */

type Filter = "today" | "upcoming" | "all" | "done";

const endOfToday = () => {
  const n = new Date();
  return new Date(n.getFullYear(), n.getMonth(), n.getDate() + 1).getTime();
};

export function TodosView() {
  const items = todos.use();
  const [draft, setDraft] = useState("");
  const [filter, setFilter] = useState<Filter>("today");
  const [openId, setOpenId] = useState<string | null>(null);
  const preview = useMemo(() => (draft.trim() ? parseQuick(draft) : null), [draft]);
  useNow(30_000);

  const eod = endOfToday();
  const lists: Record<Filter, Todo[]> = {
    // Today: due today, overdue, or without a date.
    today: items.filter((x) => !x.done && (x.due === null || x.due < eod)),
    upcoming: items.filter((x) => !x.done && x.due !== null && x.due >= eod),
    all: items.filter((x) => !x.done),
    done: items.filter((x) => x.done).sort((a, b) => (b.doneAt ?? 0) - (a.doneAt ?? 0)),
  };
  const sorted =
    filter === "done"
      ? lists.done
      : [...lists[filter]].sort(
          (a, b) => Number(b.starred) - Number(a.starred) || (a.due ?? Infinity) - (b.due ?? Infinity),
        );

  const add = () => {
    if (!preview) return;
    addTodo(preview);
    setDraft("");
    if (preview.due && preview.due >= eod && filter === "today") setFilter("upcoming");
  };

  return (
    <div className="flex h-full flex-col px-4 pt-1 pb-1">
      <form
        className="flex h-9 shrink-0 items-center gap-2 rounded-[12px] bg-fill-1 px-3"
        onSubmit={(e) => {
          e.preventDefault();
          add();
        }}
      >
        <Plus size={14} weight="bold" className="text-label-3" />
        <input
          value={draft}
          dir="auto"
          onChange={(e) => setDraft(e.target.value)}
          placeholder={t("Neue Aufgabe, z. B. „Ahmed anrufen morgen 17 Uhr“")}
          className="min-w-0 flex-1 bg-transparent text-body text-label outline-none placeholder:text-label-4"
        />
        <AnimatePresence>
          {preview?.due && (
            <motion.span
              className="flex shrink-0 items-center gap-1 rounded-full bg-fill-2 px-2 py-0.5 text-caption font-medium text-yellow"
              initial={{ opacity: 0, scale: 0.9 }}
              animate={{ opacity: 1, scale: 1 }}
              exit={{ opacity: 0, scale: 0.9 }}
            >
              <Alarm size={11} weight="fill" />
              {dueLabel(preview.due)}
              {preview.repeat !== "none" && <ArrowsClockwise size={10} weight="bold" />}
            </motion.span>
          )}
        </AnimatePresence>
      </form>

      <div className="mt-1.5 flex shrink-0 items-center gap-1">
        {(["today", "upcoming", "all", "done"] as const).map((k) => (
          <button
            key={k}
            onClick={() => setFilter(k)}
            className={`flex h-6 items-center gap-1 rounded-full px-2.5 text-caption font-medium ${filter === k ? "bg-fill-3 text-label" : "text-label-3 hover:text-label-2"}`}
          >
            {t(FILTER_LABEL[k])}
            {k !== "done" && lists[k].length > 0 && <span className="tabular text-label-3">{lists[k].length}</span>}
          </button>
        ))}
      </div>

      <div className="-mx-1 mt-1 flex-1 overflow-y-auto px-1 [scrollbar-width:none]">
        {sorted.length === 0 && (
          <div className="flex h-full items-center justify-center px-6 text-center text-body text-label-3">
            {filter === "done" ? t("Noch nichts erledigt") : filter === "upcoming" ? t("Nichts geplant") : t("Alles erledigt")}
          </div>
        )}
        <AnimatePresence initial={false}>
          {sorted.map((todo) => (
            <TodoRow key={todo.id} todo={todo} open={openId === todo.id} onToggleOpen={() => setOpenId(openId === todo.id ? null : todo.id)} />
          ))}
        </AnimatePresence>
      </div>
    </div>
  );
}

const FILTER_LABEL: Record<Filter, string> = { today: "Heute", upcoming: "Geplant", all: "Alle", done: "Erledigt" };
const REPEAT_LABEL: Record<Repeat, string> = { none: "Einmal", daily: "Täglich", weekdays: "Werktags", weekly: "Wöchentlich" };

function useNow(ms: number) {
  const [, set] = useState(0);
  useEffect(() => {
    const id = window.setInterval(() => set((n) => n + 1), ms);
    return () => window.clearInterval(id);
  }, [ms]);
}

/** "5:00 PM", "Tomorrow 5:00 PM", "Thu 6 Nov, 5:00 PM". */
export function dueLabel(due: number): string {
  const d = new Date(due);
  const today = new Date();
  const days = Math.round((new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime() - new Date(today.getFullYear(), today.getMonth(), today.getDate()).getTime()) / 86_400_000);
  const time = formatTime(due);
  if (days === 0) return time;
  if (days === 1) return `${t("Morgen")} ${time}`;
  if (days === -1) return `${t("Gestern")} ${time}`;
  return `${new Intl.DateTimeFormat(locale(), { weekday: "short", day: "numeric", month: "short" }).format(d)}, ${time}`;
}

function TodoRow({ todo, open, onToggleOpen }: { todo: Todo; open: boolean; onToggleOpen: () => void }) {
  const overdue = !todo.done && todo.due !== null && todo.due < Date.now();
  return (
    <motion.div
      layout="position"
      transition={springs.snappy}
      initial={{ opacity: 0, height: 0 }}
      animate={{ opacity: 1, height: "auto" }}
      exit={{ opacity: 0, height: 0, transition: { duration: 0.15 } }}
      className={`group overflow-hidden rounded-[10px] ${open ? "bg-fill-1" : "hover:bg-fill-1"}`}
    >
      <div className="flex min-h-8 items-center gap-2.5 px-2">
        <button
          aria-label={todo.done ? t("Als offen markieren") : t("Erledigt")}
          onClick={() => completeTodo(todo.id)}
          className={`pressable flex size-[18px] shrink-0 items-center justify-center rounded-full border-[1.5px] ${todo.done ? "border-green bg-green text-black" : overdue ? "border-red" : "border-label-3"}`}
        >
          {todo.done && <Check size={11} weight="bold" />}
        </button>
        <button className="min-w-0 flex-1 py-1 text-left" onClick={onToggleOpen}>
          <span className={`block truncate text-body ${todo.done ? "text-label-3 line-through" : "text-label"}`} dir="auto">
            {todo.text}
          </span>
          {(todo.due || todo.note) && !open && (
            <span className="flex items-center gap-1.5 text-caption">
              {todo.due && (
                <span className={`flex items-center gap-0.5 ${overdue ? "font-semibold text-red" : "text-label-3"}`}>
                  {todo.remind ? <Alarm size={10} weight="fill" /> : <CalendarBlank size={10} weight="bold" />}
                  {dueLabel(todo.due)}
                </span>
              )}
              {todo.repeat !== "none" && (
                <span className="flex items-center gap-0.5 text-label-3">
                  <ArrowsClockwise size={10} weight="bold" />
                  {t(REPEAT_LABEL[todo.repeat])}
                </span>
              )}
              {todo.note && <NotePencil size={10} weight="bold" className="text-label-3" />}
            </span>
          )}
        </button>
        <button
          aria-label={t("Markieren")}
          onClick={() => patchTodo(todo.id, { starred: !todo.starred })}
          className={`pressable flex size-6 items-center justify-center ${todo.starred ? "text-yellow" : "text-label-4 opacity-0 group-hover:opacity-100"}`}
        >
          <Star size={14} weight={todo.starred ? "fill" : "bold"} />
        </button>
        <button
          aria-label={t("Löschen")}
          onClick={() => removeTodo(todo.id)}
          className="pressable flex size-6 items-center justify-center text-label-4 opacity-0 group-hover:opacity-100 hover:text-label-2"
        >
          <X size={13} weight="bold" />
        </button>
      </div>
      {open && <TodoEditor todo={todo} />}
    </motion.div>
  );
}

/** Opened row: time, reminder on/off, repeat, note. */
function TodoEditor({ todo }: { todo: Todo }) {
  const local = todo.due ? toLocalInput(todo.due) : "";
  const quick: [string, () => number][] = [
    ["+1 Std.", () => Date.now() + 3600_000],
    ["Heute 18:00", () => atHour(0, 18)],
    ["Morgen 9:00", () => atHour(1, 9)],
  ];
  return (
    <div className="flex flex-col gap-1.5 px-2 pt-0.5 pb-2 pl-[38px]">
      <div className="flex flex-wrap items-center gap-1.5">
        <input
          type="datetime-local"
          value={local}
          onChange={(e) => patchTodo(todo.id, { due: e.target.value ? new Date(e.target.value).getTime() : null, firedFor: null, remind: e.target.value ? true : todo.remind })}
          className="h-7 rounded-[8px] bg-fill-2 px-2 text-caption text-label [color-scheme:dark] outline-none"
        />
        {quick.map(([label, at]) => (
          <button key={label} onClick={() => patchTodo(todo.id, { due: at(), firedFor: null, remind: true })} className="h-7 rounded-full bg-fill-2 px-2.5 text-caption text-label-2 hover:text-label">
            {t(label)}
          </button>
        ))}
        {todo.due && (
          <button onClick={() => patchTodo(todo.id, { due: null, repeat: "none" })} className="h-7 rounded-full px-2 text-caption text-label-3 hover:text-red">
            {t("Ohne Termin")}
          </button>
        )}
      </div>
      {todo.due && (
        <div className="flex flex-wrap items-center gap-1.5">
          <button
            onClick={() => patchTodo(todo.id, { remind: !todo.remind, firedFor: null })}
            className={`flex h-7 items-center gap-1 rounded-full px-2.5 text-caption font-medium ${todo.remind ? "bg-yellow text-black" : "bg-fill-2 text-label-3"}`}
          >
            {todo.remind ? <Alarm size={11} weight="fill" /> : <BellSlash size={11} weight="bold" />}
            {todo.remind ? t("Erinnern") : t("Keine Erinnerung")}
          </button>
          {(["none", "daily", "weekdays", "weekly"] as const).map((r) => (
            <button
              key={r}
              onClick={() => patchTodo(todo.id, { repeat: r })}
              className={`h-7 rounded-full px-2.5 text-caption ${todo.repeat === r ? "bg-fill-3 font-medium text-label" : "bg-fill-1 text-label-3 hover:text-label-2"}`}
            >
              {t(REPEAT_LABEL[r])}
            </button>
          ))}
        </div>
      )}
      <textarea
        value={todo.note}
        dir="auto"
        rows={2}
        onChange={(e) => patchTodo(todo.id, { note: e.target.value })}
        placeholder={t("Notiz …")}
        className="resize-none rounded-[8px] bg-fill-2 px-2 py-1.5 text-caption text-label outline-none placeholder:text-label-4"
      />
    </div>
  );
}

function atHour(daysAhead: number, h: number): number {
  const n = new Date();
  const d = new Date(n.getFullYear(), n.getMonth(), n.getDate() + daysAhead, h, 0);
  if (d.getTime() <= Date.now()) d.setDate(d.getDate() + 1);
  return d.getTime();
}

function toLocalInput(ms: number): string {
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}
