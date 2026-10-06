package xyz.attacktive.weatherd.domain.render

import kotlin.math.roundToInt
import kotlin.math.sqrt
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.model.SkyColorPreset
import xyz.attacktive.weatherd.domain.model.SunColorPreset

/** Reusable meteorological lighting; decorative celestial preferences never enter its cache key. */
internal class SceneryLighting {
	var directionX = 0f
		private set
	var directionY = 0f
		private set
	var directionZ = 1f
		private set
	var directStrength = 0f
		private set
	var textureStrength = 0f
		private set
	var farAtmosphere = 0f
		private set
	var nearAtmosphere = 0f
		private set
	var directColor = 0
		private set
	var ambientColor = 0
		private set

	private var width = 0
	private var height = 0
	private var phase: DayPhase? = null
	private var progress = Float.NaN
	private var cover = Float.NaN
	private var fog = Float.NaN
	private var kind: PrecipitationKind? = null
	private var severity = Float.NaN
	private var thunder = false
	private var brightness = Float.NaN
	private var saturation = Float.NaN
	private var nightBrightness = Float.NaN
	private var preset: SkyColorPreset? = null

	fun update(width: Int, height: Int, params: SceneParams): Boolean {
		val nextCover = effectiveOpaqueCloudiness(params)
		val nextSeverity = params.precipitation?.severity ?: 0f
		if (this.width == width && this.height == height && phase == params.dayPhase && progress == params.celestialProgress && cover == nextCover && fog == params.fogDensity && kind == params.precipitation?.kind && severity == nextSeverity && thunder == params.thunder && brightness == params.skyBrightnessScale && saturation == params.skySaturationScale && nightBrightness == params.nightBrightnessScale && preset == params.skyColorPreset) {
			return false
		}

		this.width = width
		this.height = height
		phase = params.dayPhase
		progress = params.celestialProgress
		cover = nextCover
		fog = params.fogDensity
		kind = params.precipitation?.kind
		severity = nextSeverity
		thunder = params.thunder
		brightness = params.skyBrightnessScale
		saturation = params.skySaturationScale
		nightBrightness = params.nightBrightnessScale
		preset = params.skyColorPreset

		// Physical pixel distances keep the same light direction convention in portrait and virtual-width scenes.
		val x = (CELESTIAL_X_FRACTION - 0.5f) * width
		val y = (0.84f - celestialHeightFraction(params.dayPhase, progress)) * height
		val z = height * 0.32f
		val length = sqrt(x * x + y * y + z * z)
		directionX = x / length
		directionY = y / length
		directionZ = z / length

		val daylight = when (params.dayPhase) {
			DayPhase.DAWN -> 0.55f + 0.45f * dawnDaylightStrength(progress)
			DayPhase.DAY -> 1f
			DayPhase.DUSK -> 1f - duskNightStrength(progress)
			DayPhase.NIGHT -> 0f
		}

		val warmth = when (params.dayPhase) {
			DayPhase.DAWN -> 1f - dawnDaylightStrength(progress)
			DayPhase.DUSK -> duskWarmStrength(progress)
			DayPhase.DAY, DayPhase.NIGHT -> 0f
		}

		val weather = skyOcclusionFor(params)
		val stormTransmission = if (thunder) { 0.35f } else { 1f }
		directStrength = daylight * sunVisibility(params.dayPhase, progress) * (1f - weather) * (1f - fog.coerceIn(0f, 1f)) * stormTransmission
		textureStrength = (0.12f + 0.88f * daylight) * (1f - weather * 0.78f) * stormTransmission
		farAtmosphere = (0.30f + (1f - daylight) * 0.64f + weather * daylight * 0.32f).coerceAtMost(0.96f)
		nearAtmosphere = (0.10f + (1f - daylight) * 0.80f + weather * daylight * 0.30f).coerceAtMost(0.94f)
		directColor = blendSurfaceColor(sunColor(DayPhase.DAY, SunColorPreset.NATURAL), sunColor(params.dayPhase, SunColorPreset.NATURAL), warmth)
		val sky = skyGradientFor(params).bottomColor
		val nightfall = when (params.dayPhase) {
			DayPhase.DUSK -> duskNightStrength(progress)
			DayPhase.NIGHT -> 1f
			DayPhase.DAWN, DayPhase.DAY -> 0f
		}

		val ambientBrightness = 1f - nightfall * (1f - nightBrightness.coerceIn(0f, 1f))
		ambientColor = blendSurfaceColor(0xFF000000.toInt(), sky, ambientBrightness)

		return true
	}
}

/** Nonnegative diffuse response in the shared right/up/toward-viewer coordinate system. */
internal fun surfaceDiffuseFor(patch: MountainSurfacePatch, lighting: SceneryLighting) = (patch.normalX * lighting.directionX + patch.normalY * lighting.directionY + patch.normalZ * lighting.directionZ).coerceIn(0f, 1f)

/** Illuminates intrinsic material first, then integrates its depth plane with the actual sky. */
internal fun surfaceColorFor(material: SceneryMaterial, plane: SceneryPlane, diffuse: Float, lighting: SceneryLighting): Int {
	val intrinsic = sceneryMaterialColor(material)
	val reflectance = when (material) {
		SceneryMaterial.FOREST, SceneryMaterial.MEADOW -> 0.5f
		else -> 1f
	}

	val direct = lighting.directStrength * diffuse.coerceIn(0f, 1f) * 0.95f * reflectance
	val red = illuminatedChannel(intrinsic ushr 16 and 255, lighting.ambientColor ushr 16 and 255, lighting.directColor ushr 16 and 255, direct)
	val green = illuminatedChannel(intrinsic ushr 8 and 255, lighting.ambientColor ushr 8 and 255, lighting.directColor ushr 8 and 255, direct)
	val blue = illuminatedChannel(intrinsic and 255, lighting.ambientColor and 255, lighting.directColor and 255, direct)
	val local = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
	val atmosphere = if (plane == SceneryPlane.FAR) { lighting.farAtmosphere } else { lighting.nearAtmosphere }

	return blendSurfaceColor(local, sceneryPlaneTone(plane, lighting.ambientColor), atmosphere)
}

private fun illuminatedChannel(material: Int, ambient: Int, sunlight: Int, direct: Float) = (material * (0.10f + ambient / 255f * 0.55f + sunlight / 255f * direct)).roundToInt().coerceIn(0, 255)

/** Packed color interpolation shared by terrain illumination and its cached paint preparation. */
internal fun blendSurfaceColor(from: Int, to: Int, fraction: Float): Int {
	val amount = fraction.coerceIn(0f, 1f)
	val red = ((from ushr 16 and 255) + ((to ushr 16 and 255) - (from ushr 16 and 255)) * amount).roundToInt()
	val green = ((from ushr 8 and 255) + ((to ushr 8 and 255) - (from ushr 8 and 255)) * amount).roundToInt()
	val blue = ((from and 255) + ((to and 255) - (from and 255)) * amount).roundToInt()

	return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
}
