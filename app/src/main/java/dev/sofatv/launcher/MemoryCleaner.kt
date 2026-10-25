package dev.sofatv.launcher

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executor

/**
 * Memory cleanup. The method is chosen in settings ([SettingsStore.cleanMethod]):
 *  - [METHOD_STANDARD]: killBackgroundProcesses, background processes only, no special rights.
 *    TCL firmware (Android 11) rejects it for every package ("Invalid packageName").
 *  - [METHOD_SAFETY_GUARD]: one-click cleanup of the system Safety Guard app (TCL). It runs with
 *    system rights and decides what to close by itself; our whitelist is ignored.
 *  - [METHOD_FORCE_STOP]: the key service presses "Force stop" on the app's settings page.
 */
class MemoryCleaner(
    private val context: Context,
    private val executor: Executor,
    private val store: SettingsStore,
) {
    private val main = Handler(Looper.getMainLooper())

    val safetyGuardAvailable: Boolean
        get() = context.packageManager.resolveActivity(safetyGuardIntent(), 0) != null

    /** Method from settings; defaults to Safety Guard when available, otherwise standard. */
    val method: String
        get() = store.cleanMethod ?: if (safetyGuardAvailable) METHOD_SAFETY_GUARD else METHOD_STANDARD

    /** [foreground] is the app on screen; it is left alone. */
    fun clean(foreground: String?) {
        when (method) {
            METHOD_SAFETY_GUARD -> cleanWithSafetyGuard()
            METHOD_FORCE_STOP -> forceStop(foreground)
            else -> killBackground(foreground)
        }
    }

    private fun killBackground(foreground: String?) {
        val keep = protectedPackages(foreground)
        executor.execute {
            val am = context.getSystemService(ActivityManager::class.java)
            val before = memory()
            for (info in context.packageManager.getInstalledApplications(0)) {
                if (info.packageName !in keep) am.killBackgroundProcesses(info.packageName)
            }
            // The system needs a moment for the processes to actually exit.
            main.postDelayed({ executor.execute { report(before) } }, SETTLE_MS)
        }
    }

    /** Safety Guard shows its own result (a widget with the amount), so no toast of ours. */
    private fun cleanWithSafetyGuard() {
        try {
            context.startActivity(safetyGuardIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            toast(context.getString(R.string.clean_guard_missing))
        }
    }

    private fun forceStop(foreground: String?) {
        val service = KeyService.instance
        if (service == null) {
            toast(context.getString(R.string.clean_needs_service))
            return
        }
        val keep = protectedPackages(foreground)
        val pm = context.packageManager
        // Only apps from the grid: third-party and updated system ones (YouTube, Play Store).
        // Already stopped ones (FLAG_STOPPED: not started since the last stop) are skipped.
        val targets = App.of(context).apps.apps.filter { app ->
            if (app.pkg in keep) return@filter false
            val info = try {
                pm.getApplicationInfo(app.pkg, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                return@filter false
            }
            val system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
            val updated = info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
            val stopped = info.flags and ApplicationInfo.FLAG_STOPPED != 0
            (!system || updated) && !stopped
        }
        if (targets.isEmpty()) {
            toast(context.getString(R.string.clean_nothing))
            return
        }
        val before = memory()
        service.forceStop(targets, returnTo = foreground) { stopped, unsupported ->
            // Not a single app page was recognized: the method doesn't work on this firmware, say so.
            if (unsupported) toast(context.getString(R.string.clean_force_stop_unsupported))
            else main.postDelayed({ executor.execute { report(before, stopped) } }, SETTLE_MS)
        }
    }

    /**
     * Result: used RAM and swap before/after and the difference. A single "freed" number is not
     * enough: on TVs with zram, background apps are already compressed into swap, and stopping
     * them mostly shrinks swap.
     */
    private fun report(before: Memory, stopped: Int = -1) {
        val after = memory()
        // One line each for before, after and freed: a toast is short-lived, a single long line can't be read in time.
        val lines = buildList {
            if (stopped >= 0) add(context.getString(R.string.clean_done_stopped, stopped))
            add(context.getString(R.string.clean_done_before, before.used / MB, before.swapUsed / MB))
            add(context.getString(R.string.clean_done_after, after.used / MB, after.swapUsed / MB))
            add(
                context.getString(
                    R.string.clean_done_freed,
                    ((before.used - after.used) / MB).coerceAtLeast(0),
                    ((before.swapUsed - after.swapUsed) / MB).coerceAtLeast(0),
                ),
            )
        }
        main.post { toast(lines.joinToString("\n")) }
    }

    private fun protectedPackages(foreground: String?): Set<String> {
        val keep = HashSet<String>()
        keep.add(context.packageName)
        keep.addAll(ALWAYS_KEEP)
        foreground?.let(keep::add)
        keep.addAll(store.cleanWhitelist)
        val resolver = context.contentResolver
        Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')?.let(keep::add)
        Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.forEach { keep.add(it.substringBefore('/')) }
        // VPN clients: a dropped VPN is worse than a few dozen megabytes.
        context.packageManager.queryIntentServices(Intent(VPN_SERVICE), 0)
            .forEach { keep.add(it.serviceInfo.packageName) }
        return keep
    }

    /** Used RAM is total minus available (MemTotal − MemAvailable), as in the status line. */
    private class Memory(val used: Long, val swapUsed: Long)

    private fun memory(): Memory {
        val values = HashMap<String, Long>()
        try {
            File("/proc/meminfo").forEachLine { line ->
                val value = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: return@forEachLine
                values[line.substringBefore(':')] = value * 1024
            }
        } catch (e: Exception) {
            // No access: show zeros.
        }
        val swapUsed = (values["SwapTotal"] ?: 0) - (values["SwapFree"] ?: 0)
        val used = (values["MemTotal"] ?: 0) - (values["MemAvailable"] ?: 0)
        return Memory(used, swapUsed)
    }

    private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    companion object {
        const val METHOD_STANDARD = "standard"
        const val METHOD_SAFETY_GUARD = "safety_guard"
        const val METHOD_FORCE_STOP = "force_stop"

        private const val SAFETY_GUARD = "com.tcl.guard"
        private const val SAFETY_GUARD_CLEAN = "com.tcl.tvweishi.ACTION_VIEW"
        private const val VPN_SERVICE = "android.net.VpnService"
        private const val SETTLE_MS = 800L
        private const val MB = 1024 * 1024

        /** Never stopped: the system itself, Settings (used to do the stopping), Safety Guard. */
        private val ALWAYS_KEEP = setOf("android", "com.android.tv.settings", "com.android.settings", SAFETY_GUARD)

        private fun safetyGuardIntent() = Intent(SAFETY_GUARD_CLEAN).setPackage(SAFETY_GUARD)
    }
}
