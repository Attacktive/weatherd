package xyz.attacktive.weatherd

/**
 * Development override for the scene simulator controls on the home screen.
 * Release builds expose the same controls only when the user enables the simulator in Settings; debug builds can force them visible here.
 */
val debugToolsEnabled = BuildConfig.DEBUG && SHOW_DEBUG_TOOLS

private const val SHOW_DEBUG_TOOLS = true
