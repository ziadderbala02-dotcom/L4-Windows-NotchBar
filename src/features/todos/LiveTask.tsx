import { useEffect, useState } from "react";
import { motion } from "motion/react";
import { Alarm, Check, CheckSquare } from "@phosphor-icons/react";
import { content } from "../../design/motion";
import { LiveEdge } from "../../notch/liveEdges";
import { t } from "../../i18n";
import { alarm, completeTodo, snoozeTodo, todos, upcoming, type Todo } from "./store";

function useTick(ms: number) {
  const [, set] = useState(0);
  useEffect(() => {
    const id = window.setInterval(() => set((n) => n + 1), ms);
    return () => window.clearInterval(id);
  }, [ms]);
}

/** A reminder due within 15 minutes (closed notch shows it beforehand). */
export function useSoonTask(): Todo | null {
  const list = todos.use();
  useTick(20_000);
  return upcoming(list);
}

export function useTaskAlarm(): Todo | null {
  return alarm.use();
}

/** Closed notch, task coming up: icon left, "in 12 min" right. */
export function LiveTaskSoon({ task }: { task: Todo }) {
  useTick(15_000);
  const mins = Math.max(1, Math.ceil(((task.due ?? 0) - Date.now()) / 60_000));
  return (
    <motion.div
      className="absolute inset-0 flex items-center justify-between px-[11px]"
      initial={{ opacity: 0, filter: "blur(4px)" }}
      animate={{ opacity: 1, filter: "blur(0px)", transition: content.enter }}
      exit={{ opacity: 0, filter: "blur(4px)", transition: content.exit }}
      title={task.text}
    >
      <LiveEdge side="left">
        <CheckSquare size={16} weight="fill" className="text-yellow" />
      </LiveEdge>
      <LiveEdge side="right">
        <span className="tabular text-caption font-semibold text-yellow">{t("in {m} Min.", { m: mins })}</span>
      </LiveEdge>
    </motion.div>
  );
}

/**
 * Closed notch, task due now (geometry.taskAlarm):
 *
 *   ⏰  Call Ahmed                     [✓ Done] [+10m] [+1h]
 */
export function LiveTaskAlarm({ task }: { task: Todo }) {
  const stop = (e: React.MouseEvent) => e.stopPropagation();
  return (
    <motion.div
      className="absolute inset-x-0 top-0 flex items-center gap-3 px-4"
      style={{ height: 54 }}
      initial={{ opacity: 0, filter: "blur(4px)" }}
      animate={{ opacity: 1, filter: "blur(0px)", transition: { ...content.enter, delay: 0.15 } }}
      exit={{ opacity: 0, filter: "blur(4px)", transition: content.exit }}
    >
      <motion.span
        className="flex text-yellow"
        animate={{ rotate: [0, -14, 12, -10, 8, 0] }}
        transition={{ duration: 0.7, repeat: Infinity, repeatDelay: 1.4 }}
      >
        <Alarm size={22} weight="fill" />
      </motion.span>
      <div className="min-w-0 flex-1">
        <div className="truncate text-footnote font-semibold text-label" dir="auto">
          {task.text}
        </div>
        {task.note && (
          <div className="truncate text-caption text-label-2" dir="auto">
            {task.note}
          </div>
        )}
      </div>
      <div className="flex shrink-0 gap-1" onPointerDown={stop} onClick={stop}>
        <button className="pressable flex h-7 items-center gap-1 rounded-full bg-green px-2.5 text-caption font-semibold text-black" onClick={() => completeTodo(task.id)}>
          <Check size={11} weight="bold" />
          {t("Erledigt")}
        </button>
        <button className="pressable h-7 rounded-full bg-fill-3 px-2.5 text-caption font-medium text-label" onClick={() => snoozeTodo(task.id, 10)}>
          +10m
        </button>
        <button className="pressable h-7 rounded-full bg-fill-3 px-2.5 text-caption font-medium text-label" onClick={() => snoozeTodo(task.id, 60)}>
          +1h
        </button>
      </div>
    </motion.div>
  );
}
