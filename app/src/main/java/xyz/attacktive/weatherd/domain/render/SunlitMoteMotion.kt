package xyz.attacktive.weatherd.domain.render

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import xyz.attacktive.weatherd.domain.model.DayPhase

internal fun sunlitMoteStrength(params: SceneParams): Float {
	if (!showsDirectSun(params)) {
		return 0f
	}

	val cloudiness = maxOf(observedOpaqueCloudiness(params), effectiveOpaqueCloudiness(params))
	val clearFraction = ((0.75f - cloudiness) / 0.55f).coerceIn(0f, 1f)
	val cloudFade = clearFraction * clearFraction * (3f - 2f * clearFraction)
	val progress = params.celestialProgress.coerceIn(0f, 1f)
	val lowSun = when (params.dayPhase) {
		DayPhase.DAWN -> 1f - 0.75f * progress
		DayPhase.DUSK -> 0.25f + 0.75f * progress
		DayPhase.DAY -> 0.25f * (2f * progress - 1f) * (2f * progress - 1f)
		DayPhase.NIGHT -> 0f
	}

	return (0.34f + 0.66f * lowSun) * cloudFade * sunVisibility(params.dayPhase, progress)
}

internal class SunlitMoteField {
	val x = FloatArray(COUNT)
	val y = FloatArray(COUNT)
	val radius = FloatArray(COUNT)
	private val random = Random(444)
	private val phase = FloatArray(COUNT) { random.nextFloat() * (2.0 * PI).toFloat() }
	private val distance = FloatArray(COUNT) { 0.06f + random.nextFloat() * 0.15f }
	private val speed = FloatArray(COUNT) { 0.025f + random.nextFloat() * 0.025f }
	private val size = FloatArray(COUNT) { 0.0022f + random.nextFloat() * 0.0014f }
	val opacity = FloatArray(COUNT) { 0.55f + random.nextFloat() * 0.45f }

	fun update(span: Float, centerX: Float, centerY: Float, sunRadius: Float, timeSeconds: Float) {
		for (index in 0 until COUNT) {
			val drift = timeSeconds.toDouble() * speed[index]
			val angle = phase[index] + 0.22 * sin(drift)
			val reach = sunRadius + span * (distance[index] + 0.015 * sin(drift * 0.73 + phase[index])).toFloat()
			x[index] = centerX + reach * cos(angle).toFloat()
			y[index] = centerY + reach * sin(angle).toFloat()
			radius[index] = span * size[index]
		}
	}

	private companion object {
		const val COUNT = 14
	}
}
