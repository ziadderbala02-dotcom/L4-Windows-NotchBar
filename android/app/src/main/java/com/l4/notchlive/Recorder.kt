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
import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
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

/** Screen recording from the notch: picture plus the sound the user chose (the phone's, the mic, both, none). */
object Recorder {
    /** When the recording started (0 = not recording). The notch shows a red dot and the time. */
    @Volatile
    var since = 0L

    fun stop(ctx: Context) {
        ctx.startService(Intent(ctx, RecordService::class.java).setAction(RecordService.STOP))
    }
}

/** Asks for the microphone (only if the sound setting needs it), then for the screen (Android asks every time). */
class RecordActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        L.load(this)
        val needsMic = Prefs(this).recAudio != "none"
        if (needsMic && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 2)
        } else {
            askScreen()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Refused: it still records, just without sound.
        askScreen()
    }

    private fun askScreen() {
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

/**
 * Records the screen to an MP4: H.264 video from a virtual display, plus AAC sound from the
 * phone itself (Android 10+, apps that allow it), the microphone, or both mixed.
 * The size is the largest the phone's encoder accepts (many stop at 1920 on the long side;
 * asking for more gave a file with no picture).
 */
class RecordService : Service() {
    companion object {
        const val STOP = "stop"
        private const val CH = "record"
        private const val ID = 7
        private const val RATE = 44_100
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var video: MediaCodec? = null
    private var audio: MediaCodec? = null
    private var inner: AudioRecord? = null
    private var mic: AudioRecord? = null
    private var muxer: MediaMuxer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private val threads = ArrayList<Thread>()

    @Volatile
    private var running = false
    private var tracksWanted = 1
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxing = false
    private val lock = Object()

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
        val mode = Prefs(this).recAudio
        val micOk = mode != "none" && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val useMic = micOk && (mode == "mic" || mode == "both")
        if (Build.VERSION.SDK_INT >= 30) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (useMic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(ID, n, type)
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(ID, n)
        }
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null || !begin(code, data, if (micOk) mode else "none")) {
            Toast.makeText(this, L.t("sc_failed"), Toast.LENGTH_SHORT).show()
            finish()
        }
        return START_NOT_STICKY
    }

    /** The biggest size (same shape as the screen, multiples of 16) the H.264 encoder takes. */
    private fun pickSize(sw: Int, sh: Int): Pair<Int, Int> {
        val caps = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .firstOrNull { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
                ?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)?.videoCapabilities
        } catch (e: Exception) {
            null
        }
        var k = minOf(1f, 1920f / maxOf(sw, sh), 1088f / minOf(sw, sh))
        while (k > 0.2f) {
            val w = ((sw * k).toInt() / 16) * 16
            val h = ((sh * k).toInt() / 16) * 16
            if (caps == null || caps.isSizeSupported(w, h)) return w to h
            k -= 0.05f
        }
        return 720 to ((720f * sh / sw).toInt() / 16) * 16
    }

    private fun begin(code: Int, data: Intent, mode: String): Boolean = try {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(code, data)
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = finish()
        }, null)
        val dm = resources.displayMetrics
        val (w, h) = pickSize(dm.widthPixels, dm.heightPixels)

        // Where it goes: Movies/NotchLive (the gallery).
        val name = "NotchLive_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())}.mp4"
        muxer = if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/NotchLive")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val u = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v) ?: throw IllegalStateException()
            uri = u
            val f = contentResolver.openFileDescriptor(u, "rw") ?: throw IllegalStateException()
            pfd = f
            MediaMuxer(f.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } else {
            MediaMuxer(File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), name).path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }

        // Video.
        val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val vc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        vc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = vc.createInputSurface()
        vc.start()
        video = vc

        // Sound, if asked and possible.
        if (mode == "internal" || mode == "both") inner = playbackRecord(p)
        if (mode == "mic" || mode == "both") mic = micRecord()
        val withSound = inner != null || mic != null
        tracksWanted = if (withSound) 2 else 1
        if (withSound) {
            val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            val ac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            ac.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            ac.start()
            audio = ac
        }

        display = p.createVirtualDisplay("NotchLive", w, h, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
        running = true
        threads += Thread({ drainVideo() }, "rec-video").apply { start() }
        if (withSound) threads += Thread({ pumpAudio() }, "rec-audio").apply { start() }
        Recorder.since = System.currentTimeMillis()
        true
    } catch (e: Exception) {
        false
    }

    private fun format(rate: Int) = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(rate)
        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
        .build()

    /** The phone's own sound (Android 10+). Apps can opt out (Netflix, calls): those stay silent. */
    @SuppressLint("MissingPermission")
    private fun playbackRecord(p: MediaProjection): AudioRecord? {
        if (Build.VERSION.SDK_INT < 29) return null
        return try {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(p)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            AudioRecord.Builder().setAudioFormat(format(RATE)).setAudioPlaybackCaptureConfig(cfg)
                .setBufferSizeInBytes(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4)
                .build().takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } catch (e: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun micRecord(): AudioRecord? = try {
        AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4)
            .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
    } catch (e: Exception) {
        null
    }

    /** Adds a track; the file starts once every expected track has its format. */
    private fun addTrack(f: MediaFormat, isVideo: Boolean) = synchronized(lock) {
        val m = muxer ?: return@synchronized
        val t = m.addTrack(f)
        if (isVideo) videoTrack = t else audioTrack = t
        val have = (if (videoTrack >= 0) 1 else 0) + (if (audioTrack >= 0) 1 else 0)
        if (have == tracksWanted) {
            m.start()
            muxing = true
        }
    }

    private fun write(track: Int, buf: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) = synchronized(lock) {
        if (muxing && track >= 0 && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
            buf.position(info.offset)
            buf.limit(info.offset + info.size)
            muxer?.writeSampleData(track, buf, info)
        }
    }

    private fun drain(codec: MediaCodec, isVideo: Boolean, untilEnd: Boolean) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val i = codec.dequeueOutputBuffer(info, 10_000)
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEnd) return
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> addTrack(codec.outputFormat, isVideo)
                i >= 0 -> {
                    codec.getOutputBuffer(i)?.let { write(if (isVideo) videoTrack else audioTrack, it, info) }
                    codec.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun drainVideo() {
        val vc = video ?: return
        try {
            while (running) drain(vc, true, false)
            vc.signalEndOfInputStream()
            drain(vc, true, true)
        } catch (e: Exception) {
            // Stopped.
        }
    }

    /** Reads the sound (mixing phone + mic when both), feeds the AAC encoder, writes its output. */
    private fun pumpAudio() {
        val ac = audio ?: return
        val n = 2048
        val a = ShortArray(n)
        val b = ShortArray(n)
        try {
            inner?.startRecording()
            mic?.startRecording()
            while (running) {
                val ra = inner?.read(a, 0, n) ?: 0
                val rb = mic?.read(b, 0, n) ?: 0
                val len = maxOf(ra, rb)
                if (len <= 0) continue
                for (k in 0 until len) {
                    val x = (if (k < ra) a[k].toInt() else 0) + (if (k < rb) b[k].toInt() else 0)
                    a[k] = x.coerceIn(-32768, 32767).toShort()
                }
                val i = ac.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val buf = ac.getInputBuffer(i) ?: continue
                    buf.clear()
                    val bytes = minOf(len * 2, buf.capacity())
                    buf.asShortBuffer().put(a, 0, bytes / 2)
                    ac.queueInputBuffer(i, 0, bytes, System.nanoTime() / 1000, 0)
                }
                drain(ac, false, false)
            }
            val i = ac.dequeueInputBuffer(10_000)
            if (i >= 0) ac.queueInputBuffer(i, 0, 0, System.nanoTime() / 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(ac, false, true)
        } catch (e: Exception) {
            // Stopped.
        }
    }

    private var finished = false

    private fun finish() {
        if (finished) return
        finished = true
        val was = Recorder.since > 0
        Recorder.since = 0L
        running = false
        for (t in threads) try {
            t.join(3_000)
        } catch (e: Exception) {
            // Gone.
        }
        for (r in listOf(inner, mic)) try {
            r?.stop()
            r?.release()
        } catch (e: Exception) {
            // Not started.
        }
        for (c in listOf(video, audio)) try {
            c?.stop()
            c?.release()
        } catch (e: Exception) {
            // Not started.
        }
        display?.release()
        try {
            projection?.stop()
        } catch (e: Exception) {
            // Stopped.
        }
        try {
            synchronized(lock) {
                if (muxing) muxer?.stop()
                muxer?.release()
            }
        } catch (e: Exception) {
            // Nothing written.
        }
        try {
            pfd?.close()
        } catch (e: Exception) {
            // Closed.
        }
        uri?.let { u ->
            try {
                if (muxing) {
                    contentResolver.update(u, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                } else {
                    contentResolver.delete(u, null, null)
                }
            } catch (e: Exception) {
                // Left pending.
            }
        }
        if (was) Toast.makeText(this, L.t(if (muxing) "rec_saved" else "sc_failed"), Toast.LENGTH_SHORT).show()
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
