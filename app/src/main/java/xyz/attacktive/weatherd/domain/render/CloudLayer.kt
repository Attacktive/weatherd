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

/** How many viewport widths a texture covers before repeating, unless a deck asks for its own span. */
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

	fun draw(
		canvas: Canvas,
		width: Float,
		height: Float,
		offset: Float,
		tint: Int,
		alpha: Int,
		top: Float = 0f,
		viewports: Float = CLOUD_TEXTURE_VIEWPORTS
	) {
		draw(canvas, width, height, offset, tint, Color.BLACK, alpha, top, viewports)
	}

	fun draw(
		canvas: Canvas,
		width: Float,
		height: Float,
		offset: Float,
		multiplyColor: Int,
		addColor: Int,
		alpha: Int,
		top: Float = 0f,
		viewports: Float = CLOUD_TEXTURE_VIEWPORTS
	) {
		if (width <= 0f || height <= 0f || alpha <= 0) {
			return
		}

		/*
		 * Fewer viewports shrink the sampled features, which is the only handle a deck has on apparent cloud size: the shader always stretches the texture across `width * viewports`.
		 * A far deck asks for a smaller span so its clouds read as smaller and further away while sharing the texture's pixels.
		 */
		transform.setScale(width * viewports / bitmap.width, height / bitmap.height)
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

}
