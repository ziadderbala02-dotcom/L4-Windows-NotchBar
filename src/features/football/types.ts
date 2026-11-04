/*
 * Football data, provider-neutral. ESPN (./espn.ts) covers the big leagues and
 * tournaments; SofaScore (./sofa.ts) fills the gaps ESPN doesn't cover (Egyptian league).
 */

export type Source = "espn" | "sofa" | "s365";

export interface Side {
  /** Provider team ID ("83"). With the source it identifies a team: "espn:83". */
  id: string;
  name: string;
  short: string;
  abbr: string;
  logo: string | null;
  /** Team color as #rrggbb, already brightened enough to glow on black. */
  color: string;
  score: number;
}

export interface MatchEvent {
  /** goal, red / yellow card, substitution, VAR decision, missed penalty, anything else. */
  kind: "goal" | "red" | "yellow" | "sub" | "var" | "pen" | "other";
  side: "home" | "away";
  minute: string;
  player: string | null;
  own: boolean;
  penalty: boolean;
  /** Stable per match (provider order), to spot new events between polls. */
  id?: string;
  /** Assist (goal) or the player going off (substitution). */
  extra?: string | null;
  /** Provider wording ("ركلة جزاء ضائعة", "VAR - هدف ملغي"), for kinds without a fixed text. */
  label?: string;
  /** Minute as a number (for sorting). */
  at?: number;
}

export interface Match {
  /** "espn:401234" / "sofa:12345678". */
  key: string;
  source: Source;
  id: string;
  /** Competition slug (ESPN) or tournament ID (SofaScore). */
  league: string;
  leagueName: string;
  state: "pre" | "in" | "post";
  /** "67'", "45'+2'", "HT", "FT", or the kickoff time before the start. */
  clock: string;
  /** Kickoff, ms since epoch. */
  kickoff: number;
  home: Side;
  away: Side;
  events: MatchEvent[];
  /**
   * While live: "play", "break" (half time and other pauses, the clock shows the status),
   * or "var" (the provider says a review is on). Unset = play.
   */
  phase?: "play" | "break" | "var";
}

/** A team the user follows. */
export interface FollowedTeam {
  key: string;
  source: Source;
  id: string;
  name: string;
  logo: string | null;
  /** ESPN: the league the team was picked from (for its schedule). */
  league: string | null;
  /** 365Scores team: its ESPN counterpart, used while 365Scores is down (null = none found). */
  espn?: { id: string; league: string } | null;
}

export function teamKey(source: Source, id: string): string {
  return `${source}:${id}`;
}

/** Fallback when a provider has no color: neutral but still visible. */
const FALLBACK = "#d1d1d6";

/**
 * Team colors come in all shades; a navy or black glow disappears on the black notch.
 * Keeps the hue and raises the lightness instead (navy → bright blue, maroon → red).
 * Of a team's two colors the more colorful one wins; grey, black or white → soft white.
 */
export function glowColor(hex: string | null | undefined, alt?: string | null): string {
  const main = norm(hex);
  const second = norm(alt);
  const colorful = (c: [number, number, number]) => c[1] >= 0.2 && c[2] > 0.06 && c[2] < 0.97;
  const lift = ([h, s, l]: [number, number, number]) => fromHsl(h, Math.max(s, 0.6), Math.min(Math.max(l, 0.55), 0.7));
  if (main) {
    const c = hsl(main);
    if (colorful(c)) return lift(c);
    // White kit (Real Madrid): a white glow, not the trim color.
    if (c[2] >= 0.85) return "#f2f2f7";
  }
  // Black or grey first color: the second one if it has color.
  if (second && colorful(hsl(second))) return lift(hsl(second));
  return FALLBACK;
}

function norm(c: string | null | undefined): string | null {
  if (!c) return null;
  const s = c.trim().replace(/^#/, "");
  if (/^[0-9a-f]{6}$/i.test(s)) return `#${s.toLowerCase()}`;
  if (/^[0-9a-f]{3}$/i.test(s)) return `#${s.split("").map((x) => x + x).join("").toLowerCase()}`;
  return null;
}

function hsl(hex: string): [number, number, number] {
  const n = parseInt(hex.slice(1), 16);
  const r = ((n >> 16) & 255) / 255;
  const g = ((n >> 8) & 255) / 255;
  const b = (n & 255) / 255;
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const l = (max + min) / 2;
  if (max === min) return [0, 0, l];
  const d = max - min;
  const s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
  const h = max === r ? ((g - b) / d + (g < b ? 6 : 0)) / 6 : max === g ? ((b - r) / d + 2) / 6 : ((r - g) / d + 4) / 6;
  return [h, s, l];
}

function fromHsl(h: number, s: number, l: number): string {
  const q = l < 0.5 ? l * (1 + s) : l + s - l * s;
  const p = 2 * l - q;
  const ch = (t: number) => {
    const x = t < 0 ? t + 1 : t > 1 ? t - 1 : t;
    const v = x < 1 / 6 ? p + (q - p) * 6 * x : x < 1 / 2 ? q : x < 2 / 3 ? p + (q - p) * (2 / 3 - x) * 6 : p;
    return Math.round(v * 255).toString(16).padStart(2, "0");
  };
  return `#${ch(h + 1 / 3)}${ch(h)}${ch(h - 1 / 3)}`;
}

export interface Player {
  name: string;
  number: string;
  pos: string;
}

export interface TeamLineup {
  formation: string | null;
  starters: Player[];
  subs: Player[];
}

export interface Lineups {
  home: TeamLineup;
  away: TeamLineup;
}

export interface TableRow {
  teamId: string;
  name: string;
  abbr: string;
  logo: string | null;
  rank: number;
  played: number;
  won: number;
  drawn: number;
  lost: number;
  gf: number;
  ga: number;
  points: number;
  /** Qualification zone from the provider ("Champions League", "Relegation"). */
  note: { color: string; text: string } | null;
  /** Live table: places gained (+) or lost (−) by the running matches. */
  move?: number;
  /** Live table: this team is playing right now. */
  live?: boolean;
}

export interface TableGroup {
  name: string;
  rows: TableRow[];
}
