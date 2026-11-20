package xyz.attacktive.weatherd.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.SkyColorPreset
import xyz.attacktive.weatherd.domain.render.SceneParams
import xyz.attacktive.weatherd.domain.render.backdropSignature

class WallpaperBackdropCachePolicyTest {
	@Test
	fun `repeated same-size surface callbacks and foreground-only updates reuse the same cached backdrop without rasterizing`() {
		val cache = WallpaperBackdropCache<Any>()
		val base = baseParams()
		var rasterizations = 0
		val first = cache.resolve(1080, 2400, backdropSignature(base)) {
			rasterizations++
			Any()
		}

		cache.onSurfaceChanged(sceneWidth = 1080, surfaceHeight = 2400)
		cache.onSurfaceChanged(sceneWidth = 1080, surfaceHeight = 2400)

		val foregroundOnlyVariants = listOf(
			base.copy(windFactor = 0.9f),
			base.copy(windScale = 1.8f),
			base.copy(precipitationScale = 1.6f),
			base.copy(cloudCountScale = 0.6f)
		)

		for (variant in foregroundOnlyVariants) {
			val outgoing = cache.current
			val resolved = cache.resolve(1080, 2400, backdropSignature(variant)) {
				rasterizations++
				Any()
			}

			cache.updateTransition(outgoing, resolved, timeSeconds = 5f, motionActive = true)

			assertSame(first.value, resolved.value)
			assertNull(cache.previous)
		}

		assertEquals(1, rasterizations)
	}

	@Test
	fun `surface destruction clears an active crossfade while preserving the cached backdrop for same-size recreation`() {
		val cache = WallpaperBackdropCache<Any>()
		val initial = baseParams()
		val updated = initial.copy(dayPhase = DayPhase.NIGHT)
		var rasterizations = 0

		val first = cache.resolve(1080, 2400, backdropSignature(initial)) {
			rasterizations++
			Any()
		}

		val second = cache.resolve(1080, 2400, backdropSignature(updated)) {
			rasterizations++
			Any()
		}

		cache.updateTransition(first, second, timeSeconds = 10f, motionActive = true)
		assertSame(first, cache.previous)
		assertEquals(10f, cache.fadeStartSeconds, 0.0001f)

		cache.onSurfaceDestroyed()
		assertNull(cache.previous)

		cache.onSurfaceChanged(sceneWidth = 1080, surfaceHeight = 2400)
		val afterRecreation = cache.resolve(1080, 2400, backdropSignature(updated)) {
			rasterizations++
			Any()
		}

		assertSame(second.value, afterRecreation.value)
		assertEquals(2, rasterizations)
	}

	@Test
	fun `surface width or height changes invalidate the cache and suppress crossfade`() {
		val cache = WallpaperBackdropCache<Any>()
		val signature = backdropSignature(baseParams())
		var rasterizations = 0

		val initial = cache.resolve(1080, 2400, signature) {
			rasterizations++
			Any()
		}

		cache.onSurfaceChanged(sceneWidth = 1080, surfaceHeight = 2340)
		assertNull(cache.current)
		assertNull(cache.previous)

		val resized = cache.resolve(1080, 2340, signature) {
			rasterizations++
			Any()
		}

		cache.updateTransition(outgoing = initial, incoming = resized, timeSeconds = 12f, motionActive = true)

		assertNotSame(initial.value, resized.value)
		assertNull(cache.previous)
		assertEquals(2, rasterizations)

		val scrolled = cache.resolve(1210, 2340, signature) {
			rasterizations++
			Any()
		}

		cache.updateTransition(outgoing = resized, incoming = scrolled, timeSeconds = 13f, motionActive = true)

		assertNotSame(resized.value, scrolled.value)
		assertNull(cache.previous)
		assertEquals(3, rasterizations)
	}

	@Test
	fun `genuine backdrop change crossfades only while motion is active`() {
		val cache = WallpaperBackdropCache<Any>()
		val day = backdropSignature(baseParams(dayPhase = DayPhase.DAY))
		val warm = backdropSignature(baseParams(dayPhase = DayPhase.DAY).copy(skyColorPreset = SkyColorPreset.WARM))
		val night = backdropSignature(baseParams(dayPhase = DayPhase.NIGHT))

		val first = cache.resolve(1080, 2400, day) { Any() }
		val second = cache.resolve(1080, 2400, warm) { Any() }
		cache.updateTransition(first, second, timeSeconds = 7.5f, motionActive = true)

		assertSame(first, cache.previous)
		assertEquals(7.5f, cache.fadeStartSeconds, 0.0001f)

		val third = cache.resolve(1080, 2400, night) { Any() }
		cache.updateTransition(second, third, timeSeconds = 9f, motionActive = false)

		assertNull(cache.previous)
	}

	@Test
	fun `shouldCrossfadeBackdrop checks nullability, identity, and both dimensions`() {
		val signature = backdropSignature(baseParams())
		val base = CachedWallpaperBackdrop(Any(), width = 1080, height = 2400, signature = signature)
		val sameSize = CachedWallpaperBackdrop(Any(), width = 1080, height = 2400, signature = signature)
		val wider = CachedWallpaperBackdrop(Any(), width = 1210, height = 2400, signature = signature)
		val shorter = CachedWallpaperBackdrop(Any(), width = 1080, height = 2340, signature = signature)

		assertFalse(shouldCrossfadeBackdrop(outgoing = null, current = base))
		assertFalse(shouldCrossfadeBackdrop(outgoing = base, current = base))
		assertTrue(shouldCrossfadeBackdrop(outgoing = base, current = sameSize))
		assertFalse(shouldCrossfadeBackdrop(outgoing = base, current = wider))
		assertFalse(shouldCrossfadeBackdrop(outgoing = base, current = shorter))
	}

	@Test
	fun `matching surface dimensions retain the cached backdrop`() {
		assertTrue(shouldRetainWallpaperBackdrop(cachedWidth = 1080, cachedHeight = 2400, sceneWidth = 1080, surfaceHeight = 2400))
		assertTrue(shouldRetainWallpaperBackdrop(cachedWidth = 1210, cachedHeight = 2400, sceneWidth = 1210, surfaceHeight = 2400))
		assertFalse(shouldRetainWallpaperBackdrop(cachedWidth = 1080, cachedHeight = 2400, sceneWidth = 1210, surfaceHeight = 2400))
		assertFalse(shouldRetainWallpaperBackdrop(cachedWidth = 1080, cachedHeight = 2400, sceneWidth = 1080, surfaceHeight = 2340))
	}

	private fun baseParams(dayPhase: DayPhase = DayPhase.DAY) = SceneParams(
		dayPhase = dayPhase,
		cloudiness = 0.4f,
		fogDensity = 0f,
		precipitation = null,
		thunder = false,
		windFactor = 0.3f,
		precipitationScale = 1f,
		windScale = 1f,
		cloudCountScale = 1f
	)
}
