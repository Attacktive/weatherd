package xyz.attacktive.weatherd.domain.render

import kotlin.math.roundToInt
import kotlin.random.Random
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.core.graphics.withSave

internal class FrostOnGlassLayer {
	private var previousTimeSeconds = Float.NaN
	private var growthSeconds = 0f
	private val artwork by lazy(LazyThreadSafetyMode.NONE) { FrostArtwork() }

	fun clear() {
		previousTimeSeconds = Float.NaN
		growthSeconds = 0f
	}

	fun draw(canvas: Canvas, width: Int, height: Int, timeSeconds: Float) {
		if (width <= 0 || height <= 0) {
			return
		}

		if (growthSeconds < GROWTH_SECONDS) {
			if (previousTimeSeconds.isFinite()) {
				growthSeconds = (growthSeconds + sceneAnimationElapsedSeconds(previousTimeSeconds, timeSeconds)).coerceAtMost(GROWTH_SECONDS)
			}

			previousTimeSeconds = timeSeconds
		}

		if (growthSeconds <= 0f) {
			return
		}

		val linear = growthSeconds / GROWTH_SECONDS
		val progress = linear * linear * (3f - 2f * linear)
		val shortSide = minOf(width, height).toFloat()
		val fullDepth = shortSide * MAX_DEPTH_FRACTION
		val depth = fullDepth * progress
		val w = width.toFloat()
		val h = height.toFloat()
		val horizontalTiles = (w / (fullDepth * TILE_ASPECT)).roundToInt().coerceAtLeast(1)
		val verticalTiles = (h / (fullDepth * TILE_ASPECT)).roundToInt().coerceAtLeast(1)
		val frost = artwork
		frost.drawEdge(canvas, w, depth, horizontalTiles, 0f, 0f, 0f, 0)
		frost.drawEdge(canvas, h, depth, verticalTiles, w, 0f, 90f, 1)
		frost.drawEdge(canvas, w, depth, horizontalTiles, w, h, 180f, 2)
		frost.drawEdge(canvas, h, depth, verticalTiles, 0f, h, 270f, 3)
	}

	private companion object {
		const val GROWTH_SECONDS = 10f
		const val MAX_DEPTH_FRACTION = 0.11f
	}
}

private class FrostArtwork {
	private val tiles = Array(4) { index -> frostTile(Random(855 + index)) }
	private val veil = Paint().apply {
		shader = frostGradient(Color.argb(65, 198, 226, 248), 29f)
	}
	private val crystal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeCap = Paint.Cap.ROUND
		strokeJoin = Paint.Join.ROUND
		strokeWidth = 1.45f
		shader = frostGradient(Color.argb(195, 237, 249, 255), TILE_DEPTH)
	}

	fun drawEdge(canvas: Canvas, length: Float, depth: Float, tileCount: Int, x: Float, y: Float, rotation: Float, firstTile: Int) {
		canvas.withSave {
			translate(x, y)
			rotate(rotation)
			clipRect(0f, 0f, length, depth)
			scale(length / (TILE_WIDTH * tileCount), depth / TILE_DEPTH)
			drawRect(0f, 0f, TILE_WIDTH * tileCount, TILE_DEPTH, veil)
			for (tileIndex in 0 until tileCount) {
				drawPath(tiles[(firstTile + tileIndex) % tiles.size], crystal)
				translate(TILE_WIDTH, 0f)
			}
		}
	}
}

private fun frostGradient(color: Int, fadeDepth: Float) = LinearGradient(0f, 0f, 0f, fadeDepth, color, color and 0xFFFFFF, Shader.TileMode.CLAMP)

private fun frostTile(random: Random) = Path().apply {
	for (index in 0 until 15) {
		val root = (index + random.nextFloat()) * (TILE_WIDTH / 15f)
		val length = if (index % 3 == 1) {
			52f + random.nextFloat() * 45f
		} else {
			9f + random.nextFloat() * 33f
		}

		val lean = length * (random.nextFloat() - 0.5f) * 1.15f
		moveTo(root, 0f)
		lineTo(root + lean, length)
		for (branch in 1..5) {
			val fraction = branch * 0.15f
			val x = root + lean * fraction
			val y = length * fraction
			val reach = length * (1f - fraction) * (0.23f + random.nextFloat() * 0.19f)
			for (side in -1..1 step 2) {
				val dx = reach * side + lean * 0.13f
				val dy = reach * (0.47f + random.nextFloat() * 0.3f)
				moveTo(x, y)
				lineTo(x + dx, y + dy)
				if (reach > 9f) {
					moveTo(x + dx * 0.49f, y + dy * 0.49f)
					lineTo(x + dx * 0.68f + lean * 0.07f, y + dy * 0.49f + reach * 0.29f)
				}
			}
		}
	}
}

private const val TILE_ASPECT = 2.8f
private const val TILE_DEPTH = 100f
private const val TILE_WIDTH = TILE_DEPTH * TILE_ASPECT
