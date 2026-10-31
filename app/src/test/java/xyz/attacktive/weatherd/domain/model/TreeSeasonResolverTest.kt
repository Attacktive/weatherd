package xyz.attacktive.weatherd.domain.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class TreeSeasonResolverTest {
	private fun epoch(utc: String) = Instant.parse(utc).epochSecond

	@Test
	fun `meteorological month boundaries reverse across hemispheres`() {
		val dates = listOf(
			"2026-02-28T23:59:59Z" to (TreeSeason.WINTER to TreeSeason.SUMMER),
			"2026-03-01T00:00:00Z" to (TreeSeason.SPRING to TreeSeason.AUTUMN),
			"2026-05-31T23:59:59Z" to (TreeSeason.SPRING to TreeSeason.AUTUMN),
			"2026-06-01T00:00:00Z" to (TreeSeason.SUMMER to TreeSeason.WINTER),
			"2026-08-31T23:59:59Z" to (TreeSeason.SUMMER to TreeSeason.WINTER),
			"2026-09-01T00:00:00Z" to (TreeSeason.AUTUMN to TreeSeason.SPRING),
			"2026-11-30T23:59:59Z" to (TreeSeason.AUTUMN to TreeSeason.SPRING),
			"2026-12-01T00:00:00Z" to (TreeSeason.WINTER to TreeSeason.SUMMER),
			"2028-02-29T12:00:00Z" to (TreeSeason.WINTER to TreeSeason.SUMMER)
		)

		for ((date, pair) in dates) {
			assertEquals(date, pair.first, resolvedTreeSeason(TreeSeason.AUTO, epoch(date), 37.57))
			assertEquals(date, pair.second, resolvedTreeSeason(TreeSeason.AUTO, epoch(date), -33.9))
		}
	}

	@Test
	fun `unknown invalid and tropical latitudes have neutral spring foliage`() {
		val now = epoch("2026-10-31T12:00:00Z")
		for (latitude in listOf(null, 0.0, 1.3, -14.99, 15.0, -15.0, 91.0, -91.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
			assertEquals("Latitude $latitude", TreeSeason.SPRING, resolvedTreeSeason(TreeSeason.AUTO, now, latitude))
		}
		assertEquals(TreeSeason.AUTUMN, resolvedTreeSeason(TreeSeason.AUTO, now, 15.01))
		assertEquals(TreeSeason.SPRING, resolvedTreeSeason(TreeSeason.AUTO, now, -15.01))
	}

	@Test
	fun `manual choices do not depend on location or current date`() {
		for (season in TreeSeason.entries.filter { it != TreeSeason.AUTO }) {
			assertEquals(season, resolvedTreeSeason(season, epoch("2026-03-01T00:00:00Z"), null))
			assertEquals(season, resolvedTreeSeason(season, epoch("2026-12-01T00:00:00Z"), -37.0))
		}
	}
}
