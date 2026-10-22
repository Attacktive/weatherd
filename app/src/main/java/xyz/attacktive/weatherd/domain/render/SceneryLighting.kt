package xyz.attacktive.weatherd.domain.render

import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import xyz.attacktive.weatherd.domain.model.DayPhase

/** Per-layer filter cache; unchanged frames neither compute lighting nor allocate filters. */
internal class SceneryLighting(private val plane: SceneryPlane) {
	private var previousParams: SceneParams? = null
	private var previousMatrix = FloatArray(20)
	private var nextMatrix = FloatArray(20)

	fun applyTo(paint: Paint, params: SceneParams) {
		if (params === previousParams) {
			return
		}

		writeSceneryColorMatrix(nextMatrix, params, plane)
		if (!nextMatrix.contentEquals(previousMatrix)) {
			paint.colorFilter = ColorMatrixColorFilter(nextMatrix)
			val swap = previousMatrix
			previousMatrix = nextMatrix
			nextMatrix = swap
		}

		previousParams = params
	}
}

/** Shared environmental light for bitmap scenery, with stronger atmospheric softening at distance and an untouched alpha row. */
internal fun writeSceneryColorMatrix(destination: FloatArray, params: SceneParams, plane: SceneryPlane) {
	val night = when (params.dayPhase) {
		DayPhase.DAY -> 0f
		DayPhase.DAWN -> 1f - dawnSunriseStrength(params.celestialProgress)
		DayPhase.DUSK -> duskNightStrength(params.celestialProgress)
		DayPhase.NIGHT -> 1f
	}

	val warmth = when (params.dayPhase) {
		DayPhase.DAWN -> dawnSunriseStrength(params.celestialProgress) * (1f - dawnDaylightStrength(params.celestialProgress))
		DayPhase.DUSK -> duskWarmStrength(params.celestialProgress) * (1f - night)
		DayPhase.DAY, DayPhase.NIGHT -> 0f
	}

	val weather = if (params.thunder) {
		1f
	} else {
		overcastAmount(params)
	}

	val distant = plane == SceneryPlane.FAR
	val fog = params.fogDensity.coerceIn(0f, 1f)
	val saturation = (1f - weather * if (distant) { 0.85f } else { 0.65f }) * (1f - night * 0.9f)
	val contrast = 1f - fog * if (distant) { 0.45f } else { 0.2f }
	val nightScale = 1f - night * (1f - params.nightBrightnessScale.coerceIn(0f, 1f))
	val brightness = (1f - weather * 0.22f) * (1f - darkenAmount(params)) * (1f - night * if (distant) { 0.78f } else { 0.74f }) * nightScale
	val ambient = night * if (distant) { 3f } else { 4f }
	val fogLight = fog * if (distant) { 30f } else { 10f }
	val lowSun = warmth * (1f - weather)

	destination.fill(0f)
	for (row in 0..2) {
		val tint = when (row) {
			0 -> 1f - night * 0.16f - weather * 0.06f
			1 -> (1f - night * 0.1f - weather * 0.025f) * (1f - lowSun * 0.12f)
			else -> 1f - lowSun * 0.28f
		}

		val factor = brightness * contrast * tint
		val start = row * 5
		destination[start] = (1f - saturation) * 0.2126f * factor
		destination[start + 1] = (1f - saturation) * 0.7152f * factor
		destination[start + 2] = (1f - saturation) * 0.0722f * factor
		destination[start + row] += saturation * factor
		destination[start + 4] = (ambient * nightScale + fogLight * brightness) * tint
	}

	destination[18] = 1f
}
