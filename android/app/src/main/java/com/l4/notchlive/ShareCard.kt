package com.l4.notchlive

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

/** The full-time card: a 1080×1080 picture of the final score and scorers, to share. */
object ShareCard {
    fun dir(ctx: Context) = File(ctx.cacheDir, "share").apply { mkdirs() }

    fun render(ctx: Context, m: Match): File {
        val w = 1080
        val bmp = Bitmap.createBitmap(w, w, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // Winner's colour fading into near-black.
        val lead = when {
            m.home.score > m.away.score -> m.home.color
            m.away.score > m.home.score -> m.away.color
            else -> 0xFF3F3F46.toInt()
        }
        val dim = Color.argb(255, Color.red(lead) / 3, Color.green(lead) / 3, Color.blue(lead) / 3)
        val bg = Paint().apply { shader = LinearGradient(0f, 0f, 0f, w.toFloat(), dim, 0xFF0B0C10.toInt(), Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, w.toFloat(), w.toFloat(), bg)

        val center = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val soft = Paint(center).apply {
            color = 0xFFC4C4CC.toInt()
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }

        soft.textSize = 40f
        c.drawText("${L.t("full_time")} · ${m.comp}", w / 2f, 150f, soft)

        // Crests and score.
        val crest = 230f
        val y = 260f
        listOf(true to 150f, false to (w - 150f - crest)).forEach { (home, x) ->
            val side = m.side(home)
            val logo = Logos.get(side.logo) {}
            if (logo != null) {
                c.drawBitmap(logo, null, RectF(x, y, x + crest, y + crest), Paint(Paint.FILTER_BITMAP_FLAG))
            } else {
                c.drawCircle(x + crest / 2, y + crest / 2, crest / 2.2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = side.color })
            }
            center.textSize = 44f
            c.drawText(side.short.take(18), x + crest / 2, y + crest + 70f, center)
        }
        center.textSize = 170f
        c.drawText("${m.home.score} - ${m.away.score}", w / 2f, y + crest / 2 + 60f, center)

        // Scorers, a column per team.
        soft.textSize = 36f
        for (home in listOf(true, false)) {
            val gs = m.events.filter { it.kind == "goal" && it.home == home }.take(5)
            gs.forEachIndexed { i, e ->
                val tag = if (e.own) L.t("own_goal") else if (e.pen) L.t("pen_short") else ""
                c.drawText("${e.player ?: ""}$tag ${e.minute}".trim(), if (home) 265f else w - 265f, 700f + i * 52f, soft)
            }
        }

        soft.textSize = 32f
        soft.color = 0xFF8B8B95.toInt()
        c.drawText("Notch Live", w / 2f, w - 70f, soft)

        val f = File(dir(ctx), "match_${m.id}.png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }
}

/** Serves the share cards read-only to the app the user shares with. */
class ShareProvider : ContentProvider() {
    override fun onCreate() = true

    private fun file(uri: Uri): File? {
        val ctx = context ?: return null
        val name = uri.lastPathSegment ?: return null
        if (name.contains('/') || name.contains("..")) return null
        val f = File(ShareCard.dir(ctx), name)
        return if (f.exists()) f else null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = file(uri) ?: return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/png"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val f = file(uri) ?: return null
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf(f.name, f.length())) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
