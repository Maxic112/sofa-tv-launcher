package dev.sofatv.launcher

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/**
 * Interface language chosen in settings ([SettingsStore.language]): "" follows the system,
 * otherwise a language code from [CHOICES].
 *
 * Activities get a context with the chosen locale in attachBaseContext. The application and
 * the key service share one Resources object, which is updated in place ([applyToApp]):
 * toasts and service overlays then follow the choice too.
 */
object Language {
    const val SYSTEM = ""
    val CHOICES = listOf(SYSTEM, "en", "ru")

    /** The system locale; [Resources.getSystem] is never affected by our override. */
    fun systemLocale(): Locale = Resources.getSystem().configuration.locales[0]

    private fun locale(code: String): Locale = if (code == SYSTEM) systemLocale() else Locale(code)

    fun wrap(base: Context, code: String): Context {
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale(code))
        return base.createConfigurationContext(config)
    }

    /** Call on start, after a change in settings and after every system configuration change. */
    fun applyToApp(app: Application, code: String) {
        val locale = locale(code)
        Locale.setDefault(locale)
        val res = app.resources
        if (res.configuration.locales[0] == locale) return
        val config = Configuration(res.configuration)
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        res.updateConfiguration(config, res.displayMetrics)
    }
}
