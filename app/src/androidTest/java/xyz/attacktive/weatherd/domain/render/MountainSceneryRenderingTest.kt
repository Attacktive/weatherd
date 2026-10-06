package xyz.attacktive.weatherd.domain.render

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.SunColorPreset

@RunWith(AndroidJUnit4::class)
class MountainSceneryRenderingTest {
	private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
	private val clear = SceneParams(DayPhase.DAY, 0f, 0f, null, false, 0f, backdropScene = BackdropScene.MOUNTAINS)

	@Test
	fun rockAndSnowShareDirectionalFaces() {
		val bitmap = software(200, 200) { syntheticRenderer().drawPlane(it, SceneryPlane.FAR) }

		try {
			assertDirectionalFaces(bitmap)
		} finally {
			bitmap.recycle()
		}
	}

	@Test
	fun materialMasksPreserveTerrainAndSnowBoundaries() {
		val outlines = checkNotNull(sceneryOutlinesFor(BackdropScene.MOUNTAINS, 0.75f))
		val renderer = prepared(outlines, 240, 320)
		val actual = software(240, 320) { drawPlanes(renderer, it) }
		val snow = software(200, 200) { syntheticRenderer().drawPlane(it, SceneryPlane.FAR) }
		val bare = software(200, 200) { syntheticRenderer(false).drawPlane(it, SceneryPlane.FAR) }

		try {
			assertClipped(actual, outlines)
			for (y in 86..102) {
				for (x in 36..44) {
					assertEquals("Snow changed rock outside its polygon at $x,$y", bare.getPixel(x, y), snow.getPixel(x, y))
				}
			}
		} finally {
			actual.recycle()
			snow.recycle()
			bare.recycle()
		}
	}

	@Test
	fun reusedRendererMatchesFreshAfterWeatherAndViewportChanges() {
		val reused = SceneRenderer(resources)
		for ((width, height) in listOf(240 to 480, 480 to 240, 360 to 480, 240 to 480)) {
			for (params in listOf(clear, clear.copy(cloudiness = 0.85f), clear)) {
				val actual = scene(reused, width, height, params)
				val expected = scene(SceneRenderer(resources), width, height, params)

				try {
					assertTrue("Stale material state for ${width}x$height, cover=${params.cloudiness}", expected.sameAs(actual))
				} finally {
					actual.recycle()
					expected.recycle()
				}
			}
		}
	}

	@Test
	fun mountainsDisappearThroughPhotoAndNoneAndReturnWithoutStaleShading() {
		val reused = SceneRenderer(resources)
		for (backdrop in listOf(BackdropScene.MOUNTAINS, BackdropScene.PHOTO, BackdropScene.NONE, BackdropScene.MOUNTAINS)) {
			val params = clear.copy(backdropScene = backdrop)
			val actual = scene(reused, 240, 480, params)
			val expected = scene(SceneRenderer(resources), 240, 480, params)

			try {
				assertTrue("Terrain survived or restored stale shading through $backdrop", expected.sameAs(actual))
			} finally {
				actual.recycle()
				expected.recycle()
			}
		}
	}

	@Test
	fun decorativeSunPreferencesPreserveTerrainPixels() {
		val ordinary = scene(SceneRenderer(resources), 240, 480, clear)
		val hidden = scene(SceneRenderer(resources), 240, 480, clear.copy(sunVisible = false, moonVisible = false, lensFlareEnabled = false, sunSizeScale = 2f, sunColorPreset = SunColorPreset.ORANGE))

		try {
			// Interior forest and meadow remain well below sky artwork, valley mist, and antialiased silhouettes.
			for (y in 452 until 478) {
				for (x in 4 until 236) {
					assertEquals("Decorative source styling changed terrain at $x,$y", ordinary.getPixel(x, y), hidden.getPixel(x, y))
				}
			}
		} finally {
			ordinary.recycle()
			hidden.recycle()
		}
	}

	@Test
	@SdkSuppress(minSdkVersion = 29)
	fun hardwarePreservesDirectionalFacesAndClipping() {
		val directional = hardware(200, 200) { syntheticRenderer().drawPlane(it, SceneryPlane.FAR) }
		val outlines = checkNotNull(sceneryOutlinesFor(BackdropScene.MOUNTAINS, 0.75f))
		val renderer = prepared(outlines, 240, 320)
		val actual = hardware(240, 320) { drawPlanes(renderer, it) }

		try {
			assertDirectionalFaces(directional)
			assertClipped(actual, outlines)
		} finally {
			directional.recycle()
			actual.recycle()
		}
	}

	private fun assertDirectionalFaces(bitmap: Bitmap) {
		val rockLeft = luminance(bitmap, 60, 130, 80, 148)
		val rockRight = luminance(bitmap, 120, 130, 140, 148)
		val snowLeft = luminance(bitmap, 60, 86, 80, 102)
		val snowRight = luminance(bitmap, 120, 86, 140, 102)

		assertTrue("Exposed rock must be brighter: $rockLeft -> $rockRight", rockRight > rockLeft + 3f)
		assertTrue("Snow must share the exposed rock face: $snowLeft -> $snowRight", snowRight > snowLeft + 3f)
	}

	private fun assertClipped(actual: Bitmap, outlines: SceneryOutlines) {
		val width = actual.width
		val height = actual.height
		val expected = software(width, height) { canvas ->
			val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
			for (layer in outlines.layers) {
				val path = Path()
				path.moveTo(layer.outline.first().x * width, layer.outline.first().y * height)
				for (point in layer.outline.drop(1)) {
					path.lineTo(point.x * width, point.y * height)
				}

				path.lineTo(width.toFloat(), height.toFloat())
				path.lineTo(0f, height.toFloat())
				path.close()
				canvas.drawPath(path, paint)
			}
		}

		val mask = pixels(expected)
		val result = pixels(actual)

		try {
			for (y in 2 until height - 2 step 3) {
				for (x in 2 until width - 2 step 3) {
					if (Color.alpha(mask[y * width + x]) == 0 && Color.alpha(mask[(y + 2) * width + x]) == 0 && Color.alpha(mask[y * width + x - 2]) == 0 && Color.alpha(mask[y * width + x + 2]) == 0) {
						assertEquals("Material escaped original terrain at $x,$y", 0, Color.alpha(result[y * width + x]))
					}
				}
			}
		} finally {
			expected.recycle()
		}
	}

	private fun syntheticRenderer(snow: Boolean = true): MountainSurfaceRenderer {
		val ridge = listOf(OutlinePoint(0f, 0.6f), OutlinePoint(0.5f, 0.1f), OutlinePoint(1f, 0.6f))
		val glyphs = if (snow) {
			listOf(SceneryGlyph(listOf(OutlinePoint(0.25f, 0.4f), OutlinePoint(0.75f, 0.4f), OutlinePoint(0.75f, 0.55f), OutlinePoint(0.25f, 0.55f)), SceneryMaterial.SNOW))
		} else {
			emptyList()
		}

		val outlines = SceneryOutlines(listOf(SceneryLayer(ridge, SceneryMaterial.ROCK, SceneryPlane.FAR)), glyphs = glyphs)
		val left = MountainSurfacePatch(listOf(ridge[0], ridge[1], OutlinePoint(0.5f, 1f), OutlinePoint(0f, 1f)), -0.8f, 0.36f, 0.48f)
		val right = MountainSurfacePatch(listOf(ridge[1], ridge[2], OutlinePoint(1f, 1f), OutlinePoint(0.5f, 1f)), 0.8f, 0.36f, 0.48f)
		val renderer = MountainSurfaceRenderer(outlines, listOf(MountainSurface(0, listOf(left, right))), 200, 200)
		renderer.updateLighting(SceneryLighting().apply { update(200, 200, clear) })

		return renderer
	}

	private fun prepared(outlines: SceneryOutlines, width: Int, height: Int) = MountainSurfaceRenderer(outlines, mountainSurfacesFor(outlines, width.toFloat() / height), width, height).apply {
		updateLighting(SceneryLighting().apply { update(width, height, clear) })
	}

	private fun drawPlanes(renderer: MountainSurfaceRenderer, canvas: Canvas) {
		renderer.drawPlane(canvas, SceneryPlane.FAR)
		renderer.drawPlane(canvas, SceneryPlane.NEAR)
	}

	private fun scene(renderer: SceneRenderer, width: Int, height: Int, params: SceneParams) = software(width, height) { renderer.render(it, width, height, params, 17f) }

	private fun software(width: Int, height: Int, draw: (Canvas) -> Unit) = createBitmap(width, height).also { draw(Canvas(it)) }

	private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }

	private fun luminance(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Float {
		var total = 0f
		for (y in top until bottom) {
			for (x in left until right) {
				val color = bitmap.getPixel(x, y)
				total += Color.red(color) * 0.2126f + Color.green(color) * 0.7152f + Color.blue(color) * 0.0722f
			}
		}

		return total / ((right - left) * (bottom - top))
	}

	@RequiresApi(29)
	private fun hardware(width: Int, height: Int, draw: (Canvas) -> Unit): Bitmap {
		val thread = HandlerThread("mountain-test-readback").apply { start() }
		val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
		val node = RenderNode("mountain-test")
		node.setPosition(0, 0, width, height)
		val renderer = HardwareRenderer()
		renderer.setOpaque(false)
		renderer.setSurface(reader.surface)
		renderer.setContentRoot(node)
		val ready = CountDownLatch(1)
		reader.setOnImageAvailableListener({ ready.countDown() }, Handler(thread.looper))

		try {
			val canvas = node.beginRecording()
			draw(canvas)
			node.endRecording()
			val result = renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
			assertTrue("Hardware readback timed out; sync=$result", ready.await(10, TimeUnit.SECONDS))

			return checkNotNull(reader.acquireNextImage()).use { image ->
				checkNotNull(image.hardwareBuffer).use { buffer ->
					val wrapped = checkNotNull(Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB)))

					try {
						checkNotNull(wrapped.copy(Bitmap.Config.ARGB_8888, false))
					} finally {
						wrapped.recycle()
					}
				}
			}
		} finally {
			renderer.destroy()
			node.discardDisplayList()
			reader.close()
			thread.quitSafely()
		}
	}
}
