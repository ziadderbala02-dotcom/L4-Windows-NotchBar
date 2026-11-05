package com.l4.notchlive

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Team crests, downloaded once per run and kept in memory. */
object Logos {
    private val cache = ConcurrentHashMap<String, Bitmap>()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    /** The crest if it's loaded; otherwise starts loading and calls [onReady] on the main thread. */
    fun get(url: String, onReady: () -> Unit): Bitmap? {
        cache[url]?.let { return it }
        if (loading.add(url)) {
            pool.execute {
                try {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 10_000
                    c.readTimeout = 15_000
                    val bmp = c.inputStream.use { BitmapFactory.decodeStream(it) }
                    c.disconnect()
                    if (bmp != null) {
                        cache[url] = bmp
                        main.post(onReady)
                    }
                } catch (e: Exception) {
                    // Try again next time it's asked for.
                } finally {
                    loading.remove(url)
                }
            }
        }
        return null
    }
}
