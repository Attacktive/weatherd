package xyz.attacktive.weatherd.domain.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import kotlin.math.roundToInt
import xyz.attacktive.weatherd.domain.model.normalizeMoonOpacity

/** Separates lunar light transmission from star blocking without changing cloud artwork or painting a black disc. */
internal class MoonOcclusionLayer {
	private var phase: MoonLayer.PreparedPhase? = null
	private val bounds = RectF()
	private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
	private var opacity = 1f
	private val erase = Paint(Paint.FILTER_BITMAP_FLAG).apply {
		xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
	}

	private var cloudMask: CloudMask? = null
	private var hasOcclusion = false
	private var starMaskReady = false
	private var cachedParams: SceneParams? = null
	private var cachedWidth = 0f
	private var cachedHeight = 0f
	private var cachedTime = 0f
	private var cachedLayoutDay = 0L

	fun configure(phase: MoonLayer.PreparedPhase?, centerX: Float, centerY: Float, radius: Float, moonOpacity: Float = 1f) {
		val normalizedOpacity = normalizeMoonOpacity(moonOpacity)
		val left = centerX - radius
		val top = centerY - radius
		val right = centerX + radius
		val bottom = centerY + radius
		if (this.phase !== phase || !matchesBounds(left, top, right, bottom) || opacity != normalizedOpacity) {
			clearCloudMask()
		}

		this.phase = phase
		opacity = normalizedOpacity
		paint.alpha = (255f * normalizedOpacity).roundToInt()
		bounds.set(left, top, right, bottom)
	}

	private fun matchesBounds(left: Float, top: Float, right: Float, bottom: Float) =
		bounds.left == left && bounds.top == top && bounds.right == right && bounds.bottom == bottom

	/** Rasterize the existing cloud draw path in scene coordinates, clipped to a bounded lunar-local bitmap. */
	fun beginCloudMask(width: Float, height: Float, params: SceneParams, timeSeconds: Float, layoutDay: Long): Canvas? {
		if (phase == null || bounds.isEmpty) {
			return null
		}

		val motionTime = if (params.windScale == 0f) 0f else timeSeconds
		if (matchesCloudInputs(width, height, params, motionTime, layoutDay)) {
			return null
		}

		cachedParams = params
		cachedWidth = width
		cachedHeight = height
		cachedTime = motionTime
		cachedLayoutDay = layoutDay
		val mask = cloudMask ?: CloudMask().also { cloudMask = it }
		mask.bitmap.eraseColor(Color.TRANSPARENT)
		mask.canvas.save()
		mask.canvas.scale(MASK_SIZE / bounds.width(), MASK_SIZE / bounds.height())
		mask.canvas.translate(-bounds.left, -bounds.top)
		return mask.canvas
	}

	private fun matchesCloudInputs(width: Float, height: Float, params: SceneParams, motionTime: Float, layoutDay: Long) =
		cachedParams == params && cachedWidth == width && cachedHeight == height && cachedTime == motionTime && cachedLayoutDay == layoutDay

	fun finishCloudMask() {
		val mask = checkNotNull(cloudMask)
		mask.canvas.restore()
		mask.bitmap.getPixels(mask.pixels, 0, MASK_SIZE, 0, 0, MASK_SIZE, MASK_SIZE)
		hasOcclusion = false
		starMaskReady = false
		for (index in mask.pixels.indices) {
			val alpha = OCCLUSION_ALPHA[mask.pixels[index] ushr 24]
			mask.pixels[index] = alpha shl 24
			hasOcclusion = hasOcclusion || alpha != 0
		}

		if (hasOcclusion) {
			mask.bitmap.setPixels(mask.pixels, 0, MASK_SIZE, 0, 0, MASK_SIZE, MASK_SIZE)
		}
	}

	fun clearCloudMask() {
		cachedParams = null
		hasOcclusion = false
	}

	/** Only intersecting stars need isolation, and their layers span their own bounds rather than the viewport. */
	fun beginStar(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float): Int {
		if (!hasOcclusion || phase == null || right < bounds.left || left > bounds.right || bottom < bounds.top || top > bounds.bottom) {
			return -1
		}

		if (!starMaskReady) {
			prepareStarMask()
		}

		return canvas.saveLayer(left - 1f, top - 1f, right + 1f, bottom + 1f, null)
	}

	fun endStar(canvas: Canvas, saveCount: Int) {
		if (saveCount < 0) {
			return
		}

		canvas.drawBitmap(checkNotNull(cloudMask).starBitmap, null, bounds, erase)
		canvas.restoreToCount(saveCount)
	}

	fun drawDisc(canvas: Canvas) {
		val currentSprite = phase?.sprite ?: return
		if (!hasOcclusion) {
			canvas.drawBitmap(currentSprite, null, bounds, paint)
			return
		}

		val layer = canvas.saveLayer(bounds, null)
		canvas.drawBitmap(currentSprite, null, bounds, paint)
		canvas.drawBitmap(checkNotNull(cloudMask).bitmap, null, bounds, erase)
		canvas.restoreToCount(layer)
	}

	/**
	 * With lunar-disc opacity P, the disc attenuates stars by 1 - A*P*(1-O), where A is phase alpha and O is cloud occlusion.
	 * Stars must remain blocked by dense clouds even when the Moon is made transparent: E = A*O / (1-A*P*(1-O)).
	 * At P=1, this reduces to the original full-opacity compensation.
	 */
	private fun prepareStarMask() {
		val mask = checkNotNull(cloudMask)
		val phaseAlpha = checkNotNull(phase).occlusionAlpha
		for (index in mask.pixels.indices) {
			val lunarAlpha = phaseAlpha[index].toInt() and 255
			val cloudAlpha = mask.pixels[index] ushr 24
			val missingOpacity = lunarAlpha * cloudAlpha
			val visibleDiscOpacity = (lunarAlpha * (255 - cloudAlpha) * opacity).roundToInt()
			val denominator = 255 * 255 - visibleDiscOpacity
			val alpha = if (missingOpacity == 0) 0 else (255 * missingOpacity + denominator / 2) / denominator
			mask.pixels[index] = alpha shl 24
		}

		mask.starBitmap.setPixels(mask.pixels, 0, MASK_SIZE, 0, 0, MASK_SIZE, MASK_SIZE)
		starMaskReady = true
	}

	private class CloudMask {
		val bitmap = Bitmap.createBitmap(MASK_SIZE, MASK_SIZE, Bitmap.Config.ARGB_8888)
		val starBitmap by lazy(LazyThreadSafetyMode.NONE) { Bitmap.createBitmap(MASK_SIZE, MASK_SIZE, Bitmap.Config.ARGB_8888) }
		val canvas = Canvas(bitmap)
		val pixels = IntArray(MASK_SIZE * MASK_SIZE)
	}

	companion object {
		const val MASK_SIZE = 128
		private val OCCLUSION_ALPHA = IntArray(256) { alpha ->
			// Dense-looking cumulus blocks lunar surface detail, while wisps retain their ordinary foreground translucency.
			val strength = ((alpha / 255f - 0.15f) / 0.60f).coerceIn(0f, 1f)
			(255f * strength * strength * (3f - 2f * strength)).toInt()
		}
	}
}
