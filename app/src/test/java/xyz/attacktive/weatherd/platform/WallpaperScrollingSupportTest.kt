package xyz.attacktive.weatherd.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperScrollingSupportTest {
	@Test
	fun `One UI Home disables wallpaper scrolling`() {
		assertFalse(wallpaperScrollingSupportedBy("com.sec.android.app.launcher"))
	}

	@Test
	fun `other and unknown launchers keep wallpaper scrolling available`() {
		assertTrue(wallpaperScrollingSupportedBy("com.example.launcher"))
		assertTrue(wallpaperScrollingSupportedBy(null))
	}
}
