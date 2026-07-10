package xyz.attacktive.weatherd.domain.render

import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
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
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.weather.SEVERITY_DRIZZLE
import xyz.attacktive.weatherd.domain.weather.SEVERITY_STEADY
import xyz.attacktive.weatherd.domain.weather.SEVERITY_STORM

/**
 * Draws a procedural weather scene onto a Canvas. Split into a static [renderBackdrop] (sky, overcast
 * ceiling, fog base, haze, vignette — cache it) and an animated [renderForeground] (twinkling stars,
 * a glowing sun/moon, drifting clouds/overcast/mist, precipitation, lightning) advanced by [timeSeconds].
 * Soft drifting layers (clouds, overcast, fog) are pre-rendered once into scrolling tiles, so the
 * per-frame cost is a handful of cheap blits rather than a fresh CPU-side blur every frame.
 */
class SceneRenderer {
	private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val blitPaint = Paint(Paint.FILTER_BITMAP_FLAG)
	private val blitDest = RectF()
	private val boltPath = Path()
	private val forkPath = Path()
	private val tiles = HashMap<String, Bitmap>()
	private var tilesKey: String? = null
	private var rainPoints = FloatArray(0)
	private val spritePaint = Paint(Paint.FILTER_BITMAP_FLAG)
	private val spriteDest = RectF()

	/*
	 * Soft-dot sprites for snowflakes and sleet pellets: the softness is rasterised once here, and every frame just blits them as filtered quads.
	 * A hard 1–3px disc shimmers against the pixel grid while it falls; a blurry sprite scaled bilinearly stays smooth at any size.
	 * Their colors are scene-independent, so they live outside [tiles] and survive scene changes.
	 */
	private val farFlakeSprite = softDotSprite(Color.rgb(235, 240, 248), 140)
	private val nearFlakeSprite = softDotSprite(Color.rgb(252, 253, 255), 235)
	private val pelletSprite = softDotSprite(Color.rgb(236, 244, 252), 230)

	fun render(canvas: Canvas, width: Int, height: Int, params: SceneParams, timeSeconds: Float) {
		renderBackdrop(canvas, width, height, params)
		renderForeground(canvas, width, height, params, timeSeconds)
	}

	/** The static layers (sky, overcast ceiling, fog base, haze, vignette). Cache these — they don't animate frame-to-frame. */
	fun renderBackdrop(canvas: Canvas, width: Int, height: Int, params: SceneParams) {
		val w = width.toFloat()
		val h = height.toFloat()

		drawSky(canvas, w, h, params)

		if (params.cloudiness > 0.55f) {
			drawOvercastCeiling(canvas, w, h, params)
		}

		if (params.fogDensity > 0f) {
			drawFogBase(canvas, w, h)
		}

		if (showsHaze(params)) {
			drawHaze(canvas, w, h, params)
		}

		drawVignette(canvas, w, h)
	}

	/** The animated layers (stars, sun/moon glow, drifting clouds/overcast/mist, precipitation, lightning). */
	fun renderForeground(canvas: Canvas, width: Int, height: Int, params: SceneParams, timeSeconds: Float) {
		val w = width.toFloat()
		val h = height.toFloat()
		val precipKey = params.precipitation?.let { "${it.kind}-${(it.severity * 100f).toInt()}" } ?: "dry"
		// Wind is deliberately absent from the key: it only shifts blit-time offsets, so a wind jitter in a refresh must not throw away every soft tile.
		val key = "${width}x$height-${params.dayPhase}-$precipKey-f${(params.fogDensity * 100f).toInt()}-t${params.thunder}-${(params.cloudiness * 100f).toInt()}"
		if (key != tilesKey) {
			tiles.clear()
			tilesKey = key
		}

		if (params.dayPhase == DayPhase.NIGHT && showsCelestialBody(params)) {
			drawStars(canvas, w, h, timeSeconds)
		}

		if (showsCelestialBody(params)) {
			drawCelestialBody(canvas, w, h, params, timeSeconds)
		}

		if (params.cloudiness > 0.1f && params.cloudiness <= 0.55f) {
			drawScatteredClouds(canvas, w, h, params, timeSeconds)
		}

		if (params.cloudiness > CLOUD_DECK_THRESHOLD || params.precipitation != null) {
			drawCloudDrift(canvas, w, h, params, timeSeconds)
		}

		if (params.fogDensity > 0f) {
			drawFogDrift(canvas, w, h, timeSeconds)
		}

		if (params.precipitation != null) {
			drawPrecipitation(canvas, w, h, params.precipitation, params.windFactor, timeSeconds)
		}

		if (params.thunder) {
			drawLightning(canvas, w, h, timeSeconds)
		}
	}

	private fun drawSky(canvas: Canvas, width: Float, height: Float, params: SceneParams) {
		val gradient = skyGradientFor(params)
		paint.style = Paint.Style.FILL
		paint.shader = LinearGradient(0f, 0f, 0f, height, gradient.topColor, gradient.bottomColor, Shader.TileMode.CLAMP)
		canvas.drawRect(0f, 0f, width, height, paint)
		paint.shader = null

		// A warm band above the horizon sells the low sun at dawn and dusk.
		if ((params.dayPhase == DayPhase.DAWN || params.dayPhase == DayPhase.DUSK) && showsCelestialBody(params)) {
			val glow = sunColor(params.dayPhase)
			paint.shader = LinearGradient(0f, height * 0.55f, 0f, height, withAlpha(glow, 0), withAlpha(glow, 80), Shader.TileMode.CLAMP)
			canvas.drawRect(0f, height * 0.55f, width, height, paint)
			paint.shader = null
		}
	}

	private fun drawStars(canvas: Canvas, width: Float, height: Float, timeSeconds: Float) {
		val random = Random(STAR_SEED)
		val count = (width * height / STAR_AREA_PER_STAR).roundToInt()
		paint.style = Paint.Style.FILL

		repeat(count) {
			val x = random.nextFloat() * width
			val y = random.nextFloat() * height * 0.62f
			val radius = random.nextFloat() * 1.6f + 0.6f
			val baseAlpha = random.nextFloat() * 150f + 80f
			val phase = random.nextFloat() * TAU
			val bright = random.nextFloat() < 0.14f
			val twinkle = 0.55f + 0.45f * sin(timeSeconds * 2.2f + phase)
			val alpha = (baseAlpha * twinkle).roundToInt().coerceIn(0, 255)

			// A handful of standout stars get a cool-blue glint halo so the sky isn't uniform pinpricks.
			if (bright) {
				paint.color = Color.argb(alpha / 4, 205, 222, 255)
				canvas.drawCircle(x, y, radius * 3.2f, paint)
			}

			paint.color = Color.argb(alpha, 255, 255, 255)
			canvas.drawCircle(x, y, if (bright) radius * 1.35f else radius, paint)
		}
	}

	private fun drawCelestialBody(canvas: Canvas, width: Float, height: Float, params: SceneParams, timeSeconds: Float) {
		val centerX = width * 0.72f
		val centerY = height * celestialHeightFraction(params.dayPhase)
		val radius = width * 0.1f
		val moon = params.dayPhase == DayPhase.NIGHT
		val core = if (moon) Color.rgb(232, 238, 247) else sunColor(params.dayPhase)

		/*
		 * Two-sine breathing halo: a slow deep swell with a faster shimmer on top, so the glow visibly
		 * blooms and recedes instead of subtly wobbling. Two blits of one pre-rendered radial sprite deepen
		 * the bloom — building RadialGradients here churned two shader allocations every frame.
		 */
		val halo = tile("celestialHalo", HALO_SPRITE_SIZE, HALO_SPRITE_SIZE) { buildHaloSprite(it, core) }
		val pulse = 0.5f + 0.35f * sin(timeSeconds * 0.8f) + 0.15f * sin(timeSeconds * 2.1f)
		val glowRadius = radius * (2.2f + 1.1f * pulse)
		val glowAlpha = (80f + 130f * pulse).roundToInt().coerceIn(0, 255)
		blitSprite(canvas, halo, centerX, centerY, glowRadius, glowAlpha)

		// Wide, faint outer bloom breathing in counter-phase, so something is always in motion.
		val outerRadius = radius * (3.6f + 0.9f * (1f - pulse))
		val outerAlpha = (26f + 34f * (1f - pulse)).roundToInt()
		blitSprite(canvas, halo, centerX, centerY, outerRadius, outerAlpha)

		paint.style = Paint.Style.FILL
		paint.color = core
		canvas.drawCircle(centerX, centerY, radius, paint)

		if (moon) {
			paint.color = withAlpha(darken(core, 0.82f), 90)
			canvas.drawCircle(centerX - radius * 0.32f, centerY - radius * 0.18f, radius * 0.2f, paint)
			canvas.drawCircle(centerX + radius * 0.18f, centerY + radius * 0.3f, radius * 0.14f, paint)
			canvas.drawCircle(centerX + radius * 0.32f, centerY - radius * 0.32f, radius * 0.1f, paint)
		}
	}

	private fun drawOvercastCeiling(canvas: Canvas, width: Float, height: Float, params: SceneParams) {
		val ceiling = overcastCeiling(params.dayPhase)
		paint.style = Paint.Style.FILL
		paint.shader = LinearGradient(0f, 0f, 0f, height * 0.6f, withAlpha(ceiling, 225), withAlpha(ceiling, 0), Shader.TileMode.CLAMP)
		canvas.drawRect(0f, 0f, width, height * 0.6f, paint)

		paint.shader = null
	}

	/**
	 * Two parallax layers of soft cloud masses drifting over the overcast/precipitation ceiling, so the sky
	 * churns rather than sits flat. Storm decks are darkened and snow decks lightened to match their skies.
	 */
	private fun drawCloudDrift(canvas: Canvas, width: Float, height: Float, params: SceneParams, timeSeconds: Float) {
		val base = overcastCeiling(params.dayPhase)
		val snowy = params.precipitation?.kind == PrecipitationKind.SNOW
		val ceiling = when {
			params.thunder -> darken(base, 0.72f)
			snowy -> lighten(base, 0.4f)
			else -> base
		}
		val destHeight = height * 0.72f
		val tileWidth = (width / TILE_DOWNSCALE).toInt()
		/*
		 * BlurMaskFilter draws past the tile edge and gets hard-clipped — on landscape tablets that
		 * reads as a dark horizontal band across the rain/overcast deck. Extra bottom pad + a soft
		 * alpha fade hide the clip.
		 */
		val blurPad = (tileWidth * 0.16f).roundToInt()
		val tileHeight = (destHeight / TILE_DOWNSCALE).toInt() + blurPad

		// Snow clouds stay milky rather than smoky, so the back layer keeps most of its brightness.
		val backFactor = if (snowy) 0.82f else 0.45f

		val back = tile("ovcBack", tileWidth, tileHeight) {
			buildMassTile(it, tileWidth.toFloat(), tileHeight.toFloat(), darken(ceiling, backFactor), 165, tileWidth * 0.14f, 6, 22L)
			fadeTileBottom(it)
		}

		val front = tile("ovcFront", tileWidth, tileHeight) {
			buildMassTile(it, tileWidth.toFloat(), tileHeight.toFloat(), lighten(ceiling, 0.5f), 120, tileWidth * 0.09f, 7, 23L)
			fadeTileBottom(it)
		}

		/*
		 * Layers scroll at clearly different speeds, bob vertically in counter-phase, and the front layer's
		 * opacity swells and fades — together the deck visibly churns rather than sliding as one sheet.
		 * Each layer's top is overscanned past the screen edge by its own bob amplitude, so the bob never wobbles the tile's hard-clipped top edge into view.
		 */
		val bobAmplitude = height * 0.022f
		val bob = sin(timeSeconds * 0.4f) * bobAmplitude
		val frontAlpha = (200f + 55f * sin(timeSeconds * 0.55f)).roundToInt()
		val backOffset = (timeSeconds * (14f + params.windFactor * 18f)) % width
		val frontOffset = (timeSeconds * (38f + params.windFactor * 48f)) % width
		blitScrolled(canvas, back, backOffset, width, destHeight + bobAmplitude, 255, bob - bobAmplitude)
		blitScrolled(canvas, front, frontOffset, width, destHeight + bobAmplitude * 1.5f, frontAlpha, -bob * 1.5f - bobAmplitude * 1.5f)
	}

	private fun drawScatteredClouds(canvas: Canvas, width: Float, height: Float, params: SceneParams, timeSeconds: Float) {
		// Gentler downscale than the mass tiles: puffs have visible edges and a highlight rim to preserve.
		val destHeight = height * 0.6f
		val tileWidth = (width / 2f).toInt()
		val tileHeight = (destHeight / 2f).toInt()
		val cloudColor = withAlpha(cloudTint(params.dayPhase), (150f + params.cloudiness * 80f).roundToInt().coerceAtMost(235))

		val cloud = tile("scattered", tileWidth, tileHeight) {
			buildScatteredTile(it, tileWidth.toFloat(), tileHeight.toFloat(), params, cloudColor)
		}

		val offset = (timeSeconds * (20f + params.windFactor * 34f)) % width
		blitScrolled(canvas, cloud, offset, width, destHeight, 255)
	}

	private fun buildScatteredTile(canvas: Canvas, width: Float, height: Float, params: SceneParams, color: Int) {
		val body = Paint(Paint.ANTI_ALIAS_FLAG)
		body.style = Paint.Style.FILL
		body.color = color
		body.maskFilter = BlurMaskFilter(width * 0.02f, BlurMaskFilter.Blur.NORMAL)

		// A lighter copy peeking over the top edge reads as sun/moonlight catching the cloud tops.
		val highlight = Paint(Paint.ANTI_ALIAS_FLAG)
		highlight.style = Paint.Style.FILL
		highlight.color = withAlpha(lighten(cloudTint(params.dayPhase), 0.55f), (Color.alpha(color) * 0.9f).roundToInt())
		highlight.maskFilter = BlurMaskFilter(width * 0.018f, BlurMaskFilter.Blur.NORMAL)

		val random = Random(CLOUD_SEED)

		repeat((2 + params.cloudiness * 5f).roundToInt()) {
			val cx = random.nextFloat() * width
			val baseline = height * (0.35f + random.nextFloat() * 0.45f)
			val scale = width * (0.07f + random.nextFloat() * 0.05f)

			wrapX(width, cx, scale * 3f) { x ->
				drawPuff(canvas, x, baseline - scale * 0.12f, scale, highlight)
				drawPuff(canvas, x, baseline, scale * 0.97f, body)
			}
		}
	}

	/** A single cloud: a row of circles sharing a flat baseline, with a couple of bumps on top. */
	private fun drawPuff(canvas: Canvas, cx: Float, baseline: Float, scale: Float, brush: Paint) {
		val r1 = scale * 0.72f
		canvas.drawCircle(cx - scale * 1.3f, baseline - r1, r1, brush)

		val r2 = scale
		canvas.drawCircle(cx - scale * 0.42f, baseline - r2, r2, brush)

		val r3 = scale * 0.95f
		canvas.drawCircle(cx + scale * 0.55f, baseline - r3, r3, brush)

		val r4 = scale * 0.68f
		canvas.drawCircle(cx + scale * 1.35f, baseline - r4, r4, brush)
		canvas.drawCircle(cx - scale * 0.25f, baseline - scale * 1.35f, scale * 0.72f, brush)
		canvas.drawCircle(cx + scale * 0.5f, baseline - scale * 1.25f, scale * 0.62f, brush)
	}

	/** Soft blurred blobs scattered across a tile — used for both overcast masses and rolling fog. */
	private fun buildMassTile(canvas: Canvas, width: Float, height: Float, color: Int, alpha: Int, blur: Float, count: Int, seed: Long) {
		val brush = Paint(Paint.ANTI_ALIAS_FLAG)
		brush.style = Paint.Style.FILL
		brush.color = withAlpha(color, alpha)
		brush.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)

		val random = Random(seed)
		repeat(count) {
			val cx = random.nextFloat() * width
			val cy = height * (0.12f + random.nextFloat() * 0.6f)
			val rx = width * (0.16f + random.nextFloat() * 0.16f)

			wrapX(width, cx, rx * 1.6f) { x ->
				canvas.drawCircle(x, cy, rx, brush)
				canvas.drawCircle(x + rx * 0.7f, cy + rx * 0.2f, rx * 0.75f, brush)
			}
		}
	}

	private fun drawFogBase(canvas: Canvas, width: Float, height: Float) {
		// Denser toward the ground, so the fog has depth instead of being a flat wash.
		paint.style = Paint.Style.FILL
		paint.shader = LinearGradient(0f, 0f, 0f, height, Color.argb(45, 214, 218, 224), Color.argb(130, 206, 210, 216), Shader.TileMode.CLAMP)
		canvas.drawRect(0f, 0f, width, height, paint)
		paint.shader = null
	}

	/** Two mist layers drifting at different speeds with a slow opacity breath, so the fog rolls instead of sitting there. */
	private fun drawFogDrift(canvas: Canvas, width: Float, height: Float, timeSeconds: Float) {
		val tileWidth = (width / TILE_DOWNSCALE).toInt()
		val tileHeight = (height / TILE_DOWNSCALE).toInt()

		val far = tile("fogFar", tileWidth, tileHeight) {
			buildMassTile(it, tileWidth.toFloat(), tileHeight.toFloat(), Color.rgb(224, 228, 232), 90, tileWidth * 0.16f, 4, 30L)
		}

		val near = tile("fogNear", tileWidth, tileHeight) {
			buildMassTile(it, tileWidth.toFloat(), tileHeight.toFloat(), Color.rgb(232, 236, 240), 70, tileWidth * 0.12f, 5, 31L)
		}

		/*
		 * The two layers drift in opposite directions — by far the most legible motion cue for a texture
		 * this soft — with counter-phased opacity breathing and a slow vertical roll on top, so banks of
		 * mist visibly slide past each other, thicken, and thin.
		 */
		val breath = 0.5f + 0.5f * sin(timeSeconds * 0.45f)
		val rollAmplitude = height * 0.03f
		val roll = sin(timeSeconds * 0.25f) * rollAmplitude
		val farOffset = (((timeSeconds * -22f) % width) + width) % width
		val nearOffset = (timeSeconds * 32f) % width
		val farAlpha = (100f + 130f * breath).roundToInt()
		val nearAlpha = (100f + 130f * (1f - breath)).roundToInt()

		// Both ends are overscanned by the roll amplitude, so the roll never wobbles a hard-clipped tile edge (or a bare gap) into view.
		blitScrolled(canvas, far, farOffset, width, height + rollAmplitude * 2f, farAlpha, roll - rollAmplitude)
		blitScrolled(canvas, near, nearOffset, width, height + rollAmplitude * 2f, nearAlpha, -roll - rollAmplitude)
	}

	private fun drawPrecipitation(canvas: Canvas, width: Float, height: Float, precipitation: Precipitation, windFactor: Float, timeSeconds: Float) {
		val random = Random(PRECIP_SEED)
		val count = (precipitationBaseCount(precipitation, width, height) * (0.4f + 0.6f * precipitation.observed)).roundToInt()
		val heavy = precipitation.severity >= HEAVY_SEVERITY

		when (precipitation.kind) {
			PrecipitationKind.SNOW -> drawSnow(canvas, width, height, count, windFactor, timeSeconds, random, heavy)
			PrecipitationKind.SLEET -> drawSleet(canvas, width, height, count, windFactor, timeSeconds, random)
			PrecipitationKind.RAIN -> drawRain(canvas, width, height, count, windFactor, timeSeconds, random, heavy)
		}
	}

	/** [heavy] turns the shower into a downpour: faster, longer, thicker, more slanted streaks on top of the higher particle count. */
	private fun drawRain(canvas: Canvas, width: Float, height: Float, count: Int, windFactor: Float, timeSeconds: Float, random: Random, heavy: Boolean) {
		paint.style = Paint.Style.STROKE
		paint.strokeCap = Paint.Cap.ROUND
		val slant = (if (heavy) 0.26f else 0.16f) + windFactor * 0.5f
		val speed = if (heavy) 1.4f else 1f
		val stretch = if (heavy) 1.5f else 1f
		val points = rainBuffer(count * 4)

		// Far layer: short, thin, dim streaks that read as distant drizzle, batched into one drawLines call.
		repeat(count) { i ->
			val x = random.nextFloat() * (width + 200f) - 100f
			val length = (10f + random.nextFloat() * 10f) * stretch
			val y = (random.nextFloat() * height + timeSeconds * 650f * speed) % (height + 120f) - 60f
			points[i * 4] = x
			points[i * 4 + 1] = y
			points[i * 4 + 2] = x + length * slant
			points[i * 4 + 3] = y + length
		}

		// No blur here: a per-frame BlurMaskFilter over hundreds of segments is what froze rain scenes.
		// Instead each batch is stroked twice — a wide faint halo under the thin core — so a streak fades out sideways rather than ending in a hard aliased edge.
		paint.strokeWidth = 5.5f
		paint.color = Color.argb(20, 205, 218, 238)
		canvas.drawLines(points, 0, count * 4, paint)

		paint.strokeWidth = 2.2f
		paint.color = Color.argb(42, 205, 218, 238)
		canvas.drawLines(points, 0, count * 4, paint)

		// Near layer: long, bright, sharp streaks in the foreground, reusing the same buffer.
		val nearCount = count / 2
		repeat(nearCount) { i ->
			val x = random.nextFloat() * (width + 200f) - 100f
			val length = (30f + random.nextFloat() * 22f) * stretch
			val y = (random.nextFloat() * height + timeSeconds * 1150f * speed) % (height + 120f) - 60f

			points[i * 4] = x
			points[i * 4 + 1] = y
			points[i * 4 + 2] = x + length * slant
			points[i * 4 + 3] = y + length
		}

		paint.strokeWidth = if (heavy) 8f else 6.5f
		paint.color = Color.argb(if (heavy) 55 else 45, 215, 226, 244)
		canvas.drawLines(points, 0, nearCount * 4, paint)

		paint.strokeWidth = if (heavy) 3.2f else 2.6f
		paint.color = Color.argb(if (heavy) 145 else 120, 215, 226, 244)
		canvas.drawLines(points, 0, nearCount * 4, paint)

		paint.style = Paint.Style.FILL
	}

	/**
	 * Two depth layers: small dim flakes drifting far away, big bright ones swaying up close — so it reads
	 * as snowfall, not stars. [heavy] means a blizzard: bigger, faster flakes leaning hard on the wind.
	 * Flakes are blits of the pre-blurred soft-dot sprites, so they stay smooth in motion instead of shimmering as hard-edged discs.
	 */
	private fun drawSnow(canvas: Canvas, width: Float, height: Float, count: Int, windFactor: Float, timeSeconds: Float, random: Random, heavy: Boolean) {
		val speed = if (heavy) 1.5f else 1f
		val size = if (heavy) 1.35f else 1f

		val farCount = count / 2
		repeat(farCount) {
			val baseX = random.nextFloat() * width
			val phase = random.nextFloat() * TAU
			val y = (random.nextFloat() * height + timeSeconds * 70f * speed) % height
			val sway = sin(timeSeconds * 0.7f + phase) * (width * 0.02f) + windFactor * (if (heavy) 30f else 14f)
			val radius = (random.nextFloat() * 1.4f + 1.2f) * size
			drawSoftDot(canvas, farFlakeSprite, baseX + sway, y, radius)
		}

		repeat(count - farCount) {
			val baseX = random.nextFloat() * width
			val phase = random.nextFloat() * TAU
			val y = (random.nextFloat() * height + timeSeconds * 165f * speed) % height
			val sway = sin(timeSeconds * 1.1f + phase) * (width * 0.045f) + windFactor * (if (heavy) 55f else 28f)
			val radius = (random.nextFloat() * 3.2f + 2.6f) * size
			drawSoftDot(canvas, nearFlakeSprite, baseX + sway, y, radius)
		}
	}

	/** Sleet: a wintry rain/snow mix — short, sharp icy streaks interleaved with small tumbling pellets. */
	private fun drawSleet(canvas: Canvas, width: Float, height: Float, count: Int, windFactor: Float, timeSeconds: Float, random: Random) {
		// Streaks: shorter and more vertical than rain, tinted cold, falling slower than a downpour.
		paint.style = Paint.Style.STROKE
		paint.strokeCap = Paint.Cap.ROUND
		val slant = 0.1f + windFactor * 0.3f
		val streakCount = count * 2 / 3
		val points = rainBuffer(streakCount * 4)

		repeat(streakCount) { i ->
			val x = random.nextFloat() * (width + 200f) - 100f
			val length = 14f + random.nextFloat() * 12f
			val y = (random.nextFloat() * height + timeSeconds * 820f) % (height + 120f) - 60f
			points[i * 4] = x
			points[i * 4 + 1] = y
			points[i * 4 + 2] = x + length * slant
			points[i * 4 + 3] = y + length
		}

		// The same halo-under-core double stroke as rain, so the icy streaks stay soft-edged too.
		paint.strokeWidth = 5.5f
		paint.color = Color.argb(38, 214, 228, 240)
		canvas.drawLines(points, 0, streakCount * 4, paint)

		paint.strokeWidth = 2.2f
		paint.color = Color.argb(118, 214, 228, 240)
		canvas.drawLines(points, 0, streakCount * 4, paint)

		paint.style = Paint.Style.FILL

		// Pellets: small icy grains tumbling between the streaks — faster and stiffer than snowflakes.
		val pelletCount = count / 2
		repeat(pelletCount) {
			val baseX = random.nextFloat() * width
			val phase = random.nextFloat() * TAU
			val y = (random.nextFloat() * height + timeSeconds * 240f) % height
			val sway = sin(timeSeconds * 1.6f + phase) * (width * 0.012f) + windFactor * 16f
			val radius = random.nextFloat() * 1.6f + 1.2f
			drawSoftDot(canvas, pelletSprite, baseX + sway, y, radius)
		}
	}

	private fun drawLightning(canvas: Canvas, width: Float, height: Float, timeSeconds: Float) {
		val phaseInCycle = timeSeconds % STRIKE_CYCLE
		if (phaseInCycle > FLASH_DURATION) {
			return
		}

		// One strike per cycle: a seeded bolt that flickers and fades, so most frames show no bolt at all.
		val t = phaseInCycle / FLASH_DURATION
		val intensity = ((1f - t) * (0.7f + 0.3f * sin(t * 40f))).coerceIn(0f, 1f)
		val random = Random(BOLT_SEED + (timeSeconds / STRIKE_CYCLE).toInt())

		paint.style = Paint.Style.FILL
		paint.color = Color.argb((95f * intensity).roundToInt(), 255, 255, 255)
		canvas.drawRect(0f, 0f, width, height, paint)

		var x = width * (0.3f + random.nextFloat() * 0.4f)
		var y = 0f
		boltPath.reset()
		boltPath.moveTo(x, y)

		val segment = height * 0.62f / BOLT_STEPS
		var forkX = x
		var forkY = y

		repeat(BOLT_STEPS) { step ->
			x += (random.nextFloat() - 0.5f) * width * 0.18f
			y += segment

			boltPath.lineTo(x, y)

			if (step == BOLT_STEPS / 2 - 1) {
				forkX = x
				forkY = y
			}
		}

		// A thinner branch splitting off mid-bolt makes the strike look like lightning rather than a zigzag line.
		forkPath.reset()
		forkPath.moveTo(forkX, forkY)
		val forkDirection = if (random.nextFloat() < 0.5f) -1f else 1f

		repeat(3) {
			forkX += forkDirection * (0.04f + random.nextFloat() * 0.08f) * width
			forkY += segment * 0.7f
			forkPath.lineTo(forkX, forkY)
		}

		paint.style = Paint.Style.STROKE
		paint.strokeCap = Paint.Cap.ROUND
		paint.strokeJoin = Paint.Join.ROUND
		paint.strokeWidth = 14f
		paint.color = Color.argb((110f * intensity).roundToInt(), 255, 244, 200)
		canvas.drawPath(boltPath, paint)

		paint.strokeWidth = 8f
		paint.color = Color.argb((80f * intensity).roundToInt(), 255, 244, 200)
		canvas.drawPath(forkPath, paint)

		paint.strokeWidth = 5f
		paint.color = Color.argb((255f * intensity).roundToInt(), 255, 253, 235)
		canvas.drawPath(boltPath, paint)

		paint.strokeWidth = 3f
		paint.color = Color.argb((210f * intensity).roundToInt(), 255, 253, 235)
		canvas.drawPath(forkPath, paint)

		paint.style = Paint.Style.FILL
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
		paint.shader = RadialGradient(width * 0.5f, height * 0.42f, radius, intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(90, 0, 0, 0)), floatArrayOf(0.5f, 1f), Shader.TileMode.CLAMP)
		canvas.drawRect(0f, 0f, width, height, paint)
		paint.shader = null
	}

	/**
	 * Dissolves the bottom [fadeFraction] of a soft-mass tile so the BlurMaskFilter clip doesn't end in a hard band.
	 * The DST_IN rect must cover the whole tile: a rect starting at the fade line gets an anti-aliased top edge, and under DST_IN that partial coverage carves a one-pixel alpha dip — which the blit stretch then widens into a visible seam across the deck.
	 * The ramp eases through smoothstep samples instead of falling linearly, because a linear ramp kinks at the fade line and the eye picks the kink up as a Mach band.
	 */
	private fun fadeTileBottom(canvas: Canvas, fadeFraction: Float = 0.4f) {
		val bounds = canvas.clipBounds
		val w = bounds.width().toFloat()
		val h = bounds.height().toFloat()
		val fadeStart = 1f - fadeFraction

		val alphas = intArrayOf(255, 255, 244, 215, 128, 40, 0)
		val colors = IntArray(alphas.size) { withAlpha(Color.BLACK, alphas[it]) }
		val stops = floatArrayOf(0f, fadeStart, fadeStart + fadeFraction * 0.125f, fadeStart + fadeFraction * 0.25f, fadeStart + fadeFraction * 0.5f, fadeStart + fadeFraction * 0.75f, 1f)

		paint.style = Paint.Style.FILL
		paint.shader = LinearGradient(0f, 0f, 0f, h, colors, stops, Shader.TileMode.CLAMP)
		paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
		canvas.drawRect(0f, 0f, w, h, paint)
		paint.xfermode = null
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

	/** Blits [sprite] as a square of [radius] around a center at [alpha], restoring the shared paint's opacity afterwards. */
	private fun blitSprite(canvas: Canvas, sprite: Bitmap, centerX: Float, centerY: Float, radius: Float, alpha: Int) {
		spritePaint.alpha = alpha.coerceIn(0, 255)
		spriteDest.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
		canvas.drawBitmap(sprite, null, spriteDest, spritePaint)
		spritePaint.alpha = 255
	}

	/** The sun/moon glow rasterised once per scene: a radial falloff from the full-alpha core color, blitted at the breathing size each frame. */
	private fun buildHaloSprite(canvas: Canvas, core: Int) {
		val center = HALO_SPRITE_SIZE / 2f

		val brush = Paint(Paint.ANTI_ALIAS_FLAG)
		brush.shader = RadialGradient(center, center, center, intArrayOf(core, withAlpha(core, 0)), floatArrayOf(0.25f, 1f), Shader.TileMode.CLAMP)
		canvas.drawCircle(center, center, center, brush)
	}

	private fun rainBuffer(size: Int): FloatArray {
		if (rainPoints.size < size) {
			rainPoints = FloatArray(size)
		}

		return rainPoints
	}

	companion object {
		private const val STAR_SEED = 1L
		private const val CLOUD_SEED = 2L
		private const val PRECIP_SEED = 3L
		private const val BOLT_SEED = 5L
		private const val STAR_AREA_PER_STAR = 22_000f
		private const val BOLT_STEPS = 6
		private const val STRIKE_CYCLE = 2.8f
		private const val FLASH_DURATION = 0.45f
		private const val TAU = 6.2831855f

		/**
		 * Soft cloud/fog tiles are built at a quarter of the surface resolution and stretched at blit time —
		 * they're heavily blurred anyway, and building them full-size stalls the first frames of a scene
		 * (BlurMaskFilter rasterisation scales with area, 16x cheaper here).
		 */
		private const val TILE_DOWNSCALE = 4f

		/** Above this cloud cover the sky reads as a solid deck: the sun/moon disappears and the drift layers churn. */
		private const val CLOUD_DECK_THRESHOLD = 0.75f

		/** Severity at and above which rain becomes a downpour and snow a blizzard (thicker, faster, more slanted). */
		private const val HEAVY_SEVERITY = 0.85f

		/** Edge length of the square soft-dot sprites — big enough that even the largest blizzard flake only ever downscales it. */
		private const val SPRITE_SIZE = 64

		/** Edge length of the celestial halo sprite; the falloff is smooth enough that upscaling to the on-screen glow stays clean. */
		private const val HALO_SPRITE_SIZE = 256

		/** Fraction of a soft-dot sprite's radius that is solid color before the fade to transparent begins. */
		private const val DOT_CORE_STOP = 0.5f
	}
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

/** The sun/moon shows only through a dry, fog-free sky that isn't a solid cloud deck. */
private fun showsCelestialBody(params: SceneParams) = params.precipitation == null && params.fogDensity <= 0f && params.cloudiness <= 0.75f

private fun showsHaze(params: SceneParams) = params.precipitation != null || params.fogDensity > 0f || params.cloudiness > 0.75f

/**
 * Base particle count before the observed-intensity modulation. Screen area over a divisor that shrinks
 * (more particles) as severity climbs; the anchor points reproduce the hand-tuned densities for
 * drizzle, steady rain, storm rain, downpours, steady snow, and blizzards.
 */
private fun precipitationBaseCount(precipitation: Precipitation, width: Float, height: Float): Int {
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

private fun celestialHeightFraction(dayPhase: DayPhase) = when (dayPhase) {
	DayPhase.DAY -> 0.2f
	DayPhase.DAWN -> 0.34f
	DayPhase.DUSK -> 0.34f
	DayPhase.NIGHT -> 0.24f
}

private fun sunColor(dayPhase: DayPhase) = when (dayPhase) {
	DayPhase.DAWN -> Color.rgb(255, 198, 140)
	DayPhase.DUSK -> Color.rgb(255, 170, 120)
	else -> Color.rgb(255, 243, 176)
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

private fun darken(color: Int, factor: Float) =
	Color.rgb((Color.red(color) * factor).roundToInt(), (Color.green(color) * factor).roundToInt(), (Color.blue(color) * factor).roundToInt())

/** Blends [color] toward white by [factor] (0 = unchanged, 1 = white) — the silvery highlight on lit cloud tops. */
private fun lighten(color: Int, factor: Float) = Color.rgb(
	(Color.red(color) + (255 - Color.red(color)) * factor).roundToInt(),
	(Color.green(color) + (255 - Color.green(color)) * factor).roundToInt(),
	(Color.blue(color) + (255 - Color.blue(color)) * factor).roundToInt()
)

private fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
