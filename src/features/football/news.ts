import { invoke } from "@tauri-apps/api/core";
import { createStore } from "../../lib/store";
import { isNative } from "../../platform/native";
import { settings } from "../../settings/store";
import { getText } from "./espn";
import { news365, type NewsItem } from "./s365";
import { follow } from "./store";
import type { FollowedTeam } from "./types";

/*
 * Football news in Arabic, like the 365Scores app: the general football feed and the
 * Egyptian league from 365Scores, plus per followed team 365Scores and Google News (365
 * alone is thin for some clubs and mixes in namesakes). New headlines pop up in the
 * closed notch: every football headline, or only the followed teams' (setting). The
 * full list lives in the football tool.
 */

export interface TeamNews extends NewsItem {
  /** The followed team it's about, or null for general football news. */
  team: FollowedTeam | null;
}

export const news = createStore<TeamNews[]>([]);
/** The headline shown in the closed notch right now. */
export const newsNotice = createStore<TeamNews | null>(null);
export const NEWS_NOTICE_MS = 9000;

/** 365Scores returns only the newest few per call, so ask often enough not to miss any. */
const POLL_MS = 4 * 60_000;
/** More new headlines than this at once (after sleep, say): only the newest pop up. */
const MAX_BURST = 3;

/** Seen IDs, persisted so a restart doesn't replay old headlines. */
const seen = createStore<Record<string, number>>({}, { persist: "football-news-seen" });
seen.set((s) => Object.fromEntries(Object.entries(s).filter(([, at]) => Date.now() - at < 7 * 86_400_000)));

/** Search words per team where the bare name is ambiguous ("الأهلي" is several clubs). */
const QUERY: Record<string, { q: string; exclude?: string[] }> = {
  "s365:8200": { q: "النادي الأهلي", exclude: ["شباب الأهلي", "الأهلي السعودي", "أهلي جدة", "الأهلي القطري", "الأهلي البحريني", "الأهلي الليبي", "ود مدني"] },
};

function queryFor(t: FollowedTeam) {
  return QUERY[t.key] ?? { q: t.name };
}

async function googleNews(t: FollowedTeam): Promise<NewsItem[]> {
  const q = queryFor(t).q;
  const xml = await getText(`https://news.google.com/rss/search?q=${encodeURIComponent(q)}&hl=ar&gl=EG&ceid=EG:ar`);
  const doc = new DOMParser().parseFromString(xml, "text/xml");
  return [...doc.querySelectorAll("item")].slice(0, 15).map((it) => {
    const raw = it.querySelector("title")?.textContent ?? "";
    const source = it.querySelector("source")?.textContent ?? "Google News";
    // Google appends " - Source" to every title.
    const title = raw.endsWith(` - ${source}`) ? raw.slice(0, -(source.length + 3)) : raw;
    const link = it.querySelector("link")?.textContent ?? "";
    return { id: `g:${link}`, title, url: link, image: null, at: Date.parse(it.querySelector("pubDate")?.textContent ?? "") || 0, source };
  });
}

/** Keep headlines that are really about this team. */
function about(t: FollowedTeam, title: string): boolean {
  const { q, exclude = [] } = queryFor(t);
  if (exclude.some((x) => title.includes(x))) return false;
  const name = t.name.replace(/^(نادي|النادي)\s+/, "");
  return title.includes(name) || title.includes(q);
}

const norm = (s: string) => s.replace(/[^\p{L}\p{N}]/gu, "").slice(0, 40);

/** General football and the Egyptian league from 365Scores (no team attached). */
async function generalNews(): Promise<NewsItem[]> {
  const [a, b] = await Promise.allSettled([news365([], "sports=1"), news365([], "competitions=552")]);
  return [...(a.status === "fulfilled" ? a.value : []), ...(b.status === "fulfilled" ? b.value : [])];
}

async function pollNews() {
  const on = settings.get().tools.some((x) => x.id === "football" && x.enabled);
  const f = follow.get();
  const mode = newsMode();
  if (!on || !f.enabled) return;
  const perTeam = Promise.allSettled(
    f.teams.map(async (t) => {
      const [a, b] = await Promise.allSettled([t.source === "s365" ? news365([t.id]) : Promise.resolve([]), googleNews(t)]);
      const items = [...(a.status === "fulfilled" ? a.value : []), ...(b.status === "fulfilled" ? b.value : [])];
      return items.filter((n) => n.title && about(t, n.title)).map((n): TeamNews => ({ ...n, team: t }));
    }),
  );
  const [teamResults, general] = await Promise.all([perTeam, generalNews().catch(() => [] as NewsItem[])]);

  // A general headline about a followed team gets that team's crest.
  const tag = (n: NewsItem): TeamNews => ({ ...n, team: f.teams.find((t) => about(t, n.title)) ?? null });
  const firstRun = Object.keys(seen.get()).length === 0;
  const all: TeamNews[] = [];
  const titles = new Set<string>();
  for (const n of [...teamResults.flatMap((r) => (r.status === "fulfilled" ? r.value : [])), ...general.map(tag)]) {
    const k = norm(n.title);
    if (!n.title || titles.has(k)) continue;
    titles.add(k);
    all.push(n);
  }
  // Last two days, newest first, merged with what was already listed (365 only sends a few).
  const merged = [...all, ...news.get().filter((o) => !titles.has(norm(o.title)))];
  const fresh = merged.filter((n) => Date.now() - n.at < 2 * 86_400_000).sort((a, b) => b.at - a.at);
  news.set(fresh.slice(0, 80));

  const unseen = all.filter((n) => !seen.get()[n.id]);
  seen.set((s) => ({ ...s, ...Object.fromEntries(unseen.map((n) => [n.id, Date.now()])) }));
  // First run: what's already out counts as seen, no burst of popups.
  if (firstRun || mode === "off") return;
  const pop = unseen
    .filter((n) => mode === "all" || n.team)
    .filter((n) => Date.now() - n.at < 3 * 3600_000)
    .sort((a, b) => b.at - a.at)
    .slice(0, MAX_BURST)
    .reverse();
  queue.push(...pop);
  showNext();
}

/** "all" football headlines pop up, only the followed "teams", or "off". */
export function newsMode(): "all" | "teams" | "off" {
  const f = follow.get();
  return f.newsMode ?? (f.news === false ? "off" : "all");
}

const queue: TeamNews[] = [];
function showNext() {
  if (newsNotice.get() || !queue.length) return;
  const n = queue.shift()!;
  newsNotice.set(n);
  window.setTimeout(() => {
    if (newsNotice.get() === n) newsNotice.set(null);
    window.setTimeout(showNext, 1500);
  }, NEWS_NOTICE_MS);
}

export function refreshNews() {
  void pollNews().catch(() => {});
}

window.setTimeout(refreshNews, 8000);
window.setInterval(refreshNews, POLL_MS);

/** Open an article in the default browser. */
export function openNews(url: string) {
  if (isNative) void invoke("football_open", { url }).catch(() => {});
  else window.open(url, "_blank");
}
