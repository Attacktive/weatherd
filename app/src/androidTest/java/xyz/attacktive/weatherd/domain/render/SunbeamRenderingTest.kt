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
	fun atmosphericRaysSpreadOutwardInsteadOfConvergingBelowTheSun() {
		val params = clearParams()
		val off = render(params.copy(sunbeamMode = SunbeamMode.OFF))
		val active = render(params.copy(sunbeamMode = SunbeamMode.CLEAR_SKIES_TOO))
		try {
			val upperWidth = litWidth(active, off, 210)
			val lowerWidth = litWidth(active, off, 300)
			assertTrue("Ray fan should widen away from its source, not cross: $upperWidth -> $lowerWidth", upperWidth > 0 && lowerWidth > upperWidth)
		} finally {
			off.recycle()
			active.recycle()
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
			assertTrue("Partial cloud openings must transmit sunlight", firstEnergy > 0L)
			assertTrue("Moving cloud openings must change the transmitted light", firstEnergy != movedEnergy)
		} finally {
			active.recycle()
			off.recycle()
			moved.recycle()
			movedOff.recycle()
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
		val energies = listOf(0.099f, 0.1f, 0.101f).map { cloudiness ->
			val params = clearParams().copy(cloudiness = cloudiness)
			val off = render(params.copy(sunbeamMode = SunbeamMode.OFF))
			val active = render(params.copy(sunbeamMode = SunbeamMode.CLEAR_SKIES_TOO))
			try {
				beamEnergy(active, off)
			} finally {
				off.recycle()
				active.recycle()
			}
		}

		assertTrue("Clear rays must exist before cloud cover begins", energies.first() > 0L)
		assertTrue("The cloud boundary must not pop the beam field: $energies", abs(energies.last() - energies.first()) < energies.first() / 10)
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

	private fun beamEnergy(active: Bitmap, off: Bitmap): Long {
		var energy = 0L
		for (y in 200 until 600) {
			for (x in 0 until active.width) {
				val light = active.getPixel(x, y)
				val control = off.getPixel(x, y)
				energy += abs(Color.alpha(light) - Color.alpha(control))
			}
		}

		return energy
	}

	private fun litWidth(active: Bitmap, off: Bitmap, y: Int): Int {
		var left = active.width
		var right = -1
		for (x in 0 until active.width) {
			if (Color.alpha(active.getPixel(x, y)) - Color.alpha(off.getPixel(x, y)) >= 2) {
				left = minOf(left, x)
				right = maxOf(right, x)
			}
		}

		return (right - left + 1).coerceAtLeast(0)
	}

	private fun renderer() = SceneRenderer(resources).apply {
		prewarmCloudTextures()
		prewarmSunTexture()
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
