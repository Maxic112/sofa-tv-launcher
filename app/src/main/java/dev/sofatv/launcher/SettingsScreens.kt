package dev.sofatv.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * How to open a system settings screen on this firmware.
 *
 * The standard action may lead to a stub: on Google TV [Settings.ACTION_ACCESSIBILITY_SETTINGS]
 * is taken by frameworkpackagestubs, even though the TV settings do have an Accessibility
 * section, reachable through its own OEM link (e.g. TCL). So each screen has a list of
 * candidates in order: the standard action, then fallbacks; the first one that leads to a real,
 * accessible screen wins. For firmware with yet another address, add its candidate to
 * [ALTERNATIVES] (and to `<queries>` in the manifest).
 */
object SettingsScreens {

    private val ALTERNATIVES: Map<String, List<Intent>> = mapOf(
        Settings.ACTION_ACCESSIBILITY_SETTINGS to listOf(
            // Android TV / Google TV settings (TCL and others): the Accessibility section.
            Intent("android.settings.ACCESSIBILITY_TV_OEM_LINK"),
            // Older Android TV settings without the OEM link.
            Intent().setComponent(
                ComponentName("com.android.tv.settings", "com.android.tv.settings.accessibility.AccessibilityActivity"),
            ),
        ),
    )

    /** Intent for the real [action] screen; null if this firmware has no such screen. */
    fun intent(context: Context, action: String): Intent? {
        val pm = context.packageManager
        return (listOf(Intent(action)) + ALTERNATIVES[action].orEmpty())
            .firstOrNull { isReal(pm, it) }
            ?.let(::Intent)
    }

    /** The screen exists, other apps may open it, and it is not a stub. */
    private fun isReal(pm: PackageManager, intent: Intent): Boolean {
        val target = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo ?: return false
        return target.exported && !target.packageName.contains("stub", ignoreCase = true)
    }
}
