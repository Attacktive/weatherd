package xyz.attacktive.weatherd.domain.render

import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.annotation.DrawableRes
import androidx.core.graphics.get
import androidx.core.graphics.withScale
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.domain.model.CLOUD_CONTRAST_SCALE_RANGE

/** How many viewport widths a texture covers before repeating, unless a deck asks for its own span. */
internal const val CLOUD_TEXTURE_VIEWPORTS = 4f

/**
 * Reuses decoded cloud pixels, sampling transforms, tint state, and seeded sprite layouts between frames.
 *
 * Fair-weather cumulus uses transparent sprite compositions built from a small shared source set.
 * Other cloud layers, including dedicated overcast banks, are decoded once and sampled through a repeating bitmap shader.
 * Fog renders through its own path in SceneRenderer.
 */
internal class CloudLayer private constructor(resources: Resources, @DrawableRes texture: Int, private val cumulusKind: CumulusKind?) {
	constructor(resources: Resources, @DrawableRes texture: Int) : this(resources, texture, cumulusKindFor(texture))

	private val bitmap = if (cumulusKind == null) decode(resources, texture) else null

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
	private val opacitySampler = OpacitySampler()
	private val opacityGeometry = CloudDrawGeometry()
	private var cumulusAlpha = 0
	private var cumulusMultiply = Color.WHITE
	private var cumulusShadow: CumulusShadow? = null
	private var cumulusDrawStyle = CloudDrawStyle.DEFAULT

	private var previousMultiply = Color.WHITE
	private var previousContrast = 1f
	private var previousGrade = CloudColorGrade.IDENTITY
	private val colorMatrix = FloatArray(20)
	private val filterTints = IntArray(24)
	private val filterContrasts = FloatArray(24)
	private val filterGrades = arrayOfNulls<CloudColorGrade>(24)
	private val filters = arrayOfNulls<ColorMatrixColorFilter>(24)
	private var nextFilter = 0
	private var cachedFarEpochDay = Long.MIN_VALUE
	private var cachedFarPlacements: List<CumulusPlacement> = emptyList()
	private var cachedOpacityStyleKind: CumulusKind? = null
	private var cachedOpacityStyleWidth = Float.NaN
	private var cachedOpacityStyleHeight = Float.NaN
	private var cachedOpacityStyleSizeScale = Float.NaN
	private var cachedOpacityStyle: CumulusStyle? = null

	fun draw(canvas: Canvas, geometry: CloudDrawGeometry, tint: Int, alpha: Int, frameLayout: NearCloudLayout, shadow: CumulusShadow? = null, style: CloudDrawStyle = CloudDrawStyle.DEFAULT) {
		if (geometry.width <= 0f || geometry.height <= 0f || alpha <= 0) {
			return
		}

		val kind = cumulusKind
		if (kind != null) {
			drawCumulus(canvas, geometry, tint, alpha, frameLayout, kind, shadow, style)
			return
		}

		val source = checkNotNull(bitmap)
		val shader = checkNotNull(cloudShader)

		val period = geometry.width * geometry.viewports
		val wrappedOffset = positiveModulo(geometry.offset, period)

		transform.setScale(period / source.width, geometry.height / source.height)
		transform.postTranslate(wrappedOffset, geometry.top)
		shader.setLocalMatrix(transform)
		paint.shader = shader
		updateColorFilter(tint, style.contrast, style.grade)
		paint.alpha = alpha.coerceIn(0, 255)
		canvas.drawRect(0f, geometry.top, geometry.width, geometry.top + geometry.height, paint)
	}

	/**
	 * Prepares a reusable opacity sampler using the same sprite placement math as [drawCumulus].
	 * The expensive style and placement lookup happens once per profile; each subsequent point probe only samples primitive geometry and bitmap alpha.
	 */
	fun opacitySampler(width: Float, height: Float, offset: Float, top: Float, alpha: Int, frameLayout: NearCloudLayout, sizeScale: Float = 1f, populationScope: CloudPopulationScope = CloudPopulationScope.FULL): OpacitySampler? {
		val kind = cumulusKind ?: return null
		if (!canSampleOpacity(width, height, alpha)) {
			return null
		}

		val period = width * CLOUD_TEXTURE_VIEWPORTS
		if (period <= 0f) {
			return null
		}

		val placements = if (kind == CumulusKind.FAR) { farPlacementsFor(frameLayout.epochDay) } else { emptyList() }

		opacitySampler.configure(
			geometry = opacityGeometry.configure(width, height, positiveModulo(offset, period), top),
			style = opacityStyleFor(kind, width, height, sizeScale),
			placements = placements,
			frameLayout = frameLayout,
			start = if (populationScope == CloudPopulationScope.ADDITIONS) { kind.additionStart } else { 0 },
			end = if (kind == CumulusKind.FAR) { placements.size } else { kind.prefixSize },
			compositionAlpha = alpha
		)

		return opacitySampler
	}

	private fun canSampleOpacity(width: Float, height: Float, alpha: Int) =
		width > 0f && height > 0f && alpha > 0 && cumulusBitmaps.isNotEmpty()

	private fun opacityStyleFor(kind: CumulusKind, width: Float, height: Float, sizeScale: Float): CumulusStyle {
		val cached = cachedOpacityStyle
		if (cached != null && cachedOpacityStyleKind == kind && cachedOpacityStyleWidth == width && cachedOpacityStyleHeight == height && cachedOpacityStyleSizeScale == sizeScale) {
			return cached
		}

		val style = cumulusStyle(kind, width, height, sizeScale)
		cachedOpacityStyleKind = kind
		cachedOpacityStyleWidth = width
		cachedOpacityStyleHeight = height
		cachedOpacityStyleSizeScale = sizeScale
		cachedOpacityStyle = style
		return style
	}

	inner class OpacitySampler {
		private var height = 0f
		private var offset = 0f
		private var top = 0f
		private var period = 0f
		private lateinit var style: CumulusStyle
		private var placements: List<CumulusPlacement> = emptyList()
		private lateinit var frameLayout: NearCloudLayout
		private var start = 0
		private var end = 0
		private var compositionAlpha = 0
		private var sampleX = 0f
		private var sampleY = 0f

		fun configure(geometry: CloudDrawGeometry, style: CumulusStyle, placements: List<CumulusPlacement>, frameLayout: NearCloudLayout, start: Int, end: Int, compositionAlpha: Int) {
			this.height = geometry.height
			this.offset = geometry.offset
			this.top = geometry.top
			this.period = geometry.width * geometry.viewports
			this.style = style
			this.placements = placements
			this.frameLayout = frameLayout
			this.start = start
			this.end = end
			this.compositionAlpha = compositionAlpha
		}

		fun opacityAt(x: Float, y: Float): Float {
			sampleX = x
			sampleY = y
			var opacity = 0f
			for (index in start until end) {
				val placement = if (cumulusKind == CumulusKind.FAR) { placements[index] } else { frameLayout[index] }

				val sourceOpacity = placementOpacityAt(placement)
				opacity = sourceOpacity + opacity * (1f - sourceOpacity)
			}

			return opacity.coerceIn(0f, 1f)
		}

		private fun placementOpacityAt(placement: CumulusPlacement): Float {
			if (cumulusKind == CumulusKind.FAR) {
				return farPlacementOpacityAt(placement)
			}

			val baseCenterX = positiveModulo(offset + period * placement.xFraction, period)
			val baseCenterY = top - style.topOffset + height * placement.yFraction
			val mirrorDirection = if (placement.mirror) -1f else 1f
			var opacity = 0f

			val variant = HERO_VARIANTS[placement.spriteIndex % HERO_VARIANTS.size]
			for (part in variant.parts) {
				val sprite = cumulusBitmaps[part.spriteIndex]
				val spriteHeight = style.baseHeight * placement.scale * style.scale.height * part.scale * part.heightScale
				val spriteWidth = spriteHeight * sprite.width.toFloat() / sprite.height.toFloat() * style.scale.width * part.widthScale
				val centerX = baseCenterX + mirrorDirection * part.offsetX * style.baseHeight * placement.scale
				val centerY = baseCenterY + part.offsetY * style.baseHeight * placement.scale
				val spriteAlpha = (compositionAlpha * style.scale.alpha * placement.alphaScale).toInt().coerceIn(0, 255)
				if (spriteAlpha <= 0) {
					continue
				}

				var partOpacity = 0f
				for (shift in -1..1) {
					val sourceOpacity = wrappedSpriteOpacity(sprite, placement.mirror, centerX + shift * period, centerY, spriteWidth, spriteHeight, spriteAlpha)
					partOpacity = sourceOpacity + partOpacity * (1f - sourceOpacity)
				}

				opacity = partOpacity + opacity * (1f - partOpacity)
			}

			return opacity
		}

		private fun farPlacementOpacityAt(placement: CumulusPlacement): Float {
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
				val sourceOpacity = wrappedSpriteOpacity(sprite, placement.mirror, centerX + shift * period, centerY, spriteWidth, spriteHeight, spriteAlpha)
				opacity = sourceOpacity + opacity * (1f - sourceOpacity)
			}

			return opacity
		}

		private fun wrappedSpriteOpacity(sprite: Bitmap, mirror: Boolean, wrappedX: Float, centerY: Float, spriteWidth: Float, spriteHeight: Float, spriteAlpha: Int): Float {
			val left = wrappedX - spriteWidth * 0.5f
			val topEdge = centerY - spriteHeight * 0.5f
			if (sampleX < left || sampleX > left + spriteWidth || sampleY < topEdge || sampleY > topEdge + spriteHeight) {
				return 0f
			}

			var u = ((sampleX - left) / spriteWidth).coerceIn(0f, 1f)
			if (mirror) {
				u = 1f - u
			}

			val v = ((sampleY - topEdge) / spriteHeight).coerceIn(0f, 1f)
			val pixelX = (u * (sprite.width - 1)).roundToInt().coerceIn(0, sprite.width - 1)
			val pixelY = (v * (sprite.height - 1)).roundToInt().coerceIn(0, sprite.height - 1)
			val sourceAlpha = Color.alpha(sprite[pixelX, pixelY]) / 255f

			return sourceAlpha * (spriteAlpha / 255f)
		}
	}

	private fun drawCumulus(canvas: Canvas, geometry: CloudDrawGeometry, tint: Int, alpha: Int, frameLayout: NearCloudLayout, kind: CumulusKind, shadow: CumulusShadow?, drawing: CloudDrawStyle) {
		val period = geometry.width * geometry.viewports
		if (period <= 0f || cumulusBitmaps.isEmpty()) {
			return
		}

		val style = opacityStyleFor(kind, geometry.width, geometry.height, geometry.sizeScale)
		configureCumulusPaint(kind, tint, alpha, shadow, drawing)
		val offset = positiveModulo(geometry.offset, period)
		if (kind == CumulusKind.FAR) {
			drawFarPopulation(canvas, geometry, style, frameLayout.epochDay, offset, period)
		} else {
			drawNearPopulation(canvas, geometry, style, kind, frameLayout, offset, period)
		}
	}

	private fun configureCumulusPaint(kind: CumulusKind, tint: Int, alpha: Int, shadow: CumulusShadow?, drawing: CloudDrawStyle) {
		paint.shader = null
		cumulusAlpha = alpha
		cumulusMultiply = if (kind == CumulusKind.FAR) { liftTowardWhite(tint, FAR_CUMULUS_TINT_LIFT) } else { tint }

		cumulusShadow = shadow
		cumulusDrawStyle = drawing
	}

	private fun drawFarPopulation(canvas: Canvas, geometry: CloudDrawGeometry, style: CumulusStyle, epochDay: Long, offset: Float, period: Float) {
		val placements = farPlacementsFor(epochDay)
		for (index in placements.indices) {
			drawFarPlacement(canvas, geometry, style, placements[index], offset, period)
		}
	}

	private fun drawNearPopulation(canvas: Canvas, geometry: CloudDrawGeometry, style: CumulusStyle, kind: CumulusKind, frameLayout: NearCloudLayout, offset: Float, period: Float) {
		val start = if (cumulusDrawStyle.populationScope == CloudPopulationScope.ADDITIONS) { kind.additionStart } else { 0 }

		for (index in start until kind.prefixSize) {
			drawHeroPlacement(canvas, geometry, style, frameLayout[index], offset, period)
		}
	}

	private fun drawFarPlacement(canvas: Canvas, geometry: CloudDrawGeometry, style: CumulusStyle, placement: CumulusPlacement, wrappedOffset: Float, period: Float) {
		val sprite = cumulusBitmaps[placement.spriteIndex % cumulusBitmaps.size]
		val spriteHeight = style.baseHeight * placement.scale * style.scale.height
		val spriteWidth = spriteHeight * sprite.width.toFloat() / sprite.height.toFloat() * style.scale.width
		val centerY = geometry.top - style.topOffset + geometry.height * placement.yFraction
		val centerX = positiveModulo(wrappedOffset + period * placement.xFraction, period)
		val spriteAlpha = (cumulusAlpha * style.scale.alpha * placement.alphaScale).toInt().coerceIn(0, 255)
		if (spriteAlpha <= 0) {
			return
		}

		paint.alpha = spriteAlpha
		for (shift in -1..1) {
			val wrappedX = centerX + shift * period
			if (wrappedX + spriteWidth * 0.5f < 0f || wrappedX - spriteWidth * 0.5f > geometry.width) {
				continue
			}

			updateColorFilter(farShadowTint(cumulusMultiply, CumulusKind.FAR, cumulusShadow, wrappedX, centerY), cumulusDrawStyle.contrast, cumulusDrawStyle.grade)
			spriteDest.set(wrappedX - spriteWidth * 0.5f, centerY - spriteHeight * 0.5f, wrappedX + spriteWidth * 0.5f, centerY + spriteHeight * 0.5f)
			drawSprite(canvas, sprite, placement, wrappedX, centerY)
		}
	}

	private fun drawHeroPlacement(canvas: Canvas, geometry: CloudDrawGeometry, style: CumulusStyle, placement: CumulusPlacement, wrappedOffset: Float, period: Float) {
		val variant = HERO_VARIANTS[placement.spriteIndex % HERO_VARIANTS.size]
		val baseCenterX = positiveModulo(wrappedOffset + period * placement.xFraction, period)
		val baseCenterY = geometry.top - style.topOffset + geometry.height * placement.yFraction
		val mirrorDirection = if (placement.mirror) { -1f } else { 1f }

		updateColorFilter(cumulusMultiply, cumulusDrawStyle.contrast, cumulusDrawStyle.grade)

		for (part in variant.parts) {
			val sprite = cumulusBitmaps[part.spriteIndex]
			val spriteHeight = style.baseHeight * placement.scale * style.scale.height * part.scale * part.heightScale
			val spriteWidth = spriteHeight * sprite.width.toFloat() / sprite.height.toFloat() * style.scale.width * part.widthScale
			val centerX = baseCenterX + mirrorDirection * part.offsetX * style.baseHeight * placement.scale
			val centerY = baseCenterY + part.offsetY * style.baseHeight * placement.scale
			val spriteAlpha = (cumulusAlpha * style.scale.alpha * placement.alphaScale).toInt().coerceIn(0, 255)
			if (spriteAlpha <= 0) {
				continue
			}

			paint.alpha = spriteAlpha
			for (shift in -1..1) {
				val wrappedX = centerX + shift * period
				if (wrappedX + spriteWidth * 0.5f < 0f || wrappedX - spriteWidth * 0.5f > geometry.width) {
					continue
				}

				spriteDest.set(wrappedX - spriteWidth * 0.5f, centerY - spriteHeight * 0.5f, wrappedX + spriteWidth * 0.5f, centerY + spriteHeight * 0.5f)
				drawSprite(canvas, sprite, placement, wrappedX, centerY)
			}
		}
	}

	private fun farShadowTint(tint: Int, kind: CumulusKind, shadow: CumulusShadow?, x: Float, y: Float): Int {
		if (kind != CumulusKind.FAR || shadow == null) {
			return tint
		}

		val shadowAmount = (shadow.opacityAt(x, y) * shadow.strength).coerceIn(0f, 1f)

		return darken(tint, 1f - shadowAmount)
	}

	private fun cumulusStyle(kind: CumulusKind, width: Float, height: Float, sizeScale: Float): CumulusStyle {
		if (kind == CumulusKind.FAR) {
			return CumulusStyle(
				baseHeight = min(width * FAR_BASE_HEIGHT_TO_WIDTH, height * FAR_BASE_HEIGHT_TO_DECK) * sizeScale,
				topOffset = height * FAR_CUMULUS_RISE,
				scale = CumulusScale(
					width = FAR_VEIL_WIDTH_SCALE,
					height = FAR_VEIL_HEIGHT_SCALE,
					alpha = FAR_CUMULUS_ALPHA_SCALE * FAR_VEIL_ALPHA_SCALE
				)
			)
		}

		return CumulusStyle(
			baseHeight = min(width * if (width < height / NEAR_CLOUD_PORTRAIT_HEIGHT) { 0.40f } else { 0.20f }, height * 0.44f) * sizeScale,
			topOffset = 0f,
			scale = CumulusScale(
				width = 1f,
				height = 1f,
				alpha = HERO_CUMULUS_ALPHA_SCALE
			)
		)
	}


	private fun farPlacementsFor(epochDay: Long): List<CumulusPlacement> {
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


	private fun drawSprite(canvas: Canvas, sprite: Bitmap, placement: CumulusPlacement, centerX: Float, centerY: Float) {
		if (!placement.mirror) {
			canvas.drawBitmap(sprite, null, spriteDest, paint)
			return
		}

		canvas.withScale(-1f, 1f, centerX, centerY) {
			canvas.drawBitmap(sprite, null, spriteDest, paint)
		}
	}

	private fun updateColorFilter(multiplyColor: Int, contrast: Float, grade: CloudColorGrade) {
		val clampedContrast = contrast.coerceIn(CLOUD_CONTRAST_SCALE_RANGE.start, CLOUD_CONTRAST_SCALE_RANGE.endInclusive)
		if (multiplyColor == previousMultiply && clampedContrast == previousContrast && grade == previousGrade) {
			return
		}

		paint.colorFilter = if (multiplyColor == Color.WHITE && clampedContrast == 1f && grade.strength == 0f) {
			null
		} else {
			cachedColorFilter(multiplyColor, clampedContrast, grade)
		}

		previousMultiply = multiplyColor
		previousContrast = clampedContrast
		previousGrade = grade
	}

	private fun cachedColorFilter(tint: Int, contrast: Float, grade: CloudColorGrade): ColorMatrixColorFilter {
		for (index in filters.indices) {
			val filter = filters[index]
			if (filter != null && filterTints[index] == tint && filterContrasts[index] == contrast && filterGrades[index] == grade) {
				return filter
			}
		}

		writeCloudColorMatrix(colorMatrix, tint, contrast, grade)
		val filter = ColorMatrixColorFilter(colorMatrix)
		filterTints[nextFilter] = tint
		filterContrasts[nextFilter] = contrast
		filterGrades[nextFilter] = grade
		filters[nextFilter] = filter
		nextFilter = (nextFilter + 1) % filters.size
		return filter
	}

	private enum class CumulusKind(val prefixSize: Int, val additionStart: Int) {
		FAR(0, 0),
		SPARSE(8, 0),
		SCATTERED(12, 8),
		PARTLY(20, 12),
		BROKEN(32, 20)
	}

	class CumulusShadow(private var lower: OpacitySampler?, private var upper: OpacitySampler?, private var sourceOffsetX: Float, private var sourceOffsetY: Float, var strength: Float) {
		fun configure(lower: OpacitySampler?, upper: OpacitySampler?, sourceOffsetX: Float, sourceOffsetY: Float, strength: Float) = apply {
			this.lower = lower
			this.upper = upper
			this.sourceOffsetX = sourceOffsetX
			this.sourceOffsetY = sourceOffsetY
			this.strength = strength
		}

		fun opacityAt(x: Float, y: Float): Float {
			val sourceX = x + sourceOffsetX
			val sourceY = y + sourceOffsetY
			val lowerOpacity = lower?.opacityAt(sourceX, sourceY) ?: 0f
			val upperOpacity = upper?.opacityAt(sourceX, sourceY) ?: 0f

			return lowerOpacity + upperOpacity * (1f - lowerOpacity)
		}
	}

	companion object {
		fun partlyCumulus(resources: Resources) = CloudLayer(resources, R.drawable.cloud_cumulus_scattered, CumulusKind.PARTLY)

		fun createNearCloudLayout(epochDay: Long) = NearCloudLayout(epochDay, buildPlacements(NEAR_PLACEMENT_TUNING, NEAR_ANCHORS, Random(layoutSeed(epochDay) xor NEAR_LAYOUT_SEED_SALT), HERO_VARIANTS.size))

		private fun cumulusKindFor(@DrawableRes texture: Int) = when (texture) {
			R.drawable.cloud_cumulus_far -> CumulusKind.FAR
			R.drawable.cloud_cumulus_sparse -> CumulusKind.SPARSE
			R.drawable.cloud_cumulus_scattered -> CumulusKind.SCATTERED
			R.drawable.cloud_cumulus_broken -> CumulusKind.BROKEN
			else -> null
		}

		private const val NEAR_LAYOUT_SEED_SALT = 0x21A7F1
		private const val FAR_LAYOUT_SEED_SALT = 0x46A2D9

		private const val HERO_BROAD = 0
		private const val HERO_BROAD_ALT = 1
		private const val HERO_SOFT_BROAD = 2
		private const val HERO_SOFT_BROAD_ALT = 3
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

		private val NEAR_PLACEMENT_TUNING = PlacementTuning(0.035f, 0.045f, 0.10f, 0.06f, 0.12f, 0.95f)
		private val FAR_PLACEMENT_TUNING = PlacementTuning(0.022f, 0.040f, 0.10f, 0.06f, 0.20f, 0.66f)

		/*
		 * Seven coherent bank variants share four first-party source bitmaps.
		 * Modest proportion changes and one joined soft-bank composition vary the silhouette without restoring the old stacked small-cloud crowns.
		 * Their order alternates morphology families because buildPlacements cycles through neighboring variants before repeating.
		 */
		private val HERO_VARIANTS = listOf(
			HeroVariant(listOf(HeroPart(HERO_BROAD))),
			HeroVariant(listOf(HeroPart(HERO_BROAD_ALT))),
			HeroVariant(listOf(HeroPart(HERO_SOFT_BROAD, scale = 1.04f, heightScale = 1.10f))),
			HeroVariant(listOf(HeroPart(HERO_BROAD, widthScale = 1.12f, heightScale = 0.85f))),
			HeroVariant(listOf(HeroPart(HERO_SOFT_BROAD_ALT, scale = 1.10f, heightScale = 1.18f))),
			HeroVariant(listOf(HeroPart(HERO_BROAD_ALT, widthScale = 0.90f, heightScale = 1.06f))),
			HeroVariant(
				listOf(
					HeroPart(HERO_SOFT_BROAD, offsetX = -0.28f, offsetY = 0.015f, scale = 0.70f, widthScale = 1.15f, heightScale = 1.10f),
					HeroPart(HERO_SOFT_BROAD_ALT, offsetX = 0.28f, offsetY = -0.03f, scale = 0.78f, widthScale = 1.10f, heightScale = 1.18f)
				)
			)
		)

		private val NEAR_ANCHORS = buildList {
			fun group(vararg anchors: CumulusAnchor) {
				for (viewport in 0 until 4) {
					for (anchor in anchors) {
						add(anchor.copy(xFraction = (viewport + anchor.xFraction) / 4f))
					}
				}
			}

			group(CumulusAnchor(0.25f, 0.31f, 0.92f), CumulusAnchor(0.75f, 0.62f, 0.92f))
			group(CumulusAnchor(0.50f, 0.80f, 1.00f))
			group(CumulusAnchor(0.12f, 0.54f, 1.08f), CumulusAnchor(0.88f, 0.90f, 0.92f))
			group(CumulusAnchor(0.55f, 0.44f, 1.12f), CumulusAnchor(0.12f, 0.73f, 1.08f), CumulusAnchor(0.74f, 0.22f, 1.00f))
		}

		private val FAR_ANCHORS = listOf(
			CumulusAnchor(0.06f, 0.38f, 0.92f, alphaScale = 0.94f),
			CumulusAnchor(0.14f, 0.51f, 0.72f, alphaScale = 0.78f),
			CumulusAnchor(0.23f, 0.56f, 0.78f, alphaScale = 0.88f),
			CumulusAnchor(0.42f, 0.30f, 0.84f, alphaScale = 0.92f),
			CumulusAnchor(0.61f, 0.61f, 0.74f, alphaScale = 0.86f),
			CumulusAnchor(0.70f, 0.54f, 0.70f, alphaScale = 0.80f),
			CumulusAnchor(0.79f, 0.43f, 0.90f, alphaScale = 0.94f),
			CumulusAnchor(0.95f, 0.27f, 0.70f, alphaScale = 0.84f)
		)

		private var sharedHeroBitmaps: List<Bitmap>? = null
		private var sharedFarBitmaps: List<Bitmap>? = null

		private fun heroBitmaps(resources: Resources): List<Bitmap> {
			sharedHeroBitmaps?.let {
				return it
			}

			return listOf(
				decode(resources, R.drawable.cloud_cumulus_hero_broad),
				decode(resources, R.drawable.cloud_cumulus_hero_broad_alt),
				decode(resources, R.drawable.cloud_cumulus_hero_soft_broad),
				decode(resources, R.drawable.cloud_cumulus_hero_soft_broad_alt)
			)
			.also {
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
			)
			.also {
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

private fun buildPlacements(tuning: PlacementTuning, anchors: List<CumulusAnchor>, random: Random, variantCount: Int): List<CumulusPlacement> {
	val variantOffset = random.nextInt(variantCount)

	return anchors.mapIndexed { index, base ->
		val xDelta = random.nextFloat() * tuning.xJitter * 2f - tuning.xJitter
		val yDelta = random.nextFloat() * tuning.yJitter * 2f - tuning.yJitter
		val scaleDelta = 1f + random.nextFloat() * tuning.scaleJitter * 2f - tuning.scaleJitter
		val alphaDelta = 1f - random.nextFloat() * tuning.alphaJitter

		CumulusPlacement(
			spriteIndex = (variantOffset + index) % variantCount,
			xFraction = wrapFraction(base.xFraction + xDelta),
			yFraction = (base.yFraction + yDelta).coerceIn(tuning.minY, tuning.maxY),
			scale = base.scale * scaleDelta,
			mirror = random.nextBoolean(),
			alphaScale = base.alphaScale * alphaDelta
		)
	}
}

private fun wrapFraction(value: Float): Float {
	return when {
		value < 0f -> value + 1f
		value >= 1f -> value - 1f
		else -> value
	}
}

internal class CloudDrawGeometry {
	var width = 0f
	var height = 0f
	var offset = 0f
	var top = 0f
	var viewports = CLOUD_TEXTURE_VIEWPORTS
	var sizeScale = 1f

	fun configure(width: Float, height: Float, offset: Float, top: Float = 0f, viewports: Float = CLOUD_TEXTURE_VIEWPORTS, sizeScale: Float = 1f) = apply {
		this.width = width
		this.height = height
		this.offset = offset
		this.top = top
		this.viewports = viewports
		this.sizeScale = sizeScale
	}
}

/** Immutable material/population policy, cached by the renderer until its style inputs change. */
internal data class CloudDrawStyle(val contrast: Float = 1f, val grade: CloudColorGrade = CloudColorGrade.IDENTITY, val populationScope: CloudPopulationScope = CloudPopulationScope.FULL) {
	companion object {
		val DEFAULT = CloudDrawStyle()
	}
}

private data class PlacementTuning(val xJitter: Float, val yJitter: Float, val scaleJitter: Float, val alphaJitter: Float, val minY: Float, val maxY: Float)

internal data class CumulusStyle(val baseHeight: Float, val topOffset: Float, val scale: CumulusScale)

internal data class CumulusScale(val width: Float, val height: Float, val alpha: Float)

private data class CumulusAnchor(val xFraction: Float, val yFraction: Float, val scale: Float, val alphaScale: Float = 1f)

internal data class CumulusPlacement(val spriteIndex: Int, val xFraction: Float, val yFraction: Float, val scale: Float, val mirror: Boolean, val alphaScale: Float)

internal enum class CloudPopulationScope {
	FULL,
	ADDITIONS
}

private data class HeroVariant(val parts: List<HeroPart>)

private data class HeroPart(val spriteIndex: Int, val offsetX: Float = 0f, val offsetY: Float = 0f, val scale: Float = 1f, val widthScale: Float = 1f, val heightScale: Float = 1f)
