package xyz.attacktive.weatherd.domain.render

import kotlin.math.roundToInt
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import xyz.attacktive.weatherd.domain.model.normalizeReadabilityStrength

internal class ReadabilityOverlayLayer {
	private val dimmingPaint = Paint().apply {
		style = Paint.Style.FILL
	}

	private val shadePaint = Paint().apply {
		style = Paint.Style.FILL
	}

	private var cachedViewportHeight = 0f

	fun draw(canvas: Canvas, width: Float, height: Float, params: SceneParams) {
		val dimmingStrength = normalizeReadabilityStrength(params.wallpaperDimmingStrength)
		val shadeStrength = normalizeReadabilityStrength(params.statusBarShadeStrength)
		if (dimmingStrength <= 0f && shadeStrength <= 0f) {
			return
		}

		if (dimmingStrength > 0f) {
			val alpha = (255f * dimmingStrength).roundToInt()
			dimmingPaint.color = Color.argb(alpha, 0, 0, 0)
			canvas.drawRect(0f, 0f, width, height, dimmingPaint)
		}

		if (shadeStrength > 0f && height > 0f && width > 0f) {
			val shadeHeight = height * GRADIENT_HEIGHT_FRACTION
			if (height != cachedViewportHeight) {
				cachedViewportHeight = height
				shadePaint.shader = LinearGradient(
					0f,
					0f,
					0f,
					shadeHeight,
					Color.BLACK,
					Color.TRANSPARENT,
					Shader.TileMode.CLAMP
				)
			}

			val alpha = (255f * shadeStrength).roundToInt()
			shadePaint.alpha = alpha
			canvas.drawRect(0f, 0f, width, shadeHeight, shadePaint)
		}
	}

	private companion object {
		const val GRADIENT_HEIGHT_FRACTION = 0.18f
	}
}
