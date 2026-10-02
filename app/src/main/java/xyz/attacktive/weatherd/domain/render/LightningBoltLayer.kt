package xyz.attacktive.weatherd.domain.render

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF

/** A seeded branching discharge with tapered cores and a software-rasterized bloom, rebuilt only for a new strike or surface. */
internal class LightningBoltLayer {
	private val channels = arrayOf(Channel(7), Channel(5), Channel(5), Channel(5), Channel(4), Channel(4))
	private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.rgb(252, 253, 255)
	}

	private val bloomPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
		xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
	}

	private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val haloPath = Path()
	private val destination = RectF()
	private var bloom: Bitmap? = null
	private var cachedSeed = Long.MIN_VALUE
	private var cachedWidth = 0f
	private var cachedHeight = 0f

	fun draw(canvas: Canvas, width: Float, height: Float, seed: Long, flash: Float) {
		if (width <= 0f || height <= 0f || flash <= 0f) {
			return
		}

		if (seed != cachedSeed || width != cachedWidth || height != cachedHeight) {
			buildChannels(width, height, seed)
			bloom = buildBloom(width, height)
			destination.set(0f, 0f, width, height)
			cachedSeed = seed
			cachedWidth = width
			cachedHeight = height
		}

		val alpha = (255f * flash).roundToInt().coerceIn(0, 255)
		bloomPaint.alpha = alpha
		canvas.drawBitmap(checkNotNull(bloom), null, destination, bloomPaint)
		corePaint.alpha = alpha
		for (channel in channels) {
			canvas.drawPath(channel.core, corePaint)
		}
	}

	private fun buildChannels(width: Float, height: Float, seed: Long) {
		val random = Random(seed)
		val span = minOf(width, height)
		val main = channels[0]
		val startX = width * random.nextFloat(0.32f, 0.68f)
		main.setEnds(startX, -height * 0.04f, startX + span * random.nextFloat(-0.2f, 0.2f), height * random.nextFloat(0.76f, 0.92f))
		buildChannel(main, span * 0.22f, span * 0.003f, true, random)

		for (branch in 1..3) {
			val origin = (main.segments * (0.22f + (branch - 1) * 0.2f + random.nextFloat(-0.035f, 0.035f))).roundToInt()
			val direction = if (random.nextBoolean()) { -1f } else { 1f }
			val reach = span * random.nextFloat(0.16f, 0.32f)
			val channel = channels[branch]
			channel.setEnds(main.x[origin], main.y[origin], main.x[origin] + direction * reach, main.y[origin] + reach * random.nextFloat(0.8f, 1.6f))
			buildChannel(channel, reach * 0.2f, main.radius[origin] * random.nextFloat(0.4f, 0.6f), false, random)
		}

		for (twig in 4..5) {
			val parent = channels[twig - 3]
			val origin = random.nextInt(parent.segments / 3, parent.segments * 2 / 3)
			val reach = span * random.nextFloat(0.065f, 0.12f)
			val direction = if (parent.x.last() > parent.x[0]) { -1f } else { 1f }
			val channel = channels[twig]
			channel.setEnds(parent.x[origin], parent.y[origin], parent.x[origin] + direction * reach, parent.y[origin] + reach * random.nextFloat(0.7f, 1.4f))
			buildChannel(channel, reach * 0.2f, parent.radius[origin] * 0.45f, false, random)
		}
	}

	private fun buildChannel(channel: Channel, displacement: Float, rootRadius: Float, main: Boolean, random: Random) {
		subdivide(channel, 0, channel.segments, displacement, random)
		for (index in 0..channel.segments) {
			val progress = index.toFloat() / channel.segments
			val taper = if (main) {
				(1f - 0.5f * progress) * ((1f - progress) / 0.16f).coerceAtMost(1f)
			} else {
				(1f - progress).pow(0.8f)
			}

			channel.radius[index] = rootRadius * taper * random.nextFloat(0.85f, 1.15f)
			val before = (index - 1).coerceAtLeast(0)
			val after = (index + 1).coerceAtMost(channel.segments)
			val dx = channel.x[after] - channel.x[before]
			val dy = channel.y[after] - channel.y[before]
			val length = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
			channel.normalX[index] = -dy / length * channel.radius[index]
			channel.normalY[index] = dx / length * channel.radius[index]
		}

		outline(channel, channel.core, 1f)
	}

	/** Midpoint displacement adds small kinks inside broad bends instead of walking an evenly spaced saw-tooth down the screen. */
	private fun subdivide(channel: Channel, start: Int, end: Int, displacement: Float, random: Random) {
		if (end - start <= 1) {
			return
		}

		val middle = (start + end) / 2
		val dx = channel.x[end] - channel.x[start]
		val dy = channel.y[end] - channel.y[start]
		val length = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
		val along = random.nextFloat(0.38f, 0.62f)
		val offset = random.nextFloat(-displacement, displacement)
		channel.x[middle] = channel.x[start] + dx * along - dy / length * offset
		channel.y[middle] = channel.y[start] + dy * along + dx / length * offset
		subdivide(channel, start, middle, displacement * 0.52f, random)
		subdivide(channel, middle, end, displacement * 0.52f, random)
	}

	private fun outline(channel: Channel, path: Path, widthScale: Float) {
		path.rewind()
		path.moveTo(channel.x[0] + channel.normalX[0] * widthScale, channel.y[0] + channel.normalY[0] * widthScale)
		for (index in 1..channel.segments) {
			path.lineTo(channel.x[index] + channel.normalX[index] * widthScale, channel.y[index] + channel.normalY[index] * widthScale)
		}

		for (index in channel.segments downTo 0) {
			path.lineTo(channel.x[index] - channel.normalX[index] * widthScale, channel.y[index] - channel.normalY[index] * widthScale)
		}

		path.close()
	}

	private fun buildBloom(width: Float, height: Float): Bitmap {
		val scale = minOf(1f, BLOOM_MAX_SIZE / maxOf(width, height))
		val bitmapWidth = (width * scale).roundToInt().coerceAtLeast(1)
		val bitmapHeight = (height * scale).roundToInt().coerceAtLeast(1)
		val span = minOf(width, height)

		// Software blur is baked into an immutable image so hardware-recorded preview and wallpaper canvases see the same glow.
		return renderImmutableBitmap(bitmapWidth, bitmapHeight) { canvas ->
			canvas.scale(bitmapWidth / width, bitmapHeight / height)
			drawBloomPass(canvas, span * 0.018f, 3f, Color.argb(110, 190, 213, 255))
			drawBloomPass(canvas, span * 0.004f, 1.8f, Color.argb(220, 228, 240, 255))
			haloPaint.maskFilter = null
		}
	}

	private fun drawBloomPass(canvas: Canvas, blur: Float, widthScale: Float, color: Int) {
		haloPaint.color = color
		haloPaint.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
		for (channel in channels) {
			outline(channel, haloPath, widthScale)
			canvas.drawPath(haloPath, haloPaint)
		}
	}

	private class Channel(levels: Int) {
		val segments = 1 shl levels
		val x = FloatArray(segments + 1)
		val y = FloatArray(segments + 1)
		val radius = FloatArray(segments + 1)
		val normalX = FloatArray(segments + 1)
		val normalY = FloatArray(segments + 1)
		val core = Path()

		fun setEnds(startX: Float, startY: Float, endX: Float, endY: Float) {
			x[0] = startX
			y[0] = startY
			x[segments] = endX
			y[segments] = endY
		}
	}

	private companion object {
		const val BLOOM_MAX_SIZE = 640f
	}
}
