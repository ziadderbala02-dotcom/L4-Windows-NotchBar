import { useEffect, useState } from "react";
import { motion } from "motion/react";
import { CheckCircle, Record, Stop } from "@phosphor-icons/react";
import { content } from "../../design/motion";
import { LiveEdge } from "../../notch/liveEdges";
import { HeaderButton } from "../../notch/header";
import { t } from "../../i18n";
import { installFfmpeg, rec, revealRecording, saved, startRecording, stopRecording } from "./store";

function useElapsed(started: number | null): string {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 500);
    return () => window.clearInterval(id);
  }, []);
  const s = Math.max(0, Math.floor((now - (started ?? now)) / 1000));
  const h = Math.floor(s / 3600);
  const mm = String(Math.floor((s % 3600) / 60)).padStart(h ? 2 : 1, "0");
  const ss = String(s % 60).padStart(2, "0");
  return h ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
}

/** Closed notch while recording: pulsing red dot on the left, time and a stop button on the right. */
export function LiveRecord() {
  const r = rec.use();
  const time = useElapsed(r.started);
  return (
    <motion.div
      className="absolute inset-0 flex items-center justify-between px-[11px]"
      initial={{ opacity: 0, filter: "blur(4px)" }}
      animate={{ opacity: 1, filter: "blur(0px)", transition: content.enter }}
      exit={{ opacity: 0, filter: "blur(4px)", transition: content.exit }}
    >
      <LiveEdge side="left">
        <motion.span className="size-[9px] rounded-full bg-red" animate={{ opacity: [1, 0.3] }} transition={{ repeat: Infinity, repeatType: "reverse", duration: 0.8 }} />
      </LiveEdge>
      <LiveEdge side="right" className="flex items-center gap-1.5">
        <span className="tabular text-footnote font-semibold text-red">{time}</span>
        <button
          className="flex size-[18px] items-center justify-center rounded-full bg-red text-white"
          aria-label={t("Aufnahme beenden")}
          title={t("Aufnahme beenden")}
          onClick={(e) => {
            e.stopPropagation();
            void stopRecording();
          }}
        >
          <Stop size={9} weight="fill" />
        </button>
      </LiveEdge>
    </motion.div>
  );
}

export function useRecordLive(): boolean {
  return rec.use().recording;
}

/** Closed notch right after stopping: "Saved", click shows the file in Explorer. */
export function SavedNotice() {
  const s = saved.use();
  if (!s) return null;
  return (
    <motion.div
      className="absolute inset-0 flex cursor-pointer items-center justify-between px-[11px]"
      onClick={() => revealRecording(s.path)}
      initial={{ opacity: 0, filter: "blur(4px)" }}
      animate={{ opacity: 1, filter: "blur(0px)", transition: content.enter }}
      exit={{ opacity: 0, filter: "blur(4px)", transition: content.exit }}
    >
      <LiveEdge side="left">
        <CheckCircle size={16} weight="fill" className="text-green" />
      </LiveEdge>
      <LiveEdge side="right">
        <span className="text-caption font-semibold text-label-2">{t("Gespeichert · Zeigen")}</span>
      </LiveEdge>
    </motion.div>
  );
}

/** Record button in the open notch's header. Without ffmpeg it offers to install it. */
export function RecordButton({ onStarted }: { onStarted: () => void }) {
  const r = rec.use();
  if (r.recording)
    return (
      <HeaderButton label={t("Aufnahme beenden")} active onClick={() => void stopRecording()}>
        <Stop size={13} weight="fill" className="text-red" />
      </HeaderButton>
    );
  if (r.ffmpeg === false)
    return (
      <HeaderButton label={t("Für Bildschirmaufnahmen wird ffmpeg benötigt – jetzt installieren")} onClick={installFfmpeg}>
        <Record size={14} weight="bold" className="text-label-4" />
      </HeaderButton>
    );
  return (
    <HeaderButton
      label={t("Bildschirm aufnehmen")}
      onClick={() => {
        void startRecording();
        // Close the notch so the recording doesn't start with it open.
        onStarted();
      }}
    >
      <Record size={14} weight="fill" className="text-red" />
    </HeaderButton>
  );
}
