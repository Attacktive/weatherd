package xyz.attacktive.weatherd.domain.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import xyz.attacktive.weatherd.domain.model.GeoLocation
import xyz.attacktive.weatherd.util.AppLogger

@Singleton
class LocationRepository @Inject constructor(@ApplicationContext private val context: Context, private val logger: AppLogger) {
	private val locationManager = context.getSystemService(LocationManager::class.java)

	fun hasLocationPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

	/**
	 * The device's coordinates, preferring a recent cached fix and actively requesting a fresh one when the cache is missing or stale.
	 * Returns null only without permission, or when no fix can be obtained at all.
	 */
	@SuppressLint("MissingPermission")
	suspend fun currentLocation(): GeoLocation? {
		if (!hasLocationPermission()) {
			logger.debug(TAG, "location permission not granted")
			return null
		}

		val cached = newestLastKnown()
		if (cached != null && cached.isFreshEnough()) {
			return cached.toGeoLocation()
		}

		val fresh = requestCurrentFix()

		return (fresh ?: cached)?.toGeoLocation()
	}

	/** Newest last-known fix across enabled providers — instant, but only whatever the system already cached. */
	@SuppressLint("MissingPermission")
	private fun newestLastKnown() = locationManager.getProviders(true)
		.mapNotNull { provider ->
			runCatching { locationManager.getLastKnownLocation(provider) }
				.getOrNull()
		}
		.maxByOrNull { it.time }

	/** Actively powers a provider for a single fresh fix, bounded by a timeout; null if none arrives in time. */
	@SuppressLint("MissingPermission")
	private suspend fun requestCurrentFix(): Location? {
		val provider = activeProvider()
		if (provider == null) {
			logger.debug(TAG, "no location provider enabled")
			return null
		}

		return withTimeoutOrNull(FIX_TIMEOUT_MILLIS.milliseconds) {
			suspendCancellableCoroutine { continuation ->
				val cancellationSignal = CancellationSignal()
				continuation.invokeOnCancellation { cancellationSignal.cancel() }
				LocationManagerCompat.getCurrentLocation(locationManager, provider, cancellationSignal, ContextCompat.getMainExecutor(context)) { location ->
					continuation.resume(location)
				}
			}
		}
	}

	/** Prefer the low-power network provider (coarse is all weather needs), then GPS, then anything enabled. */
	private fun activeProvider(): String? {
		val enabled = locationManager.getProviders(true)
		return PROVIDER_PREFERENCE.firstOrNull { it in enabled } ?: enabled.firstOrNull()
	}

	private fun Location.isFreshEnough() = System.currentTimeMillis() - time < MAX_LOCATION_AGE_MILLIS

	private fun Location.toGeoLocation() = GeoLocation(latitude, longitude)

	companion object {
		private const val TAG = "LocationRepository"
		private const val MAX_LOCATION_AGE_MILLIS = 10L * 60L * 1000L
		private const val FIX_TIMEOUT_MILLIS = 15L * 1000L
		private val PROVIDER_PREFERENCE = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
	}
}
