package xyz.attacktive.weatherd.domain.repository

import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import xyz.attacktive.weatherd.util.AppLogger

/** Turns a coordinate fix into a short place name ("Seoul") via the platform [Geocoder]; null whenever the device or its backend can't say. */
@Singleton
class ReverseGeocodingRepository @Inject constructor(@ApplicationContext private val context: Context, private val logger: AppLogger) {
	suspend fun placeName(latitude: Double, longitude: Double): String? {
		if (!Geocoder.isPresent()) {
			logger.debug(TAG, "no geocoder backend on this device")
			return null
		}

		val address = try {
			Geocoder(context, Locale.getDefault()).firstAddress(latitude, longitude)
		} catch (exception: IOException) {
			logger.debug(TAG, "reverse geocode failed: ${exception.message}")
			null
		}

		return address?.run { locality ?: subAdminArea ?: adminArea }
	}

	private suspend fun Geocoder.firstAddress(latitude: Double, longitude: Double): Address? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
		suspendCancellableCoroutine { continuation ->
			// The listener form (not the lambda) is deliberate: a plain lambda only covers onGeocode, and a backend error would leave the coroutine suspended forever.
			getFromLocation(latitude, longitude, 1, object: Geocoder.GeocodeListener {
				override fun onGeocode(addresses: MutableList<Address>) {
					continuation.resume(addresses.firstOrNull())
				}

				override fun onError(errorMessage: String?) {
					logger.debug(TAG, "reverse geocode failed: $errorMessage")
					continuation.resume(null)
				}
			})
		}
	} else {
		withContext(Dispatchers.IO) {
			@Suppress("DEPRECATION")
			getFromLocation(latitude, longitude, 1)?.firstOrNull()
		}
	}

	companion object {
		private const val TAG = "ReverseGeocodingRepository"
	}
}
