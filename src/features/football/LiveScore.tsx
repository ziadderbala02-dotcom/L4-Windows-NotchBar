import { useEffect, useState, type ReactNode } from "react";
import { AnimatePresence, motion } from "motion/react";
import { AlarmIcon as Whistle, ArrowUpRight, Newspaper, SoccerBall, UsersThree } from "@phosphor-icons/react";
import type { TeamNews } from "./news";
import { formatAgo, formatTime } from "../../lib/format";
import { content } from "../../design/motion";
import { LiveEdge } from "../../notch/liveEdges";
import { t } from "../../i18n";
import { clockText, goal, incident, redCards, scores, type Goal, type Incident, type Notice } from "./store";
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
  const inc = incident.use();
  const celebrating = g && g.matchKey === match.key ? g : null;
  const moment = !celebrating && inc && inc.matchKey === match.key ? inc : null;
  const view = celebrating ? (
    <GoalBurst key={`goal-${celebrating.at}`} match={match} g={celebrating} />
  ) : moment ? (
    <IncidentBurst key={`inc-${moment.at}`} match={match} n={moment} />
  ) : match.phase === "var" ? (
    <VarReview key={`var-${match.key}`} match={match} />
  ) : (
    <Bar key={`bar-${match.key}`} match={match} />
  );
  return (
    <motion.div
      className="absolute inset-0"
      initial={{ opacity: 0, filter: "blur(4px)" }}
      animate={{ opacity: 1, filter: "blur(0px)", transition: content.enter }}
      exit={{ opacity: 0, filter: "blur(4px)", transition: content.exit }}
    >
      <AnimatePresence initial={false} mode="popLayout">
        {view}
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

/**
 * Full team name, as large as fits: the short name from the provider, shrunk a step or two
 * for long ones instead of cut off ("الاتحاد" not "الات").
 */
function TeamName({ side }: { side: Side }) {
  const name = side.short || side.name;
  const len = [...name].length;
  const size = len <= 9 ? 11.5 : len <= 12 ? 10.5 : len <= 15 ? 9.5 : 9;
  return (
    <span className="min-w-0 truncate font-semibold text-label-2" dir="auto" style={{ fontSize: size, lineHeight: "14px" }}>
      {name}
    </span>
  );
}

/** Small red card(s) next to a team that has had a player sent off. */
function RedMark({ n }: { n: number }) {
  if (!n) return null;
  return (
    <motion.span
      className="flex shrink-0 items-center gap-px"
      initial={{ scale: 0 }}
      animate={{ scale: [0, 1.5, 1], transition: { duration: 0.45 } }}
    >
      <span className="block h-[10px] w-[7px] rounded-[1.5px] bg-red" />
      {n > 1 && <span className="text-[9px] leading-none font-bold text-red">{n}</span>}
    </motion.span>
  );
}

/**
 *   [crest] Al Ittihad 🟥        1 – 0         Ghazl El Mahalla [crest]
 *                                 67'
 * Names get the room; the minute sits small under the score (or the pause: "بين الشوطين").
 */
function Bar({ match }: { match: Match }) {
  const live = match.state === "in";
  // Added time counts on between polls (90+1', 90+2' …).
  const [, tick] = useState(0);
  useEffect(() => {
    const id = window.setInterval(() => tick((n) => n + 1), 15_000);
    return () => window.clearInterval(id);
  }, []);
  const ticking = live && match.phase !== "break";
  return (
    <motion.div
      className="absolute inset-0 flex items-center justify-between gap-1 px-[9px]"
      initial={{ opacity: 0, filter: "blur(3px)" }}
      animate={{ opacity: 1, filter: "blur(0px)", transition: { ...content.enter, delay: 0.25 } }}
      exit={{ opacity: 0, filter: "blur(3px)", transition: content.exit }}
    >
      <LiveEdge side="left" className="flex min-w-0 flex-1 cursor-pointer items-center gap-1.5">
        <span className="flex shrink-0" onClick={() => teamTap(match, "home")}>
          <Crest side={match.home} size={18} />
        </span>
        <TeamName side={match.home} />
        <RedMark n={redCards(match, "home")} />
      </LiveEdge>
      <span className="flex shrink-0 flex-col items-center leading-none">
        <span className="flex items-center gap-1">
          <Score value={match.home.score} />
          <span className="text-caption font-bold text-label-3">–</span>
          <Score value={match.away.score} />
        </span>
        <span className="flex items-center gap-[3px]">
          {ticking ? (
            <motion.span
              className="size-[4px] rounded-full bg-red"
              animate={{ opacity: [1, 0.25] }}
              transition={{ repeat: Infinity, repeatType: "reverse", duration: 0.9, ease: "easeInOut" }}
            />
          ) : null}
          <span className={`tabular text-[9.5px] leading-[11px] font-semibold ${ticking ? "text-red" : "text-label-3"}`} dir="auto">
            {clockText(match) || "–"}
          </span>
        </span>
      </span>
      <LiveEdge side="right" className="flex min-w-0 flex-1 items-center justify-end gap-1.5">
        <RedMark n={redCards(match, "away")} />
        <TeamName side={match.away} />
        <span className="flex shrink-0 cursor-pointer" onClick={() => teamTap(match, "away")}>
          <Crest side={match.away} size={18} />
        </span>
      </LiveEdge>
    </motion.div>
  );
}

/** Shared frame of the big moments (same size as the goal): icon, two lines, minute. */
function Moment({ icon, title, sub, color, side, children }: { icon: ReactNode; title: string; sub: string; color: string; side?: ReactNode; children?: ReactNode }) {
  return (
    <motion.div
      className="absolute inset-x-0 top-0 flex items-center gap-3 px-4"
      style={{ height: 58 }}
      initial={{ opacity: 0 }}
      animate={{ opacity: 1, transition: { duration: 0.15, delay: 0.1 } }}
      exit={{ opacity: 0, filter: "blur(4px)", transition: { duration: 0.2 } }}
    >
      <motion.div
        className="flex shrink-0 items-center"
        initial={{ scale: 0.2, opacity: 0 }}
        animate={{ scale: [0.2, 1.25, 1], opacity: 1, transition: { duration: 0.5, delay: 0.15, ease: "easeOut" } }}
      >
        {icon}
      </motion.div>
      <div className="min-w-0 flex-1" dir="rtl">
        <motion.div
          className="flex items-center gap-1.5 truncate text-[17px] leading-[21px] font-extrabold"
          style={{ color }}
          initial={{ y: -30, opacity: 0 }}
          animate={{ y: 0, opacity: 1, transition: { type: "spring", visualDuration: 0.4, bounce: 0.4, delay: 0.2 } }}
        >
          {children}
          <span className="truncate">{title}</span>
        </motion.div>
        <motion.div
          className="truncate text-caption text-label-2"
          initial={{ opacity: 0, y: 4 }}
          animate={{ opacity: 1, y: 0, transition: { delay: 0.5, duration: 0.3 } }}
        >
          {sub}
        </motion.div>
      </div>
      {side}
    </motion.div>
  );
}

const Minute = ({ v }: { v: string }) => (v ? <span className="shrink-0 text-footnote font-bold text-red tabular">{v}</span> : null);

/** Red card / VAR decision / penalty, for INCIDENT_MS. */
function IncidentBurst({ match, n }: { match: Match; n: Incident }) {
  const sub = [n.player, n.team.short || n.team.name].filter(Boolean).join(" · ");
  if (n.kind === "red")
    return (
      <Moment icon={<Crest side={n.team} size={32} />} title={n.title} sub={sub} color="#ff6961" side={<Minute v={n.minute} />}>
        <motion.span className="block h-[17px] w-[12px] shrink-0 rounded-[2px] bg-red" animate={{ rotate: [0, -12, 8, 0] }} transition={{ duration: 0.6, delay: 0.4 }} />
      </Moment>
    );
  if (n.kind === "pen")
    return (
      <Moment icon={<Crest side={n.team} size={32} />} title={n.title} sub={sub} color={n.ok ? "#ffb340" : "#ff6961"} side={<Minute v={n.minute} />}>
        <span className="text-[17px]">{n.ok ? "🎯" : "✗"}</span>
      </Moment>
    );
  // VAR decision: ✓ stands / given, ✗ cancelled.
  return (
    <Moment
      icon={<VarBadge />}
      title={n.title}
      sub={`${t("Nach VAR-Prüfung")} · ${n.team.short || n.team.name}`}
      color={n.ok ? "#4cd964" : "#ff6961"}
      side={
        <span className="flex shrink-0 items-center gap-1.5">
          <Score value={match.home.score} big />
          <span className="text-[16px] font-bold text-label-3">–</span>
          <Score value={match.away.score} big />
        </span>
      }
    >
      <motion.span className="text-[19px]" initial={{ scale: 0 }} animate={{ scale: [0, 1.5, 1], transition: { delay: 0.35, duration: 0.45 } }}>
        {n.ok ? "✓" : "✗"}
      </motion.span>
    </Moment>
  );
}

function VarBadge({ pulse = false }: { pulse?: boolean }) {
  return (
    <motion.span
      className="rounded-[6px] px-1.5 py-1 text-[13px] leading-none font-extrabold text-white"
      style={{ background: "#7c5cff" }}
      animate={pulse ? { opacity: [1, 0.45] } : undefined}
      transition={pulse ? { repeat: Infinity, repeatType: "reverse", duration: 0.8 } : undefined}
    >
      VAR
    </motion.span>
  );
}

/** A review the provider reports as running: stays until the decision comes in. */
function VarReview({ match }: { match: Match }) {
  // What's under review: the latest goal / card / penalty event, if the provider sent one yet.
  const last = [...match.events].reverse().find((e) => e.kind === "goal" || e.kind === "red" || e.kind === "pen");
  const what = !last ? t("Überprüfung läuft") : last.kind === "goal" ? (last.penalty ? "مراجعة ضربة جزاء" : "مراجعة هدف") : last.kind === "red" ? "مراجعة طرد" : "مراجعة ضربة جزاء";
  const team = last ? match[last.side] : null;
  return (
    <Moment
      icon={<VarBadge pulse />}
      title={what}
      sub={team ? `${team.short || team.name}${last?.player ? ` · ${last.player}` : ""}` : `${match.home.short} – ${match.away.short}`}
      color="#b4a5ff"
      side={<Minute v={match.clock} />}
    />
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
    n.kind === "second"
      ? "بدأ الشوط التاني"
      : n.kind === "kickoff"
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
        <div className="flex items-center justify-center gap-1.5 text-footnote font-semibold" style={{ color: n.kind === "lineups" ? "var(--color-yellow)" : "var(--color-green)" }}>
          {n.kind !== "lineups" ? <Whistle size={13} weight="fill" /> : <UsersThree size={13} weight="fill" />}
          <span>{title}</span>
          {n.kind === "kickoff" && <span className="text-label-3">· {formatTime(m.kickoff)}</span>}
          {n.kind === "second" && <span className="tabular text-label-2">· {m.home.score}-{m.away.score}</span>}
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
 * Hover or click opens the notch on the full headline (NewsExpanded); a click there opens the article.
 */
export function NewsNoticeView({ n }: { n: TeamNews }) {
  return (
    <motion.div
      className="absolute inset-x-0 top-0 flex cursor-pointer items-center gap-3 px-4"
      style={{ height: 54 }}
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

/**
 * Hovering a headline in the closed notch opens it in full instead of the tools:
 *
 *   [crest]  الأهلي · أخبار365 · منذ 3 د
 *            Full headline, wrapped over as many lines as it needs …
 *                                            [Read the article ↗]
 */
export function NewsExpanded({ n, onOpen }: { n: TeamNews; onOpen: () => void }) {
  return (
    <div className="flex h-full cursor-pointer flex-col gap-2 px-6 pt-4 pb-4" dir="rtl" onClick={onOpen}>
      <div className="flex items-center gap-2 text-caption font-semibold text-link">
        {n.team?.logo ? <img src={n.team.logo} alt="" className="size-5 object-contain" /> : <Newspaper size={14} weight="fill" />}
        <span className="truncate">
          {n.team ? `${n.team.name} · ` : ""}
          {n.source} · <bdi>{formatAgo(Date.now() - n.at)}</bdi>
        </span>
      </div>
      <div className="line-clamp-3 text-title leading-[22px] font-semibold text-label">{n.title}</div>
      <div className="mt-auto flex items-center gap-1 self-end text-caption font-medium text-label-2" dir="ltr">
        {t("Artikel lesen")}
        <ArrowUpRight size={11} weight="bold" />
      </div>
    </div>
  );
}

/**
 * The notch is open (you're working in a tool) and something happens in a match: a strip
 * over the header for as long as the moment lasts, in the team's color; nothing else moves.
 *
 *   [crest]  جووول! بيلينجهام 32'                     1 – 1
 */
export function OpenMomentBanner() {
  const g = goal.use();
  const inc = incident.use();
  const { matches } = scores.use();
  const key = g?.matchKey ?? inc?.matchKey;
  const m = key ? matches.find((x) => x.key === key) : null;
  const item = !m ? null : g && !g.disallowed
    ? { id: `g${g.at}`, team: g.team, color: g.team.color, title: "جووول!", sub: [g.player, g.minute].filter(Boolean).join(" · ") }
    : inc
      ? {
          id: `i${inc.at}`,
          team: inc.team,
          color: inc.kind === "red" ? "#ff453a" : inc.kind === "pen" ? "#ff9f0a" : inc.ok ? "#30d158" : "#ff453a",
          title: inc.kind === "var" ? `VAR · ${inc.title}` : inc.title,
          sub: [inc.player, inc.minute].filter(Boolean).join(" · "),
        }
      : null;
  return (
    <AnimatePresence>
      {m && item && (
        <motion.div
          key={item.id}
          className="absolute inset-x-3 top-1 bottom-0 z-20 flex items-center gap-2.5 rounded-full px-3"
          style={{ background: `color-mix(in srgb, ${item.color} 22%, #000)`, boxShadow: `inset 0 0 0 1px ${item.color}` }}
          initial={{ opacity: 0, y: -14, scale: 0.96 }}
          animate={{ opacity: 1, y: 0, scale: 1, transition: { type: "spring", visualDuration: 0.35, bounce: 0.35 } }}
          exit={{ opacity: 0, y: -10, transition: { duration: 0.2 } }}
        >
          <Crest side={item.team} size={20} />
          <span className="min-w-0 flex-1 truncate text-footnote" dir="rtl">
            <b style={{ color: item.color }}>{item.title}</b> <span className="text-label-2">{item.sub}</span>
          </span>
          <span className="tabular shrink-0 text-footnote font-bold text-label">
            {m.home.score} – {m.away.score}
          </span>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
