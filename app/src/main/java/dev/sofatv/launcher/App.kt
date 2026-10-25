package dev.sofatv.launcher

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import java.util.concurrent.Executors

/**
 * Process state. The launcher process stays alive all the time, so the app list and
 * settings live here rather than in the Activity: going back home needs no reload.
 */
class App : Application() {
    lateinit var store: SettingsStore
        private set
    lateinit var apps: AppRepository
        private set
    lateinit var tiles: TileCache
        private set
    lateinit var stats: StatsMonitor
        private set
    lateinit var sleepTimer: SleepTimer
        private set
    lateinit var cleaner: MemoryCleaner
        private set
    lateinit var memory: ProcessMemory
        private set
    lateinit var updater: Updater
        private set

    /** The home screen is currently shown (between onResume and onPause). */
    var homeVisible = false

    /** The home screen is recreated to apply a setting (rendering, grid); reopen the settings at it afterwards. */
    var reopenSettings: SettingsPages.Reopen? = null

    override fun onCreate() {
        super.onCreate()
        // One background thread for everything: app list, tiles, metrics and cleanup run in turn.
        val background = Executors.newSingleThreadExecutor()
        store = SettingsStore(this)
        Language.applyToApp(this, store.language)
        tiles = TileCache(this, background)
        apps = AppRepository(this, background, tiles)
        stats = StatsMonitor(this, background, store)
        sleepTimer = SleepTimer().apply { addListener { stats.redeliver() } }
        cleaner = MemoryCleaner(this, background, store)
        memory = ProcessMemory(this, background)
        updater = Updater(this, store)

        val packages = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = apps.reload()
        }, packages)

        // With the screen off there is no point in polling metrics or keeping the sleep timer.
        val screen = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val on = intent.action == Intent.ACTION_SCREEN_ON
                stats.setScreenOn(on)
                if (!on) sleepTimer.cancel()
            }
        }, screen)
        apps.reload()
        updater.checkIfDue()
    }

    /** The system resets the app resources to its own locale on a configuration change. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Language.applyToApp(this, store.language)
    }

    companion object {
        fun of(context: Context) = context.applicationContext as App
    }
}
