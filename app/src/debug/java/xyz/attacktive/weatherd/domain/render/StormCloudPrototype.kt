package xyz.attacktive.weatherd.domain.render

import kotlin.math.roundToInt
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import xyz.attacktive.weatherd.BuildConfig
import xyz.attacktive.weatherd.domain.model.CLOUD_CONTRAST_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.PrecipitationKind

/** Opt-in, debug-only #405 experiment; the release source set supplies an inert implementation. */
internal class StormCloudPrototype(
	private val resources: Resources,
	private val enabled: Boolean = BuildConfig.STORM_CLOUD_PROTOTYPE,
	private val decode: (Resources, StormCloudPlane) -> Bitmap = ::decodeStormCloud
) {
	@Volatile private var sprites: List<StormCloudSprite>? = null

	/** Invoked by the existing off-thread cloud prewarm, never from a draw callback. */
	@Synchronized
	fun prewarm() {
		if (!enabled || sprites != null) {
			return
		}

		val prepared = mutableListOf<StormCloudSprite>()
		try {
			for (plane in StormCloudPlane.entries) {
				prepared.add(StormCloudSprite(plane, decode(resources, plane)))
			}

			sprites = prepared.toList()
		} catch (exception: Exception) {
			prepared.forEach { it.bitmap.recycle() }
			throw IllegalStateException("Prepare the #405 debug assets with scripts/prepare-storm-cloud-prototype.py before enabling the prototype.", exception)
		}
	}

	/** Returns false when the caller must retain the existing cloud-deck path. */
	fun draw(canvas: Canvas, width: Float, height: Float, params: SceneParams, timeSeconds: Float, tint: Int, grade: CloudColorGrade): Boolean {
		if (!enabled || params.precipitation?.kind != PrecipitationKind.RAIN) {
			return false
		}

		val ready = sprites ?: return false
		for (sprite in ready) {
			sprite.draw(canvas, width, height, params, timeSeconds, tint, grade)
		}

		return true
	}
}

private fun decodeStormCloud(resources: Resources, plane: StormCloudPlane) = resources.assets.open("storm-clouds/${plane.name.lowercase()}.png").use {
	checkNotNull(BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inScaled = false }))
}

private class StormCloudSprite(private val plane: StormCloudPlane, val bitmap: Bitmap) {
	private val geometry = StormCloudGeometry()
	private val destination = RectF()
	private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
	private val matrix = FloatArray(20)
	private var previousTint: Int? = null
	private var previousContrast = Float.NaN
	private var previousGrade = CloudColorGrade.IDENTITY

	fun draw(canvas: Canvas, width: Float, height: Float, params: SceneParams, timeSeconds: Float, tint: Int, grade: CloudColorGrade) {
		if (!geometry.configure(width, height, bitmap.width, bitmap.height, plane, timeSeconds, params.windFactor, params.windScale, params.cloudSizeScale)) {
			return
		}

		val opacity = if (params.cloudScale.isFinite()) {
			params.cloudScale.coerceAtLeast(0f)
		} else {
			0f
		}

		paint.alpha = (255f * opacity * plane.opacity).roundToInt().coerceIn(0, 255)
		if (paint.alpha == 0) {
			return
		}

		val contrast = if (params.cloudContrastScale.isFinite()) {
			params.cloudContrastScale.coerceIn(CLOUD_CONTRAST_SCALE_RANGE.start, CLOUD_CONTRAST_SCALE_RANGE.endInclusive)
		} else {
			1f
		}

		if (previousTint != tint || previousContrast != contrast || previousGrade != grade) {
			writeCloudColorMatrix(matrix, tint, contrast, grade)
			paint.colorFilter = ColorMatrixColorFilter(matrix)
			previousTint = tint
			previousContrast = contrast
			previousGrade = grade
		}

		destination.set(geometry.left, geometry.top, geometry.left + geometry.width, geometry.top + geometry.height)
		canvas.drawBitmap(bitmap, null, destination, paint)
	}
}
