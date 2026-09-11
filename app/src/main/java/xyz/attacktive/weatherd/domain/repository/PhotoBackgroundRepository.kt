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
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.PhotoBucket
import xyz.attacktive.weatherd.domain.model.photoBucketFor
import xyz.attacktive.weatherd.util.AppLogger

/**
 * Owns the user's own photos, one file per [PhotoBucket], under `filesDir/backgrounds`.
 * The bytes are copied at pick time rather than the picker's [Uri] being remembered: `content://media/picker/...` grants do not survive a reboot and cannot be persisted, and a live wallpaper that stopped drawing every morning would be worthless.
 * Each copy is turned upright, downsampled to the display's long edge and re-encoded as JPEG, which bounds four buckets to roughly 12MB and leaves [load] nothing to do at draw time but read.
 * Upright has to happen here: the re-encode writes no EXIF of its own, so whatever rotation [import] fails to bake into the pixels is lost to every later read.
 */
@Singleton
class PhotoBackgroundRepository @Inject constructor(@ApplicationContext private val context: Context, private val logger: AppLogger) {
	private val directory = File(context.filesDir, DIRECTORY_NAME)

	// Every import of a bucket encodes through that bucket's one scratch file, so two of them at once would truncate each other's half-written photo straight into the live file.
	// A temp name per call would avoid the collision but leave a stray behind on a process kill, which nothing later sweeps; serializing costs a wait on a path the user already knows is slow.
	private val mutation = Mutex()

	// Four stat calls on the injecting thread, once per process, is a price worth paying for a bucket set that is correct from the very first read.
	private val availableBuckets = MutableStateFlow(scanAvailableBuckets())

	// The bucket set alone cannot say that a bucket's photo was replaced — the bucket was filled before and is filled after — so a counter tracks the bytes as well as the names.
	// Every mutation below already runs under the mutex, so the increment needs no atomic of its own; only the render thread's read has to see it, which is what the volatile buys.
	// Both mutation paths bump before publishing availableBuckets: that assignment resumes collectors on other coroutines, and one of them reading revisionNow() must never pair the new bucket set with a revision that predates it.
	@Volatile private var revision = 0

	/** The buckets that currently hold a photo, for a UI that has to reflect an import or a clear as it happens. */
	val available: StateFlow<Set<PhotoBucket>> = availableBuckets.asStateFlow()

	/** The buckets that currently hold a photo, for the render thread, which resolves a bucket mid-frame and cannot collect a flow to do it. */
	fun availableNow() = availableBuckets.value

	/**
	 * A number that changes whenever the stored photos change, for a caller that caches something rasterized from them and needs to know the pixels moved under it.
	 * Opaque and process-local: only differences matter, and a fresh process starts over alongside every cache that could have compared against the old value.
	 */
	fun revisionNow() = revision

	/**
	 * Copies the photo at [source] into [bucket], turned upright, downsampled and re-encoded, replacing whatever that bucket held.
	 * Fails rather than throws: a pick can be revoked, corrupt or simply enormous, and all three are the user's problem to see rather than the process's to die of.
	 * Serialized against every other import and [clear], so Replace tapped twice during a multi-megapixel decode queues instead of racing.
	 */
	suspend fun import(bucket: PhotoBucket, source: Uri): Result<Unit> = withContext(Dispatchers.IO) {
		mutation.withLock {
			try {
				importInto(bucket, source)
				revision++
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
	}

	/**
	 * The stored photo for [bucket], or null when the bucket is empty or its file no longer decodes.
	 * Synchronous and called from the render thread on purpose: the file is already scaled to the display and already upright, so this is a straight read with no resampling and no rotation, and the backdrop cannot be rasterized until the bitmap is in hand.
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

	/**
	 * The stored photo to draw as the sky during [dayPhase], or null when [scene] is not [BackdropScene.PHOTO], no filled bucket covers that phase, or the stored file no longer decodes.
	 * Null is the ordinary case and not a failure: the caller then lets the renderer paint its procedural sky exactly as it always has.
	 * The whole fallback rule of the feature lives here and only here — the guard on [scene], the bucket resolution and the read — so the wallpaper and the in-app preview cannot drift into showing different photos for the same phase.
	 * Synchronous and safe to call from the render thread for the same reason [load] is, and with the same ownership: the caller borrows the bitmap for one `renderBackdrop` call on the thread that rasterizes, and must recycle it afterward.
	 */
	fun loadFor(scene: BackdropScene, dayPhase: DayPhase): Bitmap? {
		if (scene != BackdropScene.PHOTO) {
			return null
		}

		val bucket = photoBucketFor(dayPhase, availableNow()) ?: return null

		return load(bucket)
	}

	/**
	 * Drops the photo stored for [bucket], after which that phase falls back to its parent bucket or to the painted sky.
	 * Serialized against [import], so clearing a bucket mid-import takes effect before or after that import rather than during it.
	 */
	suspend fun clear(bucket: PhotoBucket) {
		withContext(Dispatchers.IO) {
			mutation.withLock {
				val file = fileFor(bucket)
				if (file.exists()) {
					if (file.delete()) {
						revision++
					} else {
						logger.error(TAG, "could not delete the stored photo for $bucket")
					}
				}

				availableBuckets.value = scanAvailableBuckets()
			}
		}
	}

	private fun importFailure(bucket: PhotoBucket, cause: Throwable): Result<Unit> {
		logger.error(TAG, "importing a photo for $bucket failed", cause)

		return Result.failure(cause)
	}

	private fun importInto(bucket: PhotoBucket, source: Uri) {
		val bitmap = decodeUpright(source, targetLongEdge()) ?: throw IOException("$source did not decode to a bitmap")

		try {
			writeAtomically(bucket, bitmap)
		} finally {
			bitmap.recycle()
		}
	}

	/**
	 * Decodes [source] with its stored rotation already applied and its long edge at [targetLongEdge] or below, or null when there is no image there to decode.
	 * The caller owns the result and must recycle it.
	 */
	private fun decodeUpright(source: Uri, targetLongEdge: Int): Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
		// ImageDecoder applies the encoded origin itself, for every format the platform can decode rather than only the ones a metadata parser understands, and it samples at any integer rather than only powers of two, so this route is both upright and never the more expensive decode of the two.
		ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, source)) { decoder, info, _ ->
			// The default allocator returns a hardware bitmap, whose pixels live in graphics memory where the JPEG encoder cannot reach them.
			decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE

			// The reported size is already the upright one, and so is the target: ImageDecoder swaps both for a quarter-turn origin, so neither has to be second-guessed here.
			val size = info.size
			val longEdge = max(size.width, size.height)
			if (longEdge > targetLongEdge) {
				val scale = targetLongEdge.toFloat() / longEdge.toFloat()
				decoder.setTargetSize(max(1, (size.width * scale).roundToInt()), max(1, (size.height * scale).roundToInt()))
			}
		}
	} else {
		decodeUprightWithBitmapFactory(source, targetLongEdge)
	}

	/**
	 * The route for 26 and 27, which predate [ImageDecoder]: [BitmapFactory] reads no orientation of its own, so the tag is read separately and turned in afterwards.
	 * Scaling before rotating is deliberate — a quarter turn leaves the long edge where it was, so the rotation's copy costs one target-sized bitmap instead of one intermediate-sized one.
	 */
	private fun decodeUprightWithBitmapFactory(source: Uri, targetLongEdge: Int): Bitmap? {
		val bounds = decodeBounds(source)

		// The bounds pass reports -1 for anything BitmapFactory cannot make sense of, which photoSampleSize treats as "do not downsample" and the full decode below then rejects outright.
		// scaledToLongEdge and uprighted each consume what they are handed, so this one name follows whichever copy is currently live.
		var bitmap = decodeSampled(source, photoSampleSize(bounds.outWidth, bounds.outHeight, targetLongEdge)) ?: return null

		// The flag is what tells a finished chain from one that threw partway and left a bitmap for this function to release.
		var complete = false
		try {
			bitmap = scaledToLongEdge(bitmap, targetLongEdge)
			bitmap = uprighted(bitmap, exifOrientation(source))
			complete = true
		} finally {
			if (!complete) {
				bitmap.recycle()
			}
		}

		return bitmap
	}

	/**
	 * The orientation [source] declares, or [ExifInterface.ORIENTATION_NORMAL] when it declares none or cannot be read for one.
	 * A camera writes the sensor's pixels plus a rotation tag rather than rotated pixels, so an ordinary phone photo is stored on its side without this.
	 * An unreadable tag is not worth failing an import over: a photo that is upright for the overwhelming majority of picks beats no photo at all.
	 */
	private fun exifOrientation(source: Uri): Int {
		return try {
			openStream(source).use {
				ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
			}
		} catch (exception: IOException) {
			logger.debug(TAG, "could not read the orientation of $source: ${exception.message}")

			ExifInterface.ORIENTATION_NORMAL
		}
	}

	/**
	 * Returns [source] with the EXIF [orientation] baked into its pixels, or [source] itself when the pixels are already upright.
	 * All eight values are handled rather than the three plain rotations, so a mirrored pick comes out the same way here as it does through [ImageDecoder] instead of differing by API level.
	 * Consumes [source]: the caller must treat only the returned bitmap as live.
	 */
	private fun uprighted(source: Bitmap, orientation: Int): Bitmap {
		val matrix = Matrix()
		when (orientation) {
			ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
			ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
			ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
			ExifInterface.ORIENTATION_TRANSPOSE -> {
				matrix.setRotate(90f)
				matrix.postScale(-1f, 1f)
			}
			ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
			ExifInterface.ORIENTATION_TRANSVERSE -> {
				matrix.setRotate(270f)
				matrix.postScale(-1f, 1f)
			}
			ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
			// ORIENTATION_NORMAL, ORIENTATION_UNDEFINED and whatever a corrupt tag invents all mean the same thing: leave the pixels alone.
			else -> return source
		}

		val upright = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
		if (upright !== source) {
			source.recycle()
		}

		return upright
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
		// One scratch name per bucket is safe only because `mutation` keeps two imports of that bucket from holding it at once.
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
	 * The pixel length every stored photo is scaled to, read from the display this process is running on.
	 * The wallpaper surface is not strictly the display — a launcher that forces a scrollable wallpaper can hand us something wider — but this service never asks for one, and the worst case is a modest upscale rather than a failure.
	 * Reach for `WallpaperManager.getDesiredMinimumWidth`/`getDesiredMinimumHeight` if a device ever looks soft; that is the canonical wallpaper size, not the display metrics.
	 */
	private fun targetLongEdge(): Int {
		val metrics = context.resources.displayMetrics

		return photoTargetLongEdge(metrics.widthPixels, metrics.heightPixels)
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
	}
}

/** The long edge a stored photo is scaled to when the display metrics cannot be read at all, which is the size of a small phone from the era minSdk 26 dates to. */
private const val FALLBACK_LONG_EDGE = 1280

/**
 * The long edge to store a photo at for a display of [widthPixels] by [heightPixels], which is simply the longer of the two.
 * No floor: a 540x960 or 480x854 phone is a real minSdk 26 device rather than a bad read, and storing more pixels than it can show would have the render thread decode close to twice the bitmap it needs on exactly the devices with the least heap to spare.
 * [FALLBACK_LONG_EDGE] stands in only for a non-positive read, which is not a small display but an absent one, and scaling a photo to that would store nothing at all.
 */
internal fun photoTargetLongEdge(widthPixels: Int, heightPixels: Int): Int {
	val longEdge = max(widthPixels, heightPixels)
	if (longEdge <= 0) {
		return FALLBACK_LONG_EDGE
	}

	return longEdge
}

/**
 * The `inSampleSize` to decode a [sourceWidth] by [sourceHeight] image with so its long edge lands at or just above [targetLongEdge].
 * Always a power of two, because BitmapFactory rounds anything else up to one anyway.
 * Rounds the ratio down rather than up: a sample size that undershot the target would decode fewer pixels than the display has, and the import scales down only, so that softness would be baked into the stored file forever.
 * Returns 1 for a degenerate or undecodable size instead of dividing by zero.
 * Only the pre-P import route needs this; [ImageDecoder] is told the target size and picks its own sample size from it.
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
