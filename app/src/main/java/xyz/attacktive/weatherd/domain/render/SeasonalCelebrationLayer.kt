package xyz.attacktive.weatherd.domain.render

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.PathParser
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.HolidayCelebration

/**
 * Renders seasonal celebrations (Halloween witch, Christmas sleigh with reindeer, New Year's fireworks)
 * directly into the animated sky foreground using deterministic scheduling and reusable vector paths.
 */
internal class SeasonalCelebrationLayer {
	val frame = SeasonalCelebrationFrame()

	private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
		style = Paint.Style.FILL
	}

	private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
		style = Paint.Style.STROKE
	}

	private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
		style = Paint.Style.FILL
	}

	// Back-to-front shapes from the approved 100 × 72 artwork; parsed once, never in a frame callback.
	private val witchPaths = arrayOf(
		requireNotNull(PathParser.createPathFromPathData("M 17 49 Q 55 47 99 43 L 99 46 Q 53 50 17 52 Z")),
		requireNotNull(PathParser.createPathFromPathData("M 25 48 Q 13 42 1 45 L 5 48 L 0 50 L 5 52 L 1 56 Q 16 56 25 51 Z")),
		requireNotNull(PathParser.createPathFromPathData("M 20 47 L 24 48 L 24 52 L 20 53 Z")),
		requireNotNull(PathParser.createPathFromPathData("M 50 26 C 37 29 36 42 19 43 Q 29 50 46 44 Q 52 37 53 30 Z")),
		requireNotNull(PathParser.createPathFromPathData("M 50 28 C 40 31 39 41 26 44 Q 37 44 44 39 Z")),
		requireNotNull(PathParser.createPathFromPathData("M 48 29 Q 60 27 63 40 L 57 47 Q 46 51 42 43 Q 42 35 48 29 Z")),
		requireNotNull(PathParser.createPathFromPathData("M 48 43 Q 61 43 72 49 Q 75 52 72 56 L 66 64 L 60 61 L 64 53 L 51 53 Q 44 52 43 47 Z")),
		requireNotNull(PathParser.createPathFromPathData("M 65 59 L 69 61 L 67 67 Q 73 66 76 70 L 59 70 L 60 66 Z")),
		requireNotNull(PathParser.createPathFromPathData("M 53 18 Q 63 16 64 23 L 70 27 L 64 29 Q 64 34 57 32 L 53 29 Z")),
		requireNotNull(PathParser.createPathFromPathData("M 52 23 Q 49 29 53 34 L 47 31 Q 45 25 50 21 Z")),
		requireNotNull(PathParser.createPathFromPathData("M 39 20 Q 43 19 46 18 L 51 1 Q 59 5 60 17 Q 69 18 73 22 Q 58 26 39 22 Z")),
		requireNotNull(PathParser.createPathFromPathData("M 46 17 Q 53 19 61 17 L 63 20 Q 53 22 45 20 Z")),
		requireNotNull(PathParser.createPathFromPathData("M 56 32 Q 62 33 64 38 L 74 41 L 72 45 L 59 42 L 53 36 Z")),
		requireNotNull(PathParser.createPathFromPathData("M 73 41 Q 79 40 81 44 L 78 47 L 72 45 Z"))
	)

	private val witchColors = intArrayOf(
		0xFF71614C.toInt(), // Shaft
		0xFF9A855B.toInt(), // Straw
		0xFF927F60.toInt(), // Twine
		0xFF332A46.toInt(), // Cape
		0xFF584F63.toInt(), // Cape fold
		0xFF5B5066.toInt(), // Torso
		0xFF4B3E5B.toInt(), // Bent leg
		0xFF645C6F.toInt(), // Boot
		0xFF6A5E67.toInt(), // Face
		0xFF6C646F.toInt(), // Hair
		0xFF40324E.toInt(), // Hat
		0xFF5F546B.toInt(), // Hat band
		0xFF5F556A.toInt(), // Sleeve
		0xFF6D6169.toInt() // Hand
	)

	private val sleighRunnerPath = Path()
	private val sleighBodyPath = Path()
	private val toySackPath = Path()
	private val santaPath = Path()
	private val reindeerBodyPath = Path()
	private val reindeerAntlersPath = Path()

	init {
		buildChristmasPaths()
	}

	fun draw(canvas: Canvas, width: Float, height: Float, params: SceneParams, epochMillis: Long) {
		if (params.dayPhase != DayPhase.NIGHT || params.holidayCelebration == HolidayCelebration.NONE) {
			return
		}

		if (!frame.sample(width, height, params, epochMillis)) {
			return
		}

		when (params.holidayCelebration) {
			HolidayCelebration.HALLOWEEN -> drawHalloweenWitch(canvas, epochMillis)
			HolidayCelebration.CHRISTMAS -> drawChristmasSleigh(canvas, epochMillis)
			HolidayCelebration.NEW_YEAR -> drawNewYearFireworks(canvas)
			HolidayCelebration.NONE -> Unit
		}
	}

	private fun drawHalloweenWitch(canvas: Canvas, epochMillis: Long) {
		val saveCount = canvas.save()
		canvas.translate(frame.flybyX, frame.flybyY)
		val directionScale = if (frame.flybyFliesRight) {
			1f
		} else {
			-1f
		}

		canvas.scale(frame.flybyScale * directionScale, frame.flybyScale)
		canvas.rotate(frame.flybyTiltDegrees)

		val opacity = frame.flybyOpacity

		val artworkSaveCount = canvas.save()
		canvas.translate(-68f, -40f)
		val artworkAlpha = (255f * opacity).roundToInt()
		for (index in witchPaths.indices) {
			fillPaint.color = witchColors[index]
			fillPaint.alpha = artworkAlpha
			canvas.drawPath(witchPaths[index], fillPaint)
		}

		canvas.restoreToCount(artworkSaveCount)

		// Trailing magical embers
		drawWitchSparkles(canvas, epochMillis, opacity)

		canvas.restoreToCount(saveCount)
	}

	private fun drawWitchSparkles(canvas: Canvas, epochMillis: Long, opacity: Float) {
		fillPaint.color = Color.argb((190f * opacity).roundToInt(), 255, 175, 55)
		for (i in 0..4) {
			val sparkPhase = ((epochMillis + i * 160L) % 800L).toFloat() / 800f
			val sparkX = -44f - sparkPhase * 36f - i * 5f
			val sparkY = 8f + sin(sparkPhase * 2f * PI.toFloat() + i) * 6f
			val sparkAlpha = (1f - sparkPhase) * opacity
			fillPaint.alpha = (sparkAlpha * 210f).roundToInt()
			canvas.drawCircle(sparkX, sparkY, 1.8f * (1f - sparkPhase * 0.5f), fillPaint)
		}
	}

	private fun drawChristmasSleigh(canvas: Canvas, epochMillis: Long) {
		val saveCount = canvas.save()
		canvas.translate(frame.flybyX, frame.flybyY)
		val directionScale = if (frame.flybyFliesRight) {
			1f
		} else {
			-1f
		}

		canvas.scale(frame.flybyScale * directionScale, frame.flybyScale)
		canvas.rotate(frame.flybyTiltDegrees)

		val opacity = frame.flybyOpacity

		// Golden sleigh runners
		strokePaint.strokeWidth = 2.5f
		strokePaint.color = Color.argb((225f * opacity).roundToInt(), 182, 163, 116)
		canvas.drawPath(sleighRunnerPath, strokePaint)

		// Sleigh body
		fillPaint.color = Color.argb((230f * opacity).roundToInt(), 145, 26, 34)
		canvas.drawPath(sleighBodyPath, fillPaint)

		// Gold trim on sleigh rim
		strokePaint.strokeWidth = 1.6f
		strokePaint.color = Color.argb((210f * opacity).roundToInt(), 196, 177, 128)
		canvas.drawLine(-54f, -2f, -25f, 1f, strokePaint)

		// Toy sack bulging in the back
		fillPaint.color = Color.argb((225f * opacity).roundToInt(), 92, 62, 42)
		canvas.drawPath(toySackPath, fillPaint)

		// Santa silhouette
		fillPaint.color = Color.argb((235f * opacity).roundToInt(), 185, 32, 40)
		canvas.drawPath(santaPath, fillPaint)

		// Santa hat pom-pom
		fillPaint.color = Color.argb((240f * opacity).roundToInt(), 250, 250, 252)
		canvas.drawCircle(-27f, -23f, 2.2f, fillPaint)

		// Reindeer
		drawReindeer(canvas, opacity, frame.gallopPhase)

		// Reins
		strokePaint.strokeWidth = 1.2f
		strokePaint.color = Color.argb((190f * opacity).roundToInt(), 225, 190, 110)
		canvas.drawLine(-12f, -8f, 44f, -2f, strokePaint)

		// Magical stardust trail behind runners
		drawSleighStardust(canvas, epochMillis, opacity)

		canvas.restoreToCount(saveCount)
	}

	private fun drawReindeer(canvas: Canvas, opacity: Float, gallopPhase: Float) {
		// Reindeer body
		fillPaint.color = Color.argb((245f * opacity).roundToInt(), 126, 110, 94)
		canvas.drawPath(reindeerBodyPath, fillPaint)

		// Antlers
		strokePaint.strokeWidth = 2f
		strokePaint.color = Color.argb((235f * opacity).roundToInt(), 167, 151, 125)
		canvas.drawPath(reindeerAntlersPath, strokePaint)

		// Galloping legs
		val gallopRad = gallopPhase * 2f * PI.toFloat()
		val frontLegAngle = sin(gallopRad) * 22f + 15f
		val hindLegAngle = -sin(gallopRad) * 24f - 20f

		strokePaint.strokeWidth = 3.2f
		strokePaint.color = Color.argb((245f * opacity).roundToInt(), 126, 110, 94)

		// Front legs
		val frontRad = Math.toRadians(frontLegAngle.toDouble())
		val fKneeX = 42f + 8f * sin(frontRad).toFloat()
		val fKneeY = 1f + 8f * cos(frontRad).toFloat()
		val fHoofX = fKneeX + 7f * sin(frontRad + 0.3).toFloat()
		val fHoofY = fKneeY + 7f * cos(frontRad + 0.3).toFloat()
		canvas.drawLine(42f, 1f, fKneeX, fKneeY, strokePaint)
		canvas.drawLine(fKneeX, fKneeY, fHoofX, fHoofY, strokePaint)

		// Hind legs
		val hindRad = Math.toRadians(hindLegAngle.toDouble())
		val hKneeX = 24f + 8f * sin(hindRad).toFloat()
		val hKneeY = 0f + 8f * cos(hindRad).toFloat()
		val hHoofX = hKneeX + 8f * sin(hindRad - 0.4).toFloat()
		val hHoofY = hKneeY + 8f * cos(hindRad - 0.4).toFloat()
		canvas.drawLine(24f, 0f, hKneeX, hKneeY, strokePaint)
		canvas.drawLine(hKneeX, hKneeY, hHoofX, hHoofY, strokePaint)

		// Rudolph's glowing red nose
		fillPaint.color = Color.argb((245f * opacity).roundToInt(), 255, 45, 40)
		canvas.drawCircle(54.5f, -13.5f, 2.2f, fillPaint)

		glowPaint.color = Color.argb((95f * opacity).roundToInt(), 255, 70, 55)
		canvas.drawCircle(54.5f, -13.5f, 5.2f, glowPaint)
	}

	private fun drawSleighStardust(canvas: Canvas, epochMillis: Long, opacity: Float) {
		fillPaint.color = Color.argb((185f * opacity).roundToInt(), 255, 230, 130)
		for (i in 0..5) {
			val phase = ((epochMillis + i * 140L) % 700L).toFloat() / 700f
			val x = -64f - phase * 40f - i * 6f
			val y = 11f + sin(phase * 3f * PI.toFloat() + i) * 4f
			val alpha = (1f - phase) * opacity
			fillPaint.alpha = (alpha * 200f).roundToInt()
			canvas.drawCircle(x, y, 1.5f * (1f - phase * 0.4f), fillPaint)
		}
	}

	private fun drawNewYearFireworks(canvas: Canvas) {
		for (b in 0 until frame.activeBurstCount) {
			val burst = frame.getActiveBurst(b)
			val paletteColor = PALETTE_COLORS[burst.paletteIndex]

			if (burst.isAscent) {
				val tracerAlpha = (220f * burst.ascentOpacity).roundToInt().coerceIn(0, 255)
				val coreAlpha = (245f * burst.ascentOpacity).roundToInt().coerceIn(0, 255)
				strokePaint.strokeWidth = 2.4f
				strokePaint.color = Color.argb(tracerAlpha, Color.red(paletteColor), Color.green(paletteColor), Color.blue(paletteColor))
				canvas.drawLine(burst.rocketPrevX, burst.rocketPrevY, burst.rocketX, burst.rocketY, strokePaint)

				fillPaint.color = Color.argb(coreAlpha, 255, 245, 215)
				canvas.drawCircle(burst.rocketX, burst.rocketY, 2.6f, fillPaint)
			} else {
				// Contained soft glow
				if (burst.glowAlpha > 0.01f) {
					glowPaint.color = Color.argb(
						(burst.glowAlpha * 255f).roundToInt().coerceIn(0, 255),
						Color.red(paletteColor),
						Color.green(paletteColor),
						Color.blue(paletteColor)
					)
					canvas.drawCircle(burst.apexX, burst.apexY, burst.glowRadius, glowPaint)
				}

				// Spark particles
				strokePaint.strokeWidth = 2.2f
				for (i in 0 until burst.sparkCount) {
					val alpha = burst.sparkAlpha[i]
					if (alpha > 0.01f) {
						val alphaInt = (alpha * 255f).roundToInt().coerceIn(0, 255)
						strokePaint.color = Color.argb(
							alphaInt,
							Color.red(paletteColor),
							Color.green(paletteColor),
							Color.blue(paletteColor)
						)
						canvas.drawLine(
							burst.sparkPrevX[i],
							burst.sparkPrevY[i],
							burst.sparkX[i],
							burst.sparkY[i],
							strokePaint
						)

						fillPaint.color = Color.argb(alphaInt, 255, 255, 255)
						canvas.drawCircle(burst.sparkX[i], burst.sparkY[i], 1.5f, fillPaint)
					}
				}
			}
		}
	}

	private fun buildChristmasPaths() {
		sleighRunnerPath.reset()
		sleighRunnerPath.moveTo(-64f, 9f)
		sleighRunnerPath.cubicTo(-57f, 15f, -30f, 15f, -19f, 12f)
		sleighRunnerPath.cubicTo(-10f, 10f, -8f, 3f, -12f, 1f)
		sleighRunnerPath.cubicTo(-15f, -1f, -18f, 2f, -16f, 4f)
		sleighRunnerPath.moveTo(-49f, 8f)
		sleighRunnerPath.lineTo(-48f, 13f)
		sleighRunnerPath.moveTo(-27f, 8f)
		sleighRunnerPath.lineTo(-26f, 13f)

		sleighBodyPath.reset()
		sleighBodyPath.moveTo(-60f, -7f)
		sleighBodyPath.quadTo(-55f, -6f, -53f, -1f)
		sleighBodyPath.quadTo(-40f, 3f, -25f, 1f)
		sleighBodyPath.quadTo(-19f, 0f, -17f, -5f)
		sleighBodyPath.cubicTo(-12f, 1f, -17f, 8f, -24f, 9f)
		sleighBodyPath.quadTo(-41f, 12f, -55f, 7f)
		sleighBodyPath.quadTo(-54f, -1f, -60f, -7f)
		sleighBodyPath.close()

		toySackPath.reset()
		toySackPath.moveTo(-46f, -4f)
		toySackPath.cubicTo(-54f, -6f, -52f, -18f, -44f, -20f)
		toySackPath.cubicTo(-36f, -22f, -32f, -14f, -34f, -4f)
		toySackPath.close()

		santaPath.reset()
		santaPath.moveTo(-33f, -3f)
		santaPath.cubicTo(-34f, -10f, -31f, -14f, -27f, -15f)
		santaPath.quadTo(-30f, -19f, -26f, -22f)
		santaPath.quadTo(-29f, -25f, -28f, -25f)
		santaPath.quadTo(-22f, -29f, -19f, -23f)
		santaPath.quadTo(-15f, -20f, -20f, -16f)
		santaPath.quadTo(-17f, -13f, -16f, -10f)
		santaPath.lineTo(-11f, -8f)
		santaPath.lineTo(-13f, -5f)
		santaPath.lineTo(-21f, -8f)
		santaPath.quadTo(-19f, -3f, -20f, 1f)
		santaPath.close()

		reindeerBodyPath.reset()
		reindeerBodyPath.moveTo(20f, -5f)
		reindeerBodyPath.lineTo(15f, -11f)
		reindeerBodyPath.quadTo(21f, -12f, 25f, -8f)
		reindeerBodyPath.cubicTo(30f, -11f, 37f, -10f, 41f, -7f)
		reindeerBodyPath.quadTo(45f, -10f, 45f, -17f)
		reindeerBodyPath.lineTo(42f, -21f)
		reindeerBodyPath.quadTo(47f, -22f, 49f, -18f)
		reindeerBodyPath.quadTo(53f, -19f, 55f, -16f)
		reindeerBodyPath.lineTo(58f, -14f)
		reindeerBodyPath.quadTo(56f, -10f, 51f, -11f)
		reindeerBodyPath.quadTo(48f, -6f, 46f, 0f)
		reindeerBodyPath.quadTo(41f, 5f, 35f, 3f)
		reindeerBodyPath.quadTo(25f, 5f, 20f, -1f)
		reindeerBodyPath.close()

		reindeerAntlersPath.reset()
		reindeerAntlersPath.moveTo(49f, -18f)
		reindeerAntlersPath.quadTo(44f, -23f, 44f, -29f)
		reindeerAntlersPath.moveTo(45f, -24f)
		reindeerAntlersPath.lineTo(40f, -26f)
		reindeerAntlersPath.lineTo(39f, -30f)
		reindeerAntlersPath.moveTo(49f, -18f)
		reindeerAntlersPath.quadTo(52f, -24f, 51f, -29f)
		reindeerAntlersPath.moveTo(51f, -24f)
		reindeerAntlersPath.lineTo(55f, -27f)
	}

	private companion object {
		val PALETTE_COLORS = intArrayOf(
			Color.rgb(255, 215, 110),
			Color.rgb(255, 115, 140),
			Color.rgb(110, 235, 165),
			Color.rgb(120, 215, 255),
			Color.rgb(215, 145, 255),
			Color.rgb(245, 248, 255)
		)
	}
}
