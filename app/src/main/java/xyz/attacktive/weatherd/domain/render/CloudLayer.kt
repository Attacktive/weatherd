package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
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
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.scale
import androidx.core.graphics.withScale
import xyz.attacktive.weatherd.R

/** How many viewport widths a texture covers before repeating, unless a deck asks for its own span. */
internal const val CLOUD_TEXTURE_VIEWPORTS = 4f

/**
 * Reuses decoded cloud pixels, sampling transforms, tint state, and seeded sprite layouts between frames.
 *
 * Fair-weather cumulus uses transparent sprites directly.
 * The far fair-weather plane uses dedicated flat veil assets, while overcast sheets are deterministic multi-scale textures generated once when the layer is first used.
 * Fog draws over those sheets.
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

	private val bitmap = when {
		cumulusKind != null -> null
		sheetKind != null -> overcastTexture(sheetKind)
		else -> decode(resources, texture)
	}

	private val cumulusBitmaps = when (cumulusKind) {
		null -> emptyList()
		CumulusKind.FAR -> farBitmaps(resources)
		else -> heroBitmaps(resources)
	}

	private val cloudShader = bitmap?.let {
		BitmapShader(it, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP)
	}

	private val transform = Matrix()
	private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
		shader = cloudShader
	}

	private val spriteDest = RectF()
	private val geometry = CloudGeometry()
	private val opacitySampler = OpacitySampler()

	private var previousMultiply = Color.WHITE
	private var cachedNearEpochDay = Long.MIN_VALUE
	private var cachedFarEpochDay = Long.MIN_VALUE
	private var cachedNearPlacements: List<CumulusPlacement> = emptyList()
	private var cachedFarPlacements: List<CumulusPlacement> = emptyList()
	private var cachedOpacityStyleKind: CumulusKind? = null
	private var cachedOpacityStyleWidth = Float.NaN
	private var cachedOpacityStyleHeight = Float.NaN
	private var cachedOpacityStyle: CumulusStyle? = null

	fun draw(
		canvas: Canvas,
		width: Float,
		height: Float,
		offset: Float,
		tint: Int,
		alpha: Int,
		top: Float = 0f,
		viewports: Float = CLOUD_TEXTURE_VIEWPORTS,
		shadow: CumulusShadow? = null
	) {
		if (width <= 0f || height <= 0f || alpha <= 0) {
			return
		}

		geometry.width = width
		geometry.height = height
		geometry.offset = offset
		geometry.top = top
		geometry.viewports = viewports

		val kind = cumulusKind
		if (kind != null) {
			drawCumulus(canvas, geometry, tint, alpha, kind, shadow)
			return
		}

		val source = checkNotNull(bitmap)
		val shader = checkNotNull(cloudShader)

		val period = width * viewports
		val wrappedOffset = positiveModulo(offset, period)
		transform.setScale(period / source.width, height / source.height)
		transform.postTranslate(wrappedOffset, top)
		shader.setLocalMatrix(transform)
		paint.shader = shader
		updateColorFilter(tint)
		paint.alpha = alpha.coerceIn(0, 255)
		canvas.drawRect(0f, top, width, top + height, paint)
	}

	/**
	 * Prepares a reusable opacity sampler using the same sprite placement math as [drawCumulus].
	 * The expensive style and placement lookup happens once per profile; each subsequent point probe only samples primitive geometry and bitmap alpha.
	 */
	fun opacitySampler(width: Float, height: Float, offset: Float, top: Float, viewports: Float, alpha: Int): OpacitySampler? {
		val kind = cumulusKind ?: return null
		if (!canSampleOpacity(width, height, alpha)) {
			return null
		}

		val period = width * viewports
		if (period <= 0f) {
			return null
		}

		val placements = if (kind == CumulusKind.FAR) farPlacementsFor() else nearPlacementsFor(kind)
		opacitySampler.configure(
			width = width,
			height = height,
			offset = positiveModulo(offset, period),
			top = top,
			period = period,
			style = opacityStyleFor(kind, width, height),
			placements = placements,
			compositionAlpha = nearCompositionAlpha(kind, alpha)
		)

		return opacitySampler
	}

	private fun canSampleOpacity(width: Float, height: Float, alpha: Int) =
		width > 0f && height > 0f && alpha > 0 && cumulusBitmaps.isNotEmpty()

	private fun opacityStyleFor(kind: CumulusKind, width: Float, height: Float): CumulusStyle {
		val cached = cachedOpacityStyle
		if (cached != null && cachedOpacityStyleKind == kind && cachedOpacityStyleWidth == width && cachedOpacityStyleHeight == height) {
			return cached
		}

		val style = cumulusStyle(kind, Color.WHITE, width, height)
		cachedOpacityStyleKind = kind
		cachedOpacityStyleWidth = width
		cachedOpacityStyleHeight = height
		cachedOpacityStyle = style
		return style
	}

	inner class OpacitySampler {
		private var width = 0f
		private var height = 0f
		private var offset = 0f
		private var top = 0f
		private var period = 0f
		private lateinit var style: CumulusStyle
		private var placements: List<CumulusPlacement> = emptyList()
		private var compositionAlpha = 0
		private var sampleX = 0f
		private var sampleY = 0f

		fun configure(
			width: Float,
			height: Float,
			offset: Float,
			top: Float,
			period: Float,
			style: CumulusStyle,
			placements: List<CumulusPlacement>,
			compositionAlpha: Int
		) {
			this.width = width
			this.height = height
			this.offset = offset
			this.top = top
			this.period = period
			this.style = style
			this.placements = placements
			this.compositionAlpha = compositionAlpha
		}

		fun opacityAt(x: Float, y: Float): Float {
			sampleX = x
			sampleY = y
			var opacity = 0f
			for (placement in placements) {
				val sourceOpacity = placementOpacityAt(placement)
				opacity = sourceOpacity + opacity * (1f - sourceOpacity)
			}

			return opacity.coerceIn(0f, 1f)
		}

		private fun placementOpacityAt(placement: CumulusPlacement): Float {
			val sprite = cumulusBitmaps[placement.spriteIndex % cumulusBitmaps.size]
			val spriteHeight = style.baseHeight * placement.scale * style.scale.height
			val spriteWidth = spriteHeight * sprite.width.toFloat() / sprite.height.toFloat() * style.scale.width
			val centerY = top - style.topOffset + height * placement.yFraction
			val centerX = positiveModulo(offset + period * placement.xFraction, period)
			val spriteAlpha = (compositionAlpha * style.scale.alpha * placement.alphaScale).toInt().coerceIn(0, 255)
			if (spriteAlpha <= 0) {
				return 0f
			}

			var opacity = 0f
			for (shift in -1..1) {
				val sourceOpacity = wrappedSpriteOpacity(sprite, placement, centerX + shift * period, centerY, spriteWidth, spriteHeight, spriteAlpha)
				opacity = sourceOpacity + opacity * (1f - sourceOpacity)
			}

			return opacity
		}

		private fun wrappedSpriteOpacity(
			sprite: Bitmap,
			placement: CumulusPlacement,
			wrappedX: Float,
			centerY: Float,
			spriteWidth: Float,
			spriteHeight: Float,
			spriteAlpha: Int
		): Float {
			val left = wrappedX - spriteWidth * 0.5f
			val topEdge = centerY - spriteHeight * 0.5f
			if (sampleX < left || sampleX > left + spriteWidth || sampleY < topEdge || sampleY > topEdge + spriteHeight) {
				return 0f
			}

			var u = ((sampleX - left) / spriteWidth).coerceIn(0f, 1f)
			if (placement.mirror) {
				u = 1f - u
			}

			val v = ((sampleY - topEdge) / spriteHeight).coerceIn(0f, 1f)
			val pixelX = (u * (sprite.width - 1)).roundToInt().coerceIn(0, sprite.width - 1)
			val pixelY = (v * (sprite.height - 1)).roundToInt().coerceIn(0, sprite.height - 1)
			val sourceAlpha = Color.alpha(sprite[pixelX, pixelY]) / 255f
			return sourceAlpha * (spriteAlpha / 255f)
		}
	}

	class CumulusShadow(
		private val lower: OpacitySampler?,
		private val upper: OpacitySampler?,
		private val sourceOffsetX: Float,
		private val sourceOffsetY: Float,
		val strength: Float
	) {
		fun opacityAt(x: Float, y: Float): Float {
			val sourceX = x + sourceOffsetX
			val sourceY = y + sourceOffsetY
			val lowerOpacity = lower?.opacityAt(sourceX, sourceY) ?: 0f
			val upperOpacity = upper?.opacityAt(sourceX, sourceY) ?: 0f
			return lowerOpacity + upperOpacity * (1f - lowerOpacity)
		}
	}

	private fun drawCumulus(canvas: Canvas, geometry: CloudGeometry, tint: Int, alpha: Int, kind: CumulusKind, shadow: CumulusShadow?) {
		val style = cumulusStyle(kind, tint, geometry.width, geometry.height)
		paint.shader = null

		val placements = if (kind == CumulusKind.FAR) {
			farPlacementsFor()
		} else {
			nearPlacementsFor(kind)
		}

		val period = geometry.width * geometry.viewports
		if (period <= 0f || cumulusBitmaps.isEmpty()) {
			return
		}

		val compositionAlpha = nearCompositionAlpha(kind, alpha)
		val wrappedOffset = positiveModulo(geometry.offset, period)

		for (placement in placements) {
			val sprite = cumulusBitmaps[placement.spriteIndex % cumulusBitmaps.size]
			val spriteHeight = style.baseHeight * placement.scale * style.scale.height
			val spriteWidth = spriteHeight * sprite.width.toFloat() / sprite.height.toFloat() * style.scale.width
			val centerY = geometry.top - style.topOffset + geometry.height * placement.yFraction
			val centerX = positiveModulo(wrappedOffset + period * placement.xFraction, period)
			val spriteAlpha = (compositionAlpha * style.scale.alpha * placement.alphaScale).toInt().coerceIn(0, 255)
			if (spriteAlpha <= 0) {
				continue
			}

			paint.alpha = spriteAlpha
			for (shift in -1..1) {
				val wrappedX = centerX + shift * period
				if (wrappedX + spriteWidth * 0.5f < 0f || wrappedX - spriteWidth * 0.5f > geometry.width) {
					continue
				}

				updateColorFilter(cumulusTint(style.tint, kind, shadow, wrappedX, centerY))
				spriteDest.set(wrappedX - spriteWidth * 0.5f, centerY - spriteHeight * 0.5f, wrappedX + spriteWidth * 0.5f, centerY + spriteHeight * 0.5f)
				drawSprite(canvas, sprite, placement, wrappedX, centerY)
			}
		}
	}

	private fun cumulusTint(tint: Int, kind: CumulusKind, shadow: CumulusShadow?, x: Float, y: Float): Int {
		if (kind != CumulusKind.FAR || shadow == null) {
			return tint
		}

		return darken(tint, shadow.opacityAt(x, y) * shadow.strength)
	}

	private fun cumulusStyle(kind: CumulusKind, tint: Int, width: Float, height: Float): CumulusStyle {
		if (kind == CumulusKind.FAR) {
			return CumulusStyle(
				tint = liftTowardWhite(tint, FAR_CUMULUS_TINT_LIFT),
				baseHeight = min(width * FAR_BASE_HEIGHT_TO_WIDTH, height * FAR_BASE_HEIGHT_TO_DECK),
				topOffset = height * FAR_CUMULUS_RISE,
				scale = CumulusScale(
					width = FAR_VEIL_WIDTH_SCALE,
					height = FAR_VEIL_HEIGHT_SCALE,
					alpha = FAR_CUMULUS_ALPHA_SCALE * FAR_VEIL_ALPHA_SCALE
				)
			)
		}

		return CumulusStyle(
			tint = tint,
			baseHeight = min(width * 0.22f, height * 0.48f),
			topOffset = 0f,
			scale = CumulusScale(
				width = 1f,
				height = 1f,
				alpha = HERO_CUMULUS_ALPHA_SCALE
			)
		)
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

		cachedNearPlacements = buildPlacements(NEAR_PLACEMENT_TUNING, anchors, Random(layoutSeed(epochDay) xor seedSalt), HERO_VARIANT_COUNT)

		cachedNearEpochDay = epochDay
		return cachedNearPlacements
	}

	private fun farPlacementsFor(): List<CumulusPlacement> {
		val epochDay = System.currentTimeMillis() / MILLIS_PER_DAY
		if (cachedFarEpochDay == epochDay && cachedFarPlacements.isNotEmpty()) {
			return cachedFarPlacements
		}

		cachedFarPlacements = buildPlacements(FAR_PLACEMENT_TUNING, FAR_ANCHORS, Random(layoutSeed(epochDay) xor FAR_LAYOUT_SEED_SALT), FAR_VARIANT_COUNT)
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

	private fun drawSprite(canvas: Canvas, sprite: Bitmap, placement: CumulusPlacement, centerX: Float, centerY: Float) {
		if (!placement.mirror) {
			canvas.drawBitmap(sprite, null, spriteDest, paint)
			return
		}

		canvas.withScale(-1f, 1f, centerX, centerY) {
			canvas.drawBitmap(sprite, null, spriteDest, paint)
		}
	}

	private fun overcastTexture(kind: SheetKind): Bitmap = synchronized(OVERCAST_BITMAP_LOCK) {
		val cached = cachedOvercastTexture(kind)
		if (cached != null && !cached.isRecycled) {
			return@synchronized cached
		}

		return@synchronized buildOvercastTexture(kind)
			.also { cacheOvercastTexture(kind, it) }
	}

	private fun cachedOvercastTexture(kind: SheetKind) = when (kind) {
		SheetKind.FAR -> sharedFarOvercastBitmap
		SheetKind.NEAR -> sharedNearOvercastBitmap
	}

	private fun cacheOvercastTexture(kind: SheetKind, bitmap: Bitmap) {
		when (kind) {
			SheetKind.FAR -> sharedFarOvercastBitmap = bitmap
			SheetKind.NEAR -> sharedNearOvercastBitmap = bitmap
		}
	}

	private fun buildOvercastTexture(kind: SheetKind): Bitmap {
		val profile = when (kind) {
			SheetKind.FAR -> FAR_OVERCAST_PROFILE
			SheetKind.NEAR -> NEAR_OVERCAST_PROFILE
		}
		val noise = OvercastNoise(profile)
		val output = IntArray(OVERCAST_FIELD_WIDTH * OVERCAST_FIELD_HEIGHT)

		for (y in 0 until OVERCAST_FIELD_HEIGHT) {
			val v = y.toFloat() / (OVERCAST_FIELD_HEIGHT - 1)
			for (x in 0 until OVERCAST_FIELD_WIDTH) {
				val u = x.toFloat() / OVERCAST_FIELD_WIDTH
				output[y * OVERCAST_FIELD_WIDTH + x] = overcastPixel(u, v, profile, noise)
			}
		}

		return upscaleOvercastField(output)
	}

	private fun overcastPixel(u: Float, v: Float, profile: OvercastProfile, noise: OvercastNoise): Int {
		val warpedX = wrapUnit(u + 0.055f * (noise.warpX.sample(u, v) - 0.5f))
		val warpedY = (v + 0.075f * (noise.warpY.sample(u, v) - 0.5f)).coerceIn(0f, 1f)
		val body = noise.body.sample(warpedX, warpedY)
		val billow = noise.billow.sample(warpedX, warpedY)
		val fine = noise.fine.sample(warpedX, warpedY)
		val structure = 0.58f * body + 0.35f * billow + 0.07f * fine
		val bank = 0.76f + 0.24f * smoothstep(0.22f, 0.78f, noise.bank.sample(u, v))
		val lowerEdge = v + 0.20f * (noise.bottom.sample(u, v) - 0.5f)
		val envelope = (1f - smoothstep(0.68f, 1.02f, lowerEdge)) * (1f - smoothstep(0.92f, 0.998f, v))
		val density = smoothstep(profile.density.cut, 0.66f, structure) * bank * envelope
		val alpha = (density * profile.density.alphaGain).coerceIn(0f, 1f)
		val upperLeft = noise.body.sample(wrapUnit(warpedX - profile.lighting.shiftX), (warpedY - profile.lighting.shiftY).coerceAtLeast(0f))
		val lowerRight = noise.body.sample(wrapUnit(warpedX + profile.lighting.shiftX), (warpedY + profile.lighting.shiftY).coerceAtMost(1f))
		val directional = (upperLeft - lowerRight).coerceIn(-0.25f, 0.25f)
		val core = smoothstep(0.16f, 0.72f, density)
		var light = 1f - profile.lighting.shadowStrength * core + profile.lighting.directionalStrength * directional
		light += profile.lighting.billowLight * (billow - 0.5f)
		light += 0.045f * (fine - 0.5f)

		// Thin edges stay skylit; only cloud with real optical depth carries the shadow field.
		light = 1f - alpha * (1f - light)
		light = light.coerceIn(profile.lighting.minimumLight, 0.96f)
		val gray = (light * 255f).roundToInt()
		return Color.argb((alpha * 255f).roundToInt(), gray, gray, gray)
	}

	private fun upscaleOvercastField(pixels: IntArray): Bitmap {
		val field = createBitmap(OVERCAST_FIELD_WIDTH, OVERCAST_FIELD_HEIGHT).apply {
			setPixels(pixels, 0, OVERCAST_FIELD_WIDTH, 0, 0, OVERCAST_FIELD_WIDTH, OVERCAST_FIELD_HEIGHT)
		}

		val texture = field.scale(OVERCAST_TEXTURE_WIDTH, OVERCAST_TEXTURE_HEIGHT)
		if (texture !== field) {
			field.recycle()
		}

		return texture
	}

	private fun darken(color: Int, amount: Float): Int {
		val factor = 1f - amount.coerceIn(0f, 1f)
		return Color.rgb(
			(Color.red(color) * factor).roundToInt(),
			(Color.green(color) * factor).roundToInt(),
			(Color.blue(color) * factor).roundToInt()
		)
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
		private const val NEAR_COMPOSITION_BLEND_START = 0.50f
		private const val SPARSE_LAYOUT_SEED_SALT = 0x21A7F1
		private const val SCATTERED_LAYOUT_SEED_SALT = 0x53C4D2
		private const val BROKEN_LAYOUT_SEED_SALT = 0x7B19E5
		private const val FAR_LAYOUT_SEED_SALT = 0x46A2D9

		private const val HERO_VARIANT_COUNT = 2
		private const val FAR_VARIANT_COUNT = 2
		private const val HERO_CUMULUS_ALPHA_SCALE = 0.92f
		private const val FAR_CUMULUS_ALPHA_SCALE = 1.18f
		private const val FAR_VEIL_ALPHA_SCALE = 1.12f
		private const val FAR_VEIL_HEIGHT_SCALE = 0.52f
		private const val FAR_VEIL_WIDTH_SCALE = 1.72f
		private const val FAR_CUMULUS_TINT_LIFT = 0.16f
		private const val FAR_CUMULUS_RISE = 0.12f
		private const val FAR_BASE_HEIGHT_TO_WIDTH = 0.082f
		private const val FAR_BASE_HEIGHT_TO_DECK = 0.24f

		private const val OVERCAST_FIELD_WIDTH = 360
		private const val OVERCAST_FIELD_HEIGHT = 120
		private const val OVERCAST_TEXTURE_WIDTH = 720
		private const val OVERCAST_TEXTURE_HEIGHT = 240

		private val FAR_OVERCAST_PROFILE = OvercastProfile(
			noise = OvercastNoiseProfile(
				seed = 823,
				body = NoiseSize(10, 5),
				billow = NoiseSize(24, 11),
				fine = NoiseSize(52, 23)
			),
			density = OvercastDensityProfile(0.39f, 1.58f),
			lighting = OvercastLightingProfile(0.26f, 0.34f, 0.61f, 0.014f, 0.028f, 0.08f)
		)
		private val NEAR_OVERCAST_PROFILE = OvercastProfile(
			noise = OvercastNoiseProfile(
				seed = 1759,
				body = NoiseSize(7, 4),
				billow = NoiseSize(17, 8),
				fine = NoiseSize(40, 18)
			),
			density = OvercastDensityProfile(0.38f, 1.78f),
			lighting = OvercastLightingProfile(0.38f, 0.46f, 0.49f, 0.018f, 0.035f, 0.12f)
		)

		private val NEAR_PLACEMENT_TUNING = PlacementTuning(0.035f, 0.045f, 0.10f, 0.06f, 0.16f, 0.64f)
		private val FAR_PLACEMENT_TUNING = PlacementTuning(0.022f, 0.040f, 0.10f, 0.06f, 0.20f, 0.66f)

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

		private val OVERCAST_BITMAP_LOCK = Any()
		private var sharedFarOvercastBitmap: Bitmap? = null
		private var sharedNearOvercastBitmap: Bitmap? = null
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
			fun lift(channel: Int) = (channel + (255 - channel) * amount)
				.toInt()
				.coerceIn(0, 255)

			return Color.rgb(
				lift(Color.red(color)),
				lift(Color.green(color)),
				lift(Color.blue(color))
			)
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

private fun buildPlacements(tuning: PlacementTuning, anchors: List<CumulusAnchor>, random: Random, variantCount: Int): List<CumulusPlacement> = anchors.map { base ->
	val xDelta = random.nextFloat() * tuning.xJitter * 2f - tuning.xJitter
	val yDelta = random.nextFloat() * tuning.yJitter * 2f - tuning.yJitter
	val scaleDelta = 1f + random.nextFloat() * tuning.scaleJitter * 2f - tuning.scaleJitter
	val alphaDelta = 1f - random.nextFloat() * tuning.alphaJitter

	CumulusPlacement(
		spriteIndex = random.nextInt(variantCount),
		xFraction = wrapFraction(base.xFraction + xDelta),
		yFraction = (base.yFraction + yDelta).coerceIn(tuning.minY, tuning.maxY),
		scale = base.scale * scaleDelta,
		mirror = random.nextBoolean(),
		alphaScale = base.alphaScale * alphaDelta
	)
}

private fun wrapFraction(value: Float): Float {
	return when {
		value < 0f -> value + 1f
		value >= 1f -> value - 1f
		else -> value
	}
}

private class CloudGeometry {
	var width = 0f
	var height = 0f
	var offset = 0f
	var top = 0f
	var viewports = CLOUD_TEXTURE_VIEWPORTS
}

private data class PlacementTuning(val xJitter: Float, val yJitter: Float, val scaleJitter: Float, val alphaJitter: Float, val minY: Float, val maxY: Float)

internal data class CumulusStyle(val tint: Int, val baseHeight: Float, val topOffset: Float, val scale: CumulusScale)

internal data class CumulusScale(val width: Float, val height: Float, val alpha: Float)

private data class CumulusAnchor(val xFraction: Float, val yFraction: Float, val scale: Float, val alphaScale: Float = 1f)

internal data class CumulusPlacement(val spriteIndex: Int, val xFraction: Float, val yFraction: Float, val scale: Float, val mirror: Boolean, val alphaScale: Float)

private data class OvercastProfile(val noise: OvercastNoiseProfile, val density: OvercastDensityProfile, val lighting: OvercastLightingProfile)

private data class OvercastNoiseProfile(val seed: Int, val body: NoiseSize, val billow: NoiseSize, val fine: NoiseSize)

private data class NoiseSize(val columns: Int, val rows: Int)

private data class OvercastDensityProfile(val cut: Float, val alphaGain: Float)

private data class OvercastLightingProfile(
	val shadowStrength: Float,
	val directionalStrength: Float,
	val minimumLight: Float,
	val shiftX: Float,
	val shiftY: Float,
	val billowLight: Float
)

private class OvercastNoise(profile: OvercastProfile) {
	private val noise = profile.noise
	val warpX = NoiseGrid(5, 4, noise.seed + 1)
	val warpY = NoiseGrid(7, 5, noise.seed + 2)
	val body = FractalNoise(noise.body.columns, noise.body.rows, 3, noise.seed + 100)
	val billow = FractalNoise(noise.billow.columns, noise.billow.rows, 3, noise.seed + 200, billow = true)
	val fine = FractalNoise(noise.fine.columns, noise.fine.rows, 2, noise.seed + 300)
	val bank = NoiseGrid(4, 3, noise.seed + 400)
	val bottom = NoiseGrid(5, 3, noise.seed + 500)
}

private class NoiseGrid(private val columns: Int, private val rows: Int, seed: Int) {
	private val values = FloatArray(columns * rows)
	private val random = Random(seed)

	init {
		for (index in values.indices) {
			values[index] = random.nextFloat()
		}
	}

	fun sample(x: Float, y: Float): Float {
		val wrappedX = wrapUnit(x)
		val gridX = wrappedX * columns
		val xFloor = floor(gridX).toInt()
		val x0 = positiveModuloInt(xFloor, columns)
		val x1 = (x0 + 1) % columns
		val xAmount = smoothCurve(gridX - floor(gridX))

		val gridY = y.coerceIn(0f, 1f) * (rows - 1)
		val y0 = floor(gridY).toInt().coerceIn(0, rows - 1)
		val y1 = minOf(y0 + 1, rows - 1)
		val yAmount = smoothCurve(gridY - floor(gridY))

		val upper = lerpNoise(values[y0 * columns + x0], values[y0 * columns + x1], xAmount)
		val lower = lerpNoise(values[y1 * columns + x0], values[y1 * columns + x1], xAmount)
		return lerpNoise(upper, lower, yAmount)
	}
}

private class FractalNoise(columns: Int, rows: Int, octaves: Int, seed: Int, private val billow: Boolean = false) {
	private val layers = List(octaves) { octave ->
		NoiseGrid(columns shl octave, rows shl octave, seed + octave * 31)
	}

	fun sample(x: Float, y: Float): Float {
		var total = 0f
		var amplitude = 1f
		var norm = 0f
		for (layer in layers) {
			var value = layer.sample(x, y)
			if (billow) {
				value = 1f - abs(2f * value - 1f)
			}

			total += value * amplitude
			norm += amplitude
			amplitude *= 0.5f
		}

		return total / norm
	}
}

private fun wrapUnit(value: Float): Float {
	val wrapped = value - floor(value)
	return when {
		wrapped >= 1f -> 0f
		else -> wrapped
	}
}

private fun positiveModuloInt(value: Int, modulo: Int): Int {
	val result = value % modulo
	return when {
		result < 0 -> result + modulo
		else -> result
	}
}

private fun smoothCurve(value: Float): Float {
	val amount = value.coerceIn(0f, 1f)
	return amount * amount * (3f - 2f * amount)
}

private fun smoothstep(low: Float, high: Float, value: Float): Float {
	return smoothCurve((value - low) / (high - low))
}

private fun lerpNoise(from: Float, to: Float, fraction: Float) = from + (to - from) * fraction
