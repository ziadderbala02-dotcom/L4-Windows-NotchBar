import { useEffect, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { AlarmIcon as Whistle, Newspaper, SoccerBall, UsersThree } from "@phosphor-icons/react";
import { openNews, type TeamNews } from "./news";
import { formatTime } from "../../lib/format";
import { content } from "../../design/motion";
import { LiveEdge } from "../../notch/liveEdges";
import { t } from "../../i18n";
import { goal, type Goal, type Notice } from "./store";
import { onTeamClick } from "./MatchDetail";
import { navigate } from "../../notch/nav";
import type { Match, Side } from "./types";

/*
 * Closed notch during a match:
 *
 *   [crest] AHL 2   ● 67'   1 BAR [crest]
 *
 * On a goal the notch grows (geometry.goal), glows in the scoring team's color
 * (NotchShape `glow`) and shows GoalBurst: crest bounces in, the shout drops from the
 * top edge, the new score ticks over, then everything settles back into the bar.
 */
export function LiveScore({ match }: { match: Match }) {
  const g = goal.use();
  const celebrating = g && g.matchKey === match.key ? g : null;
  return (
    <motion.div
      className="absolute inset-0"
      initial={{ opacity: 0, filter: "blur(4px)" }}
      animate={{ opacity: 1, filter: "blur(0px)", transition: content.enter }}
      exit={{ opacity: 0, filter: "blur(4px)", transition: content.exit }}
    >
      <AnimatePresence initial={false} mode="popLayout">
        {celebrating ? <GoalBurst key={`goal-${celebrating.at}`} match={match} g={celebrating} /> : <Bar key="bar" match={match} />}
      </AnimatePresence>
    </motion.div>
  );
}

/**
 * Tap a crest in the closed bar: the notch opens (Notch handles the click) straight on
 * the football tool, with that team's lineup, or the table on a double tap.
 */
function teamTap(match: Match, side: "home" | "away") {
  navigate("football");
  onTeamClick(match, side);
}

function Bar({ match }: { match: Match }) {
  const live = match.state === "in";
  return (
    <motion.div
      className="absolute inset-0 flex items-center justify-between px-[9px]"
      initial={{ opacity: 0, filter: "blur(3px)" }}
      animate={{ opacity: 1, filter: "blur(0px)", transition: { ...content.enter, delay: 0.25 } }}
      exit={{ opacity: 0, filter: "blur(3px)", transition: content.exit }}
    >
      <LiveEdge side="left" className="flex cursor-pointer items-center gap-1.5">
        <span className="flex" onClick={() => teamTap(match, "home")}>
          <Crest side={match.home} size={18} />
        </span>
        <span className="text-caption font-semibold text-label-2">{match.home.abbr}</span>
        <Score value={match.home.score} />
      </LiveEdge>
      <span className="flex items-center gap-1 text-caption font-semibold">
        {live ? (
          <motion.span
            className="size-[5px] rounded-full bg-red"
            animate={{ opacity: [1, 0.25] }}
            transition={{ repeat: Infinity, repeatType: "reverse", duration: 0.9, ease: "easeInOut" }}
          />
        ) : null}
        <span className={`tabular ${live ? "text-label" : "text-label-3"}`}>{match.clock || "–"}</span>
      </span>
      <LiveEdge side="right" className="flex items-center gap-1.5">
        <Score value={match.away.score} />
        <span className="text-caption font-semibold text-label-2">{match.away.abbr}</span>
        <span className="flex cursor-pointer" onClick={() => teamTap(match, "away")}>
          <Crest side={match.away} size={18} />
        </span>
      </LiveEdge>
    </motion.div>
  );
}

/** A score digit that rolls up when it changes. */
function Score({ value, big = false, color }: { value: number; big?: boolean; color?: string }) {
  return (
    <span className={`relative inline-flex overflow-hidden ${big ? "h-[26px] min-w-[14px]" : "h-[16px] min-w-[8px]"} justify-center`}>
      <AnimatePresence initial={false} mode="popLayout">
        <motion.span
          key={value}
          className={`tabular font-bold ${big ? "text-[22px] leading-[26px]" : "text-footnote"}`}
          style={{ color: color ?? "var(--color-label)" }}
          initial={{ y: "100%", opacity: 0 }}
          animate={{ y: "0%", opacity: 1, transition: { type: "spring", visualDuration: 0.4, bounce: 0.35 } }}
          exit={{ y: "-100%", opacity: 0, transition: { duration: 0.18 } }}
        >
          {value}
        </motion.span>
      </AnimatePresence>
    </span>
  );
}

export function Crest({ side, size }: { side: Side; size: number }) {
  const [broken, setBroken] = useState(false);
  if (!side.logo || broken)
    return (
      <span
        className="flex shrink-0 items-center justify-center rounded-full font-bold text-black"
        style={{ width: size, height: size, background: side.color, fontSize: Math.max(7, size * 0.36) }}
      >
        {side.abbr.slice(0, 3)}
      </span>
    );
  return (
    <img
      src={side.logo}
      alt=""
      draggable={false}
      onError={() => setBroken(true)}
      className="shrink-0 object-contain"
      style={{ width: size, height: size }}
    />
  );
}

/**
 * The celebration, laid out for geometry.goal (wider and taller than the bar):
 *
 *   [CREST]   جووول!          2 – 1
 *             Salah · 67'
 */
function GoalBurst({ match, g }: { match: Match; g: Goal }) {
  const c = g.team.color;
  const text = g.disallowed ? t("Tor aberkannt") : "جووول!";
  const sub = [g.player, g.minute].filter(Boolean).join(" · ") || g.team.short;
  // Start on the old score and tick over once the shout has landed.
  const [ticked, setTicked] = useState(false);
  useEffect(() => {
    const id = window.setTimeout(() => setTicked(true), 750);
    return () => window.clearTimeout(id);
  }, []);
  const before = (s: "home" | "away") => match[s].score + (g.side === s ? (g.disallowed ? 1 : -1) : 0);
  const shownScore = (s: "home" | "away") => Math.max(0, ticked ? match[s].score : before(s));
  return (
    <motion.div
      className="absolute inset-x-0 top-0 flex items-center gap-3 px-4"
      style={{ height: 58 }}
      initial={{ opacity: 0 }}
      animate={{ opacity: 1, transition: { duration: 0.15, delay: 0.1 } }}
      exit={{ opacity: 0, filter: "blur(4px)", transition: { duration: 0.2 } }}
    >
      <motion.div
        className="flex shrink-0"
        initial={{ scale: 0.2, rotate: -25, opacity: 0 }}
        animate={{ scale: [0.2, 1.3, 1], rotate: [-25, 8, 0], opacity: 1, transition: { duration: 0.6, delay: 0.15, ease: "easeOut" } }}
      >
        <Crest side={g.team} size={34} />
      </motion.div>
      <div className="min-w-0 flex-1">
        <motion.div
          className="flex items-center gap-1.5 text-[21px] leading-[24px] font-extrabold tracking-tight"
          style={{ color: g.disallowed ? "var(--color-label-2)" : c }}
          initial={{ y: -40, opacity: 0, scale: 0.9 }}
          animate={{ y: 0, opacity: 1, scale: 1, transition: { type: "spring", visualDuration: 0.45, bounce: 0.45, delay: 0.2 } }}
        >
          {!g.disallowed && (
            <motion.span
              className="flex"
              animate={{ rotate: 360 }}
              transition={{ repeat: Infinity, duration: 1.4, ease: "linear" }}
            >
              <SoccerBall size={18} weight="fill" />
            </motion.span>
          )}
          <span dir="auto">{text}</span>
        </motion.div>
        <motion.div
          className="truncate text-caption text-label-2"
          dir="auto"
          initial={{ opacity: 0, y: 4 }}
          animate={{ opacity: 1, y: 0, transition: { delay: 0.55, duration: 0.3 } }}
        >
          {sub}
        </motion.div>
      </div>
      <div className="flex shrink-0 items-center gap-1.5">
        <Score value={shownScore("home")} big color={g.side === "home" && !g.disallowed ? c : undefined} />
        <span className="text-[18px] font-bold text-label-3">–</span>
        <Score value={shownScore("away")} big color={g.side === "away" && !g.disallowed ? c : undefined} />
      </div>
    </motion.div>
  );
}

/**
 * Closed notch, before a match of a followed team (geometry.notice):
 *
 *   [crest]  Starts in 9 min · 10:00 PM   [crest]
 *            Al Ahly vs Zamalek
 */
export function FootballNotice({ n }: { n: Notice }) {
  const m = n.match;
  const mins = Math.max(1, Math.round((m.kickoff - Date.now()) / 60_000));
  const title =
    n.kind === "kickoff"
      ? m.kickoff > Date.now()
        ? t("Anstoß in {n} Min.", { n: mins })
        : t("Anstoß!")
      : t("Aufstellungen sind da");
  return (
    <motion.div
      className="absolute inset-x-0 top-0 flex items-center gap-3 px-4"
      style={{ height: 54 }}
      initial={{ opacity: 0, filter: "blur(4px)" }}
      animate={{ opacity: 1, filter: "blur(0px)", transition: { ...content.enter, delay: 0.15 } }}
      exit={{ opacity: 0, filter: "blur(4px)", transition: content.exit }}
    >
      <motion.div className="flex" initial={{ scale: 0.4, opacity: 0 }} animate={{ scale: 1, opacity: 1, transition: { type: "spring", visualDuration: 0.4, bounce: 0.4, delay: 0.2 } }}>
        <Crest side={m.home} size={26} />
      </motion.div>
      <div className="min-w-0 flex-1 text-center">
        <div className="flex items-center justify-center gap-1.5 text-footnote font-semibold" style={{ color: n.kind === "kickoff" ? "var(--color-green)" : "var(--color-yellow)" }}>
          {n.kind === "kickoff" ? <Whistle size={13} weight="fill" /> : <UsersThree size={13} weight="fill" />}
          <span>{title}</span>
          {n.kind === "kickoff" && <span className="text-label-3">· {formatTime(m.kickoff)}</span>}
        </div>
        <div className="truncate text-caption text-label-2" dir="auto">
          {m.home.short} – {m.away.short}
        </div>
      </div>
      <motion.div className="flex" initial={{ scale: 0.4, opacity: 0 }} animate={{ scale: 1, opacity: 1, transition: { type: "spring", visualDuration: 0.4, bounce: 0.4, delay: 0.3 } }}>
        <Crest side={m.away} size={26} />
      </motion.div>
    </motion.div>
  );
}

/**
 * Closed notch, a new headline about a followed team (geometry.notice):
 *
 *   [crest]  الأهلي · أخبار365
 *            عنوان الخبر …
 *
 * Clicking opens the notch (Notch) and the article in the browser.
 */
export function NewsNoticeView({ n }: { n: TeamNews }) {
  return (
    <motion.div
      className="absolute inset-x-0 top-0 flex cursor-pointer items-center gap-3 px-4"
      style={{ height: 54 }}
      onClick={() => openNews(n.url)}
      initial={{ opacity: 0, filter: "blur(4px)" }}
      animate={{ opacity: 1, filter: "blur(0px)", transition: { ...content.enter, delay: 0.15 } }}
      exit={{ opacity: 0, filter: "blur(4px)", transition: content.exit }}
    >
      <motion.div className="flex shrink-0" initial={{ scale: 0.4, opacity: 0 }} animate={{ scale: 1, opacity: 1, transition: { type: "spring", visualDuration: 0.4, bounce: 0.4, delay: 0.2 } }}>
        {n.team?.logo ? <img src={n.team.logo} alt="" className="size-7 object-contain" /> : <Newspaper size={22} weight="fill" className="text-label-2" />}
      </motion.div>
      <div className="min-w-0 flex-1" dir="rtl">
        <div className="flex items-center gap-1.5 text-caption font-semibold text-link">
          <Newspaper size={11} weight="fill" />
          <span className="truncate">
            {n.team ? `${n.team.name} · ` : ""}
            {n.source}
          </span>
        </div>
        <div className="truncate text-footnote font-medium text-label">{n.title}</div>
      </div>
    </motion.div>
  );
}
