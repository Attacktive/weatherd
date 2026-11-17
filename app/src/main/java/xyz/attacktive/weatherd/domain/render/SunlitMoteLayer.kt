package xyz.attacktive.weatherd.domain.render

import kotlin.math.roundToInt
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader

internal class SunlitMoteLayer {
	private val field = SunlitMoteField()
	private val glow = RadialGradient(0f, 0f, 1f, Color.rgb(255, 233, 185), Color.argb(0, 255, 233, 185), Shader.TileMode.CLAMP)
	private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = glow }

	fun draw(canvas: Canvas, span: Float, centerX: Float, centerY: Float, sunRadius: Float, timeSeconds: Float, strength: Float) {
		if (strength <= 0f) {
			return
		}

		field.update(span, centerX, centerY, sunRadius, timeSeconds)
		for (index in field.x.indices) {
			val radius = field.radius[index] * 2f
			val checkpoint = canvas.save()
			canvas.translate(field.x[index], field.y[index])
			canvas.scale(radius, radius)
			paint.alpha = (190f * strength * field.opacity[index]).roundToInt().coerceIn(0, 255)
			canvas.drawCircle(0f, 0f, 1f, paint)
			canvas.restoreToCount(checkpoint)
		}
	}
}
