import { getJson } from "./espn";
import { glowColor, type Match, type MatchEvent, type Side } from "./types";

/*
 * SofaScore: covers the leagues ESPN doesn't (Egyptian Premier League). The API is
 * not official and may refuse requests or change; every call here is best-effort and
 * the notch simply shows nothing from it when it fails.
 *
 *   /api/v1/search/teams?q=…                 team search (follow picker)
 *   /api/v1/team/{id}/events/next/0 | last/0 a team's fixtures and results
 *   /api/v1/event/{id}                       one match, live
 *   /api/v1/event/{id}/incidents             goals and cards
 */

const BASE = "https://api.sofascore.com/api/v1";

export const sofaLogo = (teamId: string) => `${BASE}/team/${teamId}/image`;

interface RawTeam {
  id: number;
  name: string;
  shortName?: string;
  nameCode?: string;
  teamColors?: { primary?: string; secondary?: string };
  sport?: { slug?: string };
  country?: { name?: string };
}

interface RawEvent {
  id: number;
  startTimestamp: number;
  tournament?: { name?: string; uniqueTournament?: { id?: number; name?: string } };
  status?: { type?: string; description?: string; code?: number };
  homeTeam: RawTeam;
  awayTeam: RawTeam;
  homeScore?: { current?: number };
  awayScore?: { current?: number };
  time?: { currentPeriodStartTimestamp?: number; initial?: number; max?: number; extra?: number };
}

function side(t: RawTeam, score: number | undefined): Side {
  return {
    id: String(t.id),
    name: t.name,
    short: t.shortName ?? t.name,
    abbr: (t.nameCode ?? t.shortName ?? t.name).slice(0, 4).toUpperCase(),
    logo: sofaLogo(String(t.id)),
    color: glowColor(t.teamColors?.primary, t.teamColors?.secondary),
    score: score ?? 0,
  };
}

/** Running minute from the period start (SofaScore sends no clock string). */
function clockOf(e: RawEvent): string {
  const type = e.status?.type;
  if (type === "finished") return "FT";
  if (type !== "inprogress") return "";
  const desc = e.status?.description ?? "";
  if (/half ?time|pause/i.test(desc)) return "HT";
  const start = e.time?.currentPeriodStartTimestamp;
  if (!start) return desc || "LIVE";
  // `initial` = seconds already played when this period started (0, 2700, 5400 …).
  const offset = Math.round((e.time?.initial ?? 0) / 60);
  const cap = offset + (offset >= 90 ? 15 : 45);
  const m = Math.max(1, Math.floor((Date.now() / 1000 - start) / 60) + 1 + offset);
  return m > cap ? `${cap}'+${m - cap}'` : `${m}'`;
}

export function parseSofa(e: RawEvent): Match {
  const type = e.status?.type;
  return {
    key: `sofa:${e.id}`,
    source: "sofa",
    id: String(e.id),
    league: String(e.tournament?.uniqueTournament?.id ?? ""),
    leagueName: e.tournament?.uniqueTournament?.name ?? e.tournament?.name ?? "",
    state: type === "inprogress" ? "in" : type === "finished" ? "post" : "pre",
    clock: clockOf(e),
    kickoff: e.startTimestamp * 1000,
    home: side(e.homeTeam, e.homeScore?.current),
    away: side(e.awayTeam, e.awayScore?.current),
    events: [],
  };
}

/** Last and next match of a team (enough to know whether one is on now or soon). */
export async function sofaTeamMatches(teamId: string): Promise<Match[]> {
  const [next, last] = await Promise.allSettled([
    getJson<{ events?: RawEvent[] }>(`${BASE}/team/${teamId}/events/next/0`),
    getJson<{ events?: RawEvent[] }>(`${BASE}/team/${teamId}/events/last/0`),
  ]);
  const events = [
    ...(last.status === "fulfilled" ? (last.value.events ?? []).slice(-2) : []),
    ...(next.status === "fulfilled" ? (next.value.events ?? []).slice(0, 3) : []),
  ];
  if (next.status === "rejected" && last.status === "rejected") throw next.reason;
  return events.map(parseSofa);
}

export async function sofaMatch(eventId: string): Promise<Match> {
  const r = await getJson<{ event: RawEvent }>(`${BASE}/event/${eventId}`);
  return parseSofa(r.event);
}

export async function sofaIncidents(eventId: string): Promise<MatchEvent[]> {
  const r = await getJson<{ incidents?: { incidentType?: string; incidentClass?: string; isHome?: boolean; time?: number; addedTime?: number; player?: { name?: string } }[] }>(
    `${BASE}/event/${eventId}/incidents`,
  );
  const out: MatchEvent[] = [];
  for (const i of r.incidents ?? []) {
    const goal = i.incidentType === "goal";
    const red = i.incidentType === "card" && /red/i.test(i.incidentClass ?? "");
    if (!goal && !red) continue;
    out.push({
      kind: goal ? "goal" : "red",
      side: i.isHome ? "home" : "away",
      minute: i.time ? `${i.time}'${i.addedTime ? `+${i.addedTime}'` : ""}` : "",
      player: i.player?.name ?? null,
      own: /own/i.test(i.incidentClass ?? ""),
      penalty: /penalty/i.test(i.incidentClass ?? ""),
    });
  }
  // SofaScore lists newest first.
  return out.reverse();
}

export interface SofaTeamOption {
  id: string;
  name: string;
  country: string;
}

export async function sofaSearchTeams(q: string): Promise<SofaTeamOption[]> {
  const r = await getJson<{ teams?: RawTeam[]; results?: { type?: string; entity?: RawTeam }[] }>(`${BASE}/search/teams?q=${encodeURIComponent(q)}&page=0`);
  const teams = r.teams ?? (r.results ?? []).filter((x) => x.type === "team" && x.entity).map((x) => x.entity as RawTeam);
  return teams
    .filter((t) => !t.sport?.slug || t.sport.slug === "football")
    .slice(0, 8)
    .map((t) => ({ id: String(t.id), name: t.name, country: t.country?.name ?? "" }));
}
