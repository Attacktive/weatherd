package xyz.attacktive.weatherd.domain.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.TreeSeason

class TreeSeasonColorsTest {
	private val foliage = 0x88408030.toInt()
	private val trunk = 0xff76513a.toInt()

	@Test
	fun `spring and unresolved auto preserve every pixel exactly`() {
		for (pixel in listOf(0, 0x00808080, foliage, trunk)) {
			assertEquals(pixel, seasonalTreeFoliagePixel(pixel, TreeSeason.SPRING))
			assertEquals(pixel, seasonalTreeFoliagePixel(pixel, TreeSeason.AUTO))
		}
	}

	@Test
	fun `seasonal foliage changes RGB but never source alpha or trunk`() {
		val colors = TreeSeason.entries.filter { it != TreeSeason.SPRING && it != TreeSeason.AUTO }.map { season ->
			val result = seasonalTreeFoliagePixel(foliage, season)
			assertEquals(foliage ushr 24, result ushr 24)
			assertNotEquals(foliage, result)
			assertEquals(trunk, seasonalTreeFoliagePixel(trunk, season))
			assertEquals(0x00554433, seasonalTreeFoliagePixel(0x00554433, season))
			result
		}

		assertEquals(colors.size, colors.toSet().size)
	}

	@Test
	fun `winter foliage is cool and autumn foliage is warm`() {
		val autumn = seasonalTreeFoliagePixel(foliage, TreeSeason.AUTUMN)
		val winter = seasonalTreeFoliagePixel(foliage, TreeSeason.WINTER)
		val red = autumn ushr 16 and 0xff
		val green = autumn ushr 8 and 0xff
		val winterBlue = winter and 0xff
		val winterRed = winter ushr 16 and 0xff

		assertTrue(red > green)
		assertTrue(winterBlue > winterRed)
	}
}
