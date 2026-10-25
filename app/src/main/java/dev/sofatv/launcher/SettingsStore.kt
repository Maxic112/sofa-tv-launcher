package dev.sofatv.launcher

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Launcher settings in a single JSON file (files/settings.json), the same file that is meant
 * to be exported and imported between TVs. Apps are identified by package name.
 */
class SettingsStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "settings.json"))

    val favorites = ArrayList<String>()
    val hidden = LinkedHashSet<String>()

    /** Remapper: rules by key code (see [Keys.id]). */
    val keys = LinkedHashMap<Int, KeyRule>()
    var homeIntercept = true

    /** Master switch for remapping (the [keys] rules); Home interception does not depend on it. */
    var remapEnabled = true
    var doubleTapMs = 300
    var longPressMs = 500

    /** Apps where the remapper is off (Home interception still works). */
    val remapExcluded = LinkedHashSet<String>()

    /** Metrics polling period for the home status line and the overlay panel. */
    var statsIntervalMs = 2000

    /** Status panel: the line on the home screen and the panel over other apps (needs the service). */
    var statusOnHome = true
    var statusOverlay = false

    /** Which metrics to show: keys of [StatsFormat.ITEMS]. */
    val statusItems = LinkedHashSet(StatsFormat.ITEMS.map { it.first })

    /** Apps memory cleanup leaves alone (VPN, keyboard and services are kept anyway). */
    val cleanWhitelist = LinkedHashSet<String>()

    /** Memory cleanup method ([MemoryCleaner.METHOD_STANDARD] etc.); null picks one automatically. */
    var cleanMethod: String? = null

    /**
     * Hardware (GPU) acceleration of the home screen: smoother scrolling (~48 fps vs ~42 on a
     * 2 GB TCL TV) at the cost of memory. Service windows (timer, panel) are always drawn in software.
     */
    var hardwareAcceleration = true

    /** With favorites present, "All apps" collapses under a button (as in Android TV). */
    var collapseAllApps = true

    /** Grid columns; 0 fits standard-size tiles to the screen width ([Grid]). */
    var gridColumns = 0

    /** Tile height as a percentage of the standard one (16:9 of the tile width). */
    var tileHeightPercent = 100

    /** TV inputs ([TvInputs]): a header button and a row of input tiles above or below the favorites. */
    var inputsButton = true

    /** Header buttons "Sleep timer" and "Clean memory" on the home screen. */
    var sleepButton = true
    var cleanButton = true
    var inputsOnHome = false
    var inputsBelowFavorites = false

    /** Interface language: [Language.SYSTEM] or a code from [Language.CHOICES]. */
    var language = Language.SYSTEM

    /** Updates ([Updater]): time of the last successful check and the newer release it found. */
    var updateLastCheck = 0L
    var updateVersion: String? = null
    var updateApkUrl: String? = null

    init {
        load()
    }

    fun isFavorite(pkg: String) = pkg in favorites

    fun toggleFavorite(pkg: String) {
        if (!favorites.remove(pkg)) favorites.add(pkg)
        save()
    }

    fun hide(pkg: String) {
        favorites.remove(pkg)
        hidden.add(pkg)
        save()
    }

    fun unhide(packages: Collection<String>) {
        hidden.removeAll(packages.toSet())
        save()
    }

    /** Moves a favorite by [delta] positions; returns false if it can't move any further. */
    fun moveFavorite(pkg: String, delta: Int): Boolean {
        val from = favorites.indexOf(pkg)
        if (from < 0) return false
        val to = (from + delta).coerceIn(0, favorites.size - 1)
        if (to == from) return false
        favorites.removeAt(from)
        favorites.add(to, pkg)
        return true
    }

    fun save() {
        val keyRules = JSONArray()
        keys.values.forEach { if (!it.isEmpty) keyRules.put(it.toJson()) }
        val remap = JSONObject()
            .put("homeIntercept", homeIntercept)
            .put("enabled", remapEnabled)
            .put("doubleTapMs", doubleTapMs)
            .put("longPressMs", longPressMs)
            .put("excluded", JSONArray(remapExcluded.toList()))
            .put("keys", keyRules)
        val json = JSONObject()
            .put("version", 1)
            .put("favorites", JSONArray(favorites))
            .put("hidden", JSONArray(hidden.toList()))
            .put("remap", remap)
            .put(
                "panel",
                JSONObject()
                    .put("intervalMs", statsIntervalMs)
                    .put("home", statusOnHome)
                    .put("overlay", statusOverlay)
                    .put("items", JSONArray(statusItems.toList())),
            )
            .put(
                "update",
                JSONObject()
                    .put("lastCheck", updateLastCheck)
                    .put("version", updateVersion)
                    .put("apkUrl", updateApkUrl),
            )
            .put("clean", JSONObject().put("whitelist", JSONArray(cleanWhitelist.toList())).put("method", cleanMethod))
            .put(
                "ui",
                JSONObject()
                    .put("hardwareAcceleration", hardwareAcceleration)
                    .put("collapseAllApps", collapseAllApps)
                    .put("gridColumns", gridColumns)
                    .put("tileHeightPercent", tileHeightPercent)
                    .put("inputsButton", inputsButton)
                    .put("sleepButton", sleepButton)
                    .put("cleanButton", cleanButton)
                    .put("inputsOnHome", inputsOnHome)
                    .put("inputsBelowFavorites", inputsBelowFavorites)
                    .put("language", language),
            )
        val out = try {
            file.startWrite()
        } catch (e: IOException) {
            Log.w(TAG, "save", e)
            return
        }
        try {
            out.write(json.toString(2).toByteArray())
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            Log.w(TAG, "save", e)
        }
    }

    private fun load() {
        val json = try {
            JSONObject(String(file.readFully()))
        } catch (e: FileNotFoundException) {
            return
        } catch (e: Exception) {
            Log.w(TAG, "load", e)
            return
        }
        json.optJSONArray("favorites")?.forEachString { favorites.add(it) }
        json.optJSONArray("hidden")?.forEachString { hidden.add(it) }
        json.optJSONObject("remap")?.let { remap ->
            homeIntercept = remap.optBoolean("homeIntercept", homeIntercept)
            remapEnabled = remap.optBoolean("enabled", remapEnabled)
            doubleTapMs = remap.optInt("doubleTapMs", doubleTapMs)
            longPressMs = remap.optInt("longPressMs", longPressMs)
            remap.optJSONArray("excluded")?.forEachString { remapExcluded.add(it) }
            val rules = remap.optJSONArray("keys") ?: JSONArray()
            for (i in 0 until rules.length()) {
                rules.optJSONObject(i)?.let(KeyRule::fromJson)?.let { keys[it.key] = it }
            }
        }
        json.optJSONObject("panel")?.let { panel ->
            statsIntervalMs = panel.optInt("intervalMs", statsIntervalMs)
            statusOnHome = panel.optBoolean("home", statusOnHome)
            statusOverlay = panel.optBoolean("overlay", statusOverlay)
            panel.optJSONArray("items")?.let { items ->
                statusItems.clear()
                items.forEachString { statusItems.add(it) }
            }
        }
        json.optJSONObject("ui")?.let { ui ->
            hardwareAcceleration = ui.optBoolean("hardwareAcceleration", hardwareAcceleration)
            collapseAllApps = ui.optBoolean("collapseAllApps", collapseAllApps)
            gridColumns = ui.optInt("gridColumns", gridColumns)
            tileHeightPercent = ui.optInt("tileHeightPercent", tileHeightPercent)
            inputsButton = ui.optBoolean("inputsButton", inputsButton)
            sleepButton = ui.optBoolean("sleepButton", sleepButton)
            cleanButton = ui.optBoolean("cleanButton", cleanButton)
            inputsOnHome = ui.optBoolean("inputsOnHome", inputsOnHome)
            inputsBelowFavorites = ui.optBoolean("inputsBelowFavorites", inputsBelowFavorites)
            language = ui.optString("language", language).takeIf { it in Language.CHOICES } ?: Language.SYSTEM
        }
        json.optJSONObject("update")?.let { update ->
            updateLastCheck = update.optLong("lastCheck", updateLastCheck)
            updateVersion = update.optString("version").ifEmpty { null }
            updateApkUrl = update.optString("apkUrl").ifEmpty { null }
        }
        json.optJSONObject("clean")?.let { clean ->
            clean.optJSONArray("whitelist")?.forEachString { cleanWhitelist.add(it) }
            cleanMethod = clean.optString("method").ifEmpty { null }
        }
    }

    private inline fun JSONArray.forEachString(action: (String) -> Unit) {
        for (i in 0 until length()) optString(i).takeIf { it.isNotEmpty() }?.let(action)
    }

    private companion object {
        const val TAG = "SettingsStore"
    }
}
