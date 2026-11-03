import type { Match } from "./types";
import { glowColor } from "./types";

/** Browser-only sample match for designing the scoreboard without live data. */
export function runDemo(goals: boolean, push: (list: Match[]) => void) {
  const logo = (id: string) => `https://a.espncdn.com/i/teamlogos/soccer/500/${id}.png`;
  let minute = 63;
  const m: Match = {
    key: "espn:demo",
    source: "espn",
    id: "demo",
    league: "caf.confed",
    leagueName: "CAF CC",
    state: "in",
    clock: "63'",
    kickoff: Date.now() - 70 * 60_000,
    home: { id: "10207", name: "Al Ahly", short: "Al Ahly", abbr: "AHL", logo: logo("10207"), color: glowColor("c8102e"), score: 1 },
    away: { id: "83", name: "Barcelona", short: "Barcelona", abbr: "BAR", logo: logo("83"), color: glowColor("004d98", "a50044"), score: 1 },
    events: [
      { kind: "goal", side: "home", minute: "21'", player: "Emam Ashour", own: false, penalty: false },
      { kind: "goal", side: "away", minute: "48'", player: "Lamine Yamal", own: false, penalty: false },
    ],
  };
  const emit = () => push([{ ...m, home: { ...m.home }, away: { ...m.away }, events: [...m.events] }]);
  emit();
  let n = 0;
  window.setInterval(() => {
    minute++;
    m.clock = `${minute}'`;
    if (goals && ++n % 4 === 0) {
      const side = n % 8 === 0 ? "away" : "home";
      m[side].score++;
      m.events.push({ kind: "goal", side, minute: m.clock, player: side === "home" ? "Wessam Abou Ali" : "Raphinha", own: false, penalty: false });
    }
    emit();
  }, 2000);
}
