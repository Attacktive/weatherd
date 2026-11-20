package xyz.attacktive.weatherd.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperBackdropCachePolicyTest {
	@Test
	fun `matching surface dimensions retain the cached backdrop`() {
		assertTrue(shouldRetainWallpaperBackdrop(cachedWidth = 1080, cachedHeight = 2400, sceneWidth = 1080, surfaceHeight = 2400))
		assertTrue(shouldRetainWallpaperBackdrop(cachedWidth = 1210, cachedHeight = 2400, sceneWidth = 1210, surfaceHeight = 2400))
	}

	@Test
	fun `width or height changes invalidate the cached backdrop`() {
		assertFalse(shouldRetainWallpaperBackdrop(cachedWidth = 1080, cachedHeight = 2400, sceneWidth = 1210, surfaceHeight = 2400))
		assertFalse(shouldRetainWallpaperBackdrop(cachedWidth = 1080, cachedHeight = 2400, sceneWidth = 1080, surfaceHeight = 2340))
		assertFalse(shouldRetainWallpaperBackdrop(cachedWidth = 1080, cachedHeight = 2400, sceneWidth = 2400, surfaceHeight = 1080))
	}

	@Test
	fun `crossfade requires both backdrop dimensions to match`() {
		assertTrue(canCrossfadeBackdrop(outgoingWidth = 1080, outgoingHeight = 2400, currentWidth = 1080, currentHeight = 2400))
		assertFalse(canCrossfadeBackdrop(outgoingWidth = 1080, outgoingHeight = 2400, currentWidth = 1210, currentHeight = 2400))
		assertFalse(canCrossfadeBackdrop(outgoingWidth = 1080, outgoingHeight = 2400, currentWidth = 1080, currentHeight = 2340))
	}
}
