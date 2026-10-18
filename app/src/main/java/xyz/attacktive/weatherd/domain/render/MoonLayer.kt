package xyz.attacktive.weatherd.domain.render

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import xyz.attacktive.weatherd.R

/**
 * Realistic lunar surface layer based on NASA SVS orthographic albedo imagery.
 * Clips and shades the spherical near side for synodic phase illumination without mirroring lunar surface features.
 */
internal class MoonLayer(private val resources: Resources) {
	private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
	private val destination = RectF()
	private val phaseCache = object : LinkedHashMap<Int, Bitmap>(16, 0.75f, true) {
		override fun removeEldestEntry(eldest: Map.Entry<Int, Bitmap>?) = size > MAX_CACHED_PHASE_SPRITES
	}

	val textureReady: Boolean
		get() = source != null

	/** Decodes the base NASA lunar texture off the render thread. */
	fun prewarm() {
		if (textureReady) {
			return
		}

		loadSource()
	}

	fun phaseIndexFor(moonPhase: Float) = (normalizedPhase(moonPhase) * MOON_PHASE_STEPS).roundToInt() % MOON_PHASE_STEPS

	fun quantizedPhaseFor(moonPhase: Float) = phaseIndexFor(moonPhase).toFloat() / MOON_PHASE_STEPS

	/** Retrieves or builds a cached phase sprite for the given quantized phase step. */
	fun getOrCreateSprite(phaseIndex: Int): Bitmap {
		val clampedIndex = ((phaseIndex % MOON_PHASE_STEPS) + MOON_PHASE_STEPS) % MOON_PHASE_STEPS
		synchronized(phaseCache) {
			phaseCache[clampedIndex]?.let { return it }
		}

		val (sourceBitmap, pixels) = loadSource()
		val sprite = buildSprite(clampedIndex, sourceBitmap, pixels)
		synchronized(phaseCache) {
			phaseCache[clampedIndex] = sprite
		}

		return sprite
	}

	/** Draws the phase-illuminated lunar disc into the target canvas. */
	fun draw(canvas: Canvas, centerX: Float, centerY: Float, radius: Float, moonPhase: Float) {
		val phaseIndex = phaseIndexFor(moonPhase)
		if (phaseIndex == 0) {
			return
		}

		val sprite = getOrCreateSprite(phaseIndex)
		destination.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
		canvas.drawBitmap(sprite, null, destination, paint)
	}

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
		if (phaseIndex == 0) {
			return Bitmap.createBitmap(MOON_SPRITE_SIZE, MOON_SPRITE_SIZE, Bitmap.Config.ARGB_8888)
		}

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
