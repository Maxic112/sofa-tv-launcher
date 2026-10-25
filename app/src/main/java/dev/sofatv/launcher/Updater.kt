package dev.sofatv.launcher

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Self-update from GitHub Releases. The latest stable release (drafts and pre-releases are not
 * returned by `releases/latest`) is checked once a day; its tag ("v0.14.0") is compared with
 * our versionName, and its `.apk` asset is downloaded and installed through [PackageInstaller].
 *
 * The release URL is a build-time resource (R.string.update_url), so a test build can point
 * at a local server that serves the same JSON. Android itself makes sure the new APK is signed
 * with the same key, so only our own releases can be installed over the app.
 */
class Updater(private val context: Context, private val store: SettingsStore) {

    enum class State { IDLE, CHECKING, UP_TO_DATE, DOWNLOADING, INSTALLING }

    class Release(val version: String, val apkUrl: String)

    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<() -> Unit>()

    /** Network and file work; separate from the shared background thread so a download doesn't stall tiles. */
    private val executor = Executors.newSingleThreadExecutor()

    val currentVersion: String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    } catch (e: Exception) {
        ""
    }

    var state = State.IDLE
        private set

    /** A newer release found by the last successful check; null if there is none. */
    var available: Release? = null
        private set

    /** Download progress, 0–100. */
    var progress = 0
        private set

    /** The last check or install error, shown in settings; null when the last attempt went fine. */
    var error: String? = null
        private set

    /** Last failed automatic attempt (no network etc.), so a failing check isn't repeated on every Home press. */
    private var lastAttemptAt = 0L

    init {
        val saved = store.updateVersion
        if (saved != null && store.updateApkUrl != null && isNewer(saved, currentVersion)) {
            available = Release(saved, store.updateApkUrl!!)
        } else if (saved != null) {
            // Already installed (or older): forget it.
            store.updateVersion = null
            store.updateApkUrl = null
            store.save()
        }
        executor.execute { apkFile().delete() }
    }

    fun addListener(listener: () -> Unit) = listeners.add(listener)

    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    private fun changed() = listeners.toList().forEach { it() }

    private val busy: Boolean
        get() = state == State.CHECKING || state == State.DOWNLOADING || state == State.INSTALLING

    /** Daily automatic check; cheap to call often (on start and on every return to the home screen). */
    fun checkIfDue() {
        val now = System.currentTimeMillis()
        val sinceCheck = now - store.updateLastCheck
        if (sinceCheck in 0 until CHECK_INTERVAL_MS) return
        if (now - lastAttemptAt in 0 until RETRY_INTERVAL_MS) return
        check()
    }

    fun check() {
        if (busy) return
        lastAttemptAt = System.currentTimeMillis()
        state = State.CHECKING
        error = null
        changed()
        executor.execute {
            val result = runCatching { fetchLatest() }
            main.post {
                result.onSuccess { release ->
                    store.updateLastCheck = System.currentTimeMillis()
                    available = release?.takeIf { isNewer(it.version, currentVersion) }
                    store.updateVersion = available?.version
                    store.updateApkUrl = available?.apkUrl
                    store.save()
                    state = if (available == null) State.UP_TO_DATE else State.IDLE
                }.onFailure {
                    state = State.IDLE
                    error = context.getString(R.string.update_check_failed)
                }
                changed()
            }
        }
    }

    /** Latest release, or null if the repository has none yet (GitHub answers 404). */
    private fun fetchLatest(): Release? {
        val connection = open(context.getString(R.string.update_url))
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        val body = try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: FileNotFoundException) {
            return null
        } finally {
            connection.disconnect()
        }
        val json = JSONObject(body)
        val version = json.optString("tag_name").removePrefix("v").ifEmpty { return null }
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val url = asset.optString("browser_download_url")
            if (asset.optString("name").endsWith(".apk") && url.isNotEmpty()) return Release(version, url)
        }
        return null
    }

    /**
     * Download and install [available]. Android 8+ first needs "install unknown apps" allowed
     * for the launcher: without it we open that screen, and the user presses the button again.
     */
    fun install(activity: Activity) {
        val release = available ?: return
        if (busy) return
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= 26 && !pm.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            if (intent.resolveActivity(pm) != null) {
                Toast.makeText(activity, R.string.update_allow_install, Toast.LENGTH_LONG).show()
                activity.startActivity(intent)
            } else {
                Toast.makeText(activity, R.string.update_allow_install_manual, Toast.LENGTH_LONG).show()
            }
            return
        }
        state = State.DOWNLOADING
        progress = 0
        error = null
        changed()
        executor.execute {
            try {
                val file = apkFile()
                download(release.apkUrl, file)
                @Suppress("DEPRECATION")
                val archive = pm.getPackageArchiveInfo(file.path, 0)
                @Suppress("DEPRECATION")
                val current = pm.getPackageInfo(context.packageName, 0).versionCode
                @Suppress("DEPRECATION")
                if (archive?.packageName != context.packageName || archive.versionCode <= current) {
                    file.delete()
                    throw IllegalStateException("not a newer build of this app")
                }
                main.post {
                    state = State.INSTALLING
                    changed()
                }
                commit(file)
            } catch (e: Exception) {
                main.post { onInstallFailed(null) }
            }
        }
    }

    private fun download(url: String, file: File) {
        val connection = open(url)
        try {
            val total = connection.contentLengthLong
            var done = 0L
            var shown = -1
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        val percent = if (total > 0) (done * 100 / total).toInt() else 0
                        if (percent != shown) {
                            shown = percent
                            main.post {
                                progress = percent
                                changed()
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun commit(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // Skips the confirmation only when we are the installer of record; otherwise the system asks.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            // The installer fills in the status extras, so the PendingIntent must be mutable on Android 12+.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(context, id, Intent(context, InstallReceiver::class.java), flags)
            session.commit(callback.intentSender)
        }
    }

    /** [message] null means a download or file error; the user can simply try again. */
    fun onInstallFailed(message: String?) {
        state = State.IDLE
        error = message ?: context.getString(R.string.update_install_failed)
        apkFile().delete()
        changed()
    }

    private fun apkFile() = File(context.cacheDir, "update.apk")

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", "SofaTV-Launcher/$currentVersion")
        }

    /** Install result from [PackageInstaller]. On success the process is replaced, so there is nothing to do. */
    class InstallReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val updater = App.of(context).updater
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return updater.onInstallFailed(null)
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> Unit
                PackageInstaller.STATUS_FAILURE_ABORTED -> updater.onInstallFailed(context.getString(R.string.update_install_cancelled))
                else -> updater.onInstallFailed(
                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        ?.let { context.getString(R.string.update_install_failed_reason, it) },
                )
            }
        }
    }

    companion object {
        const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
        private const val RETRY_INTERVAL_MS = 60 * 60 * 1000L
        private const val TIMEOUT_MS = 20_000

        /** "0.14.0" > "0.13.2"; non-numeric parts ("-beta") are ignored. */
        fun isNewer(version: String, than: String): Boolean {
            fun parts(v: String) = v.split('.', '-').map { it.toIntOrNull() ?: 0 }
            val a = parts(version)
            val b = parts(than)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
