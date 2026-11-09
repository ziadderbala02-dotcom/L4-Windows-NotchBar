package com.l4.notchlive

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import java.util.Locale

/**
 * 1.9: where the phone is, for the weather and the prayer times. Network location only (cell and
 * Wi-Fi, about a district's accuracy) plus fixes other apps already asked for (passive): no GPS
 * running, so it costs next to nothing. A new place counts after moving ~1 km.
 * Falls back to the city the user picked when there is no permission or no fix.
 */
object Locator {
    private var listener: LocationListener? = null

    /** Called (main thread) when the place changed enough to refetch the weather. */
    var onMoved: (() -> Unit)? = null

    fun canFg(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun canBg(ctx: Context) = canFg(ctx) && (Build.VERSION.SDK_INT < 29 ||
        ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)

    /** The place in use: the last automatic fix when auto is on, else the chosen city. */
    fun city(ctx: Context, prefs: Prefs): City = if (prefs.locAuto && canFg(ctx)) prefs.autoCity ?: prefs.city else prefs.city

    fun start(ctx: Context, prefs: Prefs) {
        stop(ctx)
        if (!prefs.locAuto || !canFg(ctx)) return
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val l = LocationListener { loc -> got(ctx, prefs, loc) }
        listener = l
        try {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 30 * 60_000L, 1000f, l, Looper.getMainLooper())
            }
            lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 5 * 60_000L, 500f, l, Looper.getMainLooper())
        } catch (e: Exception) {
            // Provider missing or permission withdrawn.
        }
        lastKnown(ctx)?.let { got(ctx, prefs, it) }
    }

    fun stop(ctx: Context) {
        val l = listener ?: return
        try {
            (ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(l)
        } catch (e: Exception) {
            // Gone already.
        }
        listener = null
    }

    /** One quick fix now (opening the app, or "use my location"). */
    fun refresh(ctx: Context, prefs: Prefs) {
        lastKnown(ctx)?.let { got(ctx, prefs, it) }
        if (!canFg(ctx)) return
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            if (Build.VERSION.SDK_INT >= 30 && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, ctx.mainExecutor) { loc -> if (loc != null) got(ctx, prefs, loc) }
            }
        } catch (e: Exception) {
            // Keep the last one.
        }
    }

    private fun lastKnown(ctx: Context): Location? {
        if (!canFg(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER).mapNotNull {
            try {
                lm.getLastKnownLocation(it)
            } catch (e: Exception) {
                null
            }
        }.maxByOrNull { it.time }
    }

    private fun got(ctx: Context, prefs: Prefs, loc: Location) {
        val old = prefs.autoCity
        if (old != null) {
            val d = FloatArray(1)
            Location.distanceBetween(old.lat, old.lon, loc.latitude, loc.longitude, d)
            if (d[0] < 1000f && old.name.isNotEmpty()) return
        }
        // Save at once (no name yet), then look up the district's name off the main thread.
        prefs.autoCity = City(old?.name ?: "", loc.latitude, loc.longitude)
        Thread {
            val name = placeName(ctx, loc.latitude, loc.longitude)
            prefs.autoCity = City(name ?: old?.name ?: "", loc.latitude, loc.longitude)
            android.os.Handler(Looper.getMainLooper()).post { onMoved?.invoke() }
        }.start()
    }

    /** "مدينة نصر" / "Maadi": the district if the phone's geocoder knows it, else the city. */
    private fun placeName(ctx: Context, lat: Double, lon: Double): String? = try {
        if (!android.location.Geocoder.isPresent()) null
        else {
            @Suppress("DEPRECATION")
            val a = android.location.Geocoder(ctx, if (L.ar) Locale("ar") else Locale.ENGLISH).getFromLocation(lat, lon, 1)?.firstOrNull()
            a?.subLocality ?: a?.locality ?: a?.subAdminArea ?: a?.adminArea
        }
    } catch (e: Exception) {
        null
    }
}
