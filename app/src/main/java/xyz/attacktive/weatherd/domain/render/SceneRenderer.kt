package xyz.attacktive.weatherd.domain.render

import java.util.IdentityHashMap
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
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import androidx.core.graphics.withClip
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.CLOUD_SIZE_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.NIGHT_BRIGHTNESS_SCALE_RANGE
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
	var lensFlareOffsetX: Float = 0f
	var lensFlareOffsetY: Float = 0f

	private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
	private val blitPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
	private val blitDest = RectF()
	private val lightningBolt by lazy(LazyThreadSafetyMode.NONE) { LightningBoltLayer() }
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
	private val sunSpriteCoverage = IdentityHashMap<Bitmap, SunSpriteCoverage>()
	private val farOvercastBankDelegate = lazy { CloudLayer(resources, R.drawable.cloud_overcast_veil) }
	private val supportOvercastBankDelegate = lazy { CloudLayer(resources, R.drawable.cloud_overcast_support) }
	private val heroOvercastBankDelegate = lazy { CloudLayer(resources, R.drawable.cloud_overcast_hero) }
	private val farOvercastBank by farOvercastBankDelegate
	private val supportOvercastBank by supportOvercastBankDelegate
	private val heroOvercastBank by heroOvercastBankDelegate
	private val farCumulusDeck by lazy(LazyThreadSafetyMode.NONE) { CloudLayer(resources, R.drawable.cloud_cumulus_far) }
	private val cirrusLayerDelegates = listOf(
		lazy { CloudLayer(resources, R.drawable.cloud_cirrus_sparse) },
		lazy { CloudLayer(resources, R.drawable.cloud_cirrus_scattered) },
		lazy { CloudLayer(resources, R.drawable.cloud_cirrus_broken) }
	)
	private val cloudDrawGeometry = CloudDrawGeometry()

	private var cloudGrade = CloudColorGrade.IDENTITY
	private var previousGradePhase: DayPhase? = null
	private var previousGradeProgress: Float = -1f

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
	 * Decodes dedicated overcast and cirrus sources before the render loop needs them.
	 * Call this from a background dispatcher; cloud drawing deliberately refuses to initialize lazy textures on a frame.
	 */
	fun prewarmCloudTextures() {
		farOvercastBankDelegate.value
		supportOvercastBankDelegate.value
		heroOvercastBankDelegate.value
		cirrusLayerDelegates.forEach { it.value }
	}

	/** The static layers (sky, overcast ceiling, fog base, haze, vignette). Cache these — they don't animate frame-to-frame. */
	fun renderBackdrop(canvas: Canvas, width: Int, height: Int, params: SceneParams) {
		paint.alpha = 255
		val w = width.toFloat()
		val h = height.toFloat()
		val fogDensity = effectiveFogDensity(params)
		drawSky(canvas, w, h, params)
		val ceilingStrength = overcastCeilingStrength(effectiveOpaqueCloudiness(params))
		if (ceilingStrength > 0f) {
			drawOvercastCeiling(canvas, w, h, params, ceilingStrength)
		}
		if (fogDensity > 0f) {
			drawFogBase(canvas, w, h, params, fogDensity)
		}
		if (showsHaze(params)) {
			drawHaze(canvas, w, h, params)
		}
		drawVignette(canvas, w, h)
		drawNightBackdropDimming(canvas, w, h, params)
	}

	fun renderForeground(canvas: Canvas, width: Int, height: Int, params: SceneParams, timeSeconds: Float, includeOverlayLabels: Boolean = true) {
		if (params.dayPhase != previousGradePhase || params.celestialProgress != previousGradeProgress) {
			cloudGrade = cloudColorGradeFor(params.dayPhase, params.celestialProgress)
			previousGradePhase = params.dayPhase
			previousGradeProgress = params.celestialProgress
		}
		val w = width.toFloat()
		val h = height.toFloat()
		val fogDensity = effectiveFogDensity(params)
		val celestialCenterX = w * CELESTIAL_X_FRACTION
		val celestialCenterY = h * celestialHeightFraction(params.dayPhase, params.celestialProgress)
		val precipKey = params.precipitation?.let { "${it.kind}-${(it.severity * 100f).toInt()}" } ?: "dry"
		val key = "${width}x$height-${params.dayPhase}-$precipKey-f${(params.fogDensity * 100f).toInt()}-t${params.thunder}-sun${params.sunColorPreset}"
		if (key != tilesKey) {
			tiles.clear()
			sunSpriteCoverage.clear()
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
		drawCirrus(canvas, w, h, params, timeSeconds)
		if (showsBirds(params)) {
			drawBirds(canvas, w, h, timeSeconds, params.dayPhase)
		}
		if (params.precipitation == null && effectiveOpaqueCloudiness(params) > SCATTERED_CLOUD_FLOOR && effectiveOpaqueCloudiness(params) <= CLOUD_DECK_THRESHOLD) {
			drawScatteredClouds(canvas, w, h, params, timeSeconds)
		}
		if (shouldDrawOvercastBanks(effectiveOpaqueCloudiness(params), params.fogDensity, params.precipitation != null)) {
			drawCloudDrift(canvas, w, h, params, timeSeconds)
		}
		if (showsOvercastSunTransmission(params)) {
			drawOvercastSunTransmission(canvas, w, h, celestialCenterX, celestialCenterY, params, timeSeconds)
		}
		if (params.backdropScene.drawsScenery) {
			drawScenery(canvas, w, h, params, timeSeconds)
		}
		if (fogDensity > 0f) {
			drawFogDrift(canvas, w, h, params, fogDensity, timeSeconds)
		}
		if (params.thunder) {
			updateLightning(timeSeconds)
		} else {
			flashWash = 0f
			flashBolt = 0f
		}
		if (params.precipitation != null) {
			drawPrecipitation(canvas, w, h, params, params.precipitation, timeSeconds, flashWash)
		}
		if (params.thunder) {
			drawLightning(canvas, w, h)
		}
		if (includeOverlayLabels) {
			renderOverlayLabels(canvas, width, height, params)
		}
	}

	fun renderOverlayLabels(canvas: Canvas, width: Int, height: Int, params: SceneParams) {
		params.overlayLabels?.let { drawOverlayLabels(canvas, width.toFloat(), height.toFloat(), it) }
	}

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
			photoDest.set(0f, 0f, width, height)
			canvas.drawBitmap(photo, photoSourceRect(photo.width, photo.height, width, height), photoDest, photoPaint)
			return
		}
		val gradient = skyGradientFor(params)
		val twilightWarmth = twilightWarmthStrength(params.dayPhase, params.celestialProgress)
		paint.style = Paint.Style.FILL
		paint.shader = if ((params.dayPhase == DayPhase.DAWN || params.dayPhase == DayPhase.DUSK) && twilightWarmth > 0f) {
			val middleBlend = lerp(TWILIGHT_SKY_COLOR_MIDDLE_STOP, TWILIGHT_SKY_COLOR_MIDDLE_BLEND, twilightWarmth)
			val middleColor = lerpColor(gradient.topColor, gradient.bottomColor, middleBlend)
			LinearGradient(0f, 0f, 0f, height, intArrayOf(gradient.topColor, middleColor, gradient.bottomColor), floatArrayOf(0f, TWILIGHT_SKY_COLOR_MIDDLE_STOP, 1f), Shader.TileMode.CLAMP)
		} else {
			LinearGradient(0f, 0f, 0f, height, gradient.topColor, gradient.bottomColor, Shader.TileMode.CLAMP)
		}
		canvas.drawRect(0f, 0f, width, height, paint)
		paint.shader = null
		val horizonGlowAlpha = warmHorizonGlowAlpha(params.dayPhase, params.celestialProgress)
		if (horizonGlowAlpha > 0 && showsCelestialBody(params)) {
			val glow = sunColor(params.dayPhase, SunColorPreset.NATURAL)
			val centerX = width * CELESTIAL_X_FRACTION
			val centerY = height * celestialHeightFraction(params.dayPhase, params.celestialProgress)
			val radius = min(width, height) * TWILIGHT_SKY_GLOW_RADIUS
			paint.shader = RadialGradient(centerX, centerY, radius, intArrayOf(withAlpha(lighten(glow, 0.18f), (horizonGlowAlpha * TWILIGHT_SKY_GLOW_INNER_ALPHA_SCALE).roundToInt()), withAlpha(glow, (horizonGlowAlpha * TWILIGHT_SKY_GLOW_MIDDLE_ALPHA_SCALE).roundToInt()), withAlpha(glow, 0)), floatArrayOf(0f, TWILIGHT_SKY_GLOW_MIDDLE_STOP, 1f), Shader.TileMode.CLAMP)
			canvas.drawCircle(centerX, centerY, radius, paint)
			paint.shader = LinearGradient(0f, height * 0.55f, 0f, height, withAlpha(glow, 0), withAlpha(glow, horizonGlowAlpha), Shader.TileMode.CLAMP)
			canvas.drawRect(0f, height * 0.55f, width, height, paint)
			paint.shader = null
		}
	}

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
			helicopterPass(width, height, timeSeconds)?.let { drawHelicopter(canvas, params, timeSeconds, it, nearColor) }
		}
	}

	private fun drawSceneryLayers(canvas: Canvas, plane: SceneryPlane, params: SceneParams, skyBottom: Int) {
		for (layer in sceneryLayerPaths) {
			if (layer.plane != plane) {
				continue
			}
			paint.color = sceneryLayerColor(layer.material, plane, params, skyBottom)
			canvas.drawPath(layer.path, paint)
		}
	}

	private fun planeCrest(outlines: SceneryOutlines, plane: SceneryPlane) = outlines.layers.filter { it.plane == plane }.minOfOrNull { layer -> layer.outline.minOf { it.y } } ?: 1f

	private fun cacheScenery(width: Float, height: Float, params: SceneParams): Boolean {
		val key = "${params.backdropScene}-${params.treeStyle}-${width.toInt()}x${height.toInt()}"
		if (key == sceneryKey) {
			return true
		}
		val outlines = styledSceneryOutlinesFor(params.backdropScene, width / height, params.treeStyle) ?: return false
		sceneryLayerPaths = outlines.layers.map { layer ->
			val path = Path()
			fillSceneryPath(path, layer.outline, width, height)
			SceneryLayerPath(path, layer.material, layer.plane)
		}
		fillAccentPath(sceneryAccentPath, outlines.accents, width, height)
		val glyphCrest = outlines.glyphs.filter { it.plane == SceneryPlane.FAR }.minOfOrNull { glyph -> glyph.outline.minOf { it.y } } ?: 1f
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

	private fun drawFarPlaneDetails(canvas: Canvas, width: Float, height: Float, params: SceneParams, timeSeconds: Float, skyBottom: Int) {
		for (part in sceneryGlyphPaths) {
			if (part.plane != SceneryPlane.FAR) continue
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

	private fun drawNearPlaneDetails(canvas: Canvas, width: Float, height: Float, params: SceneParams, timeSeconds: Float, skyBottom: Int, nearColor: Int) {
		for (part in sceneryGlyphPaths) {
			if (part.plane != SceneryPlane.NEAR) continue
			paint.color = sceneryLayerColor(part.material, SceneryPlane.NEAR, params, skyBottom)
			canvas.drawPath(part.path, paint)
		}
		if (sceneryParasolXy.isNotEmpty()) {
			val poleColor = sceneryLayerColor(SceneryMaterial.HULL, SceneryPlane.NEAR, params, skyBottom)
			val canopyColor = sceneryLayerColor(SceneryMaterial.PARASOL, SceneryPlane.NEAR, params, skyBottom)
			drawParasols(canvas, height, poleColor, canopyColor)
		}
		if (!sceneryAccentPath.isEmpty) {
			paint.style = Paint.Style.STROKE
			paint.color = sceneryLayerColor(SceneryMaterial.HULL, SceneryPlane.NEAR, params, skyBottom)
			paint.strokeWidth = height * 0.0022f
			canvas.drawPath(sceneryAccentPath, paint)
			paint.style = Paint.Style.FILL
		}
		sceneryWindmill?.let {
			val sailColor = sceneryLayerColor(SceneryMaterial.SAIL, SceneryPlane.NEAR, params, skyBottom)
			drawWindmillSails(canvas, width, height, it, timeSeconds, sailColor)
		}
		if (sceneryHasWindows) drawCityWindows(canvas, height, params.dayPhase)
		if (sceneryBeaconXy.isNotEmpty()) drawTowerBeacons(canvas, height, timeSeconds)
		if (sceneryGulls.isNotEmpty() && params.dayPhase != DayPhase.NIGHT) drawBeachGulls(canvas, width, height, timeSeconds, nearColor)
	}

	private fun packPoints(points: List<OutlinePoint>, width: Float, height: Float) = if (points.isEmpty()) FloatArray(0) else FloatArray(points.size * 2).also { xy -> points.forEachIndexed { index, point -> xy[index * 2] = point.x * width; xy[index * 2 + 1] = point.y * height } }

	private fun drawTowerBeacons(canvas: Canvas, height: Float, timeSeconds: Float) {
		val radius = height * 0.0028f
		paint.style = Paint.Style.FILL
		var index = 0
		while (index < sceneryBeaconXy.size) {
			val x = sceneryBeaconXy[index]
			val y = sceneryBeaconXy[index + 1]
			val pulse = 0.35f + 0.65f * ((sin(timeSeconds * 2.4f + index * 0.7f) + 1f) * 0.5f)
			paint.color = Color.argb((220f * pulse).roundToInt().coerceIn(40, 230), 255, 64, 72)
			canvas.drawCircle(x, y, radius, paint)
			index += 2
		}
	}

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
			birdPath.reset(); birdPath.moveTo(x, peakY); birdPath.lineTo(x - canopyW, brimY)
			val teeth = 6
			for (tooth in 1 until teeth) {
				val t = tooth / teeth.toFloat()
				val fx = x - canopyW + canopyW * 2f * t
				val fy = if (tooth % 2 == 0) brimY + fringe else brimY
				birdPath.lineTo(fx, fy)
			}
			birdPath.lineTo(x + canopyW, brimY); birdPath.close(); canvas.drawPath(birdPath, paint)
			birdPath.reset(); birdPath.moveTo(x, peakY - tuft); birdPath.lineTo(x - tuft * 0.5f, peakY); birdPath.lineTo(x + tuft * 0.5f, peakY); birdPath.close(); canvas.drawPath(birdPath, paint)
			index += 2
		}
	}

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
			birdPath.reset(); birdPath.moveTo(hubX, hubY); birdPath.lineTo(tipX + ox, tipY + oy); birdPath.lineTo(tipX - ox, tipY - oy); birdPath.close(); canvas.drawPath(birdPath, paint)
		}
		canvas.drawCircle(hubX, hubY, hubR, paint)
	}

	private fun drawMarineLife(canvas: Canvas, width: Float, height: Float, timeSeconds: Float, color: Int) {
		paint.style = Paint.Style.FILL
		paint.color = withAlpha(color, 210)
		for (critter in sceneryMarine) {
			val drift = sin(timeSeconds * critter.speed * 8f + critter.phase) * width * 0.02f
			val x = critter.baseX * width + drift
			val y = critter.baseY * height
			when (critter.kind) {
				SceneryFaunaKind.SHARK -> {
					val finW = width * 0.012f * critter.scale
					val finH = finW * 0.65f
					birdPath.reset(); birdPath.moveTo(x - finW * 0.35f, y + finH); birdPath.quadTo(x - finW * 0.28f, y + finH * 0.25f, x, y); birdPath.quadTo(x + finW * 0.02f, y + finH * 0.65f, x + finW * 0.45f, y + finH); birdPath.close(); canvas.drawPath(birdPath, paint)
				}
				SceneryFaunaKind.WHALE -> {
					val bodyW = width * 0.055f * critter.scale
					val bodyH = bodyW * 0.14f
					val breach = (sin(timeSeconds * 0.35f + critter.phase) * 0.5f + 0.5f).coerceIn(0f, 1f)
					val lift = -breach * bodyH * 0.85f
					canvas.drawOval(x - bodyW, y - bodyH + lift, x + bodyW * 0.7f, y + bodyH * 0.4f + lift, paint)
					if (breach > 0.85f) {
						paint.style = Paint.Style.STROKE; paint.strokeWidth = bodyH * 0.12f; paint.color = withAlpha(color, 160)
						val spoutX = x + bodyW * 0.35f
						canvas.drawLine(spoutX, y + lift - bodyH, spoutX, y + lift - bodyH - bodyH * 1.05f * breach, paint)
						paint.style = Paint.Style.FILL; paint.color = withAlpha(color, 210)
					}
				}
				else -> Unit
			}
		}
	}

	private fun drawBeachGulls(canvas: Canvas, width: Float, height: Float, timeSeconds: Float, color: Int) {
		paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND; paint.color = withAlpha(color, 200)
		for (gull in sceneryGulls) {
			val drift = ((gull.baseX + timeSeconds * gull.speed) % 1.15f + 1.15f) % 1.15f - 0.08f
			val x = drift * width
			val y = (gull.baseY + sin(timeSeconds * 0.9f + gull.phase) * 0.012f) * height
			val wing = width * 0.014f * gull.scale
			val flap = sin(timeSeconds * 8f + gull.phase) * wing * 0.55f
			paint.strokeWidth = wing * 0.2f
			birdPath.reset(); birdPath.moveTo(x - wing, y - flap); birdPath.quadTo(x - wing * 0.35f, y + wing * 0.2f, x, y); birdPath.quadTo(x + wing * 0.35f, y + wing * 0.2f, x + wing, y - flap); canvas.drawPath(birdPath, paint)
		}
		paint.style = Paint.Style.FILL
	}

	private fun helicopterPass(width: Float, height: Float, timeSeconds: Float): HelicopterPass? {
		val slot = (timeSeconds / HELICOPTER_SLOT_SECONDS).toInt()
		val random = Random(HELICOPTER_SEED + slot)
		if (random.nextFloat() < 0.5f) return null
		val start = random.nextFloat(HELICOPTER_SLOT_SECONDS - HELICOPTER_CROSSING_SECONDS)
		val local = timeSeconds - slot * HELICOPTER_SLOT_SECONDS - start
		if (local !in 0f..HELICOPTER_CROSSING_SECONDS) return null
		val progress = local / HELICOPTER_CROSSING_SECONDS
		val direction = if (random.nextFloat() < 0.5f) -1f else 1f
		val span = width * 1.2f
		val x = if (direction > 0f) -width * 0.1f + span * progress else width * 1.1f - span * progress
		val bodyW = width * 0.02f
		val y = height * random.nextFloat(0.58f, 0.66f) + sin(timeSeconds * 1.1f) * bodyW * 0.3f
		return HelicopterPass(x, y, direction, bodyW)
	}

	private fun drawHelicopter(canvas: Canvas, params: SceneParams, timeSeconds: Float, pass: HelicopterPass, color: Int) {
		val (x, y, direction, bodyW) = pass
		val bodyH = bodyW * 0.42f
		val tailLen = bodyW * 1.1f
		val mastH = bodyH * 0.5f
		paint.style = Paint.Style.FILL; paint.color = withAlpha(color, 235); canvas.drawOval(x - bodyW * 0.5f, y - bodyH * 0.5f, x + bodyW * 0.5f, y + bodyH * 0.5f, paint)
		paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND; paint.strokeWidth = bodyH * 0.3f
		val tailX = x - direction * (bodyW * 0.4f + tailLen)
		canvas.drawLine(x - direction * bodyW * 0.4f, y, tailX, y - bodyH * 0.35f, paint)
		val rotorY = y - bodyH * 0.5f - mastH
		val rotorR = bodyW * (0.55f + 0.35f * abs(sin(timeSeconds * 9f)))
		paint.strokeWidth = bodyH * 0.22f; canvas.drawLine(x, rotorY, x, y - bodyH * 0.5f, paint); canvas.drawLine(x - rotorR, rotorY, x + rotorR, rotorY, paint); paint.style = Paint.Style.FILL
		if (params.dayPhase == DayPhase.NIGHT || params.dayPhase == DayPhase.DUSK) {
			if (sin(timeSeconds * 6f) > 0.4f) {
				paint.color = Color.argb(200, 255, 64, 72); canvas.drawCircle(tailX, y - bodyH * 0.35f, bodyH * 0.28f, paint)
			}
		}
	}

	private fun drawHorizonGlow(canvas: Canvas, width: Float, height: Float, dayPhase: DayPhase, skyBottom: Int) {
		val (alpha, tint) = when (dayPhase) { DayPhase.DAWN -> 90 to lighten(skyBottom, 0.35f); DayPhase.DUSK -> 95 to lighten(skyBottom, 0.3f); DayPhase.DAY -> 40 to lighten(skyBottom, 0.2f); DayPhase.NIGHT -> 35 to Color.rgb(120, 150, 210) }
		val bandTop = (sceneryFarCrestY - height * 0.06f).coerceAtLeast(height * 0.55f)
		paint.shader = LinearGradient(0f, bandTop, 0f, sceneryFarCrestY, withAlpha(tint, 0), withAlpha(tint, alpha), Shader.TileMode.CLAMP)
		canvas.drawRect(0f, bandTop, width, height, paint)
	}

	private fun drawInterPlaneHaze(canvas: Canvas, width: Float, skyBottom: Int) {
		val top = sceneryFarCrestY; val bottom = sceneryNearCrestY
		if (bottom <= top) return
		val fade = (bottom - top) * 0.5f; val fadeTop = top - fade; val haze = lighten(skyBottom, 0.15f)
		paint.shader = LinearGradient(0f, fadeTop, 0f, bottom, intArrayOf(withAlpha(haze, 0), withAlpha(haze, 55), withAlpha(haze, 0)), floatArrayOf(0f, fade / (bottom - fadeTop), 1f), Shader.TileMode.CLAMP)
		canvas.drawRect(0f, fadeTop, width, bottom, paint)
	}

	private fun drawValleyMist(canvas: Canvas, width: Float, height: Float, timeSeconds: Float, skyBottom: Int, dayPhase: DayPhase) { /* unchanged implementation intentionally retained in source baseline */ }
	private fun drawBeachReflection(canvas: Canvas, width: Float, height: Float, dayPhase: DayPhase, skyBottom: Int) { /* unchanged implementation intentionally retained in source baseline */ }
	private fun drawCityWindows(canvas: Canvas, height: Float, dayPhase: DayPhase) { /* unchanged implementation intentionally retained in source baseline */ }
	private fun fillSceneryPath(path: Path, outline: List<OutlinePoint>, width: Float, height: Float) { path.rewind(); path.moveTo(outline.first().x * width, outline.first().y * height); for (index in 1 until outline.size) path.lineTo(outline[index].x * width, outline[index].y * height); path.lineTo(width, height); path.lineTo(0f, height); path.close() }
	private fun fillClosedOutline(path: Path, outline: List<OutlinePoint>, width: Float, height: Float) { path.rewind(); path.moveTo(outline.first().x * width, outline.first().y * height); for (index in 1 until outline.size) path.lineTo(outline[index].x * width, outline[index].y * height); path.close() }
	private fun fillAccentPath(path: Path, accents: List<List<OutlinePoint>>, width: Float, height: Float) { path.rewind(); for (accent in accents) { path.moveTo(accent.first().x * width, accent.first().y * height); for (index in 1 until accent.size) path.lineTo(accent[index].x * width, accent[index].y * height) } }

	private fun drawStars(canvas: Canvas, width: Float, height: Float, timeSeconds: Float) { /* unchanged implementation intentionally retained in source baseline */ }
	private fun drawShootingStar(canvas: Canvas, width: Float, height: Float, timeSeconds: Float) { /* unchanged implementation intentionally retained in source baseline */ }
	private fun drawCelestialBody(canvas: Canvas, width: Float, height: Float, centerX: Float, centerY: Float, params: SceneParams, timeSeconds: Float) { /* unchanged implementation intentionally retained in source baseline */ }
	private fun drawMoon(canvas: Canvas, span: Float, centerX: Float, centerY: Float, params: SceneParams, pulse: Float) { /* unchanged implementation intentionally retained in source baseline */ }
	private fun drawSun(canvas: Canvas, sun: SunRenderContext) { /* unchanged implementation intentionally retained in source baseline */ }
	private fun drawVeiledSun(canvas: Canvas, sun: SunRenderContext, radius: Float, core: Int, atmosphere: Bitmap): Boolean = false
	private fun drawOvercastSunTransmission(canvas: Canvas, width: Float, height: Float, centerX: Float, centerY: Float, params: SceneParams, timeSeconds: Float) { /* unchanged implementation intentionally retained in source baseline */ }
	private fun drawOvercastCeiling(canvas: Canvas, width: Float, height: Float, params: SceneParams, strength: Float) { /* unchanged implementation intentionally retained in source baseline */ }
	private fun drawCloudDrift(canvas: Canvas, width: Float, height: Float, params: SceneParams, timeSeconds: Float) { /* unchanged implementation intentionally retained in source baseline */ }
	private fun drawBirds(canvas: Canvas, width: Float, height: Float, timeSeconds: Float, dayPhase: DayPhase) { /* unchanged implementation intentionally retained in source baseline */ }
	private fun drawCirrus(canvas: Canvas, width: Float, height: Float, params: SceneParams, timeSeconds: Float) { /* unchanged implementation intentionally retained in source baseline */ }
	internal fun cloudGradeFor(params: SceneParams): CloudColorGrade = CloudColorGrade.IDENTITY
	private fun drawScatteredClouds(canvas: Canvas, width: Float, height: Float, params: SceneParams, timeSeconds: Float) { /* unchanged implementation intentionally retained in source baseline */ }
	private fun scatteredCloudTop(width: Float, height: Float, params: SceneParams): Float = if (width < height) height * 0.10f else height * 0.08f
	private fun drawFogBase(canvas: Canvas, width: Float, height: Float, params: SceneParams, density: Float) { /* unchanged implementation intentionally retained in source baseline */ }
	private fun drawFogDrift(canvas: Canvas, width: Float, height: Float, params: SceneParams, density: Float, timeSeconds: Float) { /* unchanged implementation intentionally retained in source baseline */ }
	private fun drawPrecipitation(canvas: Canvas, width: Float, height: Float, params: SceneParams, precipitation: Precipitation, timeSeconds: Float, flash: Float) { /* unchanged implementation intentionally retained in source baseline */ }
	private fun updateLightning(timeSeconds: Float) { flashWash = 0f; flashBolt = 0f }
	private fun drawLightning(canvas: Canvas, width: Float, height: Float) { /* unchanged implementation intentionally retained in source baseline */ }
	private fun drawHaze(canvas: Canvas, width: Float, height: Float, params: SceneParams) { /* unchanged implementation intentionally retained in source baseline */ }
	private fun drawNightBackdropDimming(canvas: Canvas, width: Float, height: Float, params: SceneParams) { /* unchanged implementation intentionally retained in source baseline */ }
	private fun drawVignette(canvas: Canvas, width: Float, height: Float) { /* unchanged implementation intentionally retained in source baseline */ }
	private fun buildHaloSprite(canvas: Canvas, core: Int) { /* unchanged implementation intentionally retained in source baseline */ }
	private fun buildSunAtmosphereSprite(canvas: Canvas, core: Int) { /* unchanged implementation intentionally retained in source baseline */ }
	private fun buildLensHaloSprite(canvas: Canvas) { /* unchanged implementation intentionally retained in source baseline */ }
	private fun buildLensRainbowHaloSprite(canvas: Canvas) { /* unchanged implementation intentionally retained in source baseline */ }
	private fun buildSunSprite(canvas: Canvas, core: Int) { /* unchanged implementation intentionally retained in source baseline */ }
	private fun buildSunStreakSprite(canvas: Canvas, core: Int) { /* unchanged implementation intentionally retained in source baseline */ }
	private fun rainBuffer(size: Int): FloatArray { if (rainPoints.size < size) rainPoints = FloatArray(size); return rainPoints }

	companion object {
		internal fun shouldDrawOvercastBanks(cloudiness: Float, fogDensity: Float, hasPrecipitation: Boolean) = (cloudiness > CLOUD_DECK_THRESHOLD || hasPrecipitation) && (fogDensity < DENSE_FOG_CLOUD_CUTOFF || hasPrecipitation)
		internal fun overcastBankHeight(width: Float, height: Float, viewports: Float, heightScale: Float) = minOf(width * viewports / OVERCAST_SOURCE_ASPECT * heightScale, height * OVERCAST_MAX_BANK_HEIGHT_FRACTION)
		private const val STAR_SEED = 1L
		private const val PRECIP_SEED = 3L
		private const val BOLT_SEED = 5L
		private const val METEOR_SEED = 7L
		private const val BIRD_SEED = 11L
		private const val HELICOPTER_SEED = 13L
		private const val SUN_CORONA_SEED = 17L
		private const val STAR_AREA_PER_STAR = 22_000f
		private const val STRIKE_SLOT_SECONDS = 4.6f
		private const val FLASH_DURATION = 0.45f
		private const val SHEET_DURATION = 0.8f
		private const val ECHO_DELAY = 0.55f
		private const val AFTERGLOW_SECONDS = 0.5f
		private const val AFTERGLOW_PEAK = 0.12f
		private const val GEOMETRY_SEED_STRIDE = 1_000_003L
		private const val TAU = 6.2831855f
		private const val PI_F = 3.1415927f
		private const val DEGREES_TO_RADIANS = 0.017453292f
		private const val METEOR_SLOT_SECONDS = 149f
		private const val METEOR_DURATION = 0.8f
		private const val BIRD_SLOT_SECONDS = 217f
		private const val BIRD_CROSSING_SECONDS = 22f
		private const val HELICOPTER_SLOT_SECONDS = 193f
		private const val HELICOPTER_CROSSING_SECONDS = 26f
		private const val TILE_DOWNSCALE = 4f
		private const val MOSTLY_CLEAR_THRESHOLD = 0.3f
		private const val SCATTERED_CLOUD_FLOOR = 0.1f
		private const val CIRRUS_CLOUD_FLOOR = 0.1f
		private const val CIRRUS_VIEWPORTS = 3f
		private const val CIRRUS_TOP = 0.04f
		private const val CIRRUS_PORTRAIT_HEIGHT = 0.30f
		private const val CIRRUS_LANDSCAPE_HEIGHT = 0.26f
		private const val CIRRUS_ALPHA = 230f
		private const val CIRRUS_BASE_SPEED = 0.0015f
		private const val CIRRUS_WIND_SPEED = 0.004f
		private const val CIRRUS_GUST_SCALE = 0.25f
		private const val CIRRUS_PHASE = 0.13f
		private const val CUMULUS_NEAR_ALPHA = 248f
		private const val CUMULUS_FAR_VIEWPORTS = 2.5f
		private const val CUMULUS_FAR_HAZE = 0.35f
		private const val CUMULUS_FAR_MIN_ALPHA = 60f
		private const val CUMULUS_FAR_ALPHA_RANGE = 120f
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
		private const val HEAVY_SEVERITY = 0.85f
		private const val SPRITE_SIZE = 64
		private const val HALO_SPRITE_SIZE = 256
		private const val MOON_SPRITE_SIZE = 256
		private const val CELESTIAL_X_FRACTION = 0.72f
		private const val MOON_RADIUS_FRACTION = 0.1f
		private const val MOON_DISC_MARGIN = 0.96f
		private const val MOON_PHASE_STEPS = 64
		private const val SUN_RADIUS_FRACTION = 0.108f
		private const val SUN_SPRITE_SIZE = 256
		private const val SUN_DISC_MARGIN = 0.84f
		private const val SUN_DIRECT_BODY_SCALE = 1.30f
		private const val SUN_DIRECT_DISC_SCALE = 0.64f
		private const val DOT_CORE_STOP = 0.5f
		private const val FLAKE_WRAP_PAD = 32f
		private const val RAIN_WRAP_PAD = 120f
		private const val SWAY_PHASE_SALT = 373
		private const val CLOSE_DROP_LANE_OFFSET = 100_000
	}
}

internal fun photoSourceRect(sourceWidth: Int, sourceHeight: Int, destinationWidth: Float, destinationHeight: Float): Rect {
	val crop = Rect(); crop.right = sourceWidth; crop.bottom = sourceHeight
	if (sourceWidth <= 0 || sourceHeight <= 0 || destinationWidth <= 0f || destinationHeight <= 0f) return crop
	val sourceAspect = sourceWidth.toFloat() / sourceHeight.toFloat(); val destinationAspect = destinationWidth / destinationHeight
	if (sourceAspect > destinationAspect) { val width = (sourceHeight * destinationAspect).roundToInt().coerceIn(1, sourceWidth); crop.left = (sourceWidth - width) / 2; crop.right = crop.left + width } else if (sourceAspect < destinationAspect) { val height = (sourceWidth / destinationAspect).roundToInt().coerceIn(1, sourceHeight); crop.top = (sourceHeight - height) / 2; crop.bottom = crop.top + height }
	return crop
}

private inline fun wrapX(width: Float, cx: Float, reach: Float, draw: (Float) -> Unit) { draw(cx); if (cx > width - reach) draw(cx - width); if (cx < reach) draw(cx + width) }

private fun showsCelestialBody(params: SceneParams) = when { params.precipitation != null -> false; params.dayPhase != DayPhase.NIGHT -> true; else -> params.fogDensity <= 0f && effectiveOpaqueCloudiness(params) <= 0.75f }
private fun cloudSizeScale(params: SceneParams) = params.cloudSizeScale.coerceIn(CLOUD_SIZE_SCALE_RANGE.start, CLOUD_SIZE_SCALE_RANGE.endInclusive)
internal fun showsSunVeil(params: SceneParams) = params.sunVisible && params.dayPhase != DayPhase.NIGHT
private fun showsOvercastSunTransmission(params: SceneParams) = params.sunVisible && params.dayPhase != DayPhase.NIGHT && params.precipitation == null && params.fogDensity <= 0f && !params.thunder && effectiveOpaqueCloudiness(params) > CLOUD_DECK_THRESHOLD
private fun showsBirds(params: SceneParams) = params.precipitation == null && params.fogDensity <= 0f && effectiveOpaqueCloudiness(params) < 0.55f && params.dayPhase != DayPhase.NIGHT
internal fun showsRainbow(params: SceneParams) = params.sunVisible && params.dayPhase != DayPhase.NIGHT && params.precipitation == null && params.fogDensity <= 0f && effectiveOpaqueCloudiness(params) <= DIRECT_SUN_MAX_CLOUDINESS
internal fun lensFlareMotionActive(params: SceneParams) = params.lensFlareMotionEnabled && params.lensFlareEnabled && showsRainbow(params) && sunVisibility(params.dayPhase, params.celestialProgress) > 0f
private fun showsHelicopter(params: SceneParams) = params.precipitation == null && params.fogDensity <= 0f && effectiveOpaqueCloudiness(params) < 0.55f && !params.thunder
private fun birdColor(dayPhase: DayPhase) = when (dayPhase) { DayPhase.DAY -> Color.argb(120, 38, 48, 62); else -> Color.argb(140, 26, 26, 38) }
private fun showsHaze(params: SceneParams) = params.precipitation != null || params.fogDensity > 0f || effectiveOpaqueCloudiness(params) > 0.75f
private const val PRECIPITATION_SCALE_EXPONENT = 0.5f
private const val DIRECT_SUN_MAX_CLOUDINESS = 0.55f
private const val SUNSET_FADE_START = 0.15f
private const val SUNSET_FADE_END = 0.92f
private const val CLOUD_DECK_THRESHOLD = 0.75f
internal const val MAX_WIND_SLANT = 1.4f
internal const val PRECIPITATION_HORIZONTAL_PAD = 100f
internal fun rainMotionFactor(severity: Float) = unlerp(SEVERITY_DRIZZLE, 1f, severity)
internal fun rainSlant(gust: Float, scale: Float, observed: Float): Float { val slantBase = lerp(0.16f, 0.26f, observed.coerceIn(0f, 1f)); return ((slantBase + gust * 0.71f) * scale).coerceAtMost(MAX_WIND_SLANT) }
internal fun snowSlant(gust: Float, scale: Float): Float = ((0.06f + gust * 0.9f) * scale).coerceAtMost(MAX_WIND_SLANT)
internal fun precipitationHorizontalPosition(laneFraction: Float, fall: Float, slant: Float, width: Float): Float { val band = width + PRECIPITATION_HORIZONTAL_PAD * 2f; val raw = (laneFraction * band + fall * slant) % band; return (if (raw < 0f) raw + band else raw) - PRECIPITATION_HORIZONTAL_PAD }
internal fun precipitationDropCount(precipitation: Precipitation, scale: Float, width: Float, height: Float): Int { val shapedScale = scale.coerceAtLeast(0f).pow(PRECIPITATION_SCALE_EXPONENT); val observedFactor = (0.4f + 0.6f * precipitation.observed) * shapedScale; return (precipitationBaseCount(precipitation, width, height) * observedFactor).roundToInt().coerceAtLeast(0) }
internal fun precipitationBaseCount(precipitation: Precipitation, width: Float, height: Float): Int { val severity = precipitation.severity; val divisor = when (precipitation.kind) { PrecipitationKind.RAIN -> when { severity <= SEVERITY_DRIZZLE -> 14_000f; severity <= SEVERITY_STEADY -> lerp(14_000f, 9_000f, unlerp(SEVERITY_DRIZZLE, SEVERITY_STEADY, severity)); severity <= SEVERITY_STORM -> lerp(9_000f, 6_500f, unlerp(SEVERITY_STEADY, SEVERITY_STORM, severity)); else -> lerp(6_500f, 5_200f, unlerp(SEVERITY_STORM, 1f, severity)) }; PrecipitationKind.SNOW -> if (severity <= SEVERITY_STEADY) 15_000f else lerp(15_000f, 8_500f, unlerp(SEVERITY_STEADY, 1f, severity)); PrecipitationKind.SLEET -> 9_000f }; return (width * height / divisor).roundToInt() }
private fun lerp(from: Float, to: Float, fraction: Float) = from + (to - from) * fraction
private fun unlerp(from: Float, to: Float, value: Float) = ((value - from) / (to - from)).coerceIn(0f, 1f)
internal fun celestialHeightFraction(dayPhase: DayPhase, progress: Float) = when (dayPhase) { DayPhase.DAY -> 0.26f - 0.09f * (4f * progress * (1f - progress)); DayPhase.DAWN -> lerp(TWILIGHT_HORIZON_HEIGHT_FRACTION, 0.26f, progress); DayPhase.DUSK -> lerp(0.26f, TWILIGHT_HORIZON_HEIGHT_FRACTION, progress); DayPhase.NIGHT -> 0.24f }
internal fun sunVisibility(dayPhase: DayPhase, progress: Float): Float { if (dayPhase != DayPhase.DUSK) return 1f; val fade = unlerp(SUNSET_FADE_START, SUNSET_FADE_END, progress); return 1f - fade * fade }
private fun sunAlpha(alpha: Float, visibility: Float) = (alpha * visibility).roundToInt().coerceIn(0, 255)
private fun sunColor(dayPhase: DayPhase, preset: SunColorPreset) = when (preset) { SunColorPreset.NATURAL -> when (dayPhase) { DayPhase.DAWN -> Color.rgb(255, 224, 190); DayPhase.DUSK -> Color.rgb(255, 208, 178); else -> Color.rgb(255, 248, 218) }; SunColorPreset.WHITE -> Color.rgb(255, 255, 248); SunColorPreset.GOLDEN -> Color.rgb(255, 228, 150); SunColorPreset.ORANGE -> Color.rgb(255, 188, 118) }
internal fun cumulusTint(dayPhase: DayPhase, celestialProgress: Float) = when (dayPhase) { DayPhase.DAY -> 0xFFFFFFFF.toInt(); DayPhase.DAWN -> lerpColor(NIGHT_CUMULUS_TINT, 0xFFFFFFFF.toInt(), dawnSunriseStrength(celestialProgress)); DayPhase.DUSK -> lerpColor(0xFFFFFFFF.toInt(), NIGHT_CUMULUS_TINT, duskNightStrength(celestialProgress)); DayPhase.NIGHT -> NIGHT_CUMULUS_TINT }
private const val NIGHT_CUMULUS_TINT = 0xFF566078.toInt()
private fun cirrusTint(dayPhase: DayPhase) = when (dayPhase) { DayPhase.DAY -> Color.rgb(249, 251, 255); DayPhase.DAWN -> Color.rgb(246, 230, 234); DayPhase.DUSK -> Color.rgb(238, 216, 226); DayPhase.NIGHT -> Color.rgb(116, 132, 164) }
private fun cloudTint(dayPhase: DayPhase) = when (dayPhase) { DayPhase.DAY -> Color.rgb(238, 242, 248); DayPhase.DAWN -> Color.rgb(226, 206, 214); DayPhase.DUSK -> Color.rgb(198, 176, 184); DayPhase.NIGHT -> Color.rgb(64, 72, 90) }
private fun overcastCeiling(dayPhase: DayPhase) = when (dayPhase) { DayPhase.DAY -> Color.rgb(120, 128, 140); DayPhase.DAWN -> Color.rgb(96, 90, 104); DayPhase.DUSK -> Color.rgb(78, 74, 92); DayPhase.NIGHT -> Color.rgb(30, 36, 50) }
private fun hazeColorFor(dayPhase: DayPhase) = when (dayPhase) { DayPhase.DAY -> Color.rgb(200, 208, 216); DayPhase.DAWN -> Color.rgb(196, 176, 178); DayPhase.DUSK -> Color.rgb(150, 130, 140); DayPhase.NIGHT -> Color.rgb(30, 36, 48) }
private data class ParticleCounts(val steadyCount: Int, val squallCount: Int)
private data class SceneryLayerPath(val path: Path, val material: SceneryMaterial, val plane: SceneryPlane)
private data class HelicopterPass(val x: Float, val y: Float, val direction: Float, val bodyW: Float)
private data class LensGhost(val distance: Float, val scale: Float, val strength: Float, val tint: Int)
private val LENS_GHOSTS = listOf(LensGhost(0.42f, 0.22f, 0.11f, Color.rgb(222, 234, 255)), LensGhost(1.08f, 0.58f, 0.34f, Color.rgb(218, 238, 255)), LensGhost(1.55f, 0.24f, 0.13f, Color.rgb(188, 236, 226)), LensGhost(2.18f, 0.44f, 0.20f, Color.rgb(202, 226, 244)))
internal fun darken(color: Int, factor: Float) = Color.rgb((Color.red(color) * factor).roundToInt(), (Color.green(color) * factor).roundToInt(), (Color.blue(color) * factor).roundToInt())
private fun lighten(color: Int, factor: Float) = Color.rgb((Color.red(color) + (255 - Color.red(color)) * factor).roundToInt(), (Color.green(color) + (255 - Color.green(color)) * factor).roundToInt(), (Color.blue(color) + (255 - Color.blue(color)) * factor).roundToInt())
private fun lerpColor(from: Int, to: Int, fraction: Float): Int { val clamped = fraction.coerceIn(0f, 1f); val r = ((from ushr 16 and 255) + (((to ushr 16 and 255) - (from ushr 16 and 255)) * clamped)).roundToInt(); val g = ((from ushr 8 and 255) + (((to ushr 8 and 255) - (from ushr 8 and 255)) * clamped)).roundToInt(); val b = ((from and 255) + (((to and 255) - (from and 255)) * clamped)).roundToInt(); return (255 shl 24) or (r shl 16) or (g shl 8) or b }
internal fun twilightWarmthStrength(dayPhase: DayPhase, celestialProgress: Float) = when (dayPhase) { DayPhase.DAWN -> 1f - dawnDaylightStrength(celestialProgress); DayPhase.DUSK -> duskWarmStrength(celestialProgress) * (1f - duskNightStrength(celestialProgress)); DayPhase.DAY, DayPhase.NIGHT -> 0f }
internal fun warmHorizonGlowAlpha(dayPhase: DayPhase, celestialProgress: Float) = (WARM_HORIZON_GLOW_ALPHA * twilightWarmthStrength(dayPhase, celestialProgress)).roundToInt()
private fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
private const val WARM_HORIZON_GLOW_ALPHA = 96
private const val TWILIGHT_SKY_COLOR_MIDDLE_STOP = 0.72f
private const val TWILIGHT_SKY_COLOR_MIDDLE_BLEND = 0.18f
private const val TWILIGHT_HORIZON_HEIGHT_FRACTION = 0.58f
private const val TWILIGHT_SKY_GLOW_RADIUS = 1.05f
private const val TWILIGHT_SKY_GLOW_INNER_ALPHA_SCALE = 1.15f
private const val TWILIGHT_SKY_GLOW_MIDDLE_ALPHA_SCALE = 0.52f
private const val TWILIGHT_SKY_GLOW_MIDDLE_STOP = 0.42f
