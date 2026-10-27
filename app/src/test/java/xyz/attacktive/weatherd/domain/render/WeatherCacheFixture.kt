package xyz.attacktive.weatherd.domain.render

import io.mockk.coEvery
import io.mockk.mockk
import xyz.attacktive.weatherd.domain.repository.CachedWeather
import xyz.attacktive.weatherd.domain.repository.WeatherCacheRepository

/** Isolates storage for renderer tests; cache persistence has real DataStore coverage. */
internal fun weatherCacheFixture(initial: CachedWeather? = null): WeatherCacheRepository = mockk {
	coEvery { load() } returns initial
	coEvery { save(any()) } returns Unit
}
