package xyz.attacktive.weatherd.domain.render

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Shared deck-relative anchors overlap the three planes instead of spacing them into screen-height rows. */
internal enum class StormCloudPlane(val widthScale: Float, val depthScale: Float, val bottom: Float, val opacity: Float, val speed: Double, val phase: Double) {
	VEIL(1.42f, 0.82f, 1.32f, 0.50f, 0.007, 1.0),
	DISTANT(1.35f, 0.28f, 1.03f, 0.58f, 0.011, 0.8),
	UPPER(1.35f, 0.50f, 0.40f, 0.96f, 0.019, 2.4),
	HERO(1.18f, 0.86f, 0.94f, 0.92f, 0.027, 4.0)
}

/** Reused uniform geometry; wide screens crop the artwork rather than squeezing it into a short band. */
internal class StormCloudGeometry {
	var left = 0f
		private set
	var top = 0f
		private set
	var width = 0f
		private set
	var height = 0f
		private set

	fun configure(viewportWidth: Float, viewportHeight: Float, sourceWidth: Int, sourceHeight: Int, plane: StormCloudPlane, timeSeconds: Float, windFactor: Float, windScale: Float, sizeScale: Float): Boolean {
		if (!viewportWidth.isFinite() || !viewportHeight.isFinite() || viewportWidth <= 0f || viewportHeight <= 0f || sourceWidth <= 0 || sourceHeight <= 0) {
			return false
		}

		val aspect = sourceWidth.toFloat() / sourceHeight
		val size = finiteOr(sizeScale, 1f).coerceIn(0.5f, 2f)
		val depth = min(viewportHeight * 0.62f, max(viewportHeight * 0.48f, viewportWidth * 0.70f))
		height = max(viewportWidth * plane.widthScale / aspect, depth * plane.depthScale) * size
		width = height * aspect
		val time = finiteOr(timeSeconds, 0f).toDouble()
		val wind = finiteOr(windFactor, 0f).coerceIn(0f, 3f)
		val windMultiplier = finiteOr(windScale, 0f).coerceIn(0f, 3f)
		val angle = (time * plane.speed * (0.35 + wind) * windMultiplier) % (2.0 * PI)
		val drift = viewportWidth * 0.045f * sin(angle + plane.phase).toFloat()
		left = (viewportWidth - width) / 2f + drift
		top = depth * size * plane.bottom - height
		return true
	}

	private fun finiteOr(value: Float, fallback: Float) = if (value.isFinite()) {
		value
	} else {
		fallback
	}
}
