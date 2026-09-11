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
import kotlinx.coroutines.withContext
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import xyz.attacktive.weatherd.domain.model.PhotoBucket
import xyz.attacktive.weatherd.util.AppLogger

/**
 * Owns the user's own photos, one file per [PhotoBucket], under `filesDir/backgrounds`.
 * The bytes are copied at pick time rather than the picker's [Uri] being remembered: `content://media/picker/...` grants do not survive a reboot and cannot be persisted, and a live wallpaper that stopped drawing every morning would be worthless.
 * Each copy is downsampled to the display's long edge and re-encoded as JPEG, which bounds four buckets to roughly 12MB and leaves [load] nothing to do at draw time but read.
 */
@Singleton
class PhotoBackgroundRepository @Inject constructor(@ApplicationContext private val context: Context, private val logger: AppLogger) {
	private val directory = File(context.filesDir, DIRECTORY_NAME)

	// Four stat calls on the injecting thread, once per process, is a price worth paying for a bucket set that is correct from the very first read.
	private val availableBuckets = MutableStateFlow(scanAvailableBuckets())

	/** The buckets that currently hold a photo, for a UI that has to reflect an import or a clear as it happens. */
	val available: StateFlow<Set<PhotoBucket>> = availableBuckets.asStateFlow()

	/** The buckets that currently hold a photo, for the render thread, which resolves a bucket mid-frame and cannot collect a flow to do it. */
	fun availableNow() = availableBuckets.value

	/**
	 * Copies the photo at [source] into [bucket], downsampled and re-encoded, replacing whatever that bucket held.
	 * Fails rather than throws: a pick can be revoked, corrupt or simply enormous, and all three are the user's problem to see rather than the process's to die of.
	 */
	suspend fun import(bucket: PhotoBucket, source: Uri): Result<Unit> = withContext(Dispatchers.IO) {
		try {
			importInto(bucket, source)
			availableBuckets.value = scanAvailableBuckets()

			Result.success(Unit)
		} catch (exception: IOException) {
			importFailure(bucket, exception)
		} catch (exception: SecurityException) {
			importFailure(bucket, exception)
		} catch (exception: OutOfMemoryError) {
			// A 108MP pick can exhaust the heap even downsampled; refusing that import beats taking the wallpaper's process down with it.
			importFailure(bucket, exception)
		}
	}

	/**
	 * The stored photo for [bucket], or null when the bucket is empty or its file no longer decodes.
	 * Synchronous and called from the render thread on purpose: the file is already scaled to the display, so this is a straight read with no resampling, and the backdrop cannot be rasterized until the bitmap is in hand.
	 * The caller owns the result and must recycle it.
	 */
	fun load(bucket: PhotoBucket): Bitmap? {
		val file = fileFor(bucket)
		if (!file.exists()) {
			return null
		}

		val bitmap = try {
			BitmapFactory.decodeFile(file.path)
		} catch (exception: OutOfMemoryError) {
			logger.error(TAG, "decoding the stored photo for $bucket ran out of memory", exception)
			null
		}

		if (bitmap == null) {
			logger.debug(TAG, "the stored photo for $bucket did not decode")
		}

		return bitmap
	}

	/** Drops the photo stored for [bucket], after which that phase falls back to its parent bucket or to the painted sky. */
	suspend fun clear(bucket: PhotoBucket) {
		withContext(Dispatchers.IO) {
			val file = fileFor(bucket)
			if (file.exists() && !file.delete()) {
				logger.error(TAG, "could not delete the stored photo for $bucket")
			}

			availableBuckets.value = scanAvailableBuckets()
		}
	}

	private fun importFailure(bucket: PhotoBucket, cause: Throwable): Result<Unit> {
		logger.error(TAG, "importing a photo for $bucket failed", cause)

		return Result.failure(cause)
	}

	private fun importInto(bucket: PhotoBucket, source: Uri) {
		val targetLongEdge = targetLongEdge()
		val bounds = decodeBounds(source)

		// The bounds pass reports -1 for anything BitmapFactory cannot make sense of, which photoSampleSize treats as "do not downsample" and the full decode below then rejects outright.
		var bitmap = decodeSampled(source, photoSampleSize(bounds.outWidth, bounds.outHeight, targetLongEdge)) ?: throw IOException("$source did not decode to a bitmap")

		try {
			bitmap = scaledToLongEdge(bitmap, targetLongEdge)
			writeAtomically(bucket, bitmap)
		} finally {
			bitmap.recycle()
		}
	}

	private fun decodeBounds(source: Uri): BitmapFactory.Options {
		val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
		openStream(source).use {
			BitmapFactory.decodeStream(it, null, options)
		}

		return options
	}

	private fun decodeSampled(source: Uri, sampleSize: Int): Bitmap? {
		val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }

		// A second open rather than a rewind: a content stream is not reliably markable, and the bounds pass has already consumed the header.
		return openStream(source).use {
			BitmapFactory.decodeStream(it, null, options)
		}
	}

	private fun openStream(source: Uri) = context.contentResolver.openInputStream(source) ?: throw IOException("$source could not be opened")

	/**
	 * Returns [source] scaled down so its long edge is exactly [targetLongEdge], or [source] itself when it is already that size or smaller.
	 * Consumes [source]: the caller must treat only the returned bitmap as live.
	 */
	private fun scaledToLongEdge(source: Bitmap, targetLongEdge: Int): Bitmap {
		val longEdge = max(source.width, source.height)
		if (longEdge <= targetLongEdge) {
			return source
		}

		val scale = targetLongEdge.toFloat() / longEdge.toFloat()
		val width = max(1, (source.width * scale).roundToInt())
		val height = max(1, (source.height * scale).roundToInt())
		val scaled = Bitmap.createScaledBitmap(source, width, height, true)
		if (scaled !== source) {
			source.recycle()
		}

		return scaled
	}

	private fun writeAtomically(bucket: PhotoBucket, bitmap: Bitmap) {
		if (!directory.isDirectory && !directory.mkdirs()) {
			throw IOException("could not create $directory")
		}

		// Encoding straight onto the bucket's own file would leave a half-written photo behind on any failure; the rename is atomic within a directory, so the bucket flips from old to new in one step or not at all.
		val temporary = File(directory, "${fileNameFor(bucket)}.tmp")
		try {
			FileOutputStream(temporary).use { stream ->
				if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) {
					throw IOException("could not encode the photo for $bucket as JPEG")
				}

				// A rename is only atomic with respect to the file system's own ordering; syncing first is what keeps a power cut from leaving a named but empty bucket.
				stream.fd.sync()
			}

			if (!temporary.renameTo(fileFor(bucket))) {
				throw IOException("could not move the imported photo into place for $bucket")
			}
		} finally {
			temporary.delete()
		}
	}

	/**
	 * The pixel length every stored photo is scaled to, taken as the longer display edge so one file serves both orientations.
	 * The wallpaper surface is not strictly the display — a launcher that forces a scrollable wallpaper can hand us something wider — but this service never asks for one, and the worst case is a modest upscale rather than a failure.
	 * Reach for `WallpaperManager.getDesiredMinimumWidth`/`getDesiredMinimumHeight` if a device ever looks soft; that is the canonical wallpaper size, not the display metrics.
	 */
	private fun targetLongEdge(): Int {
		val metrics = context.resources.displayMetrics

		return max(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(MINIMUM_LONG_EDGE)
	}

	// The return type is spelled out so neither the state flow nor availableNow hands a caller a mutable handle on the live set.
	private fun scanAvailableBuckets(): Set<PhotoBucket> = PhotoBucket.entries.filterTo(mutableSetOf()) { fileFor(it).exists() }

	private fun fileFor(bucket: PhotoBucket) = File(directory, "${fileNameFor(bucket)}.jpg")

	// Kotlin's no-argument lowercase is root-locale by design; never hand it the default locale, which would turn NIGHT into "nıght" for a Turkish user and lose their photo.
	private fun fileNameFor(bucket: PhotoBucket) = bucket.name.lowercase()

	companion object {
		private const val TAG = "PhotoBackgroundRepository"
		private const val DIRECTORY_NAME = "backgrounds"
		private const val JPEG_QUALITY = 90
		private const val MINIMUM_LONG_EDGE = 1280
	}
}

/**
 * The `inSampleSize` to decode a [sourceWidth] by [sourceHeight] image with so its long edge lands at or just above [targetLongEdge].
 * Always a power of two, because BitmapFactory rounds anything else up to one anyway.
 * Rounds the ratio down rather than up: a sample size that undershot the target would decode fewer pixels than the display has, and the import scales down only, so that softness would be baked into the stored file forever.
 * Returns 1 for a degenerate or undecodable size instead of dividing by zero.
 */
internal fun photoSampleSize(sourceWidth: Int, sourceHeight: Int, targetLongEdge: Int): Int {
	// Either dimension being non-positive means there is no real image here, whatever the other one claims, so hand the full decode an untouched stream and let it fail there with a reason.
	if (sourceWidth <= 0 || sourceHeight <= 0 || targetLongEdge <= 0) {
		return 1
	}

	val sourceLongEdge = max(sourceWidth, sourceHeight)
	var sampleSize = 1
	while (sourceLongEdge / (sampleSize * 2) >= targetLongEdge) {
		sampleSize *= 2
	}

	return sampleSize
}
