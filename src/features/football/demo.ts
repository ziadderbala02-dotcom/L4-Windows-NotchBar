import type { Match } from "./types";
import { glowColor } from "./types";

/** Browser-only sample match for designing the scoreboard without live data. */
export function runDemo(goals: boolean, push: (list: Match[]) => void) {
  const logo = (id: string) => `https://a.espncdn.com/i/teamlogos/soccer/500/${id}.png`;
  let minute = 63;
  const m: Match = {
    key: "s365:demo",
    source: "s365",
    id: "demo",
    league: "caf.confed",
    leagueName: "CAF CC",
    state: "in",
    clock: "63'",
    kickoff: Date.now() - 70 * 60_000,
    home: { id: "8200", name: "Al Ahly", short: "Al Ahly", abbr: "AHL", logo: logo("10207"), color: glowColor("c8102e"), score: 1 },
    away: { id: "132", name: "Barcelona", short: "Barcelona", abbr: "BAR", logo: logo("83"), color: glowColor("004d98", "a50044"), score: 1 },
    events: [
      { kind: "goal", side: "home", minute: "21'", player: "Emam Ashour", own: false, penalty: false },
      { kind: "goal", side: "away", minute: "48'", player: "Lamine Yamal", own: false, penalty: false },
    ],
  };
  // Lineups for the detail view, and a kickoff notice shortly after start.
  void import("./store").then(({ lineups, notice }) => {
    const xi = (names: string[]) => ({ formation: "4-3-3", starters: names.map((n, i) => ({ name: n, number: String(i + 1), pos: i === 0 ? "G" : i < 5 ? "D" : i < 8 ? "M" : "F" })), subs: [{ name: "Sub Player", number: "23", pos: "M" }] });
    lineups.set({ "s365:demo": { home: xi(["El Shenawy", "Hany", "Ibrahim", "Rabia", "Maaloul", "Attia", "Ashour", "Zizo", "Trezeguet", "Abou Ali", "Taher"]), away: xi(["Ter Stegen", "Kounde", "Araujo", "Cubarsi", "Balde", "Pedri", "De Jong", "Gavi", "Yamal", "Lewandowski", "Raphinha"]) } });
    if (!goals) window.setTimeout(() => notice.set({ kind: "kickoff", match: { ...m, state: "pre", kickoff: Date.now() + 9 * 60_000 }, at: Date.now() }), 1200);
  });
  const m2: Match = { ...m, key: "s365:demo2", id: "demo2", leagueName: "UCL", kickoff: m.kickoff + 60_000, home: { ...m.home, id: "131", name: "Real Madrid", short: "Real Madrid", abbr: "RMA", logo: null, color: "#f2f2f7", score: 0 }, away: { ...m.away, id: "7171", name: "Leipzig", short: "Leipzig", abbr: "RBL", logo: null, color: glowColor("dd0741"), score: 0 }, events: [] };
  const emit = () => push([{ ...m, home: { ...m.home }, away: { ...m.away }, events: [...m.events] }, { ...m2, clock: m.clock }]);
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
