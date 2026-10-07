package xyz.attacktive.weatherd.domain.render

/** Coverage chooses nested populations, not the opacity of established material. */
internal fun nearCumulusPopulationStep(lowCloudiness: Float) = ((lowCloudiness - 0.10f) / 0.65f).coerceIn(0f, 1f) * 3f

/** Only the first population's initial appearance uses this birth weight. */
internal fun initialCumulusPopulationStrength(lowCloudiness: Float) = cloudBirthStrength(0.10f, 0.20f, lowCloudiness)

/** Dry cumulus remains solid underneath incoming banks; wet weather uses banks alone. */
internal fun shouldDrawDryClouds(params: SceneParams) = params.precipitation == null && params.fogDensity < 0.8f && effectiveOpaqueCloudiness(params) > 0.10f

/** Banks grow over the dry underlayer, with precipitation taking precedence over dense fog. */
internal fun overcastBankStrength(params: SceneParams): Float {
	if (params.precipitation != null) {
		return 1f
	}

	if (params.fogDensity >= 0.8f) {
		return 0f
	}

	return cloudBirthStrength(0.70f, 0.85f, effectiveOpaqueCloudiness(params))
}

private fun cloudBirthStrength(start: Float, end: Float, cover: Float): Float {
	val fraction = ((cover - start) / (end - start)).coerceIn(0f, 1f)
	return fraction * fraction * (3f - 2f * fraction)
}

internal const val NEAR_CLOUD_PORTRAIT_HEIGHT = 0.82f
internal const val NEAR_CLOUD_PORTRAIT_TOP = -0.04f
