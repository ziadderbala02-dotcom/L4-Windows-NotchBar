import { useEffect, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { ArrowLeft, CaretDown, CaretUp } from "@phosphor-icons/react";
import { content } from "../../design/motion";
import { createStore } from "../../lib/store";
import { t } from "../../i18n";
import { fetchStandings, liveTable } from "./espn";
import { competitionGames365, game365, standings365, stats365, type MatchStat } from "./s365";
import { Crest } from "./LiveScore";
import { TeamMatches, openTeam } from "./TeamPage";
import { clockText, leagueMatches, loadLineups, scores } from "./store";
import type { Lineups, Match, MatchEvent, TableGroup, TeamLineup } from "./types";

/*
 * One match, opened from the football tool: click → lineups, double click → table.
 * The table is live: it counts every running match of the competition as if it
 * ended now, marks the two teams of this match and shows who moved up or down.
 */

type Tab = "match" | "lineups" | "table" | "matches";
type SideKey = "home" | "away";

/** `side`: opened from one team (its lineup only, its row stands out in the table). */
export const detail = createStore<{ match: Match; tab: Tab; side?: SideKey } | null>(null);

export function openDetail(match: Match, tab: Tab, side?: SideKey) {
  // A running or finished match opens on its events and stats (365Scores has them).
  const t2 = tab === "lineups" && match.source === "s365" && match.state !== "pre" ? "match" : tab;
  detail.set({ match, tab: t2, side });
}

/**
 * One click and a double click on the same element, told apart: the single action
 * waits a moment, a second click cancels it and runs the double action instead.
 * (A plain onClick would act on the first click and swap the view away under the second.)
 */
const DOUBLE_MS = 260;
let pending: number | undefined;
export function clickOrDouble(single: () => void, double: () => void) {
  if (pending !== undefined) {
    window.clearTimeout(pending);
    pending = undefined;
    double();
    return;
  }
  pending = window.setTimeout(() => {
    pending = undefined;
    single();
  }, DOUBLE_MS);
}

/** Click a team → the match's details on that team (lineup first; Table and Matches are tabs). */
export function onTeamClick(match: Match, side: SideKey) {
  openDetail(match, "lineups", side);
}

export function MatchDetail() {
  const d = detail.use();
  const { matches } = scores.use();
  if (!d) return null;
  // Follow the live score while the detail is open.
  const m = matches.find((x) => x.key === d.match.key) ?? d.match;
  const setTab = (tab: Tab) => detail.set({ ...d, match: m, tab });
  return (
    <div className="flex h-full flex-col px-5 pt-1 pb-2">
      <div className="mb-2 flex items-center gap-2">
        <button className="pressable flex size-7 items-center justify-center rounded-full bg-fill-1 text-label-2 hover:text-label" onClick={() => detail.set(null)} aria-label={t("Zurück")}>
          <ArrowLeft size={13} weight="bold" />
        </button>
        <Crest side={m.home} size={18} />
        <span className="max-w-[110px] truncate text-footnote font-semibold text-label">{m.home.short}</span>
        <span className="tabular shrink-0 text-footnote font-bold whitespace-nowrap text-label">{m.state === "pre" ? "–" : `${m.home.score} – ${m.away.score}`}</span>
        <span className="max-w-[110px] truncate text-footnote font-semibold text-label">{m.away.short}</span>
        <Crest side={m.away} size={18} />
        {m.state === "in" && <span className="tabular shrink-0 text-caption font-semibold whitespace-nowrap text-red" dir="auto">{clockText(m)}</span>}
        <span className="flex-1" />
        <div className="flex rounded-full bg-fill-1 p-0.5">
          {(m.source === "s365" && m.state !== "pre" ? (["match", "lineups", "table", "matches"] as const) : (["lineups", "table", "matches"] as const)).map((k) => (
            <button
              key={k}
              onClick={() => setTab(k)}
              className={`h-6 rounded-full px-3 text-caption font-medium ${d.tab === k ? "bg-fill-3 text-label" : "text-label-3 hover:text-label-2"}`}
            >
              {k === "match" ? t("Spiel") : k === "lineups" ? t("Aufstellung") : k === "table" ? t("Tabelle") : t("Spiele")}
            </button>
          ))}
        </div>
      </div>
      <AnimatePresence mode="popLayout" initial={false}>
        <motion.div
          key={d.tab}
          className="min-h-0 flex-1"
          initial={{ opacity: 0, filter: "blur(4px)" }}
          animate={{ opacity: 1, filter: "blur(0px)", transition: content.enter }}
          exit={{ opacity: 0, transition: content.exit }}
        >
          {d.tab === "match" ? <MatchPane m={m} /> : d.tab === "lineups" ? <LineupsPane m={m} side={d.side} /> : d.tab === "table" ? <TablePane m={m} side={d.side} /> : <MatchesPane m={m} side={d.side} />}
        </motion.div>
      </AnimatePresence>
    </div>
  );
}

/* ---------- match: events and stats ---------- */

/**
 * The match itself: what happened (goals with assist, cards, substitutions, VAR) in order,
 * newest on top, and the key stats as bars in the team colors. Refreshes every 10 s live.
 *
 *   67'  ⚽  Salah  (Assist: Szoboszlai)                 |
 *              |                      54'  🟨  Kimmich
 */
function MatchPane({ m }: { m: Match }) {
  const [evs, setEvs] = useState<MatchEvent[] | null>(m.events.some((e) => e.id) ? m.events : null);
  const [stats, setStats] = useState<MatchStat[] | null>(null);
  const [err, setErr] = useState(false);
  useEffect(() => {
    let alive = true;
    const load = () => {
      game365(m.id)
        .then(({ match }) => alive && (setEvs(match.events), setErr(false)))
        .catch(() => alive && setErr(true));
      stats365(m.id, m.home.id)
        .then((x) => alive && setStats(x))
        .catch(() => alive && setStats((s) => s ?? []));
    };
    load();
    const id = m.state === "in" ? window.setInterval(load, 10_000) : undefined;
    return () => {
      alive = false;
      window.clearInterval(id);
    };
  }, [m.key, m.state]);
  // The scoreboard's own copy (365 detail, every 10 s) when this pane's fetch fails.
  useEffect(() => {
    if (err && m.events.some((e) => e.id)) setEvs(m.events);
  }, [err, m.events]);
  const [view, setView] = useState<"events" | "stats">("events");
  if (!evs && err) return <Empty text={t("Spieldaten gerade nicht erreichbar")} />;
  if (!evs) return <Empty text={t("Lade Spiel …")} />;
  return (
    <div className="flex h-full flex-col">
      <div className="mx-auto mb-2 flex shrink-0 rounded-full bg-fill-1 p-0.5">
        {(["events", "stats"] as const).map((k) => (
          <button
            key={k}
            onClick={() => setView(k)}
            className={`h-6 rounded-full px-4 text-caption font-medium ${view === k ? "bg-fill-3 text-label" : "text-label-3 hover:text-label-2"}`}
          >
            {k === "events" ? t("Ereignisse") : t("Statistiken")}
          </button>
        ))}
      </div>
      <div className="-mx-1 min-h-0 flex-1 overflow-y-auto px-1 pb-1">
        {view === "events" ? <Timeline evs={evs} /> : stats?.length ? <Stats stats={stats} m={m} /> : <Empty text={stats ? t("Keine Statistiken") : "…"} />}
      </div>
    </div>
  );
}

/**
 * One line down the middle with the minute on it; each team's events on its own side
 * (home left, away right, as in the header), newest on top. Goals carry the score after them.
 */
function Timeline({ evs }: { evs: MatchEvent[] }) {
  if (!evs.length) return <Empty text={t("Noch keine Ereignisse")} />;
  // Running score, oldest first.
  const asc = [...evs].sort((a, b) => (a.at ?? 0) - (b.at ?? 0));
  const score = new Map<MatchEvent, string>();
  let h = 0;
  let a = 0;
  for (const e of asc) {
    if (e.kind !== "goal") continue;
    if (e.side === "home") h++;
    else a++;
    score.set(e, `${h}-${a}`);
  }
  const rows = [...asc].reverse();
  const out: React.ReactNode[] = [];
  rows.forEach((e, i) => {
    out.push(<TimelineRow key={e.id ?? i} e={e} score={score.get(e)} />);
    const next = rows[i + 1];
    // The break, between the last event of the first half and the first of the second.
    if (next && (e.at ?? 0) >= 46 && (next.at ?? 0) < 46) out.push(<Divider key={`ht${i}`} text="بين الشوطين" />);
  });
  out.push(<Divider key="ko" text="بداية الماتش" />);
  return (
    <div className="relative">
      <div className="absolute top-0 bottom-0 left-1/2 w-px bg-fill-2" />
      {out}
    </div>
  );
}

function Divider({ text }: { text: string }) {
  return (
    <div className="relative my-1 text-center">
      <span className="bg-black px-2 text-[10.5px] text-label-3">{text}</span>
    </div>
  );
}

function TimelineRow({ e, score }: { e: MatchEvent; score?: string }) {
  const home = e.side === "home";
  const main =
    e.kind === "goal"
      ? `${e.player ?? t("Tor")}${e.penalty ? " (ج)" : e.own ? " (هدف عكسي)" : ""}`
      : e.kind === "var" || e.kind === "pen" || e.kind === "other"
        ? e.label ?? ""
        : e.player ?? "";
  const sub =
    e.kind === "goal"
      ? e.extra ? `أسيست: ${e.extra}` : ""
      : e.kind === "sub"
        ? e.extra ? `↓ ${e.extra}` : ""
        : e.kind === "var" || e.kind === "pen"
          ? e.player ?? ""
          : "";
  const body = (
    <div className={`min-w-0 ${home ? "pr-3 text-right" : "pl-3 text-left"}`}>
      <div className={`flex items-center gap-1.5 text-footnote ${home ? "justify-end" : "justify-start"} ${e.kind === "goal" ? "font-bold text-label" : "text-label"}`}>
        <EventIcon e={e} />
        <span className="truncate" dir="auto">{e.kind === "sub" ? <><span className="text-green">↑ </span>{main}</> : main}</span>
        {score && <span className="tabular shrink-0 rounded-[5px] bg-green/20 px-1 text-[10.5px] font-bold text-green">{score}</span>}
      </div>
      {sub && <div className="truncate text-caption text-label-3" dir="auto">{sub}</div>}
    </div>
  );
  return (
    <div className="grid min-h-[30px] grid-cols-[1fr_46px_1fr] items-center">
      {home ? body : <span />}
      <span className="tabular relative z-[1] justify-self-center rounded-full bg-fill-1 px-1.5 text-[10.5px] leading-[17px] font-semibold text-label-2" dir="ltr">
        {e.minute}
      </span>
      {home ? <span /> : body}
    </div>
  );
}

function EventIcon({ e }: { e: MatchEvent }) {
  if (e.kind === "goal") return <span className="text-[12px]">⚽</span>;
  if (e.kind === "red") return <span className="block h-[12px] w-[8px] shrink-0 rounded-[1.5px] bg-red" />;
  if (e.kind === "yellow") return <span className="block h-[12px] w-[8px] shrink-0 rounded-[1.5px]" style={{ background: "#ffd60a" }} />;
  if (e.kind === "var") return <span className="shrink-0 rounded-[4px] px-1 text-[9px] leading-[14px] font-extrabold text-white" style={{ background: "#7c5cff" }}>VAR</span>;
  if (e.kind === "pen") return <span className="text-[12px] text-orange">✗</span>;
  return null;
}

/**
 * Each stat: the two numbers and the name, then two bars growing out from the middle.
 * Only the side ahead on that stat gets its team color, the other stays grey, so it reads
 * at a glance even when both kits are close (yellow vs white).
 */
function Stats({ stats, m }: { stats: MatchStat[]; m: Match }) {
  return (
    <div className="flex flex-col gap-2.5 px-1 pt-1">
      {stats.map((s) => {
        const hp = Math.round(s.homeShare * 100);
        const lead = hp > 50 ? "home" : hp < 50 ? "away" : null;
        const col = (side: "home" | "away") => (lead === side ? m[side].color : "var(--color-fill-3)");
        return (
          <div key={s.id}>
            <div className="grid grid-cols-[48px_1fr_48px] items-center text-footnote">
              <span className={`tabular ${lead === "home" ? "font-bold text-label" : "text-label-2"}`}>{s.home}</span>
              <span className="truncate text-center text-caption text-label-3" dir="auto">{s.name}</span>
              <span className={`tabular text-right ${lead === "away" ? "font-bold text-label" : "text-label-2"}`}>{s.away}</span>
            </div>
            <div className="mt-1 grid grid-cols-2 gap-1">
              <div className="flex h-[6px] justify-end overflow-hidden rounded-full bg-fill-1">
                <div className="h-full rounded-full" style={{ width: `${hp}%`, background: col("home") }} />
              </div>
              <div className="flex h-[6px] overflow-hidden rounded-full bg-fill-1">
                <div className="h-full rounded-full" style={{ width: `${100 - hp}%`, background: col("away") }} />
              </div>
            </div>
          </div>
        );
      })}
    </div>
  );
}

/* ---------- lineups ---------- */

function LineupsPane({ m, side }: { m: Match; side?: SideKey }) {
  const [state, setState] = useState<Lineups | null | "loading" | "error">("loading");
  useEffect(() => {
    let alive = true;
    loadLineups(m)
      .then((l) => alive && setState(l))
      .catch(() => alive && setState("error"));
    return () => {
      alive = false;
    };
  }, [m.key]);
  if (state === "loading") return <Empty text={t("Lade Aufstellungen …")} />;
  if (state === "error") return <Empty text={t("Aufstellungen gerade nicht erreichbar")} />;
  if (!state) return <Empty text={t("Noch keine Aufstellungen – meist etwa eine Stunde vor Anpfiff")} />;
  if (side) {
    // One team: chips to flip between the two, then that eleven with its bench beside it.
    return (
      <div className="flex h-full flex-col gap-2">
        <div className="flex gap-1.5">
          {(["home", "away"] as const).map((k) => (
            <button
              key={k}
              onClick={() => detail.set({ match: m, tab: "lineups", side: k })}
              className={`pressable flex h-7 items-center gap-1.5 rounded-full px-2.5 text-caption font-medium ${k === side ? "bg-fill-3 text-label" : "bg-fill-1 text-label-3 hover:text-label-2"}`}
            >
              <Crest side={m[k]} size={14} />
              {m[k].short}
            </button>
          ))}
          <button className="ml-auto text-caption text-label-3 hover:text-label-2" onClick={() => detail.set({ match: m, tab: "lineups" })}>
            {t("Beide Teams")}
          </button>
        </div>
        <div className="-mx-1 min-h-0 flex-1 overflow-y-auto px-1 pb-1">
          <Eleven key={side} side={state[side]} color={m[side].color} wide />
        </div>
      </div>
    );
  }
  return (
    <div className="-mx-1 grid h-full grid-cols-2 gap-4 overflow-y-auto px-1 pb-1">
      <Eleven side={state.home} color={m.home.color} />
      <Eleven side={state.away} color={m.away.color} />
    </div>
  );
}

function Eleven({ side, color, wide = false }: { side: TeamLineup; color: string; wide?: boolean }) {
  if (wide) {
    // One team on its own: starters and bench in two columns.
    return (
      <div className="grid grid-cols-2 gap-6">
        <Eleven side={{ ...side, subs: [] }} color={color} />
        <div className="flex flex-col">
          <span className="mb-1 text-caption font-semibold text-label-3">{t("Bank")}</span>
          {side.subs.map((p, i) => (
            <div key={i} className="flex h-6 items-center gap-2 text-footnote text-label-2">
              <span className="tabular w-5 text-right text-caption text-label-3">{p.number}</span>
              <span className="truncate" dir="auto">
                {p.name}
              </span>
              <span className="ml-auto text-caption text-label-3">{p.pos}</span>
            </div>
          ))}
        </div>
      </div>
    );
  }
  return (
    <div className="flex flex-col">
      {side.formation && <span className="mb-1 text-caption font-semibold text-label-3">{side.formation}</span>}
      {side.starters.map((p, i) => (
        <div key={i} className="flex h-6 items-center gap-2 text-footnote">
          <span className="tabular w-5 text-right text-caption font-bold" style={{ color }}>
            {p.number}
          </span>
          <span className="truncate text-label" dir="auto">
            {p.name}
          </span>
          <span className="ml-auto text-caption text-label-3">{p.pos}</span>
        </div>
      ))}
      {side.subs.length > 0 && (
        <>
          <span className="mt-2 mb-0.5 text-caption font-semibold text-label-3">{t("Bank")}</span>
          {side.subs.map((p, i) => (
            <div key={i} className="flex h-5 items-center gap-2 text-caption text-label-2">
              <span className="tabular w-5 text-right text-label-3">{p.number}</span>
              <span className="truncate" dir="auto">
                {p.name}
              </span>
            </div>
          ))}
        </>
      )}
    </div>
  );
}

/* ---------- table ---------- */

function TablePane({ m, side }: { m: Match; side?: SideKey }) {
  const [groups, setGroups] = useState<TableGroup[] | "loading" | "error">("loading");
  useEffect(() => {
    let alive = true;
    if (m.source === "sofa") {
      setGroups("error");
      return;
    }
    (m.source === "s365" ? standings365(m.league) : fetchStandings(m.league))
      .then((g) => alive && setGroups(g))
      .catch(() => alive && setGroups("error"));
    return () => {
      alive = false;
    };
  }, [m.key]);
  // Re-render with every score update, so the live table moves with the goals.
  scores.use();
  // 365Scores: the other games of the competition today, refreshed while the table is open.
  const [others, setOthers] = useState<Match[]>([]);
  useEffect(() => {
    if (m.source !== "s365") return;
    let alive = true;
    const load = () => void competitionGames365(m.league).then((g) => alive && setOthers(g)).catch(() => {});
    load();
    const id = window.setInterval(load, 20_000);
    return () => {
      alive = false;
      window.clearInterval(id);
    };
  }, [m.key]);

  if (groups === "loading") return <Empty text={t("Lade Tabelle …")} />;
  if (groups === "error") return <Empty text={t("Keine Tabelle für diesen Wettbewerb")} />;
  // A cup with groups: only the group of this match.
  const group = groups.find((g) => g.rows.some((r) => r.teamId === m.home.id || r.teamId === m.away.id)) ?? groups[0];
  if (!group) return <Empty text={t("Keine Tabelle für diesen Wettbewerb")} />;
  // This match from the live feed (freshest score), the rest of the round from the competition.
  const round = m.source === "s365" ? [m, ...others.filter((o) => o.key !== m.key)] : leagueMatches(m.league);
  const live = liveTable(group, round);
  const isLive = live.rows.some((r) => r.live);

  return (
    <div className="-mx-1 flex h-full flex-col overflow-y-auto px-1 pb-1">
      <div className="sticky top-0 z-10 grid grid-cols-[22px_1fr_26px_26px_26px_26px_36px_32px] items-center gap-1 bg-notch pb-1 text-caption text-label-3">
        <span>#</span>
        <span>
          {group.name && groups.length > 1 ? group.name : t("Team")}
          {isLive && <span className="ml-2 font-semibold text-red">● {t("Live-Tabelle")}</span>}
        </span>
        <span className="text-right">{t("Sp.")}</span>
        <span className="text-right">{t("S")}</span>
        <span className="text-right">{t("U")}</span>
        <span className="text-right">{t("N")}</span>
        <span className="text-right">{t("TD")}</span>
        <span className="text-right">{t("Pkt.")}</span>
      </div>
      {live.rows.map((r) => {
        const mine = r.teamId === m.home.id || r.teamId === m.away.id;
        const focus = !!side && r.teamId === m[side].id;
        const color = r.teamId === m.home.id ? m.home.color : r.teamId === m.away.id ? m.away.color : null;
        return (
          <motion.div
            layout
            key={r.teamId}
            transition={{ type: "spring", visualDuration: 0.45, bounce: 0.15 }}
            className={`grid h-7 shrink-0 cursor-pointer grid-cols-[22px_1fr_26px_26px_26px_26px_36px_32px] items-center gap-1 rounded-[8px] text-footnote hover:bg-fill-1 ${focus ? "bg-fill-3" : mine ? "bg-fill-2" : ""}`}
            style={color ? { boxShadow: `inset 3px 0 0 ${color}` } : undefined}
            // Click a team in the table: its results and next matches.
            onClick={() =>
              openTeam(
                { id: r.teamId, name: r.name, short: r.name, abbr: r.abbr, logo: r.logo, color: color ?? "#d1d1d6", score: 0 },
                m.source,
              )
            }
          >
            <span className="tabular flex items-center gap-0.5 pl-1.5 text-caption text-label-3">
              <span className="h-3 w-[2px] rounded-full" style={{ background: r.note?.color ?? "transparent" }} title={r.note?.text} />
              {r.rank}
            </span>
            <span className="flex min-w-0 items-center gap-1.5">
              {r.logo && <img src={r.logo} alt="" className="size-4 shrink-0 object-contain" />}
              <span className={`truncate ${mine ? "font-semibold text-label" : "text-label-2"}`}>{r.name}</span>
              {r.live && <span className="size-[5px] shrink-0 rounded-full bg-red" />}
              {!!r.move && (
                <span className={`flex items-center text-caption font-semibold ${r.move > 0 ? "text-green" : "text-red"}`}>
                  {r.move > 0 ? <CaretUp size={10} weight="fill" /> : <CaretDown size={10} weight="fill" />}
                  {Math.abs(r.move)}
                </span>
              )}
            </span>
            <span className="tabular text-right text-label-3">{r.played}</span>
            <span className="tabular text-right text-label-3">{r.won}</span>
            <span className="tabular text-right text-label-3">{r.drawn}</span>
            <span className="tabular text-right text-label-3">{r.lost}</span>
            <span className="tabular text-right text-label-2">{r.gf - r.ga > 0 ? `+${r.gf - r.ga}` : r.gf - r.ga}</span>
            <span className="tabular pr-1 text-right font-semibold text-label">{r.points}</span>
          </motion.div>
        );
      })}
    </div>
  );
}

function Empty({ text }: { text: string }) {
  return <div className="flex h-full items-center justify-center px-6 text-center text-body text-label-3">{text}</div>;
}

/* ---------- the two teams' matches ---------- */

/** Results and next matches of either team: chips pick the team (the clicked one first). */
function MatchesPane({ m, side }: { m: Match; side?: SideKey }) {
  const [k, setK] = useState<SideKey>(side ?? "home");
  return (
    <TeamMatches
      key={k}
      side={m[k]}
      source={m.source}
      header={
        <div className="flex gap-1.5">
          {(["home", "away"] as const).map((x) => (
            <button
              key={x}
              onClick={() => setK(x)}
              className={`pressable flex h-7 items-center gap-1.5 rounded-full px-2.5 text-caption font-medium ${x === k ? "bg-fill-3 text-label" : "bg-fill-1 text-label-3 hover:text-label-2"}`}
            >
              <Crest side={m[x]} size={14} />
              <span dir="auto">{m[x].short}</span>
            </button>
          ))}
        </div>
      }
    />
  );
}
