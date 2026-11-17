package xyz.attacktive.weatherd.domain.render

import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.domain.model.BirdFlockSize
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.model.SunStyle
import xyz.attacktive.weatherd.domain.model.SunbeamMode

@RunWith(AndroidJUnit4::class)
class SunlitMoteRenderingTest {
	@Test
	fun everySunStyleIlluminatesMotesWithBeamsAndFlareOff() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val renderer = SceneRenderer(context.resources)
		val field = SunlitMoteField()
		val params = SceneParams(DayPhase.DAWN, 0f, 0f, null, false, 0f, celestialProgress = 0.15f, sunbeamMode = SunbeamMode.OFF, lensFlareEnabled = false, distantAircraftEnabled = false, birdFlockSize = BirdFlockSize.OFF)
		for ((width, height) in listOf(360 to 780, 780 to 360)) {
			field.update(360f, width * 0.72f, height * celestialHeightFraction(DayPhase.DAWN, 0.15f), 38.88f, 12f)
			for (style in SunStyle.entries) {
				renderer.prewarmSunTexture(style)
				val bitmap = createBitmap(width, height)
				try {
					val canvas = Canvas(bitmap)
					renderer.renderBackdrop(canvas, width, height, params)
					renderer.renderForeground(canvas, width, height, params.copy(sunStyle = style), 12f)
					var contrast = 0
					for (index in field.x.indices) {
						val x = field.x[index].roundToInt()
						val y = field.y[index].roundToInt()
						if (x in 4 until width - 4 && y in 4 until height - 4) {
							contrast += Color.red(bitmap.getPixel(x, y)) * 4 - Color.red(bitmap.getPixel(x - 4, y)) - Color.red(bitmap.getPixel(x + 4, y)) - Color.red(bitmap.getPixel(x, y - 4)) - Color.red(bitmap.getPixel(x, y + 4))
						}
					}

					assertTrue("$style ${width}x$height must illuminate local motes, contrast=$contrast", contrast > 100)
				} finally {
					bitmap.recycle()
				}
			}
		}

		captureScenes()
	}

	@Test
	fun passingCloudDimsMotesEvenWithBeamsAndFlareOff() {
		val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
		val renderer = SceneRenderer(resources, 20743L).apply { prewarmCloudTextures() }
		val params = SceneParams(DayPhase.DAY, 0.4f, 0f, null, false, 0.2f, sunbeamMode = SunbeamMode.OFF, lensFlareEnabled = false, distantAircraftEnabled = false, birdFlockSize = BirdFlockSize.OFF)
		val field = SunlitMoteField()
		val clouds = createBitmap(360, 780)
		val scene = createBitmap(360, 780)
		val unoccluded = createBitmap(360, 780)
		val layer = SunlitMoteLayer()
		var unoccludedContrast = 0L
		var samples = 0
		var totalContrast = 0L
		var maximumSourceOpacity = 0
		try {
			for (second in 0..360 step 15) {
				clouds.eraseColor(Color.TRANSPARENT)
				renderer.renderForeground(Canvas(clouds), 360, 780, params.copy(sunVisible = false), second.toFloat())
				maximumSourceOpacity = maxOf(maximumSourceOpacity, Color.alpha(clouds.getPixel(259, 133)))
				if (Color.alpha(clouds.getPixel(259, 133)) < 180) {
					continue
				}

				val canvas = Canvas(scene)
				renderer.renderBackdrop(canvas, 360, 780, params)
				renderer.renderForeground(canvas, 360, 780, params, second.toFloat())
				val unoccludedCanvas = Canvas(unoccluded)
				renderer.renderBackdrop(unoccludedCanvas, 360, 780, params)
				layer.draw(unoccludedCanvas, 360f, 259.2f, 132.6f, 38.88f, second.toFloat(), sunlitMoteStrength(params))
				field.update(360f, 259.2f, 132.6f, 38.88f, second.toFloat())
				for (index in field.x.indices) {
					val x = field.x[index].roundToInt()
					val y = field.y[index].roundToInt()
					if (x in 4 until 356 && y in 4 until 776 && clearPatch(clouds, x, y)) {
						totalContrast += localContrast(scene, x, y)
						unoccludedContrast += localContrast(unoccluded, x, y)
						samples++
					}
				}
			}

			assertTrue("Fixture must cover the Sun while leaving some mote locations open; maximum source opacity=$maximumSourceOpacity", samples > 0)
			assertTrue("Cloud-covered Sun must dim nearby illumination: contrast=$totalContrast unoccluded=$unoccludedContrast samples=$samples", totalContrast < unoccludedContrast * 0.6f)
		} finally {
			clouds.recycle()
			scene.recycle()
			unoccluded.recycle()
		}
	}

	private fun clearPatch(bitmap: Bitmap, x: Int, y: Int): Boolean {
		for (dy in -4..4) {
			for (dx in -4..4) {
				if (Color.alpha(bitmap.getPixel(x + dx, y + dy)) > 3) {
					return false
				}
			}
		}

		return true
	}

	private fun localContrast(bitmap: Bitmap, x: Int, y: Int) = Color.red(bitmap.getPixel(x, y)) * 4 - Color.red(bitmap.getPixel(x - 4, y)) - Color.red(bitmap.getPixel(x + 4, y)) - Color.red(bitmap.getPixel(x, y - 4)) - Color.red(bitmap.getPixel(x, y + 4))

	@Test
	fun transmissionDimsSoftPointsAndZeroLeavesCanvasUntouched() {
		val layer = SunlitMoteLayer()
		val bitmap = createBitmap(360, 780)
		try {
			val canvas = Canvas(bitmap)
			layer.draw(canvas, 360f, 180f, 200f, 40f, 0f, 1f)
			val bright = alphaSum(bitmap)
			assertTrue(bright > 0L)
			assertEquals(Color.TRANSPARENT, bitmap.getPixel(180, 200))
			assertEquals(Color.TRANSPARENT, bitmap.getPixel(10, 700))
			bitmap.eraseColor(Color.TRANSPARENT)
			layer.draw(canvas, 360f, 180f, 200f, 40f, 0f, 0.25f)
			val dim = alphaSum(bitmap)
			assertTrue(dim in 1 until bright / 2)
			bitmap.eraseColor(Color.TRANSPARENT)
			layer.draw(canvas, 360f, 180f, 200f, 40f, 0f, 0f)
			assertEquals(0L, alphaSum(bitmap))
		} finally {
			bitmap.recycle()
		}
	}

	private fun captureScenes() {
		val capture = InstrumentationRegistry.getArguments().getString("sunlitMoteEvidence") ?: return
		val time = InstrumentationRegistry.getArguments().getString("sunlitMoteTime")?.toFloat() ?: 12f
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val renderer = SceneRenderer(context.resources).apply {
			prewarmCloudTextures()
			SunStyle.entries.forEach { prewarmSunTexture(it) }
		}

		val clear = SceneParams(DayPhase.DAWN, 0f, 0f, null, false, 0f, celestialProgress = 0.15f, sunbeamMode = SunbeamMode.OFF, lensFlareEnabled = false, distantAircraftEnabled = false, birdFlockSize = BirdFlockSize.OFF)
		val scenes = mapOf(
			"dawn" to clear,
			"daylight" to clear.copy(dayPhase = DayPhase.DAY, celestialProgress = 0.5f),
			"overcast" to clear.copy(cloudiness = 0.9f),
			"rain" to clear.copy(precipitation = Precipitation(PrecipitationKind.RAIN, 0.3f, 0.3f)),
			"night" to clear.copy(dayPhase = DayPhase.NIGHT),
			"scattered" to clear.copy(dayPhase = DayPhase.DAY, cloudiness = 0.5f)
		)

		for ((width, height) in listOf(360 to 780, 780 to 360)) {
			for ((name, params) in scenes) {
				for (style in SunStyle.entries) {
					val bitmap = createBitmap(width, height)
					try {
						val canvas = Canvas(bitmap)
						renderer.renderBackdrop(canvas, width, height, params)
						renderer.renderForeground(canvas, width, height, params.copy(sunStyle = style), time)
						val file = File(context.getExternalFilesDir(null), "motes_${capture}_${name}_${style}_${width}x$height.png")
						FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
					} finally {
						bitmap.recycle()
					}
				}
			}
		}
	}

	private fun alphaSum(bitmap: Bitmap): Long {
		var sum = 0L
		for (y in 0 until bitmap.height) {
			for (x in 0 until bitmap.width) {
				sum += Color.alpha(bitmap.getPixel(x, y))
			}
		}

		return sum
	}
}
