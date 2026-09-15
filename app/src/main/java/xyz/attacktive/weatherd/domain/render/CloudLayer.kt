package xyz.attacktive.weatherd.domain.render

import android.content.res.Resources
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import androidx.annotation.DrawableRes

/** Each original texture covers four viewport widths before repeating. */
internal const val CLOUD_TEXTURE_VIEWPORTS = 4f

/** Reuses decoded cloud pixels, a sampling transform, and tint state between frames. */
internal class CloudLayer(resources: Resources, @DrawableRes texture: Int) {
	private val bitmap = checkNotNull(BitmapFactory.decodeResource(resources, texture, BitmapFactory.Options().apply { inScaled = false }))
	private val cloudShader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP)
	private val transform = Matrix()
	private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
		shader = cloudShader
	}

	private var previousMultiply = Color.WHITE
	private var previousAdd = Color.BLACK

	fun draw(canvas: Canvas, width: Float, height: Float, offset: Float, tint: Int, alpha: Int, top: Float = 0f) {
		draw(canvas, width, height, offset, tint, Color.BLACK, alpha, top)
	}

	fun draw(
		canvas: Canvas,
		width: Float,
		height: Float,
		offset: Float,
		multiplyColor: Int,
		addColor: Int,
		alpha: Int,
		top: Float = 0f
	) {
		if (width <= 0f || height <= 0f || alpha <= 0) {
			return
		}

		transform.setScale(width * CLOUD_TEXTURE_VIEWPORTS / bitmap.width, height / bitmap.height)
		transform.postTranslate(offset, top)
		cloudShader.setLocalMatrix(transform)
		updateColorFilter(multiplyColor, addColor)
		paint.alpha = alpha.coerceIn(0, 255)
		canvas.drawRect(0f, top, width, top + height, paint)
	}

	/**
	 * Draws the cloud sheet scaled uniformly based on target [height], preserving its native aspect ratio.
	 * This prevents wide or landscape displays from stretching clouds horizontally.
	 */
	fun drawUniform(canvas: Canvas, width: Float, height: Float, offset: Float, tint: Int, alpha: Int, top: Float = 0f) {
		drawUniform(canvas, width, height, offset, tint, Color.BLACK, alpha, top)
	}

	fun drawUniform(
		canvas: Canvas,
		width: Float,
		height: Float,
		offset: Float,
		multiplyColor: Int,
		addColor: Int,
		alpha: Int,
		top: Float = 0f
	) {
		if (width <= 0f || height <= 0f || alpha <= 0) {
			return
		}

		val scale = height / bitmap.height
		transform.setScale(scale, scale)
		transform.postTranslate(offset, top)
		cloudShader.setLocalMatrix(transform)
		updateColorFilter(multiplyColor, addColor)
		paint.alpha = alpha.coerceIn(0, 255)
		canvas.drawRect(0f, top, width, top + height, paint)
	}

	private fun updateColorFilter(multiplyColor: Int, addColor: Int) {
		if (multiplyColor != previousMultiply || addColor != previousAdd) {
			paint.colorFilter = if (multiplyColor == Color.WHITE && addColor == Color.BLACK) {
				null
			} else {
				LightingColorFilter(multiplyColor, addColor)
			}

			previousMultiply = multiplyColor
			previousAdd = addColor
		}
	}

	/** Returns the horizontal period in pixels for uniform drawing at target [height]. */
	fun period(height: Float): Float {
		val scale = height / bitmap.height
		return bitmap.width * scale
	}
}
