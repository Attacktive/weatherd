package xyz.attacktive.weatherd.domain.render

import kotlin.random.Random
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.LightingColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.createBitmap

internal const val CLOUD_SPRITE_COUNT = 4
internal const val CLOUD_SPRITE_ASPECT = 0.5f

internal data class CloudSpritePlacement(
	val spriteIndex: Int,
	val centerX: Float,
	val baseline: Float,
	val width: Float,
	val flip: Boolean
)

internal fun cloudSpritePlacements(
	seed: Long,
	width: Float,
	height: Float,
	count: Int,
	minWidthFraction: Float = 0.24f,
	maxWidthFraction: Float = 0.45f,
	minBaselineFraction: Float = 0.18f,
	maxBaselineFraction: Float = 0.8f
): List<CloudSpritePlacement> {
	if (count <= 0) {
		return emptyList()
	}

	val random = Random(seed)
	val safeWidth = width.coerceAtLeast(1f)
	val safeHeight = height.coerceAtLeast(1f)
	return List(count) {
		CloudSpritePlacement(
			spriteIndex = random.nextInt(CLOUD_SPRITE_COUNT),
			centerX = random.nextFloat(safeWidth),
			baseline = safeHeight * random.nextFloat(minBaselineFraction, maxBaselineFraction),
			width = (safeWidth * random.nextFloat(minWidthFraction, maxWidthFraction)).coerceAtLeast(1f),
			flip = random.nextBoolean()
		)
	}
}

internal class CloudSpriteAtlas {
	private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
	private val destination = RectF()
	private val sprites = Array(CLOUD_SPRITE_COUNT) { buildSprite(it) }

	fun draw(
		canvas: Canvas,
		placement: CloudSpritePlacement,
		tint: Int,
		alpha: Int,
		centerX: Float = placement.centerX,
		baseline: Float = placement.baseline,
		width: Float = placement.width,
		crest: Boolean = false
	) {
		val drawWidth = width.coerceAtLeast(1f)
		val drawHeight = drawWidth * CLOUD_SPRITE_ASPECT
		val top = baseline - drawHeight
		val left = centerX - drawWidth * 0.5f
		val right = centerX + drawWidth * 0.5f
		val save = canvas.save()

		if (crest) {
			canvas.clipRect(left, top, right, top + drawHeight * CLOUD_CREST_FRACTION)
		}

		paint.alpha = alpha.coerceIn(0, 255)
		paint.colorFilter = LightingColorFilter(tint, Color.BLACK)
		if (placement.flip) {
			canvas.scale(-1f, 1f, centerX, 0f)
		}
		destination.set(left, top, right, baseline)
		canvas.drawBitmap(sprites[placement.spriteIndex], null, destination, paint)

		paint.colorFilter = null
		paint.alpha = 255
		canvas.restoreToCount(save)
	}

	private fun buildSprite(variant: Int) = createBitmap(CLOUD_SPRITE_WIDTH, CLOUD_SPRITE_HEIGHT).also { bitmap ->
		val canvas = Canvas(bitmap)
		canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
		val random = Random(CLOUD_SPRITE_SEED + variant)
		val width = CLOUD_SPRITE_WIDTH.toFloat()
		val height = CLOUD_SPRITE_HEIGHT.toFloat()

		val billows = 14 + variant
		repeat(billows) {
			val centerX = width * random.nextFloat(0.15f, 0.85f)
			val centerY = height * random.nextFloat(0.34f, 0.58f)
			val radiusX = width * random.nextFloat(0.16f, 0.27f)
			val radiusY = height * random.nextFloat(0.2f, 0.32f)
			val billowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
			billowPaint.shader = RadialGradient(
				centerX,
				centerY - radiusY * 0.2f,
				radiusX,
				intArrayOf(
					Color.argb(125, 255, 255, 255),
					Color.argb(155, 220, 220, 220),
					Color.argb(90, 145, 145, 145),
					Color.argb(0, 90, 90, 90)
				),
				floatArrayOf(0f, 0.38f, 0.76f, 1f),
				Shader.TileMode.CLAMP
			)
			canvas.drawOval(
				RectF(centerX - radiusX, centerY - radiusY, centerX + radiusX, centerY + radiusY),
				billowPaint
			)
		}
		val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
		repeat(4) {
			val centerX = width * random.nextFloat(0.2f, 0.8f)
			val centerY = height * random.nextFloat(0.55f, 0.72f)
			val radiusX = width * random.nextFloat(0.1f, 0.18f)
			val radiusY = height * random.nextFloat(0.1f, 0.18f)
			shadowPaint.shader = RadialGradient(
				centerX,
				centerY,
				radiusX,
				intArrayOf(Color.argb(35, 70, 70, 70), Color.argb(0, 70, 70, 70)),
				floatArrayOf(0f, 1f),
				Shader.TileMode.CLAMP
			)
			canvas.drawOval(
				RectF(centerX - radiusX, centerY - radiusY, centerX + radiusX, centerY + radiusY),
				shadowPaint
			)
		}

		val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
		edgePaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
		edgePaint.shader = LinearGradient(
			0f,
			0f,
			width,
			0f,
			intArrayOf(Color.TRANSPARENT, Color.BLACK, Color.BLACK, Color.TRANSPARENT),
			floatArrayOf(0f, 0.14f, 0.86f, 1f),
			Shader.TileMode.CLAMP
		)
		canvas.drawRect(0f, 0f, width, height, edgePaint)
		edgePaint.shader = LinearGradient(
			0f,
			0f,
			0f,
			height,
			intArrayOf(Color.TRANSPARENT, Color.BLACK, Color.BLACK, Color.TRANSPARENT),
			floatArrayOf(0f, 0.16f, 0.86f, 1f),
			Shader.TileMode.CLAMP
		)
		canvas.drawRect(0f, 0f, width, height, edgePaint)
		edgePaint.xfermode = null
		edgePaint.shader = null

	}

	private companion object {
		private const val CLOUD_SPRITE_WIDTH = 256
		private const val CLOUD_SPRITE_HEIGHT = 128
		private const val CLOUD_CREST_FRACTION = 0.62f
		private const val CLOUD_SPRITE_SEED = 101L
	}
}
