package dev.sofatv.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.os.Handler
import android.os.Looper
import java.text.Collator
import java.util.concurrent.Executor

class AppEntry(
    val pkg: String,
    val label: String,
    val info: ActivityInfo,
    val leanback: Boolean,
) {
    val component = ComponentName(info.packageName, info.name)

    val isSystem: Boolean
        get() = info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0

    /**
     * Key of the rendered tile. It changes when the app is updated (sourceDir moves to a
     * new directory) or renamed, so a stale cache entry simply stops matching.
     */
    val tileKey: String = pkg + "_" +
        Integer.toHexString("${info.applicationInfo.sourceDir}|${info.name}|$label".hashCode())

    fun launchIntent(): Intent = Intent(Intent.ACTION_MAIN)
        .addCategory(if (leanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
        .setComponent(component)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}

/** List of launchable apps. Loaded in the background; listeners are called on the main thread. */
class AppRepository(
    private val context: Context,
    private val executor: Executor,
    private val tiles: TileCache,
) {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<() -> Unit>()
    private var loading = false
    private var reloadAgain = false

    var apps: List<AppEntry> = emptyList()
        private set
    var loaded = false
        private set

    fun addListener(listener: () -> Unit) = listeners.add(listener)

    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    fun find(pkg: String) = apps.firstOrNull { it.pkg == pkg }

    fun reload() {
        if (loading) {
            reloadAgain = true
            return
        }
        loading = true
        executor.execute {
            val list = query()
            tiles.prune(list.mapTo(HashSet()) { it.tileKey })
            main.post {
                apps = list
                loaded = true
                loading = false
                listeners.toList().forEach { it() }
                if (reloadAgain) {
                    reloadAgain = false
                    reload()
                }
            }
        }
    }

    private fun query(): List<AppEntry> {
        val pm = context.packageManager
        // One tile per package; the TV activity (LEANBACK_LAUNCHER) wins over the regular one.
        val byPackage = LinkedHashMap<String, Pair<ResolveInfo, Boolean>>()
        for (leanback in booleanArrayOf(true, false)) {
            val category = if (leanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg != context.packageName && pkg !in byPackage) byPackage[pkg] = ri to leanback
            }
        }
        val collator = Collator.getInstance()
        return byPackage.map { (pkg, value) ->
            val (ri, leanback) = value
            AppEntry(pkg, ri.loadLabel(pm).toString().trim(), ri.activityInfo, leanback)
        }.sortedWith(compareBy(collator) { it.label })
    }
}
