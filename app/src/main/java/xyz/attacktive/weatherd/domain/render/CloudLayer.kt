package xyz.attacktive.weatherd.domain.render

import kotlin.math.roundToInt
import android.content.res.Resources
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import androidx.annotation.DrawableRes
import xyz.attacktive.weatherd.R

/** How many viewport widths a texture covers before repeating, unless a deck asks for its own span. */
internal const val CLOUD_TEXTURE_VIEWPORTS = 4f

/** Reuses decoded cloud pixels, a sampling transform, and tint state between frames. */
internal class CloudLayer(resources: Resources, @DrawableRes texture: Int) {
	private val bitmap = checkNotNull(BitmapFactory.decodeResource(resources, texture, BitmapFactory.Options().apply { inScaled = false }))
	private val cloudShader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP)
	private val transform = Matrix()
	private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
		shader = cloudShader
	}
	private val cumulusDepth = when (texture) {
		R.drawable.cloud_cumulus_far -> CumulusDepth.FAR
		R.drawable.cloud_cumulus_sparse,
		R.drawable.cloud_cumulus_scattered,
		R.drawable.cloud_cumulus_broken -> CumulusDepth.NEAR
		else -> null
	}

	private var previousMultiply = Color.WHITE

	fun draw(
		canvas: Canvas,
		width: Float,
		height: Float,
		offset: Float,
		tint: Int,
		alpha: Int,
		top: Float = 0f,
		viewports: Float = CLOUD_TEXTURE_VIEWPORTS
	) {
		if (width <= 0f || height <= 0f || alpha <= 0) {
			return
		}

		/*
		 * Fair-weather cumulus gets a small depth-only geometry correction here instead of baking another texture family.
		 * The near deck grows upward enough that a drifting mass can naturally cross the sun and reads taller rather than shelf-like.
		 * The far deck is compressed and dropped toward the horizon, with a stronger atmospheric fade so mostly-clear skies keep their open blue.
		 * Horizontal scale stays entirely under the caller's `viewports` contract, so scroll periods and wrap seams remain exact.
		 */
		val drawHeight = when (cumulusDepth) {
			CumulusDepth.NEAR -> height * CUMULUS_NEAR_HEIGHT_SCALE
			CumulusDepth.FAR -> height * CUMULUS_FAR_HEIGHT_SCALE
			null -> height
		}
		val drawTop = when (cumulusDepth) {
			CumulusDepth.NEAR -> top - height * CUMULUS_NEAR_RISE
			CumulusDepth.FAR -> top + height * CUMULUS_FAR_DROP
			null -> top
		}
		val drawAlpha = when (cumulusDepth) {
			CumulusDepth.FAR -> ((alpha - CUMULUS_FAR_ALPHA_FLOOR).coerceAtLeast(0) * CUMULUS_FAR_ALPHA_SCALE).roundToInt()
			else -> alpha
		}.coerceIn(0, 255)

		if (drawAlpha <= 0) {
			return
		}

		/*
		 * Fewer viewports shrink the sampled features, which is the only handle a deck has on apparent cloud size: the shader always stretches the texture across `width * viewports`.
		 * A far deck asks for a smaller span so its clouds read as smaller and further away while sharing the texture's pixels.
		 */
		transform.setScale(width * viewports / bitmap.width, drawHeight / bitmap.height)
		transform.postTranslate(offset, drawTop)
		cloudShader.setLocalMatrix(transform)
		updateColorFilter(tint)
		paint.alpha = drawAlpha
		canvas.drawRect(0f, drawTop, width, drawTop + drawHeight, paint)
	}

	private fun updateColorFilter(multiplyColor: Int) {
		if (multiplyColor != previousMultiply) {
			paint.colorFilter = if (multiplyColor == Color.WHITE) {
				null
			} else {
				LightingColorFilter(multiplyColor, Color.BLACK)
			}

			previousMultiply = multiplyColor
		}
	}

	private enum class CumulusDepth {
		NEAR,
		FAR
	}

	private companion object {
		private const val CUMULUS_NEAR_HEIGHT_SCALE = 1.12f
		private const val CUMULUS_NEAR_RISE = 0.12f
		private const val CUMULUS_FAR_HEIGHT_SCALE = 0.85f
		private const val CUMULUS_FAR_DROP = 0.18f
		private const val CUMULUS_FAR_ALPHA_FLOOR = 35
		private const val CUMULUS_FAR_ALPHA_SCALE = 0.75f
	}
}
