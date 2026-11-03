import { useEffect, useState } from "react";
import { createStore } from "../../lib/store";
import { isNative } from "../../platform/native";
import { settings } from "../../settings/store";
import { fetchScoreboard } from "./espn";
import { sofaIncidents, sofaMatch, sofaTeamMatches } from "./sofa";
import { teamKey, type FollowedTeam, type Match, type Side } from "./types";

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
  /** "teams" = only matches of followed teams, "all" = every match of the competition. */
  competitions: { slug: string; mode: "teams" | "all" }[];
  /** Seconds the notch waits behind the live data (to match a delayed stream). */
  delay: number;
}

const espnLogo = (id: string) => `https://a.espncdn.com/i/teamlogos/soccer/500/${id}.png`;

const defaults: FollowState = {
  enabled: true,
  teams: [
    { key: "espn:10207", source: "espn", id: "10207", name: "Al Ahly", logo: espnLogo("10207"), league: "caf.confed" },
    { key: "espn:83", source: "espn", id: "83", name: "Barcelona", logo: espnLogo("83"), league: "esp.1" },
    { key: "espn:86", source: "espn", id: "86", name: "Real Madrid", logo: espnLogo("86"), league: "esp.1" },
  ],
  competitions: [
    { slug: "uefa.champions", mode: "teams" },
    { slug: "eng.1", mode: "teams" },
    { slug: "esp.1", mode: "teams" },
    { slug: "ita.1", mode: "teams" },
    { slug: "ger.1", mode: "teams" },
    { slug: "caf.champions", mode: "teams" },
    { slug: "caf.confed", mode: "teams" },
    { slug: "caf.nations", mode: "all" },
    { slug: "fifa.world", mode: "all" },
    { slug: "fifa.cwc", mode: "teams" },
    { slug: "esp.copa_del_rey", mode: "teams" },
  ],
  delay: 0,
};

export const follow = createStore<FollowState>(defaults, { persist: "football" });
follow.set((s) => ({ ...defaults, ...s }));

export function updateFollow(patch: Partial<FollowState> | ((s: FollowState) => Partial<FollowState>)) {
  follow.set((s) => ({ ...s, ...(typeof patch === "function" ? patch(s) : patch) }));
}

export function toggleTeam(team: FollowedTeam) {
  updateFollow((s) => ({
    teams: s.teams.some((t) => t.key === team.key) ? s.teams.filter((t) => t.key !== team.key) : [...s.teams, team],
  }));
}

/** Off → teams → all → off. */
export function cycleCompetition(slug: string) {
  updateFollow((s) => {
    const cur = s.competitions.find((c) => c.slug === slug);
    if (!cur) return { competitions: [...s.competitions, { slug, mode: "teams" }] };
    if (cur.mode === "teams") return { competitions: s.competitions.map((c) => (c.slug === slug ? { slug, mode: "all" as const } : c)) };
    return { competitions: s.competitions.filter((c) => c.slug !== slug) };
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

/* ---------- polling ---------- */

const espnCache = new Map<string, Match[]>();
const sofaCache = new Map<string, Match[]>();
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
  return new Set(follow.get().teams.map((t) => t.key));
}

export function involvesFollowed(m: Match, keys = followedKeys()): boolean {
  return keys.has(teamKey(m.source, m.home.id)) || keys.has(teamKey(m.source, m.away.id));
}

function relevant(m: Match): boolean {
  const f = follow.get();
  if (involvesFollowed(m)) return true;
  return m.source === "espn" && f.competitions.some((c) => c.slug === m.league && c.mode === "all");
}

/** Running, or close enough to kickoff that the first minutes should be quick. */
function hot(m: Match, now = Date.now()): boolean {
  if (m.state === "in") return true;
  if (m.state === "pre") return m.kickoff - now < 10 * 60_000 && now - m.kickoff < 3 * 3600_000;
  return false;
}

function espnSlugs(): string[] {
  const f = follow.get();
  const set = new Set(f.competitions.map((c) => c.slug));
  for (const t of f.teams) if (t.source === "espn" && t.league) set.add(t.league);
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
  await Promise.all(jobs);
  running = false;

  // Forget competitions and teams that are no longer followed.
  const slugs = new Set(espnSlugs());
  for (const k of [...espnCache.keys()]) if (!slugs.has(k)) espnCache.delete(k);
  const sofaIds = new Set(f.teams.filter((t) => t.source === "sofa").map((t) => t.id));
  for (const k of [...sofaCache.keys()]) if (!sofaIds.has(k)) sofaCache.delete(k);

  const all = dedupe([...[...espnCache.values()].flat(), ...[...sofaCache.values()].flat()]).filter(relevant);
  snapshots.push({ at: Date.now(), matches: all });
  while (snapshots.length > 2 && snapshots[1].at <= Date.now() - follow.get().delay * 1000 - 60_000) snapshots.shift();
  publish(failed.size > 0);

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
export function featured(list: Match[], g: Goal | null, now = Date.now()): Match | null {
  const keys = followedKeys();
  const live = list.filter((m) => m.state === "in" || (m.state === "post" && now - (endedAt.get(m.key) ?? 0) < FINAL_MS));
  if (!live.length) return null;
  const byGoal = g && live.find((m) => m.key === g.matchKey);
  if (byGoal) return byGoal;
  const rank = (m: Match) => (m.state === "in" ? 0 : 2) + (involvesFollowed(m, keys) ? 0 : 1);
  return [...live].sort((a, b) => rank(a) - rank(b) || a.kickoff - b.kickoff)[0];
}

export function useFeaturedMatch(): Match | null {
  const { matches } = scores.use();
  const g = goal.use();
  const { enabled } = follow.use();
  const on = settings.use().tools.some((t) => t.id === "football" && t.enabled);
  useTick(30_000);
  if (!enabled || !on) return null;
  return featured(matches, g);
}
