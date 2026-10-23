package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind

@RunWith(AndroidJUnit4::class)
class MoonRenderingTest {
	private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

	@Test
	fun unpreparedQuarterMustNotBeSynthesizedByForegroundDrawing() {
		val renderer = SceneRenderer(resources)
		renderer.prewarmMoonPhase(0.5f)
		val params = clearNightParams(moonPhase = 0.25f)
		val pending = createBitmap(PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
		renderer.renderForeground(Canvas(pending), PORTRAIT_WIDTH, PORTRAIT_HEIGHT, params, 0f)
		val hidden = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, params.copy(moonVisible = false))
		try {
			assertArrayEquals("An unprepared phase must draw neither a lunar disc nor a halo", pixels(hidden), pixels(pending))
			assertFalse("A draw must not make an unprepared phase ready", renderer.isMoonPhaseReady(0.25f))

			runBlocking(Dispatchers.Default) { renderer.prewarmMoonPhase(0.25f) }
			assertTrue(renderer.isMoonPhaseReady(0.25f))
			val prepared = renderCachedForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, params, 0f, renderer)
			try {
				val center = celestialCenter(PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
				assertTrue("The prepared quarter must appear on the next frame", Color.alpha(prepared.getPixel(center.x + 18, center.y)) >= OPAQUE_ALPHA_THRESHOLD)
			} finally {
				prepared.recycle()
			}
		} finally {
			pending.recycle()
			hidden.recycle()
		}
	}

	@Test
	fun changingToAnUnpreparedPhaseNeverBorrowsThePreviousDiscOrHalo() {
		val renderer = SceneRenderer(resources)
		runBlocking(Dispatchers.Default) { renderer.prewarmMoonPhase(0.25f) }
		val waxing = clearNightParams(moonPhase = 0.25f)
		val waning = waxing.copy(moonPhase = 0.75f)
		val before = renderCachedForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, waxing, 0f, renderer)
		val pending = renderCachedForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, waning, 0f, renderer)
		val hidden = renderCachedForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, waning.copy(moonVisible = false), 0f, renderer)
		try {
			assertArrayEquals("A pending phase must not reuse old lunar pixels", pixels(hidden), pixels(pending))
			assertFalse(renderer.isMoonPhaseReady(0.75f))

			runBlocking(Dispatchers.Default) { renderer.prewarmMoonPhase(0.75f) }
			val ready = renderCachedForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, waning, 0f, renderer)
			val restored = renderCachedForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, waxing, 0f, renderer)
			try {
				val center = celestialCenter(PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
				assertTrue("The newly prepared waning phase must illuminate the left", Color.alpha(ready.getPixel(center.x - 18, center.y)) >= OPAQUE_ALPHA_THRESHOLD)
				assertTrue("The old waxing side must no longer contain an opaque disc", Color.alpha(ready.getPixel(center.x + 18, center.y)) < OPAQUE_ALPHA_THRESHOLD)
				assertArrayEquals("Preparing another phase must not replace a cached phase's pixels", pixels(before), pixels(restored))
			} finally {
				ready.recycle()
				restored.recycle()
			}
		} finally {
			before.recycle()
			pending.recycle()
			hidden.recycle()
		}
	}

	@Test
	fun concurrentPreparationPublishesEachPhaseUnderItsOwnKey() {
		val layer = MoonLayer(resources)
		runBlocking(Dispatchers.Default) {
			listOf(
				async { layer.prewarm(0.25f) },
				async { layer.prewarm(0.75f) }
			).awaitAll()
		}

		val waxing = checkNotNull(layer.spriteFor(layer.phaseIndexFor(0.25f)))
		val waning = checkNotNull(layer.spriteFor(layer.phaseIndexFor(0.75f)))
		assertEquals(0, Color.alpha(waxing.getPixel(128, 256)))
		assertEquals(255, Color.alpha(waxing.getPixel(384, 256)))
		assertEquals(255, Color.alpha(waning.getPixel(128, 256)))
		assertEquals(0, Color.alpha(waning.getPixel(384, 256)))
	}

	@Test
	fun evictingAPhaseNeverRecyclesAnImageCapturedByAFrame() {
		val layer = MoonLayer(resources)
		runBlocking(Dispatchers.Default) { layer.prewarm(0.25f) }
		val captured = checkNotNull(layer.spriteFor(layer.phaseIndexFor(0.25f)))
		val original = pixels(captured)
		runBlocking(Dispatchers.Default) {
			for (step in 1 until 64) {
				layer.prewarm(step / 64f)
			}
		}

		assertFalse("Old phases must not accumulate without bound", layer.isPhaseReady(0.25f))
		assertArrayEquals("A frame's captured immutable sprite must survive cache eviction", original, pixels(captured))
		runBlocking(Dispatchers.Default) { layer.prewarm(0.25f) }
		assertArrayEquals(original, pixels(checkNotNull(layer.spriteFor(layer.phaseIndexFor(0.25f)))))
	}

	@Test
	fun waxingAndWaningTerminateOnOppositeSides() {
		val waxing = renderMoon(0.25f)
		val waning = renderMoon(0.75f)
		val center = PixelPoint(256, 256)
		val sampleOffset = 128

		val waxingLitSideAlpha = Color.alpha(waxing.getPixel(center.x + sampleOffset, center.y))
		val waxingDarkSideAlpha = Color.alpha(waxing.getPixel(center.x - sampleOffset, center.y))

		val waningLitSideAlpha = Color.alpha(waning.getPixel(center.x - sampleOffset, center.y))
		val waningDarkSideAlpha = Color.alpha(waning.getPixel(center.x + sampleOffset, center.y))

		assertTrue("Waxing quarter should be illuminated on the right side, but right alpha was $waxingLitSideAlpha", waxingLitSideAlpha >= OPAQUE_ALPHA_THRESHOLD)
		assertEquals("Waxing quarter must leave its unlit hemisphere transparent", 0, waxingDarkSideAlpha)

		assertTrue("Waning quarter should be illuminated on the left side, but left alpha was $waningLitSideAlpha", waningLitSideAlpha >= OPAQUE_ALPHA_THRESHOLD)
		assertEquals("Waning quarter must leave its unlit hemisphere transparent", 0, waningDarkSideAlpha)

		waxing.recycle()
		waning.recycle()
	}

	@Test
	fun lunarFeaturesAreNeverMirroredWhenWaning() {
		val fullMoon = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearNightParams(moonPhase = 0.50f))
		val waxingGibbous = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearNightParams(moonPhase = 0.38f))
		val waningGibbous = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearNightParams(moonPhase = 0.62f))

		val center = celestialCenter(PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
		val span = minOf(PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
		val radius = span * MOON_RADIUS_FRACTION

		// Mare Crisium is located on the Eastern (right) limb (~0.62 radius East, ~0.23 radius North of center).
		val crisiumX = (center.x + radius * 0.62f).roundToInt()
		val crisiumY = (center.y - radius * 0.23f).roundToInt()

		// Symmetric point on the Western (left) limb in Oceanus Procellarum / highlands.
		val oppositeX = (center.x - radius * 0.62f).roundToInt()
		val oppositeY = (center.y - radius * 0.23f).roundToInt()

		val fullCrisium = fullMoon.getPixel(crisiumX, crisiumY)
		val fullOpposite = fullMoon.getPixel(oppositeX, oppositeY)

		val waxingCrisium = waxingGibbous.getPixel(crisiumX, crisiumY)
		val waningCrisium = waningGibbous.getPixel(crisiumX, crisiumY)

		val crisiumLuminance = Color.red(fullCrisium) * 0.299f + Color.green(fullCrisium) * 0.587f + Color.blue(fullCrisium) * 0.114f
		val oppositeLuminance = Color.red(fullOpposite) * 0.299f + Color.green(fullOpposite) * 0.587f + Color.blue(fullOpposite) * 0.114f
		assertTrue("Mare Crisium should be darker than the opposite lunar terrain, but crisium=$crisiumLuminance, opposite=$oppositeLuminance", oppositeLuminance - crisiumLuminance > 15f)
		assertTrue("Waxing moon must preserve the same lunar markings", colorDistance(waxingCrisium, fullCrisium) < colorDistance(waxingCrisium, fullOpposite))

		val crisiumDistanceToOriginal = colorDistance(waningCrisium, fullCrisium)
		val crisiumDistanceToOpposite = colorDistance(waningCrisium, fullOpposite)
		assertTrue("Waning moon must not mirror surface markings; Mare Crisium should remain on the right (dist=$crisiumDistanceToOriginal vs opposite=$crisiumDistanceToOpposite)", crisiumDistanceToOriginal < crisiumDistanceToOpposite)

		fullMoon.recycle()
		waxingGibbous.recycle()
		waningGibbous.recycle()
	}

	@Test
	fun realisticSurfaceDetailIsPresentRatherThanProceduralCircles() {
		val fullMoon = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearNightParams(moonPhase = 0.50f))
		val center = celestialCenter(PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
		val span = minOf(PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
		val radius = span * MOON_RADIUS_FRACTION

		val sampleValues = mutableListOf<Float>()
		val uniqueColors = HashSet<Int>()

		val sampleCount = 14
		for (row in -sampleCount..sampleCount) {
			for (col in -sampleCount..sampleCount) {
				val dx = col.toFloat() / sampleCount
				val dy = row.toFloat() / sampleCount
				if (dx * dx + dy * dy > 0.70f) {
					continue
				}

				val sampleX = (center.x + dx * radius).roundToInt()
				val sampleY = (center.y + dy * radius).roundToInt()
				val pixel = fullMoon.getPixel(sampleX, sampleY)

				uniqueColors.add(pixel)
				val luminance = Color.red(pixel) * 0.299f + Color.green(pixel) * 0.587f + Color.blue(pixel) * 0.114f
				sampleValues.add(luminance)
			}
		}

		val mean = sampleValues.average().toFloat()
		val variance = sampleValues.map { (it - mean) * (it - mean) }.average().toFloat()
		val standardDeviation = sqrt(variance)

		assertTrue("Photographic lunar terrain should exhibit continuous luminance variation (stdDev was $standardDeviation)", standardDeviation > 12f)
		assertTrue("Photographic lunar terrain should contain rich diverse color nuances rather than flat procedural circles (unique colors was ${uniqueColors.size})", uniqueColors.size > 50)

		fullMoon.recycle()
	}

	@Test
	fun exactNewMoonDrawsNoLunarDisc() {
		val newMoon = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearNightParams(moonPhase = 0f))
		val bounds = opaqueBounds(newMoon, threshold = OPAQUE_ALPHA_THRESHOLD)

		assertTrue("New moon disc should draw zero opaque pixels, but bounds were $bounds", !bounds.isValid)

		val center = celestialCenter(PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
		val span = minOf(PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
		val radius = span * MOON_RADIUS_FRACTION
		val peakAlpha = highestAlpha(newMoon, center, radius)

		assertTrue("New moon disc area should remain unlit without an opaque black disc or border, but peak alpha was $peakAlpha", peakAlpha < HALO_MAX_ALPHA)

		val withoutMoon = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearNightParams(moonPhase = 0f, moonVisible = false))
		assertArrayEquals("New moon must not leave a luminous halo in an otherwise unlit sky", pixels(withoutMoon), pixels(newMoon))
		withoutMoon.recycle()

		newMoon.recycle()
	}

	@Test
	fun exactFullMoonDrawsCompleteLitDisc() {
		val fullMoon = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearNightParams(moonPhase = 0.5f))
		val expectedCenter = celestialCenter(PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
		val bounds = opaqueBounds(fullMoon, threshold = OPAQUE_ALPHA_THRESHOLD)
		val radiusFraction = bounds.height / 2f / minOf(PORTRAIT_WIDTH, PORTRAIT_HEIGHT)

		assertTrue("The full moon should remain centered at $expectedCenter, but opaque bounds were $bounds", abs(bounds.centerX - expectedCenter.x) <= POSITION_TOLERANCE_PIXELS && abs(bounds.centerY - expectedCenter.y) <= POSITION_TOLERANCE_PIXELS)
		assertTrue("The full moon should preserve its existing radius near $MOON_RADIUS_FRACTION, but measured $radiusFraction", radiusFraction in MOON_RADIUS_RANGE)

		fullMoon.recycle()
	}

	@Test
	fun moonSizeScalePreservesCenterInPortraitAndLandscape() {
		assertMoonSizeAndCenter(PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
		assertMoonSizeAndCenter(LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT)
	}

	@Test
	fun quantizedPhaseYieldsStableOutputRegardlessOfRenderHistory() {
		val renderer1 = SceneRenderer(resources)
		val renderer2 = SceneRenderer(resources)

		val render1A = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearNightParams(moonPhase = 0.246f), renderer = renderer1)
		val render1B = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearNightParams(moonPhase = 0.254f), renderer = renderer1)

		val render2B = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearNightParams(moonPhase = 0.254f), renderer = renderer2)
		val render2A = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearNightParams(moonPhase = 0.246f), renderer = renderer2)

		assertArrayEquals("Moon output for same quantized phase step must be identical regardless of whether 0.246 or 0.254 was rendered first", pixels(render1A), pixels(render2A))
		assertArrayEquals("Moon output for same quantized phase step must be identical on cache hit", pixels(render1A), pixels(render1B))
		assertArrayEquals("Moon output for same quantized phase step must match across separate instances", pixels(render1A), pixels(render2B))

		render1A.recycle()
		render1B.recycle()
		render2A.recycle()
		render2B.recycle()
	}

	@Test
	fun moonIsHiddenDuringDaylightOrWhenPrecipitatingOrDisabled() {
		val daylight = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearNightParams(dayPhase = DayPhase.DAY, moonPhase = 0.5f))
		val rainy = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearNightParams(dayPhase = DayPhase.NIGHT, moonPhase = 0.5f, precipitation = Precipitation(PrecipitationKind.RAIN, 0.5f, 0.5f)))
		val hidden = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearNightParams(dayPhase = DayPhase.NIGHT, moonPhase = 0.5f, moonVisible = false))

		val rainyBounds = opaqueBounds(rainy, threshold = OPAQUE_ALPHA_THRESHOLD)
		val hiddenBounds = opaqueBounds(hidden, threshold = OPAQUE_ALPHA_THRESHOLD)

		assertTrue("Rainy night must not draw the opaque moon disc", !rainyBounds.isValid)
		assertTrue("When moonVisible is false, opaque moon disc must not be drawn", !hiddenBounds.isValid)

		val resizedInDaylight = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearNightParams(dayPhase = DayPhase.DAY, moonPhase = 0.5f, moonSizeScale = 2f))
		assertArrayEquals("Moon size must not alter the daytime Sun", pixels(daylight), pixels(resizedInDaylight))
		resizedInDaylight.recycle()

		daylight.recycle()
		rainy.recycle()
		hidden.recycle()
	}

	@Test
	fun preparedOpacityProbesStayOnIlluminatedLunarPixels() {
		val layer = MoonLayer(resources)
		for (phase in listOf(0.0625f, 0.25f, 0.5f, 0.75f, 0.9375f, 0.984375f)) {
			layer.prewarm(phase)
			val prepared = checkNotNull(layer.preparedFor(layer.phaseIndexFor(phase)))
			val samples = prepared.cloudSamples
			assertTrue("Phase $phase must produce at least one illuminated cloud probe", samples.isNotEmpty() && samples.size % 3 == 0)
			var totalWeight = 0f

			for (index in samples.indices step 3) {
				val x = (((samples[index] + 1f) * 0.5f) * prepared.sprite.width).toInt().coerceIn(0, prepared.sprite.width - 1)
				val y = (((samples[index + 1] + 1f) * 0.5f) * prepared.sprite.height).toInt().coerceIn(0, prepared.sprite.height - 1)
				val alpha = Color.alpha(prepared.sprite.getPixel(x, y))
				assertTrue("Phase $phase probe ($x, $y) must sample the lit mask, found alpha $alpha", alpha >= 64)
				assertTrue("Every mask probe must have positive area weight", samples[index + 2] > 0f)
				totalWeight += samples[index + 2]
			}

			assertEquals("Probe weights must sum to one for phase $phase", 1f, totalWeight, 0.0001f)
		}
	}

	@Test
	fun cloudOverTheUnlitCrescentHemisphereDoesNotDimTheIlluminatedEdge() {
		val params = clearNightParams(moonPhase = 0.9375f, cloudiness = 0.7f)
		val renderer = SceneRenderer(resources, epochDay = 20_000L)
		val layer = MoonLayer(resources)
		val width = 540f
		val height = 960f
		val radius = minOf(width, height) * MOON_RADIUS_FRACTION

		renderer.prewarmCloudTextures()
		layer.prewarm(params.moonPhase)
		val prepared = checkNotNull(layer.preparedFor(layer.phaseIndexFor(params.moonPhase)))
		val opacity = renderer.moonCloudOpacity(
			width,
			height,
			width * CELESTIAL_X_FRACTION,
			height * celestialHeightFraction(params.dayPhase, params.celestialProgress),
			radius,
			params,
			592f,
			prepared.cloudSamples
		)

		assertTrue("Clouds covering only the dark hemisphere must not attenuate the lit crescent; measured opacity $opacity", opacity < 0.25f)
		assertTrue("Lit crescent should retain most of its own brightness", moonCloudTransmittance(opacity) > 0.8f)
	}

	@Test
	fun waningCrescentKeepsRenderedBrightnessWhenCloudsCoverOnlyItsDarkSide() {
		val params = clearNightParams(moonPhase = 0.9375f, cloudiness = 0.7f)
		val renderer = SceneRenderer(resources, epochDay = 20_000L)
		val layer = MoonLayer(resources)
		val width = 540
		val height = 960
		val timeSeconds = 592f
		renderer.prewarmCloudTextures()
		renderer.prewarmMoonPhase(params.moonPhase)
		layer.prewarm(params.moonPhase)
		val prepared = checkNotNull(layer.preparedFor(layer.phaseIndexFor(params.moonPhase)))
		val cloudyMoon = renderForegroundOnNightSky(width, height, params, timeSeconds, renderer)
		val cloudyWithoutMoon = renderForegroundOnNightSky(width, height, params.copy(moonVisible = false), timeSeconds, renderer)
		val clear = params.copy(cloudiness = 0f)
		val clearMoon = renderForegroundOnNightSky(width, height, clear, timeSeconds, renderer)
		val clearWithoutMoon = renderForegroundOnNightSky(width, height, clear.copy(moonVisible = false), timeSeconds, renderer)

		try {
			val centerX = width * CELESTIAL_X_FRACTION
			val centerY = height * celestialHeightFraction(params.dayPhase, params.celestialProgress)
			val radius = minOf(width, height) * MOON_RADIUS_FRACTION
			val cloudyBrightness = lunarContribution(cloudyMoon, cloudyWithoutMoon, prepared.cloudSamples, centerX, centerY, radius)
			val clearBrightness = lunarContribution(clearMoon, clearWithoutMoon, prepared.cloudSamples, centerX, centerY, radius)

			// The unchanged crescent contributes about 67 units on the API 37.2 reference render; 40 leaves room for rasterization differences.
			assertTrue("The clear crescent must be visibly brighter than its no-moon control; measured $clearBrightness", clearBrightness > 40f)
			assertTrue("Clouds over the dark hemisphere must preserve lit-crescent brightness; cloudy=$cloudyBrightness clear=$clearBrightness", retainsCrescentBrightness(cloudyBrightness, clearBrightness))

			val overDimmedMoon = dimmedRenderedFrame(cloudyMoon)
			val overDimmedWithoutMoon = dimmedRenderedFrame(cloudyWithoutMoon)
			try {
				val overDimmedBrightness = lunarContribution(overDimmedMoon, overDimmedWithoutMoon, prepared.cloudSamples, centerX, centerY, radius)
				assertFalse("A deliberately 35%-bright rendered crescent must fail the same brightness guard; dimmed=$overDimmedBrightness clear=$clearBrightness", retainsCrescentBrightness(overDimmedBrightness, clearBrightness))
			} finally {
				overDimmedMoon.recycle()
				overDimmedWithoutMoon.recycle()
			}
		} finally {
			cloudyMoon.recycle()
			cloudyWithoutMoon.recycle()
			clearMoon.recycle()
			clearWithoutMoon.recycle()
		}
	}

	@Test
	fun partialCloudOverlapPreservesTheUncoveredLunarSurface() {
		val renderer = SceneRenderer(resources, epochDay = 20_000L)
		renderer.prewarmCloudTextures()
		for (phase in listOf(0.5f, 0.25f)) {
			val params = clearNightParams(moonPhase = phase, cloudiness = 0.7f)
			renderer.prewarmMoonPhase(phase)
			val cloudy = renderForegroundOnNightSky(540, 960, params, 592f, renderer)
			val clear = renderForegroundOnNightSky(540, 960, params.copy(cloudiness = 0f), 592f, renderer)
			try {
				val cloudyBrightness = pixelLuminance(cloudy.getPixel(399, 260))
				val clearBrightness = pixelLuminance(clear.getPixel(399, 260))
				assertTrue("The exposed lunar surface must not dim when a cloud crosses elsewhere; phase=$phase cloudy=$cloudyBrightness clear=$clearBrightness", cloudyBrightness >= clearBrightness * 0.97f)
			} finally {
				cloudy.recycle()
				clear.recycle()
			}
		}
	}

	@Test
	fun denseCloudOverlapErasesLunarSurfaceDetail() {
		val renderer = SceneRenderer(resources, epochDay = 20_000L)
		val params = clearNightParams(cloudiness = 0.7f)
		renderer.prewarmCloudTextures()
		renderer.prewarmMoonPhase(params.moonPhase)
		val cloudy = renderForegroundOnNightSky(540, 960, params, 592f, renderer)
		val cloudyControl = renderForegroundOnNightSky(540, 960, params.copy(moonVisible = false), 592f, renderer)
		val clear = renderForegroundOnNightSky(540, 960, params.copy(cloudiness = 0f), 592f, renderer)
		val clearControl = renderForegroundOnNightSky(540, 960, params.copy(cloudiness = 0f, moonVisible = false), 592f, renderer)
		try {
			val cloudyDetail = lunarDetailEnergy(cloudy, cloudyControl)
			val clearDetail = lunarDetailEnergy(clear, clearControl)
			assertTrue("Dense cumulus must hide lunar markings instead of exposing a ghost disc; cloudy=$cloudyDetail clear=$clearDetail", cloudyDetail < clearDetail * 0.08f)
		} finally {
			cloudy.recycle()
			cloudyControl.recycle()
			clear.recycle()
			clearControl.recycle()
		}
	}

	@Test
	fun frozenMoonMaskRefreshesWhenCloudAssetsFinishPrewarming() {
		for (progress in listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f)) {
			val params = clearNightParams(cloudiness = 0.74f).copy(windScale = 0f, celestialProgress = progress)
			val renderer = SceneRenderer(resources, epochDay = 20_000L)
			renderer.prewarmMoonPhase(params.moonPhase)
			renderForegroundOnNightSky(540, 960, params, 592f, renderer).recycle()
			renderer.prewarmCloudTextures()
			val afterPreparation = renderForegroundOnNightSky(540, 960, params, 592f, renderer)
			val freshRenderer = SceneRenderer(resources, epochDay = 20_000L)
			freshRenderer.prewarmCloudTextures()
			freshRenderer.prewarmMoonPhase(params.moonPhase)
			val preparedFromStart = renderForegroundOnNightSky(540, 960, params, 592f, freshRenderer)
			try {
				assertArrayEquals("Finishing cloud preparation must invalidate a stationary moon mask at progress $progress", pixels(preparedFromStart), pixels(afterPreparation))
			} finally {
				afterPreparation.recycle()
				preparedFromStart.recycle()
			}
		}
	}

	/** Remove the cloud/sky control and measure local lunar contrast inside the fixed dense-overlap region. */
	private fun lunarDetailEnergy(withMoon: Bitmap, withoutMoon: Bitmap): Float {
		var energy = 0f
		for (y in 212..228) {
			for (x in 375..415) {
				val here = pixelLuminance(withMoon.getPixel(x, y)) - pixelLuminance(withoutMoon.getPixel(x, y))
				val next = pixelLuminance(withMoon.getPixel(x + 1, y)) - pixelLuminance(withoutMoon.getPixel(x + 1, y))
				energy += abs(next - here)
			}
		}

		return energy
	}

	@Test
	fun moonSizeScaleIsExcludedFromBackdropIndependence() {
		val params50 = clearNightParams(moonSizeScale = 0.5f)
		val params100 = clearNightParams(moonSizeScale = 1.0f)
		val params200 = clearNightParams(moonSizeScale = 2.0f)

		assertEquals(
			"Moon size scale must be excluded from backdrop signature",
			backdropSignature(params50),
			backdropSignature(params100)
		)

		assertEquals(
			"Moon size scale must be excluded from backdrop signature",
			backdropSignature(params100),
			backdropSignature(params200)
		)

		val backdrop50 = renderBackdrop(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, params50)
		val backdrop100 = renderBackdrop(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, params100)
		val backdrop200 = renderBackdrop(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, params200)

		assertArrayEquals(
			"Backdrop raster must be 100% identical regardless of moon size scale",
			pixels(backdrop50),
			pixels(backdrop100)
		)

		assertArrayEquals(
			"Backdrop raster must be 100% identical regardless of moon size scale",
			pixels(backdrop100),
			pixels(backdrop200)
		)

		backdrop50.recycle()
		backdrop100.recycle()
		backdrop200.recycle()
	}

	private fun assertMoonSizeAndCenter(width: Int, height: Int) {
		val expectedCenter = celestialCenter(width, height)

		val bitmap50 = renderForeground(width, height, clearNightParams(moonPhase = 0.5f, moonSizeScale = 0.5f))
		val bitmap100 = renderForeground(width, height, clearNightParams(moonPhase = 0.5f, moonSizeScale = 1.0f))
		val bitmap200 = renderForeground(width, height, clearNightParams(moonPhase = 0.5f, moonSizeScale = 2.0f))

		val bounds50 = opaqueBounds(bitmap50, threshold = OPAQUE_ALPHA_THRESHOLD)
		val bounds100 = opaqueBounds(bitmap100, threshold = OPAQUE_ALPHA_THRESHOLD)
		val bounds200 = opaqueBounds(bitmap200, threshold = OPAQUE_ALPHA_THRESHOLD)

		assertTrue("Moon 50% must remain centered at $expectedCenter (was ${bounds50.centerX}, ${bounds50.centerY})", abs(bounds50.centerX - expectedCenter.x) <= POSITION_TOLERANCE_PIXELS && abs(bounds50.centerY - expectedCenter.y) <= POSITION_TOLERANCE_PIXELS)
		assertTrue("Moon 100% must remain centered at $expectedCenter (was ${bounds100.centerX}, ${bounds100.centerY})", abs(bounds100.centerX - expectedCenter.x) <= POSITION_TOLERANCE_PIXELS && abs(bounds100.centerY - expectedCenter.y) <= POSITION_TOLERANCE_PIXELS)
		assertTrue("Moon 200% must remain centered at $expectedCenter (was ${bounds200.centerX}, ${bounds200.centerY})", abs(bounds200.centerX - expectedCenter.x) <= POSITION_TOLERANCE_PIXELS && abs(bounds200.centerY - expectedCenter.y) <= POSITION_TOLERANCE_PIXELS)

		val ratio200to100 = bounds200.height.toFloat() / bounds100.height.toFloat()
		val ratio50to100 = bounds50.height.toFloat() / bounds100.height.toFloat()

		assertTrue("Moon diameter at 200% should scale by ~2x relative to 100% (was $ratio200to100)", ratio200to100 in 1.85f..2.15f)
		assertTrue("Moon diameter at 50% should scale by ~0.5x relative to 100% (was $ratio50to100)", ratio50to100 in 0.45f..0.55f)

		bitmap50.recycle()
		bitmap100.recycle()
		bitmap200.recycle()
	}

	private fun clearNightParams(
		moonPhase: Float = 0.5f,
		moonSizeScale: Float = 1f,
		moonVisible: Boolean = true,
		dayPhase: DayPhase = DayPhase.NIGHT,
		precipitation: Precipitation? = null,
		cloudiness: Float = 0f,
		fogDensity: Float = 0f
	) = SceneParams(
		dayPhase = dayPhase,
		cloudiness = cloudiness,
		fogDensity = fogDensity,
		precipitation = precipitation,
		thunder = false,
		windFactor = 0.2f,
		moonPhase = moonPhase,
		moonSizeScale = moonSizeScale,
		moonVisible = moonVisible
	)

	private fun renderMoon(phase: Float): Bitmap {
		val bitmap = createBitmap(512, 512)
		val layer = MoonLayer(resources)
		layer.prewarm(phase)
		layer.spriteFor(layer.phaseIndexFor(phase))?.let { Canvas(bitmap).drawBitmap(it, 0f, 0f, null) }

		return bitmap
	}

	private fun renderForeground(
		width: Int,
		height: Int,
		params: SceneParams,
		timeSeconds: Float = 0f,
		renderer: SceneRenderer? = null
	): Bitmap {
		val actualRenderer = renderer ?: SceneRenderer(resources)
		actualRenderer.prewarmMoonPhase(params.moonPhase)
		return renderCachedForeground(width, height, params, timeSeconds, actualRenderer)
	}

	private fun renderCachedForeground(width: Int, height: Int, params: SceneParams, timeSeconds: Float, renderer: SceneRenderer): Bitmap {
		val bitmap = createBitmap(width, height)
		renderer.renderForeground(Canvas(bitmap), width, height, params, timeSeconds)

		return bitmap
	}

	/** Draw onto the same opaque night sky so RGB differences reflect lunar light rather than unpremultiplied transparent pixels. */
	private fun renderForegroundOnNightSky(width: Int, height: Int, params: SceneParams, timeSeconds: Float, renderer: SceneRenderer): Bitmap {
		val bitmap = createBitmap(width, height)
		val canvas = Canvas(bitmap)
		canvas.drawColor(Color.rgb(14, 20, 40))
		renderer.renderForeground(canvas, width, height, params, timeSeconds)

		return bitmap
	}

	private fun pixelLuminance(color: Int) = Color.red(color) * 0.2126f + Color.green(color) * 0.7152f + Color.blue(color) * 0.0722f

	/** Measure only the moon's weighted RGB contribution, subtracting the identical scene drawn without the moon. */
	private fun lunarContribution(withMoon: Bitmap, withoutMoon: Bitmap, samples: FloatArray, centerX: Float, centerY: Float, radius: Float): Float {
		var luminance = 0f
		for (index in samples.indices step 3) {
			val x = (centerX + samples[index] * radius).roundToInt()
			val y = (centerY + samples[index + 1] * radius).roundToInt()
			val weight = samples[index + 2]
			luminance += weight * (pixelLuminance(withMoon.getPixel(x, y)) - pixelLuminance(withoutMoon.getPixel(x, y))).coerceAtLeast(0f)
		}

		return luminance
	}

	private fun retainsCrescentBrightness(actual: Float, clear: Float) = clear > 40f && actual / clear > 0.75f

	/** The negative control deliberately dims both rendered frames equally, preserving their shared sky and alpha. */
	private fun dimmedRenderedFrame(source: Bitmap): Bitmap {
		val output = createBitmap(source.width, source.height)
		val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
			colorFilter = LightingColorFilter(Color.rgb(89, 89, 89), 0)
		}
		Canvas(output).drawBitmap(source, 0f, 0f, paint)

		return output
	}

	private fun renderBackdrop(
		width: Int,
		height: Int,
		params: SceneParams,
		renderer: SceneRenderer? = null
	): Bitmap {
		val bitmap = createBitmap(width, height)
		val actualRenderer = renderer ?: SceneRenderer(resources)
		actualRenderer.renderBackdrop(Canvas(bitmap), width, height, params)

		return bitmap
	}

	private fun celestialCenter(width: Int, height: Int, dayPhase: DayPhase = DayPhase.NIGHT, progress: Float = 0.5f) = PixelPoint(
		x = (width * CELESTIAL_X_FRACTION).roundToInt(),
		y = (height * celestialHeightFraction(dayPhase, progress)).roundToInt()
	)

	private fun opaqueBounds(bitmap: Bitmap, threshold: Int = OPAQUE_ALPHA_THRESHOLD): PixelBounds {
		var left = bitmap.width
		var top = bitmap.height
		var right = -1
		var bottom = -1

		for (y in 0 until bitmap.height) {
			for (x in 0 until bitmap.width) {
				if (Color.alpha(bitmap.getPixel(x, y)) < threshold) {
					continue
				}

				left = minOf(left, x)
				top = minOf(top, y)
				right = maxOf(right, x)
				bottom = maxOf(bottom, y)
			}
		}

		return PixelBounds(left, top, right, bottom)
	}

	private fun highestAlpha(bitmap: Bitmap, center: PixelPoint, radius: Float): Int {
		val radiusSquared = radius * radius
		var peak = 0

		val startY = (center.y - radius.toInt()).coerceAtLeast(0)
		val endY = (center.y + radius.toInt()).coerceAtMost(bitmap.height - 1)
		val startX = (center.x - radius.toInt()).coerceAtLeast(0)
		val endX = (center.x + radius.toInt()).coerceAtMost(bitmap.width - 1)

		for (y in startY..endY) {
			for (x in startX..endX) {
				val dx = x - center.x
				val dy = y - center.y
				if (dx * dx + dy * dy > radiusSquared) {
					continue
				}

				peak = maxOf(peak, Color.alpha(bitmap.getPixel(x, y)))
			}
		}

		return peak
	}

	private fun colorDistance(first: Int, second: Int) = maxOf(
		abs(Color.red(first) - Color.red(second)),
		abs(Color.green(first) - Color.green(second)),
		abs(Color.blue(first) - Color.blue(second))
	)

	private fun pixels(bitmap: Bitmap): IntArray {
		val array = IntArray(bitmap.width * bitmap.height)
		bitmap.getPixels(array, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

		return array
	}

	private data class PixelPoint(val x: Int, val y: Int)

	private data class PixelBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
		val isValid = left <= right && top <= bottom
		val centerX = (left + right) / 2
		val centerY = (top + bottom) / 2
		val height = if (isValid) {
			bottom - top + 1
		} else {
			0
		}
	}

	private companion object {
		const val PORTRAIT_WIDTH = 360
		const val PORTRAIT_HEIGHT = 780
		const val LANDSCAPE_WIDTH = 780
		const val LANDSCAPE_HEIGHT = 360
		const val CELESTIAL_X_FRACTION = 0.72f
		const val MOON_RADIUS_FRACTION = 0.1f
		const val OPAQUE_ALPHA_THRESHOLD = 245
		const val HALO_MAX_ALPHA = 100
		const val POSITION_TOLERANCE_PIXELS = 2
		val MOON_RADIUS_RANGE = 0.08f..0.12f
	}
}
