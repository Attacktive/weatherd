package xyz.attacktive.weatherd.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

internal data class HomeLauncher(val packageName: String, val label: String)

internal fun currentHomeLauncher(context: Context): HomeLauncher? {
	val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
	val resolved = context.packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY) ?: return null
	val activityInfo = resolved.activityInfo ?: return null
	val packageName = activityInfo.packageName
	val label = resolved.loadLabel(context.packageManager).toString()

	return HomeLauncher(packageName, label)
}

internal fun wallpaperScrollingSupportedBy(homePackageName: String?) = homePackageName !in UNSUPPORTED_WALLPAPER_SCROLLING_LAUNCHERS

private val UNSUPPORTED_WALLPAPER_SCROLLING_LAUNCHERS = setOf(
	"com.sec.android.app.launcher"
)
