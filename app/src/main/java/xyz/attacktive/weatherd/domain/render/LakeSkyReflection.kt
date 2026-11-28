package xyz.attacktive.weatherd.domain.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withScale

internal const val LAKE_WATER_START = 598f / 900f
internal const val LAKE_REFLECTION_SKY_SPAN = (1f - LAKE_WATER_START) * 1.43f
internal const val LAKE_REFLECTION_SKY_TOP = LAKE_WATER_START - LAKE_REFLECTION_SKY_SPAN

/** Caches the static reflected sky slice and composites animated cloud decks for each rendered timestamp. */
internal class LakeSkyReflection {
	private val backdropPixels = createBitmap(WIDTH, HEIGHT)
	private val backdropCanvas = Canvas(backdropPixels)
	private val pixels = createBitmap(WIDTH, HEIGHT)
	private val canvas = Canvas(pixels)
	private val copyPaint = Paint().apply {
		xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
	}

	private var lastBackdropSignature: SceneParams? = null
	private var lastBackdropWidth = 0
	private var lastBackdropHeight = 0
	private var lastParams: SceneParams? = null
	private var lastWidth = 0
	private var lastHeight = 0
	private var lastTimeSeconds = Float.NaN
	private var lastCloudReadiness = Int.MIN_VALUE

	inline fun sample(width: Int, height: Int, params: SceneParams, timeSeconds: Float, cloudReadiness: Int, hasAnimatedClouds: Boolean, drawBackdrop: (Canvas) -> Unit, drawClouds: (Canvas) -> Unit): Bitmap {
		if (!isValidInput(width, height, timeSeconds)) {
			return backdropPixels
		}

		val signature = backdropSignature(params)
		val backdropChanged = needsBackdropRedraw(width, height, signature)
		if (backdropChanged) {
			renderReflectionSlice(backdropCanvas, width, height, drawBackdrop)
			recordBackdrop(width, height, signature)
		}

		if (!hasAnimatedClouds) {
			return backdropPixels
		}

		if (backdropChanged || needsCloudRedraw(width, height, params, timeSeconds, cloudReadiness)) {
			canvas.drawBitmap(backdropPixels, 0f, 0f, copyPaint)
			renderReflectionSlice(canvas, width, height, drawClouds)
			recordClouds(width, height, params, timeSeconds, cloudReadiness)
		}

		return pixels
	}

	@PublishedApi
	internal fun isValidInput(width: Int, height: Int, timeSeconds: Float) = width > 0 && height > 0 && timeSeconds.isFinite()

	@PublishedApi
	internal fun needsBackdropRedraw(width: Int, height: Int, signature: SceneParams) = lastBackdropWidth != width || lastBackdropHeight != height || lastBackdropSignature != signature

	@PublishedApi
	internal fun needsCloudRedraw(width: Int, height: Int, params: SceneParams, timeSeconds: Float, cloudReadiness: Int) = lastTimeSeconds != timeSeconds || lastWidth != width || lastHeight != height || lastCloudReadiness != cloudReadiness || lastParams != params

	@PublishedApi
	internal fun recordBackdrop(width: Int, height: Int, signature: SceneParams) {
		lastBackdropWidth = width
		lastBackdropHeight = height
		lastBackdropSignature = signature
	}

	@PublishedApi
	internal fun recordClouds(width: Int, height: Int, params: SceneParams, timeSeconds: Float, cloudReadiness: Int) {
		lastWidth = width
		lastHeight = height
		lastParams = params
		lastTimeSeconds = timeSeconds
		lastCloudReadiness = cloudReadiness
	}

	@PublishedApi
	internal inline fun renderReflectionSlice(target: Canvas, width: Int, height: Int, draw: (Canvas) -> Unit) {
		val x = target.width.toFloat() / width
		val y = target.height.toFloat() / (height * LAKE_REFLECTION_SKY_SPAN)

		target.withScale(x, y) {
			target.translate(0f, -height * LAKE_REFLECTION_SKY_TOP)
			draw(target)
		}
	}

	private companion object {
		const val WIDTH = 256
		const val HEIGHT = 128
	}
}
