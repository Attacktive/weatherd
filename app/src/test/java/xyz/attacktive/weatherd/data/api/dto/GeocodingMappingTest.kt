package xyz.attacktive.weatherd.data.api.dto

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.GeoPlace

class GeocodingMappingTest {
	@Test
	fun `maps a geocoding result to a place`() {
		val place = GeocodingResultDto(name = "Tokyo", latitude = 35.68, longitude = 139.69, country = "Japan", admin1 = "Tokyo").toGeoPlace()

		assertEquals("Tokyo", place.name)
		assertEquals(35.68, place.latitude, 0.0)
		assertEquals(139.69, place.longitude, 0.0)
		assertEquals("Japan", place.country)
		assertEquals("Tokyo", place.region)
	}

	@Test
	fun `label drops a region that duplicates the city name`() {
		val place = GeoPlace(name = "Tokyo", latitude = 0.0, longitude = 0.0, region = "Tokyo", country = "Japan")

		assertEquals("Tokyo, Japan", place.label)
	}

	@Test
	fun `label includes a distinct region`() {
		val place = GeoPlace(name = "Springfield", latitude = 0.0, longitude = 0.0, region = "Illinois", country = "United States")

		assertEquals("Springfield, Illinois, United States", place.label)
	}
}
