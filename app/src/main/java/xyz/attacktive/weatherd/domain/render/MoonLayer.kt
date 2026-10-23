package xyz.attacktive.weatherd.domain.render

import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import xyz.attacktive.weatherd.R

/**
 * Prepares phase-shaded immutable sprites on a background thread without mirroring lunar surface features.
 * Rendering only reads atomically published sprites and never waits for preparation.
 */
internal class MoonLayer(private val resources: Resources) {
	private val phaseSprites = AtomicReferenceArray<PreparedPhase>(MOON_PHASE_STEPS)
	private val phaseOrder = IntArray(MAX_CACHED_PHASE_SPRITES) { -1 }
	private val preparationLock = Any()
	private var nextPhaseSlot = 0

	fun isPhaseReady(moonPhase: Float): Boolean {
		val phaseIndex = phaseIndexFor(moonPhase)
		return phaseIndex == 0 || spriteFor(phaseIndex) != null
	}

	/** Decodes the source and prepares only the requested phase; call from a background dispatcher. */
	fun prewarm(moonPhase: Float) {
		val phaseIndex = phaseIndexFor(moonPhase)
		if (phaseIndex == 0 || spriteFor(phaseIndex) != null) {
			return
		}

		synchronized(preparationLock) {
			if (spriteFor(phaseIndex) != null) {
				return
			}

			val (sourceBitmap, pixels) = loadSource()
			val sprite = buildSprite(phaseIndex, sourceBitmap, pixels)
			val evictedPhase = phaseOrder[nextPhaseSlot]
			if (evictedPhase >= 0) {
				phaseSprites.set(evictedPhase, null)
			}

			phaseOrder[nextPhaseSlot] = phaseIndex
			nextPhaseSlot = (nextPhaseSlot + 1) % MAX_CACHED_PHASE_SPRITES
			phaseSprites.set(phaseIndex, sprite)
		}
	}

	fun phaseIndexFor(moonPhase: Float) = (normalizedPhase(moonPhase) * MOON_PHASE_STEPS).roundToInt() % MOON_PHASE_STEPS

	/** An evicted sprite is never recycled: a frame may still hold the returned immutable bitmap. */
	fun spriteFor(phaseIndex: Int): Bitmap? = preparedFor(phaseIndex)?.sprite

	/** Publish the phase art, illumination probes, and occlusion alpha atomically so a frame cannot mix phases. */
	fun preparedFor(phaseIndex: Int): PreparedPhase? = phaseSprites.get(phaseIndex)

	/** Prepared only off the rendering thread; all arrays are immutable once published. */
	class PreparedPhase(val sprite: Bitmap, val cloudSamples: FloatArray, val occlusionAlpha: ByteArray)

	private fun normalizedPhase(phase: Float) = ((phase % 1f) + 1f) % 1f

	private fun loadSource(): Pair<Bitmap, IntArray> = synchronized(MoonLayer::class.java) {
		val currentBitmap = source
		val currentPixels = sourcePixels
		if (currentBitmap != null && currentPixels != null) {
			return currentBitmap to currentPixels
		}

		val options = BitmapFactory.Options().apply { inScaled = false }
		val bitmap = checkNotNull(BitmapFactory.decodeResource(resources, R.drawable.moon_surface, options)) {
			"Failed to decode moon surface texture from R.drawable.moon_surface"
		}

		val pixels = IntArray(MOON_TEXTURE_SIZE * MOON_TEXTURE_SIZE)
		bitmap.getPixels(pixels, 0, MOON_TEXTURE_SIZE, 0, 0, MOON_TEXTURE_SIZE, MOON_TEXTURE_SIZE)

		sourcePixels = pixels
		source = bitmap

		bitmap to pixels
	}

	private fun buildSprite(phaseIndex: Int, sourceBitmap: Bitmap, sourcePixels: IntArray): PreparedPhase {
		val quantizedPhase = phaseIndex.toFloat() / MOON_PHASE_STEPS
		if (phaseIndex == MOON_PHASE_STEPS / 2) {
			return PreparedPhase(sourceBitmap, buildCloudSamples(sourcePixels), buildOcclusionAlpha(sourcePixels))
		}

		val waning = quantizedPhase > 0.5f
		val waxingPhase = if (waning) {
			1f - quantizedPhase
		} else {
			quantizedPhase
		}

		val alpha = waxingPhase * (2.0 * PI).toFloat()
		val sinA = if (waning) {
			-sin(alpha)
		} else {
			sin(alpha)
		}

		val cosA = cos(alpha)

		val center = MOON_SPRITE_SIZE / 2f
		val radius = center
		val destPixels = IntArray(MOON_SPRITE_SIZE * MOON_SPRITE_SIZE)

		for (py in 0 until MOON_SPRITE_SIZE) {
			val y = (py + 0.5f - center) / radius
			val y2 = y * y
			if (y2 >= 1f) {
				continue
			}

			val rowOffset = py * MOON_SPRITE_SIZE
			for (px in 0 until MOON_SPRITE_SIZE) {
				val x = (px + 0.5f - center) / radius
				val pixelIndex = rowOffset + px
				destPixels[pixelIndex] = illuminatedPixel(sourcePixels[pixelIndex], x, y2, sinA, cosA)
			}
		}

		val sprite = Bitmap.createBitmap(destPixels, MOON_SPRITE_SIZE, MOON_SPRITE_SIZE, Bitmap.Config.ARGB_8888)

		return PreparedPhase(sprite, buildCloudSamples(destPixels), buildOcclusionAlpha(destPixels))
	}

	/** Bilinear half-texel samples for the bounded mask, computed once from the prepared phase rather than on a frame. */
	private fun buildOcclusionAlpha(pixels: IntArray): ByteArray {
		val size = MoonOcclusionLayer.MASK_SIZE
		val step = MOON_SPRITE_SIZE / size
		return ByteArray(size * size) { index ->
			val x = index % size * step + step / 2 - 1
			val y = index / size * step + step / 2 - 1
			val offset = y * MOON_SPRITE_SIZE + x
			val alpha = (pixels[offset] ushr 24) + (pixels[offset + 1] ushr 24) + (pixels[offset + MOON_SPRITE_SIZE] ushr 24) + (pixels[offset + MOON_SPRITE_SIZE + 1] ushr 24)
			((alpha + 2) / 4).toByte()
		}
	}

	/**
	 * Stratifies the prepared phase into nine regions and chooses an actually illuminated pixel nearest each
	 * region's alpha-weighted center. Clouds over the invisible side cannot darken the lit crescent.
	 * This full bitmap scan happens during phase preparation, never from a render callback.
	 */
	private fun buildCloudSamples(pixels: IntArray): FloatArray {
		val cells = CLOUD_SAMPLE_GRID * CLOUD_SAMPLE_GRID
		val alphaTotals = LongArray(cells)
		val weightedX = LongArray(cells)
		val weightedY = LongArray(cells)
		accumulateCloudSampleCenters(pixels, alphaTotals, weightedX, weightedY)

		val sampleX = IntArray(cells) { -1 }
		val sampleY = IntArray(cells) { -1 }
		locateIlluminatedSamples(pixels, alphaTotals, weightedX, weightedY, sampleX, sampleY)

		val totalAlpha = alphaTotals.sum()
		if (totalAlpha == 0L) {
			return FloatArray(0)
		}

		val samples = FloatArray(sampleX.count { it >= 0 } * 3)
		val radius = MOON_SPRITE_SIZE / 2f
		var index = 0
		for (cell in 0 until cells) {
			if (sampleX[cell] < 0) {
				continue
			}

			samples[index++] = (sampleX[cell] + 0.5f - radius) / radius
			samples[index++] = (sampleY[cell] + 0.5f - radius) / radius
			samples[index++] = alphaTotals[cell].toFloat() / totalAlpha
		}

		return samples
	}

	private fun accumulateCloudSampleCenters(pixels: IntArray, alphaTotals: LongArray, weightedX: LongArray, weightedY: LongArray) {
		for (y in 0 until MOON_SPRITE_SIZE) {
			for (x in 0 until MOON_SPRITE_SIZE) {
				val alpha = pixels[y * MOON_SPRITE_SIZE + x] ushr 24
				if (alpha < CLOUD_SAMPLE_MIN_ALPHA) {
					continue
				}

				val cell = y * CLOUD_SAMPLE_GRID / MOON_SPRITE_SIZE * CLOUD_SAMPLE_GRID + x * CLOUD_SAMPLE_GRID / MOON_SPRITE_SIZE
				alphaTotals[cell] += alpha.toLong()
				weightedX[cell] += x.toLong() * alpha
				weightedY[cell] += y.toLong() * alpha
			}
		}
	}

	private fun locateIlluminatedSamples(pixels: IntArray, alphaTotals: LongArray, weightedX: LongArray, weightedY: LongArray, sampleX: IntArray, sampleY: IntArray) {
		val closestDistance = FloatArray(alphaTotals.size) { Float.POSITIVE_INFINITY }
		for (y in 0 until MOON_SPRITE_SIZE) {
			for (x in 0 until MOON_SPRITE_SIZE) {
				val alpha = pixels[y * MOON_SPRITE_SIZE + x] ushr 24
				if (alpha < CLOUD_SAMPLE_MIN_ALPHA) {
					continue
				}

				val cell = y * CLOUD_SAMPLE_GRID / MOON_SPRITE_SIZE * CLOUD_SAMPLE_GRID + x * CLOUD_SAMPLE_GRID / MOON_SPRITE_SIZE
				val centerX = weightedX[cell].toFloat() / alphaTotals[cell]
				val centerY = weightedY[cell].toFloat() / alphaTotals[cell]
				val dx = x - centerX
				val dy = y - centerY
				val distance = dx * dx + dy * dy
				if (distance < closestDistance[cell]) {
					closestDistance[cell] = distance
					sampleX[cell] = x
					sampleY[cell] = y
				}
			}
		}
	}

	private fun illuminatedPixel(sourcePixel: Int, x: Float, ySquared: Float, lightX: Float, lightZ: Float): Int {
		val radiusSquared = x * x + ySquared
		if (radiusSquared >= 1f) {
			return 0
		}

		val incidence = x * lightX - sqrt(1f - radiusSquared) * lightZ
		if (incidence <= 0f) {
			return 0
		}

		val t = incidence / TERMINATOR_FALLOFF_ZONE
		val smooth = if (t < 1f) {
			t * t * (3f - 2f * t)
		} else {
			1f
		}

		val sourceAlpha = (sourcePixel ushr 24) and 0xFF
		val alpha = (sourceAlpha * smooth).roundToInt().coerceIn(0, 255)
		if (alpha == 0) {
			return 0
		}

		val shade = 0.35f + 0.65f * smooth
		val red = (((sourcePixel ushr 16) and 0xFF) * shade).roundToInt().coerceIn(0, 255)
		val green = (((sourcePixel ushr 8) and 0xFF) * shade).roundToInt().coerceIn(0, 255)
		val blue = ((sourcePixel and 0xFF) * shade).roundToInt().coerceIn(0, 255)
		return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
	}

	companion object {
		const val MOON_TEXTURE_SIZE = 512
		const val MOON_SPRITE_SIZE = 512
		const val MOON_PHASE_STEPS = 64
		private const val MAX_CACHED_PHASE_SPRITES = 16
		private const val CLOUD_SAMPLE_GRID = 3
		private const val CLOUD_SAMPLE_MIN_ALPHA = 64
		private const val TERMINATOR_FALLOFF_ZONE = 0.15f

		@Volatile
		private var source: Bitmap? = null

		@Volatile
		private var sourcePixels: IntArray? = null
	}
}
