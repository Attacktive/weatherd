package xyz.attacktive.weatherd.domain.render

import kotlin.math.roundToInt
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.model.SkyColorPreset

/** Natural rain grading, applied to the already brightness-adjusted sky, never to source artwork. */
internal fun stormCloudSkyGradient(params: SceneParams, gradient: SkyGradient): SkyGradient {
	if (params.precipitation?.kind != PrecipitationKind.RAIN || params.skyColorPreset != SkyColorPreset.NATURAL) {
		return gradient
	}

	val daylight = customDaylightStrength(params.dayPhase, params.celestialProgress)
	if (!daylight.isFinite() || daylight <= 0f) {
		return gradient
	}

	return gradient.copy(
		topColor = stormSkyColor(gradient.topColor, daylight, 0.90f),
		bottomColor = stormSkyColor(gradient.bottomColor, daylight, 0.94f),
		middleColor = gradient.middleColor?.let { stormSkyColor(it, daylight, 0.92f) }
	)
}

/** Muted slate retains the chosen brightness and a cool tint without the clear-blue daylight cast. */
private fun stormSkyColor(color: Int, daylight: Float, brightness: Float): Int {
	val red = color ushr 16 and 255
	val green = color ushr 8 and 255
	val blue = color and 255
	val luminance = red * 0.2126f + green * 0.7152f + blue * 0.0722f
	val saturation = 1f - daylight * 0.55f
	val value = 1f - daylight * (1f - brightness)

	fun channel(source: Int) = ((luminance + (source - luminance) * saturation) * value).roundToInt().coerceIn(0, 255)

	return (color and -0x1000000) or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
}
