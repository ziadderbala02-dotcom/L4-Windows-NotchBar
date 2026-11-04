import { useEffect, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { ArrowClockwise, Check, ListBullets, MagnifyingGlass, Newspaper, Plus, PushPin, SlidersHorizontal, SoccerBall, Star, Trash, X } from "@phosphor-icons/react";
import { clearNews, news, newsMode, openNews, refreshNews } from "./news";
import { content } from "../../design/motion";
import { locale, t } from "../../i18n";
import { formatAgo, formatTime } from "../../lib/format";
import { competitionName } from "./espn";
import { COMPETITIONS_365, competitionsGames365, search365, type TeamOption365 } from "./s365";
import { Crest } from "./LiveScore";
import { MatchDetail, detail, onTeamClick, openDetail } from "./MatchDetail";
import { cycleCompetition, fallback, follow, involvesFollowed, refreshScores, scores, toggleMain, togglePin, toggleTeam, updateFollow } from "./store";
import { TeamPage, teamPage } from "./TeamPage";
import type { Match } from "./types";

/*
 * Football tool:
 *   Matches  live matches as big cards (with scorers), then today and the next fixtures
 *            of the followed teams.
 *   Follow   competitions (off / my teams / all matches), teams, stream delay.
 */
export function FootballView() {
  const [panel, setPanel] = useState<"matches" | "all" | "news" | "follow">("matches");
  const open = detail.use();
  const backup = fallback.use();
  const newsCount = news.use().length;
  const team = teamPage.use();
  if (open) return <MatchDetail />;
  if (team) return <TeamPage />;
  return (
    <div className="relative flex h-full flex-col px-5 pt-1 pb-2">
      <div className="mb-2 flex items-center gap-2">
        {panel === "follow" ? (
          <span className="text-footnote font-semibold text-label">{t("Folgen")}</span>
        ) : (
          <div className="flex rounded-full bg-fill-1 p-0.5">
            {(["matches", "all", "news"] as const).map((k) => (
              <button
                key={k}
                onClick={() => setPanel(k)}
                className={`flex h-6 items-center gap-1.5 rounded-full px-3 text-caption font-medium ${panel === k ? "bg-fill-3 text-label" : "text-label-3 hover:text-label-2"}`}
              >
                {k === "matches" ? <Star size={12} weight="fill" /> : k === "all" ? <SoccerBall size={12} weight="fill" /> : <Newspaper size={12} weight="fill" />}
                {k === "matches" ? t("Meine Spiele") : k === "all" ? t("Alle") : t("Nachrichten")}
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
            onClick={panel === "news" ? refreshNews : () => (allCache.clear(), refreshScores())}
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
          {panel === "matches" ? <Matches onAll={() => setPanel("all")} /> : panel === "all" ? <AllResults /> : panel === "news" ? <NewsList /> : <Follow />}
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

/** Home: only the favourite teams' and pinned matches; everything else is under "All". */
function Matches({ onAll }: { onAll: () => void }) {
  const { matches, error, updated } = scores.use();
  const f = follow.use();
  const mine = matches.filter((m) => involvesFollowed(m) || (f.pinned ?? []).includes(m.key));
  const now = Date.now();
  const live = mine.filter((m) => m.state === "in");
  // "Today" = kicked off or kicking off today (365Scores also sends the last weeks' results).
  const todayStr = new Date().toDateString();
  const today = mine.filter((m) => m.state !== "in" && new Date(m.kickoff).toDateString() === todayStr).sort((a, b) => a.kickoff - b.kickoff);
  const later = mine
    .filter((m) => m.state === "pre" && m.kickoff > now && new Date(m.kickoff).toDateString() !== todayStr)
    .sort((a, b) => a.kickoff - b.kickoff)
    .slice(0, 8);
  const recent = mine
    .filter((m) => m.state === "post" && new Date(m.kickoff).toDateString() !== todayStr && now - m.kickoff < 7 * 86_400_000)
    .sort((a, b) => b.kickoff - a.kickoff)
    .slice(0, 6);

  if (!updated)
    return <div className="flex h-full items-center justify-center text-body text-label-3">{error ? t("Ergebnisse gerade nicht erreichbar") : t("Lade Spiele …")}</div>;

  return (
    <div className="-mx-1 flex h-full flex-col gap-2 overflow-y-auto px-1 pb-1">
      {live.map((m) => (
        <LiveCard key={m.key} m={m} several={live.length > 1} />
      ))}
      {today.length > 0 && <Section title={t("Heute")} list={today} />}
      {later.length > 0 && <Section title={t("Demnächst")} list={later} />}
      {recent.length > 0 && <Section title={t("Letzte Ergebnisse")} list={recent} />}
      {!live.length && !today.length && !later.length && !recent.length && (
        <div className="flex flex-1 items-center justify-center text-body text-label-3">{t("Gerade keine Spiele deiner Teams")}</div>
      )}
      <button className="pressable mx-auto mt-1 flex h-8 shrink-0 items-center gap-1.5 rounded-full bg-fill-1 px-4 text-caption font-medium text-label-2 hover:text-label" onClick={onAll}>
        <ListBullets size={12} weight="bold" />
        {t("Alle Ergebnisse")}
      </button>
    </div>
  );
}

/* ---------- all results, by competition ---------- */

const allCache = new Map<string, { at: number; list: Match[] }>();
const lastComp = { id: "552" };

function AllResults() {
  const [comp, setComp] = useState(lastComp.id);
  const [list, setList] = useState<Match[] | "loading" | "error">(allCache.get(comp)?.list ?? "loading");
  useEffect(() => {
    lastComp.id = comp;
    let alive = true;
    const load = () => {
      const hit = allCache.get(comp);
      if (hit && Date.now() - hit.at < 20_000) return setList(hit.list);
      competitionsGames365([comp])
        .then((l) => {
          allCache.set(comp, { at: Date.now(), list: l });
          if (alive) setList(l);
        })
        .catch(() => alive && setList((x) => (Array.isArray(x) ? x : "error")));
    };
    if (!allCache.get(comp)) setList("loading");
    load();
    const id = window.setInterval(load, 20_000);
    return () => {
      alive = false;
      window.clearInterval(id);
    };
  }, [comp]);

  const now = Date.now();
  const arr = Array.isArray(list) ? list : [];
  const live = arr.filter((m) => m.state === "in");
  const upcoming = arr.filter((m) => m.state === "pre" && m.kickoff > now - 3600_000).sort((a, b) => a.kickoff - b.kickoff).slice(0, 12);
  const results = arr.filter((m) => m.state === "post").sort((a, b) => b.kickoff - a.kickoff).slice(0, 12);

  return (
    <div className="flex h-full flex-col gap-2">
      <div className="-mx-1 flex shrink-0 gap-1.5 overflow-x-auto px-1 pb-1" dir="rtl">
        {COMPETITIONS_365.map((c) => (
          <button
            key={c.id}
            onClick={() => setComp(c.id)}
            title={c.name}
            className={`h-7 shrink-0 rounded-full px-3 text-caption font-medium ${comp === c.id ? "bg-fill-3 text-label" : "bg-fill-1 text-label-3 hover:text-label-2"}`}
          >
            {c.short}
          </button>
        ))}
      </div>
      <div className="-mx-1 flex min-h-0 flex-1 flex-col gap-1 overflow-y-auto px-1 pb-1">
        {list === "loading" ? (
          <div className="flex flex-1 items-center justify-center text-body text-label-3">{t("Lade Spiele …")}</div>
        ) : list === "error" ? (
          <div className="flex flex-1 items-center justify-center text-body text-label-3">{t("Ergebnisse gerade nicht erreichbar")}</div>
        ) : (
          <>
            {live.length > 0 && <Section title={t("Live")} list={live} pin />}
            {upcoming.length > 0 && <Section title={t("Demnächst")} list={upcoming} pin />}
            {results.length > 0 && <Section title={t("Ergebnisse")} list={results} />}
            {!live.length && !upcoming.length && !results.length && (
              <div className="flex flex-1 items-center justify-center text-body text-label-3">{t("Gerade keine Spiele in diesem Wettbewerb")}</div>
            )}
          </>
        )}
      </div>
    </div>
  );
}

function LiveCard({ m, several }: { m: Match; several: boolean }) {
  const goals = m.events.filter((e) => e.kind === "goal");
  const f = follow.use();
  const main = f.mainKey === m.key;
  const pinned = (f.pinned ?? []).includes(m.key);
  return (
    <div
      className="cursor-pointer rounded-[16px] bg-fill-1 px-4 py-3 hover:bg-fill-2"
      onClick={() => openDetail(m, "lineups")}
    >
      <div className="mb-2 flex items-center gap-2 text-caption text-label-3">
        <span dir="auto">{m.leagueName}</span>
        <span className="flex-1" />
        {pinned && <IconToggle on label={t("Nicht mehr verfolgen")} onClick={() => togglePin(m)} icon={<PushPin size={12} weight="fill" />} />}
        {several && (
          <IconToggle
            on={main}
            label={main ? t("Abwechseln") : t("Im Notch behalten")}
            onClick={() => toggleMain(m)}
            icon={<Star size={12} weight={main ? "fill" : "bold"} />}
          />
        )}
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

function IconToggle({ on, label, onClick, icon }: { on: boolean; label: string; onClick: () => void; icon: React.ReactNode }) {
  return (
    <button
      aria-label={label}
      title={label}
      className={`flex size-6 items-center justify-center rounded-full ${on ? "text-yellow" : "text-label-3 hover:text-label-2"}`}
      onClick={(e) => {
        e.stopPropagation();
        onClick();
      }}
    >
      {icon}
    </button>
  );
}

function Section({ title, list, pin = false }: { title: string; list: Match[]; pin?: boolean }) {
  return (
    <div className="flex flex-col">
      <span className="px-1 pt-1 pb-1 text-caption font-medium text-label-3">{title}</span>
      {list.map((m) => (
        <Row key={m.key} m={m} pin={pin} />
      ))}
    </div>
  );
}

const day = () => new Intl.DateTimeFormat(locale(), { weekday: "short", day: "numeric", month: "short" });

function Row({ m, pin = false }: { m: Match; pin?: boolean }) {
  const f = follow.use();
  const pinned = (f.pinned ?? []).includes(m.key);
  const fav = involvesFollowed(m);
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
      onClick={() => openDetail(m, "lineups")}
    >
      <Crest side={m.home} size={16} />
      <span className="truncate text-label">{m.home.short}</span>
      <span className="text-label-4">–</span>
      <span className="truncate text-label">{m.away.short}</span>
      <Crest side={m.away} size={16} />
      <span className="ml-1 truncate text-caption text-label-3">{m.leagueName || competitionName(m.league)}</span>
      <span className="flex-1" />
      {right}
      {(pin || pinned) && !fav && m.state !== "post" && (
        <IconToggle on={pinned} label={pinned ? t("Nicht mehr verfolgen") : t("Im Notch verfolgen")} onClick={() => togglePin(m)} icon={<PushPin size={12} weight={pinned ? "fill" : "bold"} />} />
      )}
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

