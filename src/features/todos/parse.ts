/*
 * Quick entry: "call Ahmed tomorrow 5pm", "اتصل بأحمد بكرة الساعة 5", "gym every day 7am".
 * Pulls the date/time and repetition out of the text; whatever is left is the task.
 * Understands Egyptian Arabic and English. No match = no due date.
 */

export type Repeat = "none" | "daily" | "weekdays" | "weekly";

export interface Parsed {
  text: string;
  due: number | null;
  repeat: Repeat;
}

const AR_DIGITS = "٠١٢٣٤٥٦٧٨٩";
const normDigits = (s: string) => s.replace(/[٠-٩]/g, (d) => String(AR_DIGITS.indexOf(d)));

const WEEKDAYS: [RegExp, number][] = [
  [/\b(sun(day)?)\b|(ال)?[أا]حد/i, 0],
  [/\b(mon(day)?)\b|(ال)?[إا]?[ثت]ن[يى]ن/i, 1],
  [/\b(tue(s(day)?)?)\b|(ال)?[ثت]لا?[ثت]ا?ء?/i, 2],
  [/\b(wed(nesday)?)\b|(ال)?[أا]رب?ع(اء)?/i, 3],
  [/\b(thu(r(s(day)?)?)?)\b|(ال)?خميس/i, 4],
  [/\b(fri(day)?)\b|(ال)?جمع[ةه]/i, 5],
  [/\b(sat(urday)?)\b|(ال)?سبت/i, 6],
];

const WORD_NUM: Record<string, number> = { ساعة: 1, ساعه: 1, ساعتين: 2, نص: 0.5, ربع: 0.25 };

export function parseQuick(input: string, now = new Date()): Parsed {
  let s = ` ${normDigits(input.trim())} `;
  let repeat: Repeat = "none";
  let day: Date | null = null;
  let hour: number | null = null;
  let minute = 0;
  let relative: number | null = null;
  const cut = (re: RegExp) => {
    s = s.replace(re, " ");
  };

  // ---- repetition ----
  if (/\bevery ?day\b|\bdaily\b|كل يوم|يوميا|يومياً/i.test(s)) {
    repeat = "daily";
    cut(/\bevery ?day\b|\bdaily\b|كل يوم|يوميا|يومياً/gi);
  } else if (/\bweekdays\b|أيام الشغل|ايام الشغل/i.test(s)) {
    repeat = "weekdays";
    cut(/\bweekdays\b|أيام الشغل|ايام الشغل/gi);
  } else if (/\bevery ?week\b|\bweekly\b|كل [أا]سبوع|[أا]سبوعيا/i.test(s)) {
    repeat = "weekly";
    cut(/\bevery ?week\b|\bweekly\b|كل [أا]سبوع|[أا]سبوعيا/gi);
  }

  // ---- relative: "in 30 min", "بعد ساعتين", "بعد 20 دقيقة" ----
  const rel = s.match(/\bin\s+(\d+(?:\.\d+)?)\s*(min(ute)?s?|m|h(ou)?rs?|h)\b|بعد\s+(\d+(?:\.\d+)?)?\s*(دقيق[ةه]|دقايق|دقائق|ساع[ةه]|ساعات|ساعتين)/i);
  if (rel) {
    if (rel[1]) relative = Number(rel[1]) * (/^h/i.test(rel[2]) ? 60 : 1);
    else {
      const unit = rel[6];
      const n = rel[5] ? Number(rel[5]) : (WORD_NUM[unit] ?? 1);
      relative = unit === "ساعتين" ? 120 : n * (/ساع/.test(unit) ? 60 : 1);
    }
    cut(new RegExp(rel[0].replace(/[.*+?^${}()|[\]\\]/g, "\\$&"), "i"));
  } else if (/بعد نص ساع[ةه]/.test(s)) {
    relative = 30;
    cut(/بعد نص ساع[ةه]/g);
  }

  // ---- day ----
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  if (/\btomorrow\b|بكر[ةه]|غد[اً]?|الغد/i.test(s)) {
    day = new Date(today.getTime() + 86_400_000);
    cut(/\btomorrow\b|بكر[ةه]|غد[اً]?|الغد/gi);
  } else if (/\btoday\b|\btonight\b|النهارد[هة]|اليوم|الليلة/i.test(s)) {
    day = today;
    if (/\btonight\b|الليلة/i.test(s) && hour === null) hour = 20;
    cut(/\btoday\b|\btonight\b|النهارد[هة]|اليوم|الليلة/gi);
  } else {
    for (const [re, wd] of WEEKDAYS) {
      const m = s.match(new RegExp(`(?:^|\\s)(?:يوم\\s+|on\\s+)?(?:${re.source})(?=\\s|$)`, "i"));
      if (m) {
        const diff = (wd - today.getDay() + 7) % 7 || 7;
        day = new Date(today.getTime() + diff * 86_400_000);
        cut(new RegExp(m[0].replace(/[.*+?^${}()|[\]\\]/g, "\\$&"), "i"));
        break;
      }
    }
  }

  // ---- time: "5pm", "5:30 pm", "17:30", "at 9", "الساعة 5", "5 م", "8 الصبح" ----
  const time = s.match(
    /(?:\bat\s+|الساع[ةه]\s*)?(\d{1,2})(?::(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.|ص|م|صباحا|صباحاً|مساء|مساءً|الصبح|بالليل|العصر|المغرب|الضهر|الظهر)?(?=\s|$)/i,
  );
  // A bare number only counts as a time with "at"/"الساعة", a suffix or a colon (not "buy 3 apples").
  if (time && (time[2] || time[3] || /\bat\s|الساع/i.test(time[0]))) {
    let h = Number(time[1]);
    const m = time[2] ? Number(time[2]) : 0;
    const suf = (time[3] ?? "").toLowerCase();
    const pm = /^(pm|p\.m\.|م|مساء|مساءً|بالليل|العصر|المغرب)$/.test(suf);
    const am = /^(am|a\.m\.|ص|صباحا|صباحاً|الصبح)$/.test(suf);
    const noon = /^(الضهر|الظهر)$/.test(suf);
    if (h <= 23 && m <= 59) {
      if (pm && h < 12) h += 12;
      else if (am && h === 12) h = 0;
      else if (noon && h < 6) h += 12;
      else if (!pm && !am && !noon && !time[2] && h >= 1 && h <= 7) h += 12; // "الساعة 5" → 5 PM
      hour = h;
      minute = m;
      cut(new RegExp(time[0].replace(/[.*+?^${}()|[\]\\]/g, "\\$&"), "i"));
    }
  }

  // ---- combine ----
  let due: number | null = null;
  if (relative !== null) due = now.getTime() + relative * 60_000;
  else if (day || hour !== null) {
    const base = day ?? today;
    const d = new Date(base.getFullYear(), base.getMonth(), base.getDate(), hour ?? 9, hour === null ? 0 : minute);
    // A time that already passed today, without a day → tomorrow.
    if (!day && d.getTime() <= now.getTime()) d.setDate(d.getDate() + 1);
    due = d.getTime();
  } else if (repeat !== "none") {
    const d = new Date(today.getFullYear(), today.getMonth(), today.getDate(), 9, 0);
    if (d.getTime() <= now.getTime()) d.setDate(d.getDate() + 1);
    due = d.getTime();
  }

  const text = s
    .replace(/\s+(on|at|في|يوم)\s*$/i, " ")
    .replace(/\s{2,}/g, " ")
    .trim()
    .replace(/[،,\-–]+$/, "")
    .trim();
  return { text: text || input.trim(), due, repeat };
}

/** Next occurrence after `from` for a repeating task. */
export function nextDue(due: number, repeat: Repeat, from = Date.now()): number {
  const d = new Date(due);
  const step = () => {
    if (repeat === "daily") d.setDate(d.getDate() + 1);
    else if (repeat === "weekly") d.setDate(d.getDate() + 7);
    else if (repeat === "weekdays") {
      do d.setDate(d.getDate() + 1);
      while (d.getDay() === 5 || d.getDay() === 6); // Egypt: Friday and Saturday off
    }
  };
  step();
  while (d.getTime() <= from) step();
  return d.getTime();
}
