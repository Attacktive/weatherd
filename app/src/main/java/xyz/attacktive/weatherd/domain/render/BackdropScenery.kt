package xyz.attacktive.weatherd.domain.render

import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import xyz.attacktive.weatherd.domain.model.BackdropScene

/** A point on a silhouette's upper edge, in unit coordinates: x across the screen, y down from the top. */
data class OutlinePoint(val x: Float, val y: Float)

/** What kind of cheap animated critter [SceneryFauna] describes. */
enum class SceneryFaunaKind {
	GULL,
	SHARK,
	WHALE
}

/**
 * A cheap animated accent: gulls and marine life on the coast.
 * Positions are unit-frame anchors; the renderer animates them from [phase] / [speed] and wall-clock time.
 */
data class SceneryFauna(
	val kind: SceneryFaunaKind,
	val baseX: Float,
	val baseY: Float,
	val scale: Float,
	val phase: Float,
	val speed: Float
)

/**
 * A countryside windmill: hub and ground-contact in unit coords.
 * [groundY] is the hill surface the tower must reach — without it the sails float.
 */
data class SceneryWindmill(val hubX: Float, val hubY: Float, val groundY: Float, val scale: Float)

/**
 * The two silhouette planes of a backdrop scene — the renderer closes each down to the bottom edge and fills it — plus optional accent polylines stroked in the near tone (fence posts).
 * [windows] are warm night-light centers for the metropolis; [beacons] are red tips on the tallest towers only.
 * [ships] sit on the coast horizon as separate silhouettes; [parasols] on the left beach; [windmill] on the countryside rise.
 */
data class SceneryOutlines(
	val far: List<OutlinePoint>,
	val near: List<OutlinePoint>,
	val accents: List<List<OutlinePoint>> = emptyList(),
	val windows: List<OutlinePoint> = emptyList(),
	val beacons: List<OutlinePoint> = emptyList(),
	val ships: List<List<OutlinePoint>> = emptyList(),
	val parasols: List<OutlinePoint> = emptyList(),
	val reflectionY: Float? = null,
	val gulls: List<SceneryFauna> = emptyList(),
	val marine: List<SceneryFauna> = emptyList(),
	val windmill: SceneryWindmill? = null
)

/**
 * The silhouette geometry for [scene], or null for the bare sky.
 * Pure and deterministic: each plane draws from a fixed seed, so every device shows the same skyline and tests can pin its shape.
 * [aspectRatio] (width over height) only scales how many features fit, so buildings and trees keep their proportions instead of stretching on wide screens.
 */
fun sceneryOutlinesFor(scene: BackdropScene, aspectRatio: Float): SceneryOutlines? = when (scene) {
	BackdropScene.NONE -> null
	BackdropScene.METROPOLIS -> metropolis(aspectRatio)
	BackdropScene.BEACH -> beach(aspectRatio)
	BackdropScene.MOUNTAINS -> mountains(aspectRatio)
	BackdropScene.COUNTRYSIDE -> countryside(aspectRatio)
}

private fun metropolis(aspectRatio: Float): SceneryOutlines {
	val windowRandom = Random(31L)
	val farWindows = mutableListOf<OutlinePoint>()
	val nearWindows = mutableListOf<OutlinePoint>()
	val roofCandidates = mutableListOf<OutlinePoint>()

	val far = towerRun(
		random = Random(11L),
		spec = TowerRunSpec(
			count = scaled(10, aspectRatio),
			topLow = 0.70f,
			topHigh = 0.79f,
			baseY = 0.86f,
			setbacks = true,
			windowChance = 0.35f
		),
		windows = farWindows,
		windowRandom = windowRandom,
		roofCandidates = roofCandidates
	)
	val near = rooflineRun(
		random = Random(23L),
		count = scaled(8, aspectRatio),
		topLow = 0.78f,
		topHigh = 0.88f,
		windows = nearWindows,
		windowRandom = windowRandom,
		windowChance = 0.55f
	)

	// Cap the lit-window budget so dusk/night stays a handful of rect draws, not a skyline disco.
	val litWindows = (farWindows + nearWindows).take(MAX_WINDOWS)

	// Aviation lights belong on the tallest far towers only — on the roof, not floating above it — and stay sparse in landscape.
	val beacons = roofCandidates.sortedBy { it.y }.take(BEACON_COUNT)

	return SceneryOutlines(far = far, near = near, windows = litWindows, beacons = beacons)
}

/** A shoreline with a bluff, beach parasols on the left, ships on the horizon, and a little life in the water. */
private fun beach(aspectRatio: Float): SceneryOutlines {
	// Ships and umbrellas keep a readable size on wide screens instead of shrinking to hairlines.
	val featureScale = (PORTRAIT_ASPECT / aspectRatio).coerceAtLeast(MIN_BEACH_FEATURE_SCALE)
	val shipRandom = Random(89L)
	val bluff = coastalBluff(Random(79L))

	return SceneryOutlines(
		far = flatSea(),
		near = bluff,
		// Ships stay over open water on the right — under the bluff they get swallowed by the near fill.
		ships = listOf(
			cargoShip(start = shipRandom.nextFloat(0.52f, 0.60f), length = 0.18f * featureScale),
			sailboat(start = shipRandom.nextFloat(0.78f, 0.84f), length = 0.07f * featureScale)
		),
		parasols = beachParasols(Random(83L), bluff, featureScale),
		reflectionY = SEA_HORIZON,
		gulls = beachGulls(Random(97L), featureScale),
		marine = beachMarine(Random(101L), featureScale)
	)
}

private fun mountains(aspectRatio: Float) = SceneryOutlines(
	far = mountainRange(
		random = Random(53L),
		count = scaled(3, aspectRatio),
		crestLow = 0.70f,
		crestHigh = 0.78f,
		saddleLow = 0.80f,
		saddleHigh = 0.85f,
		broadCrests = true
	),
	near = mountainRange(
		random = Random(67L),
		count = scaled(4, aspectRatio),
		crestLow = 0.83f,
		crestHigh = 0.875f,
		saddleLow = 0.89f,
		saddleHigh = 0.925f,
		broadCrests = false
	)
)

private fun countryside(aspectRatio: Float): SceneryOutlines {
	val featureScale = (PORTRAIT_ASPECT / aspectRatio).coerceAtLeast(0.55f)
	val nearHills = rollingHills(Random(59L), count = scaled(5, aspectRatio), topLow = 0.86f, topHigh = 0.91f)
	val mill = pastureWindmill(Random(71L), nearHills, featureScale)

	// Tower is welded into the near silhouette so it can't float; only the sails are drawn each frame.
	val withMill = attachWindmillTower(nearHills, mill)
	val withFarmhouse = attachFarmhouse(withMill, Random(61L), featureScale)

	return SceneryOutlines(
		far = rollingHills(Random(57L), count = scaled(4, aspectRatio), topLow = 0.78f, topHigh = 0.85f),
		near = withFarmhouse,
		accents = fencePosts(Random(63L), featureScale),
		windmill = mill
	)
}

/** Knobs for a distant tower run — keeps [towerRun] under the parameter-count lint. */
private data class TowerRunSpec(
	val count: Int,
	val topLow: Float,
	val topHigh: Float,
	val baseY: Float,
	val setbacks: Boolean = false,
	val windowChance: Float = 0f
)

/** Detached towers with street gaps between them, for a distant skyline against the sky. */
private fun towerRun(
	random: Random,
	spec: TowerRunSpec,
	windows: MutableList<OutlinePoint>? = null,
	windowRandom: Random? = null,
	roofCandidates: MutableList<OutlinePoint>? = null
): List<OutlinePoint> {
	val points = mutableListOf(OutlinePoint(0f, spec.baseY))
	val edges = segmentEdges(random, spec.count)

	for (index in 0 until spec.count) {
		val left = edges[index]
		val right = edges[index + 1]
		val inset = (right - left) * random.nextFloat(0.10f, 0.22f)
		val towerLeft = left + inset
		val towerRight = right - inset
		val top = random.nextFloat(spec.topLow, spec.topHigh)
		points += OutlinePoint(towerLeft, spec.baseY)

		val usedSetback = spec.setbacks &&
			random.nextFloat() < 0.4f &&
			towerRight - towerLeft > 0.04f

		if (usedSetback) {
			appendSetbackTower(points, random, towerLeft, towerRight, top, spec.baseY)
		} else {
			appendFlatTower(points, towerLeft, towerRight, top, spec.baseY)
		}

		val chance = if (usedSetback) {
			spec.windowChance * 0.7f
		} else {
			spec.windowChance
		}

		maybePlantWindows(windows, windowRandom, towerLeft, towerRight, top, spec.baseY, chance)

		// Beacon candidate sits on the roof line itself (same y as the parapet), never hovering above it.
		roofCandidates?.add(OutlinePoint((towerLeft + towerRight) * 0.5f, top))
	}

	points += OutlinePoint(1f, spec.baseY)

	return points
}

private fun appendSetbackTower(
	points: MutableList<OutlinePoint>,
	random: Random,
	towerLeft: Float,
	towerRight: Float,
	top: Float,
	baseY: Float
) {
	val mid = towerLeft + (towerRight - towerLeft) * random.nextFloat(0.35f, 0.65f)
	val shoulder = top + random.nextFloat(0.012f, 0.022f)
	points += OutlinePoint(towerLeft, shoulder)
	points += OutlinePoint(mid, shoulder)
	points += OutlinePoint(mid, top)
	points += OutlinePoint(towerRight, top)
	points += OutlinePoint(towerRight, baseY)
}

private fun appendFlatTower(
	points: MutableList<OutlinePoint>,
	towerLeft: Float,
	towerRight: Float,
	top: Float,
	baseY: Float
) {
	points += OutlinePoint(towerLeft, top)
	points += OutlinePoint(towerRight, top)
	points += OutlinePoint(towerRight, baseY)
}

private fun maybePlantWindows(
	windows: MutableList<OutlinePoint>?,
	windowRandom: Random?,
	left: Float,
	right: Float,
	top: Float,
	baseY: Float,
	chance: Float
) {
	if (windows == null || windowRandom == null) {
		return
	}

	plantWindows(windows, windowRandom, left, right, top, baseY, chance)
}

/** A continuous wall of building roofs at varying heights, some carrying a thin antenna mast. */
private fun rooflineRun(
	random: Random,
	count: Int,
	topLow: Float,
	topHigh: Float,
	windows: MutableList<OutlinePoint>? = null,
	windowRandom: Random? = null,
	windowChance: Float = 0f
): List<OutlinePoint> {
	val points = mutableListOf<OutlinePoint>()
	val edges = segmentEdges(random, count)
	val baseY = 0.94f

	for (index in 0 until count) {
		val left = edges[index]
		val right = edges[index + 1]
		val roof = random.nextFloat(topLow, topHigh)
		points += OutlinePoint(left, roof)

		if (random.nextFloat() < 0.4f) {
			val mastX = left + (right - left) * random.nextFloat(0.25f, 0.55f)
			val mastTop = roof - random.nextFloat(0.018f, 0.038f)
			points += OutlinePoint(mastX, roof)
			points += OutlinePoint(mastX, mastTop)
			points += OutlinePoint(mastX + MAST_WIDTH, mastTop)
			points += OutlinePoint(mastX + MAST_WIDTH, roof)
		}

		points += OutlinePoint(right, roof)

		maybePlantWindows(windows, windowRandom, left, right, roof, baseY, windowChance)
	}

	return points
}

/** Seeded warm-window centers inside a building slab; denser near the roof, sparse enough to stay cheap to draw. */
private fun plantWindows(
	into: MutableList<OutlinePoint>,
	random: Random,
	left: Float,
	right: Float,
	top: Float,
	baseY: Float,
	chance: Float
) {
	val cols = ((right - left) / 0.012f).roundToInt().coerceIn(1, 5)
	val rows = ((baseY - top) / 0.018f).roundToInt().coerceIn(1, 6)

	for (row in 0 until rows) {
		for (col in 0 until cols) {
			if (random.nextFloat() > chance) {
				continue
			}

			val x = left + (right - left) * ((col + 0.5f) / cols)
			val y = top + (baseY - top) * ((row + 0.55f) / (rows + 0.5f))
			into += OutlinePoint(x, y)
		}
	}
}

/** Bare sea horizon — ships are drawn separately so they stay recognizable. */
private fun flatSea() = listOf(OutlinePoint(0f, SEA_HORIZON), OutlinePoint(1f, SEA_HORIZON))

/** A freighter: long low hull, stern bridge, funnel — unmistakable against the open water. */
private fun cargoShip(start: Float, length: Float): List<OutlinePoint> {
	val water = SEA_HORIZON
	val deck = water - 0.022f
	val bridgeTop = water - 0.052f
	val funnelTop = water - 0.07f

	return listOf(
		OutlinePoint(start, water),
		OutlinePoint(start + length * 0.03f, deck),
		OutlinePoint(start + length * 0.5f, deck),
		OutlinePoint(start + length * 0.5f, bridgeTop),
		OutlinePoint(start + length * 0.58f, bridgeTop),
		OutlinePoint(start + length * 0.58f, funnelTop),
		OutlinePoint(start + length * 0.66f, funnelTop),
		OutlinePoint(start + length * 0.66f, bridgeTop),
		OutlinePoint(start + length * 0.8f, bridgeTop),
		OutlinePoint(start + length * 0.8f, deck),
		OutlinePoint(start + length * 0.97f, deck),
		OutlinePoint(start + length, water)
	)
}

/** A sailboat: chunky hull first, then a tall sail — so it doesn't read as a lonely triangle island. */
private fun sailboat(start: Float, length: Float): List<OutlinePoint> {
	val water = SEA_HORIZON
	val deck = water - 0.016f
	val mastTop = water - 0.078f

	return listOf(
		OutlinePoint(start, water),
		OutlinePoint(start + length * 0.06f, deck),
		OutlinePoint(start + length * 0.28f, deck),
		OutlinePoint(start + length * 0.34f, mastTop),
		OutlinePoint(start + length * 0.4f, mastTop),
		OutlinePoint(start + length * 0.72f, deck),
		OutlinePoint(start + length * 0.94f, deck),
		OutlinePoint(start + length, water)
	)
}

/**
 * A coastal bluff on the left dropping to a sandy shelf on the right.
 * Stays below the ship masts so the two-plane depth order still holds.
 */
private fun coastalBluff(random: Random): List<OutlinePoint> {
	val points = mutableListOf<OutlinePoint>()
	val steps = 12

	for (index in 0..steps) {
		val t = index / steps.toFloat()
		val x = t
		val y = when {
			t < 0.30f -> 0.845f + sin(t * 14f) * 0.006f + random.nextFloat(0.005f)
			t < 0.46f -> {
				val slide = (t - 0.30f) / 0.16f
				lerp(0.85f, 0.935f, slide * slide) + random.nextFloat(0.004f)
			}
			else -> {
				val shelf = 0.935f + sin(t * 9f) * 0.005f
				(shelf + random.nextFloat(0.008f)).coerceIn(0.91f, 0.955f)
			}
		}

		points += OutlinePoint(x, y.coerceIn(0.83f, 0.955f))
	}

	return points
}

/**
 * Thatched parasols planted on the bluff crest so the canopy sticks up into the sky.
 * Buried on the sand shelf (same tone as the near fill) they vanish completely.
 */
private fun beachParasols(random: Random, bluff: List<OutlinePoint>, featureScale: Float): List<OutlinePoint> = List(2) { index ->
	val x = (0.08f + index * 0.11f * featureScale + random.nextFloat(0.012f)).coerceAtMost(0.28f)
	val crestY = sampleOutlineY(bluff, x)

	OutlinePoint(x, crestY)
}

/** Seagull anchors above the water; the renderer drifts them and flaps their wings. */
private fun beachGulls(random: Random, featureScale: Float): List<SceneryFauna> = List(4) {
	SceneryFauna(
		kind = SceneryFaunaKind.GULL,
		baseX = random.nextFloat(0.05f, 0.75f),
		baseY = random.nextFloat(0.70f, 0.78f),
		scale = random.nextFloat(0.9f, 1.3f) * featureScale,
		phase = random.nextFloat(TAU),
		speed = random.nextFloat(0.012f, 0.03f)
	)
}

/** Sharks and a whale in the water beyond the beach — fins and a slow breach, not a nature documentary. */
private fun beachMarine(random: Random, featureScale: Float): List<SceneryFauna> {
	val sharks = List(2) {
		SceneryFauna(
			kind = SceneryFaunaKind.SHARK,
			baseX = random.nextFloat(0.35f, 0.8f),
			baseY = SEA_HORIZON + random.nextFloat(0.015f, 0.04f),
			scale = random.nextFloat(0.8f, 1.2f) * featureScale,
			phase = random.nextFloat(TAU),
			speed = random.nextFloat(0.008f, 0.018f)
		)
	}
	val whale = SceneryFauna(
		kind = SceneryFaunaKind.WHALE,
		baseX = random.nextFloat(0.42f, 0.67f),
		baseY = SEA_HORIZON + 0.03f,
		scale = random.nextFloat(1.3f, 1.6f) * featureScale,
		phase = random.nextFloat(TAU),
		speed = random.nextFloat(0.004f, 0.008f)
	)

	return sharks + whale
}

/**
 * A mountain skyline of broad massifs — shoulders, soft crests, shallow saddles — not a row of shark-tooth spikes.
 * Each massif gets several points so slopes read as ridges instead of single-tip zigzags.
 */
private fun mountainRange(
	random: Random,
	count: Int,
	crestLow: Float,
	crestHigh: Float,
	saddleLow: Float,
	saddleHigh: Float,
	broadCrests: Boolean
): List<OutlinePoint> {
	val points = mutableListOf(OutlinePoint(0f, random.nextFloat(saddleLow, saddleHigh)))
	val edges = segmentEdges(random, count)

	for (index in 0 until count) {
		val left = edges[index]
		val right = edges[index + 1]
		val span = right - left
		val crest = random.nextFloat(crestLow, crestHigh)
		val saddle = random.nextFloat(saddleLow, saddleHigh)

		// Rising shoulder — halfway up before the crest, so the slope isn't a straight knife-edge.
		val riseX = left + span * random.nextFloat(0.18f, 0.3f)
		val riseY = lerp(points.last().y, crest, random.nextFloat(0.45f, 0.6f))
		points += OutlinePoint(riseX, riseY)

		if (broadCrests && span > 0.12f) {
			// A short ridge top: two close points at nearly the same height, maybe a gentle dip between.
			val crestLeft = left + span * random.nextFloat(0.38f, 0.46f)
			val crestRight = left + span * random.nextFloat(0.55f, 0.65f)
			val crestDip = crest + random.nextFloat(0.008f, 0.018f)
			points += OutlinePoint(crestLeft, crest)
			if (random.nextFloat() < 0.65f) {
				points += OutlinePoint((crestLeft + crestRight) * 0.5f, crestDip)
			}
			points += OutlinePoint(crestRight, crest + random.nextFloat(0.006f))
		} else {
			val summit = left + span * random.nextFloat(0.4f, 0.6f)
			points += OutlinePoint(summit, crest)
			// Soften the tip with a near-side shoulder so it isn't a perfect triangle.
			val fallX = summit + (right - summit) * random.nextFloat(0.35f, 0.55f)
			points += OutlinePoint(fallX, lerp(crest, saddle, random.nextFloat(0.4f, 0.6f)))
		}

		points += OutlinePoint(right, saddle)
	}

	return points
}

/** Gentle rolling farmland hills — broader, softer than mountain peaks. */
private fun rollingHills(random: Random, count: Int, topLow: Float, topHigh: Float): List<OutlinePoint> {
	val points = mutableListOf<OutlinePoint>()
	val edges = segmentEdges(random, count)

	for (index in 0..count) {
		val x = edges[index]
		val crest = random.nextFloat(topLow, topHigh)
		val y = if (index == 0 || index == count) {
			crest + 0.02f
		} else {
			crest
		}

		points += OutlinePoint(x, y.coerceIn(0.76f, 0.94f))
	}

	return points
}

/**
 * Cuts a filled farmhouse into the near hill silhouette so it reads as a building on the rise, not a stroked canopy glyph.
 * Points stay left-to-right so the outline tests keep passing.
 */
private fun attachFarmhouse(hills: List<OutlinePoint>, random: Random, featureScale: Float): List<OutlinePoint> {
	val houseLeft = random.nextFloat(0.56f, 0.7f)
	val houseWidth = 0.08f * featureScale
	val houseRight = (houseLeft + houseWidth).coerceAtMost(0.92f)
	val span = houseRight - houseLeft
	val groundY = sampleOutlineY(hills, (houseLeft + houseRight) * 0.5f)
	val eaves = groundY - 0.022f
	val ridge = eaves - 0.02f
	val chimneyWidth = (span * 0.1f).coerceAtLeast(0.003f)
	val chimneyLeft = houseLeft + span * 0.68f
	val chimneyRight = (chimneyLeft + chimneyWidth).coerceAtMost(houseRight - span * 0.05f)
	val chimneyTop = ridge - 0.012f

	val before = hills.filter { it.x < houseLeft }
	val after = hills.filter { it.x > houseRight }

	return before + listOf(
		OutlinePoint(houseLeft, groundY),
		OutlinePoint(houseLeft, eaves),
		OutlinePoint(houseLeft + span * 0.5f, ridge),
		OutlinePoint(chimneyLeft, ridge),
		OutlinePoint(chimneyLeft, chimneyTop),
		OutlinePoint(chimneyRight, chimneyTop),
		OutlinePoint(chimneyRight, ridge),
		OutlinePoint(houseRight, eaves),
		OutlinePoint(houseRight, groundY)
	) + after
}

/** Short fence-post ticks along the near hillside. */
private fun fencePosts(random: Random, featureScale: Float): List<List<OutlinePoint>> {
	val start = random.nextFloat(0.12f, 0.27f)
	val spacing = 0.028f * featureScale

	return List(5) { index ->
		val x = start + index * spacing
		val top = random.nextFloat(0.90f, 0.91f)

		listOf(
			OutlinePoint(x, top),
			OutlinePoint(x, top + 0.018f)
		)
	}
}

/** A windmill on the near pasture — short tower planted on the hill, hub just above the crest. */
private fun pastureWindmill(random: Random, hills: List<OutlinePoint>, featureScale: Float): SceneryWindmill {
	val hubX = random.nextFloat(0.20f, 0.38f)
	val groundY = sampleOutlineY(hills, hubX)
	val towerH = 0.038f * featureScale
	val hubY = (groundY - towerH).coerceAtLeast(0.78f)

	return SceneryWindmill(hubX = hubX, hubY = hubY, groundY = groundY, scale = featureScale)
}

/**
 * Cuts the windmill tower into the near hill so the mast is part of the ground silhouette.
 * Sails stay as a separate animated draw at [mill.hubX]/[mill.hubY].
 */
private fun attachWindmillTower(hills: List<OutlinePoint>, mill: SceneryWindmill): List<OutlinePoint> {
	val half = (0.009f * mill.scale).coerceAtLeast(0.004f)
	val left = (mill.hubX - half).coerceAtLeast(0.02f)
	val right = (mill.hubX + half).coerceAtMost(0.48f)
	val before = hills.filter { it.x < left }
	val after = hills.filter { it.x > right }

	return before + listOf(
		OutlinePoint(left, mill.groundY),
		OutlinePoint(left, mill.hubY),
		OutlinePoint(right, mill.hubY),
		OutlinePoint(right, mill.groundY)
	) + after
}

/** Linear y sample along a left-to-right outline, for planting the farmhouse on the hill. */
private fun sampleOutlineY(outline: List<OutlinePoint>, x: Float): Float {
	if (outline.isEmpty()) {
		return 0.9f
	}

	if (x <= outline.first().x) {
		return outline.first().y
	}

	if (x >= outline.last().x) {
		return outline.last().y
	}

	for (index in 0 until outline.lastIndex) {
		val left = outline[index]
		val right = outline[index + 1]
		if (x in left.x..right.x) {
			val span = (right.x - left.x).coerceAtLeast(1e-4f)
			val t = (x - left.x) / span

			return lerp(left.y, right.y, t)
		}
	}

	return outline.last().y
}

/** [count] slot boundaries of randomized widths, normalized so the first edge is exactly 0 and the last exactly 1. */
private fun segmentEdges(random: Random, count: Int): FloatArray {
	val widths = FloatArray(count) { random.nextFloat(0.6f, 1.5f) }
	val total = widths.sum()
	val edges = FloatArray(count + 1)

	for (index in 0 until count) {
		edges[index + 1] = edges[index] + widths[index] / total
	}

	edges[count] = 1f

	return edges
}

/** How many features fit the screen: the counts are tuned for a portrait phone and grow with wider aspects. */
private fun scaled(baseCount: Int, aspectRatio: Float) = (baseCount * aspectRatio / PORTRAIT_ASPECT).roundToInt().coerceAtLeast(2)

private fun lerp(from: Float, to: Float, t: Float) = from + (to - from) * t

private const val PORTRAIT_ASPECT = 0.46f

/** Where the coast scene's sea meets the sky, as a fraction of screen height. */
private const val SEA_HORIZON = 0.85f

/** Antenna masts are a hair's width regardless of how many buildings share the skyline. */
private const val MAST_WIDTH = 0.005f

/** Hard ceiling on metropolis window dots so the per-frame scenery pass stays cheap on a live wallpaper. */
private const val MAX_WINDOWS = 72

/** Only the tallest far towers get a red aviation light — sparse on purpose, especially in landscape. */
private const val BEACON_COUNT = 3

/** Beach ships/umbrellas refuse to shrink below this when the screen goes wide. */
private const val MIN_BEACH_FEATURE_SCALE = 0.7f

private const val TAU = (Math.PI * 2.0).toFloat()
