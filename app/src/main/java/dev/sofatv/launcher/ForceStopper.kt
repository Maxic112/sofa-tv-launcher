package dev.sofatv.launcher

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Force-stops apps without special rights: opens the app's page in system settings and
 * presses "Force stop" → "OK" through the accessibility service, the way a person would.
 * A progress screen covers the settings meanwhile.
 *
 * Button labels come from the resources of whichever settings app opens the page on this
 * device ([STOP_RESOURCES], [OK_RESOURCES]), giving exactly the on-screen text in any language.
 * Our own list ([STOP_LABELS], [OK_LABELS]) is the fallback. The page is recognized as a
 * settings app window showing the package or the label of the app being stopped.
 * Tested on Android TV 11 settings (com.android.tv.settings, AppManagementActivity).
 */
class ForceStopper(
    private val service: AccessibilityService,
    private val overlays: Overlays,
) {
    /** Button labels on this device; resolved on every run (the language may have changed). */
    private class Labels(val settingsPackage: String?, val stop: List<String>, val ok: List<String>)

    private var labels = Labels(null, STOP_LABELS, OK_LABELS)

    /** How many app pages were recognized; 0 means the method doesn't work on this firmware. */
    private var pagesRecognized = 0
    private var pageCounted = false
    private enum class Step { OPEN_PAGE, CONFIRM, CLOSE_CONFIRM }

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<AppEntry>()
    private var total = 0
    private var stopped = 0
    private var current: AppEntry? = null
    private var step = Step.OPEN_PAGE
    private var deadline = 0L

    /** When the app page opened without "Force stop": give it a moment to finish drawing. */
    private var pageWithoutStopSince = 0L

    private var returnTo: String? = null
    private var onDone: ((stopped: Int, unsupported: Boolean) -> Unit)? = null
    private val pollRunnable = Runnable { poll() }
    private val hideProgress = Runnable { overlays.hideProgress() }

    var running = false
        private set

    /** [onDone] gets the number stopped and "unsupported": no app page was recognized at all. */
    fun start(targets: List<AppEntry>, returnTo: String?, onDone: (stopped: Int, unsupported: Boolean) -> Unit) {
        if (running) return
        running = true
        handler.removeCallbacks(hideProgress)
        queue.clear()
        queue.addAll(targets)
        total = targets.size
        stopped = 0
        pagesRecognized = 0
        labels = targets.firstOrNull()?.let { loadLabels(it.pkg) } ?: labels
        this.returnTo = returnTo
        this.onDone = onDone
        overlays.showProgress(service.getString(R.string.clean_progress_title), "")
        next()
    }

    /** Back during cleanup: stop after the current app. */
    fun cancel() {
        if (!running) return
        queue.clear()
        finish()
    }

    private fun next() {
        handler.removeCallbacks(pollRunnable)
        val app = queue.removeFirstOrNull() ?: return finish()
        current = app
        val index = total - queue.size
        overlays.updateProgress(service.getString(R.string.clean_progress, index, total, app.label))
        val intent = detailsIntent(app.pkg)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            )
        try {
            service.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            return finish()
        }
        setStep(Step.OPEN_PAGE, PAGE_TIMEOUT_MS)
        pageWithoutStopSince = 0
        pageCounted = false
        handler.postDelayed(pollRunnable, POLL_MS)
    }

    private fun poll() {
        if (!running) return
        val app = current ?: return next()
        val root = service.rootInActiveWindow
        val now = SystemClock.uptimeMillis()
        when (step) {
            Step.OPEN_PAGE -> if (root != null && isAppPage(root, app)) {
                if (!pageCounted) {
                    pageCounted = true
                    pagesRecognized++
                }
                val stop = findClickable(root, labels.stop)
                when {
                    stop != null -> {
                        stop.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        setStep(Step.CONFIRM, CONFIRM_TIMEOUT_MS)
                    }
                    pageWithoutStopSince == 0L -> pageWithoutStopSince = now
                    // "Force stop" never appeared (or is disabled): the app is already stopped.
                    now - pageWithoutStopSince > SETTLE_MS -> return next()
                }
            }
            Step.CONFIRM -> root?.let { findClickable(it, labels.ok) }?.let { ok ->
                ok.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                stopped++
                setStep(Step.CLOSE_CONFIRM, CONFIRM_TIMEOUT_MS)
            }
            // The confirmation closed: on to the next app.
            Step.CLOSE_CONFIRM -> if (root != null && findClickable(root, labels.ok) == null) return next()
        }
        if (now > deadline) return next()
        handler.postDelayed(pollRunnable, POLL_MS)
    }

    private fun setStep(value: Step, timeoutMs: Long) {
        step = value
        deadline = SystemClock.uptimeMillis() + timeoutMs
    }

    private fun finish() {
        handler.removeCallbacks(pollRunnable)
        running = false
        current = null
        // Hide the progress screen only after the transition back has happened beneath it,
        // otherwise the closing settings page flashes by.
        handler.postDelayed(hideProgress, HIDE_DELAY_MS)
        // Go back to where we started: the home screen or the app that was on screen.
        val target = returnTo
        val back = if (target == null || target == service.packageName) {
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setClassName(service, MainActivity::class.java.name)
        } else {
            service.packageManager.getLeanbackLaunchIntentForPackage(target)
                ?: service.packageManager.getLaunchIntentForPackage(target)
        }
        try {
            back?.let { service.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        } catch (e: ActivityNotFoundException) {
            // Nowhere to go back to: stay where we are.
        }
        onDone?.invoke(stopped, total > 0 && pagesRecognized == 0)
        onDone = null
    }

    private fun detailsIntent(pkg: String) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))

    /**
     * Button labels from the resources of the settings app that opens the [pkg] page
     * (com.android.tv.settings, com.android.settings, or a vendor's own): strings found by name
     * come first, our own list is the fallback. The system "OK" is a candidate too.
     */
    private fun loadLabels(pkg: String): Labels {
        val pm = service.packageManager
        val settings = pm.resolveActivity(detailsIntent(pkg), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        // In the system locale: the settings app shows its buttons in it, whatever language the launcher uses.
        val res = settings?.let {
            try {
                service.createPackageContext(it, 0).createConfigurationContext(Resources.getSystem().configuration).resources
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }
        fun fromResources(names: List<String>) = names.mapNotNull { name ->
            val id = res?.getIdentifier(name, "string", settings) ?: 0
            if (id == 0) null else res?.getString(id)?.trim()?.takeIf { it.isNotEmpty() }
        }
        return Labels(
            settingsPackage = settings,
            stop = (fromResources(STOP_RESOURCES) + STOP_LABELS).distinct(),
            ok = (fromResources(OK_RESOURCES) + Resources.getSystem().getString(android.R.string.ok) + OK_LABELS).distinct(),
        )
    }

    /**
     * The page of exactly this app is open: a settings app window (if known) showing the package
     * (Android TV shows it under the label) or the label (regular settings don't show the package).
     * Checking the window package keeps the progress screen or the previous app from matching.
     */
    private fun isAppPage(root: AccessibilityNodeInfo, app: AppEntry): Boolean {
        val settings = labels.settingsPackage
        if (settings != null && root.packageName?.toString() != settings) return false
        return hasText(root, app.pkg) || hasText(root, app.label)
    }

    private fun hasText(root: AccessibilityNodeInfo, text: String) =
        text.isNotEmpty() && root.findAccessibilityNodeInfosByText(text).any { it.text?.toString()?.trim() == text }

    /** A node with one of the labels (exact match), resolved to its nearest clickable ancestor. */
    private fun findClickable(root: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        for (label in labels) {
            for (node in root.findAccessibilityNodeInfosByText(label)) {
                if (!node.text?.toString()?.trim().equals(label, ignoreCase = true)) continue
                var target: AccessibilityNodeInfo? = node
                while (target != null && !target.isClickable) target = target.parent
                if (target != null && target.isEnabled) return target
            }
        }
        return null
    }

    private companion object {
        const val POLL_MS = 120L
        const val SETTLE_MS = 500L
        const val PAGE_TIMEOUT_MS = 4000L
        const val CONFIRM_TIMEOUT_MS = 3000L
        const val HIDE_DELAY_MS = 600L

        /** String names in the settings app: Android TV (TvSettings) and regular Android settings. */
        val STOP_RESOURCES = listOf("device_apps_app_management_force_stop", "force_stop")
        val OK_RESOURCES = listOf("okay", "settings_ok", "dlg_ok")

        /** Fallback labels for when the resources aren't found (custom vendor firmware). They match UI text, so they stay localized. */
        val STOP_LABELS = listOf("Остановить", "Принудительно остановить", "Force stop")
        val OK_LABELS = listOf("ОК", "OK")
    }
}
