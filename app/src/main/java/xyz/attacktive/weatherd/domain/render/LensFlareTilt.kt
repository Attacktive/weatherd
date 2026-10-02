package xyz.attacktive.weatherd.domain.render

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sqrt

/** Allocation-free, screen-relative gravity tilt shared by every active scene surface. */
internal class LensFlareTilt {
	var offsetX = 0f
		private set
	var offsetY = 0f
		private set

	private var baselineX = 0f
	private var baselineY = 0f
	private var rotation = -1
	private var previousTimestampNanos = 0L

	fun update(gravityX: Float, gravityY: Float, gravityZ: Float, displayRotation: Int, timestampNanos: Long) {
		if (!isValidGravitySample(gravityX, gravityY, gravityZ, timestampNanos, previousTimestampNanos)) {
			return
		}

		val screenX = screenGravityX(gravityX, gravityY, displayRotation)
		val screenY = screenGravityY(gravityX, gravityY, displayRotation)
		val angleX = atan2(screenX, sqrt(screenY * screenY + gravityZ * gravityZ))
		val angleY = atan2(gravityZ, screenY)
		if (rotation != displayRotation) {
			baselineX = angleX
			baselineY = angleY
			rotation = displayRotation
			previousTimestampNanos = timestampNanos
			offsetX = 0f
			offsetY = 0f
			return
		}

		val elapsedSeconds = (timestampNanos - previousTimestampNanos) / NANOS_PER_SECOND
		val blend = 1f - exp(-elapsedSeconds / SMOOTHING_SECONDS)
		val targetX = (normalizeAngleDifference(angleX - baselineX) / FULL_TRAVEL_RADIANS).coerceIn(-1f, 1f)
		val targetY = (normalizeAngleDifference(angleY - baselineY) / FULL_TRAVEL_RADIANS).coerceIn(-1f, 1f)
		offsetX += (targetX - offsetX) * blend
		offsetY += (targetY - offsetY) * blend
		previousTimestampNanos = timestampNanos
	}

	/** The next valid reading becomes the neutral pose after the last surface stops rendering. */
	fun reset() {
		offsetX = 0f
		offsetY = 0f
		rotation = -1
		previousTimestampNanos = 0L
	}
}

private fun isValidGravitySample(gravityX: Float, gravityY: Float, gravityZ: Float, timestampNanos: Long, previousTimestampNanos: Long): Boolean {
	if (!gravityX.isFinite() || !gravityY.isFinite() || !gravityZ.isFinite() || timestampNanos <= previousTimestampNanos) {
		return false
	}

	val magnitudeSquared = gravityX * gravityX + gravityY * gravityY + gravityZ * gravityZ

	return magnitudeSquared >= MIN_GRAVITY_SQUARED && magnitudeSquared.isFinite()
}

private fun screenGravityX(gravityX: Float, gravityY: Float, displayRotation: Int) = when (displayRotation) {
	1 -> gravityY
	2 -> -gravityX
	3 -> -gravityY
	else -> gravityX
}

private fun screenGravityY(gravityX: Float, gravityY: Float, displayRotation: Int) = when (displayRotation) {
	1 -> -gravityX
	2 -> -gravityY
	3 -> gravityX
	else -> gravityY
}

private fun normalizeAngleDifference(delta: Float): Float {
	val twoPi = 2f * PI.toFloat()
	var d = (delta + PI.toFloat()) % twoPi
	if (d < 0f) {
		d += twoPi
	}

	return d - PI.toFloat()
}

private const val MIN_GRAVITY_SQUARED = 0.01f
private const val FULL_TRAVEL_RADIANS = 0.6f
private const val SMOOTHING_SECONDS = 0.15f
private const val NANOS_PER_SECOND = 1_000_000_000f
