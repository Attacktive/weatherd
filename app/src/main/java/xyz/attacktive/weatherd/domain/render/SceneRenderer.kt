package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.CLOUD_SIZE_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.model.SUN_SIZE_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.SunColorPreset
import xyz.attacktive.weatherd.domain.model.drawsScenery
import xyz.attacktive.weatherd.domain.render.SceneRenderer.Companion.DOT_CORE_STOP
import xyz.attacktive.weatherd.domain.weather.SEVERITY_DRIZZLE
import xyz.attacktive.weatherd.domain.weather.SEVERITY_STEADY
import xyz.attacktive.weatherd.domain.weather.SEVERITY_STORM

/**
 * Draws a weather scene onto a Canvas using procedural scenery and generated cloud textures.
 * Split into a static [renderBackdrop] (sky, overcast ceiling, fog base, haze, vignette — cache it) and an animated [renderForeground] (twinkling stars, a glowing sun/moon, the horizon scenery, drifting clouds/overcast/mist, precipitation, lightning) advanced by `timeSeconds`.
 * Cloud sheets are decoded once and sampled through repeating bitmap shaders; fog uses cached scrolling veil tiles, so neither regenerates textures per frame.
 */
class SceneRenderer(resources: Resources) {
	private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
	private val blitPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
	private val blitDest = RectF()
	private val boltPath = Path()
	private val forkPath = Path()
	private val birdPath = Path()
	private val lightShaftPath = Path()
	private val sunCloudUpperSamples = FloatArray(SUN_SHAFT_PROFILE_SAMPLES)
	private val sunCloudLowerSamples = FloatArray(SUN_SHAFT_PROFILE_SAMPLES)
	private val sunCloudEdgeEnergy = FloatArray(SUN_SHAFT_PROFILE_SAMPLES)
	private val sunRenderContext = SunRenderContext()
	private val sunShaftGeometry = SunShaftGeometry()

	private class SunRenderContext {
		var span = 0f
		var width = 0f
		var height = 0f
		var centerX = 0f
		var centerY = 0f
		lateinit var params: SceneParams
		var pulse = 0f
		var timeSeconds = 0f
		var visibility = 1f
	}

	private class SunShaftGeometry {
		var originX = 0f
		var originY = 0f
		var directionX = 0f
		var directionY = 0f
		var normalX = 0f
		var normalY = 0f
		var innerReach = 0f
		var outerReach = 0f
		var innerHalfWidth = 0f
		var outerHalfWidth = 0f
		var tint = Color.WHITE
		var warpPhase = 0f
	}

	private var sceneryLayerPaths: List<SceneryLayerPath> = emptyList()
	private val sceneryAccentPath = Path()
	private var sceneryKey: String? = null
	private var sceneryFarCrestY = 0f
	private var sceneryNearCrestY = 0f
	private var sceneryReflectionY = -1f
	private var sceneryWindowXy = FloatArray(0)
	private var sceneryHasWindows = false
	private var sceneryGulls: List<SceneryFauna> = emptyList()
	private var sceneryMarine: List<SceneryFauna> = emptyList()
	private var sceneryBeaconXy = FloatArray(0)
	private var sceneryParasolXy = FloatArray(0)
	private var sceneryGlyphPaths: List<SceneryLayerPath> = emptyList()
	private var sceneryWindmill: SceneryWindmill? = null
	private val tiles = HashMap<String, Bitmap>()
	private val farOvercastBankDelegate = lazy { CloudLayer(resources, R.drawable.cloud_overcast_veil) }
	private val supportOvercastBankDelegate = lazy { CloudLayer(resources, R.drawable.cloud_overcast_support) }
	private val heroOvercastBankDelegate = lazy { CloudLayer(resources, R.drawable.cloud_overcast_hero) }
	private val farOvercastBank by farOvercastBankDelegate
	private val supportOvercastBank by supportOvercastBankDelegate
	private val heroOvercastBank by heroOvercastBankDelegate
	private val farCumulusDeck by lazy(LazyThreadSafetyMode.NONE) { CloudLayer(resources, R.drawable.cloud_cumulus_far) }
	private val cloudDrawGeometry = CloudDrawGeometry()

	/*
	 * The clear-sky placement profiles, ordered from fewest masses to most.
	 * Each lazy layer owns a seeded daily layout while CloudLayer shares the decoded source sprites across all four profiles.
	 */
	private val cumulusSteps = listOf(
		lazy(LazyThreadSafetyMode.NONE) { CloudLayer(resources, R.drawable.cloud_cumulus_sparse) },
		lazy(LazyThreadSafetyMode.NONE) { CloudLayer(resources, R.drawable.cloud_cumulus_scattered) },
		lazy(LazyThreadSafetyMode.NONE) { CloudLayer.partlyCumulus(resources) },
		lazy(LazyThreadSafetyMode.NONE) { CloudLayer(resources, R.drawable.cloud_cumulus_broken) }
	)

	private val rainbow by lazy(LazyThreadSafetyMode.NONE) { RainbowLayer(resources, R.drawable.rainbow) }
	private var tilesKey: String? = null
	private var rainPoints = FloatArray(0)
	private val spritePaint = Paint(Paint.FILTER_BITMAP_FLAG)
	private val spriteDest = RectF()

	/**
	 * The photo backdrop is blitted through its own paint rather than the shared [paint] or [blitPaint].
	 * It needs the bilinear filtering [paint] does not carry, and it must not inherit the opacity [blitPaint] is left holding after a soft-tile blit.
	 */
	private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
	private val photoDest = RectF()

	/** Additive-ish compositing for anything that is light rather than surface: the sun's bloom, its streak, and its lens ghosts. */
	private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
		.apply {
			xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
		}

	private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
		.apply {
			textAlign = Paint.Align.CENTER
			letterSpacing = 0.03f
		}

	// Per-frame lightning state, recomputed by [updateLightning] before anything that reacts to a flash draws.
	private var flashWash = 0f
	private var flashBolt = 0f
	private var flashSheet = false
	private var flashSlot = 0

	/*
	 * Soft-dot sprites for snowflakes and sleet pellets: the softness is rasterized once here, and every frame just blits them as filtered quads.
	 * A hard 1–3px disc shimmers against the pixel grid while it falls; a blurry sprite scaled bilinearly stays smooth at any size.
	 * Their colors are scene-independent, so they live outside [tiles] and survive scene changes.
	 */
	private val farFlakeSprite = softDotSprite(Color.rgb(235, 240, 248), 140)
	private val nearFlakeSprite = softDotSprite(Color.rgb(252, 253, 255), 235)
	private val pelletSprite = softDotSprite(Color.rgb(236, 244, 252), 230)

	/**
	 * The decoded photo to draw as the sky while [SceneParams.backdropScene] is [BackdropScene.PHOTO].
	 * The owner sets this for the duration of one [renderBackdrop] call and releases it afterward; the renderer only borrows the bitmap and never recycles it.
	 * Null — or a bitmap the owner has already recycled — is not an error: the procedural sky draws instead.
	 * The assignment, the [renderBackdrop] call, the clear and the recycle must all happen on the thread that rasterizes: this field is deliberately unsynchronized, and the sky pass reads it, checks it for recycling and then dereferences it through the blit.
	 */
	var backgroundPhoto: Bitmap? = null

	fun render(canvas: Canvas, width: Int, height: Int, params: SceneParams, timeSeconds: Float) {
		renderBackdrop(canvas, width, height, params)
		renderForeground(canvas, width, height, params, timeSeconds)
	}

	/**
	 * Decodes the dedicated overcast bank sources before the render loop needs them.
	 * Call this from a background dispatcher; [drawCloudDrift] deliberately refuses to initialize the lazy banks on a frame.
	 */
	fun prewarmOvercastClouds() {
		farOvercastBankDelegate.value
		supportOvercastBankDelegate.value
		heroOvercastBankDelegate.value
	}

	/** The static layers (sky, overcast ceiling, fog base, haze, vignette). Cache these — they don't animate frame-to-frame. */
	fun renderBackdrop(canvas: Canvas, width: Int, height: Int, params: SceneParams) {
		// Foreground drawing reuses this paint with translucent colors, so a phase-triggered backdrop rebuild must not inherit that alpha.
		paint.alpha = 255

		val w = width.toFloat()
		val h = height.toFloat()

		drawSky(canvas, w, h, params)

		val ceilingStrength = overcastCeilingStrength(effectiveCloudiness(params))
		if (ceilingStrength > 0f) {
			drawOvercastCeiling(canvas, w, h, params, ceilingStrength)
		}

		if (params.fogDensity > 0f) {
			drawFogBase(canvas, w, h, params)
		}

		if (showsHaze(params)) {
			drawHaze(canvas, w, h, params)
		}

		drawVignette(canvas, w, h)
	}

	/** The animated layers (stars, sun/moon glow, horizon scenery, drifting clouds/overcast/mist, precipitation, lightning). */
	fun renderForeground(canvas: Canvas, width: Int, height: Int, params: SceneParams, timeSeconds: Float, includeOverlayLabels: Boolean = true) {
		val w = width.toFloat()
		val h = height.toFloat()
		val celestialCenterX = w * CELESTIAL_X_FRACTION
		val celestialCenterY = h * celestialHeightFraction(params.dayPhase, params.celestialProgress)
		val precipKey = params.precipitation?.let { "${it.kind}-${(it.severity * 100f).toInt()}" } ?: "dry"

		// Wind and cloud rendering are foreground concerns: they do not change cached tile pixels, so a refresh must not discard them.
		val key = "${width}x$height-${params.dayPhase}-$precipKey-f${(params.fogDensity * 100f).toInt()}-t${params.thunder}-sun${params.sunColorPreset}"
		if (key != tilesKey) {
			tiles.clear()
			tilesKey = key
		}

		if (params.dayPhase == DayPhase.NIGHT && showsCelestialBody(params)) {
			drawStars(canvas, w, h, timeSeconds)
			drawShootingStar(canvas, w, h, timeSeconds)
		}

		if (showsRainbow(params)) {
			rainbow.draw(canvas, celestialCenterX, celestialCenterY, params.dayPhase, sunVisibility(params.dayPhase, params.celestialProgress))
		}

		if (showsCelestialBody(params)) {
			drawCelestialBody(canvas, w, h, celestialCenterX, celestialCenterY, params, timeSeconds)
		}

		if (showsBirds(params)) {
			drawBirds(canvas, w, h, timeSeconds, params.dayPhase)
		}

		if (params.precipitation == null && effectiveCloudiness(params) > SCATTERED_CLOUD_FLOOR && effectiveCloudiness(params) <= CLOUD_DECK_THRESHOLD) {
			drawScatteredClouds(canvas, w, h, params, timeSeconds)
		}

		if (shouldDrawOvercastBanks(effectiveCloudiness(params), params.fogDensity, params.precipitation != null)) {
			drawCloudDrift(canvas, w, h, params, timeSeconds)
		}

		if (showsOvercastSunTransmission(params)) {
			drawOvercastSunTransmission(canvas, w, h, celestialCenterX, celestialCenterY, params, timeSeconds)
		}

		// The scenery draws after the celestial body and clouds (they belong to the sky behind it) but before fog, rain, and lightning (weather happens in front of the horizon).
		if (params.backdropScene.drawsScenery) {
			drawScenery(canvas, w, h, params, timeSeconds)
		}

		if (params.fogDensity > 0f) {
			drawFogDrift(canvas, w, h, params, timeSeconds)
		}

		if (params.thunder) {
			updateLightning(timeSeconds)
		} else {
			flashWash = 0f
			flashBolt = 0f
		}

		if (params.precipitation != null) {
			// Passing both params and the smart-cast non-null precipitation preserves narrowing without re-checking.
			drawPrecipitation(canvas, w, h, params, params.precipitation, timeSeconds, flashWash)
		}

		if (params.thunder) {
			drawLightning(canvas, w, h)
		}

		if (includeOverlayLabels) {
			renderOverlayLabels(canvas, width, height, params)
		}
	}

	/** Draws the optional weather/location HUD independently of scene translation, so live-wallpaper parallax does not slide interface text across launcher pages. */
	fun renderOverlayLabels(canvas: Canvas, width: Int, height: Int, params: SceneParams) {
		params.overlayLabels?.let {
			drawOverlayLabels(canvas, width.toFloat(), height.toFloat(), it)
		}
	}

	/**
	 * The optional text overlay, drawn above everything so no weather ever obscures it.
	 *
	 * It rides at the top of the sky, just under the status bar.
	 * Everywhere lower is spoken for: the scenery's silhouettes own the bottom third and are far too busy to read text against, while below them a launcher's dock and the lock screen's shortcuts claim the rest.
	 * The one thing that shares this band is the sun or moon drifting through, so the text keeps a shadow and simply draws over it.
	 */
	private fun drawOverlayLabels(canvas: Canvas, width: Float, height: Float, labels: OverlayLabels) {
		textPaint.setShadowLayer(height * 0.005f, 0f, height * 0.0012f, Color.argb(165, 8, 12, 20))

		labels.weather?.let {
			textPaint.textSize = height * 0.024f
			textPaint.color = Color.argb(225, 240, 245, 252)
			canvas.drawText(it, width / 2f, height * 0.09f, textPaint)
		}

		labels.location?.let {
			textPaint.textSize = height * 0.0165f
			textPaint.color = Color.argb(180, 228, 236, 248)
			canvas.drawText(it, width / 2f, height * 0.121f, textPaint)
		}
	}

	private fun drawSky(canvas: Canvas, width: Float, height: Float, params: SceneParams) {
		val photo = backgroundPhoto
		if (params.backdropScene == BackdropScene.PHOTO && photo != null && !photo.isRecycled) {
			// The photo supplies the whole sky, so everything renderBackdrop draws after this — overcast ceiling, fog base, haze, vignette — composites onto it with no tinting pass of its own.
			photoDest.set(0f, 0f, width, height)
			canvas.drawBitmap(photo, photoSourceRect(photo.width, photo.height, width, height), photoDest, photoPaint)
			return
		}

		val gradient = skyGradientFor(params)
		paint.style = Paint.Style.FILL
		paint.shader = LinearGradient(0f, 0f, 0f, height, gradient.topColor, gradient.bottomColor, Shader.TileMode.CLAMP)
		canvas.drawRect(0f, 0f, width, height, paint)
		paint.shader = null

		// A warm band above the horizon sells the low sun at dawn and dusk.
		if ((params.dayPhase == DayPhase.DAWN || params.dayPhase == DayPhase.DUSK) && showsCelestialBody(params)) {
			val glow = sunColor(params.dayPhase, SunColorPreset.NATURAL)
			paint.shader = LinearGradient(0f, height * 0.55f, 0f, height, withAlpha(glow, 0), withAlpha(glow, 80), Shader.TileMode.CLAMP)
			canvas.drawRect(0f, height * 0.55f, width, height, paint)
			paint.shader = null
		}
	}

	/**
	 * The user's chosen horizon silhouettes: two depth planes tinted from the current sky's bottom color, so storm gloom, snow milkiness, and night all carry onto them for free.
	 * Geometry and fauna anchors rebuild only when the scene or the surface size changes; every frame after that is a handful of cached path fills, optional mist/gulls/sails, and a few cheap accents.
	 */
	private fun drawScenery(canvas: Canvas, width: Float, height: Float, params: SceneParams, timeSeconds: Float) {
		if (!cacheScenery(width, height, params)) {
			return
		}

		val skyBottom = skyGradientFor(params).bottomColor
		val nearColor = sceneryPlaneTone(SceneryPlane.NEAR, skyBottom)

		paint.style = Paint.Style.FILL
		drawHorizonGlow(canvas, width, height, params.dayPhase, skyBottom)
		paint.shader = null

		drawSceneryLayers(canvas, SceneryPlane.FAR, params, skyBottom)
		drawFarPlaneDetails(canvas, width, height, params, timeSeconds, skyBottom)
		drawInterPlaneHaze(canvas, width, skyBottom)
		paint.shader = null

		drawSceneryLayers(canvas, SceneryPlane.NEAR, params, skyBottom)
		drawNearPlaneDetails(canvas, width, height, params, timeSeconds, skyBottom, nearColor)

		if (params.backdropScene == BackdropScene.METROPOLIS && showsHelicopter(params)) {
			helicopterPass(width, height, timeSeconds)?.let {
				drawHelicopter(canvas, params, timeSeconds, it, nearColor)
			}
		}
	}

	/** Fills one depth plane's cached layer paths, each in its material's color for the current weather and phase. */
	private fun drawSceneryLayers(canvas: Canvas, plane: SceneryPlane, params: SceneParams, skyBottom: Int) {
		for (layer in sceneryLayerPaths) {
			if (layer.plane != plane) {
				continue
			}

			paint.color = sceneryLayerColor(layer.material, plane, params, skyBottom)
			canvas.drawPath(layer.path, paint)
		}
	}

	/** The highest outline point of one plane's layers, in unit y; the frame bottom when the plane is empty. */
	private fun planeCrest(outlines: SceneryOutlines, plane: SceneryPlane) = outlines.layers
		.filter { it.plane == plane }
		.minOfOrNull { layer -> layer.outline.minOf { it.y } } ?: 1f

	/** Rebuilds cached scenery paths and accents when the scene or surface size changes. */
	private fun cacheScenery(width: Float, height: Float, params: SceneParams): Boolean {
		val key = "${params.backdropScene}-${width.toInt()}x${height.toInt()}"
		if (key == sceneryKey) {
			return true
		}

		val outlines = sceneryOutlinesFor(params.backdropScene, width / height) ?: return false
		sceneryLayerPaths = outlines.layers.map { layer ->
			val path = Path()
			fillSceneryPath(path, layer.outline, width, height)

			SceneryLayerPath(path, layer.material, layer.plane)
		}

		fillAccentPath(sceneryAccentPath, outlines.accents, width, height)

		val glyphCrest = outlines.glyphs
			.filter { it.plane == SceneryPlane.FAR }
			.minOfOrNull { glyph -> glyph.outline.minOf { it.y } } ?: 1f

		sceneryFarCrestY = minOf(planeCrest(outlines, SceneryPlane.FAR), glyphCrest) * height
		sceneryNearCrestY = planeCrest(outlines, SceneryPlane.NEAR) * height
		sceneryReflectionY = outlines.reflectionY?.times(height) ?: -1f
		sceneryHasWindows = outlines.windows.isNotEmpty()
		sceneryWindowXy = packPoints(outlines.windows, width, height)
		sceneryGulls = outlines.gulls
		sceneryMarine = outlines.marine
		sceneryBeaconXy = packPoints(outlines.beacons, width, height)
		sceneryParasolXy = packPoints(outlines.parasols, width, height)
		sceneryGlyphPaths = outlines.glyphs.map { glyph ->
			val path = Path().also { fillClosedOutline(it, glyph.outline, width, height) }

			SceneryLayerPath(path, glyph.material, glyph.plane)
		}

		sceneryWindmill = outlines.windmill
		sceneryKey = key

		return true
	}

	/** Painted glyphs (sloop, snowcaps), sea reflection, marine life, and mountain mist — everything that lives on/behind the far plane. */
	private fun drawFarPlaneDetails(canvas: Canvas, width: Float, height: Float, params: SceneParams, timeSeconds: Float, skyBottom: Int) {
		for (part in sceneryGlyphPaths) {
			if (part.plane != SceneryPlane.FAR) {
				continue
			}

			paint.color = sceneryLayerColor(part.material, SceneryPlane.FAR, params, skyBottom)
			canvas.drawPath(part.path, paint)
		}

		if (sceneryReflectionY >= 0f) {
			drawBeachReflection(canvas, width, height, params.dayPhase, skyBottom)
			paint.shader = null
		}

		if (sceneryMarine.isNotEmpty()) {
			val waterColor = sceneryLayerColor(SceneryMaterial.WATER, SceneryPlane.FAR, params, skyBottom)
			drawMarineLife(canvas, width, height, timeSeconds, darken(waterColor, 0.6f))
		}

		if (params.backdropScene == BackdropScene.MOUNTAINS) {
			drawValleyMist(canvas, width, height, timeSeconds, skyBottom, params.dayPhase)
		}
	}

	/** Near-plane glyphs (the farmhouse), parasols, fence accents, windmill sails, windows, beacons, and gulls. */
	private fun drawNearPlaneDetails(canvas: Canvas, width: Float, height: Float, params: SceneParams, timeSeconds: Float, skyBottom: Int, nearColor: Int) {
		for (part in sceneryGlyphPaths) {
			if (part.plane != SceneryPlane.NEAR) {
				continue
			}

			paint.color = sceneryLayerColor(part.material, SceneryPlane.NEAR, params, skyBottom)
			canvas.drawPath(part.path, paint)
		}

		if (sceneryParasolXy.isNotEmpty()) {
			val poleColor = sceneryLayerColor(SceneryMaterial.HULL, SceneryPlane.NEAR, params, skyBottom)
			val canopyColor = sceneryLayerColor(SceneryMaterial.PARASOL, SceneryPlane.NEAR, params, skyBottom)
			drawParasols(canvas, height, poleColor, canopyColor)
		}

		if (!sceneryAccentPath.isEmpty) {
			// Fence posts read as weathered wood, not cutouts of the hill behind them.
			paint.style = Paint.Style.STROKE
			paint.color = sceneryLayerColor(SceneryMaterial.HULL, SceneryPlane.NEAR, params, skyBottom)
			paint.strokeWidth = height * 0.0022f
			canvas.drawPath(sceneryAccentPath, paint)
			paint.style = Paint.Style.FILL
		}

		sceneryWindmill?.let {
			// Classic white canvas sails against the sky; the tower stays part of the painted hill.
			val sailColor = sceneryLayerColor(SceneryMaterial.SAIL, SceneryPlane.NEAR, params, skyBottom)
			drawWindmillSails(canvas, width, height, it, timeSeconds, sailColor)
		}

		if (sceneryHasWindows) {
			drawCityWindows(canvas, height, params.dayPhase)
		}

		if (sceneryBeaconXy.isNotEmpty()) {
			drawTowerBeacons(canvas, height, timeSeconds)
		}

		if (sceneryGulls.isNotEmpty() && params.dayPhase != DayPhase.NIGHT) {
			drawBeachGulls(canvas, width, height, timeSeconds, nearColor)
		}
	}

	/** Packs unit-frame points into a flat pixel xy array once per scenery rebuild. */
	private fun packPoints(points: List<OutlinePoint>, width: Float, height: Float) =
		if (points.isEmpty()) {
			FloatArray(0)
		} else {
			FloatArray(points.size * 2).also { xy ->
				points.forEachIndexed { index, point ->
					xy[index * 2] = point.x * width
					xy[index * 2 + 1] = point.y * height
				}
			}
		}

	/** Slow red aviation blips on the tallest roofs — a soft pulse, sitting on the parapet. */
	private fun drawTowerBeacons(canvas: Canvas, height: Float, timeSeconds: Float) {
		val radius = height * 0.0028f
		paint.style = Paint.Style.FILL

		var index = 0
		while (index < sceneryBeaconXy.size) {
			val x = sceneryBeaconXy[index]
			val y = sceneryBeaconXy[index + 1]
			val pulse = 0.35f + 0.65f * ((sin(timeSeconds * 2.4f + index * 0.7f) + 1f) * 0.5f)
			val alpha = (220f * pulse).roundToInt().coerceIn(40, 230)
			paint.color = Color.argb(alpha, 255, 64, 72)
			canvas.drawCircle(x, y, radius, paint)

			index += 2
		}
	}

	/**
	 * Thatched beach parasols on the bluff crest — smaller cones so neighbors don't overlap.
	 * Feet stay on the ridge; only the canopy/pole scale shrank.
	 */
	private fun drawParasols(canvas: Canvas, height: Float, poleColor: Int, canopyColor: Int) {
		paint.strokeCap = Paint.Cap.ROUND

		var index = 0
		while (index < sceneryParasolXy.size) {
			val x = sceneryParasolXy[index]
			val footY = sceneryParasolXy[index + 1]
			val poleH = height * 0.042f
			val canopyH = height * 0.024f
			val canopyW = height * 0.026f
			val peakY = footY - poleH
			val brimY = peakY + canopyH
			val fringe = height * 0.0035f
			val tuft = height * 0.0055f

			paint.style = Paint.Style.STROKE
			paint.strokeWidth = height * 0.0024f
			paint.color = withAlpha(poleColor, 245)
			canvas.drawLine(x, footY, x, brimY - fringe * 0.5f, paint)

			paint.style = Paint.Style.FILL
			paint.color = withAlpha(canopyColor, 245)
			birdPath.reset()
			birdPath.moveTo(x, peakY)
			birdPath.lineTo(x - canopyW, brimY)

			val teeth = 6
			for (tooth in 1 until teeth) {
				val t = tooth / teeth.toFloat()
				val fx = x - canopyW + canopyW * 2f * t
				val fy = if (tooth % 2 == 0) {
					brimY + fringe
				} else {
					brimY
				}

				birdPath.lineTo(fx, fy)
			}

			birdPath.lineTo(x + canopyW, brimY)
			birdPath.close()
			canvas.drawPath(birdPath, paint)

			birdPath.reset()
			birdPath.moveTo(x, peakY - tuft)
			birdPath.lineTo(x - tuft * 0.5f, peakY)
			birdPath.lineTo(x + tuft * 0.5f, peakY)
			birdPath.close()
			canvas.drawPath(birdPath, paint)

			index += 2
		}
	}

	/**
	 * Rotating sails only — the tower is already part of the near-hill silhouette, so it can't float off the crest.
	 */
	private fun drawWindmillSails(canvas: Canvas, width: Float, height: Float, mill: SceneryWindmill, timeSeconds: Float, color: Int) {
		val hubX = mill.hubX * width
		val hubY = mill.hubY * height
		val scale = mill.scale
		val bladeLen = height * 0.032f * scale
		val bladeHalf = height * 0.0045f * scale
		val hubR = height * 0.006f * scale
		val angle = timeSeconds * 0.7f

		paint.style = Paint.Style.FILL
		paint.color = withAlpha(color, 245)

		for (blade in 0 until 4) {
			val theta = angle + blade * (PI_F * 0.5f)
			val tipX = hubX + cos(theta) * bladeLen
			val tipY = hubY + sin(theta) * bladeLen
			val ox = -sin(theta) * bladeHalf
			val oy = cos(theta) * bladeHalf
			birdPath.reset()
			birdPath.moveTo(hubX, hubY)
			birdPath.lineTo(tipX + ox, tipY + oy)
			birdPath.lineTo(tipX - ox, tipY - oy)
			birdPath.close()
			canvas.drawPath(birdPath, paint)
		}

		canvas.drawCircle(hubX, hubY, hubR, paint)
	}

	/** Shark fins and a whale back in the water — drawn on the far plane before the near bluff covers the beach. */
	private fun drawMarineLife(canvas: Canvas, width: Float, height: Float, timeSeconds: Float, color: Int) {
		paint.style = Paint.Style.FILL
		paint.color = withAlpha(color, 210)

		for (critter in sceneryMarine) {
			val drift = sin(timeSeconds * critter.speed * 8f + critter.phase) * width * 0.02f
			val x = critter.baseX * width + drift
			val y = critter.baseY * height

			when (critter.kind) {
				SceneryFaunaKind.SHARK -> {
					// A dorsal fin, not a road sign: convex leading edge up to an upright tip, concave trailing edge scooping back down to the base.
					// Both axes come from one basis so the fin keeps its shape at every screen aspect.
					val finW = width * 0.012f * critter.scale
					val finH = finW * 0.65f
					birdPath.reset()
					birdPath.moveTo(x - finW * 0.35f, y + finH)
					birdPath.quadTo(x - finW * 0.28f, y + finH * 0.25f, x, y)
					birdPath.quadTo(x + finW * 0.02f, y + finH * 0.65f, x + finW * 0.45f, y + finH)
					birdPath.close()
					canvas.drawPath(birdPath, paint)
				}
				SceneryFaunaKind.WHALE -> {
					// Both axes come from one basis so the back never stretches with the screen's aspect.
					val bodyW = width * 0.055f * critter.scale
					val bodyH = bodyW * 0.14f
					val breach = (sin(timeSeconds * 0.35f + critter.phase) * 0.5f + 0.5f).coerceIn(0f, 1f)
					val lift = -breach * bodyH * 0.85f
					canvas.drawOval(x - bodyW, y - bodyH + lift, x + bodyW * 0.7f, y + bodyH * 0.4f + lift, paint)

					// Occasional spout when the back is highest.
					if (breach > 0.85f) {
						paint.style = Paint.Style.STROKE
						paint.strokeWidth = bodyH * 0.12f
						paint.color = withAlpha(color, 160)
						val spoutX = x + bodyW * 0.35f
						canvas.drawLine(spoutX, y + lift - bodyH, spoutX, y + lift - bodyH - bodyH * 1.05f * breach, paint)
						paint.style = Paint.Style.FILL
						paint.color = withAlpha(color, 210)
					}
				}
				else -> Unit
			}
		}
	}

	/** A few gulls drifting and flapping over the beach — always on for daytime beach scenes, cheap stroked W glyphs. */
	private fun drawBeachGulls(canvas: Canvas, width: Float, height: Float, timeSeconds: Float, color: Int) {
		paint.style = Paint.Style.STROKE
		paint.strokeCap = Paint.Cap.ROUND
		paint.strokeJoin = Paint.Join.ROUND
		paint.color = withAlpha(color, 200)

		for (gull in sceneryGulls) {
			val drift = ((gull.baseX + timeSeconds * gull.speed) % 1.15f + 1.15f) % 1.15f - 0.08f
			val x = drift * width
			val y = (gull.baseY + sin(timeSeconds * 0.9f + gull.phase) * 0.012f) * height
			val wing = width * 0.014f * gull.scale
			val flap = sin(timeSeconds * 8f + gull.phase) * wing * 0.55f
			paint.strokeWidth = wing * 0.2f
			birdPath.reset()
			birdPath.moveTo(x - wing, y - flap)
			birdPath.quadTo(x - wing * 0.35f, y + wing * 0.2f, x, y)
			birdPath.quadTo(x + wing * 0.35f, y + wing * 0.2f, x + wing, y - flap)
			canvas.drawPath(birdPath, paint)
		}

		paint.style = Paint.Style.FILL
	}

	/**
	 * Where a helicopter is over the skyline right now, or null when this slot stays quiet or its crossing hasn't started.
	 * Slot-scheduled like the meteors and bird flocks, so most of the time the sky is empty.
	 */
	private fun helicopterPass(width: Float, height: Float, timeSeconds: Float): HelicopterPass? {
		val slot = (timeSeconds / HELICOPTER_SLOT_SECONDS).toInt()
		val random = Random(HELICOPTER_SEED + slot)
		val quiet = random.nextFloat() < 0.5f
		if (quiet) {
			return null
		}

		val start = random.nextFloat(HELICOPTER_SLOT_SECONDS - HELICOPTER_CROSSING_SECONDS)
		val local = timeSeconds - slot * HELICOPTER_SLOT_SECONDS - start
		if (local !in 0f..HELICOPTER_CROSSING_SECONDS) {
			return null
		}

		val progress = local / HELICOPTER_CROSSING_SECONDS
		val direction = if (random.nextFloat() < 0.5f) {
			-1f
		} else {
			1f
		}

		val span = width * 1.2f
		val x = if (direction > 0f) {
			-width * 0.1f + span * progress
		} else {
			width * 1.1f - span * progress
		}

		val bodyW = width * 0.02f
		val y = height * random.nextFloat(0.58f, 0.66f) + sin(timeSeconds * 1.1f) * bodyW * 0.3f

		return HelicopterPass(x, y, direction, bodyW)
	}

	/**
	 * The helicopter itself: fuselage, tail boom, and a rotor line flickering to fake the spin.
	 * Every dimension derives from the fuselage length, keeping the shape honest at any aspect; after dark it carries a blinking anti-collision light.
	 */
	private fun drawHelicopter(canvas: Canvas, params: SceneParams, timeSeconds: Float, pass: HelicopterPass, color: Int) {
		val (x, y, direction, bodyW) = pass
		val bodyH = bodyW * 0.42f
		val tailLen = bodyW * 1.1f
		val mastH = bodyH * 0.5f

		paint.style = Paint.Style.FILL
		paint.color = withAlpha(color, 235)
		canvas.drawOval(x - bodyW * 0.5f, y - bodyH * 0.5f, x + bodyW * 0.5f, y + bodyH * 0.5f, paint)

		paint.style = Paint.Style.STROKE
		paint.strokeCap = Paint.Cap.ROUND
		paint.strokeWidth = bodyH * 0.3f
		val tailX = x - direction * (bodyW * 0.4f + tailLen)
		canvas.drawLine(x - direction * bodyW * 0.4f, y, tailX, y - bodyH * 0.35f, paint)

		// The main rotor sweeps as a flickering near-horizontal line — length pulsing with the blade angle fakes the spin.
		val rotorY = y - bodyH * 0.5f - mastH
		val rotorR = bodyW * (0.55f + 0.35f * abs(sin(timeSeconds * 9f)))
		paint.strokeWidth = bodyH * 0.22f
		canvas.drawLine(x, rotorY, x, y - bodyH * 0.5f, paint)
		canvas.drawLine(x - rotorR, rotorY, x + rotorR, rotorY, paint)
		paint.style = Paint.Style.FILL

		if (params.dayPhase == DayPhase.NIGHT || params.dayPhase == DayPhase.DUSK) {
			val blink = sin(timeSeconds * 6f)
			if (blink > 0.4f) {
				paint.color = Color.argb(200, 255, 64, 72)
				canvas.drawCircle(tailX, y - bodyH * 0.35f, bodyH * 0.28f, paint)
			}
		}
	}

	/** A short gradient band just above the far crest — warm at dawn/dusk, soft by day, cool and thin at night. */
	private fun drawHorizonGlow(canvas: Canvas, width: Float, height: Float, dayPhase: DayPhase, skyBottom: Int) {
		val (alpha, tint) = when (dayPhase) {
			DayPhase.DAWN -> 90 to lighten(skyBottom, 0.35f)
			DayPhase.DUSK -> 95 to lighten(skyBottom, 0.3f)
			DayPhase.DAY -> 40 to lighten(skyBottom, 0.2f)
			DayPhase.NIGHT -> 35 to Color.rgb(120, 150, 210)
		}

		val bandTop = (sceneryFarCrestY - height * 0.06f).coerceAtLeast(height * 0.55f)

		paint.shader = LinearGradient(
			0f,
			bandTop,
			0f,
			sceneryFarCrestY,
			withAlpha(tint, 0),
			withAlpha(tint, alpha),
			Shader.TileMode.CLAMP
		)

		// Fill past the crest (CLAMP holds the end color, and the silhouettes cover it) so the band never stops on a hard bright edge across the sky.
		canvas.drawRect(0f, bandTop, width, height, paint)
	}

	/** A translucent wash between the two crests so the far plane reads as atmospheric depth rather than a second flat sticker. */
	private fun drawInterPlaneHaze(canvas: Canvas, width: Float, skyBottom: Int) {
		val top = sceneryFarCrestY
		val bottom = sceneryNearCrestY
		if (bottom <= top) {
			return
		}

		// The wash ramps in above the far crest too — starting it at full strength on the crest line drew a seam right across the sky.
		val fade = (bottom - top) * 0.5f
		val fadeTop = top - fade
		val haze = lighten(skyBottom, 0.15f)

		paint.shader = LinearGradient(
			0f,
			fadeTop,
			0f,
			bottom,
			intArrayOf(withAlpha(haze, 0), withAlpha(haze, 55), withAlpha(haze, 0)),
			floatArrayOf(0f, fade / (bottom - fadeTop), 1f),
			Shader.TileMode.CLAMP
		)

		canvas.drawRect(0f, fadeTop, width, bottom, paint)
	}

	/**
	 * Soft mist bands drifting through the mountain valley between the two ridges.
	 * Cheap ovals + low alpha — no blur filters, so the live wallpaper stays light.
	 */
	private fun drawValleyMist(canvas: Canvas, width: Float, height: Float, timeSeconds: Float, skyBottom: Int, dayPhase: DayPhase) {
		val top = sceneryFarCrestY
		val bottom = sceneryNearCrestY
		if (bottom <= top + height * 0.02f) {
			return
		}

		val mist = lighten(skyBottom, 0.35f)
		val baseAlpha = when (dayPhase) {
			DayPhase.DAY -> 55
			DayPhase.DAWN, DayPhase.DUSK -> 70
			DayPhase.NIGHT -> 35
		}

		val valley = bottom - top
		paint.style = Paint.Style.FILL

		for (band in 0 until 3) {
			val speed = 0.012f + band * 0.006f
			val travel = width * 1.35f
			val drift = ((timeSeconds * speed * width + band * width * 0.37f) % travel + travel) % travel - width * 0.2f
			val cy = top + valley * (0.28f + band * 0.22f) + sin(timeSeconds * 0.18f + band * 1.7f) * height * 0.006f
			val bandH = valley * (0.18f + band * 0.04f)
			val bandW = width * (0.5f + band * 0.12f)
			val alpha = (baseAlpha * (1f - band * 0.12f)).roundToInt().coerceIn(20, 80)
			val topY = cy - bandH * 0.5f
			val bottomY = cy + bandH * 0.5f

			fun drawBand(originX: Float) {
				paint.shader = RadialGradient(
					originX + bandW * 0.5f,
					cy,
					bandW * 0.55f,
					withAlpha(mist, alpha),
					withAlpha(mist, 0),
					Shader.TileMode.CLAMP
				)

				canvas.drawOval(originX, topY, originX + bandW, bottomY, paint)
			}

			drawBand(drift)

			// Wrap so a band exiting one side re-enters the other without a pop.
			drawBand(drift - travel)
		}

		paint.shader = null
	}

	/** A short vertical wash under the sea line; muted at night so the water stays a silhouette. */
	private fun drawBeachReflection(canvas: Canvas, width: Float, height: Float, dayPhase: DayPhase, skyBottom: Int) {
		val alpha = when (dayPhase) {
			DayPhase.DAWN, DayPhase.DUSK -> 70
			DayPhase.DAY -> 45
			DayPhase.NIGHT -> 18
		}

		val bandBottom = (sceneryReflectionY + height * 0.045f).coerceAtMost(height)

		paint.shader = LinearGradient(
			0f,
			sceneryReflectionY,
			0f,
			bandBottom,
			withAlpha(lighten(skyBottom, 0.25f), alpha),
			withAlpha(skyBottom, 0),
			Shader.TileMode.CLAMP
		)

		canvas.drawRect(0f, sceneryReflectionY, width, bandBottom, paint)
	}

	/** Seeded warm window rects for the metropolis — static positions, phase-only opacity, no twinkle. */
	private fun drawCityWindows(canvas: Canvas, height: Float, dayPhase: DayPhase) {
		val alpha = when (dayPhase) {
			DayPhase.NIGHT -> 210
			DayPhase.DUSK -> 150
			DayPhase.DAWN -> 40
			DayPhase.DAY -> return
		}

		val w = height * 0.0028f
		val h = height * 0.0036f
		paint.color = Color.argb(alpha, 255, 214, 140)

		var index = 0
		while (index < sceneryWindowXy.size) {
			val x = sceneryWindowXy[index]
			val y = sceneryWindowXy[index + 1]
			canvas.drawRect(x, y, x + w, y + h, paint)

			index += 2
		}
	}

	/** Scales a unit outline to pixels and closes it across the bottom corners, so the silhouette fills down off the frame. */
	private fun fillSceneryPath(path: Path, outline: List<OutlinePoint>, width: Float, height: Float) {
		path.rewind()
		path.moveTo(outline.first().x * width, outline.first().y * height)

		for (index in 1 until outline.size) {
			path.lineTo(outline[index].x * width, outline[index].y * height)
		}

		path.lineTo(width, height)
		path.lineTo(0f, height)
		path.close()
	}

	/** Scales a closed unit polygon (glyphs) to pixels without stretching it to the frame bottom. */
	private fun fillClosedOutline(path: Path, outline: List<OutlinePoint>, width: Float, height: Float) {
		path.rewind()
		path.moveTo(outline.first().x * width, outline.first().y * height)

		for (index in 1 until outline.size) {
			path.lineTo(outline[index].x * width, outline[index].y * height)
		}

		path.close()
	}

	/** Scales the accent polylines to pixels as open strokes — gulls and friends, never filled. */
	private fun fillAccentPath(path: Path, accents: List<List<OutlinePoint>>, width: Float, height: Float) {
		path.rewind()

		for (accent in accents) {
			path.moveTo(accent.first().x * width, accent.first().y * height)

			for (index in 1 until accent.size) {
				path.lineTo(accent[index].x * width, accent[index].y * height)
			}
		}
	}

	private fun drawStars(canvas: Canvas, width: Float, height: Float, timeSeconds: Float) {
		val random = Random(STAR_SEED)
		val count = (width * height / STAR_AREA_PER_STAR).roundToInt()
		paint.style = Paint.Style.FILL

		repeat(count) {
			val x = random.nextFloat(0f, width)
			val y = random.nextFloat(0f, height * 0.62f)
			val radius = random.nextFloat(0.6f, 2.2f)
			val baseAlpha = random.nextFloat(80f, 230f)
			val phase = random.nextFloat(0f, TAU)
			val bright = random.nextFloat() < 0.14f

			// Every star used to twinkle at one shared 2.2 rad/s — synchronized twinkle reads as a screensaver. Each now has its own rate, and the bright ones breathe slowly instead of flickering.
			val frequency = random.nextFloat(1.4f, 3.1f)
			val rate = if (bright) {
				frequency * 0.45f
			} else {
				frequency
			}

			val twinkle = 0.55f + 0.45f * sin(timeSeconds * rate + phase)
			val alpha = (baseAlpha * twinkle).roundToInt().coerceIn(0, 255)

			// A handful of standout stars get a cool-blue glint halo so the sky isn't uniform pinpricks.
			if (bright) {
				paint.color = Color.argb(alpha / 4, 205, 222, 255)
				canvas.drawCircle(x, y, radius * 3.2f, paint)
			}

			val coreRadius = if (bright) {
				radius * 1.35f
			} else {
				radius
			}

			paint.color = Color.argb(alpha, 255, 255, 255)
			canvas.drawCircle(x, y, coreRadius, paint)
		}
	}

	/**
	 * At most one meteor per slot and most slots stay empty, so a clear night earns a rare treat rather than a fireworks show.
	 * The streak flies a seeded straight line under a sine envelope, swelling and dying instead of blinking in and out — and like everything else it is a pure function of time.
	 */
	private fun drawShootingStar(canvas: Canvas, width: Float, height: Float, timeSeconds: Float) {
		val slot = (timeSeconds / METEOR_SLOT_SECONDS).toInt()
		val random = Random(METEOR_SEED + slot)
		val quiet = random.nextFloat() < 0.55f
		if (quiet) {
			return
		}

		val start = random.nextFloat(METEOR_SLOT_SECONDS - METEOR_DURATION - 1f)
		val local = timeSeconds - slot * METEOR_SLOT_SECONDS - start
		if (local !in 0f..METEOR_DURATION) {
			return
		}

		val progress = local / METEOR_DURATION
		val envelope = sin(progress * PI_F)
		val fromX = width * random.nextFloat(0.15f, 0.75f)
		val fromY = height * random.nextFloat(0.06f, 0.28f)
		val angle = random.nextFloat(20f, 45f) * DEGREES_TO_RADIANS
		val direction = if (random.nextFloat() < 0.5f) {
			-1f
		} else {
			1f
		}

		val travel = width * 0.45f
		val headX = fromX + direction * cos(angle) * travel * progress
		val headY = fromY + sin(angle) * travel * progress
		val tail = width * 0.08f * envelope
		val tailX = headX - direction * cos(angle) * tail
		val tailY = headY - sin(angle) * tail

		// The same halo-under-core pairing as rain streaks, with a soft-dot head that grows and shrinks with the envelope.
		paint.style = Paint.Style.STROKE
		paint.strokeCap = Paint.Cap.ROUND
		paint.strokeWidth = 5.5f
		paint.color = Color.argb((36f * envelope).roundToInt(), 214, 226, 248)
		canvas.drawLine(tailX, tailY, headX, headY, paint)

		paint.strokeWidth = 2.2f
		paint.color = Color.argb((165f * envelope).roundToInt(), 244, 248, 255)
		canvas.drawLine(tailX, tailY, headX, headY, paint)

		paint.style = Paint.Style.FILL
		drawSoftDot(canvas, nearFlakeSprite, headX, headY, 3f * envelope)
	}

	private fun drawCelestialBody(canvas: Canvas, width: Float, height: Float, centerX: Float, centerY: Float, params: SceneParams, timeSeconds: Float) {
		/*
		 * Both discs size off the shorter side, so turning the device moves them without resizing them.
		 * Sizing off width alone more than doubled the sun against the screen on rotation, which no real sky does.
		 */
		val span = min(width, height)

		/*
		 * Two-sine breathing: a slow deep swell with a faster shimmer on top, so the glow visibly blooms and recedes instead of subtly wobbling.
		 * Both bodies share it, so a scene never has two glows drifting out of step.
		 */
		val pulse = 0.5f + 0.35f * sin(timeSeconds * 0.8f) + 0.15f * sin(timeSeconds * 2.1f)

		if (params.dayPhase == DayPhase.NIGHT) {
			if (params.moonVisible) {
				drawMoon(canvas, span, centerX, centerY, params, pulse)
			}
		} else if (params.sunVisible) {
			sunRenderContext.span = span
			sunRenderContext.width = width
			sunRenderContext.height = height
			sunRenderContext.centerX = centerX
			sunRenderContext.centerY = centerY
			sunRenderContext.params = params
			sunRenderContext.pulse = pulse
			sunRenderContext.timeSeconds = timeSeconds
			sunRenderContext.visibility = sunVisibility(params.dayPhase, params.celestialProgress)
			drawSun(canvas, sunRenderContext)
		}
	}

	private fun drawMoon(canvas: Canvas, span: Float, centerX: Float, centerY: Float, params: SceneParams, pulse: Float) {
		val radius = span * MOON_RADIUS_FRACTION
		val core = Color.rgb(232, 238, 247)

		// A crescent sheds far less light than a full disc, so the whole glow scales with the lit fraction.
		val litFraction = (1f - cos(params.moonPhase * TAU)) / 2f
		val litScale = 0.35f + 0.65f * litFraction

		// Two blits of one pre-rendered radial sprite deepen the bloom — building RadialGradients here churned two shader allocations every frame.
		val halo = tile("moonHalo", HALO_SPRITE_SIZE, HALO_SPRITE_SIZE) { buildHaloSprite(it, core) }
		blitSprite(canvas, halo, centerX, centerY, radius * (2.2f + 1.1f * pulse), ((80f + 130f * pulse) * litScale).roundToInt())

		// Wide, faint outer bloom breathing in counter-phase, so something is always in motion.
		blitSprite(canvas, halo, centerX, centerY, radius * (3.6f + 0.9f * (1f - pulse)), ((26f + 34f * (1f - pulse)) * litScale).roundToInt())

		// The disc is a sprite shaped by the real synodic phase — tonight's sky and the wallpaper agree on the moon.
		val phaseIndex = (params.moonPhase * MOON_PHASE_STEPS).roundToInt()
		val moonSprite = tile("moon-$phaseIndex", MOON_SPRITE_SIZE, MOON_SPRITE_SIZE) { buildMoonSprite(it, core, params.moonPhase) }
		blitSprite(canvas, moonSprite, centerX, centerY, radius / MOON_DISC_MARGIN, 255)
	}

	/** The sun as a structured atmospheric light source rather than a painted object. */
	private fun drawSun(canvas: Canvas, sun: SunRenderContext) {
		if (sun.visibility <= 0f) {
			return
		}

		val radius = sun.span * SUN_RADIUS_FRACTION * sun.params.sunSizeScale.coerceIn(SUN_SIZE_SCALE_RANGE.start, SUN_SIZE_SCALE_RANGE.endInclusive)
		val core = sunColor(sun.params.dayPhase, sun.params.sunColorPreset)
		val halo = tile("sunHalo", HALO_SPRITE_SIZE, HALO_SPRITE_SIZE) { buildHaloSprite(it, core) }
		val atmosphere = tile("sunAtmosphere", HALO_SPRITE_SIZE, HALO_SPRITE_SIZE) { buildSunAtmosphereSprite(it, core) }
		if (drawVeiledSun(canvas, sun, radius, core, atmosphere)) {
			return
		}

		drawSunLightShafts(canvas, sun, radius, core)
		if (sun.params.lensFlareEnabled) {
			drawSunLensFlare(canvas, sun, radius, core)
		}

		val directRadius = radius * SUN_DIRECT_BODY_SCALE
		drawDirectSunGlow(canvas, sun, directRadius, core, halo, atmosphere)

		val disc = tile("sunDisc", SUN_SPRITE_SIZE, SUN_SPRITE_SIZE) { buildSunSprite(it, core) }
		blitSprite(canvas, disc, sun.centerX, sun.centerY, directRadius / SUN_DISC_MARGIN * SUN_DIRECT_DISC_SCALE, sunAlpha(255f, sun.visibility))
	}

	private fun drawVeiledSun(canvas: Canvas, sun: SunRenderContext, radius: Float, core: Int, atmosphere: Bitmap): Boolean {
		if (sun.params.fogDensity <= 0f && effectiveCloudiness(sun.params) <= DIRECT_SUN_MAX_CLOUDINESS) {
			return false
		}

		val strength = min(veiledCloudStrength(effectiveCloudiness(sun.params)), veiledFogStrength(sun.params.fogDensity))
		blitGlow(canvas, atmosphere, sun.centerX, sun.centerY, radius * (SUN_VEILED_BLOOM_REACH + 0.25f * sun.pulse), sunAlpha(SUN_VEILED_BLOOM_ALPHA * strength, sun.visibility))
		drawVeiledSunDisc(canvas, sun, radius, core)
		return true
	}

	private fun veiledCloudStrength(cloudiness: Float): Float {
		if (cloudiness <= DIRECT_SUN_MAX_CLOUDINESS) {
			return 1f
		}

		return lerp(SUN_VEILED_MAX_STRENGTH, SUN_VEILED_MIN_STRENGTH, unlerp(DIRECT_SUN_MAX_CLOUDINESS, 1f, cloudiness))
	}

	private fun veiledFogStrength(fogDensity: Float): Float {
		if (fogDensity <= 0f) {
			return 1f
		}

		return lerp(SUN_VEILED_MAX_STRENGTH, SUN_VEILED_MIN_STRENGTH, fogDensity.coerceIn(0f, 1f))
	}

	private fun drawVeiledSunDisc(canvas: Canvas, sun: SunRenderContext, radius: Float, core: Int) {
		if (sun.params.fogDensity > 0f || sun.params.thunder || effectiveCloudiness(sun.params) <= DIRECT_SUN_MAX_CLOUDINESS || effectiveCloudiness(sun.params) > CLOUD_DECK_THRESHOLD) {
			return
		}

		val cover = unlerp(DIRECT_SUN_MAX_CLOUDINESS, CLOUD_DECK_THRESHOLD, effectiveCloudiness(sun.params))
		val discAlpha = sunAlpha(lerp(SUN_VEILED_DISC_MAX_ALPHA, SUN_VEILED_DISC_MIN_ALPHA, cover), sun.visibility)
		val disc = tile("sunDisc", SUN_SPRITE_SIZE, SUN_SPRITE_SIZE) { buildSunSprite(it, core) }
		blitSprite(canvas, disc, sun.centerX, sun.centerY, radius / SUN_DISC_MARGIN * SUN_VEILED_DISC_SCALE, discAlpha)
	}

	private fun drawOvercastSunTransmission(canvas: Canvas, width: Float, height: Float, centerX: Float, centerY: Float, params: SceneParams, timeSeconds: Float) {
		val span = min(width, height)
		val radius = span * SUN_RADIUS_FRACTION * params.sunSizeScale.coerceIn(SUN_SIZE_SCALE_RANGE.start, SUN_SIZE_SCALE_RANGE.endInclusive)
		val core = sunColor(params.dayPhase, params.sunColorPreset)
		val atmosphere = tile("sunAtmosphere", HALO_SPRITE_SIZE, HALO_SPRITE_SIZE) { buildSunAtmosphereSprite(it, core) }
		val halo = tile("sunHalo", HALO_SPRITE_SIZE, HALO_SPRITE_SIZE) { buildHaloSprite(it, core) }
		val cover = unlerp(CLOUD_DECK_THRESHOLD, 1f, effectiveCloudiness(params))
		val visibility = sunVisibility(params.dayPhase, params.celestialProgress)
		val pulse = 0.97f + 0.03f * sin(timeSeconds * 0.8f)
		val broadAlpha = sunAlpha(lerp(SUN_OVERCAST_TRANSMISSION_MAX_ALPHA, SUN_OVERCAST_TRANSMISSION_MIN_ALPHA, cover), visibility)
		val coreAlpha = sunAlpha(lerp(SUN_OVERCAST_CORE_MAX_ALPHA, SUN_OVERCAST_CORE_MIN_ALPHA, cover), visibility)

		blitGlow(canvas, atmosphere, centerX, centerY, radius * SUN_OVERCAST_TRANSMISSION_REACH * pulse, broadAlpha)
		blitGlow(canvas, halo, centerX, centerY, radius * SUN_OVERCAST_CORE_REACH * pulse, coreAlpha)
	}

	private fun drawDirectSunGlow(canvas: Canvas, sun: SunRenderContext, radius: Float, core: Int, halo: Bitmap, atmosphere: Bitmap) {
		blitGlow(canvas, atmosphere, sun.centerX, sun.centerY, radius * (SUN_BLOOM_FAR + 0.5f * sun.pulse), sunAlpha(SUN_BLOOM_FAR_ALPHA * (0.94f + 0.06f * sun.pulse), sun.visibility))
		drawSunCorona(canvas, sun, radius, core)
		blitGlow(canvas, halo, sun.centerX, sun.centerY, radius * (SUN_BLOOM_NEAR + 0.25f * (1f - sun.pulse)), sunAlpha(SUN_BLOOM_NEAR_ALPHA * (0.94f + 0.06f * sun.pulse), sun.visibility))
	}

	private fun drawSunCorona(canvas: Canvas, sun: SunRenderContext, radius: Float, core: Int) {
		if (sun.params.dayPhase == DayPhase.NIGHT) {
			return
		}

		val corona = tile("sunCorona-${sun.params.dayPhase}", SUN_CORONA_SPRITE_SIZE, SUN_CORONA_SPRITE_SIZE) { buildSunCoronaSprite(it, core, sunCoronaWarmth(sun.params.dayPhase)) }
		val scale = sunCoronaScale(sun.params.dayPhase)
		val alpha = sunCoronaAlpha(sun.params.dayPhase)
		val cloudStrength = sunCoronaCloudStrength(effectiveCloudiness(sun.params))
		blitGlow(canvas, corona, sun.centerX, sun.centerY, radius * SUN_CORONA_REACH * scale, sunAlpha(SUN_CORONA_ALPHA * alpha * cloudStrength * (0.92f + 0.08f * sun.pulse), sun.visibility))
	}

	private fun sunCoronaScale(dayPhase: DayPhase) = when (dayPhase) {
		DayPhase.DAY -> 1f
		DayPhase.DAWN -> SUN_CORONA_DAWN_SCALE
		DayPhase.DUSK -> SUN_CORONA_DUSK_SCALE
		DayPhase.NIGHT -> 0f
	}

	private fun sunCoronaAlpha(dayPhase: DayPhase) = when (dayPhase) {
		DayPhase.DAY -> 1f
		DayPhase.DAWN -> SUN_CORONA_DAWN_ALPHA
		DayPhase.DUSK -> SUN_CORONA_DUSK_ALPHA
		DayPhase.NIGHT -> 0f
	}

	private fun sunCoronaWarmth(dayPhase: DayPhase) = when (dayPhase) {
		DayPhase.DAY -> SUN_CORONA_DAY_WARMTH
		DayPhase.DAWN, DayPhase.DUSK -> SUN_CORONA_TWILIGHT_WARMTH
		DayPhase.NIGHT -> 0f
	}

	private fun sunCoronaCloudStrength(cloudiness: Float) = lerp(1f, SUN_CORONA_CLOUD_MIN_STRENGTH, unlerp(0f, DIRECT_SUN_MAX_CLOUDINESS, cloudiness))

	private fun drawSunLensFlare(canvas: Canvas, sun: SunRenderContext, radius: Float, core: Int) {
		val axisX = sun.width / 2f - sun.centerX
		val axisY = sun.height / 2f - sun.centerY
		val lensHalo = tile("sunLensHalo-reference", HALO_SPRITE_SIZE, HALO_SPRITE_SIZE) { buildLensHaloSprite(it) }
		val lensHaloAlpha = SUN_LENS_HALO_ALPHA * lensHaloPhaseStrength(sun.params.dayPhase)
		blitGlow(canvas, lensHalo, sun.centerX + axisX * SUN_LENS_HALO_AXIS_OFFSET, sun.centerY + axisY * SUN_LENS_HALO_AXIS_OFFSET, radius * SUN_LENS_HALO_REACH, sunAlpha(lensHaloAlpha, sun.visibility))

		val streak = tile("sunStreak", SUN_STREAK_SPRITE_WIDTH, SUN_STREAK_SPRITE_HEIGHT) { buildSunStreakSprite(it, core) }
		val streakHalfWidth = radius * SUN_STREAK_REACH
		blitGlowRect(canvas, streak, sun.centerX, sun.centerY, streakHalfWidth, streakHalfWidth * SUN_STREAK_ASPECT, sunAlpha(SUN_STREAK_ALPHA * (0.9f + 0.1f * sun.pulse), sun.visibility))
		for (index in LENS_GHOSTS.indices) {
			val ghost = LENS_GHOSTS[index]
			val tint = tile("sunGhost-$index", HALO_SPRITE_SIZE, HALO_SPRITE_SIZE) { buildLensGhostSprite(it, ghost.tint) }
			blitGlow(canvas, tint, sun.centerX + axisX * ghost.distance, sun.centerY + axisY * ghost.distance, radius * ghost.scale, sunAlpha(ghost.strength * 255f, sun.visibility))
		}
	}

	/**
	 * The moon rasterized once per phase step: the lit shape bounded by the circular limb and the elliptical terminator, and the craters clipped to the lit side.
	 * The dark side deliberately draws nothing — the sky shows straight through, keeping the moon stylized rather than realistic.
	 * Waning phases mirror the waxing construction horizontally instead of duplicating the arc plumbing.
	 */
	private fun buildMoonSprite(canvas: Canvas, core: Int, moonPhase: Float) {
		val center = MOON_SPRITE_SIZE / 2f
		val radius = center * MOON_DISC_MARGIN
		val brush = Paint(Paint.ANTI_ALIAS_FLAG)
		brush.style = Paint.Style.FILL

		val waning = moonPhase > 0.5f
		if (waning) {
			canvas.scale(-1f, 1f, center, center)
		}

		val waxingPhase = if (waning) {
			1f - moonPhase
		} else {
			moonPhase
		}

		// cos runs 1 → -1 across new → full: the terminator ellipse collapses to a line at the quarter and re-widens.
		val terminatorScale = cos(waxingPhase * TAU)
		val litPath = Path()
		val limb = RectF(center - radius, center - radius, center + radius, center + radius)
		litPath.arcTo(limb, -90f, 180f)

		val terminatorHalfWidth = radius * abs(terminatorScale)
		val terminatorOval = RectF(center - terminatorHalfWidth, center - radius, center + terminatorHalfWidth, center + radius)
		if (terminatorScale > 0f) {
			// Crescent: the terminator bulges toward the lit limb, leaving a sliver.
			litPath.arcTo(terminatorOval, 90f, -180f)
		} else {
			// Gibbous: the terminator bulges into the dark side.
			litPath.arcTo(terminatorOval, 90f, 180f)
		}

		litPath.close()
		brush.color = core
		canvas.drawPath(litPath, brush)

		canvas.withClip(litPath) {
			brush.color = withAlpha(darken(core, 0.82f), 90)
			drawCircle(center - radius * 0.32f, center - radius * 0.18f, radius * 0.2f, brush)
			drawCircle(center + radius * 0.18f, center + radius * 0.3f, radius * 0.14f, brush)
			drawCircle(center + radius * 0.32f, center - radius * 0.32f, radius * 0.1f, brush)
		}
	}

	private fun drawOvercastCeiling(canvas: Canvas, width: Float, height: Float, params: SceneParams, strength: Float) {
		val ceiling = overcastCeiling(params.dayPhase)
		paint.style = Paint.Style.FILL
		val ceilingAlpha = (190f * strength * params.cloudScale).roundToInt()
			.coerceIn(0, 255)

		paint.shader = LinearGradient(
			0f,
			0f,
			0f,
			height * 0.6f,
			withAlpha(ceiling, ceilingAlpha),
			withAlpha(ceiling, 0),
			Shader.TileMode.CLAMP
		)

		canvas.drawRect(0f, 0f, width, height * 0.6f, paint)

		paint.shader = null
	}

	/**
	 * Dedicated stratocumulus banks overlap into an overcast ceiling: a soft far veil, a middle support mass, one screen-dominant hero mass, and a low bridge veil.
	 * The sources already carry overcast morphology and internal shading, so runtime work is limited to scale, tint, opacity, and drift.
	 */
	private fun drawCloudDrift(canvas: Canvas, width: Float, height: Float, params: SceneParams, timeSeconds: Float) {
		if (!farOvercastBankDelegate.isInitialized() || !supportOvercastBankDelegate.isInitialized() || !heroOvercastBankDelegate.isInitialized()) {
			return
		}

		val base = lerpColor(overcastCeiling(params.dayPhase), cloudTint(params.dayPhase), 0.58f)
		val color = when {
			params.thunder -> darken(base, 0.72f)
			params.precipitation?.kind == PrecipitationKind.SNOW -> lighten(base, 0.12f)
			else -> base
		}

		val surge = width * 0.006f * params.windFactor * params.windScale
		val drift = surge * (0.6f * sin(timeSeconds * 0.19f) + 0.4f * sin(timeSeconds * 0.47f))
		val bobAmplitude = height * 0.006f * params.windScale
		val bob = bobAmplitude * (0.65f * sin(timeSeconds * 0.4f) + 0.35f * sin(timeSeconds * 1.07f))
		val swell = 0.96f + 0.04f * (0.7f * sin(timeSeconds * 0.55f) + 0.3f * sin(timeSeconds * 1.31f))

		val farPeriod = width * OVERCAST_FAR_VIEWPORTS
		val supportPeriod = width * OVERCAST_SUPPORT_VIEWPORTS
		val heroPeriod = width * OVERCAST_HERO_VIEWPORTS
		val bridgePeriod = width * OVERCAST_BRIDGE_VIEWPORTS
		val farOffset = wrapOffset(timeSeconds * width * (0.003f + params.windFactor * 0.006f) * params.windScale + drift * 0.35f - width * 0.22f, farPeriod)
		val supportOffset = wrapOffset(timeSeconds * width * (0.006f + params.windFactor * 0.011f) * params.windScale + drift * 0.75f - width * 0.67f, supportPeriod)
		val heroOffset = wrapOffset(timeSeconds * width * (0.009f + params.windFactor * 0.017f) * params.windScale + drift * 1.25f - width * 0.16f, heroPeriod)
		val bridgeOffset = wrapOffset(timeSeconds * width * (0.004f + params.windFactor * 0.008f) * params.windScale + drift * 0.55f - width * 0.91f, bridgePeriod)
		val farHeight = overcastBankHeight(width, height, OVERCAST_FAR_VIEWPORTS, OVERCAST_FAR_HEIGHT_SCALE)
		val supportHeight = overcastBankHeight(width, height, OVERCAST_SUPPORT_VIEWPORTS, OVERCAST_SUPPORT_HEIGHT_SCALE)
		val heroHeight = overcastBankHeight(width, height, OVERCAST_HERO_VIEWPORTS, OVERCAST_HERO_HEIGHT_SCALE)
		val bridgeHeight = overcastBankHeight(width, height, OVERCAST_BRIDGE_VIEWPORTS, OVERCAST_BRIDGE_HEIGHT_SCALE)
		val farAlpha = (255f * 0.46f * params.cloudScale).roundToInt()
		val supportAlpha = (255f * 0.72f * params.cloudScale).roundToInt()
		val heroAlpha = (255f * 0.88f * params.cloudScale * swell).roundToInt()
		val bridgeAlpha = (255f * 0.50f * params.cloudScale).roundToInt()

		farOvercastBank.draw(canvas, cloudDrawGeometry.configure(width, farHeight, farOffset, height * OVERCAST_FAR_TOP + bob * 0.25f, OVERCAST_FAR_VIEWPORTS), darken(color, 0.96f), farAlpha, contrast = params.cloudContrastScale)
		supportOvercastBank.draw(canvas, cloudDrawGeometry.configure(width, supportHeight, supportOffset, height * OVERCAST_SUPPORT_TOP - bob * 0.35f, OVERCAST_SUPPORT_VIEWPORTS), darken(color, 0.94f), supportAlpha, contrast = params.cloudContrastScale)
		heroOvercastBank.draw(canvas, cloudDrawGeometry.configure(width, heroHeight, heroOffset, height * OVERCAST_HERO_TOP - bob, OVERCAST_HERO_VIEWPORTS), color, heroAlpha, contrast = params.cloudContrastScale)
		farOvercastBank.draw(canvas, cloudDrawGeometry.configure(width, bridgeHeight, bridgeOffset, height * OVERCAST_BRIDGE_TOP + bob * 0.45f, OVERCAST_BRIDGE_VIEWPORTS), darken(color, 0.91f), bridgeAlpha, contrast = params.cloudContrastScale)
	}

	/**
	 * A small flock crossing every few minutes on fair days: staggered wing glyphs with phase-offset wingbeats and a light vertical bob.
	 * Slot-scheduled like meteors and lightning, so most of the time the sky is empty and a crossing stays a treat.
	 * Drawn behind the cloud sheets for depth.
	 */
	private fun drawBirds(canvas: Canvas, width: Float, height: Float, timeSeconds: Float, dayPhase: DayPhase) {
		val slot = (timeSeconds / BIRD_SLOT_SECONDS).toInt()
		val random = Random(BIRD_SEED + slot)
		val quiet = random.nextFloat() < 0.45f
		if (quiet) {
			return
		}

		val start = random.nextFloat(BIRD_SLOT_SECONDS - BIRD_CROSSING_SECONDS)
		val local = timeSeconds - slot * BIRD_SLOT_SECONDS - start
		if (local !in 0f..BIRD_CROSSING_SECONDS) {
			return
		}

		val progress = local / BIRD_CROSSING_SECONDS
		val direction = if (random.nextFloat() < 0.5f) {
			-1f
		} else {
			1f
		}

		val flockSize = 3 + random.nextFloat(3f).toInt()
		val baseY = height * random.nextFloat(0.14f, 0.26f)
		val span = width * 1.2f
		val leadX = if (direction > 0f) {
			-width * 0.1f + span * progress
		} else {
			width * 1.1f - span * progress
		}

		val wing = width * 0.011f
		paint.style = Paint.Style.STROKE
		paint.strokeCap = Paint.Cap.ROUND
		paint.strokeJoin = Paint.Join.ROUND
		paint.strokeWidth = wing * 0.22f
		paint.color = birdColor(dayPhase)

		repeat(flockSize) { j ->
			val trailing = j * width * 0.045f * direction
			val x = leadX - trailing
			val lateral = laneFraction(j, slot) - 0.5f
			val y = baseY + lateral * height * 0.05f + sin(timeSeconds * 1.3f + j * 1.7f) * height * 0.004f

			// The wingtips swing above and below the body, offset per bird, so the flock never flaps in unison.
			val flap = sin(timeSeconds * 9f + j * 2.1f) * wing * 0.55f
			birdPath.reset()
			birdPath.moveTo(x - wing, y - flap)
			birdPath.quadTo(x - wing * 0.35f, y + wing * 0.18f, x, y)
			birdPath.quadTo(x + wing * 0.35f, y + wing * 0.18f, x + wing, y - flap)
			canvas.drawPath(birdPath, paint)
		}

		paint.style = Paint.Style.FILL
	}

	/**
	 * The clear-sky deck: fair-weather cumulus over blue, rather than a veil whose opacity stands in for how much cloud there is.
	 * Coverage lives in sparse, scattered, partly cloudy and broken placement populations, while CloudLayer rotates through multiple morphology variants inside each population.
	 * A far deck of smaller, hazier masses sits lower toward the horizon, one broad support bank bridges the cloudier clear-sky range, and the near deck of full-size masses rides above it.
	 */
	private fun drawScatteredClouds(canvas: Canvas, width: Float, height: Float, params: SceneParams, timeSeconds: Float) {
		val coverage = ((effectiveCloudiness(params) - SCATTERED_CLOUD_FLOOR) / (CLOUD_DECK_THRESHOLD - SCATTERED_CLOUD_FLOOR)).coerceIn(0f, 1f)
		val cloudTop = scatteredCloudTop(width, height, params)
		val nearState = nearCumulusState(width, height, params, timeSeconds, coverage)
		val castShadow = cumulusCastShadow(width, height, params, cloudTop, nearState)

		drawSunVeil(canvas, width, height, params)
		drawFarCumulus(canvas, width, height, params, timeSeconds, coverage, cloudTop, castShadow)
		drawPartlyCloudBank(canvas, width, height, params, timeSeconds, coverage)
		drawNearCumulus(canvas, width, params, cloudTop, nearState)
	}

	/** Where the clear-sky decks may start; clouds are allowed to cross the sun and naturally occlude the light drawn behind them. */
	private fun scatteredCloudTop(width: Float, height: Float, params: SceneParams): Float {
		return if (width < height) {
			height * 0.10f
		} else {
			height * 0.08f
		}
	}

	/** The pale wash the sun throws onto the air around it; drawn under the decks so a cloud crossing it still reads as solid. */
	private fun drawSunVeil(canvas: Canvas, width: Float, height: Float, params: SceneParams) {
		if (!showsSunVeil(params)) {
			return
		}

		val veilAlpha = (255f * 0.22f * (effectiveCloudiness(params) / 0.55f) * params.cloudScale).roundToInt().coerceIn(0, 255)
		if (veilAlpha <= 0) {
			return
		}

		val veilTint = if (params.sunColorPreset == SunColorPreset.NATURAL) {
			when (params.dayPhase) {
				DayPhase.DAY -> Color.rgb(215, 228, 245)
				DayPhase.DAWN -> Color.rgb(240, 220, 225)
				DayPhase.DUSK -> Color.rgb(230, 205, 215)
				DayPhase.NIGHT -> Color.rgb(64, 72, 90)
			}
		} else {
			sunColor(params.dayPhase, params.sunColorPreset)
		}

		val atmosphere = tile("sunVeil-${params.dayPhase}-${params.sunColorPreset}", HALO_SPRITE_SIZE, HALO_SPRITE_SIZE) { buildSunAtmosphereSprite(it, veilTint) }

		blitSprite(
			canvas,
			atmosphere,
			width * CELESTIAL_X_FRACTION,
			height * celestialHeightFraction(params.dayPhase, params.celestialProgress),
			min(width, height) * SUN_RADIUS_FRACTION * params.sunSizeScale.coerceIn(SUN_SIZE_SCALE_RANGE.start, SUN_SIZE_SCALE_RANGE.endInclusive) * SUN_CUMULUS_VEIL_REACH,
			veilAlpha
		)
	}

	private fun nearCumulusState(width: Float, height: Float, params: SceneParams, timeSeconds: Float, coverage: Float): NearCumulusState {
		val deckHeight = if (width < height) {
			height * 0.46f
		} else {
			height * 0.40f
		}

		val step = nearCumulusStep(coverage)
		val lower = step.toInt().coerceAtMost(cumulusSteps.size - 1)
		val upper = lower + 1
		val blend = step - lower
		val alpha = (CUMULUS_NEAR_ALPHA * params.cloudScale).roundToInt().coerceIn(0, 255)
		val growth = if (upper < cumulusSteps.size && blend >= CUMULUS_BLEND_FLOOR) {
			(CUMULUS_NEAR_ALPHA * blend * params.cloudScale).roundToInt().coerceIn(0, 255)
		} else {
			0
		}

		return NearCumulusState(
			deckHeight = deckHeight,
			offset = cumulusOffset(width, params, timeSeconds, 0.008f + params.windFactor * 0.016f, 1.5f, 0.78f, CLOUD_TEXTURE_VIEWPORTS),
			lower = lower,
			upper = upper,
			alpha = alpha,
			growth = growth
		)
	}

	private fun nearCumulusStep(coverage: Float): Float {
		val partlyIndex = CUMULUS_PARTLY_INDEX.toFloat()
		val linearStep = coverage * (cumulusSteps.size - 1)
		if (linearStep <= partlyIndex) {
			return linearStep
		}

		if (coverage <= CUMULUS_BROKEN_BLEND_START) {
			return partlyIndex
		}

		return partlyIndex + unlerp(CUMULUS_BROKEN_BLEND_START, 1f, coverage)
	}

	/**
	 * Samples the upper deck along the incoming light direction, then lets the far deck darken only the lower cloud sprites whose projected points are covered.
	 * This keeps cast shadows on cloud material instead of painting translated silhouettes into the blue sky.
	 */
	private fun cumulusCastShadow(width: Float, height: Float, params: SceneParams, cloudTop: Float, nearState: NearCumulusState): CloudLayer.CumulusShadow? {
		val strength = cumulusCastShadowStrength(params)
		if (strength <= 0f) {
			return null
		}

		val sizeScale = cloudSizeScale(params)
		val lower = cumulusShadowSampler(width, cloudTop, nearState, nearState.lower, nearState.alpha, sizeScale)
		val upper = cumulusShadowSampler(width, cloudTop, nearState, nearState.upper, nearState.growth, sizeScale)
		if (lower == null && upper == null) {
			return null
		}

		return CloudLayer.CumulusShadow(
			lower = lower,
			upper = upper,
			sourceOffsetX = width * (CELESTIAL_X_FRACTION - 0.5f) * CUMULUS_CAST_SHADOW_HORIZONTAL_PROJECTION,
			sourceOffsetY = cumulusCastShadowOffsetY(width, height),
			strength = strength
		)
	}

	private fun cumulusCastShadowStrength(params: SceneParams) = when (params.dayPhase) {
		DayPhase.DAY -> CUMULUS_CAST_SHADOW_DAY_STRENGTH
		DayPhase.DAWN -> CUMULUS_CAST_SHADOW_TWILIGHT_STRENGTH
		DayPhase.DUSK -> CUMULUS_CAST_SHADOW_TWILIGHT_STRENGTH * sunVisibility(params.dayPhase, params.celestialProgress)
		DayPhase.NIGHT -> 0f
	}

	private fun cumulusShadowSampler(
		width: Float,
		cloudTop: Float,
		nearState: NearCumulusState,
		index: Int,
		alpha: Int,
		sizeScale: Float
	): CloudLayer.OpacitySampler? {
		if (index !in cumulusSteps.indices || alpha <= 0) {
			return null
		}

		return cumulusSteps[index].value.opacitySampler(
			width,
			nearState.deckHeight,
			nearState.offset,
			cloudTop,
			alpha,
			sizeScale
		)
	}

	private fun cumulusCastShadowOffsetY(width: Float, height: Float): Float {
		val fraction = if (width < height) {
			CUMULUS_CAST_SHADOW_SOURCE_Y_PORTRAIT
		} else {
			CUMULUS_CAST_SHADOW_SOURCE_Y_LANDSCAPE
		}

		return -height * fraction
	}

	/**
	 * The distant deck: one texture at a shorter repeat span, so its masses come out smaller, sitting lower and closer to the horizon.
	 * Distance is carried by haze and size, while far-cloud opacity stays subdued in sparse skies and strengthens toward the cloudier end of the clear-sky range.
	 * Projected opacity from the upper deck selectively shades clouds that sit in its light path.
	 */
	private fun drawFarCumulus(
		canvas: Canvas,
		width: Float,
		height: Float,
		params: SceneParams,
		timeSeconds: Float,
		coverage: Float,
		cloudTop: Float,
		castShadow: CloudLayer.CumulusShadow?
	) {
		val isPortrait = width < height
		val tint = lerpColor(cumulusTint(params.dayPhase), skyGradientFor(params).topColor, CUMULUS_FAR_HAZE)
		val farCoverage = coverage * coverage
		val alpha = ((CUMULUS_FAR_MIN_ALPHA + CUMULUS_FAR_ALPHA_RANGE * farCoverage) * params.cloudScale).roundToInt().coerceIn(0, 255)
		val drop = if (isPortrait) {
			height * 0.22f
		} else {
			height * 0.18f
		}

		val deckHeight = if (isPortrait) {
			height * 0.34f
		} else {
			height * 0.30f
		}

		val geometry = cloudDrawGeometry.configure(
			width,
			deckHeight,
			cumulusOffset(width, params, timeSeconds, 0.004f + params.windFactor * 0.008f, 1f, 0.34f, CUMULUS_FAR_VIEWPORTS),
			cloudTop + drop,
			CUMULUS_FAR_VIEWPORTS,
			cloudSizeScale(params)
		)

		farCumulusDeck.draw(canvas, geometry, tint, alpha, shadow = castShadow, contrast = params.cloudContrastScale)
	}

	/** One broad support bank replaces several detached puffs near the partly-cloudy end of the clear-sky range without enabling the overcast ceiling. */
	private fun drawPartlyCloudBank(canvas: Canvas, width: Float, height: Float, params: SceneParams, timeSeconds: Float, coverage: Float) {
		if (!heroOvercastBankDelegate.isInitialized()) {
			return
		}

		val weight = unlerp(PARTLY_BANK_START_COVERAGE, PARTLY_BANK_FULL_COVERAGE, coverage)
		if (weight <= 0f) {
			return
		}

		val period = width * PARTLY_BANK_VIEWPORTS
		val surge = width * 0.004f * params.windFactor * params.windScale
		val drift = surge * (0.6f * sin(timeSeconds * 0.19f) + 0.4f * sin(timeSeconds * 0.47f))
		val offset = wrapOffset(timeSeconds * width * (0.0045f + params.windFactor * 0.009f) * params.windScale + drift * 0.60f - width * PARTLY_BANK_PHASE, period)
		val bankHeight = overcastBankHeight(width, height, PARTLY_BANK_VIEWPORTS, PARTLY_BANK_HEIGHT_SCALE)
		val tint = lerpColor(cumulusTint(params.dayPhase), skyGradientFor(params).topColor, PARTLY_BANK_HAZE)
		val alpha = (255f * PARTLY_BANK_ALPHA * weight * params.cloudScale).roundToInt().coerceIn(0, 255)

		heroOvercastBank.draw(
			canvas,
			cloudDrawGeometry.configure(width, bankHeight, offset, height * PARTLY_BANK_TOP, PARTLY_BANK_VIEWPORTS),
			tint,
			alpha,
			contrast = params.cloudContrastScale
		)
	}

	/**
	 * The near deck: full-size masses at the coverage the weather asks for.
	 * The steps share a noise field, so drawing the next one over the current at partial alpha grows each mass rather than dissolving it into a different sky.
	 */
	private fun drawNearCumulus(canvas: Canvas, width: Float, params: SceneParams, cloudTop: Float, state: NearCumulusState) {
		val tint = cumulusTint(params.dayPhase)
		val geometry = cloudDrawGeometry.configure(width, state.deckHeight, state.offset, cloudTop, sizeScale = cloudSizeScale(params))
		cumulusSteps[state.lower].value.draw(canvas, geometry, tint, state.alpha, contrast = params.cloudContrastScale)

		if (state.upper < cumulusSteps.size && state.growth > 0) {
			cumulusSteps[state.upper].value.draw(canvas, geometry, tint, state.growth, contrast = params.cloudContrastScale)
		}
	}

	private data class NearCumulusState(
		val deckHeight: Float,
		val offset: Float,
		val lower: Int,
		val upper: Int,
		val alpha: Int,
		val growth: Int
	)

	/** A deck's horizontal position: a steady drift at its own speed plus a shared gust, wrapped to that deck's own repeat span. */
	private fun cumulusOffset(width: Float, params: SceneParams, timeSeconds: Float, speed: Float, gust: Float, phase: Float, viewports: Float): Float {
		val surge = width * 0.004f * params.windFactor * params.windScale
		val drift = surge * (0.6f * sin(timeSeconds * 0.19f) + 0.4f * sin(timeSeconds * 0.47f))

		return wrapOffset(timeSeconds * width * speed * params.windScale + drift * gust - width * phase, width * viewports)
	}

	/** Cached low, elongated veils: fog texture without recognizable cloud silhouettes. */
	private fun buildFogVeilTile(canvas: Canvas, width: Float, height: Float, color: Int, alpha: Int, seed: Long, near: Boolean) {
		val blur = if (near) {
			height * 0.055f
		} else {
			height * 0.075f
		}

		val brush = Paint(Paint.ANTI_ALIAS_FLAG)
		brush.style = Paint.Style.FILL
		brush.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
		val bounds = RectF()
		val random = Random(seed)
		val count = if (near) {
			10
		} else {
			13
		}

		val horizontalSpan = min(width, height * 1.6f)

		repeat(count) {
			val centerX = random.nextFloat(width)
			val centerY = if (near) {
				height * random.nextFloat(0.42f, 0.90f)
			} else {
				height * random.nextFloat(0.10f, 0.64f)
			}

			val radiusX = if (near) {
				horizontalSpan * random.nextFloat(0.18f, 0.34f)
			} else {
				horizontalSpan * random.nextFloat(0.14f, 0.28f)
			}

			val radiusY = if (near) {
				height * random.nextFloat(0.04f, 0.085f)
			} else {
				height * random.nextFloat(0.03f, 0.07f)
			}

			brush.color = withAlpha(color, (alpha * random.nextFloat(0.55f, 1f)).roundToInt())

			wrapX(width, centerX, radiusX + blur * 1.5f) { x ->
				bounds.set(x - radiusX, centerY - radiusY, x + radiusX, centerY + radiusY)
				canvas.drawOval(bounds, brush)
			}
		}
	}

	private fun drawFogBase(canvas: Canvas, width: Float, height: Float, params: SceneParams) {
		val density = params.fogDensity.coerceIn(0f, 1f)
		val color = lighten(hazeColorFor(params.dayPhase), 0.10f)
		paint.style = Paint.Style.FILL
		paint.shader = LinearGradient(
			0f,
			0f,
			0f,
			height,
			intArrayOf(
				withAlpha(color, (12f * density).roundToInt()),
				withAlpha(color, (40f * density).roundToInt()),
				withAlpha(color, (105f * density).roundToInt())
			),
			floatArrayOf(0f, 0.48f, 1f),
			Shader.TileMode.CLAMP
		)

		canvas.drawRect(0f, 0f, width, height, paint)
		paint.shader = null
	}

	/** Two broad veil fields share one prevailing drift with parallax, while opacity and height breathe slowly enough to read as rolling fog rather than counter-scrolling smoke. */
	private fun drawFogDrift(canvas: Canvas, width: Float, height: Float, params: SceneParams, timeSeconds: Float) {
		val density = params.fogDensity.coerceIn(0f, 1f)
		val tileWidth = (width / TILE_DOWNSCALE).toInt()
		val tileHeight = (height / TILE_DOWNSCALE).toInt()
		val baseColor = hazeColorFor(params.dayPhase)

		val far = tile("fogFar", tileWidth, tileHeight) {
			buildFogVeilTile(it, tileWidth.toFloat(), tileHeight.toFloat(), lighten(baseColor, 0.12f), 84, 30L, near = false)
		}

		val near = tile("fogNear", tileWidth, tileHeight) {
			buildFogVeilTile(it, tileWidth.toFloat(), tileHeight.toFloat(), lighten(baseColor, 0.22f), 96, 31L, near = true)
		}

		val breath = 0.5f + 0.5f * (0.7f * sin(timeSeconds * 0.45f) + 0.3f * sin(timeSeconds * 1.13f))
		val rollAmplitude = height * 0.018f
		val roll = rollAmplitude * (0.7f * sin(timeSeconds * 0.25f) + 0.3f * sin(timeSeconds * 0.73f))
		val surge = width * 0.012f * gustFactor(timeSeconds, params.windFactor) * params.windScale
		val farOffset = wrapOffset(timeSeconds * width * (0.003f + params.windFactor * 0.005f) * params.windScale + surge * 0.35f - width * 0.22f, width)
		val nearOffset = wrapOffset(timeSeconds * width * (0.006f + params.windFactor * 0.010f) * params.windScale + surge * 0.75f - width * 0.63f, width)

		// A slow clearing envelope changes how much mist is present without changing either layer's velocity.
		val clearing = 0.90f + 0.10f * sin(timeSeconds * 0.043f)
		val farAlpha = ((72f + 52f * breath) * clearing * density).roundToInt()
		val nearAlpha = ((82f + 58f * (1f - breath)) * clearing * density).roundToInt()

		// Both ends are overscanned by the roll amplitude, so the roll never exposes a hard tile edge or bare gap.
		blitScrolled(canvas, far, farOffset, width, height + rollAmplitude * 2f, farAlpha, roll * 0.35f - rollAmplitude)
		blitScrolled(canvas, near, nearOffset, width, height + rollAmplitude * 2f, nearAlpha, -roll * 0.70f - rollAmplitude)
	}

	/**
	 * The shared wind signal: windFactor scaled by two incommensurate sines, so every wind-driven layer leans and eases together in gusts instead of holding one constant slant.
	 * Only amplitudes modulate — a frequency or velocity change multiplied by a large timeSeconds teleports whatever it drives.
	 */
	private fun gustFactor(timeSeconds: Float, windFactor: Float) = windFactor * (0.7f + 0.21f * sin(timeSeconds * 0.23f) + 0.09f * sin(timeSeconds * 0.67f))

	/** Positive modulo for scroll offsets, so a gust displacement can swing them briefly negative without breaking the wrap. */
	private fun wrapOffset(offset: Float, width: Float) = ((offset % width) + width) % width

	/**
	 * A cheap hash of particle index and fall cycle onto 0..1, so every particle re-enters on a fresh lane each time it wraps instead of re-falling one path forever.
	 * Pure arithmetic — no allocation, no RNG state — so particle motion stays a pure function of time.
	 */
	private fun laneFraction(index: Int, cycle: Int): Float {
		var mixed = index * 374_761_393 + cycle * 668_265_263
		mixed = (mixed xor (mixed ushr 13)) * 1_274_126_177
		mixed = mixed xor (mixed ushr 16)

		return (mixed ushr 8) / 16_777_216f
	}

	/** Precipitation density breathes ±15% over half-minute swells, so the fall reads as squalls instead of a constant static. */
	private fun squallFactor(timeSeconds: Float) = 0.85f + 0.15f * (0.6f * sin(timeSeconds * 0.21f) + 0.4f * sin(timeSeconds * 0.53f))

	private fun drawPrecipitation(canvas: Canvas, width: Float, height: Float, params: SceneParams, precipitation: Precipitation, timeSeconds: Float, flash: Float) {
		val count = precipitationDropCount(precipitation, params.precipitationScale, width, height)
		val squallCount = (count * squallFactor(timeSeconds)).roundToInt()
		val counts = ParticleCounts(count, squallCount)
		val heavy = precipitation.severity >= HEAVY_SEVERITY
		val gust = gustFactor(timeSeconds, params.windFactor)

		when (precipitation.kind) {
			PrecipitationKind.SNOW -> {
				val slant = snowSlant(gust, params.windScale)
				drawSnow(canvas, width, height, counts, slant, timeSeconds, heavy)
			}
			PrecipitationKind.SLEET -> {
				val streakSlant = ((0.1f + gust * 0.43f) * params.windScale).coerceAtMost(MAX_WIND_SLANT)
				val pelletSlant = snowSlant(gust, params.windScale)
				drawSleet(canvas, width, height, count, streakSlant, pelletSlant, timeSeconds, flash)
			}
			PrecipitationKind.RAIN -> {
				val slant = rainSlant(gust, params.windScale, precipitation.observed)
				drawRain(canvas, width, height, counts, slant, timeSeconds, precipitation.severity, flash)
			}
		}
	}

	/**
	 * Rain keeps the allocation-free batched drawLines path, but each streak is a faint motion trail with a shorter bright leading segment instead of two equally long strokes.
	 * Severity changes fall speed and motion-blur length across the forecast bands, while measured precipitation continuously influences the slant.
	 * Do not replace the tapered double-stroke with a per-frame BlurMaskFilter: blurring hundreds of segments that way previously froze rain scenes.
	 */
	private fun drawRain(canvas: Canvas, width: Float, height: Float, counts: ParticleCounts, slant: Float, timeSeconds: Float, severity: Float, flash: Float) {
		paint.style = Paint.Style.STROKE
		paint.strokeCap = Paint.Cap.ROUND

		val severityFactor = rainMotionFactor(severity)
		val speed = lerp(0.82f, 1.32f, severityFactor)
		val stretch = lerp(0.72f, 1.3f, severityFactor)
		val span = height + RAIN_WRAP_PAD * 2f
		val nearCount = counts.steadyCount / 2

		drawFarRain(canvas, width, height, counts.squallCount, span, slant, stretch, speed, timeSeconds, flash)
		drawNearRain(canvas, width, height, nearCount, span, slant, stretch, speed, timeSeconds, flash)
		drawCloseDrops(canvas, width, height, nearCount, span, slant, stretch, speed, timeSeconds, flash)

		paint.style = Paint.Style.FILL
	}

	/** Far layer: fine low-contrast trails with a short glint at the leading edge. */
	private fun drawFarRain(canvas: Canvas, width: Float, height: Float, squallCount: Int, span: Float, slant: Float, stretch: Float, speed: Float, timeSeconds: Float, flash: Float) {
		/*
		 * Each streak picks a fresh lane per fall cycle, so no drop re-falls one fixed path forever.
		 * Every layer seeds its own Random: per-particle constants must never depend on another layer's breathing count, or one ±1 tick reshuffles every draw after it and whole layers teleport.
		 */
		val farRandom = Random(PRECIP_SEED)
		val points = rainBuffer(squallCount * 4)

		repeat(squallCount) { i ->
			val length = farRandom.nextFloat(10f, 20f) * stretch
			val travel = farRandom.nextFloat(height) + timeSeconds * 650f * speed
			val cycle = (travel / span).toInt()
			val fall = travel % span
			val x = precipitationHorizontalPosition(laneFraction(i * 2, cycle), fall, slant, width)
			val y = fall - RAIN_WRAP_PAD

			points[i * 4] = x
			points[i * 4 + 1] = y
			points[i * 4 + 2] = x + length * slant
			points[i * 4 + 3] = y + length
		}

		paint.strokeWidth = 2.8f
		paint.color = Color.argb(gleam(12, flash), 205, 218, 238)
		canvas.drawLines(points, 0, squallCount * 4, paint)

		trimStreakTails(points, squallCount, 0.58f)
		paint.strokeWidth = 1.1f
		paint.color = Color.argb(gleam(46, flash), 218, 228, 242)
		canvas.drawLines(points, 0, squallCount * 4, paint)
	}

	/** Near layer: longer motion trails whose brighter leading segment stays thin enough to read as water rather than a glowing rod. */
	private fun drawNearRain(canvas: Canvas, width: Float, height: Float, nearCount: Int, span: Float, slant: Float, stretch: Float, speed: Float, timeSeconds: Float, flash: Float) {
		val nearRandom = Random(PRECIP_SEED + 1L)
		val points = rainBuffer(nearCount * 4)

		repeat(nearCount) { i ->
			val length = nearRandom.nextFloat(30f, 52f) * stretch
			val travel = nearRandom.nextFloat(height) + timeSeconds * 1150f * speed
			val cycle = (travel / span).toInt()
			val fall = travel % span
			val x = precipitationHorizontalPosition(laneFraction(i * 2 + 1, cycle), fall, slant, width)
			val y = fall - RAIN_WRAP_PAD

			points[i * 4] = x
			points[i * 4 + 1] = y
			points[i * 4 + 2] = x + length * slant
			points[i * 4 + 3] = y + length
		}

		drawNearPrecipitationStreaks(canvas, points, nearCount, flash)
	}

	/** Shared near-distance streak treatment for rain and sleet, so changing precipitation kind does not change stroke weight before the sleet pellets even appear. */
	private fun drawNearPrecipitationStreaks(canvas: Canvas, points: FloatArray, count: Int, flash: Float) {
		paint.strokeWidth = 3.4f
		paint.color = Color.argb(gleam(18, flash), 210, 221, 236)
		canvas.drawLines(points, 0, count * 4, paint)

		trimStreakTails(points, count, 0.52f)
		paint.strokeWidth = 1.5f
		paint.color = Color.argb(gleam(110, flash), 225, 234, 246)
		canvas.drawLines(points, 0, count * 4, paint)
	}

	/** A sparse foreground pass keeps a few drops distinct without turning them into thick white capsules. */
	private fun drawCloseDrops(canvas: Canvas, width: Float, height: Float, nearCount: Int, span: Float, slant: Float, stretch: Float, speed: Float, timeSeconds: Float, flash: Float) {
		val closeCount = nearCount / 12
		if (closeCount == 0) {
			return
		}

		val random = Random(PRECIP_SEED + 2L)
		val points = rainBuffer(closeCount * 4)

		repeat(closeCount) { i ->
			val length = random.nextFloat(46f, 72f) * stretch
			val travel = random.nextFloat(height) + timeSeconds * 1450f * speed
			val cycle = (travel / span).toInt()
			val fall = travel % span
			val x = precipitationHorizontalPosition(laneFraction(i + CLOSE_DROP_LANE_OFFSET, cycle), fall, slant, width)
			val y = fall - RAIN_WRAP_PAD

			points[i * 4] = x
			points[i * 4 + 1] = y
			points[i * 4 + 2] = x + length * slant
			points[i * 4 + 3] = y + length
		}

		paint.strokeWidth = 4.2f
		paint.color = Color.argb(gleam(24, flash), 216, 226, 240)
		canvas.drawLines(points, 0, closeCount * 4, paint)

		trimStreakTails(points, closeCount, 0.48f)
		paint.strokeWidth = 2.2f
		paint.color = Color.argb(gleam(170, flash), 230, 238, 248)
		canvas.drawLines(points, 0, closeCount * 4, paint)
	}

	/** Keeps the falling head fixed while shortening each segment from its trailing end, producing a cheap two-step motion blur without shaders or allocations. */
	private fun trimStreakTails(points: FloatArray, count: Int, fraction: Float) {
		repeat(count) { i ->
			val offset = i * 4
			points[offset] = lerp(points[offset], points[offset + 2], fraction)
			points[offset + 1] = lerp(points[offset + 1], points[offset + 3], fraction)
		}
	}

	/**
	 * Two depth layers: small dim flakes drifting far away, big bright ones swaying up close — so it reads as snowfall, not stars.
	 * [heavy] means a blizzard: bigger, faster flakes leaning hard on the wind.
	 * Flakes are blits of the pre-blurred soft-dot sprites, so they stay smooth in motion instead of shimmering as hard-edged discs.
	 */
	private fun drawSnow(canvas: Canvas, width: Float, height: Float, counts: ParticleCounts, slant: Float, timeSeconds: Float, heavy: Boolean) {
		val speed = if (heavy) {
			1.5f
		} else {
			1f
		}

		val size = if (heavy) {
			1.35f
		} else {
			1f
		}

		/*
		 * Lane and sway phase re-hash every wrap, and the wrap spans a pad past both edges so soft dots never pop at the border.
		 * Per-layer Randoms and a steady near count keep flakes from teleporting when the squall factor ticks.
		 */
		val span = height + FLAKE_WRAP_PAD * 2f
		val farRandom = Random(PRECIP_SEED)

		repeat(counts.squallCount / 2) { i ->
			val travel = farRandom.nextFloat(height) + timeSeconds * 70f * speed
			val cycle = (travel / span).toInt()
			val fall = travel % span
			val x = precipitationHorizontalPosition(laneFraction(i * 2, cycle), fall, slant, width)
			val phase = laneFraction(i * 2, cycle + SWAY_PHASE_SALT) * TAU
			val y = fall - FLAKE_WRAP_PAD
			val sway = sin(timeSeconds * 0.7f + phase) * (width * 0.02f)
			val radius = farRandom.nextFloat(1.2f, 2.6f) * size

			drawSoftDot(canvas, farFlakeSprite, x + sway, y, radius)
		}

		val nearRandom = Random(PRECIP_SEED + 1L)

		repeat(counts.steadyCount - counts.steadyCount / 2) { i ->
			val travel = nearRandom.nextFloat(height) + timeSeconds * 165f * speed
			val cycle = (travel / span).toInt()
			val fall = travel % span
			val x = precipitationHorizontalPosition(laneFraction(i * 2 + 1, cycle), fall, slant, width)
			val phase = laneFraction(i * 2 + 1, cycle + SWAY_PHASE_SALT) * TAU
			val y = fall - FLAKE_WRAP_PAD
			val sway = sin(timeSeconds * 1.1f + phase) * (width * 0.045f)
			val radius = nearRandom.nextFloat(2.6f, 5.8f) * size

			drawSoftDot(canvas, nearFlakeSprite, x + sway, y, radius)
		}
	}

	/** Sleet: a wintry rain/snow mix — short, sharp icy streaks interleaved with small tumbling pellets. */
	private fun drawSleet(canvas: Canvas, width: Float, height: Float, count: Int, streakSlant: Float, pelletSlant: Float, timeSeconds: Float, flash: Float) {
		/*
		 * Streaks: shorter and more vertical than rain, tinted cold, falling slower than a downpour.
		 * Sleet skips squall breathing entirely; both its layers are bright enough that a mid-fall pop would show.
		 */
		paint.style = Paint.Style.STROKE
		paint.strokeCap = Paint.Cap.ROUND

		val streakCount = count * 2 / 3
		val streakRandom = Random(PRECIP_SEED)
		val points = rainBuffer(streakCount * 4)

		val span = height + 120f

		repeat(streakCount) { i ->
			val length = streakRandom.nextFloat(14f, 26f)
			val travel = streakRandom.nextFloat(height) + timeSeconds * 820f
			val cycle = (travel / span).toInt()
			val fall = travel % span
			val x = precipitationHorizontalPosition(laneFraction(i * 2, cycle), fall, streakSlant, width)
			val y = fall - 60f

			points[i * 4] = x
			points[i * 4 + 1] = y
			points[i * 4 + 2] = x + length * streakSlant
			points[i * 4 + 3] = y + length
		}

		drawNearPrecipitationStreaks(canvas, points, streakCount, flash)

		paint.style = Paint.Style.FILL

		// Pellets: small icy grains tumbling between the streaks — faster and stiffer than snowflakes.
		val pelletCount = count / 2
		val pelletRandom = Random(PRECIP_SEED + 1L)
		val pelletSpan = height + FLAKE_WRAP_PAD * 2f

		repeat(pelletCount) { i ->
			val travel = pelletRandom.nextFloat(height) + timeSeconds * 240f
			val cycle = (travel / pelletSpan).toInt()
			val fall = travel % pelletSpan
			val x = precipitationHorizontalPosition(laneFraction(i * 2 + 1, cycle), fall, pelletSlant, width)
			val phase = laneFraction(i * 2 + 1, cycle + SWAY_PHASE_SALT) * TAU
			val y = fall - FLAKE_WRAP_PAD
			val sway = sin(timeSeconds * 1.6f + phase) * (width * 0.012f)
			val radius = pelletRandom.nextFloat(1.2f, 2.8f)

			drawSoftDot(canvas, pelletSprite, x + sway, y, radius)
		}
	}

	/**
	 * Advances the lightning schedule for this frame.
	 * Strikes live in fixed slots but fire at a per-slot random moment, sometimes twice, sometimes not at all — so the storm never keeps a beat.
	 * Roughly 60% are sheet strikes: no bolt, just the deck lighting up from inside.
	 * Everything derives from [timeSeconds] and the slot seed, so the schedule stays a pure function of time.
	 */
	private fun updateLightning(timeSeconds: Float) {
		flashWash = 0f
		flashBolt = 0f
		flashSlot = (timeSeconds / STRIKE_SLOT_SECONDS).toInt()

		val random = Random(BOLT_SEED + flashSlot)
		val quiet = random.nextFloat() < 0.12f
		if (quiet) {
			return
		}

		flashSheet = random.nextFloat() < 0.6f
		val strikeWindow = STRIKE_SLOT_SECONDS - SHEET_DURATION - ECHO_DELAY - AFTERGLOW_SECONDS
		val strikeAt = random.nextFloat(strikeWindow)
		val local = timeSeconds - flashSlot * STRIKE_SLOT_SECONDS - strikeAt

		val strike = coreFlash(local, flashSheet)
		val echoes = random.nextFloat() < 0.25f
		val echo = if (echoes) {
			coreFlash(local - ECHO_DELAY, flashSheet) * 0.6f
		} else {
			0f
		}

		flashWash = maxOf(strike, echo, afterglow(local, flashSheet))
		flashBolt = if (flashSheet) {
			0f
		} else {
			maxOf(strike, echo)
		}
	}

	/** The flash's brightness through its core lifetime: a decaying hard flicker for bolts, a slower swell for sheet strikes. */
	private fun coreFlash(local: Float, sheet: Boolean): Float {
		val duration = if (sheet) {
			SHEET_DURATION
		} else {
			FLASH_DURATION
		}

		if (local !in 0f..duration) {
			return 0f
		}

		val t = local / duration

		return if (sheet) {
			(sin(t * PI_F) * (0.85f + 0.15f * sin(t * 31f))).coerceIn(0f, 1f)
		} else {
			((1f - t) * (0.7f + 0.3f * sin(t * 40f))).coerceIn(0f, 1f)
		}
	}

	/** A faint lingering wash after the flash dies, so a strike fades out of the sky instead of snapping off. */
	private fun afterglow(local: Float, sheet: Boolean): Float {
		val duration = if (sheet) {
			SHEET_DURATION
		} else {
			FLASH_DURATION
		}

		val tail = (local - duration) / AFTERGLOW_SECONDS

		return if (tail in 0f..1f) {
			AFTERGLOW_PEAK * (1f - tail)
		} else {
			0f
		}
	}

	/** Streak alpha lifted while lightning washes the scene — rain genuinely glints in a flash. */
	private fun gleam(alpha: Int, flash: Float) = (alpha * (1f + 0.8f * flash)).roundToInt().coerceAtMost(255)

	private fun drawLightning(canvas: Canvas, width: Float, height: Float) {
		if (flashWash <= 0.01f) {
			return
		}

		// Geometry gets its own seed stride so it never replays the schedule draws consumed in [updateLightning].
		val random = Random((BOLT_SEED + flashSlot) * GEOMETRY_SEED_STRIDE)
		paint.style = Paint.Style.FILL

		if (flashSheet) {
			// The distant strike: a soft wash plus a broad glow low in the deck, as if a cloud lit up from within.
			paint.color = Color.argb((30f * flashWash).roundToInt(), 236, 238, 255)
			canvas.drawRect(0f, 0f, width, height, paint)

			val glow = tile("sheetGlow", HALO_SPRITE_SIZE, HALO_SPRITE_SIZE) { buildHaloSprite(it, Color.rgb(226, 228, 252)) }
			val glowX = width * random.nextFloat(0.2f, 0.8f)
			val glowY = height * random.nextFloat(0.1f, 0.25f)
			val glowRadius = width * random.nextFloat(0.5f, 0.75f)
			blitSprite(canvas, glow, glowX, glowY, glowRadius, (170f * flashWash).roundToInt())

			return
		}

		paint.color = Color.argb((95f * flashWash).roundToInt(), 255, 255, 255)
		canvas.drawRect(0f, 0f, width, height, paint)

		if (flashBolt <= 0.01f) {
			return
		}

		buildBolt(width, height, random)

		paint.style = Paint.Style.STROKE
		paint.strokeCap = Paint.Cap.ROUND
		paint.strokeJoin = Paint.Join.ROUND
		paint.strokeWidth = 14f
		paint.color = Color.argb((110f * flashBolt).roundToInt(), 255, 244, 200)
		canvas.drawPath(boltPath, paint)

		paint.strokeWidth = 8f
		paint.color = Color.argb((80f * flashBolt).roundToInt(), 255, 244, 200)
		canvas.drawPath(forkPath, paint)

		paint.strokeWidth = 5f
		paint.color = Color.argb((255f * flashBolt).roundToInt(), 255, 253, 235)
		canvas.drawPath(boltPath, paint)

		paint.strokeWidth = 3f
		paint.color = Color.argb((210f * flashBolt).roundToInt(), 255, 253, 235)
		canvas.drawPath(forkPath, paint)

		paint.style = Paint.Style.FILL
	}

	/** Regenerates [boltPath] and its thinner [forkPath] branch from the slot's geometry seed — the mid-bolt fork is what makes the strike read as lightning rather than a zigzag line. */
	private fun buildBolt(width: Float, height: Float, random: Random) {
		var x = width * random.nextFloat(0.3f, 0.7f)
		var y = 0f
		boltPath.reset()
		boltPath.moveTo(x, y)

		val segment = height * 0.62f / BOLT_STEPS
		var forkX = x
		var forkY = y

		repeat(BOLT_STEPS) { step ->
			x += random.nextFloat(-width * 0.09f, width * 0.09f)
			y += segment

			boltPath.lineTo(x, y)

			if (step == BOLT_STEPS / 2 - 1) {
				forkX = x
				forkY = y
			}
		}

		forkPath.reset()
		forkPath.moveTo(forkX, forkY)

		val forkDirection = if (random.nextFloat() < 0.5f) {
			-1f
		} else {
			1f
		}

		repeat(3) {
			forkX += forkDirection * random.nextFloat(0.04f, 0.12f) * width
			forkY += segment * 0.7f
			forkPath.lineTo(forkX, forkY)
		}
	}

	private fun drawHaze(canvas: Canvas, width: Float, height: Float, params: SceneParams) {
		val haze = hazeColorFor(params.dayPhase)
		paint.style = Paint.Style.FILL
		paint.shader = LinearGradient(0f, height * 0.55f, 0f, height, withAlpha(haze, 0), withAlpha(haze, 120), Shader.TileMode.CLAMP)
		canvas.drawRect(0f, height * 0.55f, width, height, paint)

		paint.shader = null
	}

	private fun drawVignette(canvas: Canvas, width: Float, height: Float) {
		val radius = maxOf(width, height) * 0.72f
		paint.style = Paint.Style.FILL
		paint.shader = RadialGradient(
			width * 0.5f,
			height * 0.42f,
			radius,
			intArrayOf(
				Color.argb(0, 0, 0, 0),
				Color.argb(0, 0, 0, 0),
				Color.argb(8, 0, 0, 0),
				Color.argb(22, 0, 0, 0),
				Color.argb(42, 0, 0, 0),
				Color.argb(60, 0, 0, 0)
			),
			floatArrayOf(0f, 0.4f, 0.55f, 0.7f, 0.85f, 1f),
			Shader.TileMode.CLAMP
		)

		canvas.drawRect(0f, 0f, width, height, paint)
		paint.shader = null
	}

	/** Draws a (usually downscaled) tile stretched to [destWidth] x [destHeight], twice, so it wraps seamlessly while scrolling. */
	private fun blitScrolled(canvas: Canvas, bitmap: Bitmap, offset: Float, destWidth: Float, destHeight: Float, alpha: Int, yOffset: Float = 0f) {
		blitPaint.alpha = alpha.coerceIn(0, 255)
		blitDest.set(offset, yOffset, offset + destWidth, yOffset + destHeight)
		canvas.drawBitmap(bitmap, null, blitDest, blitPaint)

		blitDest.offset(-destWidth, 0f)
		canvas.drawBitmap(bitmap, null, blitDest, blitPaint)
	}

	/** Returns a cached tile by name, building it once via [build] when the scene changes. */
	private inline fun tile(name: String, width: Int, height: Int, build: (Canvas) -> Unit): Bitmap {
		tiles[name]?.let { return it }

		val bitmap = createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1))
		build(Canvas(bitmap))
		tiles[name] = bitmap

		return bitmap
	}

	/** A soft round dot rendered once: a solid core out to [DOT_CORE_STOP] of the radius, melting into a fully transparent rim. */
	private fun softDotSprite(color: Int, alpha: Int): Bitmap {
		val center = SPRITE_SIZE / 2f

		val brush = Paint(Paint.ANTI_ALIAS_FLAG)
		brush.shader = RadialGradient(center, center, center, intArrayOf(withAlpha(color, alpha), withAlpha(color, alpha), withAlpha(color, 0)), floatArrayOf(0f, DOT_CORE_STOP, 1f), Shader.TileMode.CLAMP)

		val bitmap = createBitmap(SPRITE_SIZE, SPRITE_SIZE)
		val canvas = Canvas(bitmap)
		canvas.drawCircle(center, center, center, brush)

		return bitmap
	}

	/** Blits a soft-dot sprite scaled so its solid core spans [coreRadius]; the halo extends past that and fades to nothing. */
	private fun drawSoftDot(canvas: Canvas, sprite: Bitmap, x: Float, y: Float, coreRadius: Float) {
		val reach = coreRadius / DOT_CORE_STOP
		spriteDest.set(x - reach, y - reach, x + reach, y + reach)
		canvas.drawBitmap(sprite, null, spriteDest, spritePaint)
	}

	/** Blits [sprite] as a square of [radius] around a center at [alpha], restoring the shared paint's opacity afterward. */
	private fun blitSprite(canvas: Canvas, sprite: Bitmap, centerX: Float, centerY: Float, radius: Float, alpha: Int) {
		spritePaint.alpha = alpha.coerceIn(0, 255)
		spriteDest.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
		canvas.drawBitmap(sprite, null, spriteDest, spritePaint)
		spritePaint.alpha = 255
	}

	/** [blitSprite] through [glowPaint], so the sprite adds light to the sky underneath instead of painting over it. */
	private fun blitGlow(canvas: Canvas, sprite: Bitmap, centerX: Float, centerY: Float, radius: Float, alpha: Int) {
		glowPaint.alpha = alpha.coerceIn(0, 255)
		spriteDest.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
		canvas.drawBitmap(sprite, null, spriteDest, glowPaint)
		glowPaint.alpha = 255
	}

	/** [blitGlow] for sprites that are not square — the anamorphic streak is far wider than it is tall. */
	private fun blitGlowRect(canvas: Canvas, sprite: Bitmap, centerX: Float, centerY: Float, halfWidth: Float, halfHeight: Float, alpha: Int) {
		glowPaint.alpha = alpha.coerceIn(0, 255)
		spriteDest.set(centerX - halfWidth, centerY - halfHeight, centerX + halfWidth, centerY + halfHeight)
		canvas.drawBitmap(sprite, null, spriteDest, glowPaint)
		glowPaint.alpha = 255
	}

	/** The sun/moon glow rasterized once per scene: a radial falloff from the full-alpha core color, blitted at the breathing size each frame. */
	private fun buildHaloSprite(canvas: Canvas, core: Int) {
		val center = HALO_SPRITE_SIZE / 2f

		val brush = Paint(Paint.ANTI_ALIAS_FLAG)
		brush.shader = RadialGradient(center, center, center, intArrayOf(core, withAlpha(core, 0)), floatArrayOf(0.25f, 1f), Shader.TileMode.CLAMP)
		canvas.drawCircle(center, center, center, brush)
	}

	/**
	 * Cached atmospheric sunlight built from offset soft lobes rather than one radial disc.
	 * Their overlap keeps the source warm and recognizable while giving the outer falloff no stable circular limb.
	 */
	private fun buildSunAtmosphereSprite(canvas: Canvas, core: Int) {
		val size = HALO_SPRITE_SIZE.toFloat()
		val brush = Paint(Paint.ANTI_ALIAS_FLAG)
		val warm = lighten(core, SUN_ATMOSPHERE_LIFT)

		drawAtmosphereLobe(canvas, brush, warm, size * 0.48f, size * 0.46f, size * 0.47f, 205)
		drawAtmosphereLobe(canvas, brush, warm, size * 0.30f, size * 0.54f, size * 0.34f, 95)
		drawAtmosphereLobe(canvas, brush, warm, size * 0.69f, size * 0.38f, size * 0.30f, 80)
		drawAtmosphereLobe(canvas, brush, warm, size * 0.56f, size * 0.70f, size * 0.27f, 58)
		drawAtmosphereLobe(canvas, brush, warm, size * 0.40f, size * 0.22f, size * 0.23f, 45)
	}

	private fun drawAtmosphereLobe(canvas: Canvas, brush: Paint, color: Int, centerX: Float, centerY: Float, radius: Float, peakAlpha: Int) {
		val middleAlpha = (peakAlpha * SUN_ATMOSPHERE_MIDDLE_ALPHA).roundToInt()

		brush.shader = RadialGradient(
			centerX,
			centerY,
			radius,
			intArrayOf(withAlpha(color, peakAlpha), withAlpha(color, middleAlpha), withAlpha(color, 0)),
			floatArrayOf(0f, SUN_ATMOSPHERE_MIDDLE_STOP, 1f),
			Shader.TileMode.CLAMP
		)

		canvas.drawCircle(centerX, centerY, radius, brush)
	}

	/** Crepuscular shafts derived from the moving cloud silhouette rather than a canned fan. */
	private fun drawSunLightShafts(canvas: Canvas, sun: SunRenderContext, radius: Float, core: Int) {
		if (!sampleSunCloudEdgeProfile(sun, radius)) {
			return
		}

		val baseAngle = sunShaftBaseAngle(sun)
		val baseReach = sun.height * SUN_SHAFT_REACH_FRACTION
		val tint = lighten(core, 0.82f)
		glowPaint.shader = null
		glowPaint.style = Paint.Style.FILL
		glowPaint.alpha = 255
		val drawn = drawSunShaftPeaks(canvas, sun, radius, baseAngle, baseReach, tint)
		if (drawn == 0) {
			drawFallbackSunShaft(canvas, sun, radius, baseAngle, baseReach, tint)
		}
	}

	private fun sunShaftBaseAngle(sun: SunRenderContext): Float {
		val towardLowerSky = atan2(sun.height - sun.centerY, sun.width * 0.5f - sun.centerX)
		val timeLean = when (sun.params.dayPhase) {
			DayPhase.DAWN -> -0.14f + sun.params.celestialProgress * 0.08f
			DayPhase.DAY -> (sun.params.celestialProgress - 0.5f) * 0.12f
			DayPhase.DUSK -> 0.06f + sun.params.celestialProgress * 0.08f
			DayPhase.NIGHT -> 0f
		}

		return towardLowerSky + timeLean
	}

	private fun drawSunShaftPeaks(canvas: Canvas, sun: SunRenderContext, radius: Float, baseAngle: Float, baseReach: Float, tint: Int): Int {
		var drawn = 0
		var lastPeak = -SUN_SHAFT_MIN_PEAK_GAP
		for (index in 1 until SUN_SHAFT_PROFILE_SAMPLES - 1) {
			val energy = sunCloudEdgeEnergy[index]
			if (energy < SUN_SHAFT_EDGE_THRESHOLD || index - lastPeak < SUN_SHAFT_MIN_PEAK_GAP) {
				continue
			}

			val leftEnergy = sunCloudEdgeEnergy[index - 1]
			val rightEnergy = sunCloudEdgeEnergy[index + 1]
			if (!isSunShaftLocalPeak(energy, leftEnergy, rightEnergy)) {
				continue
			}

			val refinedIndex = refinedSunShaftIndex(index, leftEnergy, energy, rightEnergy)
			drawSunShaft(canvas, sun, radius, baseAngle, baseReach, tint, refinedIndex, energy)
			drawn++
			lastPeak = index
			if (drawn >= SUN_SHAFT_MAX_RAYS) {
				break
			}
		}

		return drawn
	}

	private fun isSunShaftLocalPeak(energy: Float, leftEnergy: Float, rightEnergy: Float) =
		energy >= leftEnergy && energy >= rightEnergy && (energy > leftEnergy || energy > rightEnergy)

	private fun refinedSunShaftIndex(index: Int, leftEnergy: Float, energy: Float, rightEnergy: Float): Float {
		val weight = leftEnergy + energy + rightEnergy
		if (weight <= 0.0001f) {
			return index.toFloat()
		}

		return ((index - 1) * leftEnergy + index * energy + (index + 1) * rightEnergy) / weight
	}

	private fun drawFallbackSunShaft(canvas: Canvas, sun: SunRenderContext, radius: Float, baseAngle: Float, baseReach: Float, tint: Int) {
		var totalEnergy = 0f
		var weightedIndex = 0f
		var strongestEnergy = 0f
		for (index in sunCloudEdgeEnergy.indices) {
			val energy = sunCloudEdgeEnergy[index]
			totalEnergy += energy
			weightedIndex += index * energy
			strongestEnergy = maxOf(strongestEnergy, energy)
		}

		if (strongestEnergy >= SUN_SHAFT_EDGE_THRESHOLD && totalEnergy > 0.0001f) {
			drawSunShaft(canvas, sun, radius, baseAngle, baseReach, tint, weightedIndex / totalEnergy, strongestEnergy)
		}
	}

	private fun drawSunShaft(canvas: Canvas, sun: SunRenderContext, radius: Float, baseAngle: Float, baseReach: Float, tint: Int, refinedIndex: Float, energy: Float) {
		val normalizedX = -1f + 2f * refinedIndex / (SUN_SHAFT_PROFILE_SAMPLES - 1f)
		val strength = ((energy - SUN_SHAFT_EDGE_THRESHOLD) / (1f - SUN_SHAFT_EDGE_THRESHOLD)).coerceIn(0f, 1f).pow(0.72f)
		val alpha = sunAlpha(SUN_SHAFT_MAX_ALPHA * strength, sun.visibility).coerceAtMost(SUN_SHAFT_MAX_ALPHA)
		if (alpha <= 1) {
			return
		}

		val angle = baseAngle + normalizedX * SUN_SHAFT_DIVERGENCE
		val directionX = cos(angle)
		val directionY = sin(angle)
		val sampleFloor = refinedIndex.toInt().coerceIn(0, SUN_SHAFT_PROFILE_SAMPLES - 1)
		val sampleCeiling = (sampleFloor + 1).coerceAtMost(SUN_SHAFT_PROFILE_SAMPLES - 1)
		val sampleBlend = (refinedIndex - sampleFloor).coerceIn(0f, 1f)
		val upperOpacity = lerp(sunCloudUpperSamples[sampleFloor], sunCloudUpperSamples[sampleCeiling], sampleBlend)
		val lowerOpacity = lerp(sunCloudLowerSamples[sampleFloor], sunCloudLowerSamples[sampleCeiling], sampleBlend)
		val verticalBias = (lowerOpacity - upperOpacity).coerceIn(-1f, 1f)
		val variation = 0.88f + 0.12f * (0.5f + 0.5f * sin(refinedIndex * 1.73f + sun.params.celestialProgress * 2.4f))
		sunShaftGeometry.originX = sun.centerX + normalizedX * radius * SUN_SHAFT_ORIGIN_SPAN
		sunShaftGeometry.originY = sun.centerY + verticalBias * radius * SUN_SHAFT_ORIGIN_VERTICAL_SPAN
		sunShaftGeometry.directionX = directionX
		sunShaftGeometry.directionY = directionY
		sunShaftGeometry.normalX = -directionY
		sunShaftGeometry.normalY = directionX
		sunShaftGeometry.outerReach = baseReach * (0.52f + 0.34f * strength) * variation
		sunShaftGeometry.innerReach = radius * (0.24f + 0.06f * abs(normalizedX))
		sunShaftGeometry.innerHalfWidth = radius * (0.024f + 0.016f * strength)
		sunShaftGeometry.outerHalfWidth = sunShaftGeometry.innerHalfWidth * (SUN_SHAFT_END_WIDTH_MULTIPLIER + SUN_SHAFT_END_WIDTH_RANGE * (1f - strength))
		sunShaftGeometry.tint = tint
		sunShaftGeometry.warpPhase = refinedIndex * 0.83f + sun.params.celestialProgress * 2.1f
		drawSunShaftWedge(canvas, sunShaftGeometry, maxOf(1, alpha / SUN_SHAFT_FEATHER_ALPHA_DIVISOR), SUN_SHAFT_FEATHER_WIDTH)
		drawSunShaftWedge(canvas, sunShaftGeometry, alpha, 1f)
	}

	private fun sampleSunCloudEdgeProfile(sun: SunRenderContext, radius: Float): Boolean {
		if (!canSampleSunCloudProfile(sun.params)) {
			return false
		}

		val coverage = ((effectiveCloudiness(sun.params) - SCATTERED_CLOUD_FLOOR) / (CLOUD_DECK_THRESHOLD - SCATTERED_CLOUD_FLOOR)).coerceIn(0f, 1f)
		val cloudTop = scatteredCloudTop(sun.width, sun.height, sun.params)
		val offset = cumulusOffset(sun.width, sun.params, sun.timeSeconds, 0.008f + sun.params.windFactor * 0.016f, 1.5f, 0.78f, CLOUD_TEXTURE_VIEWPORTS)
		val deckHeight = if (sun.width < sun.height) sun.height * 0.46f else sun.height * 0.40f
		val step = coverage * (cumulusSteps.size - 1)
		val lowerIndex = step.toInt().coerceAtMost(cumulusSteps.size - 1)
		val blend = step - lowerIndex
		val lowerAlpha = (CUMULUS_NEAR_ALPHA * sun.params.cloudScale).roundToInt().coerceIn(0, 255)
		val upperIndex = lowerIndex + 1
		val upperAlpha = sunCloudUpperAlpha(upperIndex, blend, sun.params.cloudScale)
		val sizeScale = cloudSizeScale(sun.params)
		val lowerSampler = cumulusSteps[lowerIndex].value.opacitySampler(sun.width, deckHeight, offset, cloudTop, lowerAlpha, sizeScale) ?: return false
		val upperSampler = if (upperAlpha > 0 && upperIndex < cumulusSteps.size) cumulusSteps[upperIndex].value.opacitySampler(sun.width, deckHeight, offset, cloudTop, upperAlpha, sizeScale) else null
		val strongestOpacity = sampleSunCloudRows(sun, radius, lowerSampler, upperSampler)
		val strongestEdge = computeSunCloudEdgeEnergy()
		return strongestOpacity > SUN_SHAFT_MIN_OBSTRUCTION && strongestEdge >= SUN_SHAFT_EDGE_THRESHOLD
	}

	private fun canSampleSunCloudProfile(params: SceneParams) =
		params.precipitation == null && effectiveCloudiness(params) > SCATTERED_CLOUD_FLOOR && effectiveCloudiness(params) <= CLOUD_DECK_THRESHOLD && params.cloudScale > 0f

	private fun sunCloudUpperAlpha(upperIndex: Int, blend: Float, cloudScale: Float): Int {
		if (upperIndex >= cumulusSteps.size || blend < CUMULUS_BLEND_FLOOR) {
			return 0
		}

		return (CUMULUS_NEAR_ALPHA * blend * cloudScale).roundToInt().coerceIn(0, 255)
	}

	private fun sampleSunCloudRows(sun: SunRenderContext, radius: Float, lowerSampler: CloudLayer.OpacitySampler, upperSampler: CloudLayer.OpacitySampler?): Float {
		val sampleSpan = radius * SUN_SHAFT_SAMPLE_SPAN
		val upperY = sun.centerY - radius * SUN_SHAFT_SAMPLE_VERTICAL_OFFSET
		val lowerY = sun.centerY + radius * SUN_SHAFT_SAMPLE_VERTICAL_OFFSET
		var strongestOpacity = 0f
		for (index in 0 until SUN_SHAFT_PROFILE_SAMPLES) {
			val t = index / (SUN_SHAFT_PROFILE_SAMPLES - 1f)
			val x = sun.centerX - sampleSpan + sampleSpan * 2f * t
			val upperOpacity = sampleSunCloudOpacity(lowerSampler, upperSampler, x, upperY)
			val lowerOpacity = sampleSunCloudOpacity(lowerSampler, upperSampler, x, lowerY)
			sunCloudUpperSamples[index] = upperOpacity
			sunCloudLowerSamples[index] = lowerOpacity
			strongestOpacity = maxOf(strongestOpacity, upperOpacity, lowerOpacity)
		}

		return strongestOpacity
	}

	private fun sampleSunCloudOpacity(lowerSampler: CloudLayer.OpacitySampler, upperSampler: CloudLayer.OpacitySampler?, x: Float, y: Float): Float {
		val lowerOpacity = lowerSampler.opacityAt(x, y)
		val upperOpacity = upperSampler?.opacityAt(x, y) ?: 0f
		return 1f - (1f - lowerOpacity) * (1f - upperOpacity)
	}

	private fun computeSunCloudEdgeEnergy(): Float {
		var strongestEdge = 0f
		for (index in 0 until SUN_SHAFT_PROFILE_SAMPLES) {
			val previous = (index - 1).coerceAtLeast(0)
			val next = (index + 1).coerceAtMost(SUN_SHAFT_PROFILE_SAMPLES - 1)
			val upper = sunCloudUpperSamples[index]
			val lower = sunCloudLowerSamples[index]
			val verticalContrast = abs(lower - upper)
			val upperHorizontalContrast = abs(sunCloudUpperSamples[next] - sunCloudUpperSamples[previous]) * 0.5f
			val lowerHorizontalContrast = abs(sunCloudLowerSamples[next] - sunCloudLowerSamples[previous]) * 0.5f
			val silhouetteContrast = maxOf(verticalContrast * SUN_SHAFT_VERTICAL_EDGE_WEIGHT, upperHorizontalContrast, lowerHorizontalContrast)
			val localOpacity = maxOf(upper, lower)
			val localTransmission = 1f - minOf(upper, lower)
			val energy = silhouetteContrast * (0.55f + 0.45f * localOpacity) * (0.65f + 0.35f * localTransmission)
			sunCloudEdgeEnergy[index] = energy
			strongestEdge = maxOf(strongestEdge, energy)
		}

		return strongestEdge
	}

	private fun drawSunShaftWedge(canvas: Canvas, shaft: SunShaftGeometry, alpha: Int, widthScale: Float) {
		val reachSpan = shaft.outerReach - shaft.innerReach
		if (reachSpan <= 0f || alpha <= 0) {
			return
		}

		for (segment in 0 until SUN_SHAFT_SEGMENTS) {
			val t0 = segment / SUN_SHAFT_SEGMENTS.toFloat()
			val t1 = (segment + 1) / SUN_SHAFT_SEGMENTS.toFloat()
			drawSunShaftSegment(canvas, shaft, alpha, widthScale, reachSpan, t0, t1)
		}
	}

	private fun drawSunShaftSegment(canvas: Canvas, shaft: SunShaftGeometry, alpha: Int, widthScale: Float, reachSpan: Float, t0: Float, t1: Float) {
		val midpoint = (t0 + t1) * 0.5f
		val segmentAlpha = (alpha * (1f - midpoint).pow(SUN_SHAFT_FADE_POWER)).roundToInt()
		if (segmentAlpha <= 0) {
			return
		}

		val reach0 = shaft.innerReach + reachSpan * t0
		val reach1 = shaft.innerReach + reachSpan * t1
		val width0 = lerp(shaft.innerHalfWidth, shaft.outerHalfWidth, t0.pow(SUN_SHAFT_WIDTH_EASE)) * widthScale
		val width1 = lerp(shaft.innerHalfWidth, shaft.outerHalfWidth, t1.pow(SUN_SHAFT_WIDTH_EASE)) * widthScale
		val centerWarp0 = sin(shaft.warpPhase + t0 * SUN_SHAFT_WARP_FREQUENCY) * width0 * SUN_SHAFT_CENTER_WARP
		val centerWarp1 = sin(shaft.warpPhase + t1 * SUN_SHAFT_WARP_FREQUENCY) * width1 * SUN_SHAFT_CENTER_WARP
		val leftScale0 = 1f + sin(shaft.warpPhase * 1.37f + t0 * SUN_SHAFT_EDGE_WARP_FREQUENCY) * SUN_SHAFT_EDGE_WARP
		val leftScale1 = 1f + sin(shaft.warpPhase * 1.37f + t1 * SUN_SHAFT_EDGE_WARP_FREQUENCY) * SUN_SHAFT_EDGE_WARP
		val rightScale0 = 1f + sin(shaft.warpPhase * 1.91f + 1.2f + t0 * SUN_SHAFT_EDGE_WARP_FREQUENCY) * SUN_SHAFT_EDGE_WARP
		val rightScale1 = 1f + sin(shaft.warpPhase * 1.91f + 1.2f + t1 * SUN_SHAFT_EDGE_WARP_FREQUENCY) * SUN_SHAFT_EDGE_WARP
		val center0X = shaft.originX + shaft.directionX * reach0 + shaft.normalX * centerWarp0
		val center0Y = shaft.originY + shaft.directionY * reach0 + shaft.normalY * centerWarp0
		val center1X = shaft.originX + shaft.directionX * reach1 + shaft.normalX * centerWarp1
		val center1Y = shaft.originY + shaft.directionY * reach1 + shaft.normalY * centerWarp1

		lightShaftPath.rewind()
		lightShaftPath.moveTo(center0X + shaft.normalX * width0 * leftScale0, center0Y + shaft.normalY * width0 * leftScale0)
		lightShaftPath.lineTo(center1X + shaft.normalX * width1 * leftScale1, center1Y + shaft.normalY * width1 * leftScale1)
		lightShaftPath.lineTo(center1X - shaft.normalX * width1 * rightScale1, center1Y - shaft.normalY * width1 * rightScale1)
		lightShaftPath.lineTo(center0X - shaft.normalX * width0 * rightScale0, center0Y - shaft.normalY * width0 * rightScale0)
		lightShaftPath.close()
		glowPaint.color = withAlpha(shaft.tint, segmentAlpha)

		canvas.drawPath(lightShaftPath, glowPaint)
	}

	/**
	 * Cached deterministic starburst between the white disc and the broad atmospheric bloom.
	 * Broad tapered lobes and heavy feathering keep the rays optically connected to the bloom instead of reading as pointed spokes around the disc.
	 */
	private fun buildSunCoronaSprite(canvas: Canvas, core: Int, warmth: Float) {
		val size = SUN_CORONA_SPRITE_SIZE.toFloat()
		val center = size / 2f
		val random = Random(SUN_CORONA_SEED)
		val brush = Paint(Paint.ANTI_ALIAS_FLAG)
		val ray = Path()
		val featherBlur = BlurMaskFilter(size * SUN_CORONA_FEATHER_BLUR_FRACTION, BlurMaskFilter.Blur.NORMAL)
		val rayBlur = BlurMaskFilter(size * SUN_CORONA_BLUR_FRACTION, BlurMaskFilter.Blur.NORMAL)
		val gold = Color.rgb(255, 193, 72)
		val warm = lerpColor(core, gold, warmth)

		brush.style = Paint.Style.FILL

		brush.shader = RadialGradient(
			center,
			center,
			center * SUN_CORONA_GLOW_REACH,
			intArrayOf(withAlpha(warm, 168), withAlpha(warm, 70), withAlpha(warm, 10), withAlpha(warm, 0)),
			floatArrayOf(0f, 0.24f, 0.52f, 1f),
			Shader.TileMode.CLAMP
		)

		canvas.drawCircle(center, center, center * SUN_CORONA_GLOW_REACH, brush)

		brush.shader = null

		repeat(SUN_CORONA_RAY_COUNT) { index ->
			val emphasis = sunCoronaRayEmphasis(index)
			val angle = TAU * index / SUN_CORONA_RAY_COUNT + random.nextFloat(-SUN_CORONA_ANGLE_JITTER, SUN_CORONA_ANGLE_JITTER)
			val directionX = cos(angle)
			val directionY = sin(angle)
			val normalX = -directionY
			val normalY = directionX
			val innerRadius = center * random.nextFloat(0.14f, 0.20f)
			val outerRadius = center * random.nextFloat(lerp(0.46f, 0.62f, emphasis), lerp(0.58f, 0.78f, emphasis))
			val halfWidth = center * random.nextFloat(lerp(0.054f, 0.072f, emphasis), lerp(0.072f, 0.098f, emphasis))
			val tipHalfWidth = halfWidth * random.nextFloat(0.18f, 0.30f)
			val alpha = random.nextFloat(lerp(82f, 108f, emphasis), lerp(120f, 160f, emphasis)).roundToInt()

			ray.rewind()
			ray.moveTo(center + directionX * innerRadius + normalX * halfWidth, center + directionY * innerRadius + normalY * halfWidth)
			ray.lineTo(center + directionX * outerRadius + normalX * tipHalfWidth, center + directionY * outerRadius + normalY * tipHalfWidth)
			ray.lineTo(center + directionX * outerRadius - normalX * tipHalfWidth, center + directionY * outerRadius - normalY * tipHalfWidth)
			ray.lineTo(center + directionX * innerRadius - normalX * halfWidth, center + directionY * innerRadius - normalY * halfWidth)
			ray.close()

			brush.maskFilter = featherBlur
			brush.color = withAlpha(warm, (alpha * SUN_CORONA_FEATHER_ALPHA_SCALE).roundToInt())
			canvas.drawPath(ray, brush)

			brush.maskFilter = rayBlur
			brush.color = withAlpha(warm, alpha)
			canvas.drawPath(ray, brush)
		}
	}

	private fun sunCoronaRayEmphasis(index: Int) = when (index) {
		0, 3, 7, 11, 14 -> 1f
		2, 5, 9, 12 -> SUN_CORONA_SECONDARY_RAY_EMPHASIS
		else -> SUN_CORONA_SHORT_RAY_EMPHASIS
	}

	/** Broad lens haze around the direct sun, fading continuously so it never resolves into a visible circular ring. */
	private fun buildLensHaloSprite(canvas: Canvas) {
		val center = HALO_SPRITE_SIZE / 2f
		val radius = center * SUN_LENS_HALO_RADIUS_FRACTION
		val warm = Color.rgb(255, 238, 198)
		val brush = Paint(Paint.ANTI_ALIAS_FLAG)
		brush.shader = RadialGradient(
			center,
			center,
			radius,
			intArrayOf(
				withAlpha(warm, SUN_LENS_HALO_SPRITE_ALPHA),
				withAlpha(warm, (SUN_LENS_HALO_SPRITE_ALPHA * SUN_LENS_HALO_MIDDLE_ALPHA_SCALE).roundToInt()),
				withAlpha(warm, (SUN_LENS_HALO_SPRITE_ALPHA * SUN_LENS_HALO_OUTER_ALPHA_SCALE).roundToInt()),
				withAlpha(warm, 0)
			),
			floatArrayOf(0f, SUN_LENS_HALO_MIDDLE_STOP, SUN_LENS_HALO_OUTER_STOP, 1f),
			Shader.TileMode.CLAMP
		)

		canvas.drawCircle(center, center, radius, brush)
	}

	private fun lensHaloPhaseStrength(dayPhase: DayPhase) = when (dayPhase) {
		DayPhase.DAY -> 0.72f
		DayPhase.DAWN -> 0.055f
		DayPhase.DUSK -> 0.018f
		DayPhase.NIGHT -> 0f
	}

	/** A filled optical reflection with a broad translucent shoulder, so the ghost survives SCREEN compositing against a bright daytime sky without becoming a hollow ring. */
	private fun buildLensGhostSprite(canvas: Canvas, tint: Int) {
		val center = HALO_SPRITE_SIZE / 2f
		val brush = Paint(Paint.ANTI_ALIAS_FLAG)
		brush.shader = RadialGradient(
			center,
			center,
			center,
			intArrayOf(tint, withAlpha(tint, 220), withAlpha(tint, 150), withAlpha(tint, 60), withAlpha(tint, 0)),
			floatArrayOf(0f, 0.22f, 0.52f, 0.82f, 1f),
			Shader.TileMode.CLAMP
		)

		canvas.drawCircle(center, center, center, brush)
	}

	/**
	 * The sun disc rasterized once per scene, the moon's counterpart.
	 * Its warm cream center eases through a warmer shoulder before the alpha feather, so the source stays luminous without resolving into a neutral white spot.
	 */
	private fun buildSunSprite(canvas: Canvas, core: Int) {
		val center = SUN_SPRITE_SIZE / 2f
		val brush = Paint(Paint.ANTI_ALIAS_FLAG)
		val hotCore = lighten(core, SUN_CORE_LIFT)
		val innerShoulder = lighten(core, SUN_INNER_LIFT)
		val softEdge = lighten(core, SUN_EDGE_LIFT)
		val stops = intArrayOf(hotCore, innerShoulder, softEdge, withAlpha(softEdge, SUN_EDGE_ALPHA), withAlpha(core, 0))
		val positions = floatArrayOf(0f, 0.18f * SUN_DISC_MARGIN, 0.50f * SUN_DISC_MARGIN, 0.80f * SUN_DISC_MARGIN, 1f)

		brush.shader = RadialGradient(center, center, center, stops, positions, Shader.TileMode.CLAMP)
		canvas.drawCircle(center, center, center, brush)
	}

	/**
	 * The anamorphic streak rasterized once per scene: a horizontal bar of light through the disc, brightest at its middle and gone by either end.
	 * Cylindrical lens elements smear a point source sideways, and the eye has learned to read that smear as "too bright to look at".
	 * Built as a vertical falloff masked by a horizontal one — a radial gradient cannot span a sprite this lopsided without clamping almost all of it away.
	 */
	private fun buildSunStreakSprite(canvas: Canvas, core: Int) {
		val width = SUN_STREAK_SPRITE_WIDTH.toFloat()
		val height = SUN_STREAK_SPRITE_HEIGHT.toFloat()
		val bright = lighten(core, 0.82f)
		val brush = Paint(Paint.ANTI_ALIAS_FLAG)

		brush.shader = LinearGradient(0f, 0f, 0f, height, intArrayOf(withAlpha(bright, 0), bright, withAlpha(bright, 0)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
		canvas.drawRect(0f, 0f, width, height, brush)

		// Taper the ends to nothing; without this the bar stops dead at the sprite edge and the seam is obvious against the sky.
		brush.shader = LinearGradient(0f, 0f, width, 0f, intArrayOf(withAlpha(bright, 0), bright, withAlpha(bright, 0)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
		brush.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
		canvas.drawRect(0f, 0f, width, height, brush)
	}

	private fun rainBuffer(size: Int): FloatArray {
		if (rainPoints.size < size) {
			rainPoints = FloatArray(size)
		}

		return rainPoints
	}

	companion object {
		internal fun shouldDrawOvercastBanks(cloudiness: Float, fogDensity: Float, hasPrecipitation: Boolean) =
			(cloudiness > CLOUD_DECK_THRESHOLD || hasPrecipitation) && (fogDensity < DENSE_FOG_CLOUD_CUTOFF || hasPrecipitation)

		internal fun overcastBankHeight(width: Float, height: Float, viewports: Float, heightScale: Float) =
			minOf(width * viewports / OVERCAST_SOURCE_ASPECT * heightScale, height * OVERCAST_MAX_BANK_HEIGHT_FRACTION)

		private const val STAR_SEED = 1L
		private const val PRECIP_SEED = 3L
		private const val BOLT_SEED = 5L
		private const val METEOR_SEED = 7L
		private const val BIRD_SEED = 11L
		private const val HELICOPTER_SEED = 13L
		private const val SUN_CORONA_SEED = 17L
		private const val STAR_AREA_PER_STAR = 22_000f
		private const val BOLT_STEPS = 6

		/** Length of one lightning scheduling slot; each slot hosts at most one strike, fired at a random moment within it. */
		private const val STRIKE_SLOT_SECONDS = 4.6f

		private const val FLASH_DURATION = 0.45f
		private const val SHEET_DURATION = 0.8f
		private const val ECHO_DELAY = 0.55f
		private const val AFTERGLOW_SECONDS = 0.5f
		private const val AFTERGLOW_PEAK = 0.12f

		/** Multiplies the slot seed for bolt/glow geometry, so geometry never replays the schedule's random draws. */
		private const val GEOMETRY_SEED_STRIDE = 1_000_003L

		private const val TAU = 6.2831855f
		private const val PI_F = 3.1415927f
		private const val DEGREES_TO_RADIANS = 0.017453292f

		/** Length of one meteor scheduling slot; roughly half the slots fire one meteor at a random moment. */
		private const val METEOR_SLOT_SECONDS = 149f

		private const val METEOR_DURATION = 0.8f

		/** Length of one bird scheduling slot; a bit over half the slots host one flock crossing. */
		private const val BIRD_SLOT_SECONDS = 217f

		private const val BIRD_CROSSING_SECONDS = 22f

		/** Length of one helicopter scheduling slot over the metropolis; about half the slots host one crossing. */
		private const val HELICOPTER_SLOT_SECONDS = 193f

		private const val HELICOPTER_CROSSING_SECONDS = 26f

		/**
		 * Fog tiles are built at a quarter of the surface resolution and stretched at blit time to bound CPU-side blur work.
		 * Cloud sheets use their original textures directly and do not share this downscale.
		 */
		private const val TILE_DOWNSCALE = 4f

		private const val MOSTLY_CLEAR_THRESHOLD = 0.3f

		/** Below this cloudiness the sky is drawn empty: a genuinely clear day has no cumulus in it, not a faint suggestion of some. */
		private const val SCATTERED_CLOUD_FLOOR = 0.1f

		/**
		 * The near cumulus deck draws very close to opaque, which is the whole point of moving coverage into the textures.
		 * A sunlit crown has to be able to reach white, and it cannot if the deck's own paint is holding it back.
		 */
		private const val CUMULUS_NEAR_ALPHA = 248f

		/** The far deck's repeat span: shorter than the near deck's, which is what makes the same kind of mass come out smaller and read as distant. */
		private const val CUMULUS_FAR_VIEWPORTS = 2.5f

		/** How far the far deck's tint is pulled toward the sky above it, standing in for the air between. */
		private const val CUMULUS_FAR_HAZE = 0.35f

		/** The far deck's opacity at the scattered-cloud floor, and how much more it gains by the overcast threshold. */
		private const val CUMULUS_FAR_MIN_ALPHA = 60f
		private const val CUMULUS_FAR_ALPHA_RANGE = 120f

		/** Cross-fade weights below this draw nothing, so the common case stays at two deck draws rather than three. */
		private const val CUMULUS_BLEND_FLOOR = 0.02f
		private const val CUMULUS_PARTLY_INDEX = 2
		private const val CUMULUS_BROKEN_BLEND_START = 0.90f

		private const val PARTLY_BANK_START_COVERAGE = 0.70f
		private const val PARTLY_BANK_FULL_COVERAGE = 0.95f
		private const val PARTLY_BANK_VIEWPORTS = 1.72f
		private const val PARTLY_BANK_HEIGHT_SCALE = 0.58f
		private const val PARTLY_BANK_TOP = 0.27f
		private const val PARTLY_BANK_PHASE = 0.34f
		private const val PARTLY_BANK_HAZE = 0.34f
		private const val PARTLY_BANK_ALPHA = 0.28f

		private const val DENSE_FOG_CLOUD_CUTOFF = 0.8f
		private const val OVERCAST_SOURCE_ASPECT = 3f
		private const val OVERCAST_MAX_BANK_HEIGHT_FRACTION = 0.55f
		private const val OVERCAST_FAR_VIEWPORTS = 1.35f
		private const val OVERCAST_SUPPORT_VIEWPORTS = 1.15f
		private const val OVERCAST_HERO_VIEWPORTS = 1.03f
		private const val OVERCAST_BRIDGE_VIEWPORTS = 1.25f
		private const val OVERCAST_FAR_HEIGHT_SCALE = 1.10f
		private const val OVERCAST_SUPPORT_HEIGHT_SCALE = 1.00f
		private const val OVERCAST_HERO_HEIGHT_SCALE = 1.10f
		private const val OVERCAST_BRIDGE_HEIGHT_SCALE = 1.00f
		private const val OVERCAST_FAR_TOP = -0.02f
		private const val OVERCAST_SUPPORT_TOP = 0.17f
		private const val OVERCAST_HERO_TOP = 0.30f
		private const val OVERCAST_BRIDGE_TOP = 0.47f

		/** The upper deck is sampled slightly toward the sun and upward, projecting its cover onto the lower cloud plane. */
		private const val CUMULUS_CAST_SHADOW_HORIZONTAL_PROJECTION = 0.18f
		private const val CUMULUS_CAST_SHADOW_SOURCE_Y_PORTRAIT = 0.14f
		private const val CUMULUS_CAST_SHADOW_SOURCE_Y_LANDSCAPE = 0.12f

		/** Maximum lower-cloud darkening from a fully opaque upper cloud. */
		private const val CUMULUS_CAST_SHADOW_DAY_STRENGTH = 0.22f
		private const val CUMULUS_CAST_SHADOW_TWILIGHT_STRENGTH = 0.15f

		/** Severity at and above which rain becomes a downpour and snow a blizzard (thicker, faster, more slanted). */
		private const val HEAVY_SEVERITY = 0.85f

		/** Edge length of the square soft-dot sprites — big enough that even the largest blizzard flake only ever downscales it. */
		private const val SPRITE_SIZE = 64

		/** Edge length of the celestial halo sprite; the falloff is smooth enough that upscaling to the on-screen glow stays clean. */
		private const val HALO_SPRITE_SIZE = 256

		/** Edge length of the pre-rendered moon sprite. */
		private const val MOON_SPRITE_SIZE = 256

		private const val CELESTIAL_X_FRACTION = 0.72f

		/** The moon's radius as a fraction of the screen's shorter side; it stays the generous disc it always was, because a moon genuinely does read large. */
		private const val MOON_RADIUS_FRACTION = 0.1f

		/** The moon disc fills this fraction of its sprite, leaving margin so the anti-aliased limb never clips at the bitmap edge. */
		private const val MOON_DISC_MARGIN = 0.96f

		/** Distinct phase sprites across the synodic month — the sprite cache key quantizes to these steps. */
		private const val MOON_PHASE_STEPS = 64

		/**
		 * The sun's optical radius as a fraction of the shorter side.
		 * The direct disc feathers well inside this field, so the nominal size can stay comparable to the moon without returning to a flat painted ball.
		 */
		private const val SUN_RADIUS_FRACTION = 0.060f

		/** Edge length of the pre-rendered sun disc sprite, matching the moon's so both discs upscale identically. */
		private const val SUN_SPRITE_SIZE = 256

		/** The sun disc fills this fraction of its sprite radius; the remainder carries the feathered atmospheric edge. */
		private const val SUN_DISC_MARGIN = 0.84f
		private const val SUN_DIRECT_BODY_SCALE = 1.30f
		private const val SUN_DIRECT_DISC_SCALE = 1.08f

		/** Cached corona geometry and on-screen reach around the smaller solar disc. */
		private const val SUN_CORONA_SPRITE_SIZE = 512
		private const val SUN_CORONA_RAY_COUNT = 16
		private const val SUN_CORONA_REACH = 2.85f
		private const val SUN_CORONA_ALPHA = 212f
		private const val SUN_CORONA_BLUR_FRACTION = 0.021f
		private const val SUN_CORONA_FEATHER_BLUR_FRACTION = 0.063f
		private const val SUN_CORONA_FEATHER_ALPHA_SCALE = 0.22f
		private const val SUN_CORONA_ANGLE_JITTER = 0.15f
		private const val SUN_CORONA_SECONDARY_RAY_EMPHASIS = 0.66f
		private const val SUN_CORONA_SHORT_RAY_EMPHASIS = 0.28f
		private const val SUN_CORONA_DAY_WARMTH = 0.92f
		private const val SUN_CORONA_TWILIGHT_WARMTH = 0.72f
		private const val SUN_CORONA_GLOW_REACH = 0.52f
		private const val SUN_CORONA_CLOUD_MIN_STRENGTH = 0.18f
		private const val SUN_CORONA_DAWN_SCALE = 0.88f
		private const val SUN_CORONA_DAWN_ALPHA = 0.44f
		private const val SUN_CORONA_DUSK_SCALE = 0.65f
		private const val SUN_CORONA_DUSK_ALPHA = 0.38f

		/** Cloud-edge-driven volumetric rays. Sampling the moving silhouette makes the fan itself move with the clouds instead of only changing opacity. */
		private const val SUN_SHAFT_PROFILE_SAMPLES = 33
		private const val SUN_SHAFT_SAMPLE_SPAN = 1.28f
		private const val SUN_SHAFT_SAMPLE_VERTICAL_OFFSET = 0.34f
		private const val SUN_SHAFT_VERTICAL_EDGE_WEIGHT = 0.82f
		private const val SUN_SHAFT_EDGE_THRESHOLD = 0.075f
		private const val SUN_SHAFT_MIN_OBSTRUCTION = 0.07f
		private const val SUN_SHAFT_REACH_FRACTION = 0.27f
		private const val SUN_SHAFT_DIVERGENCE = 0.035f
		private const val SUN_SHAFT_ORIGIN_SPAN = 1.28f
		private const val SUN_SHAFT_ORIGIN_VERTICAL_SPAN = 0.34f
		private const val SUN_SHAFT_END_WIDTH_MULTIPLIER = 5.0f
		private const val SUN_SHAFT_END_WIDTH_RANGE = 1.5f
		private const val SUN_SHAFT_FEATHER_WIDTH = 1.85f
		private const val SUN_SHAFT_FEATHER_ALPHA_DIVISOR = 4
		private const val SUN_SHAFT_SEGMENTS = 6
		private const val SUN_SHAFT_WIDTH_EASE = 1.15f
		private const val SUN_SHAFT_FADE_POWER = 2.35f
		private const val SUN_SHAFT_CENTER_WARP = 0.06f
		private const val SUN_SHAFT_EDGE_WARP = 0.08f
		private const val SUN_SHAFT_WARP_FREQUENCY = 4.8f
		private const val SUN_SHAFT_EDGE_WARP_FREQUENCY = 6.2f
		private const val SUN_SHAFT_MAX_ALPHA = 14
		private const val SUN_SHAFT_MAX_RAYS = 6
		private const val SUN_SHAFT_MIN_PEAK_GAP = 3

		/** How far the center is lifted toward white while keeping the natural sun visibly warm. */
		private const val SUN_CORE_LIFT = 0.42f

		/** How far the inner shoulder is lifted toward white before easing into the warmer limb. */
		private const val SUN_INNER_LIFT = 0.26f

		/** How far the limb is lifted toward white to prevent a saturated yellow outline behind translucent clouds. */
		private const val SUN_EDGE_LIFT = 0.18f

		/** Alpha at the nominal limb before the final transparent feather. */
		private const val SUN_EDGE_ALPHA = 60

		/** Bloom reach as a multiple of the disc radius: an irregular atmospheric far pass and a radial near pass hugging the limb. */
		private const val SUN_BLOOM_FAR = 5.2f
		private const val SUN_BLOOM_NEAR = 2.20f

		/** Peak alpha of each bloom pass before the restrained breathing scales it. */
		private const val SUN_BLOOM_FAR_ALPHA = 40f
		private const val SUN_BLOOM_NEAR_ALPHA = 104f

		/** Broad irregular bloom used when cloud or fog transmits the sun; fog remains diffuse-only while overcast may retain a faint limb. */
		private const val SUN_VEILED_BLOOM_REACH = 7.2f
		private const val SUN_VEILED_BLOOM_ALPHA = 138f
		private const val SUN_VEILED_MAX_STRENGTH = 0.68f
		private const val SUN_VEILED_MIN_STRENGTH = 0.42f

		/** The hidden solar limb only survives the cloudy transition; once the overcast deck takes over, the broad atmospheric glow is the only visible sun cue. */
		private const val SUN_VEILED_DISC_MAX_ALPHA = 160f
		private const val SUN_VEILED_DISC_MIN_ALPHA = 28f
		private const val SUN_VEILED_DISC_SCALE = 1.08f

		/** Broad transmitted daylight drawn after the overcast sheets so the source remains perceptible without restoring a circular disc. */
		private const val SUN_OVERCAST_TRANSMISSION_REACH = 7.2f
		private const val SUN_OVERCAST_TRANSMISSION_MAX_ALPHA = 78f
		private const val SUN_OVERCAST_TRANSMISSION_MIN_ALPHA = 48f
		private const val SUN_OVERCAST_CORE_REACH = 3.6f
		private const val SUN_OVERCAST_CORE_MAX_ALPHA = 58f
		private const val SUN_OVERCAST_CORE_MIN_ALPHA = 32f

		/** The atmospheric sprite uses a pale source color and a long falloff inside each overlapping lobe. */
		private const val SUN_ATMOSPHERE_LIFT = 0.72f
		private const val SUN_ATMOSPHERE_MIDDLE_STOP = 0.44f
		private const val SUN_ATMOSPHERE_MIDDLE_ALPHA = 0.32f

		/** Keeps the cloudy-sky air wash at roughly its old absolute size after shrinking the direct disc. */
		private const val SUN_CUMULUS_VEIL_REACH = 5f

		/** The streak sprite is long and thin; only its width needs resolution, since the vertical falloff is a single soft gradient. */
		private const val SUN_STREAK_SPRITE_WIDTH = 512
		private const val SUN_STREAK_SPRITE_HEIGHT = 32

		/** Half-length of the streak as a multiple of the disc radius and its height relative to that half-length. */
		private const val SUN_STREAK_REACH = 3.4f
		private const val SUN_STREAK_ASPECT = 0.11f

		/** Peak alpha of the streak before the restrained breathing scales it. */
		private const val SUN_STREAK_ALPHA = 36f

		/** Large optical haze around the direct sun; its cached sprite is brightest inside and fades continuously through the outer atmosphere. */
		private const val SUN_LENS_HALO_REACH = 6.6f
		private const val SUN_LENS_HALO_ALPHA = 128f
		private const val SUN_LENS_HALO_AXIS_OFFSET = 0.18f
		private const val SUN_LENS_HALO_RADIUS_FRACTION = 0.76f
		private const val SUN_LENS_HALO_SPRITE_ALPHA = 52
		private const val SUN_LENS_HALO_MIDDLE_ALPHA_SCALE = 0.40f
		private const val SUN_LENS_HALO_OUTER_ALPHA_SCALE = 0.10f
		private const val SUN_LENS_HALO_MIDDLE_STOP = 0.48f
		private const val SUN_LENS_HALO_OUTER_STOP = 0.72f

		/** Fraction of a soft-dot sprite's radius that is solid color before the fade to transparent begins. */
		private const val DOT_CORE_STOP = 0.5f

		/** Soft dots wrap this far past both screen edges, so their halos never pop in or out at the border. */
		private const val FLAKE_WRAP_PAD = 32f

		/** Rain streaks wrap this far past both screen edges — longer than the longest close drop in a downpour, so a re-laned streak's tip can never flash at the top edge. */
		private const val RAIN_WRAP_PAD = 120f

		/** Offsets the cycle when hashing a particle's sway phase, so phase and lane draw from different parts of the hash space. */
		private const val SWAY_PHASE_SALT = 373

		/** Shifts close-drop indices into their own hash namespace so they never mirror a far or near streak's lane. */
		private const val CLOSE_DROP_LANE_OFFSET = 100_000
	}
}

/**
 * The centered crop of a source photo whose aspect ratio matches the destination, so the photo fills the surface without stretching.
 * A source proportionally wider than the destination keeps its full height and is trimmed at the sides; a taller one keeps its full width and is trimmed at the top and bottom.
 * Cropping through a source rect beats scaling past the destination's edges: the blit then rasterizes only the pixels that land on screen.
 * A zero or negative source or destination dimension short-circuits to the source bounds instead of dividing by zero, which is an empty rect only when the source itself is empty.
 */
internal fun photoSourceRect(sourceWidth: Int, sourceHeight: Int, destinationWidth: Float, destinationHeight: Float): Rect {
	/*
	 * The fields are assigned directly because the mockable android.jar that JVM unit tests run against stubs out Rect's constructors and its `set`, which would hand every test a 0x0 rect.
	 * On a device this is precisely what `Rect(left, top, right, bottom)` does, so the arithmetic stays testable without dragging in Robolectric.
	 */
	val crop = Rect()
	crop.right = sourceWidth
	crop.bottom = sourceHeight

	if (sourceWidth <= 0 || sourceHeight <= 0 || destinationWidth <= 0f || destinationHeight <= 0f) {
		return crop
	}

	val sourceAspect = sourceWidth.toFloat() / sourceHeight.toFloat()
	val destinationAspect = destinationWidth / destinationHeight

	if (sourceAspect > destinationAspect) {
		val width = (sourceHeight * destinationAspect).roundToInt().coerceIn(1, sourceWidth)
		// Integer division leaves the odd leftover pixel on the right, which is half a pixel of off-center at worst.
		crop.left = (sourceWidth - width) / 2
		crop.right = crop.left + width
	} else if (sourceAspect < destinationAspect) {
		val height = (sourceWidth / destinationAspect).roundToInt().coerceIn(1, sourceHeight)
		crop.top = (sourceHeight - height) / 2
		crop.bottom = crop.top + height
	}

	return crop
}

/** Invokes [draw] at [cx] and again wrapped to the opposite edge when within [reach], so a tile scrolls seamlessly. */
private inline fun wrapX(width: Float, cx: Float, reach: Float, draw: (Float) -> Unit) {
	draw(cx)

	if (cx > width - reach) {
		draw(cx - width)
	}

	if (cx < reach) {
		draw(cx + width)
	}
}

/** Dry daylight keeps either a full or veiled sun, while the moon remains limited to a clear, fog-free sky. */
private fun showsCelestialBody(params: SceneParams) = when {
	params.precipitation != null -> false
	params.dayPhase != DayPhase.NIGHT -> true
	else -> params.fogDensity <= 0f && effectiveCloudiness(params) <= 0.75f
}

/** Fair-weather cloud geometry scale, clamped defensively for direct [SceneParams] construction in tests and previews. */
private fun cloudSizeScale(params: SceneParams) = params.cloudSizeScale.coerceIn(CLOUD_SIZE_SCALE_RANGE.start, CLOUD_SIZE_SCALE_RANGE.endInclusive)

/** The scattered-cloud air wash belongs to the visible sun and never appears at night. */
internal fun showsSunVeil(params: SceneParams) = params.sunVisible && params.dayPhase != DayPhase.NIGHT

/** A heavy dry deck still transmits a broad patch of daylight even when the solar limb itself is no longer visible. */
private fun showsOvercastSunTransmission(params: SceneParams) = params.sunVisible && params.dayPhase != DayPhase.NIGHT && params.precipitation == null && params.fogDensity <= 0f && !params.thunder && effectiveCloudiness(params) > CLOUD_DECK_THRESHOLD

/** Birds fly only through fair daylight skies: no precipitation, no fog, cover below the deck threshold, and never at night. */
private fun showsBirds(params: SceneParams) = params.precipitation == null && params.fogDensity <= 0f && effectiveCloudiness(params) < 0.55f && params.dayPhase != DayPhase.NIGHT

/** The chromatic halo follows direct daylight visibility and disappears with the user's sun visibility preference. */
internal fun showsRainbow(params: SceneParams) = params.sunVisible && params.dayPhase != DayPhase.NIGHT && params.precipitation == null && params.fogDensity <= 0f && effectiveCloudiness(params) <= DIRECT_SUN_MAX_CLOUDINESS

/** Helicopters fly in weather birds won't — night included, that's when the blinking light pays off — but storms, fog, and a heavy deck still ground them. */
private fun showsHelicopter(params: SceneParams) = params.precipitation == null && params.fogDensity <= 0f && effectiveCloudiness(params) < 0.55f && !params.thunder

private fun birdColor(dayPhase: DayPhase) = when (dayPhase) {
	DayPhase.DAY -> Color.argb(120, 38, 48, 62)
	else -> Color.argb(140, 26, 26, 38)
}

private fun showsHaze(params: SceneParams) = params.precipitation != null || params.fogDensity > 0f || effectiveCloudiness(params) > 0.75f

private const val PRECIPITATION_SCALE_EXPONENT = 0.5f

private const val DIRECT_SUN_MAX_CLOUDINESS = 0.55f
private const val SUNSET_FADE_START = 0.15f

private const val CLOUD_DECK_THRESHOLD = 0.75f

internal const val MAX_WIND_SLANT = 1.4f

internal const val PRECIPITATION_HORIZONTAL_PAD = 100f

/** Maps the categorical rain severity bands onto a 0..1 motion range, clamped so malformed provider values cannot exaggerate the animation. */
internal fun rainMotionFactor(severity: Float) = unlerp(SEVERITY_DRIZZLE, 1f, severity)

/**
 * Slant of falling rain streaks, derived from the gust factor, measured precipitation, and the user's intensity preference.
 * [observed] is continuous even though forecast severity is categorical, so it supplies the intermediate lean that live weather can actually produce.
 * The scale multiplies the whole expression including its calm floor, so the slider spans its full range rather than being diluted by a fixed base.
 * Clamped to [MAX_WIND_SLANT] so downpours in gales cannot lean past roughly 54 degrees off vertical, where rain reads as broken.
 */
internal fun rainSlant(gust: Float, scale: Float, observed: Float): Float {
	val slantBase = lerp(0.16f, 0.26f, observed.coerceIn(0f, 1f))

	return ((slantBase + gust * 0.71f) * scale).coerceAtMost(MAX_WIND_SLANT)
}

/**
 * Slant of falling snow and sleet pellets, derived from the gust factor and scaled by the user's intensity preference.
 * Snow catches wind more readily than rain but barely leans in dead calm.
 * Clamped to [MAX_WIND_SLANT] so blizzards in gales do not exceed the same physical ceiling as rain.
 */
internal fun snowSlant(gust: Float, scale: Float): Float {
	return ((0.06f + gust * 0.9f) * scale).coerceAtMost(MAX_WIND_SLANT)
}

/**
 * Calculates the horizontal position of a falling precipitation particle.
 * Density is preserved under wind by wrapping within the spawn band ([width] + 200f) offset by -100f.
 * As a particle descends, horizontal displacement advances with distance fallen ([fall]) at the [slant] ratio, keeping motion parallel to streak angle without thinning the field or exposing edges.
 */
internal fun precipitationHorizontalPosition(laneFraction: Float, fall: Float, slant: Float, width: Float): Float {
	val band = width + PRECIPITATION_HORIZONTAL_PAD * 2f
	val raw = (laneFraction * band + fall * slant) % band
	val wrapped = if (raw < 0f) {
		raw + band
	} else {
		raw
	}

	return wrapped - PRECIPITATION_HORIZONTAL_PAD
}

/**
 * How many particles to draw for this precipitation, after the user's intensity preference.
 * The 0.4 floor keeps any reported precipitation visible at all — a trace of rain should not render as a clear sky — but it is a default, not a law, so the user's scale multiplies the whole factor rather than only the 0.6 the observation controls.
 * Scaling the observation instead would leave 40% of the drops beyond the slider's reach and turn a 20x control into a 1.7x one.
 * The scale is shaped with [PRECIPITATION_SCALE_EXPONENT] because the slider is shared with wind, but drop count is perceived ratiometrically rather than linearly, so an unshaped multiplier makes the bottom of the travel swing several-fold while the top barely moves.
 * Any exponent leaves 1f exactly 1f, so the default is untouched.
 */
internal fun precipitationDropCount(precipitation: Precipitation, scale: Float, width: Float, height: Float): Int {
	val shapedScale = scale.coerceAtLeast(0f).pow(PRECIPITATION_SCALE_EXPONENT)
	val observedFactor = (0.4f + 0.6f * precipitation.observed) * shapedScale

	return (precipitationBaseCount(precipitation, width, height) * observedFactor)
		.roundToInt()
		.coerceAtLeast(0)
}

/**
 * Base particle count before the observed-intensity modulation.
 * Screen area over a divisor that shrinks (more particles) as severity climbs; the anchor points reproduce the hand-tuned densities for drizzle, steady rain, storm rain, downpours, steady snow, and blizzards.
 */
internal fun precipitationBaseCount(precipitation: Precipitation, width: Float, height: Float): Int {
	val severity = precipitation.severity
	val divisor = when (precipitation.kind) {
		PrecipitationKind.RAIN -> when {
			severity <= SEVERITY_DRIZZLE -> 14_000f
			severity <= SEVERITY_STEADY -> lerp(14_000f, 9_000f, unlerp(SEVERITY_DRIZZLE, SEVERITY_STEADY, severity))
			severity <= SEVERITY_STORM -> lerp(9_000f, 6_500f, unlerp(SEVERITY_STEADY, SEVERITY_STORM, severity))
			else -> lerp(6_500f, 5_200f, unlerp(SEVERITY_STORM, 1f, severity))
		}
		PrecipitationKind.SNOW -> when {
			severity <= SEVERITY_STEADY -> 15_000f
			else -> lerp(15_000f, 8_500f, unlerp(SEVERITY_STEADY, 1f, severity))
		}
		PrecipitationKind.SLEET -> 9_000f
	}

	return (width * height / divisor).roundToInt()
}

private fun lerp(from: Float, to: Float, fraction: Float) = from + (to - from) * fraction

private fun unlerp(from: Float, to: Float, value: Float) = ((value - from) / (to - from)).coerceIn(0f, 1f)

/**
 * Where the sun/moon hangs, as a fraction of screen height.
 * The phase progress eases it along a continuous arc: it climbs through dawn, sweeps a shallow parabola across the day whose ends meet the twilight heights exactly, and sinks back through dusk — motion on the scale of minutes, so even a calm clear scene is never a still image.
 */
private fun celestialHeightFraction(dayPhase: DayPhase, progress: Float) = when (dayPhase) {
	DayPhase.DAY -> 0.26f - 0.09f * (4f * progress * (1f - progress))
	DayPhase.DAWN -> lerp(0.42f, 0.26f, progress)
	DayPhase.DUSK -> lerp(0.26f, 0.42f, progress)
	DayPhase.NIGHT -> 0.24f
}

/**
 * Dusk fades every sun component together while leaving the independently rendered orange sky intact.
 * A smooth curve keeps the source nearly steady at the start of dusk, then eases it completely away before night.
 */
private fun sunVisibility(dayPhase: DayPhase, progress: Float): Float {
	if (dayPhase != DayPhase.DUSK) {
		return 1f
	}

	val fade = unlerp(SUNSET_FADE_START, 1f, progress)
	val eased = fade * fade

	return 1f - eased
}

private fun sunAlpha(alpha: Float, visibility: Float) = (alpha * visibility).roundToInt().coerceIn(0, 255)

private fun sunColor(dayPhase: DayPhase, preset: SunColorPreset) = when (preset) {
	SunColorPreset.NATURAL -> when (dayPhase) {
		DayPhase.DAWN -> Color.rgb(255, 224, 190)
		DayPhase.DUSK -> Color.rgb(255, 208, 178)
		else -> Color.rgb(255, 248, 218)
	}

	SunColorPreset.WHITE -> Color.rgb(255, 255, 248)
	SunColorPreset.GOLDEN -> Color.rgb(255, 228, 150)
	SunColorPreset.ORANGE -> Color.rgb(255, 188, 118)
}

/**
 * The multiply a cumulus deck draws through.
 * The textures carry their own sunlit-to-shadow ramp, so daylight has to pass through untouched or the shading gets applied twice and the crowns go gray.
 */
private fun cumulusTint(dayPhase: DayPhase) = when (dayPhase) {
	DayPhase.DAY -> Color.WHITE
	DayPhase.DAWN -> Color.rgb(252, 226, 224)
	DayPhase.DUSK -> Color.rgb(246, 206, 198)
	DayPhase.NIGHT -> Color.rgb(86, 96, 120)
}


private fun cloudTint(dayPhase: DayPhase) = when (dayPhase) {
	DayPhase.DAY -> Color.rgb(238, 242, 248)
	DayPhase.DAWN -> Color.rgb(226, 206, 214)
	DayPhase.DUSK -> Color.rgb(198, 176, 184)
	DayPhase.NIGHT -> Color.rgb(64, 72, 90)
}


private fun overcastCeiling(dayPhase: DayPhase) = when (dayPhase) {
	DayPhase.DAY -> Color.rgb(120, 128, 140)
	DayPhase.DAWN -> Color.rgb(96, 90, 104)
	DayPhase.DUSK -> Color.rgb(78, 74, 92)
	DayPhase.NIGHT -> Color.rgb(30, 36, 50)
}

private fun hazeColorFor(dayPhase: DayPhase) = when (dayPhase) {
	DayPhase.DAY -> Color.rgb(200, 208, 216)
	DayPhase.DAWN -> Color.rgb(196, 176, 178)
	DayPhase.DUSK -> Color.rgb(150, 130, 140)
	DayPhase.NIGHT -> Color.rgb(30, 36, 48)
}

/**
 * Particle counts for precipitation layers: the steady count alongside the squall count.
 * The squall count is deliberately applied only to the far layers, because a particle popping into existence mid-fall is imperceptible at their alpha but a visible teleport in the bright near layers.
 */
private data class ParticleCounts(val steadyCount: Int, val squallCount: Int)

/** A cached scenery layer path with the material and plane needed to color it each frame. */
private data class SceneryLayerPath(val path: Path, val material: SceneryMaterial, val plane: SceneryPlane)

/** A helicopter mid-crossing: fuselage center, heading, and the fuselage length every other dimension derives from. */
private data class HelicopterPass(val x: Float, val y: Float, val direction: Float, val bodyW: Float)

/**
 * One reflection in the lens flare.
 * [distance] walks the axis from the sun toward the frame's center, [scale] multiplies the disc radius, [strength] is peak opacity, and [tint] is the color that element passes.
 */
private data class LensGhost(val distance: Float, val scale: Float, val strength: Float, val tint: Int)

/**
 * The ghosts, ordered along the optical axis away from the sun.
 * They stay subtle at ordinary brightness, but remain intentionally readable against a clear daytime sky.
 */
private val LENS_GHOSTS = listOf(
	LensGhost(-0.72f, 0.82f, 0.10f, Color.rgb(255, 238, 204)),
	LensGhost(0.76f, 0.72f, 0.16f, Color.rgb(255, 232, 202)),
	LensGhost(1.34f, 0.62f, 0.12f, Color.rgb(196, 228, 248)),
	LensGhost(1.82f, 1.24f, 0.08f, Color.rgb(214, 232, 215))
)

internal fun darken(color: Int, factor: Float) =
	Color.rgb((Color.red(color) * factor).roundToInt(), (Color.green(color) * factor).roundToInt(), (Color.blue(color) * factor).roundToInt())

/** Blends [color] toward white by [factor] (0 = unchanged, 1 = white) — the silvery highlight on lit cloud tops. */
private fun lighten(color: Int, factor: Float) = Color.rgb(
	(Color.red(color) + (255 - Color.red(color)) * factor).roundToInt(),
	(Color.green(color) + (255 - Color.green(color)) * factor).roundToInt(),
	(Color.blue(color) + (255 - Color.blue(color)) * factor).roundToInt()
)

/** Blends [from] toward [to] by [fraction] (0 = [from], 1 = [to]) — how the far scenery plane picks up the sky's haze. */
private fun lerpColor(from: Int, to: Int, fraction: Float) = Color.rgb(
	(Color.red(from) + (Color.red(to) - Color.red(from)) * fraction).roundToInt(),
	(Color.green(from) + (Color.green(to) - Color.green(from)) * fraction).roundToInt(),
	(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * fraction).roundToInt()
)

private fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
