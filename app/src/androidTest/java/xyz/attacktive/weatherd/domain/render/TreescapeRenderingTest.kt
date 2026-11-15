package xyz.attacktive.weatherd.domain.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.model.TreeSeason

@RunWith(AndroidJUnit4::class)
class TreescapeRenderingTest {
	private val layer = TreescapeLayer(InstrumentationRegistry.getInstrumentation().targetContext.resources)
	private val clear = SceneParams(
		dayPhase = DayPhase.DAY,
		cloudiness = 0f,
		fogDensity = 0f,
		precipitation = null,
		thunder = false,
		windFactor = 0f,
		backdropScene = BackdropScene.TREESCAPE,
		treeSeason = TreeSeason.SPRING,
		treesEnabled = false
	)

	@Test
	fun meadowCoversBottomAndLeavesUpperSkyOpenInBothOrientations() {
		layer.prewarm(TreeSeason.SPRING)
		for ((width, height) in listOf(240 to 480, 480 to 240)) {
			val bitmap = render(width, height, clear)
			try {
				for (x in 0 until width) {
					assertEquals("Upper sky at $x in ${width}x$height", 0, Color.alpha(bitmap.getPixel(x, 0)))
					assertEquals("Bottom edge at $x in ${width}x$height", 255, Color.alpha(bitmap.getPixel(x, height - 1)))
				}
			} finally {
				bitmap.recycle()
			}
		}
	}

	@Test
	fun treesStayLowAndGroundedWithoutBeingStretchedAcrossLandscape() {
		layer.prewarm(TreeSeason.SPRING)
		for ((width, height) in listOf(240 to 480, 480 to 240)) {
			val bitmap = render(width, height, clear)
			try {
				val horizon = (0 until height).first { y -> (0 until width).count { x -> Color.alpha(bitmap.getPixel(x, y)) > 128 } > width * 0.9f }
				val top = (0 until horizon).first { y -> (0 until width).any { x -> Color.alpha(bitmap.getPixel(x, y)) > 128 } }
				val canopyBottom = top + (horizon - top) / 2
				val treePixels = (top until canopyBottom).flatMap { y ->
					(0 until width).filter { x -> Color.alpha(bitmap.getPixel(x, y)) > 128 }.map { x -> x to y }
				}

				assertTrue("Trees must rise above the meadow", treePixels.isNotEmpty())
				val left = treePixels.minOf { it.first }
				val right = treePixels.maxOf { it.first }
				val grounded = groundedPixels(bitmap)
				assertTrue("Trees must leave open sky", top > height * 0.15f)
				assertTrue("Tree crowns must connect to the meadow through their trunks", treePixels.count { grounded[it.second * width + it.first] } > treePixels.size * 0.9f)
				assertTrue("Tree composition must retain its broad proportions", right - left > (horizon - top) * 1.2f)
				assertTrue("Landscape trees must not stretch to fill the viewport", width < height || right - left < width * 0.8f)
			} finally {
				bitmap.recycle()
			}
		}
	}

	@Test
	fun seasonalArtworkChangesMaterialsButPreservesSilhouettesAndCachedVariants() {
		val seasons = listOf(TreeSeason.SPRING, TreeSeason.SUMMER, TreeSeason.AUTUMN, TreeSeason.WINTER)
		val images = mutableListOf<Bitmap>()
		try {
			for (season in seasons) {
				layer.prewarm(season)
				assertTrue("$season must be ready after preparation", layer.isTextureReady(season))
				images.add(render(240, 480, clear.copy(treeSeason = season)))
			}

			for (season in seasons) {
				assertTrue("Preparing another season must retain $season", layer.isTextureReady(season))
			}

			for (i in images.indices) {
				for (j in 0 until i) {
					assertSameAlpha(images[i], images[j])
					assertTrue("Seasons must change the tree canopy", changedPixels(images[i], images[j], 0, 300) > 20)
					assertTrue("Seasons must change the meadow", changedPixels(images[i], images[j], 350, 480) > 20)
				}
			}

			val springAgain = render(240, 480, clear)
			try {
				assertTrue("Returning to spring must restore its cached appearance", images[0].sameAs(springAgain))
			} finally {
				springAgain.recycle()
			}
		} finally {
			images.forEach { it.recycle() }
		}
	}

	@Test
	fun weatherAndNightGradeTheSceneryWithoutChangingItsSilhouette() {
		layer.prewarm(TreeSeason.SPRING)
		val day = render(240, 480, clear)
		val conditions = listOf(
			clear.copy(dayPhase = DayPhase.DAWN),
			clear.copy(dayPhase = DayPhase.DUSK),
			clear.copy(dayPhase = DayPhase.NIGHT),
			clear.copy(cloudiness = 1f, fogDensity = 1f, precipitation = Precipitation(PrecipitationKind.RAIN, 1f, 1f))
		)
		try {
			for (condition in conditions) {
				val graded = render(240, 480, condition)
				try {
					assertSameAlpha(day, graded)
					assertFalse("Environmental lighting must change the artwork", day.sameAs(graded))
				} finally {
					graded.recycle()
				}
			}

			val night = clear.copy(dayPhase = DayPhase.NIGHT)
			val normal = render(240, 480, night)
			val dim = render(240, 480, night.copy(nightBrightnessScale = 0.2f))
			try {
				assertSameAlpha(normal, dim)
				assertTrue("Night brightness must dim the scenery", brightness(dim) < brightness(normal) * 0.35)
			} finally {
				normal.recycle()
				dim.recycle()
			}
		} finally {
			day.recycle()
		}
	}

	private fun groundedPixels(bitmap: Bitmap): BooleanArray {
		val width = bitmap.width
		val visited = BooleanArray(width * bitmap.height)
		val queue = IntArray(visited.size)
		var start = 0
		var end = 0
		for (x in 0 until width) {
			val index = (bitmap.height - 1) * width + x
			visited[index] = true
			queue[end++] = index
		}

		while (start < end) {
			val index = queue[start++]
			val x = index % width
			val y = index / width
			for (nearY in maxOf(0, y - 1)..minOf(bitmap.height - 1, y + 1)) {
				for (nearX in maxOf(0, x - 1)..minOf(width - 1, x + 1)) {
					val neighbor = nearY * width + nearX
					if (!visited[neighbor] && Color.alpha(bitmap.getPixel(nearX, nearY)) > 32) {
						visited[neighbor] = true
						queue[end++] = neighbor
					}
				}
			}
		}

		return visited
	}

	private fun render(width: Int, height: Int, params: SceneParams): Bitmap {
		val bitmap = createBitmap(width, height)
		layer.draw(Canvas(bitmap), width.toFloat(), height.toFloat(), params)
		return bitmap
	}

	private fun assertSameAlpha(expected: Bitmap, actual: Bitmap) {
		for (y in 0 until expected.height) {
			for (x in 0 until expected.width) {
				assertEquals("Alpha at $x,$y", Color.alpha(expected.getPixel(x, y)), Color.alpha(actual.getPixel(x, y)))
			}
		}
	}

	private fun changedPixels(first: Bitmap, second: Bitmap, top: Int, bottom: Int): Int {
		var count = 0
		for (y in top until bottom) {
			for (x in 0 until first.width) {
				if (first.getPixel(x, y) != second.getPixel(x, y)) {
					count++
				}
			}
		}

		return count
	}

	private fun brightness(bitmap: Bitmap): Double {
		var sum = 0.0
		for (y in 0 until bitmap.height) {
			for (x in 0 until bitmap.width) {
				val color = bitmap.getPixel(x, y)
				sum += (Color.red(color) * 0.2126 + Color.green(color) * 0.7152 + Color.blue(color) * 0.0722) * Color.alpha(color)
			}
		}

		return sum
	}
}
