package xyz.attacktive.weatherd.service

import org.junit.Assert.assertEquals
import org.junit.Test

class WallpaperParallaxTest {
	@Test
	fun `disabled scrolling keeps the current screen-sized scene`() {
		assertEquals(1000, wallpaperSceneWidth(1000, scrollingEnabled = false))
		assertEquals(0f, wallpaperViewportLeft(1000, 1000, xOffset = 1f, scrollingEnabled = false), 0.0001f)
	}

	@Test
	fun `enabled scrolling renders a wider scene centered at half offset`() {
		val sceneWidth = wallpaperSceneWidth(1000, scrollingEnabled = true)

		assertEquals(1120, sceneWidth)
		assertEquals(60f, wallpaperViewportLeft(1000, sceneWidth, xOffset = 0.5f, scrollingEnabled = true), 0.0001f)
	}

	@Test
	fun `launcher offsets span the virtual scene without exposing past its edges`() {
		val sceneWidth = wallpaperSceneWidth(1000, scrollingEnabled = true)

		assertEquals(60f, wallpaperViewportLeft(1000, sceneWidth, xOffset = -1f, scrollingEnabled = true), 0.0001f)
		assertEquals(120f, wallpaperViewportLeft(1000, sceneWidth, xOffset = 2f, scrollingEnabled = true), 0.0001f)
	}

	@Test
	fun `invalid launcher offsets fall back to the centered viewport`() {
		assertEquals(0.5f, normalizeWallpaperOffset(-1f), 0.0001f)
		assertEquals(0.5f, normalizeWallpaperOffset(Float.NaN), 0.0001f)
		assertEquals(0.5f, normalizeWallpaperOffset(Float.POSITIVE_INFINITY), 0.0001f)
	}
}
