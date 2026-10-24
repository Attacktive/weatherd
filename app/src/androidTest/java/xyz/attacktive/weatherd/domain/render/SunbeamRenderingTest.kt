package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.domain.model.BirdFlockSize
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.model.SunColorPreset
import xyz.attacktive.weatherd.domain.model.SunStyle
import xyz.attacktive.weatherd.domain.model.SunbeamMode

@RunWith(AndroidJUnit4::class)
class SunbeamRenderingTest {
	private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

	companion object {
		// 2026-10-17 UTC (epoch day 20743), matching original CI attempt 1 baseline.
		private const val BASELINE_FIXTURE_EPOCH_DAY = 20743L

		// 2026-10-18 UTC (epoch day 20744), corresponding to CI attempt 2 date rollover.
		private const val DRIFTED_FIXTURE_EPOCH_DAY = 20744L
	}

	@Test
	fun clearSkyRequiresExplicitOptInAndRetainsTheSunInBothStyles() {
		for (style in SunStyle.entries) {
			val params = clearParams().copy(sunStyle = style)
			val off = render(params.copy(sunbeamMode = SunbeamMode.OFF))
			val through = render(params)
			val clear = render(params.copy(sunbeamMode = SunbeamMode.CLEAR_SKIES_TOO))
			try {
				assertTrue("Through clouds must not add rays to a clear sky: $style", off.sameAs(through))
				assertTrue("Clear skies too must add light below the sun: $style", beamEnergy(clear, off) > 0L)
				assertTrue("Off must retain the solar core: $style", Color.alpha(off.getPixel(259, 133)) >= 245)
				assertTrue("Beams must not alter the solar core: $style", off.getPixel(259, 133) == clear.getPixel(259, 133))
			} finally {
				off.recycle()
				through.recycle()
				clear.recycle()
			}
		}
	}

	@Test
	fun clearSkyGlareExtendsAboveAndBelowTheSolarSourceInBothStyles() {
		for (style in SunStyle.entries) {
			val params = clearParams().copy(sunStyle = style)
			val off = render(params.copy(sunbeamMode = SunbeamMode.OFF))
			val active = render(params.copy(sunbeamMode = SunbeamMode.CLEAR_SKIES_TOO))
			try {
				assertTrue("Optical glare must extend above the sun, not hang below it: $style", beamEnergy(active, off, 20, 70) > 0L)
				assertTrue("Optical glare must also extend below the sun: $style", beamEnergy(active, off, 200, 250) > 0L)
			} finally {
				off.recycle()
				active.recycle()
			}
		}
	}

	@Test
	fun partialCloudOpeningsProduceLightAndRespondToCloudMovement() {
		val params = clearParams().copy(cloudiness = 0.7f)
		val active = render(params, time = 240f)
		val off = render(params.copy(sunbeamMode = SunbeamMode.OFF), time = 240f)
		val moved = render(params, time = 120f)
		val movedOff = render(params.copy(sunbeamMode = SunbeamMode.OFF), time = 120f)
		try {
			val firstEnergy = beamEnergy(active, off)
			val movedEnergy = beamEnergy(moved, movedOff)
			assertTrue("Partial cloud openings must transmit sunlight (firstEnergy=$firstEnergy, movedEnergy=$movedEnergy, cloudiness=${params.cloudiness})", firstEnergy > 0L)
			assertTrue("Moving cloud openings must change the transmitted light (firstEnergy=$firstEnergy, movedEnergy=$movedEnergy)", firstEnergy != movedEnergy)
		} finally {
			active.recycle()
			off.recycle()
			moved.recycle()
			movedOff.recycle()
		}
	}

	@Test
	fun passingCloudEdgesDoNotIgniteShaftsInASingleFrame() {
		val params = clearParams().copy(cloudiness = 0.7f)
		val activeRenderer = renderer()
		val controlRenderer = renderer()
		var previousEnergy = 0L
		var largestJump = 0L
		var peakEnergy = 0L
		for (frame in 0..90) {
			val time = 223f + frame / 30f
			val active = render(params, renderer = activeRenderer, time = time)
			val off = render(params.copy(sunbeamMode = SunbeamMode.OFF), renderer = controlRenderer, time = time)
			try {
				val energy = beamEnergy(active, off)
				if (frame > 0) {
					largestJump = maxOf(largestJump, abs(energy - previousEnergy))
				}

				peakEnergy = maxOf(peakEnergy, energy)
				previousEnergy = energy
			} finally {
				active.recycle()
				off.recycle()
			}
		}

		assertTrue("A passing cloud must produce visible shafts", peakEnergy > 0L)
		assertTrue("One frame must not ignite a large fraction of the shaft field: jump=$largestJump peak=$peakEnergy", largestJump < peakEnergy / 5)
	}

	@Test
	fun fadedShaftHistoryCannotSurviveHiddenSunOrSuppressedWeather() {
		val base = clearParams().copy(cloudiness = 0.7f)
		val suppressed = listOf(
			base.copy(sunbeamMode = SunbeamMode.OFF),
			base.copy(dayPhase = DayPhase.NIGHT),
			base.copy(sunVisible = false),
			base.copy(fogDensity = 0.5f),
			base.copy(thunder = true),
			base.copy(precipitation = Precipitation(PrecipitationKind.RAIN, severity = 0.5f, observed = 0.5f)),
			base.copy(cloudiness = 0.85f)
		)

		for (params in suppressed) {
			val shared = renderer()
			render(base, renderer = shared, time = 223f).recycle()
			render(base, renderer = shared, time = 224f).recycle()
			val hidden = render(params, renderer = shared, time = 224.1f)
			val hiddenFresh = render(params, time = 224.1f)
			val restored = render(base, renderer = shared, time = 224.2f)
			val restoredFresh = render(base, time = 224.2f)
			try {
				assertTrue("Suppression must remove shafts immediately: $params", hidden.sameAs(hiddenFresh))
				assertTrue("Returning to sunlight must not reuse old shaft strengths: $params", restored.sameAs(restoredFresh))
			} finally {
				hidden.recycle()
				hiddenFresh.recycle()
				restored.recycle()
				restoredFresh.recycle()
			}
		}
	}

	@Test
	fun settledSimulatorProgressEditsCannotFreezeOldCloudOpenings() {
		val base = clearParams().copy(cloudiness = 0.7f)
		val shared = renderer()
		render(base, renderer = shared, time = 223f).recycle()
		render(base, renderer = shared, time = 224f).recycle()
		for (step in 1..20) {
			val params = base.copy(celestialProgress = 0.5f + step * 0.001f)
			val edited = render(params, renderer = shared, time = 224f)
			val current = render(params, time = 224f)
			try {
				assertTrue("A settled slider edit must resample the current opening at step $step", edited.sameAs(current))
			} finally {
				edited.recycle()
				current.recycle()
			}
		}
	}

	@Test
	fun midOnlyCloudVeilingSuppressesClearRaysWithoutAddingDistantOpeningShafts() {
		for (style in SunStyle.entries) {
			val params = clearParams().copy(cloudiness = 0.6f, cloudLayers = SceneCloudLayers(low = 0f, mid = 0.6f, high = 0f), sunStyle = style)
			val off = render(params.copy(sunbeamMode = SunbeamMode.OFF))
			val through = render(params.copy(sunbeamMode = SunbeamMode.THROUGH_CLOUDS))
			val clear = render(params.copy(sunbeamMode = SunbeamMode.CLEAR_SKIES_TOO))
			try {
				assertTrue("Distant cloud openings do not generate their own shafts: $style", off.sameAs(through))
				assertTrue("A mid-only deck veiling the sun must also suppress clear-sky rays: $style", off.sameAs(clear))
			} finally {
				off.recycle()
				through.recycle()
				clear.recycle()
			}
		}
	}

	@Test
	fun highOnlyCirrusDoesNotSuppressClearSkyRays() {
		for (style in SunStyle.entries) {
			val params = clearParams().copy(cloudiness = 0.6f, cloudLayers = SceneCloudLayers(low = 0f, mid = 0f, high = 0.6f), sunStyle = style)
			val off = render(params.copy(sunbeamMode = SunbeamMode.OFF))
			val clear = render(params.copy(sunbeamMode = SunbeamMode.CLEAR_SKIES_TOO))
			try {
				assertTrue("Nonopaque high cirrus must not act like a mid-level sun veil: $style", beamEnergy(clear, off) > 0L)
			} finally {
				off.recycle()
				clear.recycle()
			}
		}
	}

	@Test
	fun modesCannotOverrideSunVisibilityOrObscuringWeather() {
		val base = clearParams()
		val suppressed = listOf(
			base.copy(dayPhase = DayPhase.NIGHT),
			base.copy(sunVisible = false),
			base.copy(fogDensity = 0.5f),
			base.copy(thunder = true),
			base.copy(precipitation = Precipitation(PrecipitationKind.RAIN, severity = 0.5f, observed = 0.5f)),
			base.copy(cloudiness = 0.85f),
			base.copy(dayPhase = DayPhase.DUSK, celestialProgress = 0.95f)
		)

		for (params in suppressed) {
			val off = render(params.copy(sunbeamMode = SunbeamMode.OFF))
			try {
				for (mode in listOf(SunbeamMode.THROUGH_CLOUDS, SunbeamMode.CLEAR_SKIES_TOO)) {
					val active = render(params.copy(sunbeamMode = mode))
					try {
						assertTrue("Suppressed sunbeams must match Off: $params, $mode", off.sameAs(active))
					} finally {
						active.recycle()
					}
				}
			} finally {
				off.recycle()
			}
		}
	}

	@Test
	fun reusedRendererAgreesWithFreshFramesAcrossModesColorsStylesAndDimensions() {
		val shared = renderer()
		val scenes = listOf(
			clearParams().copy(sunbeamMode = SunbeamMode.CLEAR_SKIES_TOO),
			clearParams().copy(cloudiness = 0.7f),
			clearParams().copy(sunbeamMode = SunbeamMode.OFF, sunStyle = SunStyle.RADIANT),
			clearParams().copy(sunbeamMode = SunbeamMode.CLEAR_SKIES_TOO, sunColorPreset = SunColorPreset.ORANGE),
			clearParams()
		)

		for (params in scenes) {
			for ((width, height) in listOf(360 to 780, 780 to 360)) {
				val reused = render(params, width, height, shared)
				val fresh = render(params, width, height)
				try {
					assertTrue("Changing foreground preferences must not retain stale paint or textures: $params", reused.sameAs(fresh))
				} finally {
					reused.recycle()
					fresh.recycle()
				}
			}
		}
	}

	@Test
	fun clearModeFadesContinuouslyAtTheFirstCloudPopulationBoundary() {
		// Evaluated across both the baseline fixture date (2026-10-17, epoch day 20743) and the drifted CI date (2026-10-18, epoch day 20744).
		for (epochDay in listOf(BASELINE_FIXTURE_EPOCH_DAY, DRIFTED_FIXTURE_EPOCH_DAY)) {
			val shared = renderer(epochDay)
			val testCases = listOf(0.099f, 0.1f, 0.101f).map { cloudiness ->
				val params = clearParams().copy(cloudiness = cloudiness)
				val off = render(params.copy(sunbeamMode = SunbeamMode.OFF), renderer = shared)
				val active = render(params.copy(sunbeamMode = SunbeamMode.CLEAR_SKIES_TOO), renderer = shared)
				Pair(active, off)
			}
			try {
				// Measure energy on pixels unobscured by the newly spawned cumulus deck to isolate ray-field continuity from foreground cloud occlusion.
				val boundaryOff = testCases.last().second
				val energies = testCases.map { (active, off) ->
					beamEnergy(active, off, unoccludedOnly = boundaryOff)
				}

				assertTrue("Clear rays must exist before cloud cover begins on day $epochDay", energies.first() > 0L)
				assertTrue("The cloud boundary must not pop the beam field on day $epochDay: $energies", abs(energies.last() - energies.first()) < energies.first() / 10)
			} finally {
				testCases.forEach { (active, off) ->
					active.recycle()
					off.recycle()
				}
			}
		}
	}

	@Test
	fun cloudShaftsCannotComeFromAnEmptyPatchAroundTheSun() {
		val params = clearParams().copy(cloudiness = 0.65f, cloudLayers = SceneCloudLayers(low = 0.65f, mid = 0f, high = 0f))
		val shared = renderer()
		var emptyFrames = 0
		for (second in 0..360 step 15) {
			val clouds = render(params.copy(sunVisible = false), renderer = shared, time = second.toFloat())
			val empty = sourceOpacityRange(clouds).last <= 3
			clouds.recycle()
			if (empty) {
				emptyFrames++
				val off = render(params.copy(sunbeamMode = SunbeamMode.OFF), renderer = shared, time = second.toFloat())
				val active = render(params, renderer = shared, time = second.toFloat())
				try {
					assertTrue("No cloud is visible around the solar source at $second seconds, so there is no opening to carve shafts", off.sameAs(active))
				} finally {
					off.recycle()
					active.recycle()
				}
			}
		}

		assertTrue("The fixture must include an unobstructed solar patch", emptyFrames > 0)
	}

	private fun sourceOpacityRange(bitmap: Bitmap): IntRange {
		var minimum = 255
		var maximum = 0
		for (y in 119..147 step 3) {
			for (x in 208..310 step 3) {
				val alpha = Color.alpha(bitmap.getPixel(x, y))
				minimum = minOf(minimum, alpha)
				maximum = maxOf(maximum, alpha)
			}
		}

		return minimum..maximum
	}

	private fun beamEnergy(active: Bitmap, off: Bitmap, top: Int = 200, bottom: Int = 600, unoccludedOnly: Bitmap? = null): Long {
		val width = active.width
		val height = bottom - top
		val count = width * height
		val activePixels = IntArray(count)
		val controlPixels = IntArray(count)
		active.getPixels(activePixels, 0, width, 0, top, width, height)
		off.getPixels(controlPixels, 0, width, 0, top, width, height)

		val maskPixels = unoccludedOnly?.let {
			val pixels = IntArray(count)
			it.getPixels(pixels, 0, width, 0, top, width, height)
			pixels
		}

		var energy = 0L
		for (index in 0 until count) {
			if (maskPixels == null || Color.alpha(maskPixels[index]) == 0) {
				energy += abs(Color.alpha(activePixels[index]) - Color.alpha(controlPixels[index]))
			}
		}

		return energy
	}

	private fun renderer(epochDay: Long? = BASELINE_FIXTURE_EPOCH_DAY) = SceneRenderer(resources, epochDay).apply {
		prewarmCloudTextures()
		SunStyle.entries.forEach { prewarmSunTexture(it) }
	}

	private fun render(params: SceneParams, width: Int = 360, height: Int = 780, renderer: SceneRenderer = renderer(), time: Float = 240f): Bitmap {
		val bitmap = createBitmap(width, height)
		renderer.renderForeground(Canvas(bitmap), width, height, params, time)
		return bitmap
	}

	private fun clearParams() = SceneParams(
		dayPhase = DayPhase.DAY,
		cloudiness = 0f,
		fogDensity = 0f,
		precipitation = null,
		thunder = false,
		windFactor = 0.2f,
		lensFlareEnabled = false,
		distantAircraftEnabled = false,
		birdFlockSize = BirdFlockSize.OFF
	)
}
