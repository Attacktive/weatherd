package xyz.attacktive.weatherd.domain.render

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import xyz.attacktive.weatherd.domain.model.normalizeCloudSizeScale

/** Shared deck-relative anchors overlap the three planes instead of spacing them into screen-height rows. */
internal enum class StormCloudPlane(val widthScale: Float, val depthScale: Float, val bottom: Float, val opacity: Float, val scrollRate: Double, val phase: Double) {
	VEIL(1.42f, 0.82f, 1.32f, 0.50f, 0.0007, 1.0),
	DISTANT(1.35f, 0.28f, 1.03f, 0.58f, 0.0011, 0.8),
	UPPER(1.35f, 0.50f, 0.40f, 0.96f, 0.0019, 2.4),
	HERO(1.18f, 0.86f, 0.94f, 0.92f, 0.0027, 4.0)
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

	private var lastTimeSeconds: Double? = null
	private var travel = 0.0

	fun configure(viewportWidth: Float, viewportHeight: Float, sourceWidth: Int, sourceHeight: Int, plane: StormCloudPlane, timeSeconds: Float, windFactor: Float, windScale: Float, sizeScale: Float): Boolean {
		if (!validSourceAndViewport(viewportWidth, viewportHeight, sourceWidth, sourceHeight)) {
			return false
		}

		val aspect = sourceWidth.toFloat() / sourceHeight
		val size = normalizeCloudSizeScale(sizeScale)
		val depth = min(viewportHeight * 0.62f, max(viewportHeight * 0.48f, viewportWidth * 0.70f))
		height = max(viewportWidth * plane.widthScale / aspect, depth * plane.depthScale) * size
		width = height * aspect
		val time = if (timeSeconds.isFinite()) timeSeconds.toDouble() else lastTimeSeconds ?: 0.0
		val elapsed = lastTimeSeconds?.let { max(0.0, time - it) } ?: 0.0
		lastTimeSeconds = time
		val wind = finiteOr(windFactor, 0f).coerceIn(0f, 3f)
		val windMultiplier = finiteOr(windScale, 0f).coerceIn(0f, 3f)
		val windResponse = windMultiplier * (1f + max(0f, windMultiplier - 1f) * 2f)
		val velocity = viewportWidth * plane.scrollRate * (0.35 + wind) * windResponse
		travel = (travel + elapsed * velocity) % width.toDouble()
		left = (viewportWidth - width) / 2f + viewportWidth * 0.045f * sin(plane.phase).toFloat() + travel.toFloat()
		top = depth * size * plane.bottom - height - viewportHeight * RAIN_CLOUD_LIFT_FRACTION
		return true
	}

	/** Keep the top of the screen inside the upper bank's covered band without stretching it. */
	fun coverTopEdge(profile: CloudAlphaProfile) {
		if (profile.coveredTop.isFinite() && profile.coveredBottom.isFinite()) {
			top = top.coerceIn(-height * profile.coveredBottom, -height * profile.coveredTop)
		}
	}

	private fun validSourceAndViewport(viewportWidth: Float, viewportHeight: Float, sourceWidth: Int, sourceHeight: Int) =
		viewportWidth.isFinite() && viewportHeight.isFinite() && viewportWidth > 0f && viewportHeight > 0f &&
			sourceWidth > 0 && sourceHeight > 0

	private fun finiteOr(value: Float, fallback: Float) = if (value.isFinite()) {
		value
	} else {
		fallback
	}
}

private const val RAIN_CLOUD_LIFT_FRACTION = 0.15f
