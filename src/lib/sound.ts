/*
 * Alarm sounds via WebAudio, no audio files needed. Classic alarm patterns:
 * each pattern is a short phrase repeated up to the chosen duration.
 */
export type AlarmSound = "chime" | "bell" | "digital" | "radar" | "marimba" | "soft";
/** Seconds; 0 = once, -1 = until stopped (max. 2 minutes). */
export type AlarmDuration = 0 | 5 | 15 | 30 | -1;

export const alarmSounds: { value: AlarmSound; label: string }[] = [
  { value: "chime", label: "Gong" },
  { value: "bell", label: "Glocke" },
  { value: "digital", label: "Digitalwecker" },
  { value: "radar", label: "Radar" },
  { value: "marimba", label: "Marimba" },
  { value: "soft", label: "Sanft" },
];

interface Note {
  freq: number;
  at: number;
  /** Decay time in s. */
  decay: number;
  type?: OscillatorType;
  /** Overtones (multiples + relative volume) for a bell sound. */
  partials?: [number, number][];
  gain?: number;
}

/** One phrase per sound; `length` = gap until it repeats. */
const patterns: Record<AlarmSound, { notes: Note[]; length: number }> = {
  chime: {
    notes: [
      { freq: 880, at: 0, decay: 1.4 },
      { freq: 1318.5, at: 0.16, decay: 1.4 },
    ],
    length: 1.8,
  },
  bell: {
    notes: [{ freq: 660, at: 0, decay: 2.4, partials: [[2, 0.5], [3, 0.25], [4.2, 0.18], [5.4, 0.1]] }],
    length: 1.6,
  },
  digital: {
    notes: [0, 0.14, 0.28, 0.42].map((at) => ({ freq: 2048, at, decay: 0.09, type: "square" as const, gain: 0.35 })),
    length: 1.0,
  },
  radar: {
    notes: [0, 0.12, 0.24, 0.36].map((at, i) => ({ freq: 1046.5 * (1 + i * 0.06), at, decay: 0.16, type: "triangle" as const, gain: 0.8 })),
    length: 1.1,
  },
  marimba: {
    notes: [
      { freq: 523.25, at: 0, decay: 0.5, partials: [[4, 0.15]] },
      { freq: 659.25, at: 0.15, decay: 0.5, partials: [[4, 0.15]] },
      { freq: 783.99, at: 0.3, decay: 0.5, partials: [[4, 0.15]] },
      { freq: 1046.5, at: 0.45, decay: 0.8, partials: [[4, 0.15]] },
    ],
    length: 1.5,
  },
  soft: {
    notes: [
      { freq: 587.33, at: 0, decay: 1.8, gain: 0.7 },
      { freq: 880, at: 0.5, decay: 2.2, gain: 0.55 },
    ],
    length: 2.8,
  },
};

const MAX_RING_S = 120;
let ctx: AudioContext | null = null;
let stopCurrent: (() => void) | null = null;

/** Plays the alarm and returns a stop function. A new alarm stops the old one. */
export function playAlarm(sound: AlarmSound, volume: number, duration: AlarmDuration): () => void {
  stopCurrent?.();
  ctx ??= new AudioContext();
  const c = ctx;
  void c.resume();
  const master = c.createGain();
  master.gain.value = Math.max(0, Math.min(1, volume)) * 0.5;
  master.connect(c.destination);

  const { notes, length } = patterns[sound];
  const total = duration === 0 ? length : duration === -1 ? MAX_RING_S : duration;
  const repeats = Math.max(1, Math.floor(total / length));
  const start = c.currentTime + 0.02;
  const nodes: OscillatorNode[] = [];

  for (let r = 0; r < repeats; r++) {
    for (const n of notes) {
      const t = start + r * length + n.at;
      for (const [mult, rel] of [[1, 1], ...(n.partials ?? [])] as [number, number][]) {
        const osc = c.createOscillator();
        const g = c.createGain();
        osc.type = n.type ?? "sine";
        osc.frequency.value = n.freq * mult;
        const peak = 0.25 * (n.gain ?? 1) * rel;
        g.gain.setValueAtTime(0, t);
        g.gain.linearRampToValueAtTime(peak, t + 0.008);
        g.gain.exponentialRampToValueAtTime(0.0001, t + n.decay);
        osc.connect(g).connect(master);
        osc.start(t);
        osc.stop(t + n.decay + 0.05);
        nodes.push(osc);
      }
    }
  }

  const stop = () => {
    // Fade out softly instead of cutting off.
    master.gain.cancelScheduledValues(c.currentTime);
    master.gain.setTargetAtTime(0, c.currentTime, 0.05);
    window.setTimeout(() => {
      nodes.forEach((o) => {
        try {
          o.stop();
        } catch {
          // Already stopped.
        }
      });
      master.disconnect();
    }, 300);
    if (stopCurrent === stop) stopCurrent = null;
  };
  stopCurrent = stop;
  window.setTimeout(() => stopCurrent === stop && (stopCurrent = null), (repeats * length + 2) * 1000);
  return stop;
}

/** Is an alarm playing right now? */
export function alarmRinging(): boolean {
  return stopCurrent !== null;
}

export function stopAlarm() {
  stopCurrent?.();
}

/* ---------- event sounds (notifications, news, task alarms, goals) ---------- */

/** Short one-shot sounds; the alarm patterns above can be used as well (played once). */
type Shot = { notes: Note[]; noise?: { at: number; dur: number; gain: number } };
const shots: Record<string, Shot> = {
  pop: { notes: [{ freq: 1200, at: 0, decay: 0.12, gain: 0.8 }, { freq: 1800, at: 0.05, decay: 0.1, gain: 0.5 }] },
  ding: { notes: [{ freq: 1568, at: 0, decay: 0.9, partials: [[2, 0.3], [3, 0.12]] }] },
  tap: { notes: [{ freq: 990, at: 0, decay: 0.08, type: "triangle", gain: 0.9 }, { freq: 1320, at: 0.09, decay: 0.1, type: "triangle", gain: 0.7 }] },
  whistle: {
    notes: [
      { freq: 2950, at: 0, decay: 0.32, type: "triangle", gain: 0.5 },
      { freq: 2950, at: 0.38, decay: 0.32, type: "triangle", gain: 0.5 },
      { freq: 2950, at: 0.76, decay: 0.9, type: "triangle", gain: 0.6 },
    ],
  },
  // Whistle plus a crowd roar (filtered noise).
  goal: {
    notes: [{ freq: 2950, at: 0, decay: 0.5, type: "triangle", gain: 0.45 }],
    noise: { at: 0.15, dur: 2.6, gain: 0.5 },
  },
};

export const eventSounds: { value: string; label: string }[] = [
  { value: "pop", label: "Pop" },
  { value: "ding", label: "Ding" },
  { value: "tap", label: "Tippen" },
  { value: "whistle", label: "Pfiff" },
  { value: "goal", label: "Stadion" },
  ...alarmSounds.map((a) => ({ value: `alarm:${a.value}`, label: a.label })),
  { value: "custom", label: "Eigene Datei …" },
];

/** Play one built-in sound once. */
export function playShot(id: string, volume: number) {
  if (id.startsWith("alarm:")) return void playAlarm(id.slice(6) as AlarmSound, volume, 0);
  const shot = shots[id] ?? shots.pop;
  ctx ??= new AudioContext();
  const c = ctx;
  void c.resume();
  const master = c.createGain();
  master.gain.value = Math.max(0, Math.min(1, volume)) * 0.5;
  master.connect(c.destination);
  const start = c.currentTime + 0.02;
  for (const n of shot.notes) {
    const t = start + n.at;
    for (const [mult, rel] of [[1, 1], ...(n.partials ?? [])] as [number, number][]) {
      const osc = c.createOscillator();
      const g = c.createGain();
      osc.type = n.type ?? "sine";
      osc.frequency.value = n.freq * mult;
      const peak = 0.25 * (n.gain ?? 1) * rel;
      g.gain.setValueAtTime(0, t);
      g.gain.linearRampToValueAtTime(peak, t + 0.006);
      g.gain.exponentialRampToValueAtTime(0.0001, t + n.decay);
      osc.connect(g).connect(master);
      osc.start(t);
      osc.stop(t + n.decay + 0.05);
    }
  }
  if (shot.noise) {
    const { at, dur, gain } = shot.noise;
    const len = Math.floor(c.sampleRate * dur);
    const buf = c.createBuffer(1, len, c.sampleRate);
    const data = buf.getChannelData(0);
    for (let i = 0; i < len; i++) data[i] = Math.random() * 2 - 1;
    const src = c.createBufferSource();
    src.buffer = buf;
    const band = c.createBiquadFilter();
    band.type = "bandpass";
    band.frequency.value = 900;
    band.Q.value = 0.6;
    const g = c.createGain();
    const t = start + at;
    g.gain.setValueAtTime(0, t);
    g.gain.linearRampToValueAtTime(gain * 0.25, t + 0.35);
    g.gain.setValueAtTime(gain * 0.25, t + dur * 0.55);
    g.gain.linearRampToValueAtTime(0, t + dur);
    src.connect(band).connect(g).connect(master);
    src.start(t);
    src.stop(t + dur);
  }
  window.setTimeout(() => master.disconnect(), 4000);
}

/** Play an audio file (already loaded as a blob URL). */
export function playUrl(url: string, volume: number) {
  const a = new Audio(url);
  a.volume = Math.max(0, Math.min(1, volume));
  void a.play().catch(() => {});
}
