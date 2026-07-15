package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
import kotlin.math.cos
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
import androidx.core.graphics.withClip
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.weather.SEVERITY_DRIZZLE
import xyz.attacktive.weatherd.domain.weather.SEVERITY_STEADY
import xyz.attacktive.weatherd.domain.weather.SEVERITY_STORM

/**
 * Draws a procedural weather scene onto a Canvas. Split into a static [renderBackdrop] (sky, overcast
 * ceiling, fog base, haze, vignette — cache it) and an animated [renderForeground] (twinkling stars,
 * a glowing sun/moon, the horizon scenery, drifting clouds/overcast/mist, precipitation, lightning) advanced by [timeSeconds].
 * Soft drifting layers (clouds, overcast, fog) are pre-rendered once into scrolling tiles, so the
 * per-frame cost is a handful of cheap blits rather than a fresh CPU-side blur every frame.
 */
class SceneRenderer {
	private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val blitPaint = Paint(Paint.FILTER_BITMAP_FLAG)
	private val blitDest = RectF()
	private val boltPath = Path()
	private val forkPath = Path()
	private val birdPath = Path()
	private val sceneryFarPath = Path()
	private val sceneryNearPath = Path()
	private val sceneryAccentPath = Path()
	private var sceneryKey: String? = null
	private val tiles = HashMap<String, Bitmap>()
	private var tilesKey: String? = null
	private var rainPoints = FloatArray(0)
	private val spritePaint = Paint(Paint.FILTER_BITMAP_FLAG)
	private val spriteDest = RectF()

	private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		textAlign = Paint.Align.CENTER
		letterSpacing = 0.03f
	}

	// Per-frame lightning state, recomputed by [updateLightning] before anything that reacts to a flash draws.
	private var flashWash = 0f
	private var flashBolt = 0f
	private var flashSheet = false
	private var flashSlot = 0

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

	/** The animated layers (stars, sun/moon glow, horizon scenery, drifting clouds/overcast/mist, precipitation, lightning). */
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
			drawShootingStar(canvas, w, h, timeSeconds)
		}

		if (showsCelestialBody(params)) {
			drawCelestialBody(canvas, w, h, params, timeSeconds)
		}

		if (showsBirds(params)) {
			drawBirds(canvas, w, h, timeSeconds, params.dayPhase)
		}

		if (params.cloudiness > 0.1f && params.cloudiness <= 0.55f) {
			drawScatteredClouds(canvas, w, h, params, timeSeconds)
		}

		if (params.cloudiness > CLOUD_DECK_THRESHOLD || params.precipitation != null) {
			drawCloudDrift(canvas, w, h, params, timeSeconds)
		}

		// The scenery draws after the celestial body and clouds (they belong to the sky behind it) but before fog, rain, and lightning (weather happens in front of the horizon).
		if (params.backdropScene != BackdropScene.NONE) {
			drawScenery(canvas, w, h, params)
		}

		if (params.fogDensity > 0f) {
			drawFogDrift(canvas, w, h, timeSeconds)
		}

		if (params.thunder) {
			updateLightning(timeSeconds)
		} else {
			flashWash = 0f
			flashBolt = 0f
		}

		if (params.precipitation != null) {
			drawPrecipitation(canvas, w, h, params.precipitation, params.windFactor, timeSeconds, flashWash)
		}

		if (params.thunder) {
			drawLightning(canvas, w, h)
		}

		params.overlayLabels?.let {
			drawOverlayLabels(canvas, w, h, it)
		}
	}

	/**
	 * The optional text overlay, drawn above everything so no weather ever obscures it.
	 *
	 * It rides at the top of the sky, just under the status bar. Everywhere lower is spoken for: the scenery's
	 * silhouettes own the bottom third and are far too busy to read text against, while below them a launcher's
	 * dock and the lock screen's shortcuts claim the rest. The one thing that shares this band is the sun or
	 * moon drifting through, so the text keeps a shadow and simply draws over it.
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

	/**
	 * The user's chosen horizon silhouettes: two depth planes tinted from the current sky's bottom color, so storm gloom, snow milkiness, and night all carry onto them for free.
	 * The geometry rebuilds only when the scene or the surface size changes; every frame after that is two cached path fills.
	 */
	private fun drawScenery(canvas: Canvas, width: Float, height: Float, params: SceneParams) {
		val key = "${params.backdropScene}-${width.toInt()}x${height.toInt()}"
		if (key != sceneryKey) {
			val outlines = sceneryOutlinesFor(params.backdropScene, width / height) ?: return
			fillSceneryPath(sceneryFarPath, outlines.far, width, height)
			fillSceneryPath(sceneryNearPath, outlines.near, width, height)
			fillAccentPath(sceneryAccentPath, outlines.accents, width, height)
			sceneryKey = key
		}

		val skyBottom = skyGradientFor(params).bottomColor
		val nearColor = darken(skyBottom, 0.2f)

		paint.style = Paint.Style.FILL
		paint.color = lerpColor(skyBottom, nearColor, 0.55f)
		canvas.drawPath(sceneryFarPath, paint)
		paint.color = nearColor
		canvas.drawPath(sceneryNearPath, paint)

		if (!sceneryAccentPath.isEmpty) {
			paint.style = Paint.Style.STROKE
			paint.strokeWidth = height * 0.0022f
			canvas.drawPath(sceneryAccentPath, paint)
			paint.style = Paint.Style.FILL
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
			val x = random.nextFloat() * width
			val y = random.nextFloat() * height * 0.62f
			val radius = random.nextFloat() * 1.6f + 0.6f
			val baseAlpha = random.nextFloat() * 150f + 80f
			val phase = random.nextFloat() * TAU
			val bright = random.nextFloat() < 0.14f

			// Every star used to twinkle at one shared 2.2 rad/s — synchronized twinkle reads as a screensaver. Each now has its own rate, and the bright ones breathe slowly instead of flickering.
			val frequency = 1.4f + random.nextFloat() * 1.7f
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
	 * At most one meteor per slot and most slots stay empty, so a clear night earns a rare treat rather
	 * than a fireworks show. The streak flies a seeded straight line under a sine envelope, swelling and
	 * dying instead of blinking in and out — and like everything else it is a pure function of time.
	 */
	private fun drawShootingStar(canvas: Canvas, width: Float, height: Float, timeSeconds: Float) {
		val slot = (timeSeconds / METEOR_SLOT_SECONDS).toInt()
		val random = Random(METEOR_SEED + slot)
		val quiet = random.nextFloat() < 0.55f
		if (quiet) {
			return
		}

		val start = random.nextFloat() * (METEOR_SLOT_SECONDS - METEOR_DURATION - 1f)
		val local = timeSeconds - slot * METEOR_SLOT_SECONDS - start
		if (local !in 0f..METEOR_DURATION) {
			return
		}

		val progress = local / METEOR_DURATION
		val envelope = sin(progress * PI_F)
		val fromX = width * (0.15f + random.nextFloat() * 0.6f)
		val fromY = height * (0.06f + random.nextFloat() * 0.22f)
		val angle = (20f + random.nextFloat() * 25f) * DEGREES_TO_RADIANS
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

	private fun drawCelestialBody(canvas: Canvas, width: Float, height: Float, params: SceneParams, timeSeconds: Float) {
		val centerX = width * 0.72f
		val centerY = height * celestialHeightFraction(params.dayPhase, params.celestialProgress)
		val radius = width * 0.1f
		val moon = params.dayPhase == DayPhase.NIGHT
		val core = if (moon) {
			Color.rgb(232, 238, 247)
		} else {
			sunColor(params.dayPhase)
		}

		// A crescent sheds far less light than a full disc, so the whole glow scales with the lit fraction.
		val litFraction = if (moon) {
			(1f - cos(params.moonPhase * TAU)) / 2f
		} else {
			1f
		}

		val litScale = 0.35f + 0.65f * litFraction

		/*
		 * Two-sine breathing halo: a slow deep swell with a faster shimmer on top, so the glow visibly
		 * blooms and recedes instead of subtly wobbling. Two blits of one pre-rendered radial sprite deepen
		 * the bloom — building RadialGradients here churned two shader allocations every frame.
		 */
		val halo = tile("celestialHalo", HALO_SPRITE_SIZE, HALO_SPRITE_SIZE) { buildHaloSprite(it, core) }
		val pulse = 0.5f + 0.35f * sin(timeSeconds * 0.8f) + 0.15f * sin(timeSeconds * 2.1f)
		val glowRadius = radius * (2.2f + 1.1f * pulse)
		val glowAlpha = ((80f + 130f * pulse) * litScale).roundToInt().coerceIn(0, 255)
		blitSprite(canvas, halo, centerX, centerY, glowRadius, glowAlpha)

		// Wide, faint outer bloom breathing in counter-phase, so something is always in motion.
		val outerRadius = radius * (3.6f + 0.9f * (1f - pulse))
		val outerAlpha = ((26f + 34f * (1f - pulse)) * litScale).roundToInt()
		blitSprite(canvas, halo, centerX, centerY, outerRadius, outerAlpha)

		if (moon) {
			// The disc is a sprite shaped by the real synodic phase — tonight's sky and the wallpaper agree on the moon.
			val phaseIndex = (params.moonPhase * MOON_PHASE_STEPS).roundToInt()
			val moonSprite = tile("moon-$phaseIndex", MOON_SPRITE_SIZE, MOON_SPRITE_SIZE) { buildMoonSprite(it, core, params.moonPhase) }
			blitSprite(canvas, moonSprite, centerX, centerY, radius / MOON_DISC_MARGIN, 255)
		} else {
			paint.style = Paint.Style.FILL
			paint.color = core
			canvas.drawCircle(centerX, centerY, radius, paint)
		}
	}

	/**
	 * The moon rasterised once per phase step: the lit shape bounded by the circular limb and the elliptical terminator, and the craters clipped to the lit side.
	 * The dark side deliberately draws nothing — the sky shows straight through, keeping the moon stylised rather than realistic.
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
		 * Bob and swell each sum two incommensurate sines so the churn never repeats on a visible period,
		 * and gusts surge the whole deck forward and back as a bounded displacement on the constant scroll.
		 * Each layer's top is overscanned past the screen edge by its own bob amplitude, so the bob never wobbles the tile's hard-clipped top edge into view.
		 */
		val bobAmplitude = height * 0.022f
		val bob = bobAmplitude * (0.65f * sin(timeSeconds * 0.4f) + 0.35f * sin(timeSeconds * 1.07f))
		val frontAlpha = (200f + 55f * (0.7f * sin(timeSeconds * 0.55f) + 0.3f * sin(timeSeconds * 1.31f))).roundToInt()
		val surge = width * 0.012f * params.windFactor
		val drift = surge * (0.6f * sin(timeSeconds * 0.19f) + 0.4f * sin(timeSeconds * 0.47f))
		val backOffset = wrapOffset(timeSeconds * (14f + params.windFactor * 18f) + drift, width)
		val frontOffset = wrapOffset(timeSeconds * (38f + params.windFactor * 48f) + drift * 1.8f, width)
		blitScrolled(canvas, back, backOffset, width, destHeight + bobAmplitude, 255, bob - bobAmplitude)
		blitScrolled(canvas, front, frontOffset, width, destHeight + bobAmplitude * 1.5f, frontAlpha, -bob * 1.5f - bobAmplitude * 1.5f)
	}

	/**
	 * A small flock crossing every few minutes on fair days: staggered wing glyphs with phase-offset
	 * wingbeats and a light vertical bob. Slot-scheduled like meteors and lightning, so most of the time
	 * the sky is empty and a crossing stays a treat. Drawn behind the scattered puffs for depth.
	 */
	private fun drawBirds(canvas: Canvas, width: Float, height: Float, timeSeconds: Float, dayPhase: DayPhase) {
		val slot = (timeSeconds / BIRD_SLOT_SECONDS).toInt()
		val random = Random(BIRD_SEED + slot)
		val quiet = random.nextFloat() < 0.45f
		if (quiet) {
			return
		}

		val start = random.nextFloat() * (BIRD_SLOT_SECONDS - BIRD_CROSSING_SECONDS)
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

		val flockSize = 3 + (random.nextFloat() * 3f).toInt()
		val baseY = height * (0.14f + random.nextFloat() * 0.12f)
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

	private fun drawScatteredClouds(canvas: Canvas, width: Float, height: Float, params: SceneParams, timeSeconds: Float) {
		// Gentler downscale than the mass tiles: puffs have visible edges and a highlight rim to preserve.
		val destHeight = height * 0.6f
		val tileWidth = (width / 2f).toInt()
		val tileHeight = (destHeight / 2f).toInt()
		val cloudColor = withAlpha(cloudTint(params.dayPhase), (150f + params.cloudiness * 80f).roundToInt().coerceAtMost(235))

		/*
		 * Two depth layers: sparse, small, dim puffs creeping high in the back, the full billows in front —
		 * fair-weather skies get the parallax the overcast deck already has, instead of one flat sheet.
		 */
		val farColor = withAlpha(cloudTint(params.dayPhase), (Color.alpha(cloudColor) * 0.6f).roundToInt())
		val far = tile("scatteredFar", tileWidth, tileHeight) {
			buildScatteredTile(it, tileWidth.toFloat(), tileHeight.toFloat(), params, farColor, puffScale = 0.55f, countFactor = 0.6f, baselineLift = 0.16f, seed = CLOUD_SEED + 1L)
		}

		val near = tile("scattered", tileWidth, tileHeight) {
			buildScatteredTile(it, tileWidth.toFloat(), tileHeight.toFloat(), params, cloudColor, puffScale = 1f, countFactor = 1f, baselineLift = 0f, seed = CLOUD_SEED)
		}

		// The same bounded gust surge as the deck, so fair-weather puffs answer to the one wind too.
		val surge = width * 0.01f * params.windFactor
		val drift = surge * (0.6f * sin(timeSeconds * 0.19f) + 0.4f * sin(timeSeconds * 0.47f))
		val farOffset = wrapOffset(timeSeconds * (11f + params.windFactor * 18f) + drift * 0.6f, width)
		val nearOffset = wrapOffset(timeSeconds * (20f + params.windFactor * 34f) + drift, width)
		blitScrolled(canvas, far, farOffset, width, destHeight, 255)
		blitScrolled(canvas, near, nearOffset, width, destHeight, 255)
	}

	private fun buildScatteredTile(canvas: Canvas, width: Float, height: Float, params: SceneParams, color: Int, puffScale: Float, countFactor: Float, baselineLift: Float, seed: Long) {
		val body = Paint(Paint.ANTI_ALIAS_FLAG)
		body.style = Paint.Style.FILL
		body.color = color
		body.maskFilter = BlurMaskFilter(width * 0.02f, BlurMaskFilter.Blur.NORMAL)

		// A lighter copy peeking over the top edge reads as sun/moonlight catching the cloud tops.
		val highlight = Paint(Paint.ANTI_ALIAS_FLAG)
		highlight.style = Paint.Style.FILL
		highlight.color = withAlpha(lighten(cloudTint(params.dayPhase), 0.55f), (Color.alpha(color) * 0.9f).roundToInt())
		highlight.maskFilter = BlurMaskFilter(width * 0.018f, BlurMaskFilter.Blur.NORMAL)

		val random = Random(seed)
		val count = ((2 + params.cloudiness * 5f) * countFactor).roundToInt().coerceAtLeast(1)

		repeat(count) {
			val cx = random.nextFloat() * width
			val baseline = height * (0.35f - baselineLift + random.nextFloat() * 0.45f)
			val scale = width * (0.07f + random.nextFloat() * 0.05f) * puffScale

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
		val breath = 0.5f + 0.5f * (0.7f * sin(timeSeconds * 0.45f) + 0.3f * sin(timeSeconds * 1.13f))
		val rollAmplitude = height * 0.03f
		val roll = rollAmplitude * (0.7f * sin(timeSeconds * 0.25f) + 0.3f * sin(timeSeconds * 0.73f))
		val farOffset = (((timeSeconds * -22f) % width) + width) % width
		val nearOffset = (timeSeconds * 32f) % width

		// A very slow clearing envelope on top of the breath: the banks thin out for a stretch, then close back in.
		val clearing = 0.82f + 0.18f * sin(timeSeconds * 0.043f)
		val farAlpha = ((100f + 130f * breath) * clearing).roundToInt()
		val nearAlpha = ((100f + 130f * (1f - breath)) * clearing).roundToInt()

		// Both ends are overscanned by the roll amplitude, so the roll never wobbles a hard-clipped tile edge (or a bare gap) into view.
		blitScrolled(canvas, far, farOffset, width, height + rollAmplitude * 2f, farAlpha, roll - rollAmplitude)
		blitScrolled(canvas, near, nearOffset, width, height + rollAmplitude * 2f, nearAlpha, -roll - rollAmplitude)
	}

	/**
	 * The shared wind signal: windFactor scaled by two incommensurate sines, so every wind-driven layer
	 * leans and eases together in gusts instead of holding one constant slant. Only amplitudes modulate —
	 * a frequency or velocity change multiplied by a large timeSeconds teleports whatever it drives.
	 */
	private fun gustFactor(timeSeconds: Float, windFactor: Float) = windFactor * (0.7f + 0.21f * sin(timeSeconds * 0.23f) + 0.09f * sin(timeSeconds * 0.67f))

	/** Positive modulo for scroll offsets, so a gust displacement can swing them briefly negative without breaking the wrap. */
	private fun wrapOffset(offset: Float, width: Float) = ((offset % width) + width) % width

	/**
	 * A cheap hash of particle index and fall cycle onto 0..1, so every particle re-enters on a fresh lane
	 * each time it wraps instead of re-falling one path forever. Pure arithmetic — no allocation, no RNG
	 * state — so particle motion stays a pure function of time.
	 */
	private fun laneFraction(index: Int, cycle: Int): Float {
		var mixed = index * 374_761_393 + cycle * 668_265_263
		mixed = (mixed xor (mixed ushr 13)) * 1_274_126_177
		mixed = mixed xor (mixed ushr 16)

		return (mixed ushr 8) / 16_777_216f
	}

	/** Precipitation density breathes ±15% over half-minute swells, so the fall reads as squalls instead of a constant static. */
	private fun squallFactor(timeSeconds: Float) = 0.85f + 0.15f * (0.6f * sin(timeSeconds * 0.21f) + 0.4f * sin(timeSeconds * 0.53f))

	private fun drawPrecipitation(canvas: Canvas, width: Float, height: Float, precipitation: Precipitation, windFactor: Float, timeSeconds: Float, flash: Float) {
		val observedFactor = 0.4f + 0.6f * precipitation.observed
		val count = (precipitationBaseCount(precipitation, width, height) * observedFactor).roundToInt()

		/*
		 * Only the dim far layers breathe with the squall factor: a particle popping into existence
		 * mid-fall is a teleport, imperceptible at the far layers' alphas but exactly what the bright
		 * near layers must never show — so those hold the steady count.
		 */
		val squallCount = (count * squallFactor(timeSeconds)).roundToInt()
		val heavy = precipitation.severity >= HEAVY_SEVERITY

		when (precipitation.kind) {
			PrecipitationKind.SNOW -> drawSnow(canvas, width, height, count, squallCount, windFactor, timeSeconds, heavy)
			PrecipitationKind.SLEET -> drawSleet(canvas, width, height, count, windFactor, timeSeconds, flash)
			PrecipitationKind.RAIN -> drawRain(canvas, width, height, count, squallCount, windFactor, timeSeconds, heavy, flash)
		}
	}

	/** [heavy] turns the shower into a downpour: faster, longer, thicker, more slanted streaks on top of the higher particle count. */
	private fun drawRain(canvas: Canvas, width: Float, height: Float, count: Int, squallCount: Int, windFactor: Float, timeSeconds: Float, heavy: Boolean, flash: Float) {
		paint.style = Paint.Style.STROKE
		paint.strokeCap = Paint.Cap.ROUND

		val speed = if (heavy) {
			1.4f
		} else {
			1f
		}

		val stretch = if (heavy) {
			1.5f
		} else {
			1f
		}

		val slantBase = if (heavy) {
			0.26f
		} else {
			0.16f
		}

		val slant = slantBase + gustFactor(timeSeconds, windFactor) * 0.71f
		val span = height + RAIN_WRAP_PAD * 2f
		val nearCount = count / 2

		drawFarRain(canvas, width, height, squallCount, span, slant, stretch, speed, timeSeconds, flash)
		drawNearRain(canvas, width, height, nearCount, span, slant, stretch, speed, timeSeconds, heavy, flash)
		drawCloseDrops(canvas, width, height, nearCount, span, slant, stretch, speed, timeSeconds, heavy, flash)

		paint.style = Paint.Style.FILL
	}

	/** Far layer: short, thin, dim streaks that read as distant drizzle, batched into one drawLines call. */
	private fun drawFarRain(canvas: Canvas, width: Float, height: Float, squallCount: Int, span: Float, slant: Float, stretch: Float, speed: Float, timeSeconds: Float, flash: Float) {
		// Each streak picks a fresh lane per fall cycle, so no drop re-falls one fixed path forever.
		// Every layer seeds its own Random: per-particle constants must never depend on another layer's breathing count, or one ±1 tick reshuffles every draw after it and whole layers teleport.
		val farRandom = Random(PRECIP_SEED)
		val points = rainBuffer(squallCount * 4)
		repeat(squallCount) { i ->
			val length = (10f + farRandom.nextFloat() * 10f) * stretch
			val travel = farRandom.nextFloat() * height + timeSeconds * 650f * speed
			val cycle = (travel / span).toInt()
			val x = laneFraction(i * 2, cycle) * (width + 200f) - 100f
			val y = travel % span - RAIN_WRAP_PAD
			points[i * 4] = x
			points[i * 4 + 1] = y
			points[i * 4 + 2] = x + length * slant
			points[i * 4 + 3] = y + length
		}

		// No blur here: a per-frame BlurMaskFilter over hundreds of segments is what froze rain scenes.
		// Instead each batch is stroked twice — a wide faint halo under the thin core — so a streak fades out sideways rather than ending in a hard aliased edge.
		paint.strokeWidth = 5.5f
		paint.color = Color.argb(gleam(20, flash), 205, 218, 238)
		canvas.drawLines(points, 0, squallCount * 4, paint)

		paint.strokeWidth = 2.2f
		paint.color = Color.argb(gleam(42, flash), 205, 218, 238)
		canvas.drawLines(points, 0, squallCount * 4, paint)
	}

	/** Near layer: long, bright, sharp streaks in the foreground, reusing the same buffer as the far pass. */
	private fun drawNearRain(canvas: Canvas, width: Float, height: Float, nearCount: Int, span: Float, slant: Float, stretch: Float, speed: Float, timeSeconds: Float, heavy: Boolean, flash: Float) {
		val nearRandom = Random(PRECIP_SEED + 1L)
		val points = rainBuffer(nearCount * 4)
		repeat(nearCount) { i ->
			val length = (30f + nearRandom.nextFloat() * 22f) * stretch
			val travel = nearRandom.nextFloat() * height + timeSeconds * 1150f * speed
			val cycle = (travel / span).toInt()
			val x = laneFraction(i * 2 + 1, cycle) * (width + 200f) - 100f
			val y = travel % span - RAIN_WRAP_PAD

			points[i * 4] = x
			points[i * 4 + 1] = y
			points[i * 4 + 2] = x + length * slant
			points[i * 4 + 3] = y + length
		}

		val nearHaloAlpha = if (heavy) {
			55
		} else {
			45
		}

		paint.strokeWidth = if (heavy) {
			8f
		} else {
			6.5f
		}

		paint.color = Color.argb(gleam(nearHaloAlpha, flash), 215, 226, 244)
		canvas.drawLines(points, 0, nearCount * 4, paint)

		val nearCoreAlpha = if (heavy) {
			145
		} else {
			120
		}

		paint.strokeWidth = if (heavy) {
			3.2f
		} else {
			2.6f
		}

		paint.color = Color.argb(gleam(nearCoreAlpha, flash), 215, 226, 244)
		canvas.drawLines(points, 0, nearCount * 4, paint)
	}

	/** A sparse pass of standout drops — longer, thicker, brighter and faster than the near layer — so a shower has texture instead of uniform static. */
	private fun drawCloseDrops(canvas: Canvas, width: Float, height: Float, nearCount: Int, span: Float, slant: Float, stretch: Float, speed: Float, timeSeconds: Float, heavy: Boolean, flash: Float) {
		val closeCount = nearCount / 12
		if (closeCount == 0) {
			return
		}

		val random = Random(PRECIP_SEED + 2L)
		val points = rainBuffer(closeCount * 4)
		repeat(closeCount) { i ->
			val length = (46f + random.nextFloat() * 26f) * stretch
			val travel = random.nextFloat() * height + timeSeconds * 1450f * speed
			val cycle = (travel / span).toInt()
			val x = laneFraction(i + CLOSE_DROP_LANE_OFFSET, cycle) * (width + 200f) - 100f
			val y = travel % span - RAIN_WRAP_PAD
			points[i * 4] = x
			points[i * 4 + 1] = y
			points[i * 4 + 2] = x + length * slant
			points[i * 4 + 3] = y + length
		}

		paint.style = Paint.Style.STROKE
		paint.strokeCap = Paint.Cap.ROUND
		paint.strokeWidth = if (heavy) {
			9.5f
		} else {
			8f
		}

		paint.color = Color.argb(gleam(60, flash), 222, 232, 248)
		canvas.drawLines(points, 0, closeCount * 4, paint)

		paint.strokeWidth = if (heavy) {
			4.2f
		} else {
			3.6f
		}

		paint.color = Color.argb(gleam(185, flash), 226, 236, 250)
		canvas.drawLines(points, 0, closeCount * 4, paint)
	}

	/**
	 * Two depth layers: small dim flakes drifting far away, big bright ones swaying up close — so it reads
	 * as snowfall, not stars. [heavy] means a blizzard: bigger, faster flakes leaning hard on the wind.
	 * Flakes are blits of the pre-blurred soft-dot sprites, so they stay smooth in motion instead of shimmering as hard-edged discs.
	 */
	private fun drawSnow(canvas: Canvas, width: Float, height: Float, count: Int, squallCount: Int, windFactor: Float, timeSeconds: Float, heavy: Boolean) {
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

		val gust = gustFactor(timeSeconds, windFactor)

		// Lane and sway phase re-hash every wrap, and the wrap spans a pad past both edges so soft dots never pop at the border.
		// Per-layer Randoms and a steady near count keep flakes from teleporting when the squall factor ticks.
		val span = height + FLAKE_WRAP_PAD * 2f
		val farRandom = Random(PRECIP_SEED)
		val farLean = if (heavy) {
			43f
		} else {
			20f
		}

		repeat(squallCount / 2) { i ->
			val travel = farRandom.nextFloat() * height + timeSeconds * 70f * speed
			val cycle = (travel / span).toInt()
			val baseX = laneFraction(i * 2, cycle) * width
			val phase = laneFraction(i * 2, cycle + SWAY_PHASE_SALT) * TAU
			val y = travel % span - FLAKE_WRAP_PAD
			val sway = sin(timeSeconds * 0.7f + phase) * (width * 0.02f) + gust * farLean
			val radius = (farRandom.nextFloat() * 1.4f + 1.2f) * size
			drawSoftDot(canvas, farFlakeSprite, baseX + sway, y, radius)
		}

		val nearRandom = Random(PRECIP_SEED + 1L)
		val nearLean = if (heavy) {
			79f
		} else {
			40f
		}

		repeat(count - count / 2) { i ->
			val travel = nearRandom.nextFloat() * height + timeSeconds * 165f * speed
			val cycle = (travel / span).toInt()
			val baseX = laneFraction(i * 2 + 1, cycle) * width
			val phase = laneFraction(i * 2 + 1, cycle + SWAY_PHASE_SALT) * TAU
			val y = travel % span - FLAKE_WRAP_PAD
			val sway = sin(timeSeconds * 1.1f + phase) * (width * 0.045f) + gust * nearLean
			val radius = (nearRandom.nextFloat() * 3.2f + 2.6f) * size
			drawSoftDot(canvas, nearFlakeSprite, baseX + sway, y, radius)
		}
	}

	/** Sleet: a wintry rain/snow mix — short, sharp icy streaks interleaved with small tumbling pellets. */
	private fun drawSleet(canvas: Canvas, width: Float, height: Float, count: Int, windFactor: Float, timeSeconds: Float, flash: Float) {
		// Streaks: shorter and more vertical than rain, tinted cold, falling slower than a downpour.
		// Sleet skips squall breathing entirely; both its layers are bright enough that a mid-fall pop would show.
		paint.style = Paint.Style.STROKE
		paint.strokeCap = Paint.Cap.ROUND

		val gust = gustFactor(timeSeconds, windFactor)
		val slant = 0.1f + gust * 0.43f
		val streakCount = count * 2 / 3
		val streakRandom = Random(PRECIP_SEED)
		val points = rainBuffer(streakCount * 4)

		val span = height + 120f
		repeat(streakCount) { i ->
			val length = 14f + streakRandom.nextFloat() * 12f
			val travel = streakRandom.nextFloat() * height + timeSeconds * 820f
			val cycle = (travel / span).toInt()
			val x = laneFraction(i * 2, cycle) * (width + 200f) - 100f
			val y = travel % span - 60f
			points[i * 4] = x
			points[i * 4 + 1] = y
			points[i * 4 + 2] = x + length * slant
			points[i * 4 + 3] = y + length
		}

		// The same halo-under-core double stroke as rain, so the icy streaks stay soft-edged too.
		paint.strokeWidth = 5.5f
		paint.color = Color.argb(gleam(38, flash), 214, 228, 240)
		canvas.drawLines(points, 0, streakCount * 4, paint)

		paint.strokeWidth = 2.2f
		paint.color = Color.argb(gleam(118, flash), 214, 228, 240)
		canvas.drawLines(points, 0, streakCount * 4, paint)

		paint.style = Paint.Style.FILL

		// Pellets: small icy grains tumbling between the streaks — faster and stiffer than snowflakes.
		val pelletCount = count / 2
		val pelletRandom = Random(PRECIP_SEED + 1L)
		val pelletSpan = height + FLAKE_WRAP_PAD * 2f
		repeat(pelletCount) { i ->
			val travel = pelletRandom.nextFloat() * height + timeSeconds * 240f
			val cycle = (travel / pelletSpan).toInt()
			val baseX = laneFraction(i * 2 + 1, cycle) * width
			val phase = laneFraction(i * 2 + 1, cycle + SWAY_PHASE_SALT) * TAU
			val y = travel % pelletSpan - FLAKE_WRAP_PAD
			val sway = sin(timeSeconds * 1.6f + phase) * (width * 0.012f) + gust * 23f
			val radius = pelletRandom.nextFloat() * 1.6f + 1.2f
			drawSoftDot(canvas, pelletSprite, baseX + sway, y, radius)
		}
	}

	/**
	 * Advances the lightning schedule for this frame. Strikes live in fixed slots but fire at a per-slot
	 * random moment, sometimes twice, sometimes not at all — so the storm never keeps a beat. Roughly 60%
	 * are sheet strikes: no bolt, just the deck lighting up from inside. Everything derives from
	 * [timeSeconds] and the slot seed, so the schedule stays a pure function of time.
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
		val strikeAt = random.nextFloat() * strikeWindow
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
			val glowX = width * (0.2f + random.nextFloat() * 0.6f)
			val glowY = height * (0.1f + random.nextFloat() * 0.15f)
			val glowRadius = width * (0.5f + random.nextFloat() * 0.25f)
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

		forkPath.reset()
		forkPath.moveTo(forkX, forkY)
		val forkDirection = if (random.nextFloat() < 0.5f) {
			-1f
		} else {
			1f
		}

		repeat(3) {
			forkX += forkDirection * (0.04f + random.nextFloat() * 0.08f) * width
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
		private const val METEOR_SEED = 7L
		private const val BIRD_SEED = 11L
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

		/** Edge length of the pre-rendered moon sprite. */
		private const val MOON_SPRITE_SIZE = 256

		/** The moon disc fills this fraction of its sprite, leaving margin so the anti-aliased limb never clips at the bitmap edge. */
		private const val MOON_DISC_MARGIN = 0.96f

		/** Distinct phase sprites across the synodic month — the sprite cache key quantises to these steps. */
		private const val MOON_PHASE_STEPS = 64

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

/** Birds fly only through fair daylight skies: no precipitation, no fog, cover below the deck threshold, and never at night. */
private fun showsBirds(params: SceneParams) = params.precipitation == null && params.fogDensity <= 0f && params.cloudiness < 0.55f && params.dayPhase != DayPhase.NIGHT

private fun birdColor(dayPhase: DayPhase) = when (dayPhase) {
	DayPhase.DAY -> Color.argb(120, 38, 48, 62)
	else -> Color.argb(140, 26, 26, 38)
}

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

/**
 * Where the sun/moon hangs, as a fraction of screen height. The phase progress eases it along a
 * continuous arc: it climbs through dawn, sweeps a shallow parabola across the day whose ends meet the
 * twilight heights exactly, and sinks back through dusk — motion on the scale of minutes, so even a
 * calm clear scene is never a still image.
 */
private fun celestialHeightFraction(dayPhase: DayPhase, progress: Float) = when (dayPhase) {
	DayPhase.DAY -> 0.26f - 0.09f * (4f * progress * (1f - progress))
	DayPhase.DAWN -> lerp(0.42f, 0.26f, progress)
	DayPhase.DUSK -> lerp(0.26f, 0.42f, progress)
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

/** Blends [from] toward [to] by [fraction] (0 = [from], 1 = [to]) — how the far scenery plane picks up the sky's haze. */
private fun lerpColor(from: Int, to: Int, fraction: Float) = Color.rgb(
	(Color.red(from) + (Color.red(to) - Color.red(from)) * fraction).roundToInt(),
	(Color.green(from) + (Color.green(to) - Color.green(from)) * fraction).roundToInt(),
	(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * fraction).roundToInt()
)

private fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
