import { invoke } from "@tauri-apps/api/core";
import { isNative } from "../../platform/native";
import { glowColor, type Match, type MatchEvent, type Side } from "./types";

/*
 * ESPN's public site API (no key). Covers the European leagues, UEFA, CAF club
 * competitions, AFCON and the World Cup, but not the Egyptian league (→ ./sofa.ts).
 *
 *   /apis/site/v2/sports/soccer/{slug}/scoreboard        today's matches with live score
 *   /apis/site/v2/sports/soccer/{slug}/teams             teams of a competition
 *   /apis/site/v2/sports/soccer/{slug}/teams/{id}/schedule
 */

/** GET through Rust (no CORS trouble), in the browser directly. */
export async function getText(url: string): Promise<string> {
  if (isNative) return invoke<string>("football_get", { url });
  const r = await fetch(url, { signal: AbortSignal.timeout(10_000) });
  if (!r.ok) throw new Error(`http:${r.status}`);
  return r.text();
}

export async function getJson<T = unknown>(url: string): Promise<T> {
  return JSON.parse(await getText(url)) as T;
}

const BASE = "https://site.api.espn.com/apis/site/v2/sports/soccer";

export interface Competition {
  slug: string;
  name: string;
  /** Short label for the notch card. */
  short: string;
}

/** Competitions offered in the follow list (ESPN slugs). */
export const COMPETITIONS: Competition[] = [
  { slug: "uefa.champions", name: "UEFA Champions League", short: "UCL" },
  { slug: "eng.1", name: "Premier League", short: "PL" },
  { slug: "esp.1", name: "LaLiga", short: "LaLiga" },
  { slug: "ita.1", name: "Serie A", short: "Serie A" },
  { slug: "ger.1", name: "Bundesliga", short: "Bundesliga" },
  { slug: "fra.1", name: "Ligue 1", short: "Ligue 1" },
  { slug: "caf.champions", name: "CAF Champions League", short: "CAF CL" },
  { slug: "caf.confed", name: "CAF Confederation Cup", short: "CAF CC" },
  { slug: "caf.nations", name: "Africa Cup of Nations", short: "AFCON" },
  { slug: "fifa.world", name: "FIFA World Cup", short: "World Cup" },
  { slug: "fifa.cwc", name: "FIFA Club World Cup", short: "Club WC" },
  { slug: "uefa.europa", name: "UEFA Europa League", short: "UEL" },
  { slug: "uefa.euro", name: "UEFA Euro", short: "Euro" },
  { slug: "esp.copa_del_rey", name: "Copa del Rey", short: "Copa" },
  { slug: "esp.super_cup", name: "Supercopa de España", short: "Supercopa" },
  { slug: "eng.fa", name: "FA Cup", short: "FA Cup" },
  { slug: "ksa.1", name: "Saudi Pro League", short: "SPL" },
];

export function competitionName(slug: string): string {
  return COMPETITIONS.find((c) => c.slug === slug)?.short ?? slug;
}

/* ---------- raw shapes (only the fields we read) ---------- */

interface RawTeam {
  id: string;
  displayName?: string;
  shortDisplayName?: string;
  abbreviation?: string;
  logo?: string;
  logos?: { href: string }[];
  color?: string;
  alternateColor?: string;
}

interface RawCompetitor {
  homeAway: "home" | "away";
  score?: string | { value?: number; displayValue?: string };
  team: RawTeam;
}

interface RawDetail {
  type?: { text?: string };
  clock?: { displayValue?: string };
  team?: { id?: string };
  scoringPlay?: boolean;
  redCard?: boolean;
  ownGoal?: boolean;
  penaltyKick?: boolean;
  athletesInvolved?: { displayName?: string; shortName?: string }[];
}

interface RawEvent {
  id: string;
  date: string;
  status?: { displayClock?: string; type?: { state?: string; shortDetail?: string; detail?: string; completed?: boolean } };
  competitions?: { competitors?: RawCompetitor[]; details?: RawDetail[]; status?: RawEvent["status"] }[];
  league?: { slug?: string };
}

function side(c: RawCompetitor): Side {
  const t = c.team;
  const score = typeof c.score === "object" ? Number(c.score?.value ?? c.score?.displayValue ?? 0) : Number(c.score ?? 0);
  return {
    id: String(t.id),
    name: t.displayName ?? t.shortDisplayName ?? "?",
    short: t.shortDisplayName ?? t.displayName ?? "?",
    abbr: (t.abbreviation ?? t.shortDisplayName ?? "?").slice(0, 4).toUpperCase(),
    logo: t.logo ?? t.logos?.[0]?.href ?? null,
    color: glowColor(t.color, t.alternateColor),
    score: Number.isFinite(score) ? score : 0,
  };
}

/** ESPN clock → what the notch shows: "67'", "45'+2'", "HT", "FT". */
function clockOf(e: RawEvent, state: Match["state"]): string {
  const st = e.status ?? e.competitions?.[0]?.status;
  const detail = st?.type?.shortDetail ?? "";
  if (state === "post") return /AET|pen/i.test(detail) ? detail.replace(/^FT-?/, "").trim() || "FT" : "FT";
  if (state === "in") {
    if (/^HT$|half ?time/i.test(detail)) return "HT";
    const c = st?.displayClock?.trim();
    if (c && c !== "0'") return c.replace(/\s/g, "");
    return detail || "LIVE";
  }
  return "";
}

export function parseEvent(e: RawEvent, league: string, leagueName: string): Match | null {
  const comp = e.competitions?.[0];
  const cs = comp?.competitors ?? [];
  const h = cs.find((c) => c.homeAway === "home");
  const a = cs.find((c) => c.homeAway === "away");
  if (!h || !a) return null;
  const raw = (e.status ?? comp?.status)?.type?.state;
  const state: Match["state"] = raw === "in" ? "in" : raw === "post" ? "post" : "pre";
  const home = side(h);
  const away = side(a);
  const events: MatchEvent[] = [];
  for (const d of comp?.details ?? []) {
    const goal = d.scoringPlay || /goal/i.test(d.type?.text ?? "");
    const red = d.redCard || /red card/i.test(d.type?.text ?? "");
    if (!goal && !red) continue;
    const tid = d.team?.id ? String(d.team.id) : null;
    events.push({
      kind: goal ? "goal" : "red",
      side: tid === home.id ? "home" : "away",
      minute: d.clock?.displayValue ?? "",
      player: d.athletesInvolved?.[0]?.displayName ?? d.athletesInvolved?.[0]?.shortName ?? null,
      own: !!d.ownGoal || /own goal/i.test(d.type?.text ?? ""),
      penalty: !!d.penaltyKick || /penalty/i.test(d.type?.text ?? ""),
    });
  }
  return {
    key: `espn:${e.id}`,
    source: "espn",
    id: String(e.id),
    league,
    leagueName,
    state,
    clock: clockOf(e, state),
    kickoff: Date.parse(e.date),
    home,
    away,
    events,
  };
}

export async function fetchScoreboard(slug: string): Promise<Match[]> {
  const r = await getJson<{ events?: RawEvent[]; leagues?: { abbreviation?: string; name?: string }[] }>(`${BASE}/${slug}/scoreboard`);
  const name = competitionName(slug);
  return (r.events ?? []).map((e) => parseEvent(e, slug, name)).filter((m): m is Match => !!m);
}

export interface TeamOption {
  id: string;
  name: string;
  logo: string | null;
  league: string;
}

const teamCache = new Map<string, TeamOption[]>();

/** All teams of a competition, for the follow picker. */
export async function fetchTeams(slug: string): Promise<TeamOption[]> {
  const hit = teamCache.get(slug);
  if (hit) return hit;
  const r = await getJson<{ sports?: { leagues?: { teams?: { team: RawTeam }[] }[] }[] }>(`${BASE}/${slug}/teams`);
  const list = (r.sports?.[0]?.leagues?.[0]?.teams ?? []).map(({ team: t }) => ({
    id: String(t.id),
    name: t.displayName ?? t.shortDisplayName ?? "?",
    logo: t.logos?.[0]?.href ?? t.logo ?? null,
    league: slug,
  }));
  list.sort((a, b) => a.name.localeCompare(b.name));
  teamCache.set(slug, list);
  return list;
}

/** Upcoming and recent matches of a team in its league (the tool's fixture list). */
export async function fetchTeamSchedule(slug: string, teamId: string): Promise<Match[]> {
  const r = await getJson<{ events?: RawEvent[] }>(`${BASE}/${slug}/teams/${teamId}/schedule?fixture=true`);
  const name = competitionName(slug);
  return (r.events ?? []).map((e) => parseEvent(e, e.league?.slug ?? slug, name)).filter((m): m is Match => !!m);
}
