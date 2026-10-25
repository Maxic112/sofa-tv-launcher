package dev.sofatv.launcher

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * Accessibility service: Home interception and the remote key remapper.
 * It runs in the launcher process and keeps it alive, so the home screen opens instantly.
 *
 * Gestures: presses of one key are counted while the gap stays within [SettingsStore.doubleTapMs];
 * holding longer than [SettingsStore.longPressMs] is a gesture of its own. A second press is awaited only
 * for keys with a double press assigned; otherwise the action runs right on release.
 */
class KeyService : AccessibilityService() {
    private lateinit var app: App
    private lateinit var store: SettingsStore
    private lateinit var audio: AudioManager
    private lateinit var overlays: Overlays
    private lateinit var forceStopper: ForceStopper
    private val handler = Handler(Looper.getMainLooper())

    /** Package of the foreground window (for exclusions) and the last two apps (for "previous app"). */
    private var foreground: String? = null
    private var currentApp: String? = null
    private var previousApp: String? = null

    private var gestureKey = NO_KEY
    private var gestureRule: KeyRule? = null
    private var taps = 0
    private var longFired = false
    private var captureUpKey = NO_KEY

    private val onLongPress = Runnable {
        longFired = true
        gestureRule?.let { run(it, KeyRule.LONG, gestureKey) }
    }
    private val onTapsDone = Runnable { finishTaps() }

    /**
     * Emergency exit: holding Volume − for [RESET_HOLD_MS] turns key remapping off.
     * The service itself stays on: on TVs without an Accessibility screen it couldn't be re-enabled without ADB.
     *
     * An IR remote sends a stream of separate presses while held, so a short release
     * (up to [RESET_RELEASE_GRACE_MS]) doesn't break the hold; a Bluetooth remote keeps the key down.
     */
    private var volumeDownHeld = false
    private val onVolumeDownReleased = Runnable {
        volumeDownHeld = false
        handler.removeCallbacks(onVolumeDownHeld)
    }
    private val onVolumeDownHeld = Runnable {
        volumeDownHeld = false
        if (!store.remapEnabled) return@Runnable
        store.remapEnabled = false
        store.save()
        reset()
        Toast.makeText(this, R.string.remap_reset_done, Toast.LENGTH_LONG).show()
    }

    override fun onServiceConnected() {
        app = App.of(this)
        store = app.store
        audio = getSystemService(AudioManager::class.java)
        overlays = Overlays(ContextThemeWrapper(this, R.style.Theme_SofaTv))
        forceStopper = ForceStopper(this, overlays)
        instance = this
        overlays.setOnHome(app.homeVisible)
        overlays.restorePanel()
    }

    /** The Sofa TV home screen appeared or went away; reported by MainActivity itself (same process). */
    fun setHomeVisible(visible: Boolean) = overlays.setOnHome(visible)

    /** The overlay panel, toggled from the launcher settings. */
    fun setPanelEnabled(enabled: Boolean) = overlays.setPanelEnabled(enabled)

    /** "Key service" switch → off: the service can disable itself without special rights. */
    fun turnOff() {
        overlays.release()
        disableSelf()
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            overlays.release()
            app.sleepTimer.cancel()
        }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    fun showSleepPicker() = overlays.showSleepPicker()

    fun showSleepWarning() = overlays.showSleepWarning()

    /** The sleep timer ran out: pause the player and put the TV to sleep. */
    /** Force stop through settings; [returnTo] is where to go back afterwards. */
    fun forceStop(targets: List<AppEntry>, returnTo: String?, onDone: (stopped: Int, unsupported: Boolean) -> Unit) =
        forceStopper.start(targets, returnTo, onDone)

    fun goToSleep() {
        overlays.closeDialog()
        media(KeyEvent.KEYCODE_MEDIA_PAUSE)
        if (Build.VERSION.SDK_INT >= 28) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } else {
            toast(R.string.sleep_unsupported)
        }
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Settings pages flash by during cleanup; that's not the user switching apps.
        if (forceStopper.running) return
        if (pkg == SYSTEM_UI || pkg == currentInputMethod()) return
        // Our own overlays (panel, timer) aren't an app switch: the previous app stays underneath.
        if (pkg == packageName && event.className?.startsWith("android.widget.") == true) return
        foreground = pkg
        if (pkg != packageName && pkg != "android" && pkg != currentApp) {
            previousApp = currentApp
            currentApp = pkg
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val key = Keys.id(event)
        val down = event.action == KeyEvent.ACTION_DOWN

        // Before any rules: works even if Volume − itself is remapped.
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (down) {
                handler.removeCallbacks(onVolumeDownReleased)
                if (!volumeDownHeld) {
                    volumeDownHeld = true
                    handler.postDelayed(onVolumeDownHeld, RESET_HOLD_MS)
                }
            } else {
                handler.postDelayed(onVolumeDownReleased, RESET_RELEASE_GRACE_MS)
            }
        }

        // During cleanup keys don't reach the settings; Back aborts.
        if (forceStopper.running) {
            if (down && event.keyCode == KeyEvent.KEYCODE_BACK) forceStopper.cancel()
            return true
        }

        capture?.let { callback ->
            if (down && event.repeatCount == 0) {
                capture = null
                captureUpKey = key
                handler.post { callback(key) }
            }
            return true
        }
        if (key == captureUpKey) {
            if (!down) captureUpKey = NO_KEY
            return true
        }

        if (key != gestureKey) {
            // Release of a key whose press we didn't consume isn't ours.
            if (!down || event.repeatCount > 0) return false
            val rule = ruleFor(key) ?: return false
            finishTaps()
            gestureKey = key
            gestureRule = rule
            taps = 0
        }
        val rule = gestureRule ?: return false

        if (down) {
            if (event.repeatCount > 0) return true
            handler.removeCallbacks(onTapsDone)
            longFired = false
            if (rule.has(KeyRule.LONG)) handler.postDelayed(onLongPress, store.longPressMs.toLong())
        } else {
            handler.removeCallbacks(onLongPress)
            if (longFired) {
                reset()
                return true
            }
            taps++
            if (taps >= rule.maxTaps) finishTaps() else handler.postDelayed(onTapsDone, store.doubleTapMs.toLong())
        }
        return true
    }

    private fun ruleFor(key: Int): KeyRule? {
        val excluded = foreground?.let { it in store.remapExcluded } == true
        if (store.remapEnabled && !excluded) store.keys[key]?.takeUnless { it.isEmpty }?.let { return it }
        if (key == KeyEvent.KEYCODE_HOME && store.homeIntercept) return HOME_RULE
        return null
    }

    private fun finishTaps() {
        if (gestureKey == NO_KEY) return
        val count = taps
        val key = gestureKey
        val rule = gestureRule
        reset()
        if (count <= 0 || rule == null) return
        val gesture = if (count >= 2) KeyRule.DOUBLE else KeyRule.SINGLE
        run(rule, gesture, key)
    }

    private fun run(rule: KeyRule, gesture: Int, key: Int) {
        val action = rule.actions[gesture] ?: Keys.defaultAction(key, store.homeIntercept) ?: return
        runAction(action)
    }

    private fun reset() {
        handler.removeCallbacks(onLongPress)
        handler.removeCallbacks(onTapsDone)
        gestureKey = NO_KEY
        gestureRule = null
        taps = 0
        longFired = false
    }

    fun runAction(action: KeyAction) {
        when (action.type) {
            KeyAction.NONE -> Unit
            KeyAction.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            KeyAction.HOME -> start(homeIntent())
            KeyAction.SYSTEM_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            KeyAction.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            KeyAction.NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            KeyAction.POWER_DIALOG -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
            KeyAction.LOCK -> if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            KeyAction.SCREENSHOT -> if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            KeyAction.VOLUME_UP -> volume(AudioManager.ADJUST_RAISE)
            KeyAction.VOLUME_DOWN -> volume(AudioManager.ADJUST_LOWER)
            KeyAction.MUTE -> volume(AudioManager.ADJUST_TOGGLE_MUTE)
            KeyAction.PLAY_PAUSE -> media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            KeyAction.MEDIA_NEXT -> media(KeyEvent.KEYCODE_MEDIA_NEXT)
            KeyAction.MEDIA_PREVIOUS -> media(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            KeyAction.MEDIA_KEY -> action.arg?.toIntOrNull()?.let(::media)
            KeyAction.PREVIOUS_APP -> previousApp?.let(::launchPackage)
            KeyAction.APP -> action.arg?.let(::launchPackage)
            KeyAction.SETTINGS -> action.arg?.let { openSettings(it) }
            KeyAction.INTENT -> action.arg?.let { KeyAction.parseIntent(it) }?.let(::start)
            // Turning it on for good shows a toast: the panel may already be visible for 5 s, and without it
            // nothing would seem to change. The 5-second peek needs no toast, the panel appears anyway.
            KeyAction.PANEL -> {
                overlays.togglePanel()
                toast(if (store.statusOverlay) R.string.panel_shown_always else R.string.panel_hidden)
            }
            KeyAction.PANEL_PEEK -> overlays.peekPanel()
            KeyAction.SLEEP_TIMER -> overlays.showSleepPicker()
            KeyAction.CLEAN_MEMORY -> app.cleaner.clean(foreground)
        }
    }

    private fun homeIntent() = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_HOME)
        .setClassName(this, MainActivity::class.java.name)

    /**
     * Settings screen at the address from [SettingsScreens]. For Accessibility without its own address
     * (Xiaomi on Android 9): main settings plus a hint where the section is, instead of the system stub.
     */
    private fun openSettings(action: String) {
        val intent = SettingsScreens.intent(this, action)
        when {
            intent != null -> start(intent)
            action == Settings.ACTION_ACCESSIBILITY_SETTINGS -> {
                start(SettingsScreens.intent(this, Settings.ACTION_SETTINGS) ?: Intent(Settings.ACTION_SETTINGS))
                toast(R.string.accessibility_path_hint)
            }
            else -> start(Intent(action))
        }
    }

    private fun launchPackage(pkg: String) {
        val intent = packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) start(intent) else toast(R.string.cannot_open)
    }

    private fun start(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        } catch (e: ActivityNotFoundException) {
            toast(R.string.cannot_open)
        } catch (e: SecurityException) {
            toast(R.string.cannot_open)
        }
    }

    private fun volume(direction: Int) = audio.adjustSuggestedStreamVolume(
        direction, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI,
    )

    /** A media key goes to the active media session (the player), as the system does it. */
    private fun media(code: Int) {
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }

    private fun currentInputMethod(): String? =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')

    private fun toast(text: Int) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        private const val NO_KEY = Int.MIN_VALUE
        private const val RESET_HOLD_MS = 7_000L
        private const val RESET_RELEASE_GRACE_MS = 400L
        private const val SYSTEM_UI = "com.android.systemui"
        private val HOME_RULE = KeyRule(KeyEvent.KEYCODE_HOME).apply {
            actions[KeyRule.SINGLE] = KeyAction(KeyAction.HOME)
        }

        /** The service is running (enabled in Accessibility). */
        var instance: KeyService? = null
            private set

        /** A "press a key" prompt from settings: the next press goes here instead of the rules. */
        var capture: ((Int) -> Unit)? = null
    }
}
