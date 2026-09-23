package com.kemahub

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.kemahub.core.GeoPoint
import com.kemahub.core.Hub

/** Where the phone is, for profiles with a place. Only used while such a profile exists. */
@SuppressLint("MissingPermission") // every entry point checks granted()
object Locations {
    val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun granted(context: Context) = PERMISSIONS.any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun manager(context: Context) = context.getSystemService(LocationManager::class.java)

    private fun provider(lm: LocationManager): String? = when {
        Build.VERSION.SDK_INT >= 31 && lm.allProviders.contains(LocationManager.FUSED_PROVIDER) &&
            lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
        lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        else -> null
    }

    fun Location.toGeo() = GeoPoint(latitude, longitude, if (hasAccuracy()) accuracy else 0f)

    /** The freshest last-known fix (no new request), or null. */
    fun lastKnown(context: Context): GeoPoint? {
        if (!granted(context)) return null
        val lm = manager(context) ?: return null
        return lm.getProviders(true)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }
            ?.takeIf { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < 30 * 60 * 1_000_000_000L }
            ?.toGeo()
    }

    /**
     * A listener that also implements the callbacks that only became default methods in
     * Android 11 (older versions would otherwise fail when calling them).
     */
    private class Listener(val onFix: (Location) -> Unit) : LocationListener {
        override fun onLocationChanged(location: Location) = onFix(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    /** One fresh fix, delivered on the main thread; null if location is off or nothing came in 30 s. */
    fun current(context: Context, done: (GeoPoint?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val lm = manager(context)
        val provider = lm?.let { provider(it) }
        if (!granted(context) || lm == null || provider == null) {
            main.post { done(null) }
            return
        }
        var finished = false
        lateinit var listener: Listener
        fun finish(p: GeoPoint?) {
            if (finished) return
            finished = true
            lm.removeUpdates(listener)
            done(p)
        }
        listener = Listener { finish(it.toGeo()) }
        runCatching { lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper()) }
            .onFailure { main.post { finish(null) } }
        main.postDelayed({ finish(lastKnown(context)) }, 30_000)
    }

    /**
     * Low-power updates while [start]ed: every few minutes or ~100 m, whichever the provider
     * manages. Each fix goes to [Hub.setLocation].
     */
    class Tracker(private val context: Context) {
        private var listener: LocationListener? = null

        val running get() = listener != null

        fun start(): Boolean {
            if (listener != null) return true
            val lm = manager(context) ?: return false
            val provider = provider(lm) ?: return false
            if (!granted(context)) return false
            val l = Listener { Hub.setLocation(context, it.toGeo()) }
            runCatching { lm.requestLocationUpdates(provider, 3 * 60_000L, 100f, l, Looper.getMainLooper()) }
                .onFailure {
                    Hub.log("location updates failed: $it")
                    return false
                }
            listener = l
            lastKnown(context)?.let { Hub.setLocation(context, it) }
            Hub.log("location updates on ($provider)")
            return true
        }

        fun stop() {
            val l = listener ?: return
            listener = null
            manager(context)?.removeUpdates(l)
            Hub.log("location updates off")
        }
    }
}
