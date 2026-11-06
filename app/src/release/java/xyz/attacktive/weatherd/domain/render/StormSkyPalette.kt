@file:Suppress("UNUSED_PARAMETER")

package xyz.attacktive.weatherd.domain.render

/** Release palettes never apply the experimental daytime rain grade. */
internal fun prototypeSkyGradient(params: SceneParams, gradient: SkyGradient, enabled: Boolean = false) = gradient
