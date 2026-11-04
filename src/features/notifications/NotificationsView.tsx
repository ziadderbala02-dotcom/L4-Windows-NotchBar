import { useEffect } from "react";
import { motion } from "motion/react";
import { ArrowUpRight, Bell, ChatCircleDots, EnvelopeSimple, Trash, WhatsappLogo } from "@phosphor-icons/react";
import { content } from "../../design/motion";
import { formatAgo } from "../../lib/format";
import { t } from "../../i18n";
import { clearHistory, history, markRead, openApp, status, type Notif } from "./store";

/*
 * Notifications:
 *   closed notch   [icon] Sender · one line of text            (NotifNotice)
 *   hover          the whole message, "Open WhatsApp"          (NotifExpanded)
 *   tool           everything that came in, newest first        (NotificationsView)
 *
 * Replying from the notch isn't possible: Windows lets other apps read a toast, but not
 * use its reply box. Clicking opens the sending app instead.
 */

const APP_STYLE: Record<string, { icon: typeof Bell; color: string }> = {
  WhatsApp: { icon: WhatsappLogo, color: "#25d366" },
  Telegram: { icon: ChatCircleDots, color: "#2aabee" },
  Outlook: { icon: EnvelopeSimple, color: "#3b9cff" },
  Gmail: { icon: EnvelopeSimple, color: "#ea4335" },
  Discord: { icon: ChatCircleDots, color: "#7c87ff" },
  Teams: { icon: ChatCircleDots, color: "#8b8cff" },
  Slack: { icon: ChatCircleDots, color: "#e7a8ff" },
};

export function AppIcon({ app, size = 18 }: { app: string; size?: number }) {
  const s = APP_STYLE[app] ?? { icon: Bell, color: "var(--color-label-2)" };
  const I = s.icon;
  return <I size={size} weight="fill" style={{ color: s.color }} className="shrink-0" />;
}

export function NotifNotice({ n }: { n: Notif }) {
  return (
    <motion.div
      className="absolute inset-x-0 top-0 flex items-center gap-3 px-4"
      style={{ height: 54 }}
      initial={{ opacity: 0, filter: "blur(4px)" }}
      animate={{ opacity: 1, filter: "blur(0px)", transition: { ...content.enter, delay: 0.15 } }}
      exit={{ opacity: 0, filter: "blur(4px)", transition: content.exit }}
    >
      <motion.div className="flex" initial={{ scale: 0.3, opacity: 0 }} animate={{ scale: 1, opacity: 1, transition: { type: "spring", visualDuration: 0.4, bounce: 0.45, delay: 0.15 } }}>
        <AppIcon app={n.app} size={26} />
      </motion.div>
      <div className="min-w-0 flex-1">
        <div className="truncate text-footnote font-semibold text-label" dir="auto">
          {n.title}
        </div>
        <div className="truncate text-caption text-label-2" dir="auto">
          {n.body || n.app}
        </div>
      </div>
    </motion.div>
  );
}

export function NotifExpanded({ n, onOpen }: { n: Notif; onOpen: () => void }) {
  return (
    <div className="flex h-full cursor-pointer flex-col gap-2 px-6 pt-4 pb-4" onClick={onOpen}>
      <div className="flex items-center gap-2 text-caption text-label-3">
        <AppIcon app={n.app} size={16} />
        <span className="font-semibold text-label-2">{n.app}</span>
        <span>· {formatAgo(Date.now() - n.at)}</span>
      </div>
      <div className="truncate text-title font-semibold text-label" dir="auto">
        {n.title}
      </div>
      <div className="line-clamp-3 text-body text-label-2" dir="auto">
        {n.body}
      </div>
      <div className="mt-auto flex items-center gap-1 self-end text-caption font-medium text-label-2">
        {t("In {app} öffnen", { app: n.app })}
        <ArrowUpRight size={11} weight="bold" />
      </div>
    </div>
  );
}

export function NotificationsView() {
  const list = history.use();
  const st = status.use();
  // Opening the tool counts as having seen everything.
  useEffect(() => markRead(), [list.length]);
  return (
    <div className="flex h-full flex-col px-5 pt-1 pb-2">
      <div className="mb-2 flex items-center gap-2">
        <span className="text-footnote font-semibold text-label">{t("Verpasst")}</span>
        <span className="text-caption text-label-3">{list.length ? t("{n} Benachrichtigungen", { n: list.length }) : ""}</span>
        <span className="flex-1" />
        {list.length > 0 && (
          <button className="pressable flex h-7 items-center gap-1.5 rounded-full bg-fill-1 px-3 text-caption font-medium text-label-2 hover:text-label" onClick={clearHistory}>
            <Trash size={12} weight="bold" />
            {t("Alle löschen")}
          </button>
        )}
      </div>
      {!st.ok && <div className="mb-2 rounded-[10px] bg-fill-1 px-3 py-2 text-caption text-yellow">{t("Benachrichtigungen können gerade nicht gelesen werden")}</div>}
      {!list.length ? (
        <div className="flex flex-1 items-center justify-center px-8 text-center text-body text-label-3">{t("Neue Benachrichtigungen (WhatsApp, Mails …) erscheinen im Notch und bleiben hier")}</div>
      ) : (
        <div className="-mx-1 flex min-h-0 flex-1 flex-col overflow-y-auto px-1 pb-1">
          {list.map((n) => (
            <button key={`${n.id}-${n.at}`} onClick={() => openApp(n)} className="flex items-start gap-2.5 rounded-[10px] px-2 py-1.5 text-left hover:bg-fill-1">
              <span className="mt-0.5">
                <AppIcon app={n.app} size={18} />
              </span>
              <span className="min-w-0 flex-1">
                <span className="flex items-baseline gap-2">
                  <span className="truncate text-footnote font-semibold text-label" dir="auto">
                    {n.title}
                  </span>
                  <span className="ml-auto shrink-0 text-caption text-label-3">
                    {n.app} · {formatAgo(Date.now() - n.at)}
                  </span>
                </span>
                {n.body && (
                  <span className="line-clamp-2 block text-caption text-label-2" dir="auto">
                    {n.body}
                  </span>
                )}
              </span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
