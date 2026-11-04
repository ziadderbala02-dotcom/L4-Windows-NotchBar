import { useContext, useRef } from "react";
import { AnimatePresence, useTransform } from "motion/react";
import { useNowPlaying } from "../platform/media";
import { LiveActivity } from "../features/now-playing/LiveActivity";
import { LiveTimer, useTimerLive } from "../features/timer/LiveTimer";
import { LiveDiscord } from "../features/discord/LiveDiscord";
import { useDiscordCall } from "../features/discord/store";
import { LiveGaming } from "../features/system/LiveGaming";
import { useGamingLive } from "../features/system/gaming";
import { FootballNotice, LiveScore, NewsNoticeView } from "../features/football/LiveScore";
import { newsNotice } from "../features/football/news";
import { notifNotice } from "../features/notifications/store";
import { LiveRecord, SavedNotice, useRecordLive } from "../features/record/LiveRecord";
import { saved } from "../features/record/store";
import { NotifNotice } from "../features/notifications/NotificationsView";
import { goal, notice, useFeaturedMatch } from "../features/football/store";
import { LiveClock, useClockLive } from "./LiveClock";
import { LiveTaskAlarm, LiveTaskSoon, useSoonTask, useTaskAlarm } from "../features/todos/LiveTask";
import { geometry } from "./geometry";
import { LiveSpread, ShapeWidth } from "./liveEdges";

/**
 * Which live activity the closed notch shows. Exactly one, by priority:
 * gaming mode (turned on deliberately) before time-critical (timer, call) before
 * background (music). Add new activities here. The ID is also the tool that opens.
 */
export function useLiveActivity(): { id: string | null } {
  const gaming = useGamingLive();
  const timerLive = useTimerLive();
  const call = useDiscordCall();
  const np = useNowPlaying();
  const match = useFeaturedMatch();
  const g = goal.use();
  const clock = useClockLive();
  const note = notice.use();
  const headline = newsNotice.use();
  const message = notifNotice.use();
  const recording = useRecordLive();
  const justSaved = saved.use();
  const taskAlarm = useTaskAlarm();
  const soonTask = useSoonTask();
  if (gaming) return { id: "system" };
  // Recording: always visible, so it's never forgotten running.
  if (recording) return { id: "record" };
  if (justSaved) return { id: "saved" };
  // A task due now waits for an answer (Done / snooze); a goal still cuts in.
  if (taskAlarm && !(match && g && g.matchKey === match.key)) return { id: "taskAlarm" };
  // A goal interrupts everything except gaming mode, for the few seconds it lasts.
  if (match && g && g.matchKey === match.key) return { id: "football" };
  if (note) return { id: "notice" };
  if (message) return { id: "notif" };
  if (headline && !timerLive && !call) return { id: "news" };
  if (timerLive) return { id: "timer" };
  if (call) return { id: "discord" };
  if (match) return { id: "football" };
  if (soonTask) return { id: "taskSoon" };
  if (np?.isPlaying) return { id: "media" };
  if (clock) return { id: "clock" };
  return { id: null };
}

/**
 * The activity sits in a fixed box the size of the closed live notch, centered. Its two
 * sides (LiveEdge) follow the shape's edges by transform as it opens, closes or peeks.
 * Pinned to the edges by layout instead, they crept in pixel steps (issue #3).
 */
export function LiveSlot({ id }: { id: string | null }) {
  const np = useNowPlaying();
  const call = useDiscordCall();
  const match = useFeaturedMatch();
  const note = notice.use();
  const headline = newsNotice.use();
  const message = notifNotice.use();
  const taskAlarm = useTaskAlarm();
  const soonTask = useSoonTask();
  // Keep the last size while an activity fades out (id is null when the notch opens).
  const last = useRef(id);
  if (id) last.current = id;
  const box =
    last.current === "system"
      ? geometry.gaming
      : last.current === "football"
        ? geometry.score
        : last.current === "clock"
          ? geometry.clock
          : last.current === "taskAlarm"
            ? geometry.taskAlarm
            : last.current === "notice" || last.current === "news" || last.current === "notif"
            ? geometry.notice
          : geometry.live;
  const boxW = useRef(box.w);
  boxW.current = box.w;
  const spread = useTransform(useContext(ShapeWidth), (w) =>
    Math.max(0, (w - boxW.current) / 2),
  );
  return (
    <LiveSpread.Provider value={spread}>
      <div
        className="absolute top-0 left-1/2"
        style={{ width: box.w, height: box.h, marginLeft: -box.w / 2 }}
      >
        <AnimatePresence initial={false}>
          {id === "system" && <LiveGaming key="system" />}
          {id === "timer" && <LiveTimer key="timer" />}
          {id === "discord" && call && (
            <LiveDiscord key="discord" call={call} />
          )}
          {id === "football" && match && <LiveScore key="football" match={match} />}
          {id === "notice" && note && <FootballNotice key={`notice-${note.at}`} n={note} />}
          {id === "news" && headline && <NewsNoticeView key={`news-${headline.id}`} n={headline} />}
          {id === "notif" && message && <NotifNotice key={`notif-${message.id}-${message.at}`} n={message} />}
          {id === "media" && np && <LiveActivity key="media" np={np} />}
          {id === "clock" && <LiveClock key="clock" />}
          {id === "taskAlarm" && taskAlarm && <LiveTaskAlarm key={`task-${taskAlarm.id}`} task={taskAlarm} />}
          {id === "taskSoon" && soonTask && <LiveTaskSoon key={`soon-${soonTask.id}`} task={soonTask} />}
          {id === "record" && <LiveRecord key="record" />}
          {id === "saved" && <SavedNotice key="saved" />}
        </AnimatePresence>
      </div>
    </LiveSpread.Provider>
  );
}
