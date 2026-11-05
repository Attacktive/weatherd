package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import xyz.attacktive.weatherd.domain.model.CustomSkyGradient
import xyz.attacktive.weatherd.domain.model.SkyGradientDirection
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.model.SkyColorPreset
import xyz.attacktive.weatherd.domain.weather.SEVERITY_STORM

class ScenePaletteTest {
	@Test
	fun `silhouette takes the plane tone exactly`() {
		for (phase in DayPhase.entries) {
			val params = clearParams(phase)
			val skyBottom = skyGradientFor(params).bottomColor

			for (plane in SceneryPlane.entries) {
				assertEquals(
					"$phase $plane",
					sceneryPlaneTone(plane, skyBottom),
					sceneryLayerColor(SceneryMaterial.SILHOUETTE, plane, params, skyBottom)
				)
			}
		}
	}

	@Test
	fun `clear day keeps painted materials distinct from the silhouette tone`() {
		val params = clearParams(DayPhase.DAY)
		val skyBottom = skyGradientFor(params).bottomColor

		val water = sceneryLayerColor(SceneryMaterial.WATER, SceneryPlane.FAR, params, skyBottom)
		val sand = sceneryLayerColor(SceneryMaterial.SAND, SceneryPlane.NEAR, params, skyBottom)
		val pasture = sceneryLayerColor(SceneryMaterial.PASTURE, SceneryPlane.FAR, params, skyBottom)
		val wheat = sceneryLayerColor(SceneryMaterial.WHEAT, SceneryPlane.NEAR, params, skyBottom)
		val steel = sceneryLayerColor(SceneryMaterial.STEEL, SceneryPlane.FAR, params, skyBottom)
		val masonry = sceneryLayerColor(SceneryMaterial.MASONRY, SceneryPlane.NEAR, params, skyBottom)

		// Water reads cool, sand and wheat warm, pasture green — the point of painting at all.
		assertTrue("water should be blue/green dominant", blue(water) > red(water))
		assertTrue("sand should be warm", red(sand) > blue(sand))
		assertTrue("pasture should be green dominant", green(pasture) > red(pasture) && green(pasture) > blue(pasture))
		assertTrue("wheat should be warm", red(wheat) > blue(wheat))
		assertTrue("steel should be cool", blue(steel) > red(steel))
		assertTrue("masonry should be warm", red(masonry) > blue(masonry))
	}

	@Test
	fun `a thunderstorm collapses paint to the silhouette tone`() {
		val params = clearParams(DayPhase.DAY).copy(
			cloudiness = 0.75f,
			precipitation = Precipitation(PrecipitationKind.RAIN, SEVERITY_STORM, observed = 0.9f),
			thunder = true
		)
		val skyBottom = skyGradientFor(params).bottomColor

		for (material in SceneryMaterial.entries) {
			assertEquals(
				"$material must gray out in a storm",
				sceneryPlaneTone(SceneryPlane.NEAR, skyBottom),
				sceneryLayerColor(material, SceneryPlane.NEAR, params, skyBottom)
			)
		}
	}

	@Test
	fun `night sits within a hair of the silhouette tone`() {
		val params = clearParams(DayPhase.NIGHT)
		val skyBottom = skyGradientFor(params).bottomColor
		val tone = sceneryPlaneTone(SceneryPlane.NEAR, skyBottom)
		val sand = sceneryLayerColor(SceneryMaterial.SAND, SceneryPlane.NEAR, params, skyBottom)

		// Night keeps 5% of the intrinsic color, so the residue across three channels stays under ~30 of 765.
		assertTrue("night sand should be nearly silhouette", channelDistance(sand, tone) <= 30)
	}

	@Test
	fun `the far plane sits deeper in atmosphere than the near plane`() {
		val params = clearParams(DayPhase.DAY)
		val skyBottom = skyGradientFor(params).bottomColor

		val nearWater = sceneryLayerColor(SceneryMaterial.WATER, SceneryPlane.NEAR, params, skyBottom)
		val farWater = sceneryLayerColor(SceneryMaterial.WATER, SceneryPlane.FAR, params, skyBottom)
		val nearTone = sceneryPlaneTone(SceneryPlane.NEAR, skyBottom)
		val farTone = sceneryPlaneTone(SceneryPlane.FAR, skyBottom)

		assertTrue(
			"far water should sit closer to its plane tone than near water does",
			channelDistance(farWater, farTone) < channelDistance(nearWater, nearTone)
		)
	}

	@Test
	fun `natural default preserves the established day gradient`() {
		val gradient = skyGradientFor(clearParams(DayPhase.DAY))

		assertEquals(0xFF4A90D9.toInt(), gradient.topColor)
		assertEquals(0xFFA9D6F5.toInt(), gradient.bottomColor)
	}

	@Test
	fun `late dawn keeps sunrise warmth then blends into the blue day palette`() {
		val sunrise = skyGradientFor(clearParams(DayPhase.DAWN).copy(celestialProgress = 0.5f))
		val morning = skyGradientFor(clearParams(DayPhase.DAWN).copy(celestialProgress = 0.75f))
		val daylight = skyGradientFor(clearParams(DayPhase.DAWN).copy(celestialProgress = 0.85f))
		val day = skyGradientFor(clearParams(DayPhase.DAY))

		assertEquals(0xFF34406B.toInt(), sunrise.topColor)
		assertEquals(0xFFF6A984.toInt(), sunrise.bottomColor)
		assertTrue("late-dawn horizon should have turned blue", blue(morning.bottomColor) > red(morning.bottomColor))
		assertEquals("late dawn should meet the day palette before the phase flips", day, daylight)
	}

	@Test
	fun `dawn daylight strength ramps only after sunrise`() {
		assertEquals(0f, dawnDaylightStrength(0.25f), 0.0001f)
		assertEquals(0f, dawnDaylightStrength(0.5f), 0.0001f)
		assertTrue(dawnDaylightStrength(0.75f) > 0.5f)
		assertEquals(1f, dawnDaylightStrength(0.85f), 0.0001f)
		assertEquals(1f, dawnDaylightStrength(1f), 0.0001f)
	}

	@Test
	fun `dusk moves from day through sunset into night`() {
		val early = skyGradientFor(clearParams(DayPhase.DUSK).copy(celestialProgress = 0f))
		val sunset = skyGradientFor(clearParams(DayPhase.DUSK).copy(celestialProgress = 0.5f))
		val late = skyGradientFor(clearParams(DayPhase.DUSK).copy(celestialProgress = 0.85f))
		val day = skyGradientFor(clearParams(DayPhase.DAY))
		val night = skyGradientFor(clearParams(DayPhase.NIGHT))

		assertEquals(day, early)
		assertEquals(0xFF26314F.toInt(), sunset.topColor)
		assertEquals(0xFFE8825B.toInt(), sunset.bottomColor)
		assertTrue("late dusk should darken toward night", channelSum(late.topColor) < channelSum(sunset.topColor))
		assertEquals(night, skyGradientFor(clearParams(DayPhase.DUSK).copy(celestialProgress = 1f)))
	}

	@Test
	fun `dusk warmth rises around sunset then fades into night`() {
		assertEquals(0f, duskWarmStrength(0f), 0.0001f)
		assertEquals(1f, duskWarmStrength(0.5f), 0.0001f)
		assertEquals(0f, duskNightStrength(0.5f), 0.0001f)
		assertTrue(duskNightStrength(0.85f) > 0f)
		assertEquals(1f, duskNightStrength(1f), 0.0001f)
		assertEquals(0f, twilightWarmthStrength(DayPhase.DUSK, 0f), 0.0001f)
		assertEquals(1f, twilightWarmthStrength(DayPhase.DUSK, 0.5f), 0.0001f)
		assertEquals(0f, twilightWarmthStrength(DayPhase.DUSK, 1f), 0.0001f)
	}

	@Test
	fun `twilight horizon glow peaks while the warm sky is visible`() {
		assertEquals(96, warmHorizonGlowAlpha(DayPhase.DAWN, 0.5f))
		assertTrue(warmHorizonGlowAlpha(DayPhase.DAWN, 0.75f) in 1 until 96)
		assertEquals(0, warmHorizonGlowAlpha(DayPhase.DAWN, 0.85f))
		assertEquals(0, warmHorizonGlowAlpha(DayPhase.DUSK, 0f))
		assertEquals(96, warmHorizonGlowAlpha(DayPhase.DUSK, 0.5f))
		assertTrue(warmHorizonGlowAlpha(DayPhase.DUSK, 0.85f) in 1 until 96)
		assertEquals(0, warmHorizonGlowAlpha(DayPhase.DUSK, 1f))
		assertEquals(0, warmHorizonGlowAlpha(DayPhase.DAY, 0.5f))
	}

	@Test
	fun `sky brightness and saturation tune the clear palette`() {
		val natural = skyGradientFor(clearParams(DayPhase.DAY))
		val dim = skyGradientFor(clearParams(DayPhase.DAY).copy(skyBrightnessScale = 0.7f))
		val muted = skyGradientFor(clearParams(DayPhase.DAY).copy(skySaturationScale = 0.5f))

		assertTrue("lower brightness must darken the top color", channelSum(dim.topColor) < channelSum(natural.topColor))
		assertTrue("lower saturation must narrow channel separation", channelSpread(muted.topColor) < channelSpread(natural.topColor))
	}

	@Test
	fun `overcast weather gray honors sky brightness in every phase`() {
		for (phase in DayPhase.entries) {
			val params = clearParams(phase).copy(cloudiness = 0.9f)
			val normal = skyGradientFor(params)
			for (brightness in listOf(0.5f, 1.5f)) {
				val adjusted = skyGradientFor(params.copy(skyBrightnessScale = brightness))
				assertEquals("$phase top brightness $brightness", channelSum(normal.topColor) * brightness.toDouble(), channelSum(adjusted.topColor).toDouble(), 3.0)
				assertEquals("$phase bottom brightness $brightness", channelSum(normal.bottomColor) * brightness.toDouble(), channelSum(adjusted.bottomColor).toDouble(), 3.0)
			}
		}
	}

	@Test
	fun `clear blue keeps a rich blue zenith and pale horizon with adjustable brightness`() {
		val params = clearParams(DayPhase.DAY).copy(skyColorPreset = SkyColorPreset.fromName("CLEAR_BLUE"))
		val natural = skyGradientFor(clearParams(DayPhase.DAY))
		val blue = skyGradientFor(params)
		val darker = skyGradientFor(params.copy(skyBrightnessScale = 0.7f))
		val brighter = skyGradientFor(params.copy(skyBrightnessScale = 1.2f))

		assertTrue("the reference palette should deepen the blue rather than wash it toward white", red(blue.topColor) < red(natural.topColor))
		assertTrue("the zenith should remain blue", blue(blue.topColor) > green(blue.topColor) && green(blue.topColor) > red(blue.topColor))
		assertTrue("the horizon should be lighter than the zenith", channelSum(blue.bottomColor) > channelSum(blue.topColor))
		assertTrue("the existing brightness slider should darken clear blue", channelSum(darker.topColor) < channelSum(blue.topColor))
		assertTrue("the existing brightness slider should lighten clear blue", channelSum(brighter.topColor) > channelSum(blue.topColor))
	}

	@Test
	fun `clear blue preserves natural sunrise sunset and night rather than making them daytime blue`() {
		for (phase in listOf(DayPhase.DAWN, DayPhase.DUSK, DayPhase.NIGHT)) {
			val natural = clearParams(phase)
			val blue = natural.copy(skyColorPreset = SkyColorPreset.fromName("CLEAR_BLUE"))

			assertEquals("the blue daylight preference should preserve $phase lighting", skyGradientFor(natural), skyGradientFor(blue))
		}
	}

	@Test
	fun `clear blue meets daylight at phase boundaries and yields to full overcast`() {
		val params = clearParams(DayPhase.DAY).copy(skyColorPreset = SkyColorPreset.fromName("CLEAR_BLUE"))
		val daylight = skyGradientFor(params)

		assertEquals(daylight, skyGradientFor(params.copy(dayPhase = DayPhase.DAWN, celestialProgress = 0.85f)))
		assertEquals(daylight, skyGradientFor(params.copy(dayPhase = DayPhase.DUSK, celestialProgress = 0f)))
		assertEquals(
			skyGradientFor(clearParams(DayPhase.DAY).copy(cloudiness = 0.85f)),
			skyGradientFor(params.copy(cloudiness = 0.85f))
		)
	}

	@Test
	fun `sky presets stay phase aware and weather can still gray them out`() {
		val natural = skyGradientFor(clearParams(DayPhase.DUSK))
		val cyberpunk = skyGradientFor(clearParams(DayPhase.DUSK).copy(skyColorPreset = SkyColorPreset.CYBERPUNK))
		val overcast = skyGradientFor(clearParams(DayPhase.DUSK).copy(skyColorPreset = SkyColorPreset.CYBERPUNK, cloudiness = 0.85f))

		assertNotEquals(natural, cyberpunk)
		assertEquals("full overcast must still flatten a stylized sky to weather gray", overcast.topColor, overcast.bottomColor)
	}

	@Test
	fun `a scattered sky keeps the clear day blue`() {
		val clear = skyGradientFor(clearParams(DayPhase.DAY))
		val scattered = skyGradientFor(clearParams(DayPhase.DAY).copy(cloudiness = 0.5f))

		// Cumulus darken a sky by covering it, so the gaps between them stay as blue as an empty sky does.
		assertEquals("scattered cloud must not drain the sky's blue", clear, scattered)
	}

	@Test
	fun `known high cloud does not gray a dry sky`() {
		val clear = skyGradientFor(clearParams(DayPhase.DAY).copy(cloudiness = 0.05f))
		val highCloud = skyGradientFor(
			clearParams(DayPhase.DAY).copy(
				cloudiness = 0.8f,
				cloudLayers = SceneCloudLayers(low = 0.05f, mid = 0.05f, high = 0.8f)
			)
		)

		assertEquals(clear, highCloud)
	}

	@Test
	fun `the sky starts graying only when overcast banks replace scattered clouds`() {
		val clear = skyGradientFor(clearParams(DayPhase.DAY))
		val atThreshold = skyGradientFor(clearParams(DayPhase.DAY).copy(cloudiness = 0.75f))
		val pastThreshold = skyGradientFor(clearParams(DayPhase.DAY).copy(cloudiness = 0.8f))

		assertEquals("blue must hold through scattered clouds", clear, atThreshold)
		assertNotEquals("the overcast deck must start graying the sky", clear, pastThreshold)
	}

	@Test
	fun `dry partly cloudy gaps retain phase gradients through the scattered cloud range`() {
		for (phase in listOf(DayPhase.DAY, DayPhase.DAWN, DayPhase.DUSK)) {
			val clear = clearParams(phase).copy(celestialProgress = 0.5f)
			for (cloudiness in listOf(0.55f, 0.65f, 0.7f, 0.75f)) {
				assertEquals(
					"$phase at $cloudiness coverage must preserve the clear phase gradient between clouds",
					skyGradientFor(clear),
					skyGradientFor(clear.copy(cloudiness = cloudiness))
				)
			}
		}
	}

	@Test
	fun `cloud count changes cloud density without changing dry sky atmosphere`() {
		for (phase in listOf(DayPhase.DAY, DayPhase.DAWN, DayPhase.DUSK)) {
			val partly = clearParams(phase).copy(cloudiness = 0.7f, celestialProgress = 0.5f)
			val baseline = skyGradientFor(partly)

			for (count in listOf(0.5f, 1f, 1.2f, 2f)) {
				val scaled = partly.copy(cloudCountScale = count)
				assertEquals("$phase Cloud Count $count must preserve the sky", baseline, skyGradientFor(scaled))
				assertEquals(0f, backdropCeilingStrength(scaled), 0.0001f)
				assertEquals(0f, darkenAmount(scaled), 0.0001f)
			}
		}

		val scaled = clearParams(DayPhase.DAY).copy(cloudiness = 0.45f, cloudCountScale = 2f)
		val overcast = clearParams(DayPhase.DAY).copy(cloudiness = 0.9f)

		assertEquals(skyGradientFor(clearParams(DayPhase.DAY)), skyGradientFor(scaled))
		assertNotEquals(skyGradientFor(overcast), skyGradientFor(scaled))
		assertEquals(0.9f, effectiveOpaqueCloudiness(scaled), 0.0001f)
	}

	@Test
	fun `observed low and mid cover govern grayness independently of count`() {
		val partly = clearParams(DayPhase.DAY).copy(
			cloudiness = 0.9f,
			cloudLayers = SceneCloudLayers(low = 0.7f, mid = 0.6f, high = 0.95f)
		)
		val overcast = partly.copy(cloudLayers = SceneCloudLayers(low = 0.85f, mid = 0.6f, high = 0f))

		assertEquals(skyGradientFor(clearParams(DayPhase.DAY)), skyGradientFor(partly.copy(cloudCountScale = 2f)))
		assertEquals(0f, backdropCeilingStrength(partly.copy(cloudCountScale = 2f)), 0.0001f)
		assertEquals(1f, backdropCeilingStrength(overcast.copy(cloudCountScale = 0.5f)), 0.0001f)
		assertEquals(skyGradientFor(overcast), skyGradientFor(overcast.copy(cloudCountScale = 0.5f)))
	}

	@Test
	fun `rain and fog backdrop do not change with cloud count`() {
		val dry = clearParams(DayPhase.DAY).copy(cloudiness = 0.72f)
		val rain = dry.copy(precipitation = Precipitation(PrecipitationKind.RAIN, 0.65f, observed = 0.5f))
		val fog = dry.copy(fogDensity = 0.5f)

		for (scene in listOf(rain, fog)) {
			assertEquals(backdropCeilingStrength(scene), backdropCeilingStrength(scene.copy(cloudCountScale = 2f)), 0.0001f)
			assertEquals(backdropCeilingStrength(scene), backdropCeilingStrength(scene.copy(cloudCountScale = 0.5f)), 0.0001f)
			assertEquals(skyGradientFor(scene), skyGradientFor(scene.copy(cloudCountScale = 2f)))
		}
	}

	@Test
	fun `the overcast ceiling fades in instead of jumping at the threshold`() {
		assertEquals(0f, overcastCeilingStrength(0.75f), 0.0001f)
		assertEquals(0.5f, overcastCeilingStrength(0.8f), 0.0001f)
		assertEquals(1f, overcastCeilingStrength(0.85f), 0.0001f)
	}

	@Test
	fun `wet and foggy ceilings retain their original onset and ramp`() {
		val rain = Precipitation(PrecipitationKind.RAIN, 0.65f, observed = 0.5f)
		for (cloudiness in listOf(0.55f, 0.65f, 0.72f, 0.8f, 0.85f)) {
			val dry = clearParams(DayPhase.DAY).copy(cloudiness = cloudiness)
			val previousStrength = ((cloudiness - 0.55f) / (0.85f - 0.55f)).coerceIn(0f, 1f)

			assertEquals("dry at $cloudiness must keep the later ceiling", overcastCeilingStrength(cloudiness), backdropCeilingStrength(dry), 0.0001f)
			assertEquals("rain at $cloudiness must retain the earlier ceiling", previousStrength, backdropCeilingStrength(dry.copy(precipitation = rain)), 0.0001f)
			assertEquals("fog at $cloudiness must retain the earlier ceiling", previousStrength, backdropCeilingStrength(dry.copy(fogDensity = 0.5f)), 0.0001f)
		}
	}

	@Test
	fun `an overcast sky gives up its blue entirely`() {
		val overcast = skyGradientFor(clearParams(DayPhase.DAY).copy(cloudiness = 0.85f))

		// Both ends have reached the same gray, so the gradient has no blue left to lose.
		assertEquals("a fully overcast sky should flatten to the phase gray", overcast.topColor, overcast.bottomColor)
	}

	@Test
	fun `custom sky gradient in day phase uses exact normalized two-stop colors and carries direction and positions`() {
		val custom = CustomSkyGradient(
			startColor = 0xFF112233.toInt(),
			endColor = 0xFF445566.toInt(),
			threeColors = false,
			startPosition = 0.1f,
			endPosition = 0.9f,
			direction = SkyGradientDirection.HORIZONTAL
		)
		val params = clearParams(DayPhase.DAY).copy(
			skyColorPreset = SkyColorPreset.CUSTOM,
			customSkyGradient = custom
		)
		val result = skyGradientFor(params)

		assertEquals(0xFF112233.toInt(), result.topColor)
		assertEquals(0xFF445566.toInt(), result.bottomColor)
		assertNull(result.middleColor)
		assertEquals(0.1f, result.startPosition, 0.0001f)
		assertEquals(0.9f, result.endPosition, 0.0001f)
		assertEquals(SkyGradientDirection.HORIZONTAL, result.direction)
	}

	@Test
	fun `custom sky gradient in day phase preserves middle color for three-stop mode`() {
		val custom = CustomSkyGradient(
			startColor = 0xFF102030.toInt(),
			middleColor = 0xFF405060.toInt(),
			endColor = 0xFF708090.toInt(),
			threeColors = true,
			startPosition = 0.05f,
			middlePosition = 0.45f,
			endPosition = 0.95f,
			direction = SkyGradientDirection.DIAGONAL
		)
		val params = clearParams(DayPhase.DAY).copy(
			skyColorPreset = SkyColorPreset.CUSTOM,
			customSkyGradient = custom
		)
		val result = skyGradientFor(params)

		assertEquals(0xFF102030.toInt(), result.topColor)
		assertEquals(0xFF405060.toInt(), result.middleColor)
		assertEquals(0xFF708090.toInt(), result.bottomColor)
		assertEquals(0.05f, result.startPosition, 0.0001f)
		assertEquals(0.45f, result.middlePosition, 0.0001f)
		assertEquals(0.95f, result.endPosition, 0.0001f)
		assertEquals(SkyGradientDirection.DIAGONAL, result.direction)
	}

	@Test
	fun `custom sky gradient at dawn blends natural dawn toward custom daylight`() {
		val custom = CustomSkyGradient(
			startColor = 0xFF00AAFF.toInt(),
			middleColor = 0xFF88EEFF.toInt(),
			endColor = 0xFFFFFFFF.toInt(),
			threeColors = true,
			middlePosition = 0.4f
		)
		val dawnStartParams = clearParams(DayPhase.DAWN).copy(
			skyColorPreset = SkyColorPreset.CUSTOM,
			customSkyGradient = custom,
			celestialProgress = 0f
		)
		val naturalDawnParams = clearParams(DayPhase.DAWN).copy(
			skyColorPreset = SkyColorPreset.NATURAL,
			celestialProgress = 0f
		)
		val atDawnStart = skyGradientFor(dawnStartParams)
		val naturalDawn = skyGradientFor(naturalDawnParams)

		assertEquals(naturalDawn.topColor, atDawnStart.topColor)
		assertEquals(naturalDawn.bottomColor, atDawnStart.bottomColor)

		val dawnEndParams = clearParams(DayPhase.DAWN).copy(
			skyColorPreset = SkyColorPreset.CUSTOM,
			customSkyGradient = custom,
			celestialProgress = 1f
		)
		val atDawnEnd = skyGradientFor(dawnEndParams)

		assertEquals(custom.startColor, atDawnEnd.topColor)
		assertEquals(custom.middleColor, atDawnEnd.middleColor)
		assertEquals(custom.endColor, atDawnEnd.bottomColor)

		val midDawnParams = clearParams(DayPhase.DAWN).copy(
			skyColorPreset = SkyColorPreset.CUSTOM,
			customSkyGradient = custom,
			celestialProgress = 0.7f
		)
		val midDawn = skyGradientFor(midDawnParams)

		assertTrue(channelDistance(midDawn.topColor, naturalDawn.topColor) > 0)
		assertTrue(channelDistance(midDawn.topColor, custom.startColor) > 0)
	}

	@Test
	fun `custom sky gradient at dusk transitions from custom day through natural dusk to natural night`() {
		val custom = CustomSkyGradient(
			startColor = 0xFF224488.toInt(),
			middleColor = 0xFF6688CC.toInt(),
			endColor = 0xFFAACCFF.toInt(),
			threeColors = true,
			middlePosition = 0.5f
		)
		val duskStartParams = clearParams(DayPhase.DUSK).copy(
			skyColorPreset = SkyColorPreset.CUSTOM,
			customSkyGradient = custom,
			celestialProgress = 0f
		)
		val atDuskStart = skyGradientFor(duskStartParams)

		assertEquals(custom.startColor, atDuskStart.topColor)
		assertEquals(custom.middleColor, atDuskStart.middleColor)
		assertEquals(custom.endColor, atDuskStart.bottomColor)

		val duskMidParams = clearParams(DayPhase.DUSK).copy(
			skyColorPreset = SkyColorPreset.CUSTOM,
			customSkyGradient = custom,
			celestialProgress = 0.5f
		)
		val naturalDuskParams = clearParams(DayPhase.DUSK).copy(
			skyColorPreset = SkyColorPreset.NATURAL,
			celestialProgress = 0.5f
		)
		val atDuskMid = skyGradientFor(duskMidParams)
		val naturalDusk = skyGradientFor(naturalDuskParams)

		assertEquals(naturalDusk.topColor, atDuskMid.topColor)
		assertEquals(naturalDusk.bottomColor, atDuskMid.bottomColor)

		val duskEndParams = clearParams(DayPhase.DUSK).copy(
			skyColorPreset = SkyColorPreset.CUSTOM,
			customSkyGradient = custom,
			celestialProgress = 1f
		)
		val naturalNightParams = clearParams(DayPhase.NIGHT).copy(
			skyColorPreset = SkyColorPreset.NATURAL
		)
		val atDuskEnd = skyGradientFor(duskEndParams)
		val naturalNight = skyGradientFor(naturalNightParams)

		assertEquals(naturalNight.topColor, atDuskEnd.topColor)
		assertEquals(naturalNight.bottomColor, atDuskEnd.bottomColor)
	}

	@Test
	fun `custom sky gradient at night uses natural night colors avoiding blue midnight`() {
		val custom = CustomSkyGradient(
			startColor = 0xFF00FF00.toInt(),
			middleColor = 0xFFFFFF00.toInt(),
			endColor = 0xFFFF0000.toInt(),
			threeColors = true,
			middlePosition = 0.6f
		)
		val customNight = skyGradientFor(
			clearParams(DayPhase.NIGHT).copy(
				skyColorPreset = SkyColorPreset.CUSTOM,
				customSkyGradient = custom
			)
		)
		val naturalNight = skyGradientFor(
			clearParams(DayPhase.NIGHT).copy(
				skyColorPreset = SkyColorPreset.NATURAL
			)
		)

		assertEquals("Night top should match Natural night", naturalNight.topColor, customNight.topColor)
		assertEquals("Night bottom should match Natural night", naturalNight.bottomColor, customNight.bottomColor)
	}

	@Test
	fun `custom sky gradient full overcast converges all stops to weather gray`() {
		val custom = CustomSkyGradient(
			startColor = 0xFF001155.toInt(),
			middleColor = 0xFF882200.toInt(),
			endColor = 0xFFEEFF00.toInt(),
			threeColors = true,
			middlePosition = 0.4f
		)
		val overcast = skyGradientFor(
			clearParams(DayPhase.DAY).copy(
				skyColorPreset = SkyColorPreset.CUSTOM,
				customSkyGradient = custom,
				cloudiness = 0.85f
			)
		)

		assertEquals("top and bottom must converge on full overcast", overcast.topColor, overcast.bottomColor)
		assertEquals("middle must converge to the same gray", overcast.topColor, overcast.middleColor)
	}

	@Test
	fun `custom geometry yields to the complete natural gradient without daylight influence`() {
		for (direction in listOf(SkyGradientDirection.HORIZONTAL, SkyGradientDirection.DIAGONAL)) {
			for (threeColors in listOf(false, true)) {
				val custom = CustomSkyGradient(
					startColor = 0xFFFF2200.toInt(),
					middleColor = 0xFF00FF88.toInt(),
					endColor = 0xFF2244FF.toInt(),
					threeColors = threeColors,
					startPosition = 0.2f,
					middlePosition = 0.4f,
					endPosition = 0.8f,
					direction = direction
				)

				for ((phase, progress) in listOf(DayPhase.NIGHT to 0.5f, DayPhase.DAWN to 0f, DayPhase.DAWN to 0.5f, DayPhase.DUSK to 0.5f, DayPhase.DUSK to 1f)) {
					val natural = clearParams(phase).copy(celestialProgress = progress)
					val selected = natural.copy(skyColorPreset = SkyColorPreset.CUSTOM, customSkyGradient = custom)
					assertEquals("$phase at $progress with $direction and threeColors=$threeColors", skyGradientFor(natural), skyGradientFor(selected))
				}
			}
		}
	}

	@Test
	fun `custom daylight strength follows smooth activation ramps`() {
		assertEquals(1f, customDaylightStrength(DayPhase.DAY, 0.5f), 0.0001f)
		assertEquals(0f, customDaylightStrength(DayPhase.NIGHT, 0.5f), 0.0001f)

		assertEquals(0f, customDaylightStrength(DayPhase.DAWN, 0.25f), 0.0001f)
		assertEquals(0f, customDaylightStrength(DayPhase.DAWN, 0.5f), 0.0001f)
		val midDawn = customDaylightStrength(DayPhase.DAWN, 0.675f)
		assertTrue("mid-dawn daylight strength should be between 0 and 1", midDawn > 0f && midDawn < 1f)
		assertEquals(1f, customDaylightStrength(DayPhase.DAWN, 0.85f), 0.0001f)
		assertEquals(1f, customDaylightStrength(DayPhase.DAWN, 1f), 0.0001f)

		assertEquals(1f, customDaylightStrength(DayPhase.DUSK, 0f), 0.0001f)
		assertEquals(1f, customDaylightStrength(DayPhase.DUSK, 0.15f), 0.0001f)
		val midDusk = customDaylightStrength(DayPhase.DUSK, 0.325f)
		assertTrue("mid-dusk daylight strength should be between 0 and 1", midDusk > 0f && midDusk < 1f)
		assertEquals(0f, customDaylightStrength(DayPhase.DUSK, 0.5f), 0.0001f)
		assertEquals(0f, customDaylightStrength(DayPhase.DUSK, 1f), 0.0001f)
	}

	@Test
	fun `custom sky gradient at zero daylight matches natural gradient under overcast and storm`() {
		val custom = CustomSkyGradient(
			startColor = 0xFFFF0055.toInt(),
			middleColor = 0xFF55FF00.toInt(),
			endColor = 0xFF0055FF.toInt(),
			threeColors = true,
			startPosition = 0.15f,
			middlePosition = 0.35f,
			endPosition = 0.85f,
			direction = SkyGradientDirection.DIAGONAL
		)

		for ((phase, progress) in listOf(DayPhase.NIGHT to 0.5f, DayPhase.DAWN to 0.25f, DayPhase.DUSK to 0.75f)) {
			val natural = clearParams(phase).copy(celestialProgress = progress, cloudiness = 0.9f, thunder = true)
			val selected = natural.copy(skyColorPreset = SkyColorPreset.CUSTOM, customSkyGradient = custom)
			assertEquals("$phase at $progress under storm", skyGradientFor(natural), skyGradientFor(selected))
		}
	}

	@Test
	fun `custom sky gradient during daylight transition carries custom direction and positions`() {
		val customHorizontal = CustomSkyGradient(
			startColor = 0xFF112233.toInt(),
			middleColor = 0xFF445566.toInt(),
			endColor = 0xFF778899.toInt(),
			threeColors = true,
			startPosition = 0.1f,
			middlePosition = 0.4f,
			endPosition = 0.9f,
			direction = SkyGradientDirection.HORIZONTAL
		)
		val dawnParams = clearParams(DayPhase.DAWN).copy(
			skyColorPreset = SkyColorPreset.CUSTOM,
			customSkyGradient = customHorizontal,
			celestialProgress = 0.7f
		)
		val dawnGradient = skyGradientFor(dawnParams)

		assertEquals(SkyGradientDirection.HORIZONTAL, dawnGradient.direction)
		assertEquals(0.1f, dawnGradient.startPosition, 0.0001f)
		assertEquals(0.4f, dawnGradient.middlePosition, 0.0001f)
		assertEquals(0.9f, dawnGradient.endPosition, 0.0001f)
		assertNotNull(dawnGradient.middleColor)

		val customDiagonal = CustomSkyGradient(
			startColor = 0xFFAABBCC.toInt(),
			middleColor = 0xFFCCDDEE.toInt(),
			endColor = 0xFFEEFFFF.toInt(),
			threeColors = true,
			startPosition = 0.2f,
			middlePosition = 0.6f,
			endPosition = 0.8f,
			direction = SkyGradientDirection.DIAGONAL
		)
		val duskParams = clearParams(DayPhase.DUSK).copy(
			skyColorPreset = SkyColorPreset.CUSTOM,
			customSkyGradient = customDiagonal,
			celestialProgress = 0.3f
		)
		val duskGradient = skyGradientFor(duskParams)

		assertEquals(SkyGradientDirection.DIAGONAL, duskGradient.direction)
		assertEquals(0.2f, duskGradient.startPosition, 0.0001f)
		assertEquals(0.6f, duskGradient.middlePosition, 0.0001f)
		assertEquals(0.8f, duskGradient.endPosition, 0.0001f)
		assertNotNull(duskGradient.middleColor)
	}

	@Test
	fun `custom daylight overlay keeps twilight weather gray instead of daytime gray`() {
		for ((phase, progress) in listOf(DayPhase.DAWN to 0.7f, DayPhase.DUSK to 0.3f)) {
			val params = clearParams(phase).copy(
				skyColorPreset = SkyColorPreset.CUSTOM,
				customSkyGradient = CustomSkyGradient(threeColors = true),
				celestialProgress = progress,
				cloudiness = 0.85f,
				thunder = true,
				skyBrightnessScale = 1.2f
			)
			val natural = naturalSkyGradientFor(params)
			val custom = customDaylightSkyGradientFor(params)

			assertEquals("$phase start must use current weather lighting", natural.topColor, custom.topColor)
			assertEquals("$phase middle must use current weather lighting", natural.topColor, custom.middleColor)
			assertEquals("$phase end must use current weather lighting", natural.bottomColor, custom.bottomColor)
		}
	}

	private fun clearParams(dayPhase: DayPhase) = SceneParams(
		dayPhase = dayPhase,
		cloudiness = 0.05f,
		fogDensity = 0f,
		precipitation = null,
		thunder = false,
		windFactor = 0.3f
	)

	private fun red(color: Int) = color ushr 16 and 0xFF

	private fun green(color: Int) = color ushr 8 and 0xFF

	private fun blue(color: Int) = color and 0xFF

	private fun channelSum(color: Int) = red(color) + green(color) + blue(color)

	private fun channelSpread(color: Int): Int {
		val channels = listOf(red(color), green(color), blue(color))
		return channels.max() - channels.min()
	}

	private fun channelDistance(a: Int, b: Int) = abs(red(a) - red(b)) + abs(green(a) - green(b)) + abs(blue(a) - blue(b))
}
