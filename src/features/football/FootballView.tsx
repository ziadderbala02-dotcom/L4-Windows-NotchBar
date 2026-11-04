import { useEffect, useMemo, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { ArrowClockwise, Check, MagnifyingGlass, Plus, SlidersHorizontal, SoccerBall, X } from "@phosphor-icons/react";
import { content, fade } from "../../design/motion";
import { locale, t } from "../../i18n";
import { formatTime } from "../../lib/format";
import { COMPETITIONS, competitionName, fetchTeams, fetchTeamSchedule, type TeamOption } from "./espn";
import { sofaLogo, sofaSearchTeams, sofaTeamMatches, type SofaTeamOption } from "./sofa";
import { Crest } from "./LiveScore";
import { MatchDetail, clickOrDouble, detail, onTeamClick, openDetail } from "./MatchDetail";
import { cycleCompetition, follow, refreshScores, scores, toggleTeam, updateFollow } from "./store";
import type { FollowedTeam, Match } from "./types";

/*
 * Football tool:
 *   Matches  live matches as big cards (with scorers), then today and the next fixtures
 *            of the followed teams.
 *   Follow   competitions (off / my teams / all matches), teams, stream delay.
 */
export function FootballView() {
  const [panel, setPanel] = useState<"matches" | "follow">("matches");
  const open = detail.use();
  if (open) return <MatchDetail />;
  return (
    <div className="relative flex h-full flex-col px-5 pt-1 pb-2">
      <div className="mb-2 flex items-center gap-2">
        <SoccerBall size={15} weight="fill" className="text-label-2" />
        <span className="text-footnote font-semibold text-label">{panel === "matches" ? t("Spiele") : t("Folgen")}</span>
        <span className="flex-1" />
        {panel === "matches" && (
          <button className="pressable flex size-7 items-center justify-center rounded-full text-label-3 hover:text-label" title={t("Aktualisieren")} onClick={refreshScores}>
            <ArrowClockwise size={14} weight="bold" />
          </button>
        )}
        <button
          className="pressable flex h-7 items-center gap-1.5 rounded-full bg-fill-1 px-3 text-caption font-medium text-label-2 hover:text-label"
          onClick={() => setPanel(panel === "matches" ? "follow" : "matches")}
        >
          {panel === "matches" ? <SlidersHorizontal size={12} weight="bold" /> : <Check size={12} weight="bold" />}
          {panel === "matches" ? t("Teams & Ligen") : t("Fertig")}
        </button>
      </div>
      <AnimatePresence mode="popLayout" initial={false}>
        <motion.div
          key={panel}
          className="min-h-0 flex-1"
          initial={{ opacity: 0, filter: "blur(4px)" }}
          animate={{ opacity: 1, filter: "blur(0px)", transition: content.enter }}
          exit={{ opacity: 0, transition: content.exit }}
        >
          {panel === "matches" ? <Matches /> : <Follow />}
        </motion.div>
      </AnimatePresence>
    </div>
  );
}

/* ---------- matches ---------- */

const upcomingCache: { at: number; key: string; list: Match[] } = { at: 0, key: "", list: [] };

/** Next fixtures of the followed teams (ESPN schedules + SofaScore), cached for 30 min. */
function useUpcoming(): Match[] {
  const { teams } = follow.use();
  const key = teams.map((t) => t.key).join(",");
  const [list, setList] = useState<Match[]>(upcomingCache.key === key ? upcomingCache.list : []);
  useEffect(() => {
    if (upcomingCache.key === key && Date.now() - upcomingCache.at < 30 * 60_000) return;
    let alive = true;
    const jobs = teams.map((tm) =>
      tm.source === "espn" && tm.league ? fetchTeamSchedule(tm.league, tm.id) : tm.source === "sofa" ? sofaTeamMatches(tm.id) : Promise.resolve([]),
    );
    void Promise.allSettled(jobs).then((rs) => {
      const now = Date.now();
      const seen = new Set<string>();
      const all = rs
        .flatMap((r) => (r.status === "fulfilled" ? r.value : []))
        .filter((m) => m.state === "pre" && m.kickoff > now && !seen.has(m.key) && seen.add(m.key))
        .sort((a, b) => a.kickoff - b.kickoff)
        .slice(0, 8);
      Object.assign(upcomingCache, { at: now, key, list: all });
      if (alive) setList(all);
    });
    return () => {
      alive = false;
    };
  }, [key]);
  return list;
}

function Matches() {
  const { matches, error, updated } = scores.use();
  const upcoming = useUpcoming();
  const live = matches.filter((m) => m.state === "in");
  const today = matches.filter((m) => m.state !== "in").sort((a, b) => a.kickoff - b.kickoff);
  const later = upcoming.filter((u) => !matches.some((m) => m.key === u.key));

  if (!updated && !upcoming.length)
    return <div className="flex h-full items-center justify-center text-body text-label-3">{error ? t("Ergebnisse gerade nicht erreichbar") : t("Lade Spiele …")}</div>;

  return (
    <div className="-mx-1 flex h-full flex-col gap-2 overflow-y-auto px-1 pb-1">
      {live.map((m) => (
        <LiveCard key={m.key} m={m} />
      ))}
      {today.length > 0 && <Section title={t("Heute")} list={today} />}
      {later.length > 0 && <Section title={t("Demnächst")} list={later} />}
      {!live.length && !today.length && !later.length && (
        <div className="flex flex-1 items-center justify-center text-body text-label-3">{t("Gerade keine Spiele deiner Teams")}</div>
      )}
    </div>
  );
}

function LiveCard({ m }: { m: Match }) {
  const goals = m.events.filter((e) => e.kind === "goal");
  return (
    <div
      className="cursor-pointer rounded-[16px] bg-fill-1 px-4 py-3 hover:bg-fill-2"
      onClick={() => clickOrDouble(() => openDetail(m, "lineups"), () => openDetail(m, "table"))}
      title={t("Klick: Aufstellungen · Doppelklick: Tabelle")}
    >
      <div className="mb-2 flex items-center justify-between text-caption text-label-3">
        <span>{m.leagueName}</span>
        <span className="flex items-center gap-1 font-semibold text-red">
          <motion.span className="size-[5px] rounded-full bg-red" animate={{ opacity: [1, 0.25] }} transition={{ repeat: Infinity, repeatType: "reverse", duration: 0.9 }} />
          <span className="tabular">{m.clock}</span>
        </span>
      </div>
      <div className="grid grid-cols-[1fr_auto_1fr] items-center gap-3">
        <TeamCell side={m.home} align="left" onClick={() => onTeamClick(m, "home")} />
        <span className="tabular text-[30px] leading-none font-bold tracking-tight text-label">
          {m.home.score} <span className="text-label-3">–</span> {m.away.score}
        </span>
        <TeamCell side={m.away} align="right" onClick={() => onTeamClick(m, "away")} />
      </div>
      {goals.length > 0 && (
        <div className="mt-2 grid grid-cols-2 gap-x-6 text-caption text-label-2">
          <div className="flex flex-col">{goals.filter((g) => g.side === "home").map((g, i) => <Scorer key={i} {...g} />)}</div>
          <div className="flex flex-col items-end">{goals.filter((g) => g.side === "away").map((g, i) => <Scorer key={i} {...g} />)}</div>
        </div>
      )}
    </div>
  );
}

function Scorer({ player, minute, own, penalty }: { player: string | null; minute: string; own: boolean; penalty: boolean }) {
  return (
    <span className="truncate" dir="auto">
      <SoccerBall size={10} weight="fill" className="mr-1 inline align-[-1px] text-label-3" />
      {player ?? "?"} {minute}
      {penalty ? ` (${t("Elfm.")})` : ""}
      {own ? ` (${t("ET")})` : ""}
    </span>
  );
}

function TeamCell({ side, align, onClick }: { side: Match["home"]; align: "left" | "right"; onClick: () => void }) {
  return (
    <div
      className={`-m-1 flex min-w-0 items-center gap-2 rounded-[10px] p-1 hover:bg-fill-2 ${align === "right" ? "flex-row-reverse text-right" : ""}`}
      onClick={(e) => {
        // The card itself also opens the detail: the team wins.
        e.stopPropagation();
        onClick();
      }}
    >
      <Crest side={side} size={30} />
      <span className="truncate text-body font-semibold text-label">{side.short}</span>
    </div>
  );
}

function Section({ title, list }: { title: string; list: Match[] }) {
  return (
    <div className="flex flex-col">
      <span className="px-1 pt-1 pb-1 text-caption font-medium text-label-3">{title}</span>
      {list.map((m) => (
        <Row key={m.key} m={m} />
      ))}
    </div>
  );
}

const day = () => new Intl.DateTimeFormat(locale(), { weekday: "short", day: "numeric", month: "short" });

function Row({ m }: { m: Match }) {
  const isToday = new Date(m.kickoff).toDateString() === new Date().toDateString();
  const right =
    m.state === "post" ? (
      <span className="tabular font-semibold text-label">
        {m.home.score} – {m.away.score} <span className="ml-1 font-normal text-label-3">{m.clock}</span>
      </span>
    ) : (
      <span className="tabular text-label-2">{isToday ? formatTime(m.kickoff) : `${day().format(m.kickoff)} · ${formatTime(m.kickoff)}`}</span>
    );
  return (
    <div
      className="flex h-8 cursor-pointer items-center gap-2 rounded-[8px] px-1 text-footnote hover:bg-fill-1"
      onClick={() => clickOrDouble(() => openDetail(m, "lineups"), () => openDetail(m, "table"))}
      title={t("Klick: Aufstellungen · Doppelklick: Tabelle")}
    >
      <Crest side={m.home} size={16} />
      <span className="truncate text-label">{m.home.short}</span>
      <span className="text-label-4">–</span>
      <span className="truncate text-label">{m.away.short}</span>
      <Crest side={m.away} size={16} />
      <span className="ml-1 truncate text-caption text-label-3">{m.leagueName || competitionName(m.league)}</span>
      <span className="flex-1" />
      {right}
    </div>
  );
}

/* ---------- follow ---------- */

function Follow() {
  const f = follow.use();
  return (
    <div className="-mx-1 flex h-full flex-col gap-3 overflow-y-auto px-1 pb-1">
      <div>
        <Label text={t("Wettbewerbe")} hint={t("Tippen: aus → meine Teams → alle Spiele")} />
        <div className="flex flex-wrap gap-1.5">
          {COMPETITIONS.map((c) => {
            const mode = f.competitions.find((x) => x.slug === c.slug)?.mode;
            return (
              <button
                key={c.slug}
                onClick={() => cycleCompetition(c.slug)}
                className={`pressable flex h-7 items-center gap-1 rounded-full px-3 text-caption font-medium ${
                  mode === "all" ? "bg-accent text-black" : mode === "teams" ? "bg-fill-3 text-label" : "bg-fill-1 text-label-3"
                }`}
                title={c.name}
              >
                {c.short}
                {mode === "all" && <span className="opacity-70">· {t("alle")}</span>}
              </button>
            );
          })}
        </div>
      </div>

      <div>
        <Label text={t("Meine Teams")} />
        <div className="flex flex-wrap gap-1.5">
          {f.teams.map((tm) => (
            <span key={tm.key} className="flex h-7 items-center gap-1.5 rounded-full bg-fill-2 pr-1 pl-2 text-caption text-label">
              {tm.logo && <img src={tm.logo} alt="" className="size-4 object-contain" />}
              {tm.name}
              {tm.source === "sofa" && <span className="text-label-3">· SofaScore</span>}
              <button className="flex size-5 items-center justify-center rounded-full text-label-3 hover:text-label" onClick={() => toggleTeam(tm)} aria-label={t("Entfernen")}>
                <X size={10} weight="bold" />
              </button>
            </span>
          ))}
        </div>
        <TeamSearch />
      </div>

      <div>
        <Label text={t("Mit dem Stream synchronisieren")} hint={t("Wartet so viele Sekunden, damit ein Tor nicht vor dem Bild kommt")} />
        <div className="flex items-center gap-3">
          <input type="range" min={0} max={90} step={5} value={f.delay} onChange={(e) => updateFollow({ delay: Number(e.target.value) })} className="flex-1" />
          <span className="tabular w-12 text-right text-footnote text-label">{f.delay ? `${f.delay} s` : t("aus")}</span>
        </div>
      </div>
    </div>
  );
}

function Label({ text, hint }: { text: string; hint?: string }) {
  return (
    <div className="mb-1.5 flex items-baseline gap-2">
      <span className="text-footnote font-semibold text-label">{text}</span>
      {hint && <span className="truncate text-caption text-label-3">{hint}</span>}
    </div>
  );
}

/** Search the teams of the followed competitions (ESPN), plus SofaScore for everything else. */
function TeamSearch() {
  const f = follow.use();
  const [q, setQ] = useState("");
  const [pool, setPool] = useState<TeamOption[]>([]);
  const [sofa, setSofa] = useState<SofaTeamOption[] | "loading" | "error" | null>(null);

  useEffect(() => {
    let alive = true;
    const slugs = [...new Set(f.competitions.map((c) => c.slug))];
    void Promise.allSettled(slugs.map(fetchTeams)).then((rs) => {
      const seen = new Set<string>();
      const all = rs.flatMap((r) => (r.status === "fulfilled" ? r.value : [])).filter((x) => !seen.has(x.id) && seen.add(x.id));
      if (alive) setPool(all);
    });
    return () => {
      alive = false;
    };
  }, [f.competitions]);

  useEffect(() => setSofa(null), [q]);

  const query = q.trim().toLowerCase();
  const hits = useMemo(() => (query.length < 2 ? [] : pool.filter((x) => x.name.toLowerCase().includes(query)).slice(0, 6)), [pool, query]);
  const followed = new Set(f.teams.map((x) => x.key));

  const add = (team: FollowedTeam) => {
    toggleTeam(team);
    setQ("");
  };

  return (
    <div className="mt-2 flex flex-col gap-1">
      <div className="flex h-8 items-center gap-2 rounded-[10px] bg-fill-1 px-3">
        <MagnifyingGlass size={13} weight="bold" className="text-label-3" />
        <input
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder={t("Team hinzufügen, z. B. Arsenal")}
          className="min-w-0 flex-1 bg-transparent text-footnote text-label outline-none placeholder:text-label-4"
        />
      </div>
      {hits.map((x) => (
        <button
          key={x.id}
          disabled={followed.has(`espn:${x.id}`)}
          onClick={() => add({ key: `espn:${x.id}`, source: "espn", id: x.id, name: x.name, logo: x.logo, league: x.league })}
          className="flex h-8 items-center gap-2 rounded-[8px] px-2 text-left text-footnote text-label hover:bg-fill-1 disabled:opacity-40"
        >
          {x.logo ? <img src={x.logo} alt="" className="size-4 object-contain" /> : <SoccerBall size={14} />}
          {x.name}
          <span className="text-caption text-label-3">{competitionName(x.league)}</span>
          <span className="flex-1" />
          <Plus size={12} weight="bold" className="text-label-3" />
        </button>
      ))}
      {query.length >= 2 && (
        <AnimatePresence mode="popLayout" initial={false}>
          {sofa === null ? (
            <motion.button
              key="ask"
              className="h-8 rounded-[8px] px-2 text-left text-caption text-link hover:bg-fill-1"
              onClick={() => {
                setSofa("loading");
                sofaSearchTeams(q.trim())
                  .then((r) => setSofa(r))
                  .catch(() => setSofa("error"));
              }}
              transition={fade}
            >
              {t("Nicht dabei? Auf SofaScore suchen (z. B. ägyptische Liga)")}
            </motion.button>
          ) : sofa === "loading" ? (
            <motion.span key="loading" className="px-2 text-caption text-label-3">{t("Suche …")}</motion.span>
          ) : sofa === "error" ? (
            <motion.span key="error" className="px-2 text-caption text-label-3">{t("SofaScore gerade nicht erreichbar")}</motion.span>
          ) : (
            <motion.div key="list" className="flex flex-col">
              {sofa.length === 0 && <span className="px-2 text-caption text-label-3">{t("Nichts gefunden")}</span>}
              {sofa.map((x) => (
                <button
                  key={x.id}
                  disabled={followed.has(`sofa:${x.id}`)}
                  onClick={() => add({ key: `sofa:${x.id}`, source: "sofa", id: x.id, name: x.name, logo: sofaLogo(x.id), league: null })}
                  className="flex h-8 items-center gap-2 rounded-[8px] px-2 text-left text-footnote text-label hover:bg-fill-1 disabled:opacity-40"
                >
                  <img src={sofaLogo(x.id)} alt="" className="size-4 object-contain" />
                  {x.name}
                  <span className="text-caption text-label-3">{x.country} · SofaScore</span>
                  <span className="flex-1" />
                  <Plus size={12} weight="bold" className="text-label-3" />
                </button>
              ))}
            </motion.div>
          )}
        </AnimatePresence>
      )}
    </div>
  );
}

