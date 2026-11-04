import { useEffect, useState } from "react";
import { createStore } from "../../lib/store";
import { isNative } from "../../platform/native";
import { settings } from "../../settings/store";
import { fetchLineups, fetchScoreboard, fetchTeams } from "./espn";
import { sofaIncidents, sofaLineups, sofaMatch, sofaTeamMatches } from "./sofa";
import { COMPETITIONS_365, competitionsGames365, crest365, englishName365, game365, teamGames365 } from "./s365";
import { teamKey, type FollowedTeam, type Lineups, type Match, type Side } from "./types";

/*
 * Live scores for the teams and competitions the user follows.
 *
 * Polling is tiered so it stays light: every competition is checked every few minutes,
 * and only those with a match running (or about to kick off) every 10 s. Each result is
 * kept with its arrival time; what the notch shows can lag behind on purpose
 * (`delay`, "sync with my stream"), so a goal doesn't pop up before the TV shows it.
 */

export interface FollowState {
  enabled: boolean;
  teams: FollowedTeam[];
  /**
   * Competitions shown in full (every match), as 365Scores IDs ("552"). Before 1.9.1
   * these were ESPN slugs ("fifa.world"); the migration below converts them.
   */
  competitions: { slug: string; mode: "teams" | "all" }[];
  /** Seconds the notch waits behind the live data (to match a delayed stream). */
  delay: number;
  /** Old switch (1.9); newsMode replaces it. */
  news?: boolean;
  /** Which headlines pop up in the notch: all football news, the followed teams', or none. */
  newsMode?: "all" | "teams" | "off";
  /** Matches followed one-off (📌), by match key ("s365:4804720"); dropped after full time. */
  pinned?: string[];
  /** The match that stays in the notch when several run at once (⭐); null = take turns. */
  mainKey?: string | null;
  /** Competitions listed under "All", in this order (unset = the default list). */
  allComps?: { id: string; name: string }[];
}

const defaults: FollowState = {
  enabled: true,
  teams: [
    { key: "s365:8200", source: "s365", id: "8200", name: "الأهلي", logo: crest365(8200, 20), league: null },
    { key: "s365:132", source: "s365", id: "132", name: "برشلونة", logo: crest365(132), league: null },
    { key: "s365:131", source: "s365", id: "131", name: "ريال مدريد", logo: crest365(131), league: null },
  ],
  competitions: [
    { slug: "167", mode: "all" },
    { slug: "5930", mode: "all" },
  ],
  delay: 0,
  news: true,
};

export const follow = createStore<FollowState>(defaults, { persist: "football" });
follow.set((s) => ({ ...defaults, ...s }));

// 1.9: the starting teams move from ESPN to 365Scores (Arabic names, Egyptian league,
// lineups and news for all three). Teams the user added themselves stay as they are.
try {
  if (!localStorage.getItem("notch:football-365")) {
    localStorage.setItem("notch:football-365", "1");
    const swap: Record<string, FollowedTeam> = {
      "espn:10207": defaults.teams[0],
      "espn:83": defaults.teams[1],
      "espn:86": defaults.teams[2],
    };
    follow.set((s) => {
      const teams: FollowedTeam[] = [];
      for (const t of s.teams.map((x) => swap[x.key] ?? x)) if (!teams.some((y) => y.key === t.key)) teams.push(t);
      return { ...s, teams };
    });
  }
} catch {
  // No storage: keep the list.
}

// 1.9.1: everything comes from 365Scores, so ESPN and 365 never show the same match twice.
// ESPN competition slugs become 365Scores IDs (the ones offered before; others are dropped).
try {
  if (!localStorage.getItem("notch:football-comps-365")) {
    localStorage.setItem("notch:football-comps-365", "1");
    const map: Record<string, string> = {
      "caf.nations": "167", "fifa.world": "5930", "uefa.champions": "572", "uefa.europa": "573", "eng.1": "7", "esp.1": "11",
      "ita.1": "17", "ger.1": "25", "fra.1": "35", "caf.champions": "624", "caf.confed": "627", "ksa.1": "649", "eng.fa": "8", "esp.super_cup": "15",
    };
    follow.set((s) => ({
      ...s,
      competitions: s.competitions
        .filter((c) => c.mode === "all")
        .map((c) => (map[c.slug] ? { slug: map[c.slug], mode: "all" as const } : /^\d+$/.test(c.slug) ? c : null))
        .filter((c): c is { slug: string; mode: "all" } => !!c),
    }));
  }
} catch {
  // No storage: keep the list.
}

export function updateFollow(patch: Partial<FollowState> | ((s: FollowState) => Partial<FollowState>)) {
  follow.set((s) => ({ ...s, ...(typeof patch === "function" ? patch(s) : patch) }));
}

export function toggleTeam(team: FollowedTeam) {
  const adding = !follow.get().teams.some((t) => t.key === team.key);
  updateFollow((s) => ({
    teams: adding ? [...s.teams, team] : s.teams.filter((t) => t.key !== team.key),
  }));
  if (adding && team.source === "s365" && team.espn === undefined && !KNOWN_ESPN[team.id]) void resolveEspn(team);
}

/** 📌 Follow one match (not a favourite team's) in the notch until it ends. */
export function togglePin(m: Match) {
  updateFollow((s) => {
    const pins = s.pinned ?? [];
    const on = pins.includes(m.key);
    return { pinned: on ? pins.filter((k) => k !== m.key) : [...pins, m.key], mainKey: on && s.mainKey === m.key ? null : s.mainKey };
  });
}

/** ⭐ Keep this match in the notch (others only cut in for a goal); again = take turns. */
export function toggleMain(m: Match) {
  updateFollow((s) => ({ mainKey: s.mainKey === m.key ? null : m.key }));
}

export function isPinned(m: Match): boolean {
  return (follow.get().pinned ?? []).includes(m.key);
}

/* ---------- ESPN as a fallback for 365Scores ---------- */

/**
 * 365Scores is the main source (Arabic, Egyptian league), but its API is not official.
 * After three failed rounds in a row the followed teams and competitions switch to their
 * ESPN counterparts (English names, no Egyptian league) and 365Scores is retried every
 * five minutes; the first success switches back. Only one source is ever live, so the
 * same match can't show up twice.
 */
export const fallback = createStore<{ active: boolean; since: number | null }>({ active: false, since: null });
let fails365 = 0;
const FALLBACK_AFTER = 3;
const RETRY_365_MS = 5 * 60_000;

const KNOWN_ESPN: Record<string, { id: string; league: string }> = {
  "8200": { id: "10207", league: "caf.confed" },
  "132": { id: "83", league: "esp.1" },
  "131": { id: "86", league: "esp.1" },
};

/** 365Scores competition → ESPN slug. */
const COMP_TO_ESPN: Record<string, string> = {
  "167": "caf.nations", "5930": "fifa.world", "572": "uefa.champions", "573": "uefa.europa", "7": "eng.1", "11": "esp.1",
  "17": "ita.1", "25": "ger.1", "35": "fra.1", "624": "caf.champions", "627": "caf.confed", "649": "ksa.1", "8": "eng.fa", "15": "esp.super_cup",
};

/** Cups where followed clubs may play besides their league. */
const FALLBACK_CUPS = ["uefa.champions", "uefa.europa", "caf.champions", "caf.confed", "fifa.cwc", "esp.copa_del_rey"];
const LEAGUES_TO_SEARCH = ["eng.1", "esp.1", "ita.1", "ger.1", "fra.1", "ksa.1", "uefa.champions", "uefa.europa", "caf.champions", "caf.confed"];

function espnFor(t: FollowedTeam): { id: string; league: string } | null {
  if (t.source !== "s365") return null;
  return t.espn ?? KNOWN_ESPN[t.id] ?? null;
}

/** Find a newly followed 365Scores team on ESPN (by its English name), once. */
async function resolveEspn(team: FollowedTeam) {
  const norm = (x: string) => x.toLowerCase().replace(/\b(fc|sc|cf|afc|club|al|el)\b|[^a-z]/g, "");
  let found: { id: string; league: string } | null = null;
  try {
    const en = await englishName365(team.id, team.name);
    if (en) {
      for (const slug of LEAGUES_TO_SEARCH) {
        const hit = (await fetchTeams(slug).catch(() => [])).find((x) => norm(x.name) === norm(en));
        if (hit) {
          found = { id: hit.id, league: slug };
          break;
        }
      }
    }
  } catch {
    // Leave it unresolved; it just won't have a fallback.
  }
  updateFollow((s) => ({ teams: s.teams.map((t) => (t.key === team.key ? { ...t, espn: found } : t)) }));
}

/** Every match of a competition on or off (your teams' matches show up anyway). */
export function cycleCompetition(slug: string) {
  updateFollow((s) => {
    const cur = s.competitions.find((c) => c.slug === slug);
    if (cur?.mode === "all") return { competitions: s.competitions.filter((c) => c.slug !== slug) };
    return { competitions: [...s.competitions.filter((c) => c.slug !== slug), { slug, mode: "all" as const }] };
  });
}

/* ---------- state the UI reads ---------- */

export interface Scores {
  /** Relevant matches (live, today, recent), as shown (after the delay). */
  matches: Match[];
  updated: number | null;
  /** At least one source failed in the last round. */
  error: boolean;
}

export const scores = createStore<Scores>({ matches: [], updated: null, error: false });

export interface Goal {
  matchKey: string;
  side: "home" | "away";
  team: Side;
  player: string | null;
  minute: string;
  /** False when the score went down (goal ruled out by VAR). */
  disallowed: boolean;
  at: number;
}

/** The goal being celebrated right now (cleared after GOAL_MS). */
export const goal = createStore<Goal | null>(null);
export const GOAL_MS = 6500;

/** A finished match stays in the notch this long with its final score. */
const FINAL_MS = 3 * 60_000;
const FAST_MS = 10_000;
const SLOW_MS = 5 * 60_000;
const RETRY_MS = 60_000;

/* ---------- notices: kickoff soon, lineups out ---------- */

export interface Notice {
  kind: "kickoff" | "lineups";
  match: Match;
  at: number;
}

/** Shown in the closed notch for NOTICE_MS, one at a time. */
export const notice = createStore<Notice | null>(null);
export const NOTICE_MS = 9000;
const queue: Notice[] = [];

function showNext() {
  if (notice.get() || !queue.length) return;
  const n = { ...queue.shift()!, at: Date.now() };
  notice.set(n);
  window.setTimeout(() => {
    if (notice.get() === n) notice.set(null);
    window.setTimeout(showNext, 600);
  }, NOTICE_MS);
}

/** Remembered across restarts so a reminder never fires twice. */
const notified = createStore<Record<string, number>>({}, { persist: "football-notified" });
notified.set((s) => Object.fromEntries(Object.entries(s).filter(([, at]) => Date.now() - at < 2 * 86_400_000)));

function notifyOnce(id: string, n: Omit<Notice, "at">) {
  if (notified.get()[id]) return;
  notified.set((s) => ({ ...s, [id]: Date.now() }));
  queue.push({ ...n, at: 0 });
  showNext();
}

/** Lineups by match key, once announced. */
export const lineups = createStore<Record<string, Lineups>>({});
const lineupCheckedAt = new Map<string, number>();

export async function loadLineups(m: Match): Promise<Lineups | null> {
  const have = lineups.get()[m.key];
  if (have) return have;
  const l = m.source === "espn" ? await fetchLineups(m.league, m.id) : m.source === "s365" ? (await game365(m.id)).lineups : await sofaLineups(m.id);
  if (l) lineups.set((s) => ({ ...s, [m.key]: l }));
  return l;
}

/**
 * For followed teams only (not every World Cup match): "starts in 10 min" once, and
 * from 75 min before kickoff a lineup check every 4 min until the elevens are out.
 */
function checkReminders(list: Match[], now = Date.now()) {
  const keys = followedKeys();
  for (const m of list) {
    if (m.state !== "pre" || !(involvesFollowed(m, keys) || isPinned(m))) continue;
    const until = m.kickoff - now;
    if (until > 0 && until <= 10 * 60_000) notifyOnce(`kick:${m.key}`, { kind: "kickoff", match: m });
    if (until <= 75 * 60_000 && until > -30 * 60_000 && !lineups.get()[m.key] && now - (lineupCheckedAt.get(m.key) ?? 0) > 4 * 60_000) {
      lineupCheckedAt.set(m.key, now);
      void loadLineups(m)
        .then((l) => l && notifyOnce(`xi:${m.key}`, { kind: "lineups", match: m }))
        .catch(() => {});
    }
  }
}

/** Every match of a competition today (all of them, not just followed): for the live table. */
export function leagueMatches(slug: string): Match[] {
  return espnCache.get(slug) ?? [];
}

/* ---------- polling ---------- */

const espnCache = new Map<string, Match[]>();
const sofaCache = new Map<string, Match[]>();
let cache365: Match[] = [];
const nextAt = new Map<string, number>();
const failed = new Set<string>();
const snapshots: { at: number; matches: Match[] }[] = [];
const endedAt = new Map<string, number>();
let shown = new Map<string, Match>();
let timer: number | undefined;
let running = false;

function toolEnabled(): boolean {
  return settings.get().tools.some((t) => t.id === "football" && t.enabled);
}

function followedKeys(): Set<string> {
  const keys = new Set(follow.get().teams.map((t) => t.key));
  if (fallback.get().active) for (const t of follow.get().teams) {
    const e = espnFor(t);
    if (e) keys.add(teamKey("espn", e.id));
  }
  return keys;
}

export function involvesFollowed(m: Match, keys = followedKeys()): boolean {
  return keys.has(teamKey(m.source, m.home.id)) || keys.has(teamKey(m.source, m.away.id));
}

function relevant(m: Match): boolean {
  const f = follow.get();
  if (involvesFollowed(m) || (f.pinned ?? []).includes(m.key)) return true;
  if (m.source === "s365") return f.competitions.some((c) => c.slug === m.league && c.mode === "all");
  return fallback.get().active && m.source === "espn" && f.competitions.some((c) => c.mode === "all" && COMP_TO_ESPN[c.slug] === m.league);
}

/** Running, or close enough to kickoff that the first minutes should be quick. */
function hot(m: Match, now = Date.now()): boolean {
  if (m.state === "in") return true;
  if (m.state === "pre") return m.kickoff - now < 10 * 60_000 && now - m.kickoff < 3 * 3600_000;
  return false;
}

/**
 * ESPN competitions worth polling: those on "all matches", and (only when ESPN teams are
 * followed) those on "my teams". 365Scores teams don't need any of them.
 */
function espnSlugs(): string[] {
  // Only teams added from ESPN (older installs); competitions come from 365Scores now.
  const set = new Set<string>();
  const f = follow.get();
  for (const t of f.teams) if (t.source === "espn" && t.league) set.add(t.league);
  if (fallback.get().active) {
    const mapped = f.teams.map(espnFor).filter((e): e is { id: string; league: string } => !!e);
    for (const e of mapped) set.add(e.league);
    if (mapped.length) for (const c of FALLBACK_CUPS) set.add(c);
    for (const c of f.competitions) if (c.mode === "all" && COMP_TO_ESPN[c.slug]) set.add(COMP_TO_ESPN[c.slug]);
  }
  return [...set];
}

async function pollEspn(slug: string, now: number) {
  try {
    const ms = await fetchScoreboard(slug);
    espnCache.set(slug, ms);
    failed.delete(`espn:${slug}`);
    nextAt.set(`espn:${slug}`, now + (ms.some((m) => relevant(m) && hot(m, now)) ? FAST_MS : SLOW_MS));
  } catch {
    failed.add(`espn:${slug}`);
    nextAt.set(`espn:${slug}`, now + RETRY_MS);
  }
}

async function pollSofa(team: FollowedTeam, now: number) {
  const id = `sofa:${team.id}`;
  try {
    const prev = sofaCache.get(team.id) ?? [];
    const live = prev.filter((m) => hot(m, now));
    // A match on now: only that one, every 10 s. Otherwise the fixture list, every few minutes.
    const ms = live.length
      ? await Promise.all(live.map((m) => sofaMatch(m.id))).then((upd) => [...prev.filter((m) => !hot(m, now)), ...upd])
      : await sofaTeamMatches(team.id);
    sofaCache.set(team.id, ms);
    failed.delete(id);
    nextAt.set(id, now + (ms.some((m) => hot(m, now)) ? FAST_MS : SLOW_MS));
  } catch {
    failed.add(id);
    nextAt.set(id, now + RETRY_MS);
  }
}

/**
 * 365Scores: one request covers every followed team. While a game is live (or about to
 * start) every 10 s, plus its detail for the scorers.
 */
/** Pins and the ⭐ go away once their match has been over for a few minutes. */
function unpinFinished(ms: Match[], now: number) {
  const f = follow.get();
  const over = (k: string) => {
    const m = ms.find((x) => x.key === k);
    if (!m) return false;
    return m.state === "post" && now - (endedAt.get(k) ?? m.kickoff + 2.5 * 3600_000) > FINAL_MS;
  };
  const pins = (f.pinned ?? []).filter((k) => !over(k));
  const main = f.mainKey && over(f.mainKey) ? null : (f.mainKey ?? null);
  if (pins.length !== (f.pinned ?? []).length || main !== (f.mainKey ?? null)) updateFollow({ pinned: pins, mainKey: main });
}

async function poll365(now: number) {
  const ids = follow.get().teams.filter((t) => t.source === "s365").map((t) => t.id);
  const comps = follow.get().competitions.filter((c) => c.mode === "all" && /^\d+$/.test(c.slug)).map((c) => c.slug);
  const pinned = (follow.get().pinned ?? []).filter((k) => k.startsWith("s365:")).map((k) => k.slice(5));
  if (!ids.length && !comps.length && !pinned.length) {
    cache365 = [];
    return;
  }
  try {
    const [byTeam, byComp] = await Promise.all([teamGames365(ids), competitionsGames365(comps)]);
    let ms = [...byTeam, ...byComp.filter((g) => !byTeam.some((x) => x.key === g.key))];
    // Pinned matches not covered by a followed team: fetch each one.
    const missing = pinned.filter((id) => !ms.some((m) => m.id === id));
    const extra = await Promise.allSettled(missing.map((id) => game365(id)));
    ms = [...ms, ...extra.flatMap((x) => (x.status === "fulfilled" ? [x.value.match] : []))];
    unpinFinished(ms, now);
    const live = ms.filter((m) => m.state === "in");
    if (live.length) {
      const details = await Promise.allSettled(live.map((m) => game365(m.id)));
      const byKey = new Map(details.flatMap((d) => (d.status === "fulfilled" ? [[d.value.match.key, d.value.match] as const] : [])));
      // Keep the competition label from the list (the detail has the long one).
      ms = ms.map((m) => (byKey.has(m.key) ? { ...byKey.get(m.key)!, leagueName: m.leagueName } : m));
    }
    cache365 = ms;
    failed.delete("s365");
    fails365 = 0;
    if (fallback.get().active) {
      // Back on 365Scores: the ESPN data goes (espnSlugs shrinks, the cycle drops it).
      fallback.set({ active: false, since: null });
    }
    nextAt.set("s365", now + (ms.some((m) => hot(m, now)) ? FAST_MS : SLOW_MS));
  } catch {
    failed.add("s365");
    fails365++;
    if (fails365 >= FALLBACK_AFTER && !fallback.get().active) {
      fallback.set({ active: true, since: now });
      cache365 = [];
      // Fetch the ESPN side right away (its slugs aren't scheduled yet, so they're due).
      window.setTimeout(() => void cycle(), 1500);
    }
    nextAt.set("s365", now + (fallback.get().active ? RETRY_365_MS : RETRY_MS));
  }
}

/** Same match from two sources (Al Ahly in the CAF via ESPN and SofaScore): keep ESPN. */
function dedupe(list: Match[]): Match[] {
  const norm = (s: string) => s.toLowerCase().replace(/\b(fc|sc|cf|al|el|club)\b|[^a-z]/g, "");
  const out: Match[] = [];
  for (const m of list) {
    const dup = out.find(
      (o) =>
        o.source !== m.source &&
        Math.abs(o.kickoff - m.kickoff) < 20 * 60_000 &&
        [o.home.name, o.away.name].some((a) => [m.home.name, m.away.name].some((b) => norm(a) && norm(a) === norm(b))),
    );
    if (!dup) out.push(m);
    else if (dup.source === "sofa" && m.source === "espn") out[out.indexOf(dup)] = m;
  }
  return out;
}

async function cycle() {
  window.clearTimeout(timer);
  if (running) return;
  if (!follow.get().enabled || !toolEnabled()) {
    timer = window.setTimeout(() => void cycle(), SLOW_MS);
    return;
  }
  running = true;
  const now = Date.now();
  const f = follow.get();
  const due = (k: string) => now >= (nextAt.get(k) ?? 0);
  const jobs: Promise<void>[] = [];
  for (const slug of espnSlugs()) if (due(`espn:${slug}`)) jobs.push(pollEspn(slug, now));
  for (const t of f.teams) if (t.source === "sofa" && due(`sofa:${t.id}`)) jobs.push(pollSofa(t, now));
  if (due("s365")) jobs.push(poll365(now));
  await Promise.all(jobs);
  running = false;

  // Forget competitions and teams that are no longer followed.
  const slugs = new Set(espnSlugs());
  for (const k of [...espnCache.keys()]) if (!slugs.has(k)) espnCache.delete(k);
  const sofaIds = new Set(f.teams.filter((t) => t.source === "sofa").map((t) => t.id));
  for (const k of [...sofaCache.keys()]) if (!sofaIds.has(k)) sofaCache.delete(k);

  const all = dedupe([...[...espnCache.values()].flat(), ...[...sofaCache.values()].flat(), ...cache365]).filter(relevant);
  snapshots.push({ at: Date.now(), matches: all });
  while (snapshots.length > 2 && snapshots[1].at <= Date.now() - follow.get().delay * 1000 - 60_000) snapshots.shift();
  publish(failed.size > 0);
  checkReminders(all);

  const waits = [...nextAt.values()].map((t) => t - Date.now());
  timer = window.setTimeout(() => void cycle(), Math.max(2000, Math.min(SLOW_MS, ...waits)));
}

/** Show the newest snapshot that is at least `delay` old, and spot goals on the way. */
function publish(error = scores.get().error) {
  const cutoff = Date.now() - follow.get().delay * 1000;
  let snap: (typeof snapshots)[number] | undefined;
  for (const s of snapshots) if (s.at <= cutoff) snap = s;
  if (!snap) return;
  const next = new Map(snap.matches.map((m) => [m.key, m]));
  for (const m of snap.matches) {
    const before = shown.get(m.key);
    if (!before) continue;
    if (before.state === "in" && m.state === "post") endedAt.set(m.key, Date.now());
    for (const s of ["home", "away"] as const) {
      const diff = m[s].score - before[s].score;
      if (diff !== 0 && m.state !== "pre") celebrate(m, s, diff < 0);
    }
  }
  shown = next;
  scores.set({ matches: snap.matches, updated: snap.at, error });
}

function celebrate(m: Match, side: "home" | "away", disallowed: boolean) {
  const last = [...m.events].reverse().find((e) => e.kind === "goal" && e.side === side);
  const g: Goal = { matchKey: m.key, side, team: m[side], player: disallowed ? null : (last?.player ?? null), minute: last?.minute ?? m.clock, disallowed, at: Date.now() };
  goal.set(g);
  window.setTimeout(() => goal.get() === g && goal.set(null), GOAL_MS);
  // 365Scores lists scorers in the game detail; fetch it if the list didn't have it yet.
  if (m.source === "s365" && !disallowed && !last) {
    void game365(m.id)
      .then(({ match }) => {
        const sc = [...match.events].reverse().find((e) => e.kind === "goal" && e.side === side);
        if (sc && goal.get()?.at === g.at) goal.set({ ...g, player: sc.player, minute: sc.minute || g.minute });
      })
      .catch(() => {});
  }
  // SofaScore sends scorers separately.
  if (m.source === "sofa" && !disallowed) {
    void sofaIncidents(m.id)
      .then((evs) => {
        const sc = [...evs].reverse().find((e) => e.kind === "goal" && e.side === side);
        if (sc && goal.get()?.at === g.at) goal.set({ ...g, player: sc.player, minute: sc.minute || g.minute });
      })
      .catch(() => {});
  }
}

/** Restart polling now (follow list changed, tool switched on, manual refresh). */
export function refreshScores() {
  nextAt.clear();
  void cycle();
}

follow.subscribe(() => {
  // Debounced: toggling several teams in a row is one refresh.
  window.clearTimeout(refreshDebounce);
  refreshDebounce = window.setTimeout(refreshScores, 800);
});
let refreshDebounce: number | undefined;
let lastTools = toolEnabled();
settings.subscribe(() => {
  const on = toolEnabled();
  if (on !== lastTools) {
    lastTools = on;
    refreshScores();
  }
});

// With a stream delay, the shown state has to catch up between polls.
window.setInterval(() => {
  if (follow.get().delay > 0) publish();
}, 1000);

// Browser preview: `?football` shows a demo match, `?football=goal` scores every 8 s.
const demoParam = typeof location !== "undefined" ? new URLSearchParams(location.search).get("football") : null;
if (demoParam !== null && !isNative) void import("./demo").then((d) => d.runDemo(demoParam === "goal", (list) => {
  snapshots.push({ at: Date.now(), matches: list });
  publish(false);
}));
else void cycle();

/* ---------- hooks ---------- */

/** Ticks every `ms` so time-based views (final score fading out) update. */
function useTick(ms: number) {
  const [, set] = useState(0);
  useEffect(() => {
    const id = window.setInterval(() => set((n) => n + 1), ms);
    return () => window.clearInterval(id);
  }, [ms]);
}

/**
 * The match the closed notch shows: the one with a goal right now, else a running
 * match of a followed team, else any running match, else one that just ended.
 */
/** Several matches running and no ⭐: the notch takes turns this often. */
export const ROTATE_MS = 10_000;

/**
 * The match the closed notch shows:
 *   1. the one with a goal right now (always, so no goal is missed)
 *   2. the ⭐ match, while it runs
 *   3. otherwise the running matches take turns every ROTATE_MS
 *   4. a match that just ended, with its final score
 */
export function featured(list: Match[], g: Goal | null, now = Date.now()): Match | null {
  const live = list.filter((m) => m.state === "in" || (m.state === "post" && now - (endedAt.get(m.key) ?? 0) < FINAL_MS));
  if (!live.length) return null;
  const byGoal = g && live.find((m) => m.key === g.matchKey);
  if (byGoal) return byGoal;
  const running = live.filter((m) => m.state === "in").sort((a, b) => a.kickoff - b.kickoff || a.key.localeCompare(b.key));
  const main = follow.get().mainKey;
  const star = main && running.find((m) => m.key === main);
  if (star) return star;
  if (running.length) return running[Math.floor(now / ROTATE_MS) % running.length];
  return [...live].sort((a, b) => b.kickoff - a.kickoff)[0];
}

export function useFeaturedMatch(): Match | null {
  const { matches } = scores.use();
  const g = goal.use();
  const { enabled } = follow.use();
  const on = settings.use().tools.some((t) => t.id === "football" && t.enabled);
  // Taking turns needs a tick at the rotation pace; otherwise a slow one is enough.
  const running = matches.filter((m) => m.state === "in").length;
  useTick(running > 1 ? 1000 : 30_000);
  if (!enabled || !on) return null;
  return featured(matches, g);
}

/* ---------- competitions under "All" ---------- */

export function allComps(): { id: string; name: string }[] {
  return follow.get().allComps ?? COMPETITIONS_365.map((c) => ({ id: c.id, name: c.short }));
}

export function setAllComps(list: { id: string; name: string }[]) {
  updateFollow({ allComps: list });
}

export function moveComp(id: string, by: -1 | 1) {
  const l = [...allComps()];
  const i = l.findIndex((c) => c.id === id);
  const j = i + by;
  if (i < 0 || j < 0 || j >= l.length) return;
  [l[i], l[j]] = [l[j], l[i]];
  setAllComps(l);
}
