@file:Suppress("UNUSED_PARAMETER")

package xyz.attacktive.weatherd.domain.render

import android.content.res.Resources
import android.graphics.Canvas

/** Release builds never load the experimental artwork or replace the established renderer. */
internal class StormCloudPrototype(resources: Resources) {
	fun prewarm() {}

	fun draw(canvas: Canvas, width: Float, height: Float, params: SceneParams, timeSeconds: Float, tint: Int, grade: CloudColorGrade) = false
}
