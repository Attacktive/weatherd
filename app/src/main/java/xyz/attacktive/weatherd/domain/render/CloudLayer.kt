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

	private var previousTint = Color.WHITE

	fun draw(canvas: Canvas, width: Float, height: Float, offset: Float, tint: Int, alpha: Int, top: Float = 0f) {
		if (width <= 0f || height <= 0f || alpha <= 0) {
			return
		}

		transform.setScale(width * CLOUD_TEXTURE_VIEWPORTS / bitmap.width, height / bitmap.height)
		transform.postTranslate(offset, top)
		cloudShader.setLocalMatrix(transform)
		if (tint != previousTint) {
			paint.colorFilter = if (tint == Color.WHITE) {
				null
			} else {
				LightingColorFilter(tint, Color.BLACK)
			}

			previousTint = tint
		}

		paint.alpha = alpha.coerceIn(0, 255)
		canvas.drawRect(0f, top, width, top + height, paint)
	}
}
