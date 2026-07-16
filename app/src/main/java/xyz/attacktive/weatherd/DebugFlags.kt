package xyz.attacktive.weatherd

/**
 * Master switch for on-screen debug tooling (the scene picker on the home screen).
 * Flip [SHOW_DEBUG_TOOLS] to false to preview the production UI; release builds strip the tooling regardless, since the flag is gated on [BuildConfig.DEBUG].
 */
val debugToolsEnabled = BuildConfig.DEBUG && SHOW_DEBUG_TOOLS

private const val SHOW_DEBUG_TOOLS = true
