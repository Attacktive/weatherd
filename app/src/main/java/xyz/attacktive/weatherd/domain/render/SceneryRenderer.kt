package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.model.SkyColorPreset
import xyz.attacktive.weatherd.domain.model.drawsScenery

/** Owns the active scenery's geometry, material paints, and existing animated details. */
internal class SceneryRenderer {
	private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
	private val birdPath = Path()
	private var sceneryLayerPaths = emptyArray<SceneryLayerPath>()
	private val sceneryAccentPath = Path()
	private var sceneryScene = BackdropScene.NONE
	private var sceneryWidth = 0
	private var sceneryHeight = 0
	private var sceneryFarCrestY = 0f
	private var sceneryNearCrestY = 0f
	private var sceneryReflectionY = -1f
	private var sceneryWindowXy = FloatArray(0)
	private var sceneryHasWindows = false
	private var sceneryGulls = emptyArray<SceneryFauna>()
	private var sceneryMarine = emptyArray<SceneryFauna>()
	private var sceneryBeaconXy = FloatArray(0)
	private var sceneryParasolXy = FloatArray(0)
	private var sceneryGlyphPaths = emptyArray<SceneryLayerPath>()
	private var sceneryWindmill: SceneryWindmill? = null
	private val palette = SceneryPalette()
	private var horizonShader: Shader? = null
	private var hazeShader: Shader? = null
	private var reflectionShader: Shader? = null
	private val mistShaders = arrayOfNulls<Shader>(3)
	private val mistMatrix = Matrix()
	private var helicopterSlot = Int.MIN_VALUE
	private var helicopterQuiet = true
	private var helicopterStart = 0f
	private var helicopterDirection = 1f
	private var helicopterAltitude = 0f
	private val helicopter = HelicopterPass(0f, 0f, 1f, 0f)

	/**
	 * The user's chosen horizon silhouettes: two depth planes tinted from the current sky's bottom color, so storm gloom, snow milkiness, and night all carry onto them for free.
	 * Geometry and fauna anchors rebuild only when the scene or the surface size changes; every frame after that is a handful of cached path fills, optional mist/gulls/sails, and a few cheap accents.
	 */
	fun draw(canvas: Canvas, width: Int, height: Int, params: SceneParams, timeSeconds: Float, horizonAlpha: Int) {
		if (!params.backdropScene.drawsScenery) {
			if (sceneryScene.drawsScenery) {
				releaseScenery()
			}

			return
		}

		val rebuilt = cacheScenery(width, height, params)
		val lightingChanged = palette.update(params)
		val w = width.toFloat()
		val h = height.toFloat()
		val skyBottom = palette.skyBottom
		val nearColor = palette.nearColor
		if (rebuilt || lightingChanged) {
			prepareGradients(w, h, params.dayPhase, skyBottom)
		}

		paint.style = Paint.Style.FILL
		// Preserve the established sky-detail modulation of the decorative horizon glow, never the terrain fills.
		paint.alpha = horizonAlpha
		drawHorizonGlow(canvas, w, h)
		paint.shader = null

		drawSceneryLayers(canvas, SceneryPlane.FAR)
		drawFarPlaneDetails(canvas, w, h, params, timeSeconds)
		drawInterPlaneHaze(canvas, w)
		paint.shader = null

		drawSceneryLayers(canvas, SceneryPlane.NEAR)
		drawNearPlaneDetails(canvas, w, h, params, timeSeconds, nearColor)

		if (params.backdropScene == BackdropScene.METROPOLIS && showsHelicopter(params)) {
			helicopterPass(w, h, timeSeconds)?.let {
				drawHelicopter(canvas, params, timeSeconds, it, nearColor)
			}
		}
	}

	/** Drops obsolete scene ownership without recycling sources that recorded hardware commands may still retain. */
	private fun releaseScenery() {
		sceneryLayerPaths = emptyArray()
		sceneryGlyphPaths = emptyArray()
		sceneryGulls = emptyArray()
		sceneryMarine = emptyArray()
		sceneryWindowXy = FloatArray(0)
		sceneryBeaconXy = FloatArray(0)
		sceneryParasolXy = FloatArray(0)
		sceneryAccentPath.rewind()
		sceneryWindmill = null
		horizonShader = null
		hazeShader = null
		reflectionShader = null
		mistShaders.fill(null)
		paint.shader = null
		sceneryScene = BackdropScene.NONE
	}

	/** Fills one depth plane's cached layer paths, each in its material's color for the current weather and phase. */
	private fun drawSceneryLayers(canvas: Canvas, plane: SceneryPlane) {
		for (layer in sceneryLayerPaths) {
			if (layer.plane != plane) {
				continue
			}

			paint.color = palette.color(layer.material, plane)
			canvas.drawPath(layer.path, paint)
		}
	}

	/** The highest outline point of one plane's layers, in unit y; the frame bottom when the plane is empty. */
	private fun planeCrest(outlines: SceneryOutlines, plane: SceneryPlane) = outlines.layers
		.filter { it.plane == plane }
		.minOfOrNull { layer -> layer.outline.minOf { it.y } } ?: 1f

	/** Rebuilds cached scenery paths and accents when the scene or surface size changes. */
	private fun cacheScenery(width: Int, height: Int, params: SceneParams): Boolean {
		if (params.backdropScene == sceneryScene && width == sceneryWidth && height == sceneryHeight) {
			return false
		}

		val w = width.toFloat()
		val h = height.toFloat()
		val outlines = checkNotNull(sceneryOutlinesFor(params.backdropScene, w / h))
		sceneryLayerPaths = Array(outlines.layers.size) { index ->
			val layer = outlines.layers[index]
			val path = Path()
			fillSceneryPath(path, layer.outline, w, h)

			SceneryLayerPath(path, layer.material, layer.plane)
		}

		fillAccentPath(sceneryAccentPath, outlines.accents, w, h)

		val glyphCrest = outlines.glyphs
			.filter { it.plane == SceneryPlane.FAR }
			.minOfOrNull { glyph -> glyph.outline.minOf { it.y } } ?: 1f

		sceneryFarCrestY = minOf(planeCrest(outlines, SceneryPlane.FAR), glyphCrest) * height
		sceneryNearCrestY = planeCrest(outlines, SceneryPlane.NEAR) * height
		sceneryReflectionY = outlines.reflectionY?.times(h) ?: -1f
		sceneryHasWindows = outlines.windows.isNotEmpty()
		sceneryWindowXy = packPoints(outlines.windows, w, h)
		sceneryGulls = outlines.gulls.toTypedArray()
		sceneryMarine = outlines.marine.toTypedArray()
		sceneryBeaconXy = packPoints(outlines.beacons, w, h)
		sceneryParasolXy = packPoints(outlines.parasols, w, h)
		sceneryGlyphPaths = Array(outlines.glyphs.size) { index ->
			val glyph = outlines.glyphs[index]
			val path = Path().also { fillClosedOutline(it, glyph.outline, w, h) }

			SceneryLayerPath(path, glyph.material, glyph.plane)
		}

		sceneryWindmill = outlines.windmill
		sceneryScene = params.backdropScene
		sceneryWidth = width
		sceneryHeight = height

		return true
	}

	/** Painted glyphs (sloop, snowcaps), sea reflection, marine life, and mountain mist — everything that lives on/behind the far plane. */
	private fun drawFarPlaneDetails(canvas: Canvas, width: Float, height: Float, params: SceneParams, timeSeconds: Float) {
		for (part in sceneryGlyphPaths) {
			if (part.plane != SceneryPlane.FAR) {
				continue
			}

			paint.color = palette.color(part.material, SceneryPlane.FAR)
			canvas.drawPath(part.path, paint)
		}

		if (sceneryReflectionY >= 0f) {
			drawBeachReflection(canvas, width, height)
			paint.shader = null
		}

		if (sceneryMarine.isNotEmpty()) {
			val waterColor = palette.color(SceneryMaterial.WATER, SceneryPlane.FAR)
			drawMarineLife(canvas, width, height, timeSeconds, darken(waterColor, 0.6f))
		}

		if (params.backdropScene == BackdropScene.MOUNTAINS) {
			drawValleyMist(canvas, width, height, timeSeconds)
		}
	}

	/** Near-plane glyphs (the farmhouse), parasols, fence accents, windmill sails, windows, beacons, and gulls. */
	private fun drawNearPlaneDetails(canvas: Canvas, width: Float, height: Float, params: SceneParams, timeSeconds: Float, nearColor: Int) {
		for (part in sceneryGlyphPaths) {
			if (part.plane != SceneryPlane.NEAR) {
				continue
			}

			paint.color = palette.color(part.material, SceneryPlane.NEAR)
			canvas.drawPath(part.path, paint)
		}

		if (sceneryParasolXy.isNotEmpty()) {
			val poleColor = palette.color(SceneryMaterial.HULL, SceneryPlane.NEAR)
			val canopyColor = palette.color(SceneryMaterial.PARASOL, SceneryPlane.NEAR)
			drawParasols(canvas, height, poleColor, canopyColor)
		}

		if (!sceneryAccentPath.isEmpty) {
			// Fence posts read as weathered wood, not cutouts of the hill behind them.
			paint.style = Paint.Style.STROKE
			paint.color = palette.color(SceneryMaterial.HULL, SceneryPlane.NEAR)
			paint.strokeWidth = height * 0.0022f
			canvas.drawPath(sceneryAccentPath, paint)
			paint.style = Paint.Style.FILL
		}

		sceneryWindmill?.let {
			// Classic white canvas sails against the sky; the tower stays part of the painted hill.
			val sailColor = palette.color(SceneryMaterial.SAIL, SceneryPlane.NEAR)
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
		if (slot != helicopterSlot) {
			val random = Random(HELICOPTER_SEED + slot)
			helicopterQuiet = random.nextFloat() < 0.5f
			if (!helicopterQuiet) {
				helicopterStart = random.nextFloat(HELICOPTER_SLOT_SECONDS - HELICOPTER_CROSSING_SECONDS)
				helicopterDirection = if (random.nextFloat() < 0.5f) {
					-1f
				} else {
					1f
				}

				helicopterAltitude = random.nextFloat(0.58f, 0.66f)
			}

			helicopterSlot = slot
		}

		val local = timeSeconds - slot * HELICOPTER_SLOT_SECONDS - helicopterStart
		if (helicopterQuiet || local < 0f || local > HELICOPTER_CROSSING_SECONDS) {
			return null
		}

		val progress = local / HELICOPTER_CROSSING_SECONDS
		val span = width * 1.2f
		helicopter.x = if (helicopterDirection > 0f) {
			-width * 0.1f + span * progress
		} else {
			width * 1.1f - span * progress
		}

		helicopter.bodyW = width * 0.02f
		helicopter.y = height * helicopterAltitude + sin(timeSeconds * 1.1f) * helicopter.bodyW * 0.3f
		helicopter.direction = helicopterDirection

		return helicopter
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

	/** Rebuilds weather-tinted shaders only when geometry or palette inputs change. */
	private fun prepareGradients(width: Float, height: Float, dayPhase: DayPhase, skyBottom: Int) {
		val horizonAlpha = when (dayPhase) {
			DayPhase.DAWN -> 90
			DayPhase.DUSK -> 95
			DayPhase.DAY -> 40
			DayPhase.NIGHT -> 35
		}

		val horizonTint = when (dayPhase) {
			DayPhase.DAWN -> lighten(skyBottom, 0.35f)
			DayPhase.DUSK -> lighten(skyBottom, 0.3f)
			DayPhase.DAY -> lighten(skyBottom, 0.2f)
			DayPhase.NIGHT -> Color.rgb(120, 150, 210)
		}

		val bandTop = (sceneryFarCrestY - height * 0.06f).coerceAtLeast(height * 0.55f)
		horizonShader = LinearGradient(0f, bandTop, 0f, sceneryFarCrestY, withAlpha(horizonTint, 0), withAlpha(horizonTint, horizonAlpha), Shader.TileMode.CLAMP)
		val valley = sceneryNearCrestY - sceneryFarCrestY
		hazeShader = if (valley > 0f) {
			val fade = valley * 0.5f
			val fadeTop = sceneryFarCrestY - fade
			val haze = lighten(skyBottom, 0.15f)

			LinearGradient(
				0f,
				fadeTop,
				0f,
				sceneryNearCrestY,
				intArrayOf(withAlpha(haze, 0), withAlpha(haze, 55), withAlpha(haze, 0)),
				floatArrayOf(0f, fade / (sceneryNearCrestY - fadeTop), 1f),
				Shader.TileMode.CLAMP
			)
		} else {
			null
		}

		val reflectionAlpha = when (dayPhase) {
			DayPhase.DAWN, DayPhase.DUSK -> 70
			DayPhase.DAY -> 45
			DayPhase.NIGHT -> 18
		}

		reflectionShader = if (sceneryReflectionY >= 0f) {
			val bandBottom = (sceneryReflectionY + height * 0.045f).coerceAtMost(height)

			LinearGradient(0f, sceneryReflectionY, 0f, bandBottom, withAlpha(lighten(skyBottom, 0.25f), reflectionAlpha), withAlpha(skyBottom, 0), Shader.TileMode.CLAMP)
		} else {
			null
		}

		val mist = lighten(skyBottom, 0.35f)
		val mistAlpha = when (dayPhase) {
			DayPhase.DAY -> 55
			DayPhase.DAWN, DayPhase.DUSK -> 70
			DayPhase.NIGHT -> 35
		}

		for (band in mistShaders.indices) {
			mistShaders[band] = if (sceneryScene == BackdropScene.MOUNTAINS && valley > height * 0.02f) {
				val bandW = width * (0.5f + band * 0.12f)
				val alpha = (mistAlpha * (1f - band * 0.12f)).roundToInt().coerceIn(20, 80)

				RadialGradient(0f, 0f, bandW * 0.55f, withAlpha(mist, alpha), withAlpha(mist, 0), Shader.TileMode.CLAMP)
			} else {
				null
			}
		}
	}

	/** A short gradient band just above the far crest — warm at dawn/dusk, soft by day, cool and thin at night. */
	private fun drawHorizonGlow(canvas: Canvas, width: Float, height: Float) {
		val bandTop = (sceneryFarCrestY - height * 0.06f).coerceAtLeast(height * 0.55f)
		paint.shader = horizonShader
		// Fill past the crest so the silhouettes cover the clamped gradient instead of leaving a hard bright edge.
		canvas.drawRect(0f, bandTop, width, height, paint)
	}

	/** A translucent wash between the two crests so the far plane reads as atmospheric depth rather than a second flat sticker. */
	private fun drawInterPlaneHaze(canvas: Canvas, width: Float) {
		val shader = hazeShader ?: return
		val fadeTop = sceneryFarCrestY - (sceneryNearCrestY - sceneryFarCrestY) * 0.5f
		paint.shader = shader
		canvas.drawRect(0f, fadeTop, width, sceneryNearCrestY, paint)
	}

	/**
	 * Soft mist bands drifting through the mountain valley between the two ridges.
	 * Cheap ovals + low alpha — no blur filters, so the live wallpaper stays light.
	 */
	private fun drawValleyMist(canvas: Canvas, width: Float, height: Float, timeSeconds: Float) {
		val top = sceneryFarCrestY
		val bottom = sceneryNearCrestY
		if (bottom <= top + height * 0.02f) {
			return
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
			val topY = cy - bandH * 0.5f
			val bottomY = cy + bandH * 0.5f

			val shader = mistShaders[band] ?: continue
			mistMatrix.setTranslate(drift + bandW * 0.5f, cy)
			shader.setLocalMatrix(mistMatrix)
			paint.shader = shader
			canvas.drawOval(drift, topY, drift + bandW, bottomY, paint)

			// Wrap so a band exiting one side re-enters the other without a pop.
			val wrapped = drift - travel
			mistMatrix.setTranslate(wrapped + bandW * 0.5f, cy)
			shader.setLocalMatrix(mistMatrix)
			paint.shader = shader
			canvas.drawOval(wrapped, topY, wrapped + bandW, bottomY, paint)
		}

		paint.shader = null
	}

	/** A short vertical wash under the sea line; muted at night so the water stays a silhouette. */
	private fun drawBeachReflection(canvas: Canvas, width: Float, height: Float) {
		val bandBottom = (sceneryReflectionY + height * 0.045f).coerceAtMost(height)
		paint.shader = reflectionShader
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

	private companion object {
		const val HELICOPTER_SEED = 13L
		const val HELICOPTER_SLOT_SECONDS = 193f
		const val HELICOPTER_CROSSING_SECONDS = 26f
		const val PI_F = 3.1415927f
	}
}

/** A cached scenery layer path with the material and plane needed to color it each frame. */
private data class SceneryLayerPath(val path: Path, val material: SceneryMaterial, val plane: SceneryPlane)

/** A helicopter mid-crossing: fuselage center, heading, and the fuselage length every other dimension derives from. */
private data class HelicopterPass(var x: Float, var y: Float, var direction: Float, var bodyW: Float)

/** Helicopters fly in weather birds won't — night included, that's when the blinking light pays off — but storms, fog, and a heavy deck still ground them. */
private fun showsHelicopter(params: SceneParams) = params.precipitation == null && params.fogDensity <= 0f && effectiveOpaqueCloudiness(params) < 0.55f && !params.thunder

/** Primitive-keyed cache of the unchanged flat-scene palette shared by scenery details. */
private class SceneryPalette {
	var skyBottom = 0
		private set
	var nearColor = 0
		private set

	private var phase: DayPhase? = null
	private var progress = Float.NaN
	private var cover = Float.NaN
	private var fog = Float.NaN
	private var kind: PrecipitationKind? = null
	private var severity = Float.NaN
	private var thunder = false
	private var brightness = Float.NaN
	private var saturation = Float.NaN
	private var preset: SkyColorPreset? = null
	private val farColors = IntArray(SceneryMaterial.entries.size)
	private val nearColors = IntArray(SceneryMaterial.entries.size)

	fun color(material: SceneryMaterial, plane: SceneryPlane) = if (plane == SceneryPlane.FAR) {
		farColors[material.ordinal]
	} else {
		nearColors[material.ordinal]
	}

	fun update(params: SceneParams): Boolean {
		val nextCover = effectiveOpaqueCloudiness(params)
		val nextSeverity = params.precipitation?.severity ?: 0f
		if (phase == params.dayPhase && progress == params.celestialProgress && cover == nextCover && fog == params.fogDensity && kind == params.precipitation?.kind && severity == nextSeverity && thunder == params.thunder && brightness == params.skyBrightnessScale && saturation == params.skySaturationScale && preset == params.skyColorPreset) {
			return false
		}

		phase = params.dayPhase
		progress = params.celestialProgress
		cover = nextCover
		fog = params.fogDensity
		kind = params.precipitation?.kind
		severity = nextSeverity
		thunder = params.thunder
		brightness = params.skyBrightnessScale
		saturation = params.skySaturationScale
		preset = params.skyColorPreset
		skyBottom = skyGradientFor(params).bottomColor
		nearColor = sceneryPlaneTone(SceneryPlane.NEAR, skyBottom)

		for (material in SceneryMaterial.entries) {
			farColors[material.ordinal] = sceneryLayerColor(material, SceneryPlane.FAR, params, skyBottom)
			nearColors[material.ordinal] = sceneryLayerColor(material, SceneryPlane.NEAR, params, skyBottom)
		}

		return true
	}
}
