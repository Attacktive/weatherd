package xyz.attacktive.weatherd.domain.render

import kotlin.math.exp

/** Instance-local angular shaft strengths; cloud geometry and independent lens reflections remain instantaneous. */
internal class SunShaftResponse(sampleCount: Int) {
	val targets = FloatArray(sampleCount)
	val strengths = FloatArray(sampleCount)
	private var previousTime = Float.NaN

	fun update(timeSeconds: Float, reset: Boolean = false) {
		val elapsed = timeSeconds - previousTime
		if (reset || !elapsed.isFinite() || elapsed < 0f || elapsed > MAX_FRAME_GAP_SECONDS) {
			targets.copyInto(strengths)
		} else {
			val blend = 1f - exp(-elapsed / RESPONSE_SECONDS)
			for (index in strengths.indices) {
				strengths[index] += (targets[index] - strengths[index]) * blend
			}
		}

		previousTime = timeSeconds
	}

	private companion object {
		const val RESPONSE_SECONDS = 1.2f
		const val MAX_FRAME_GAP_SECONDS = 2f
	}
}
