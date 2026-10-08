package com.ikverse.deskglow.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import com.ikverse.deskglow.store.City
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

fun hasLocationAccess(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/**
 * Where the phone is, as a [City] the weather can be fetched for. Reads the coarse (city-level)
 * position once per call: the newest position Android already has, or a single fresh one if it has
 * none. Nothing is tracked in the background. Blocks, so call it off the main thread.
 */
class LocationFinder(private val context: Context) {

    @SuppressLint("MissingPermission")
    fun locate(): City? {
        if (!hasLocationAccess(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val location = newestKnown(manager) ?: fresh(manager) ?: return null
        return cityAt(location.latitude, location.longitude, placeName(location))
    }

    @SuppressLint("MissingPermission")
    private fun newestKnown(manager: LocationManager): Location? =
        manager.getProviders(true).mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }

    @SuppressLint("MissingPermission")
    private fun fresh(manager: LocationManager): Location? {
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) } ?: return null
        var found: Location? = null
        val done = CountDownLatch(1)
        val cancel = CancellationSignal()
        LocationManagerCompat.getCurrentLocation(manager, provider, cancel, Runnable::run) {
            found = it
            done.countDown()
        }
        if (!done.await(FIX_TIMEOUT_S, TimeUnit.SECONDS)) cancel.cancel()
        return found
    }

    /** The town's name from Android's own geocoder, when the phone has one; null otherwise. */
    @Suppress("DEPRECATION")
    private fun placeName(location: Location): Pair<String, String>? {
        if (!Geocoder.isPresent()) return null
        return runCatching {
            Geocoder(context, Locale.getDefault()).getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
        }.getOrNull()?.let { address ->
            val name = address.locality ?: address.subAdminArea ?: address.adminArea ?: return null
            val region = listOfNotNull(address.adminArea?.takeIf { it != name }, address.countryName).joinToString(", ")
            name to region
        }
    }

    private companion object {
        const val FIX_TIMEOUT_S = 15L
    }
}

/** A city at a position rounded to about a kilometre, which is all a forecast needs and all that is sent. */
internal fun cityAt(latitude: Double, longitude: Double, place: Pair<String, String>?): City =
    City(place?.first ?: "Current location", place?.second ?: "", roundCoordinate(latitude), roundCoordinate(longitude))

internal fun roundCoordinate(value: Double): Double = Math.round(value * 100) / 100.0
