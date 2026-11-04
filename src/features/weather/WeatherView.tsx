import { useEffect, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { ArrowLeft, CircleNotch, Drop, MagnifyingGlass, MapPin, NavigationArrow, Wind } from "@phosphor-icons/react";
import { content, fade, spin } from "../../design/motion";
import { settings } from "../../settings/store";
import { Skeleton } from "../../ui/controls";
import { formatTemp, formatWind } from "../../lib/format";
import { locale, t } from "../../i18n";
import { choosePlace, describe, detectPlace, loadForecast, searchPlaces, type Forecast, type Place } from "./store";

/*
 * Weather via Open-Meteo (data and location detection: ./store.ts).
 *
 *   ☀ 22°   Vienna · Cloudy         feels 21° · Wind 7 km/h
 *   Mo  Tu  We  Th  Fr  Sa  Su
 *   ☁   ☀   🌧  …
 *   18° 21° 15°
 */
const weekday = () => new Intl.DateTimeFormat(locale(), { weekday: "short" });

export function WeatherView() {
  const place = settings.use().weather;
  const [data, setData] = useState<Forecast | null>(null);
  const [error, setError] = useState(false);
  const [searching, setSearching] = useState(false);
  const [detectFailed, setDetectFailed] = useState(false);
  /** A day tapped in the week row: its hours replace the overview. */
  const [day, setDay] = useState<string | null>(null);

  // First start without a location: detect via IP; if that fails, show the search.
  useEffect(() => {
    if (!place) void detectPlace().then((ok) => setDetectFailed(!ok));
  }, [place]);

  useEffect(() => {
    if (!place) return;
    let alive = true;
    setError(false);
    loadForecast(place.lat, place.lon)
      .then((d) => alive && setData(d))
      .catch(() => alive && setError(true));
    return () => {
      alive = false;
    };
  }, [place]);

  return (
    <div className="relative flex h-full flex-col px-5 pt-1 pb-2">
      <AnimatePresence mode="popLayout" initial={false}>
        {searching || (!place && detectFailed) ? (
          <motion.div key="search" className="h-full" initial={{ opacity: 0 }} animate={{ opacity: 1, transition: content.enter }} exit={{ opacity: 0, transition: content.exit }}>
            <PlaceSearch onDone={() => setSearching(false)} canCancel={!!place} />
          </motion.div>
        ) : error ? (
          <motion.div key="error" className="flex h-full items-center justify-center text-body text-label-3" initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={fade}>
            {t("Wetter gerade nicht erreichbar")}
          </motion.div>
        ) : !data || !place ? (
          <motion.div key="loading" className="flex flex-col gap-3" exit={{ opacity: 0, transition: content.exit }}>
            <Skeleton className="h-10 w-48" />
            <Skeleton className="h-20 w-full" />
          </motion.div>
        ) : day ? (
          <motion.div key={`day-${day}`} className="h-full" initial={{ opacity: 0, filter: "blur(4px)" }} animate={{ opacity: 1, filter: "blur(0px)", transition: content.enter }} exit={{ opacity: 0, transition: content.exit }}>
            <DayHours data={data} date={day} onBack={() => setDay(null)} />
          </motion.div>
        ) : (
          <motion.div key="data" className="flex h-full flex-col justify-between" initial={{ opacity: 0, filter: "blur(4px)" }} animate={{ opacity: 1, filter: "blur(0px)", transition: content.enter }}>
            <Current data={data} name={place.name} auto={!!place.auto} onChangePlace={() => setSearching(true)} />
            <div className="grid grid-cols-7 gap-1">
              {data.daily.map((d, i) => {
                const { icon: I, text } = describe(d.code);
                return (
                  <button key={d.date} onClick={() => setDay(d.date)} className={`pressable flex flex-col items-center gap-1 rounded-[10px] py-1.5 hover:bg-fill-2 ${i === 0 ? "bg-fill-1" : ""}`} title={text}>
                    <span className="text-caption text-label-3">{i === 0 ? t("Heute") : weekday().format(new Date(`${d.date}T12:00:00`)).replace(".", "")}</span>
                    <I size={18} weight="fill" className="text-label-2" />
                    <span className="tabular text-caption font-semibold text-label">{formatTemp(d.max)}</span>
                    <span className="tabular -mt-1 text-caption text-label-3">{formatTemp(d.min)}</span>
                  </button>
                );
              })}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}

/** Strong wind from this gust speed on (km/h). */
const STORM_GUST = 50;

/**
 * One day, hour by hour (from now on, for today):
 *
 *   ← Thursday            rain 14–17 · thunder at 16
 *   15   16   17   18 …
 *   🌧   ⛈    🌧   ☁
 *   19°  18°  18°  17°
 *   60%  80%  55%
 * Rain, thunder and strong wind hours stand out in color.
 */
function DayHours({ data, date, onBack }: { data: Forecast; date: string; onBack: () => void }) {
  const nowHour = new Date();
  const hours = data.hourly.filter((h) => h.time.startsWith(date) && new Date(h.time).getTime() >= nowHour.getTime() - 3600_000);
  const kind = (h: Forecast["hourly"][number]): string | null =>
    h.code >= 95 ? "storm" : (h.code >= 51 && h.code <= 67) || (h.code >= 80 && h.code <= 82) ? "rain" : h.code >= 71 && h.code <= 86 ? "snow" : h.gust >= STORM_GUST ? "wind" : null;
  const tint: Record<string, string> = { storm: "rgba(191,90,242,0.22)", rain: "rgba(10,132,255,0.2)", snow: "rgba(100,210,255,0.18)", wind: "rgba(255,159,10,0.2)" };
  const label: Record<string, string> = { storm: t("Gewitter"), rain: t("Regen"), snow: t("Schnee"), wind: t("Starker Wind") };
  const alerts = [...new Set(hours.map(kind).filter((k): k is string => !!k))];
  const dayName = date === data.daily[0]?.date ? t("Heute") : new Intl.DateTimeFormat(locale(), { weekday: "long" }).format(new Date(`${date}T12:00:00`));
  const hourFmt = new Intl.DateTimeFormat(locale(), { hour: "numeric", hour12: settings.get().units.clock === "12" });
  return (
    <div className="flex h-full flex-col gap-2">
      <div className="flex items-center gap-2">
        <button onClick={onBack} className="pressable flex size-7 items-center justify-center rounded-full bg-fill-1 text-label-2 hover:text-label" aria-label={t("Zurück")}>
          <ArrowLeft size={13} weight="bold" />
        </button>
        <span className="text-footnote font-semibold text-label">{dayName}</span>
        <span className="flex-1" />
        {alerts.map((k) => (
          <span key={k} className="rounded-full px-2 text-caption leading-[20px] font-medium text-label" style={{ background: tint[k] }}>
            {label[k]}
          </span>
        ))}
      </div>
      <div className="-mx-1 flex min-h-0 flex-1 gap-1 overflow-x-auto px-1 pb-1 [scrollbar-width:thin]">
        {hours.map((h) => {
          const { icon: I, text } = describe(h.code, h.day);
          const k = kind(h);
          return (
            <div key={h.time} title={text} className="flex w-[46px] shrink-0 flex-col items-center justify-between rounded-[10px] py-2" style={{ background: k ? tint[k] : "var(--color-fill-1)" }}>
              <span className="text-caption text-label-3">{hourFmt.format(new Date(h.time))}</span>
              <I size={18} weight="fill" className="text-label-2" />
              <span className="tabular text-footnote font-semibold text-label">{formatTemp(h.temp)}</span>
              <span className="tabular text-[10px] text-label-3">{h.rain >= 10 ? `${h.rain}%` : k === "wind" ? `${Math.round(h.gust)}` : " "}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function Current({ data, name, auto, onChangePlace }: { data: Forecast; name: string; auto: boolean; onChangePlace: () => void }) {
  const { icon: I, text } = describe(data.current.code, data.current.day);
  const today = data.daily[0];
  return (
    <div className="flex items-center gap-3">
      <I size={38} weight="fill" className="text-label" />
      <span className="tabular text-[34px] leading-none font-semibold tracking-[-0.03em]">{formatTemp(data.current.temp)}</span>
      <div className="min-w-0">
        <button onClick={onChangePlace} className="flex items-center gap-1 text-footnote font-medium text-label hover:text-label-2" title={t("Ort ändern")}>
          {auto ? <NavigationArrow size={11} weight="fill" /> : <MapPin size={11} weight="fill" />} {name}
        </button>
        <div className="text-caption text-label-3">{text}</div>
      </div>
      <div className="ml-auto flex flex-col items-end gap-0.5 text-caption text-label-3">
        <span>{t("gefühlt {n}", { n: formatTemp(data.current.feels) })}</span>
        <span className="flex items-center gap-1"><Wind size={11} weight="bold" /> {formatWind(data.current.wind)}</span>
        {today && today.rain > 0 && <span className="flex items-center gap-1"><Drop size={11} weight="fill" /> {today.rain} %</span>}
      </div>
    </div>
  );
}

/** Location search; also used in the settings (`compact`). Arrow = back to automatic via IP. */
export function PlaceSearch({ onDone, canCancel, compact = false }: { onDone: () => void; canCancel: boolean; compact?: boolean }) {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<Place[]>([]);
  const [locating, setLocating] = useState(false);

  // Debounce the search so not every keystroke sends a request.
  useEffect(() => {
    const q = query.trim();
    if (q.length < 2) {
      setResults([]);
      return;
    }
    const id = setTimeout(() => {
      searchPlaces(q)
        .then(setResults)
        .catch(() => setResults([]));
    }, 250);
    return () => clearTimeout(id);
  }, [query]);

  return (
    <div className={`flex flex-col gap-2 ${compact ? "" : "h-full"}`}>
      <div className={`flex h-9 items-center gap-2 rounded-[12px] px-3 ${compact ? "bg-fill-2" : "bg-fill-1"}`}>
        <MagnifyingGlass size={14} weight="bold" className="text-label-3" />
        <input
          autoFocus
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder={t("Ort suchen, z. B. Berlin")}
          className="min-w-0 flex-1 bg-transparent text-body text-label outline-none placeholder:text-label-4"
        />
        <button
          title={t("Automatisch per IP")}
          aria-label={t("Automatisch per IP")}
          onClick={() => {
            setLocating(true);
            void detectPlace(true).then((ok) => {
              setLocating(false);
              if (ok) onDone();
            });
          }}
          className="pressable flex size-6 items-center justify-center text-label-3 hover:text-label"
        >
          {locating ? (
            <motion.span className="flex" animate={{ rotate: 360 }} transition={spin}>
              <CircleNotch size={13} weight="bold" />
            </motion.span>
          ) : (
            <NavigationArrow size={13} weight="fill" />
          )}
        </button>
        {canCancel && (
          <button onClick={onDone} className="text-caption text-label-3 hover:text-label-2">
            {t("Abbrechen")}
          </button>
        )}
      </div>
      <div className="flex flex-col">
        {results.map((p) => (
          <button
            key={`${p.lat},${p.lon}`}
            onClick={() => {
              choosePlace(p);
              onDone();
            }}
            className="flex h-8 items-center gap-2 rounded-[8px] px-3 text-left hover:bg-fill-1"
          >
            <MapPin size={12} weight="fill" className="text-label-3" />
            <span className="text-footnote text-label">{p.name}</span>
            <span className="truncate text-caption text-label-3">{p.detail}</span>
          </button>
        ))}
        {!compact && query.trim().length < 2 && <span className="px-3 pt-2 text-caption text-label-3">{t("Für welchen Ort soll das Wetter angezeigt werden?")}</span>}
      </div>
    </div>
  );
}
