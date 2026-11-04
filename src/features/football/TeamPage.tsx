import { useEffect, useState, type ReactNode } from "react";
import { AnimatePresence, motion } from "motion/react";
import { ArrowLeft, Star } from "@phosphor-icons/react";
import { content } from "../../design/motion";
import { createStore } from "../../lib/store";
import { formatTime } from "../../lib/format";
import { locale, t } from "../../i18n";
import { Crest } from "./LiveScore";
import { openDetail } from "./MatchDetail";
import { teamFixtures365, teamResults365 } from "./s365";
import { follow, toggleTeam } from "./store";
import type { Match, Side, Source } from "./types";

/*
 * A team's page (double click on a team), like the 365Scores app: its last results and
 * its next matches, and a ⭐ to add it to the favourites. 365Scores teams only; ESPN
 * teams (fallback) have no page.
 */

export const teamPage = createStore<{ side: Side; source: Source } | null>(null);

export function openTeam(side: Side, source: Source) {
  teamPage.set({ side, source });
}

const cache = new Map<string, { at: number; results: Match[]; fixtures: Match[] }>();

/** Full page for a team (back button + its matches). */
export function TeamPage() {
  const p = teamPage.use();
  if (!p) return null;
  return (
    <div className="flex h-full flex-col px-5 pt-1 pb-2">
      <TeamMatches side={p.side} source={p.source} onBack={() => teamPage.set(null)} />
    </div>
  );
}

/**
 * A team's last results and next matches, with a ⭐ to make it a favourite. Used on the
 * team page and in the "Matches" tab of a match's details.
 */
export function TeamMatches({ side, source, onBack, header }: { side: Side; source: Source; onBack?: () => void; header?: ReactNode }) {
  const [tab, setTab] = useState<"results" | "fixtures">("results");
  const [data, setData] = useState<{ results: Match[]; fixtures: Match[] } | "loading" | "error" | "none">("loading");
  const f = follow.use();

  useEffect(() => {
    if (source !== "s365") return setData("none");
    const key = side.id;
    const hit = cache.get(key);
    if (hit && Date.now() - hit.at < 5 * 60_000) return setData(hit);
    setData("loading");
    let alive = true;
    void Promise.allSettled([teamResults365(key), teamFixtures365(key)]).then(([r, fx]) => {
      const v = { results: r.status === "fulfilled" ? r.value : [], fixtures: fx.status === "fulfilled" ? fx.value : [] };
      if (r.status === "rejected" && fx.status === "rejected") return alive && setData("error");
      cache.set(key, { at: Date.now(), ...v });
      if (alive) setData(v);
    });
    return () => {
      alive = false;
    };
  }, [side.id, source]);

  const key = `${source}:${side.id}`;
  const fav = f.teams.some((x) => x.key === key);
  const list = typeof data === "string" ? [] : tab === "results" ? data.results : data.fixtures;

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="mb-2 flex items-center gap-2">
        {onBack && (
          <button className="pressable flex size-7 items-center justify-center rounded-full bg-fill-1 text-label-2 hover:text-label" onClick={onBack} aria-label={t("Zurück")}>
            <ArrowLeft size={13} weight="bold" />
          </button>
        )}
        {header ?? (
          <>
            <Crest side={side} size={24} />
            <span className="truncate text-title font-semibold text-label" dir="auto">
              {side.name}
            </span>
          </>
        )}
        {source === "s365" && (
          <button
            className={`pressable flex size-7 items-center justify-center rounded-full ${fav ? "text-yellow" : "text-label-3 hover:text-label-2"}`}
            title={fav ? t("Aus Favoriten entfernen") : t("Zu Favoriten")}
            onClick={() => toggleTeam({ key, source: "s365", id: side.id, name: side.name, logo: side.logo, league: null })}
          >
            <Star size={15} weight={fav ? "fill" : "bold"} />
          </button>
        )}
        <span className="flex-1" />
        <div className="flex rounded-full bg-fill-1 p-0.5">
          {(["results", "fixtures"] as const).map((k) => (
            <button
              key={k}
              onClick={() => setTab(k)}
              className={`h-6 rounded-full px-3 text-caption font-medium ${tab === k ? "bg-fill-3 text-label" : "text-label-3 hover:text-label-2"}`}
            >
              {k === "results" ? t("Ergebnisse") : t("Nächste Spiele")}
            </button>
          ))}
        </div>
      </div>
      <AnimatePresence mode="popLayout" initial={false}>
        <motion.div
          key={`${side.id}-${tab}`}
          className="-mx-1 min-h-0 flex-1 overflow-y-auto px-1 pb-1"
          initial={{ opacity: 0, filter: "blur(4px)" }}
          animate={{ opacity: 1, filter: "blur(0px)", transition: content.enter }}
          exit={{ opacity: 0, transition: content.exit }}
        >
          {data === "loading" ? (
            <Empty text={t("Lade Spiele …")} />
          ) : data === "none" ? (
            <Empty text={t("Für dieses Team gibt es keine Seite")} />
          ) : data === "error" ? (
            <Empty text={t("Ergebnisse gerade nicht erreichbar")} />
          ) : !list.length ? (
            <Empty text={tab === "results" ? t("Keine Ergebnisse") : t("Keine anstehenden Spiele")} />
          ) : (
            list.map((m) => <TeamRow key={m.key} m={m} teamId={side.id} />)
          )}
        </motion.div>
      </AnimatePresence>
    </div>
  );
}

const day = () => new Intl.DateTimeFormat(locale(), { weekday: "short", day: "numeric", month: "short" });

/** One match from the team's point of view: W/D/L badge for results, date and time for fixtures. */
function TeamRow({ m, teamId }: { m: Match; teamId: string }) {
  const home = m.home.id === teamId;
  const us = home ? m.home : m.away;
  const them = home ? m.away : m.home;
  const res = m.state === "post" ? (us.score > them.score ? "W" : us.score < them.score ? "L" : "D") : null;
  const badge = res === "W" ? "bg-green text-black" : res === "L" ? "bg-red text-white" : "bg-fill-3 text-label";
  return (
    <div className="flex h-9 cursor-pointer items-center gap-2 rounded-[8px] px-1 text-footnote hover:bg-fill-1" onClick={() => {
        teamPage.set(null);
        openDetail(m, "lineups");
      }}>
      {res ? (
        <span className={`flex size-5 shrink-0 items-center justify-center rounded-[5px] text-caption font-bold ${badge}`}>{t(res === "W" ? "S" : res === "L" ? "N" : "U")}</span>
      ) : (
        <span className="w-5 shrink-0" />
      )}
      <span className="w-6 shrink-0 text-caption text-label-3">{home ? t("H") : t("A")}</span>
      <Crest side={them} size={18} />
      <span className="truncate text-label" dir="auto">
        {them.short}
      </span>
      <span className="ml-1 truncate text-caption text-label-3" dir="auto">
        {m.leagueName}
      </span>
      <span className="flex-1" />
      {m.state === "post" ? (
        <span className="tabular font-semibold text-label">
          {us.score} – {them.score}
        </span>
      ) : m.state === "in" ? (
        <span className="tabular font-semibold text-red">
          {us.score} – {them.score} · {m.clock}
        </span>
      ) : (
        <span className="tabular text-caption text-label-2">
          {day().format(m.kickoff)} · {formatTime(m.kickoff)}
        </span>
      )}
    </div>
  );
}

function Empty({ text }: { text: string }) {
  return <div className="flex h-full items-center justify-center px-6 text-center text-body text-label-3">{text}</div>;
}
