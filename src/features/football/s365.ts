import { getJson } from "./espn";
import { glowColor, type Lineups, type Match, type MatchEvent, type Player, type Side, type TableGroup, type TableRow, type TeamLineup } from "./types";

/*
 * 365Scores: the web app's own JSON API (not official, no key). Covers the Egyptian
 * league and cups, CAF, and the big European clubs, in Arabic.
 *
 *   /web/search/?query=…                       teams (follow picker)
 *   /web/games/current/?competitors=1,2        recent, live and next games of teams
 *   /web/games/current/?competitions=552       … of a competition (for the live table)
 *   /web/game/?gameId=…                        one game: events, lineups, players
 *   /web/standings/?competitions=552           table
 *   /web/news/?competitors=1,2                 news (Arabic)
 *
 * statusGroup: 1/2 = not started, 3 = live, 4 = finished.
 */

const BASE = "https://webws.365scores.com/web";
const PARAMS = "langId=27&timezoneName=Africa/Cairo&userCountryId=131&appTypeId=5";

const url = (path: string) => `${BASE}/${path}${path.includes("?") ? "&" : "?"}${PARAMS}`;

export const crest365 = (id: string | number, version = 1) =>
  `https://imagecache.365scores.com/image/upload/f_png,w_68,h_68,c_limit,q_auto:eco,dpr_2,d_Competitors:default1.png/v${version}/Competitors/${id}`;

interface RawCompetitor {
  id: number;
  name: string;
  shortName?: string;
  symbolicName?: string;
  score?: number;
  color?: string;
  awayColor?: string;
  imageVersion?: number;
  countryId?: number;
  sportId?: number;
  mainCompetitionId?: number;
  lineups?: { status?: number; formation?: string; members?: RawLineupMember[] };
}

interface RawLineupMember {
  id: number;
  status?: number;
  position?: { name?: string; shortName?: string };
}

interface RawEvent {
  competitorId: number;
  eventType: { id: number; subTypeId?: number; name?: string };
  gameTime?: number;
  addedTime?: number;
  gameTimeDisplay?: string;
  playerId?: number;
}

interface RawGame {
  id: number;
  competitionId: number;
  competitionDisplayName?: string;
  startTime: string;
  statusGroup: number;
  statusText?: string;
  shortStatusText?: string;
  gameTimeDisplay?: string;
  gameTime?: number;
  homeCompetitor: RawCompetitor;
  awayCompetitor: RawCompetitor;
  events?: RawEvent[];
  members?: { id: number; athleteId?: number; name?: string; shortName?: string; jerseyNumber?: number }[];
}

function side(c: RawCompetitor): Side {
  return {
    id: String(c.id),
    name: c.name,
    short: c.shortName || c.name,
    abbr: (c.symbolicName || c.name).slice(0, 4).toUpperCase(),
    logo: crest365(c.id, c.imageVersion),
    color: glowColor(c.color, c.awayColor),
    score: typeof c.score === "number" && c.score >= 0 ? c.score : 0,
  };
}

function clockOf(g: RawGame, state: Match["state"]): string {
  if (state === "post") return g.shortStatusText || "FT";
  if (state !== "in") return "";
  // During play the minute ("67'", "45'+2'"); at the break the status ("استراحة").
  return g.gameTimeDisplay?.trim() || g.shortStatusText || "LIVE";
}

function events(g: RawGame): MatchEvent[] {
  const name = (id?: number) => {
    const m = g.members?.find((x) => x.id === id || x.athleteId === id);
    return m?.name ?? m?.shortName ?? null;
  };
  const out: MatchEvent[] = [];
  for (const e of g.events ?? []) {
    // 1 = goal (subtype 3 penalty, 2 own goal), 3 = red card.
    if (e.eventType.id !== 1 && e.eventType.id !== 3) continue;
    out.push({
      kind: e.eventType.id === 1 ? "goal" : "red",
      side: e.competitorId === g.homeCompetitor.id ? "home" : "away",
      minute: e.gameTimeDisplay || (e.gameTime ? `${e.gameTime}'` : ""),
      player: name(e.playerId),
      own: e.eventType.id === 1 && e.eventType.subTypeId === 2,
      penalty: e.eventType.id === 1 && e.eventType.subTypeId === 3,
    });
  }
  return out;
}

export function parse365(g: RawGame, leagueName?: string): Match {
  const state: Match["state"] = g.statusGroup === 3 ? "in" : g.statusGroup === 4 ? "post" : "pre";
  return {
    key: `s365:${g.id}`,
    source: "s365",
    id: String(g.id),
    league: String(g.competitionId),
    leagueName: leagueName ?? shortCompetition(g.competitionDisplayName ?? ""),
    state,
    clock: clockOf(g, state),
    kickoff: Date.parse(g.startTime),
    home: side(g.homeCompetitor),
    away: side(g.awayCompetitor),
    events: events(g),
  };
}

/** "الدوري المصري - المرحلة النهائية - مجموعة البطولة" → "الدوري المصري". */
function shortCompetition(s: string): string {
  return s.split(" - ")[0];
}

/** Recent, live and upcoming games of the given teams (one request for all). */
export async function teamGames365(ids: string[]): Promise<Match[]> {
  if (!ids.length) return [];
  const r = await getJson<{ games?: RawGame[] }>(url(`games/current/?competitors=${ids.join(",")}`));
  return (r.games ?? []).map((g) => parse365(g));
}

/** Today's games of a competition (all of them): feeds the live table. */
export async function competitionGames365(competitionId: string): Promise<Match[]> {
  const r = await getJson<{ games?: RawGame[] }>(url(`games/current/?competitions=${competitionId}`));
  return (r.games ?? []).map((g) => parse365(g));
}

/** One game with its events (scorers) and lineups. */
export async function game365(gameId: string): Promise<{ match: Match; lineups: Lineups | null }> {
  const r = await getJson<{ game: RawGame }>(url(`game/?gameId=${gameId}`));
  const g = r.game;
  const players = new Map((g.members ?? []).map((m) => [m.id, m]));
  const team = (c: RawCompetitor): TeamLineup | null => {
    const ms = c.lineups?.members ?? [];
    const toPlayer = (m: RawLineupMember): Player => {
      const p = players.get(m.id);
      return { name: p?.name ?? p?.shortName ?? "?", number: p?.jerseyNumber ? String(p.jerseyNumber) : "", pos: m.position?.name ?? "" };
    };
    const starters = ms.filter((m) => m.status === 1).map(toPlayer);
    if (starters.length < 11) return null;
    return { formation: c.lineups?.formation ?? null, starters, subs: ms.filter((m) => m.status === 2).map(toPlayer) };
  };
  const home = team(g.homeCompetitor);
  const away = team(g.awayCompetitor);
  return { match: parse365(g), lineups: home && away ? { home, away } : null };
}

/** League table; for a cup with groups, every group. */
export async function standings365(competitionId: string): Promise<TableGroup[]> {
  const r = await getJson<{
    standings?: {
      displayName?: string;
      groupName?: string;
      rows?: {
        competitor: RawCompetitor;
        position: number;
        gamePlayed: number;
        gamesWon: number;
        gamesEven: number;
        gamesLost: number;
        for: number;
        against: number;
        points: number;
        destinationNum?: number;
        groupNum?: number;
      }[];
      destinations?: { num: number; name: string; color: string }[];
      groups?: { num: number; name: string }[];
    }[];
  }>(url(`standings/?competitions=${competitionId}`));
  const out: TableGroup[] = [];
  for (const s of r.standings ?? []) {
    const dest = new Map((s.destinations ?? []).map((d) => [d.num, d]));
    const byGroup = new Map<number, TableRow[]>();
    for (const x of s.rows ?? []) {
      const d = x.destinationNum ? dest.get(x.destinationNum) : undefined;
      const row: TableRow = {
        teamId: String(x.competitor.id),
        name: x.competitor.shortName || x.competitor.name,
        abbr: (x.competitor.symbolicName ?? "").toUpperCase(),
        logo: crest365(x.competitor.id, x.competitor.imageVersion),
        rank: x.position,
        played: x.gamePlayed,
        won: x.gamesWon,
        drawn: x.gamesEven,
        lost: x.gamesLost,
        gf: x.for,
        ga: x.against,
        points: x.points,
        note: d ? { color: d.color, text: d.name } : null,
      };
      const k = x.groupNum ?? 0;
      byGroup.set(k, [...(byGroup.get(k) ?? []), row]);
    }
    for (const [num, rows] of byGroup) {
      const gname = s.groups?.find((g) => g.num === num)?.name ?? s.displayName ?? "";
      out.push({ name: gname, rows: rows.sort((a, b) => a.rank - b.rank) });
    }
  }
  return out;
}

export interface TeamOption365 {
  id: string;
  name: string;
  country: number;
  logo: string;
}

export async function search365(q: string): Promise<TeamOption365[]> {
  const r = await getJson<{ competitors?: RawCompetitor[] }>(url(`search/?query=${encodeURIComponent(q)}`));
  return (r.competitors ?? [])
    .filter((c) => c.sportId === 1)
    .slice(0, 8)
    .map((c) => ({ id: String(c.id), name: c.name, country: c.countryId ?? 0, logo: crest365(c.id, c.imageVersion) }));
}

export interface NewsItem {
  id: string;
  title: string;
  url: string;
  image: string | null;
  at: number;
  source: string;
}

/** News for teams, or with `filter` ("sports=1", "competitions=552") a general feed. */
export async function news365(ids: string[], filter?: string): Promise<NewsItem[]> {
  if (!ids.length && !filter) return [];
  const r = await getJson<{ news?: { id: number; title: string; url: string; image?: string; publishDate: string }[]; newsSources?: { id: number; name: string }[] }>(
    url(filter ? `news/?${filter}` : `news/?competitors=${ids.join(",")}`),
  );
  return (r.news ?? []).map((n) => ({
    id: `365:${n.id}`,
    title: n.title,
    url: n.url,
    image: n.image ?? null,
    at: Date.parse(n.publishDate),
    source: "365Scores",
  }));
}

/** Competitions offered under "show every match of" (365Scores IDs). */
export const COMPETITIONS_365: { id: string; short: string; name: string }[] = [
  { id: "552", short: "الدوري المصري", name: "Egyptian Premier League" },
  { id: "553", short: "كأس مصر", name: "Egypt Cup" },
  { id: "7808", short: "كأس الرابطة", name: "Egyptian League Cup" },
  { id: "624", short: "أبطال أفريقيا", name: "CAF Champions League" },
  { id: "627", short: "الكونفدرالية", name: "CAF Confederation Cup" },
  { id: "167", short: "أمم أفريقيا", name: "Africa Cup of Nations" },
  { id: "5930", short: "كأس العالم", name: "FIFA World Cup" },
  { id: "572", short: "أبطال أوروبا", name: "UEFA Champions League" },
  { id: "573", short: "الدوري الأوروبي", name: "UEFA Europa League" },
  { id: "7", short: "الدوري الإنجليزي", name: "Premier League" },
  { id: "11", short: "الدوري الإسباني", name: "LaLiga" },
  { id: "17", short: "الدوري الإيطالي", name: "Serie A" },
  { id: "25", short: "الدوري الألماني", name: "Bundesliga" },
  { id: "35", short: "الدوري الفرنسي", name: "Ligue 1" },
  { id: "649", short: "الدوري السعودي", name: "Saudi Pro League" },
  { id: "8", short: "كأس إنجلترا", name: "FA Cup" },
  { id: "15", short: "السوبر الإسباني", name: "Supercopa de España" },
];

/** Every game of these competitions (recent, live, next). */
export async function competitionsGames365(ids: string[]): Promise<Match[]> {
  if (!ids.length) return [];
  const r = await getJson<{ games?: RawGame[] }>(url(`games/current/?competitions=${ids.join(",")}`));
  return (r.games ?? []).map((g) => parse365(g));
}

/** The English name of a 365Scores team (to find it on ESPN for the fallback). */
export async function englishName365(id: string, query: string): Promise<string | null> {
  const r = await getJson<{ competitors?: RawCompetitor[] }>(url(`search/?query=${encodeURIComponent(query)}`).replace("langId=27", "langId=1"));
  return r.competitors?.find((c) => String(c.id) === id)?.name ?? null;
}
