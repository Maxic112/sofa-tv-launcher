package dev.sofatv.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import org.json.JSONObject

/**
 * Action assigned to a key gesture. [arg] is a package, a settings screen action,
 * a media key code or an intent string; [name] is the label for shortcuts.
 */
class KeyAction(val type: String, val arg: String? = null, val name: String? = null) {

    fun toJson(): JSONObject = JSONObject().put("type", type).apply {
        arg?.let { put("arg", it) }
        name?.let { put("name", it) }
    }

    companion object {
        const val NONE = "none"
        const val BACK = "back"
        const val HOME = "home"
        const val SYSTEM_HOME = "system_home"
        const val RECENTS = "recents"
        const val NOTIFICATIONS = "notifications"
        const val POWER_DIALOG = "power_dialog"
        const val LOCK = "lock"
        const val SCREENSHOT = "screenshot"
        const val VOLUME_UP = "volume_up"
        const val VOLUME_DOWN = "volume_down"
        const val MUTE = "mute"
        const val PLAY_PAUSE = "play_pause"
        const val MEDIA_NEXT = "media_next"
        const val MEDIA_PREVIOUS = "media_previous"
        const val MEDIA_KEY = "media_key"
        const val PREVIOUS_APP = "previous_app"
        const val APP = "app"
        const val SETTINGS = "settings"
        const val INTENT = "intent"
        const val PANEL = "panel"
        const val PANEL_PEEK = "panel_peek"
        const val SLEEP_TIMER = "sleep_timer"
        const val CLEAN_MEMORY = "clean_memory"

        /** Parameterless action for the picker: type, label, icon. */
        class Simple(val type: String, val label: Int, val icon: Int)

        /**
         * Parameterless actions in display order: "nothing" first, then the launcher's own
         * actions (labelled "Sofa TV: …"), then system ones.
         */
        val SIMPLE: List<Simple> = buildList {
            add(Simple(NONE, R.string.action_none, R.drawable.ic_block))
            add(Simple(HOME, R.string.action_home, R.drawable.ic_home))
            add(Simple(PANEL, R.string.action_panel, R.drawable.ic_monitoring))
            add(Simple(PANEL_PEEK, R.string.action_panel_peek, R.drawable.ic_timer_5))
            add(Simple(SLEEP_TIMER, R.string.action_sleep_timer, R.drawable.ic_bedtime))
            add(Simple(CLEAN_MEMORY, R.string.action_clean_memory, R.drawable.ic_cleaning_services))
            add(Simple(BACK, R.string.action_back, R.drawable.ic_arrow_back))
            add(Simple(SYSTEM_HOME, R.string.action_system_home, R.drawable.ic_house))
            add(Simple(PREVIOUS_APP, R.string.action_previous_app, R.drawable.ic_flip_to_back))
            add(Simple(RECENTS, R.string.action_recents, R.drawable.ic_history))
            add(Simple(NOTIFICATIONS, R.string.action_notifications, R.drawable.ic_notifications))
            add(Simple(VOLUME_UP, R.string.action_volume_up, R.drawable.ic_volume_up))
            add(Simple(VOLUME_DOWN, R.string.action_volume_down, R.drawable.ic_volume_down))
            add(Simple(MUTE, R.string.action_mute, R.drawable.ic_volume_off))
            add(Simple(PLAY_PAUSE, R.string.action_play_pause, R.drawable.ic_play_pause))
            add(Simple(MEDIA_NEXT, R.string.action_media_next, R.drawable.ic_skip_next))
            add(Simple(MEDIA_PREVIOUS, R.string.action_media_previous, R.drawable.ic_skip_previous))
            add(Simple(POWER_DIALOG, R.string.action_power_dialog, R.drawable.ic_power_settings_new))
            if (Build.VERSION.SDK_INT >= 28) {
                add(Simple(LOCK, R.string.action_lock, R.drawable.ic_tv_off))
                add(Simple(SCREENSHOT, R.string.action_screenshot, R.drawable.ic_screenshot_monitor))
            }
        }

        /** System settings screens a key can open. */
        val SETTINGS_SCREENS: List<Pair<String, Int>> = listOf(
            Settings.ACTION_SETTINGS to R.string.screen_all,
            Settings.ACTION_WIFI_SETTINGS to R.string.screen_wifi,
            Settings.ACTION_BLUETOOTH_SETTINGS to R.string.screen_bluetooth,
            Settings.ACTION_SOUND_SETTINGS to R.string.screen_sound,
            Settings.ACTION_DISPLAY_SETTINGS to R.string.screen_display,
            Settings.ACTION_APPLICATION_SETTINGS to R.string.screen_apps,
            Settings.ACTION_INTERNAL_STORAGE_SETTINGS to R.string.screen_storage,
            Settings.ACTION_DATE_SETTINGS to R.string.screen_date,
            Settings.ACTION_LOCALE_SETTINGS to R.string.screen_locale,
            Settings.ACTION_ACCESSIBILITY_SETTINGS to R.string.screen_accessibility,
            Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS to R.string.screen_developer,
        )

        fun fromJson(json: JSONObject?): KeyAction? {
            val type = json?.optString("type").orEmpty()
            if (type.isEmpty()) return null
            return KeyAction(type, json!!.optString("arg").ifEmpty { null }, json.optString("name").ifEmpty { null })
        }

        /** null means "default", i.e. the key's normal behavior. */
        fun label(context: Context, action: KeyAction?): String {
            if (action == null) return context.getString(R.string.action_default)
            SIMPLE.firstOrNull { it.type == action.type }?.let { return context.getString(it.label) }
            return when (action.type) {
                APP -> {
                    val pkg = action.arg.orEmpty()
                    context.getString(R.string.action_app_named, App.of(context).apps.find(pkg)?.label ?: pkg)
                }
                SETTINGS -> SETTINGS_SCREENS.firstOrNull { it.first == action.arg }
                    ?.let { context.getString(R.string.action_settings_named, context.getString(it.second)) }
                    ?: action.arg.orEmpty()
                INTENT -> action.name ?: action.arg.orEmpty()
                MEDIA_KEY -> Keys.name(context, action.arg?.toIntOrNull() ?: 0)
                else -> action.type
            }
        }

        /**
         * Intent for "Custom intent": a link (http, market etc., opened as VIEW),
         * an intent:…;end / #Intent;…;end string, or a "package/.Activity" component.
         */
        fun parseIntent(text: String): Intent? {
            val value = text.trim()
            return try {
                when {
                    value.startsWith("intent:") || value.startsWith("#Intent") -> Intent.parseUri(value, Intent.URI_INTENT_SCHEME)
                    value.contains("://") || value.startsWith("market:") -> Intent(Intent.ACTION_VIEW, Uri.parse(value))
                    value.contains('/') -> ComponentName.unflattenFromString(value)?.let { Intent().setComponent(it) }
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** Settings of one key: an action per gesture; null means "default". */
class KeyRule(val key: Int) {
    val actions = arrayOfNulls<KeyAction>(GESTURES)

    val isEmpty: Boolean
        get() = actions.all { it == null }

    fun has(gesture: Int) = actions[gesture] != null

    /** How many consecutive presses are worth waiting for. */
    val maxTaps: Int
        get() = if (has(DOUBLE)) 2 else 1

    fun toJson(): JSONObject = JSONObject().put("key", key).apply {
        for (g in 0 until GESTURES) actions[g]?.let { put(GESTURE_NAMES[g], it.toJson()) }
    }

    companion object {
        const val SINGLE = 0
        const val DOUBLE = 1
        const val LONG = 2
        const val GESTURES = 3

        /** Triple press was removed in 0.9.0: "triple" in old settings is simply not read. */
        private val GESTURE_NAMES = arrayOf("single", "double", "long")
        val GESTURE_LABELS = intArrayOf(R.string.gesture_single, R.string.gesture_double, R.string.gesture_long)
        val GESTURE_ICONS = intArrayOf(R.drawable.ic_filter_1, R.drawable.ic_filter_2, R.drawable.ic_hourglass_top)

        fun fromJson(json: JSONObject): KeyRule? {
            if (!json.has("key")) return null
            val rule = KeyRule(json.optInt("key"))
            for (g in 0 until GESTURES) rule.actions[g] = KeyAction.fromJson(json.optJSONObject(GESTURE_NAMES[g]))
            return rule
        }
    }
}

/** Remote key identifiers and names. */
object Keys {
    /** Keys without an Android code (KEYCODE_UNKNOWN) are told apart by scan code. */
    private const val SCAN_BASE = 100_000

    fun id(event: KeyEvent) =
        if (event.keyCode != KeyEvent.KEYCODE_UNKNOWN) event.keyCode else SCAN_BASE + event.scanCode

    fun name(context: Context, key: Int): String {
        if (key >= SCAN_BASE) return context.getString(R.string.key_scan_code, key - SCAN_BASE)
        NAMES[key]?.let { return context.getString(it) }
        val raw = KeyEvent.keyCodeToString(key)
        return if (raw.startsWith("KEYCODE_")) raw.removePrefix("KEYCODE_") else context.getString(R.string.key_code, key)
    }

    /**
     * What replaces a key's normal behavior once it is intercepted for a double press or hold.
     * Without root not every key can be emulated: OK, arrows and digits cannot.
     */
    fun defaultAction(key: Int, homeIntercept: Boolean): KeyAction? = when (key) {
        KeyEvent.KEYCODE_BACK -> KeyAction(KeyAction.BACK)
        KeyEvent.KEYCODE_HOME -> KeyAction(if (homeIntercept) KeyAction.HOME else KeyAction.SYSTEM_HOME)
        KeyEvent.KEYCODE_APP_SWITCH -> KeyAction(KeyAction.RECENTS)
        KeyEvent.KEYCODE_VOLUME_UP -> KeyAction(KeyAction.VOLUME_UP)
        KeyEvent.KEYCODE_VOLUME_DOWN -> KeyAction(KeyAction.VOLUME_DOWN)
        KeyEvent.KEYCODE_VOLUME_MUTE -> KeyAction(KeyAction.MUTE)
        in MEDIA_KEYS -> KeyAction(KeyAction.MEDIA_KEY, key.toString())
        else -> null
    }

    fun canEmulate(key: Int) = defaultAction(key, true) != null

    /** Keys the TV can't be controlled without: arrows, OK, Back. */
    fun isSystem(key: Int) = key in SYSTEM_KEYS

    private val SYSTEM_KEYS = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_BACK,
    )

    private val MEDIA_KEYS = setOf(
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_HEADSETHOOK,
    )

    private val NAMES = mapOf(
        KeyEvent.KEYCODE_HOME to R.string.key_home,
        KeyEvent.KEYCODE_BACK to R.string.key_back,
        KeyEvent.KEYCODE_DPAD_CENTER to R.string.key_ok,
        KeyEvent.KEYCODE_ENTER to R.string.key_ok,
        KeyEvent.KEYCODE_DPAD_UP to R.string.key_up,
        KeyEvent.KEYCODE_DPAD_DOWN to R.string.key_down,
        KeyEvent.KEYCODE_DPAD_LEFT to R.string.key_left,
        KeyEvent.KEYCODE_DPAD_RIGHT to R.string.key_right,
        KeyEvent.KEYCODE_MENU to R.string.key_menu,
        KeyEvent.KEYCODE_VOLUME_UP to R.string.key_volume_up,
        KeyEvent.KEYCODE_VOLUME_DOWN to R.string.key_volume_down,
        KeyEvent.KEYCODE_VOLUME_MUTE to R.string.key_mute,
        KeyEvent.KEYCODE_CHANNEL_UP to R.string.key_channel_up,
        KeyEvent.KEYCODE_CHANNEL_DOWN to R.string.key_channel_down,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to R.string.key_play_pause,
        KeyEvent.KEYCODE_SETTINGS to R.string.key_settings,
        KeyEvent.KEYCODE_TV_INPUT to R.string.key_input,
        KeyEvent.KEYCODE_SEARCH to R.string.key_search,
        KeyEvent.KEYCODE_VOICE_ASSIST to R.string.key_voice,
        KeyEvent.KEYCODE_ASSIST to R.string.key_voice,
        KeyEvent.KEYCODE_GUIDE to R.string.key_guide,
        KeyEvent.KEYCODE_INFO to R.string.key_info,
        KeyEvent.KEYCODE_PROG_RED to R.string.key_red,
        KeyEvent.KEYCODE_PROG_GREEN to R.string.key_green,
        KeyEvent.KEYCODE_PROG_YELLOW to R.string.key_yellow,
        KeyEvent.KEYCODE_PROG_BLUE to R.string.key_blue,
    )
}
