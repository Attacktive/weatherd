package xyz.attacktive.weatherd.domain.render

import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.weather.SEVERITY_STORM

@RunWith(AndroidJUnit4::class)
class ReadabilityRenderingTest {
	private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
	private val params = SceneParams(DayPhase.DAY, 0f, 0f, null, false, 0f)

	@Test
	fun disablingBothOverlaysLeavesNoStaleShading() {
		val effects = SceneScreenEffects(SceneRenderer(resources))
		val bitmap = background()
		effects.render(Canvas(bitmap), WIDTH, HEIGHT, params.copy(wallpaperDimmingStrength = 0.5f, statusBarShadeStrength = 0.5f), 0f)
		bitmap.eraseColor(BACKGROUND)
		effects.render(Canvas(bitmap), WIDTH, HEIGHT, params, 0f)
		val untouched = background()
		assertTrue("Turning both controls off must restore the exact original pixels", bitmap.sameAs(untouched))
		bitmap.recycle()
		untouched.recycle()
	}

	@Test
	fun wallpaperDimmingDarkensTheWholeCompletedSceneUniformly() {
		val bitmap = background()
		SceneScreenEffects(SceneRenderer(resources)).render(Canvas(bitmap), WIDTH, HEIGHT, params.copy(wallpaperDimmingStrength = 0.5f), 0f)

		for (y in intArrayOf(0, HEIGHT / 2, HEIGHT - 1)) {
			val pixel = bitmap.getPixel(WIDTH / 2, y)
			assertEquals(50f, Color.red(pixel).toFloat(), 1f)
			assertEquals(75f, Color.green(pixel).toFloat(), 1f)
			assertEquals(100f, Color.blue(pixel).toFloat(), 1f)
			assertEquals(255, Color.alpha(pixel))
		}

		bitmap.recycle()
	}

	@Test
	fun statusBarShadeFadesWithoutDimmingTheRestOfTheWallpaper() {
		val bitmap = background()
		SceneScreenEffects(SceneRenderer(resources)).render(Canvas(bitmap), WIDTH, HEIGHT, params.copy(statusBarShadeStrength = 0.5f), 0f)

		val top = Color.red(bitmap.getPixel(WIDTH / 2, 0))
		val fading = Color.red(bitmap.getPixel(WIDTH / 2, HEIGHT / 10))
		assertTrue("The top edge must be shaded", top in 49..52)
		assertTrue("Shading must smoothly decrease away from the top", fading in (top + 1)..99)
		assertEquals(BACKGROUND, bitmap.getPixel(WIDTH / 2, HEIGHT / 2))
		assertEquals(BACKGROUND, bitmap.getPixel(WIDTH / 2, HEIGHT - 1))
		bitmap.recycle()
	}

	@Test
	fun changingViewportRebuildsTheGradientWithoutMovingItHorizontally() {
		val effects = SceneScreenEffects(SceneRenderer(resources))
		val shaded = params.copy(statusBarShadeStrength = 0.5f)
		val portrait = background()
		effects.render(Canvas(portrait), WIDTH, HEIGHT, shaded, 0f)
		val landscape = createBitmap(HEIGHT, WIDTH).also { it.eraseColor(BACKGROUND) }
		effects.render(Canvas(landscape), HEIGHT, WIDTH, shaded, 0f)
		val fresh = createBitmap(HEIGHT, WIDTH).also { it.eraseColor(BACKGROUND) }
		SceneScreenEffects(SceneRenderer(resources)).render(Canvas(fresh), HEIGHT, WIDTH, shaded, 0f)

		assertTrue("A reused gradient must match a fresh renderer at the new viewport size", landscape.sameAs(fresh))
		assertEquals(landscape.getPixel(0, 10), landscape.getPixel(HEIGHT - 1, 10))
		assertEquals(BACKGROUND, landscape.getPixel(HEIGHT / 2, WIDTH / 2))
		portrait.recycle()
		landscape.recycle()
		fresh.recycle()
	}

	@Test
	fun glassDropletsAreDarkenedWithTheCompletedScene() {
		val rainy = params.copy(
			precipitation = Precipitation(PrecipitationKind.RAIN, SEVERITY_STORM, observed = 1f),
			precipitationScale = 2f,
			glassDropletsEnabled = true
		)

		val actual = background()
		SceneScreenEffects(SceneRenderer(resources)).render(Canvas(actual), WIDTH, HEIGHT, rainy.copy(wallpaperDimmingStrength = 0.5f), 11.25f)
		val expected = background()
		SceneScreenEffects(SceneRenderer(resources)).render(Canvas(expected), WIDTH, HEIGHT, rainy, 11.25f)
		Canvas(expected).drawColor(Color.argb(128, 0, 0, 0))

		assertTrue("Screen glass must be composited before readability shading", actual.sameAs(expected))
		actual.recycle()
		expected.recycle()
	}

	@Test
	fun labelsRemainAboveBothReadabilityOverlays() {
		val labeled = params.copy(overlayLabels = OverlayLabels("CLEAR", "Seoul"), wallpaperDimmingStrength = 0.5f, statusBarShadeStrength = 0.5f)
		val actual = background()
		SceneScreenEffects(SceneRenderer(resources)).render(Canvas(actual), WIDTH, HEIGHT, labeled, 0f)
		val expected = background()
		SceneScreenEffects(SceneRenderer(resources)).render(Canvas(expected), WIDTH, HEIGHT, labeled.copy(overlayLabels = null), 0f)
		SceneRenderer(resources).renderOverlayLabels(Canvas(expected), WIDTH, HEIGHT, labeled)

		assertTrue("Readability shading must not dim Weatherd's own labels", actual.sameAs(expected))
		actual.recycle()
		expected.recycle()
	}

	private fun background() = createBitmap(WIDTH, HEIGHT).also { it.eraseColor(BACKGROUND) }

	private companion object {
		const val WIDTH = 360
		const val HEIGHT = 780
		val BACKGROUND = Color.rgb(100, 150, 200)
	}
}
