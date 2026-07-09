package xyz.attacktive.weatherd.domain.model

enum class PrecipitationKind { RAIN, SNOW, SLEET }

/**
 * What's falling from the sky and how hard.
 *
 * [severity] is the categorical strength the forecast code promises (0.35 drizzle .. 1.0 downpour/blizzard);
 * it drives streak styling and the base particle density. [observed] is the measured precipitation normalised
 * to 0..1 (mm/h over 10), which modulates the density so a "rain" code with barely any water stays sparse.
 */
data class Precipitation(val kind: PrecipitationKind, val severity: Float, val observed: Float)
