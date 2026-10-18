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
	private val phaseSprites = AtomicReferenceArray<Bitmap>(MOON_PHASE_STEPS)
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
	fun spriteFor(phaseIndex: Int): Bitmap? = phaseSprites.get(phaseIndex)

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

	private fun buildSprite(phaseIndex: Int, sourceBitmap: Bitmap, sourcePixels: IntArray): Bitmap {
		val quantizedPhase = phaseIndex.toFloat() / MOON_PHASE_STEPS
		if (phaseIndex == MOON_PHASE_STEPS / 2) {
			return sourceBitmap
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

		return Bitmap.createBitmap(destPixels, MOON_SPRITE_SIZE, MOON_SPRITE_SIZE, Bitmap.Config.ARGB_8888)
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
		private const val TERMINATOR_FALLOFF_ZONE = 0.15f

		@Volatile
		private var source: Bitmap? = null

		@Volatile
		private var sourcePixels: IntArray? = null
	}
}
