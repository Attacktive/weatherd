package xyz.attacktive.weatherd.domain.render

import kotlin.math.min
import kotlin.random.Random
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.annotation.DrawableRes
import androidx.core.graphics.withScale
import xyz.attacktive.weatherd.R

/** How many viewport widths a texture covers before repeating, unless a deck asks for its own span. */
internal const val CLOUD_TEXTURE_VIEWPORTS = 4f

/**
 * Reuses decoded cloud pixels, sampling transforms, tint state, and seeded sprite layouts between frames.
 *
 * Fair-weather cumulus uses transparent sprites directly.
 * The far fair-weather plane uses dedicated flat veil assets, while overcast sheets keep their repeating textures and borrow several small, subdued hero fragments to break up the ceiling without leaving a single recognizable fair-weather cloud hanging in the scene.
 * Fog draws over those fragments.
 */
internal class CloudLayer(resources: Resources, @DrawableRes texture: Int) {
	private val cumulusKind = when (texture) {
		R.drawable.cloud_cumulus_far -> CumulusKind.FAR
		R.drawable.cloud_cumulus_sparse -> CumulusKind.SPARSE
		R.drawable.cloud_cumulus_scattered -> CumulusKind.SCATTERED
		R.drawable.cloud_cumulus_broken -> CumulusKind.BROKEN
		else -> null
	}

	private val sheetKind = when (texture) {
		R.drawable.cloud_sheet_far -> SheetKind.FAR
		R.drawable.cloud_sheet_near -> SheetKind.NEAR
		else -> null
	}

	private val bitmap = if (cumulusKind == null) {
		decode(resources, texture)
	} else {
		null
	}

	private val cumulusBitmaps = when (cumulusKind) {
		null -> emptyList()
		CumulusKind.FAR -> farBitmaps(resources)
		else -> heroBitmaps(resources)
	}

	private val sheetHeroBitmaps = if (sheetKind != null) {
		heroBitmaps(resources)
	} else {
		emptyList()
	}

	private val cloudShader = bitmap?.let {
		BitmapShader(it, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP)
	}

	private val transform = Matrix()
	private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
		shader = cloudShader
	}

	private val spriteDest = RectF()

	private var previousMultiply = Color.WHITE
	private var cachedNearEpochDay = Long.MIN_VALUE
	private var cachedFarEpochDay = Long.MIN_VALUE
	private var cachedNearPlacements: List<CumulusPlacement> = emptyList()
	private var cachedFarPlacements: List<CumulusPlacement> = emptyList()

	fun draw(canvas: Canvas, width: Float, height: Float, offset: Float, tint: Int, alpha: Int, top: Float = 0f, viewports: Float = CLOUD_TEXTURE_VIEWPORTS) {
		if (width <= 0f || height <= 0f || alpha <= 0) {
			return
		}

		val kind = cumulusKind
		if (kind != null) {
			drawCumulus(canvas, width, height, offset, tint, alpha, top, viewports, kind)
			return
		}

		val source = checkNotNull(bitmap)
		val shader = checkNotNull(cloudShader)

		transform.setScale(width * viewports / source.width, height / source.height)
		transform.postTranslate(offset, top)
		shader.setLocalMatrix(transform)
		paint.shader = shader
		updateColorFilter(tint)
		paint.alpha = alpha.coerceIn(0, 255)
		canvas.drawRect(0f, top, width, top + height, paint)

		sheetKind?.let {
			drawSheetCloudMasses(canvas, width, height, offset, tint, alpha, top, it)
		}
	}

	private fun drawCumulus(
		canvas: Canvas,
		width: Float,
		height: Float,
		offset: Float,
		tint: Int,
		alpha: Int,
		top: Float,
		viewports: Float,
		kind: CumulusKind
	) {
		val far = kind == CumulusKind.FAR
		val drawTint = if (far) {
			liftTowardWhite(tint, FAR_CUMULUS_TINT_LIFT)
		} else {
			tint
		}

		updateColorFilter(drawTint)
		paint.shader = null

		val placements = if (far) {
			farPlacementsFor()
		} else {
			nearPlacementsFor(kind)
		}

		val period = width * viewports
		if (period <= 0f || cumulusBitmaps.isEmpty()) {
			return
		}

		val baseHeight = if (far) {
			min(width * FAR_BASE_HEIGHT_TO_WIDTH, height * FAR_BASE_HEIGHT_TO_DECK)
		} else {
			min(width * 0.22f, height * 0.48f)
		}

		val alphaScale = if (far) {
			FAR_CUMULUS_ALPHA_SCALE
		} else {
			HERO_CUMULUS_ALPHA_SCALE
		}

		val topOffset = if (far) {
			height * FAR_CUMULUS_RISE
		} else {
			0f
		}

		val compositionAlpha = nearCompositionAlpha(kind, alpha)

		for (placement in placements) {
			val sprite = cumulusBitmaps[placement.spriteIndex % cumulusBitmaps.size]
			val veil = far && placement.spriteIndex >= FAR_VEIL_FIRST_INDEX
			val variantHeightScale = if (veil) FAR_VEIL_HEIGHT_SCALE else 1f
			val variantWidthScale = if (veil) FAR_VEIL_WIDTH_SCALE else 1f
			val variantAlphaScale = if (veil) FAR_VEIL_ALPHA_SCALE else 1f
			val spriteHeight = baseHeight * placement.scale * variantHeightScale
			val spriteWidth = spriteHeight * sprite.width.toFloat() / sprite.height.toFloat() * variantWidthScale
			val centerY = top - topOffset + height * placement.yFraction
			val centerX = positiveModulo(offset + period * placement.xFraction, period)
			val spriteAlpha = (compositionAlpha * alphaScale * variantAlphaScale * placement.alphaScale).toInt().coerceIn(0, 255)
			if (spriteAlpha <= 0) {
				continue
			}

			paint.alpha = spriteAlpha
			for (shift in -1..1) {
				val wrappedX = centerX + shift * period
				if (wrappedX + spriteWidth * 0.5f < 0f || wrappedX - spriteWidth * 0.5f > width) {
					continue
				}

				drawSprite(canvas, sprite, placement, wrappedX, centerY, spriteWidth, spriteHeight)
			}
		}
	}

	/**
	 * Adds small broad fragments inside a heavy sheet.
	 * They stay high, overlap the sheet texture, and remain faint enough that the eye reads one overcast deck rather than an isolated fair-weather sprite.
	 * Fog is drawn later and mutes them.
	 */
	private fun drawSheetCloudMasses(canvas: Canvas, width: Float, height: Float, offset: Float, tint: Int, alpha: Int, top: Float, kind: SheetKind) {
		if (sheetHeroBitmaps.isEmpty()) {
			return
		}

		val anchors = if (kind == SheetKind.FAR) {
			SHEET_FAR_MASSES
		} else {
			SHEET_NEAR_MASSES
		}

		val driftScale = if (kind == SheetKind.FAR) SHEET_FAR_MASS_DRIFT else SHEET_NEAR_MASS_DRIFT
		val drift = offset * driftScale

		updateColorFilter(liftTowardWhite(tint, SHEET_MASS_TINT_LIFT))
		paint.shader = null

		for (anchor in anchors) {
			val sprite = sheetHeroBitmaps[anchor.spriteIndex % sheetHeroBitmaps.size]
			val targetWidth = width * anchor.widthFraction
			val targetHeight = targetWidth * sprite.height.toFloat() / sprite.width.toFloat()
			val centerX = positiveModulo(width * anchor.xFraction + drift, width)
			val centerY = top + height * anchor.yFraction
			val spriteAlpha = (alpha * anchor.alphaScale).toInt().coerceIn(0, 255)
			if (spriteAlpha <= 0) {
				continue
			}

			val placement = CumulusPlacement(
				spriteIndex = anchor.spriteIndex,
				xFraction = 0f,
				yFraction = 0f,
				scale = 1f,
				mirror = anchor.mirror,
				alphaScale = 1f
			)

			paint.alpha = spriteAlpha

			for (shift in -1..1) {
				val wrappedX = centerX + shift * width
				if (wrappedX + targetWidth * 0.5f < 0f || wrappedX - targetWidth * 0.5f > width) {
					continue
				}

				drawSprite(canvas, sprite, placement, wrappedX, centerY, targetWidth, targetHeight)
			}
		}
	}

	private fun nearPlacementsFor(kind: CumulusKind): List<CumulusPlacement> {
		val epochDay = System.currentTimeMillis() / MILLIS_PER_DAY
		if (cachedNearEpochDay == epochDay && cachedNearPlacements.isNotEmpty()) {
			return cachedNearPlacements
		}

		val anchors = when (kind) {
			CumulusKind.SPARSE -> SPARSE_ANCHORS
			CumulusKind.SCATTERED -> SCATTERED_ANCHORS
			CumulusKind.BROKEN -> BROKEN_ANCHORS
			CumulusKind.FAR -> error("Far cumulus uses its own placement cache")
		}

		val seedSalt = when (kind) {
			CumulusKind.SPARSE -> SPARSE_LAYOUT_SEED_SALT
			CumulusKind.SCATTERED -> SCATTERED_LAYOUT_SEED_SALT
			CumulusKind.BROKEN -> BROKEN_LAYOUT_SEED_SALT
		}

		cachedNearPlacements = buildPlacements(
			anchors = anchors,
			random = Random(layoutSeed(epochDay) xor seedSalt),
			xJitter = NEAR_POSITION_X_JITTER,
			yJitter = NEAR_POSITION_Y_JITTER,
			scaleJitter = NEAR_SCALE_JITTER,
			alphaJitter = NEAR_ALPHA_JITTER,
			minY = NEAR_MIN_Y_FRACTION,
			maxY = NEAR_MAX_Y_FRACTION,
			variantCount = HERO_VARIANT_COUNT
		)

		cachedNearEpochDay = epochDay
		return cachedNearPlacements
	}

	private fun farPlacementsFor(): List<CumulusPlacement> {
		val epochDay = System.currentTimeMillis() / MILLIS_PER_DAY
		if (cachedFarEpochDay == epochDay && cachedFarPlacements.isNotEmpty()) {
			return cachedFarPlacements
		}

		cachedFarPlacements = buildPlacements(
			anchors = FAR_ANCHORS,
			random = Random(layoutSeed(epochDay) xor FAR_LAYOUT_SEED_SALT),
			xJitter = FAR_POSITION_X_JITTER,
			yJitter = FAR_POSITION_Y_JITTER,
			scaleJitter = FAR_SCALE_JITTER,
			alphaJitter = FAR_ALPHA_JITTER,
			minY = FAR_MIN_Y_FRACTION,
			maxY = FAR_MAX_Y_FRACTION,
			variantCount = FAR_VARIANT_COUNT
		)
		.mapIndexed { index, placement ->
			when (index) {
				0 -> placement.copy(spriteIndex = 0)
				1 -> placement.copy(spriteIndex = 1)
				else -> placement
			}
		}

		cachedFarEpochDay = epochDay
		return cachedFarPlacements
	}

	private fun buildPlacements(anchors: List<CumulusAnchor>, random: Random, xJitter: Float, yJitter: Float, scaleJitter: Float, alphaJitter: Float, minY: Float, maxY: Float, variantCount: Int): List<CumulusPlacement> = anchors.map { base ->
		val xDelta = random.nextFloat() * xJitter * 2f - xJitter
		val yDelta = random.nextFloat() * yJitter * 2f - yJitter
		val scaleDelta = 1f + random.nextFloat() * scaleJitter * 2f - scaleJitter
		val alphaDelta = 1f - random.nextFloat() * alphaJitter

		CumulusPlacement(
			spriteIndex = random.nextInt(variantCount),
			xFraction = wrapFraction(base.xFraction + xDelta),
			yFraction = (base.yFraction + yDelta).coerceIn(minY, maxY),
			scale = base.scale * scaleDelta,
			mirror = random.nextBoolean(),
			alphaScale = base.alphaScale * alphaDelta
		)
	}

	private fun nearCompositionAlpha(kind: CumulusKind, alpha: Int): Int {
		if (kind == CumulusKind.FAR || kind == CumulusKind.SPARSE) {
			return alpha
		}

		val floor = (255f * NEAR_COMPOSITION_BLEND_START).toInt()
		if (alpha <= floor) {
			return 0
		}

		return ((alpha - floor) / (1f - NEAR_COMPOSITION_BLEND_START)).toInt().coerceIn(0, 255)
	}

	private fun drawSprite(canvas: Canvas, sprite: Bitmap, placement: CumulusPlacement, centerX: Float, centerY: Float, width: Float, height: Float) {
		val left = centerX - width * 0.5f
		val top = centerY - height * 0.5f
		spriteDest.set(left, top, left + width, top + height)

		if (!placement.mirror) {
			canvas.drawBitmap(sprite, null, spriteDest, paint)
			return
		}

		canvas.withScale(-1f, 1f, centerX, centerY) {
			canvas.drawBitmap(sprite, null, spriteDest, paint)
		}
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

	private enum class CumulusKind {
		FAR,
		SPARSE,
		SCATTERED,
		BROKEN
	}

	private enum class SheetKind {
		FAR,
		NEAR
	}

	private companion object {
		private const val MILLIS_PER_DAY = 86_400_000L

		private const val NEAR_POSITION_X_JITTER = 0.035f
		private const val NEAR_POSITION_Y_JITTER = 0.045f
		private const val NEAR_SCALE_JITTER = 0.10f
		private const val NEAR_ALPHA_JITTER = 0.06f
		private const val NEAR_MIN_Y_FRACTION = 0.16f
		private const val NEAR_MAX_Y_FRACTION = 0.64f
		private const val NEAR_COMPOSITION_BLEND_START = 0.50f
		private const val SPARSE_LAYOUT_SEED_SALT = 0x21A7F1
		private const val SCATTERED_LAYOUT_SEED_SALT = 0x53C4D2
		private const val BROKEN_LAYOUT_SEED_SALT = 0x7B19E5

		private const val FAR_POSITION_X_JITTER = 0.022f
		private const val FAR_POSITION_Y_JITTER = 0.040f
		private const val FAR_SCALE_JITTER = 0.10f
		private const val FAR_ALPHA_JITTER = 0.06f
		private const val FAR_MIN_Y_FRACTION = 0.20f
		private const val FAR_MAX_Y_FRACTION = 0.66f
		private const val FAR_LAYOUT_SEED_SALT = 0x46A2D9

		private const val HERO_VARIANT_COUNT = 2
		private const val FAR_VARIANT_COUNT = 2
		private const val FAR_VEIL_FIRST_INDEX = 0
		private const val HERO_CUMULUS_ALPHA_SCALE = 0.92f
		private const val FAR_CUMULUS_ALPHA_SCALE = 1.18f
		private const val FAR_VEIL_ALPHA_SCALE = 1.12f
		private const val FAR_VEIL_HEIGHT_SCALE = 0.52f
		private const val FAR_VEIL_WIDTH_SCALE = 1.72f
		private const val FAR_CUMULUS_TINT_LIFT = 0.16f
		private const val FAR_CUMULUS_RISE = 0.12f
		private const val FAR_BASE_HEIGHT_TO_WIDTH = 0.082f
		private const val FAR_BASE_HEIGHT_TO_DECK = 0.24f

		private const val SHEET_FAR_MASS_DRIFT = 0.035f
		private const val SHEET_NEAR_MASS_DRIFT = 0.055f
		private const val SHEET_MASS_TINT_LIFT = 0.01f

		private val SPARSE_ANCHORS = listOf(
			CumulusAnchor(0.18f, 0.34f, 1.00f),
			CumulusAnchor(0.72f, 0.45f, 0.82f, alphaScale = 0.96f)
		)

		private val SCATTERED_ANCHORS = listOf(
			CumulusAnchor(0.05f, 0.31f, 0.78f, alphaScale = 0.94f),
			CumulusAnchor(0.27f, 0.50f, 0.92f),
			CumulusAnchor(0.50f, 0.24f, 0.76f, alphaScale = 0.96f),
			CumulusAnchor(0.73f, 0.55f, 0.86f, alphaScale = 0.94f),
			CumulusAnchor(0.94f, 0.37f, 0.72f, alphaScale = 0.92f)
		)

		private val BROKEN_ANCHORS = listOf(
			CumulusAnchor(0.04f, 0.43f, 0.92f),
			CumulusAnchor(0.16f, 0.24f, 0.74f, alphaScale = 0.96f),
			CumulusAnchor(0.29f, 0.57f, 0.82f),
			CumulusAnchor(0.41f, 0.34f, 0.96f),
			CumulusAnchor(0.54f, 0.20f, 0.70f, alphaScale = 0.94f),
			CumulusAnchor(0.66f, 0.52f, 0.86f),
			CumulusAnchor(0.78f, 0.30f, 0.76f, alphaScale = 0.94f),
			CumulusAnchor(0.89f, 0.60f, 0.72f, alphaScale = 0.90f),
			CumulusAnchor(0.98f, 0.41f, 0.80f, alphaScale = 0.92f)
		)

		private val FAR_ANCHORS = listOf(
			CumulusAnchor(0.06f, 0.38f, 0.92f, alphaScale = 0.94f),
			CumulusAnchor(0.23f, 0.56f, 0.78f, alphaScale = 0.88f),
			CumulusAnchor(0.42f, 0.30f, 0.84f, alphaScale = 0.92f),
			CumulusAnchor(0.61f, 0.61f, 0.74f, alphaScale = 0.86f),
			CumulusAnchor(0.79f, 0.43f, 0.90f, alphaScale = 0.94f),
			CumulusAnchor(0.95f, 0.27f, 0.70f, alphaScale = 0.84f)
		)

		private val SHEET_FAR_MASSES = listOf(
			SheetCloudMass(0.08f, 0.18f, 0.32f, 0.28f, 0, false),
			SheetCloudMass(0.50f, 0.30f, 0.28f, 0.24f, 1, true),
			SheetCloudMass(0.86f, 0.20f, 0.24f, 0.22f, 0, true)
		)

		private val SHEET_NEAR_MASSES = listOf(
			SheetCloudMass(0.30f, 0.24f, 0.36f, 0.26f, 1, false),
			SheetCloudMass(0.82f, 0.34f, 0.30f, 0.22f, 0, true)
		)

		private var sharedHeroBitmaps: List<Bitmap>? = null
		private var sharedFarBitmaps: List<Bitmap>? = null

		private fun heroBitmaps(resources: Resources): List<Bitmap> {
			sharedHeroBitmaps?.let {
				return it
			}

			return listOf(
				decode(resources, R.drawable.cloud_cumulus_hero_broad),
				decode(resources, R.drawable.cloud_cumulus_hero_broad_alt)
			).also {
				sharedHeroBitmaps = it
			}
		}

		private fun farBitmaps(resources: Resources): List<Bitmap> {
			sharedFarBitmaps?.let {
				return it
			}

			return listOf(
				decode(resources, R.drawable.cloud_cumulus_far_veil_broad),
				decode(resources, R.drawable.cloud_cumulus_far_veil_layered)
			).also {
				sharedFarBitmaps = it
			}
		}

		private fun layoutSeed(epochDay: Long): Int {
			return (epochDay * 1_103_515_245L + 0xC10D5L).toInt()
		}

		private fun liftTowardWhite(color: Int, amount: Float): Int {
			fun lift(channel: Int) = (channel + (255 - channel) * amount).toInt().coerceIn(0, 255)

			return Color.rgb(
				lift(Color.red(color)),
				lift(Color.green(color)),
				lift(Color.blue(color))
			)
		}

		private fun wrapFraction(value: Float): Float {
			return when {
				value < 0f -> value + 1f
				value >= 1f -> value - 1f
				else -> value
			}
		}

		private fun decode(resources: Resources, @DrawableRes texture: Int) = checkNotNull(BitmapFactory.decodeResource(resources, texture, BitmapFactory.Options().apply { inScaled = false }))

		private fun positiveModulo(value: Float, modulo: Float): Float {
			val result = value % modulo
			return if (result < 0f) {
				result + modulo
			} else {
				result
			}
		}
	}
}

private data class CumulusAnchor(val xFraction: Float, val yFraction: Float, val scale: Float, val alphaScale: Float = 1f)

private data class CumulusPlacement(val spriteIndex: Int, val xFraction: Float, val yFraction: Float, val scale: Float, val mirror: Boolean, val alphaScale: Float)

private data class SheetCloudMass(val xFraction: Float, val yFraction: Float, val widthFraction: Float, val alphaScale: Float, val spriteIndex: Int, val mirror: Boolean)
