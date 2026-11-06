package xyz.attacktive.weatherd.domain.render

import xyz.attacktive.weatherd.BuildConfig

/** Keep the enabled rain adjustment inside the cached palette path shared by sky and scenery. */
internal fun prototypeSkyGradient(params: SceneParams, gradient: SkyGradient, enabled: Boolean = BuildConfig.STORM_CLOUD_PROTOTYPE) = if (enabled) {
	stormCloudSkyGradient(params, gradient)
} else {
	gradient
}
