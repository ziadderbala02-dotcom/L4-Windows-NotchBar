import { useEffect, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { ArrowClockwise, Check, MagnifyingGlass, Newspaper, Plus, SlidersHorizontal, SoccerBall, Trash, X } from "@phosphor-icons/react";
import { clearNews, news, newsMode, openNews, refreshNews } from "./news";
import { content } from "../../design/motion";
import { locale, t } from "../../i18n";
import { formatAgo, formatTime } from "../../lib/format";
import { competitionName, fetchTeamSchedule } from "./espn";
import { sofaTeamMatches } from "./sofa";
import { COMPETITIONS_365, search365, teamGames365, type TeamOption365 } from "./s365";
import { Crest } from "./LiveScore";
import { MatchDetail, clickOrDouble, detail, onTeamClick, openDetail } from "./MatchDetail";
import { cycleCompetition, fallback, follow, refreshScores, scores, toggleTeam, updateFollow } from "./store";
import type { Match } from "./types";

/*
 * Football tool:
 *   Matches  live matches as big cards (with scorers), then today and the next fixtures
 *            of the followed teams.
 *   Follow   competitions (off / my teams / all matches), teams, stream delay.
 */
export function FootballView() {
  const [panel, setPanel] = useState<"matches" | "news" | "follow">("matches");
  const open = detail.use();
  const backup = fallback.use();
  const newsCount = news.use().length;
  if (open) return <MatchDetail />;
  return (
    <div className="relative flex h-full flex-col px-5 pt-1 pb-2">
      <div className="mb-2 flex items-center gap-2">
        {panel === "follow" ? (
          <span className="text-footnote font-semibold text-label">{t("Folgen")}</span>
        ) : (
          <div className="flex rounded-full bg-fill-1 p-0.5">
            {(["matches", "news"] as const).map((k) => (
              <button
                key={k}
                onClick={() => setPanel(k)}
                className={`flex h-6 items-center gap-1.5 rounded-full px-3 text-caption font-medium ${panel === k ? "bg-fill-3 text-label" : "text-label-3 hover:text-label-2"}`}
              >
                {k === "matches" ? <SoccerBall size={12} weight="fill" /> : <Newspaper size={12} weight="fill" />}
                {k === "matches" ? t("Spiele") : t("Nachrichten")}
              </button>
            ))}
          </div>
        )}
        <span className="flex-1" />
        {backup.active && panel !== "follow" && (
          <span className="rounded-full bg-fill-1 px-2 py-0.5 text-caption text-yellow" title={t("365Scores antwortet gerade nicht, die Daten kommen solange von ESPN")}>
            {t("ESPN (Ersatz)")}
          </span>
        )}
        {panel === "news" && newsCount > 0 && (
          <button
            className="pressable flex h-7 items-center gap-1.5 rounded-full bg-fill-1 px-3 text-caption font-medium text-label-2 hover:text-label"
            onClick={clearNews}
          >
            <Trash size={12} weight="bold" />
            {t("Alle löschen")}
          </button>
        )}
        {panel !== "follow" && (
          <button
            className="pressable flex size-7 items-center justify-center rounded-full text-label-3 hover:text-label"
            title={t("Aktualisieren")}
            onClick={panel === "news" ? refreshNews : refreshScores}
          >
            <ArrowClockwise size={14} weight="bold" />
          </button>
        )}
        <button
          className="pressable flex h-7 items-center gap-1.5 rounded-full bg-fill-1 px-3 text-caption font-medium text-label-2 hover:text-label"
          onClick={() => setPanel(panel === "follow" ? "matches" : "follow")}
        >
          {panel !== "follow" ? <SlidersHorizontal size={12} weight="bold" /> : <Check size={12} weight="bold" />}
          {panel !== "follow" ? t("Teams & Ligen") : t("Fertig")}
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
          {panel === "matches" ? <Matches /> : panel === "news" ? <NewsList /> : <Follow />}
        </motion.div>
      </AnimatePresence>
    </div>
  );
}

/* ---------- news ---------- */

function NewsList() {
  const list = news.use();
  if (!list.length) return <div className="flex h-full items-center justify-center text-body text-label-3">{t("Keine neuen Nachrichten")}</div>;
  return (
    <div className="-mx-1 flex h-full flex-col overflow-y-auto px-1 pb-1" dir="rtl">
      {list.map((n) => (
        <button key={n.id} onClick={() => openNews(n.url)} className="flex min-h-11 items-center gap-2.5 rounded-[10px] px-2 py-1.5 text-right hover:bg-fill-1">
          {n.team?.logo ? <img src={n.team.logo} alt="" className="size-5 shrink-0 object-contain" /> : <Newspaper size={16} weight="fill" className="shrink-0 text-label-3" />}
          <span className="min-w-0 flex-1">
            <span className="line-clamp-2 text-footnote text-label">{n.title}</span>
            <span className="text-caption text-label-3">
              {n.source} · <bdi>{formatAgo(Date.now() - n.at)}</bdi>
            </span>
          </span>
        </button>
      ))}
    </div>
  );
}

/* ---------- matches ---------- */

const upcomingCache: { at: number; key: string; list: Match[] } = { at: 0, key: "", list: [] };

/** Next fixtures of the followed teams (ESPN schedules, 365Scores, SofaScore), cached for 30 min. */
function useUpcoming(): Match[] {
  const { teams } = follow.use();
  const key = teams.map((t) => t.key).join(",");
  const [list, setList] = useState<Match[]>(upcomingCache.key === key ? upcomingCache.list : []);
  useEffect(() => {
    if (upcomingCache.key === key && Date.now() - upcomingCache.at < 30 * 60_000) return;
    let alive = true;
    const ids365 = teams.filter((tm) => tm.source === "s365").map((tm) => tm.id);
    const jobs = [
      teamGames365(ids365),
      ...teams.map((tm) =>
        tm.source === "espn" && tm.league ? fetchTeamSchedule(tm.league, tm.id) : tm.source === "sofa" ? sofaTeamMatches(tm.id) : Promise.resolve([] as Match[]),
      ),
    ];
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
  // "Today" = kicked off or kicking off today (365Scores also sends the last weeks' results).
  const todayStr = new Date().toDateString();
  const today = matches.filter((m) => m.state !== "in" && new Date(m.kickoff).toDateString() === todayStr).sort((a, b) => a.kickoff - b.kickoff);
  const recent = matches
    .filter((m) => m.state === "post" && new Date(m.kickoff).toDateString() !== todayStr && Date.now() - m.kickoff < 4 * 86_400_000)
    .sort((a, b) => b.kickoff - a.kickoff)
    .slice(0, 5);
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
      {recent.length > 0 && <Section title={t("Letzte Ergebnisse")} list={recent} />}
      {!live.length && !today.length && !later.length && !recent.length && (
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
        <Label text={t("Alle Spiele zeigen von")} hint={t("Spiele deiner Teams kommen immer, auch ohne Auswahl")} />
        <div className="flex flex-wrap gap-1.5">
          {COMPETITIONS_365.map((c) => {
            const mode = f.competitions.find((x) => x.slug === c.id)?.mode;
            return (
              <button
                key={c.id}
                onClick={() => cycleCompetition(c.id)}
                className={`pressable flex h-7 items-center gap-1 rounded-full px-3 text-caption font-medium ${
                  mode === "all" ? "bg-fill-3 text-label ring-1 ring-label-3" : "bg-fill-1 text-label-3"
                }`}
                title={c.name}
              >
                {c.short}
                {mode === "all" && <Check size={10} weight="bold" />}
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
              <span dir="auto">{tm.name}</span>
              {tm.source === "espn" && <span className="text-label-3">· ESPN</span>}
              <button className="flex size-5 items-center justify-center rounded-full text-label-3 hover:text-label" onClick={() => toggleTeam(tm)} aria-label={t("Entfernen")}>
                <X size={10} weight="bold" />
              </button>
            </span>
          ))}
        </div>
        <TeamSearch />
      </div>

      <div>
        <Label text={t("Nachrichten im Notch")} hint={t("Alle Schlagzeilen erscheinen im Tab Nachrichten")} />
        <div className="flex gap-1.5">
          {(["all", "teams", "off"] as const).map((k) => (
            <button
              key={k}
              onClick={() => updateFollow({ newsMode: k })}
              className={`pressable h-7 rounded-full px-3 text-caption font-medium ${newsMode() === k ? "bg-fill-3 text-label ring-1 ring-label-3" : "bg-fill-1 text-label-3 hover:text-label-2"}`}
            >
              {k === "all" ? t("Alle Fußball-News") : k === "teams" ? t("Nur meine Teams") : t("Aus")}
            </button>
          ))}
        </div>
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

/** Team search on 365Scores (Arabic or English names, every league incl. Egypt). */
function TeamSearch() {
  const f = follow.use();
  const [q, setQ] = useState("");
  const [hits, setHits] = useState<TeamOption365[] | "loading" | "error">([]);

  useEffect(() => {
    const query = q.trim();
    if (query.length < 2) return setHits([]);
    setHits("loading");
    const id = window.setTimeout(() => {
      search365(query)
        .then(setHits)
        .catch(() => setHits("error"));
    }, 350);
    return () => window.clearTimeout(id);
  }, [q]);

  const followed = new Set(f.teams.map((x) => x.key));
  return (
    <div className="mt-2 flex flex-col gap-1">
      <div className="flex h-8 items-center gap-2 rounded-[10px] bg-fill-1 px-3">
        <MagnifyingGlass size={13} weight="bold" className="text-label-3" />
        <input
          value={q}
          dir="auto"
          onChange={(e) => setQ(e.target.value)}
          placeholder={t("Team hinzufügen, z. B. الزمالك oder Arsenal")}
          className="min-w-0 flex-1 bg-transparent text-footnote text-label outline-none placeholder:text-label-4"
        />
      </div>
      {hits === "loading" && <span className="px-2 text-caption text-label-3">{t("Suche …")}</span>}
      {hits === "error" && <span className="px-2 text-caption text-label-3">{t("Suche gerade nicht erreichbar")}</span>}
      {Array.isArray(hits) && q.trim().length >= 2 && hits.length === 0 && <span className="px-2 text-caption text-label-3">{t("Nichts gefunden")}</span>}
      {Array.isArray(hits) &&
        hits.map((x) => (
          <button
            key={x.id}
            disabled={followed.has(`s365:${x.id}`)}
            onClick={() => {
              toggleTeam({ key: `s365:${x.id}`, source: "s365", id: x.id, name: x.name, logo: x.logo, league: null });
              setQ("");
            }}
            className="flex h-8 items-center gap-2 rounded-[8px] px-2 text-left text-footnote text-label hover:bg-fill-1 disabled:opacity-40"
          >
            <img src={x.logo} alt="" className="size-4 object-contain" />
            <span dir="auto">{x.name}</span>
            <span className="flex-1" />
            <Plus size={12} weight="bold" className="text-label-3" />
          </button>
        ))}
    </div>
  );
}

