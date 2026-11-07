package com.l4.notchlive

import android.accessibilityservice.AccessibilityService
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** One slot of the always-on notch: a built-in action, an app, or a link. */
data class Slot(val type: String, val value: String, val label: String) {
    fun toJson(): JSONObject = JSONObject().put("t", type).put("v", value).put("l", label)
}

/**
 * The shortcut slots (8, all empty at first: the user fills them) and what each built-in does.
 * Built-ins favour things buried in the phone's menus over ones that already have a button.
 */
object Shortcuts {
    const val SLOTS = 8

    /** Built-in actions, in the order the picker lists them. */
    val ACTIONS = listOf(
        "record", "qr", "screenshot", "night", "awake", "torch",
        "media_play", "media_next", "media_prev", "ringer", "lock", "quick", "notifs", "recents",
        "dark", "wifi", "bluetooth", "data", "volume", "dnd", "battery", "alarm", "timer", "camera",
        "app_sounds", "saver", "hide", "app",
    )

    fun glyph(action: String): String = when (action) {
        "record" -> "⏺"
        "qr" -> "▦"
        "screenshot" -> "📸"
        "night" -> "🌙"
        "awake" -> "☕"
        "torch" -> "🔦"
        "media_play" -> "⏯"
        "media_next" -> "⏭"
        "media_prev" -> "⏮"
        "ringer" -> "📳"
        "lock" -> "🔒"
        "quick" -> "☰"
        "notifs" -> "🔔"
        "recents" -> "▣"
        "dark" -> "◐"
        "wifi" -> "📶"
        "bluetooth" -> "ᛒ"
        "data" -> "⇅"
        "volume" -> "🔊"
        "dnd" -> "⛔"
        "battery" -> "🔋"
        "alarm" -> "⏰"
        "timer" -> "⏱"
        "camera" -> "📷"
        "app_sounds" -> "🔕"
        "saver" -> "🍃"
        "hide" -> "🙈"
        else -> "⚙"
    }

    /** Actions that need the accessibility service (Android only lets it press these). */
    fun needsAccessibility(action: String) = action in setOf("screenshot", "lock", "quick", "notifs", "recents")

    fun load(ctx: Context): List<Slot?> {
        val raw = Prefs(ctx).shortcutsRaw
        val out = MutableList<Slot?>(SLOTS) { null }
        try {
            val a = JSONArray(raw)
            for (i in 0 until minOf(SLOTS, a.length())) {
                val o = a.optJSONObject(i) ?: continue
                out[i] = Slot(o.getString("t"), o.getString("v"), o.optString("l"))
            }
        } catch (e: Exception) {
            // Empty.
        }
        return out
    }

    fun save(ctx: Context, slots: List<Slot?>) {
        val a = JSONArray()
        for (s in slots) a.put(s?.toJson() ?: JSONObject.NULL)
        Prefs(ctx).shortcutsRaw = a.toString()
    }

    fun set(ctx: Context, index: Int, slot: Slot?) {
        val l = load(ctx).toMutableList()
        if (index in l.indices) l[index] = slot
        save(ctx, l)
    }

    fun label(ctx: Context, s: Slot): String = when (s.type) {
        "action" -> L.t("sc_${s.value}")
        else -> s.label
    }

    /* ---------- icons ---------- */

    private val appIcons = ConcurrentHashMap<String, Bitmap>()

    fun appIcon(ctx: Context, pkg: String): Bitmap? = appIcons[pkg] ?: try {
        val d = ctx.packageManager.getApplicationIcon(pkg)
        val b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 96, 96)
        d.draw(Canvas(b))
        appIcons[pkg] = b
        b
    } catch (e: Exception) {
        null
    }

    /** The site's icon, through Google's favicon service. */
    fun siteIconUrl(link: String): String {
        val host = try {
            Uri.parse(link).host ?: link
        } catch (e: Exception) {
            link
        }
        return "https://www.google.com/s2/favicons?domain=$host&sz=128"
    }

    fun icon(ctx: Context, s: Slot, onReady: () -> Unit): Bitmap? = when (s.type) {
        "app" -> appIcon(ctx, s.value)
        "link" -> Logos.get(siteIconUrl(s.value), onReady)
        else -> null
    }

    /* ---------- running a slot ---------- */

    /** Runs it; returns a short message to flash in the notch, or null. */
    fun run(ctx: Context, s: Slot): String? = when (s.type) {
        "app" -> {
            val i = ctx.packageManager.getLaunchIntentForPackage(s.value)
            if (i == null) L.t("sc_missing") else start(ctx, i)
        }
        "link" -> start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(fixLink(s.value))))
        else -> action(ctx, s.value)
    }

    fun fixLink(v: String): String = if (v.startsWith("http://") || v.startsWith("https://")) v else "https://$v"

    private fun start(ctx: Context, i: Intent): String? = try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (e: Exception) {
        L.t("sc_failed")
    }

    private fun global(a: Int): String? {
        val t = TapService.instance ?: return L.t("sc_need_access")
        return if (t.performGlobalAction(a)) null else L.t("sc_failed")
    }

    private fun media(ctx: Context, key: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val now = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0))
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0))
    }

    private fun action(ctx: Context, a: String): String? {
        val prefs = Prefs(ctx)
        return when (a) {
            "record" -> {
                if (Recorder.since > 0) {
                    Recorder.stop(ctx)
                    null
                } else {
                    start(ctx, Intent(ctx, RecordActivity::class.java))
                }
            }
            "qr" -> start(ctx, Intent(ctx, QrActivity::class.java))
            "screenshot" -> global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            "lock" -> global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            "quick" -> global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            "notifs" -> global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            "recents" -> global(AccessibilityService.GLOBAL_ACTION_RECENTS)
            "night" -> {
                Screen.toggleNight(ctx)
                L.t(if (Screen.nightOn) "sc_on" else "sc_off")
            }
            "awake" -> {
                Screen.toggleAwake(ctx)
                L.t(if (Screen.awakeOn) "sc_awake_on" else "sc_off")
            }
            "torch" -> torch(ctx)
            "media_play" -> {
                media(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                null
            }
            "media_next" -> {
                media(ctx, KeyEvent.KEYCODE_MEDIA_NEXT)
                null
            }
            "media_prev" -> {
                media(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                null
            }
            "ringer" -> {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                try {
                    if (am.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                        am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                        L.t("sc_vibrate")
                    } else {
                        am.ringerMode = AudioManager.RINGER_MODE_NORMAL
                        L.t("sc_ring")
                    }
                } catch (e: SecurityException) {
                    start(ctx, Intent(Settings.ACTION_SOUND_SETTINGS))
                }
            }
            "dark" -> start(ctx, Intent(Settings.ACTION_DISPLAY_SETTINGS))
            "wifi" -> start(ctx, Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS))
            "bluetooth" -> start(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            "data" -> start(ctx, Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS))
            "volume" -> start(ctx, Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS))
            "dnd" -> start(ctx, Intent("android.settings.ZEN_MODE_SETTINGS"))
            "battery" -> start(ctx, Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
            "alarm" -> start(ctx, Intent(AlarmClock.ACTION_SHOW_ALARMS))
            "timer" -> start(ctx, Intent(AlarmClock.ACTION_SHOW_TIMERS))
            "camera" -> start(ctx, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            "app_sounds" -> {
                prefs.sound = !prefs.sound
                L.t(if (prefs.sound) "sc_sounds_on" else "sc_sounds_off")
            }
            "saver" -> {
                prefs.speed = if (prefs.speed == "saver") "fast" else "saver"
                L.t(if (prefs.speed == "saver") "sc_saver_on" else "sc_saver_off")
            }
            "hide" -> null // LiveService hides the notch for an hour.
            else -> start(ctx, Intent(ctx, MainActivity::class.java))
        }
    }

    /* ---------- torch ---------- */

    @Volatile
    private var torchOn = false
    private var torchWatched = false

    private fun torch(ctx: Context): String? {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                ?: return L.t("sc_failed")
            if (!torchWatched) {
                torchWatched = true
                cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                        if (cameraId == id) torchOn = enabled
                    }
                }, null)
            }
            cm.setTorchMode(id, !torchOn)
            torchOn = !torchOn
            null
        } catch (e: Exception) {
            L.t("sc_failed")
        }
    }
}

/** Night filter (a warm dim layer over everything) and keep-awake, as tiny overlay windows. */
object Screen {
    @Volatile
    var nightOn = false
        private set

    @Volatile
    var awakeOn = false
        private set

    private var night: View? = null
    private var awake: View? = null
    private var nightWm: WindowManager? = null
    private var awakeWm: WindowManager? = null

    private fun host(ctx: Context): Pair<Context, Int> =
        TapService.instance?.let { it to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY }
            ?: (ctx to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)

    fun toggleNight(ctx: Context) {
        if (nightOn) {
            try {
                nightWm?.removeView(night)
            } catch (e: Exception) {
                // Gone.
            }
            night = null
            nightOn = false
            return
        }
        val (h, type) = host(ctx)
        val wm = h.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = View(h).apply { setBackgroundColor(0x66301800) }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        if (Build.VERSION.SDK_INT >= 30) p.fitInsetsTypes = 0
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        try {
            wm.addView(v, p)
            night = v
            nightWm = wm
            nightOn = true
        } catch (e: Exception) {
            nightOn = false
        }
    }

    fun toggleAwake(ctx: Context) {
        if (awakeOn) {
            try {
                awakeWm?.removeView(awake)
            } catch (e: Exception) {
                // Gone.
            }
            awake = null
            awakeOn = false
            return
        }
        val (h, type) = host(ctx)
        val wm = h.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = View(h)
        val p = WindowManager.LayoutParams(
            1, 1, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        )
        try {
            wm.addView(v, p)
            awake = v
            awakeWm = wm
            awakeOn = true
        } catch (e: Exception) {
            awakeOn = false
        }
    }
}
