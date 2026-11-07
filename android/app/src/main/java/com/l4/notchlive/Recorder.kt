package com.l4.notchlive

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.widget.Toast
import java.io.File

/** Screen recording from the notch: video only (sound would need the microphone permission). */
object Recorder {
    /** When the recording started (0 = not recording). The notch shows a red dot and the time. */
    @Volatile
    var since = 0L

    fun stop(ctx: Context) {
        ctx.startService(Intent(ctx, RecordService::class.java).setAction(RecordService.STOP))
    }
}

/** Asks Android for permission to capture the screen (it asks every time), then starts recording. */
class RecordActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        L.load(this)
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), 1)
        } catch (e: Exception) {
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data != null) {
            val i = Intent(this, RecordService::class.java).putExtra("code", resultCode).putExtra("data", data)
            try {
                startForegroundService(i)
            } catch (e: Exception) {
                Toast.makeText(this, L.t("sc_failed"), Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }
}

class RecordService : Service() {
    companion object {
        const val STOP = "stop"
        private const val CH = "record"
        private const val ID = 7
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var file: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            finish()
            return START_NOT_STICKY
        }
        L.load(this)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, L.t("sc_record"), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, RecordService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_ball)
            .setContentTitle(L.t("rec_on"))
            .setContentText(L.t("rec_stop_hint"))
            .setOngoing(true)
            .setContentIntent(stop)
            .addAction(Notification.Action.Builder(null, L.t("rec_stop"), stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(ID, n)
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null || !begin(code, data)) {
            finish()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun begin(code: Int, data: Intent): Boolean = try {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(code, data)
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = finish()
        }, null)
        val dm = resources.displayMetrics
        // Even sizes, at most 1080 wide, keep the screen's shape.
        val scale = minOf(1f, 1080f / dm.widthPixels)
        val w = ((dm.widthPixels * scale).toInt() / 2) * 2
        val h = ((dm.heightPixels * scale).toInt() / 2) * 2
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        r.setVideoSize(w, h)
        r.setVideoFrameRate(30)
        r.setVideoEncodingBitRate(8_000_000)
        val name = "NotchLive_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())}.mp4"
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/NotchLive")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val u = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v) ?: throw IllegalStateException()
            uri = u
            val f = contentResolver.openFileDescriptor(u, "w") ?: throw IllegalStateException()
            pfd = f
            r.setOutputFile(f.fileDescriptor)
        } else {
            val f = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), name)
            file = f
            r.setOutputFile(f.path)
        }
        r.prepare()
        display = p.createVirtualDisplay("NotchLive", w, h, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
        r.start()
        recorder = r
        Recorder.since = System.currentTimeMillis()
        true
    } catch (e: Exception) {
        false
    }

    private var finished = false

    private fun finish() {
        if (finished) return
        finished = true
        val was = Recorder.since > 0
        Recorder.since = 0L
        try {
            recorder?.stop()
        } catch (e: Exception) {
            // Stopped before any frame: nothing saved.
        }
        recorder?.release()
        display?.release()
        projection?.stop()
        try {
            pfd?.close()
        } catch (e: Exception) {
            // Closed.
        }
        uri?.let { u ->
            val v = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            try {
                contentResolver.update(u, v, null, null)
            } catch (e: Exception) {
                // Left pending.
            }
        }
        if (was) Toast.makeText(this, L.t("rec_saved"), Toast.LENGTH_SHORT).show()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        finish()
        super.onDestroy()
    }
}

/** Scans a QR code with Google's scanner (no camera permission needed); falls back to the camera app. */
class QrActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        L.load(this)
        try {
            com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(this).startScan()
                .addOnSuccessListener { b -> done(b.rawValue) }
                .addOnFailureListener { fallback() }
                .addOnCanceledListener { finish() }
        } catch (e: Throwable) {
            fallback()
        }
    }

    /** No Google Play services (some phones): most camera apps read QR codes themselves. */
    private fun fallback() {
        try {
            startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        } catch (e: Exception) {
            Toast.makeText(this, L.t("sc_failed"), Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    /** A link opens; anything else is copied and shown. */
    private fun done(text: String?) {
        if (text.isNullOrBlank()) {
            finish()
            return
        }
        if (text.startsWith("http://") || text.startsWith("https://")) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(text)))
            } catch (e: Exception) {
                // No browser.
            }
        } else {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("QR", text))
            Toast.makeText(this, L.f("qr_copied", text.take(80)), Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
