package xyz.attacktive.weatherd.domain.repository

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import xyz.attacktive.weatherd.util.AppLogger

/**
 * Owns the user's custom solar-disc image under app-private storage.
 * Picker grants are intentionally not retained: the chosen pixels are copied immediately so the live wallpaper can still render after reboot or after the source app revokes its content URI.
 * Imports are center-cropped to a square, capped at 512px and encoded as PNG so transparent artwork stays transparent while arbitrary rectangular photos cannot leak outside the circular sun mask.
 */
@Singleton
class SunImageRepository @Inject constructor(@ApplicationContext private val context: Context, private val logger: AppLogger) {
	private val directory = File(context.filesDir, DIRECTORY_NAME)
	private val file = File(directory, FILE_NAME)
	private val mutation = Mutex()
	private val _available = MutableStateFlow(storedImageDecodes())
	private val _revision = MutableStateFlow(0)

	val available: StateFlow<Boolean> = _available.asStateFlow()
	val revision: StateFlow<Int> = _revision.asStateFlow()

	fun revisionNow() = _revision.value

	suspend fun import(source: Uri): Result<Unit> = withContext(Dispatchers.IO) {
		mutation.withLock {
			try {
				val bitmap = decodeImport(source) ?: throw IOException("$source did not decode to a bitmap")

				try {
					writeAtomically(bitmap)
				} finally {
					bitmap.recycle()
				}

				_revision.value++
				_available.value = storedImageDecodes()

				Result.success(Unit)
			} catch (exception: Exception) {
				importFailure(exception)
			} catch (error: OutOfMemoryError) {
				importFailure(error)
			}
		}
	}

	fun load(): Bitmap? {
		if (!file.exists()) {
			return null
		}

		val bitmap = try {
			BitmapFactory.decodeFile(file.path)
		} catch (error: OutOfMemoryError) {
			logger.error(TAG, "decoding the stored custom sun image ran out of memory", error)
			null
		}

		if (bitmap == null) {
			logger.debug(TAG, "the stored custom sun image did not decode")
		}

		return bitmap
	}

	suspend fun loadThumbnail(): Bitmap? = withContext(Dispatchers.IO) {
		val source = load() ?: return@withContext null
		val edge = max(1, (THUMBNAIL_TARGET_DP * context.resources.displayMetrics.density).roundToInt())
		val thumbnail = Bitmap.createScaledBitmap(source, edge, edge, true)
		if (thumbnail !== source) {
			source.recycle()
		}

		thumbnail
	}

	suspend fun clear() {
		withContext(Dispatchers.IO) {
			mutation.withLock {
				if (file.exists()) {
					if (file.delete()) {
						_revision.value++
					} else {
						logger.error(TAG, "could not delete the stored custom sun image")
					}
				}

				_available.value = storedImageDecodes()
			}
		}
	}

	private fun importFailure(cause: Throwable): Result<Unit> {
		logger.error(TAG, "importing the custom sun image failed", cause)

		return Result.failure(cause)
	}

	private fun decodeImport(source: Uri): Bitmap? {
		val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
		openStream(source).use {
			BitmapFactory.decodeStream(it, null, bounds)
		}

		if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
			return null
		}

		val options = BitmapFactory.Options().apply {
			inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
		}

		val decoded = openStream(source).use {
			BitmapFactory.decodeStream(it, null, options)
		} ?: return null

		return cropAndScale(decoded)
	}

	private fun cropAndScale(source: Bitmap): Bitmap {
		val edge = minOf(source.width, source.height)
		val left = (source.width - edge) / 2
		val top = (source.height - edge) / 2
		val square = Bitmap.createBitmap(source, left, top, edge, edge)
		if (square !== source) {
			source.recycle()
		}

		if (edge <= STORED_EDGE_PIXELS) {
			return square
		}

		val scaled = Bitmap.createScaledBitmap(square, STORED_EDGE_PIXELS, STORED_EDGE_PIXELS, true)
		if (scaled !== square) {
			square.recycle()
		}

		return scaled
	}

	private fun sampleSizeFor(width: Int, height: Int): Int {
		val longEdge = max(width, height)
		var sampleSize = 1
		while (longEdge / (sampleSize * 2) >= STORED_EDGE_PIXELS) {
			sampleSize *= 2
		}

		return sampleSize
	}

	private fun writeAtomically(bitmap: Bitmap) {
		if (!directory.isDirectory && !directory.mkdirs()) {
			throw IOException("could not create $directory")
		}

		val temporary = File(directory, "$FILE_NAME.tmp")
		try {
			FileOutputStream(temporary).use { stream ->
				if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
					throw IOException("could not encode the custom sun image as PNG")
				}

				stream.fd.sync()
			}

			if (!temporary.renameTo(file)) {
				throw IOException("could not move the custom sun image into place")
			}
		} finally {
			temporary.delete()
		}
	}

	private fun storedImageDecodes(): Boolean {
		if (!file.exists()) {
			return false
		}

		val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
		BitmapFactory.decodeFile(file.path, bounds)

		return bounds.outWidth > 0 && bounds.outHeight > 0
	}

	private fun openStream(source: Uri) = context.contentResolver.openInputStream(source) ?: throw IOException("$source could not be opened")

	companion object {
		private const val TAG = "SunImageRepository"
		private const val DIRECTORY_NAME = "celestial"
		private const val FILE_NAME = "custom-sun.png"
		private const val STORED_EDGE_PIXELS = 512
		private const val THUMBNAIL_TARGET_DP = 48f
	}
}
