package dev.sofatv.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.provider.Settings
import android.view.KeyEvent
import android.widget.EditText
import android.widget.Toast
import dev.sofatv.launcher.SettingsPanel.Page
import dev.sofatv.launcher.SettingsPanel.Row
import dev.sofatv.launcher.SettingsPanel.Tone

/**
 * Settings pages in the side panel:
 *  - Remote keys: service, Home interception, remapping, timings, exclusions;
 *  - Status panel: on home, over apps, period, what to show;
 *  - Memory cleanup: method, exclusions;
 *  - App list: hidden apps, collapsing, grid (columns, tile height);
 *  - Performance: hardware acceleration, RAM used;
 *  - Language; Android settings; updates (version, check, install).
 */
class SettingsPages(
    private val activity: Activity,
    private val panel: SettingsPanel,
    /** Something visible on the home screen changed: hidden apps, the status line. */
    private val onHomeChanged: () -> Unit,
) {
    private val app = App.of(activity)
    private val store = app.store
    private val handler = Handler(Looper.getMainLooper())
    private var pendingShortcut: ((Intent?) -> Unit)? = null

    fun openRoot() = panel.open(root())

    /** What to open after the home screen is recreated to apply a setting. */
    enum class Reopen { ACCELERATION, GRID_COLUMNS, TILE_HEIGHT, LANGUAGE }

    fun reopen(where: Reopen) {
        val root = root()
        when (where) {
            Reopen.ACCELERATION -> {
                val from = root.indexOf(R.string.group_performance)
                panel.open(root, from)
                val performance = performanceGroup()
                panel.push(performance, performance.indexOf(R.string.hardware_acceleration), from)
                measureRam()
            }
            Reopen.GRID_COLUMNS, Reopen.TILE_HEIGHT -> {
                val from = root.indexOf(R.string.group_apps)
                panel.open(root, from)
                val apps = appsGroup()
                val row = if (where == Reopen.GRID_COLUMNS) R.string.grid_columns else R.string.tile_height
                panel.push(apps, apps.indexOf(row), from)
            }
            Reopen.LANGUAGE -> panel.open(root, root.indexOf(R.string.language))
        }
    }

    private fun Page.indexOf(title: Int) = rows().indexOfFirst { it.title == str(title) }.coerceAtLeast(0)

    fun onShortcutResult(data: Intent?) {
        pendingShortcut?.invoke(data)
        pendingShortcut = null
    }

    // ---- Root ----

    private fun root() = Page(str(R.string.settings)) {
        listOf(
            Row(str(R.string.group_keys), MORE, icon = R.drawable.ic_settings_remote) { panel.push(keysGroup()) },
            Row(str(R.string.group_status), MORE, icon = R.drawable.ic_monitoring) { panel.push(statusGroup()) },
            Row(str(R.string.group_clean), MORE, icon = R.drawable.ic_cleaning_services) { panel.push(cleanGroup()) },
            Row(str(R.string.group_apps), MORE, icon = R.drawable.ic_apps) { panel.push(appsGroup()) },
            Row(str(R.string.group_performance), MORE, icon = R.drawable.ic_speed) {
                panel.push(performanceGroup())
                measureRam()
            },
            Row(str(R.string.language), languageLabel(store.language), icon = R.drawable.ic_language) { panel.push(languagePage()) },
            Row(str(R.string.settings_system), MORE, icon = R.drawable.ic_tune) { start(Intent(Settings.ACTION_SETTINGS)) },
            Row(str(R.string.updates), MORE, icon = R.drawable.ic_update, badge = app.updater.available != null) {
                panel.push(updatesPage())
            },
        )
    }

    // ---- Updates ----

    /** From the header "Update" button: straight to this page, and start installing right away. */
    fun openUpdates(install: Boolean) {
        val root = root()
        val from = root.indexOf(R.string.updates)
        panel.open(root, from)
        val page = updatesPage()
        panel.push(page, page.indexOf(if (install) R.string.update_install else R.string.update_check), from)
        if (install) app.updater.install(activity)
    }

    private fun updatesPage() = Page(str(R.string.updates)) {
        val updater = app.updater
        val busy = updater.state == Updater.State.CHECKING
        buildList {
            add(Row(str(R.string.version), updater.currentVersion, icon = R.drawable.ic_info))
            updater.available?.let { release ->
                add(Row(str(R.string.update_available, release.version), badge = true, tone = Tone.WARNING))
                val progress = when (updater.state) {
                    Updater.State.DOWNLOADING -> str(R.string.update_downloading, updater.progress)
                    Updater.State.INSTALLING -> str(R.string.update_installing)
                    else -> updater.error
                }
                add(Row(str(R.string.update_install), progress, icon = R.drawable.ic_download) { updater.install(activity) })
            }
            val status = when {
                busy -> str(R.string.update_checking)
                updater.available == null && updater.error != null -> updater.error
                updater.state == Updater.State.UP_TO_DATE -> str(R.string.update_up_to_date)
                else -> null
            }
            add(Row(str(R.string.update_check), status, icon = R.drawable.ic_update) { updater.check() })
            val last = store.updateLastCheck
            add(
                Row(
                    if (last > 0) str(R.string.update_hint_last, lastCheckTime(last)) else str(R.string.update_hint),
                ),
            )
        }
    }

    private fun lastCheckTime(millis: Long): String =
        DateFormat.getMediumDateFormat(activity).format(millis) + ", " + DateFormat.getTimeFormat(activity).format(millis)

    // ---- Language ----

    private fun languagePage() = Page(str(R.string.language)) {
        Language.CHOICES.map { code ->
            val label = if (code == Language.SYSTEM) str(R.string.language_system) else languageLabel(code)
            Row(label, mark(store.language == code)) { applyLanguage(code) }
        }
    }

    /** "System (English)" for the system choice, otherwise the language's own name. */
    private fun languageLabel(code: String) = when (code) {
        "en" -> str(R.string.language_en)
        "ru" -> str(R.string.language_ru)
        else -> {
            val system = Language.systemLocale()
            str(R.string.language_system_value, system.getDisplayLanguage(system).replaceFirstChar { it.titlecase(system) })
        }
    }

    /** Every screen caches its texts, so the home screen is recreated and the settings reopened. */
    private fun applyLanguage(code: String) {
        if (code == store.language) return panel.back()
        store.language = code
        store.save()
        Language.applyToApp(app, code)
        app.reopenSettings = Reopen.LANGUAGE
        activity.recreate()
    }

    // ---- Performance ----

    private fun performanceGroup() = Page(str(R.string.group_performance)) {
        listOf(
            Row(str(R.string.hardware_acceleration), icon = R.drawable.ic_bolt, checked = store.hardwareAcceleration) {
                toggleHardwareAcceleration()
            },
            Row(str(R.string.hardware_acceleration_hint)),
            Row(str(R.string.ram_used), ramValue(app.memory.latest?.total), icon = R.drawable.ic_memory) {
                panel.push(ramPage())
                measureRam()
            },
        )
    }

    // ---- RAM used ----

    /** Measured in the background; the page rows are rebuilt with fresh values. */
    private fun measureRam() = app.memory.measure { panel.refresh() }

    /** "Refresh" is the only action row: without it, nothing on the page could take focus. */
    private fun ramPage() = Page(str(R.string.ram_used)) {
        val ram = app.memory.latest
        listOf(
            Row(str(R.string.ram_total), ramValue(ram?.total), icon = R.drawable.ic_memory),
            Row(str(R.string.ram_graphics), ramValue(ram?.graphics)),
            Row(str(R.string.ram_native), ramValue(ram?.native)),
            Row(str(R.string.ram_java), ramValue(ram?.java)),
            Row(str(R.string.ram_code), ramValue(ram?.code)),
            Row(str(R.string.ram_other), ramValue(ram?.other)),
            Row(str(R.string.ram_hint)),
            Row(str(R.string.ram_refresh)) { measureRam() },
        )
    }

    /** KB → "57 MB"; an ellipsis until measured (so the row keeps its shape on refresh). */
    private fun ramValue(kb: Int?) = if (kb == null) RAM_PENDING else str(R.string.ram_value, (kb + 512) / 1024)

    /**
     * Acceleration is set on the window at creation, so the home screen is recreated. Turning it off
     * frees the GPU buffers at once, but the GL driver only when the process restarts.
     */
    private fun toggleHardwareAcceleration() {
        store.hardwareAcceleration = !store.hardwareAcceleration
        store.save()
        app.reopenSettings = Reopen.ACCELERATION
        activity.recreate()
    }

    // ---- Remote keys ----

    /** While the service is off, an orange note sits at the top: nothing in this section works without it. */
    private fun keysGroup() = Page(str(R.string.group_keys)) {
        val serviceOff = KeyService.instance == null
        listOfNotNull(
            if (serviceOff) Row(str(R.string.service_off_warning), icon = R.drawable.ic_warning, tone = Tone.WARNING) else null,
            Row(str(R.string.service_toggle), icon = R.drawable.ic_accessibility_new, checked = !serviceOff) {
                toggleService()
            },
            Row(str(R.string.home_intercept), icon = R.drawable.ic_home, checked = store.homeIntercept) {
                store.homeIntercept = !store.homeIntercept
                store.save()
                panel.refresh()
            },
            Row(
                str(R.string.keys_title),
                if (store.remapEnabled) count(store.keys.values.count { !it.isEmpty }) else str(R.string.remap_off),
                icon = R.drawable.ic_gamepad,
            ) { panel.push(keysPage()) },
            Row(str(R.string.timings_title), str(R.string.timings_value, store.doubleTapMs, store.longPressMs), icon = R.drawable.ic_timer) {
                panel.push(timingsPage())
            },
            Row(str(R.string.excluded_short), count(store.remapExcluded.size), icon = R.drawable.ic_block) {
                panel.push(appChecklist(str(R.string.excluded_short), str(R.string.excluded_hint), store.remapExcluded))
            },
        )
    }

    /**
     * The service can turn itself off. The app can't turn it on; only the user can,
     * in Accessibility: we open that screen, or explain what to do if the TV has none.
     */
    private fun toggleService() {
        val service = KeyService.instance
        if (service != null) {
            service.turnOff()
            handler.postDelayed({ panel.refresh() }, SERVICE_REFRESH_MS)
            return
        }
        // On Google TV the standard address leads to a stub; SettingsScreens picks a working one.
        val intent = SettingsScreens.intent(activity, Settings.ACTION_ACCESSIBILITY_SETTINGS)
        if (intent != null) {
            Toast.makeText(activity, R.string.service_enable_hint, Toast.LENGTH_LONG).show()
            start(intent)
        } else {
            // Xiaomi on Android 9 and others: the section exists only inside the main settings screen, with no
            // address of its own, so show the path and open the main settings.
            AlertDialog.Builder(activity)
                .setTitle(R.string.service_off_title)
                .setMessage(R.string.service_enable_manual)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    start(SettingsScreens.intent(activity, Settings.ACTION_SETTINGS) ?: Intent(Settings.ACTION_SETTINGS))
                }
                .setNegativeButton(R.string.close, null)
                .show()
        }
    }

    private fun keysPage(): Page = Page(str(R.string.keys_title)) {
        listOf(
            Row(str(R.string.remap_enabled), icon = R.drawable.ic_gamepad, checked = store.remapEnabled) {
                store.remapEnabled = !store.remapEnabled
                store.save()
                panel.refresh()
            },
            Row(str(R.string.remap_system_warning), icon = R.drawable.ic_warning, tone = Tone.WARNING),
        ) + store.keys.values.map { rule ->
            val assigned = (0 until KeyRule.GESTURES).count { rule.has(it) }
            Row(Keys.name(activity, rule.key), if (assigned == 0) str(R.string.keys_not_set) else str(R.string.keys_assigned, assigned)) {
                panel.push(keyPage(rule.key))
            }
        } + Row(str(R.string.keys_add)) { capture() }
    }

    private fun keyPage(key: Int): Page {
        val rule = store.keys.getOrPut(key) { KeyRule(key) }
        return Page(Keys.name(activity, key)) {
            val warning = if (Keys.isSystem(key)) {
                listOf(Row(str(R.string.remap_system_key_warning), icon = R.drawable.ic_warning, tone = Tone.WARNING))
            } else {
                emptyList()
            }
            warning + (0 until KeyRule.GESTURES).map { g ->
                Row(str(KeyRule.GESTURE_LABELS[g]), KeyAction.label(activity, rule.actions[g]), icon = KeyRule.GESTURE_ICONS[g]) {
                    panel.push(actionPicker(rule, g))
                }
            } + Row(str(R.string.key_delete), icon = R.drawable.ic_delete, tone = Tone.DANGER) {
                store.keys.remove(key)
                store.save()
                panel.back()
            }
        }
    }

    private fun actionPicker(rule: KeyRule, gesture: Int): Page {
        /** Assign and go back to the key's page ([levels] pages up). */
        fun assign(action: KeyAction?, levels: Int) {
            rule.actions[gesture] = action
            store.save()
            warnIfSingleLost(rule)
            repeat(levels) { panel.back() }
        }
        val current = rule.actions[gesture]
        return Page(str(KeyRule.GESTURE_LABELS[gesture])) {
            buildList {
                add(Row(str(R.string.action_default), mark(current == null), icon = R.drawable.ic_settings_backup_restore) {
                    assign(null, 1)
                })
                KeyAction.SIMPLE.forEach { simple ->
                    add(Row(str(simple.label), mark(current?.type == simple.type), icon = simple.icon) {
                        assign(KeyAction(simple.type), 1)
                    })
                }
                add(Row(str(R.string.action_pick_app), MORE, icon = R.drawable.ic_apps) {
                    panel.push(Page(str(R.string.action_pick_app)) {
                        app.apps.apps.map { entry ->
                            Row(entry.label, mark(current?.type == KeyAction.APP && current.arg == entry.pkg), appIcon = entry) {
                                assign(KeyAction(KeyAction.APP, entry.pkg), 2)
                            }
                        }
                    })
                })
                add(Row(str(R.string.action_pick_settings), MORE, icon = R.drawable.ic_settings) {
                    panel.push(Page(str(R.string.action_pick_settings)) {
                        KeyAction.SETTINGS_SCREENS.map { (action, label) ->
                            Row(str(label), mark(current?.type == KeyAction.SETTINGS && current.arg == action)) {
                                assign(KeyAction(KeyAction.SETTINGS, action), 2)
                            }
                        }
                    })
                })
                add(Row(str(R.string.action_pick_shortcut), MORE, icon = R.drawable.ic_shortcut) { pickShortcut { assign(it, 2) } })
                add(Row(str(R.string.action_pick_intent), MORE, icon = R.drawable.ic_link) { inputIntent { assign(it, 1) } })
            }
        }
    }

    /** The key had to be intercepted entirely, and its normal press can't be replayed without root. */
    private fun warnIfSingleLost(rule: KeyRule) {
        val intercepted = rule.has(KeyRule.DOUBLE) || rule.has(KeyRule.LONG)
        if (intercepted && !rule.has(KeyRule.SINGLE) && !Keys.canEmulate(rule.key)) {
            Toast.makeText(activity, R.string.key_single_lost, Toast.LENGTH_LONG).show()
        }
    }

    /** Classic shortcuts (ACTION_CREATE_SHORTCUT): the app itself offers what to open. */
    private fun pickShortcut(onPicked: (KeyAction) -> Unit) {
        val pm = activity.packageManager
        val sources = pm.queryIntentActivities(Intent(Intent.ACTION_CREATE_SHORTCUT), 0)
        if (sources.isEmpty()) {
            Toast.makeText(activity, R.string.shortcut_none, Toast.LENGTH_SHORT).show()
            return
        }
        panel.push(Page(str(R.string.action_pick_shortcut)) {
            sources.map { ri ->
                Row(ri.loadLabel(pm).toString(), MORE) {
                    pendingShortcut = { data ->
                        @Suppress("DEPRECATION")
                        val intent = data?.getParcelableExtra<Intent>(Intent.EXTRA_SHORTCUT_INTENT)
                        if (intent != null) {
                            val name = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME)
                            onPicked(KeyAction(KeyAction.INTENT, intent.toUri(Intent.URI_INTENT_SCHEME), name))
                        } else {
                            Toast.makeText(activity, R.string.shortcut_unsupported, Toast.LENGTH_SHORT).show()
                        }
                    }
                    val request = Intent(Intent.ACTION_CREATE_SHORTCUT)
                        .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                    try {
                        @Suppress("DEPRECATION")
                        activity.startActivityForResult(request, REQUEST_SHORTCUT)
                    } catch (e: ActivityNotFoundException) {
                        pendingShortcut = null
                        Toast.makeText(activity, R.string.cannot_open, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    private fun inputIntent(onPicked: (KeyAction) -> Unit) {
        val input = EditText(activity).apply {
            hint = str(R.string.intent_hint)
            isSingleLine = true
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.action_pick_intent)
            .setView(input)
            .setPositiveButton(R.string.done) { _, _ ->
                val text = input.text.toString().trim()
                if (KeyAction.parseIntent(text) != null) onPicked(KeyAction(KeyAction.INTENT, text))
                else Toast.makeText(activity, R.string.intent_invalid, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * "Press a key": with the service on, it catches the press (so Home and other keys
     * that never reach the app are visible); otherwise the dialog itself does.
     */
    private fun capture() {
        var seconds = CAPTURE_SECONDS
        val serviceRunning = KeyService.instance != null
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.capture_title)
            .setMessage(captureMessage(seconds, serviceRunning))
            .create()
        var captured = false
        val onCaptured = { key: Int ->
            captured = true
            dialog.dismiss()
            panel.push(keyPage(key))
        }
        val tick = object : Runnable {
            override fun run() {
                seconds--
                if (seconds <= 0) {
                    dialog.cancel()
                } else {
                    dialog.setMessage(captureMessage(seconds, serviceRunning))
                    handler.postDelayed(this, 1000)
                }
            }
        }
        if (serviceRunning) {
            KeyService.capture = onCaptured
        } else {
            // Catch it on release so it doesn't reach the panel as an OK press.
            var downKey = Int.MIN_VALUE
            dialog.setOnKeyListener { _, _, event ->
                val key = Keys.id(event)
                when {
                    event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 -> downKey = key
                    event.action == KeyEvent.ACTION_UP && key == downKey && !captured -> onCaptured(key)
                }
                true
            }
        }
        dialog.setOnDismissListener {
            KeyService.capture = null
            handler.removeCallbacks(tick)
        }
        dialog.show()
        handler.postDelayed(tick, 1000)
    }

    private fun captureMessage(seconds: Int, serviceRunning: Boolean): String {
        val text = str(R.string.capture_message, seconds)
        return if (serviceRunning) text else text + "\n\n" + str(R.string.capture_no_service)
    }

    private fun timingsPage() = Page(str(R.string.timings_title)) {
        listOf(
            Row(str(R.string.gesture_double), str(R.string.ms, store.doubleTapMs)) {
                panel.push(optionsPage(str(R.string.gesture_double), DOUBLE_TAP_OPTIONS.map { it to str(R.string.ms, it) }, store.doubleTapMs) {
                    store.doubleTapMs = it
                })
            },
            Row(str(R.string.gesture_long), str(R.string.ms, store.longPressMs)) {
                panel.push(optionsPage(str(R.string.gesture_long), LONG_PRESS_OPTIONS.map { it to str(R.string.ms, it) }, store.longPressMs) {
                    store.longPressMs = it
                })
            },
        )
    }

    // ---- App list ----

    private fun appsGroup() = Page(str(R.string.group_apps)) {
        listOf(
            Row(str(R.string.group_hidden), count(store.hidden.size), icon = R.drawable.ic_visibility_off) { panel.push(hiddenPage()) },
            Row(str(R.string.collapse_all_apps), icon = R.drawable.ic_unfold_less, checked = store.collapseAllApps) {
                store.collapseAllApps = !store.collapseAllApps
                store.save()
                onHomeChanged()
                panel.refresh()
            },
            Row(str(R.string.collapse_all_apps_hint)),
            Row(str(R.string.grid_columns), columnsLabel(store.gridColumns), icon = R.drawable.ic_view_column) {
                panel.push(columnsPage())
            },
            Row(str(R.string.tile_height), heightLabel(store.tileHeightPercent), icon = R.drawable.ic_height) {
                panel.push(heightPage())
            },
        )
    }

    // ---- Grid: columns and tile height ----

    private fun columnsPage() = Page(str(R.string.grid_columns)) {
        Grid.COLUMNS.map { columns ->
            Row(columnsLabel(columns), mark(store.gridColumns == columns)) {
                applyGrid(Reopen.GRID_COLUMNS) { store.gridColumns = columns }
            }
        }
    }

    private fun heightPage() = Page(str(R.string.tile_height)) {
        Grid.HEIGHT_PERCENTS.map { percent ->
            Row(heightLabel(percent), mark(store.tileHeightPercent == percent)) {
                applyGrid(Reopen.TILE_HEIGHT) { store.tileHeightPercent = percent }
            }
        }
    }

    /** Tile size is set when the grid is created, so the home screen is recreated and we come back here. */
    private fun applyGrid(where: Reopen, change: () -> Unit) {
        val before = store.gridColumns to store.tileHeightPercent
        change()
        if (store.gridColumns to store.tileHeightPercent == before) return panel.back()
        store.save()
        app.reopenSettings = where
        activity.recreate()
    }

    /** "Auto (5)" shows how many columns fit this screen; otherwise the number. */
    private fun columnsLabel(columns: Int) =
        if (columns == 0) str(R.string.grid_columns_auto, Grid.of(activity.resources, store).autoSpan) else columns.toString()

    private fun heightLabel(percent: Int) =
        if (percent == 100) str(R.string.tile_height_default) else str(R.string.tile_height_percent, percent)

    // ---- Status panel ----

    private fun statusGroup() = Page(str(R.string.group_status)) {
        listOf(
            Row(str(R.string.status_home), icon = R.drawable.ic_home, checked = store.statusOnHome) {
                store.statusOnHome = !store.statusOnHome
                store.save()
                onHomeChanged()
                panel.refresh()
            },
            Row(str(R.string.status_overlay), icon = R.drawable.ic_layers, checked = store.statusOverlay) { toggleOverlay() },
            Row(str(R.string.status_interval), str(R.string.seconds, store.statsIntervalMs / 1000), icon = R.drawable.ic_update) {
                panel.push(optionsPage(str(R.string.status_interval), INTERVAL_OPTIONS.map { it to str(R.string.seconds, it / 1000) }, store.statsIntervalMs) {
                    store.statsIntervalMs = it
                })
            },
            Row(str(R.string.status_items), str(R.string.status_items_value, store.statusItems.size, StatsFormat.ITEMS.size), icon = R.drawable.ic_checklist) {
                panel.push(statusItemsPage())
            },
        )
    }

    /** The overlay panel is drawn by the service; without it the setting is saved and applies later. */
    private fun toggleOverlay() {
        val enabled = !store.statusOverlay
        val service = KeyService.instance
        if (service != null) {
            service.setPanelEnabled(enabled)
        } else {
            store.statusOverlay = enabled
            store.save()
            if (enabled) Toast.makeText(activity, R.string.status_overlay_needs_service, Toast.LENGTH_LONG).show()
        }
        panel.refresh()
    }

    /** Same icons as the metrics in the panel itself; CPU gets a note about firmware data. */
    private fun statusItemsPage() = Page(str(R.string.status_items)) {
        StatsFormat.ITEMS.flatMap { (key, label) ->
            val row = Row(str(label), icon = StatsFormat.ICONS[key], checked = key in store.statusItems) {
                if (!store.statusItems.remove(key)) store.statusItems.add(key)
                store.save()
                app.stats.redeliver()
                panel.refresh()
            }
            if (key == StatsFormat.CPU) listOf(row, Row(str(R.string.item_cpu_hint))) else listOf(row)
        }
    }

    // ---- Memory cleanup ----

    private fun cleanGroup() = Page(str(R.string.group_clean)) {
        listOf(
            Row(str(R.string.clean_method_title), str(cleanMethodShortLabel(app.cleaner.method)), icon = R.drawable.ic_cleaning_services) { panel.push(cleanMethodPage()) },
            Row(str(R.string.clean_whitelist_short), count(store.cleanWhitelist.size), icon = R.drawable.ic_shield) {
                panel.push(appChecklist(str(R.string.clean_whitelist_short), str(R.string.clean_whitelist_hint), store.cleanWhitelist))
            },
        )
    }

    /** Safety Guard is offered only where it exists (TCL). */
    private fun cleanMethodPage(): Page {
        val methods = buildList {
            add(MemoryCleaner.METHOD_STANDARD)
            if (app.cleaner.safetyGuardAvailable) add(MemoryCleaner.METHOD_SAFETY_GUARD)
            add(MemoryCleaner.METHOD_FORCE_STOP)
        }
        return Page(str(R.string.clean_method_title)) {
            methods.map { method ->
                Row(str(cleanMethodLabel(method)), mark(app.cleaner.method == method)) {
                    store.cleanMethod = method
                    store.save()
                    panel.back()
                }
            }
        }
    }

    /** Short label, shown as the value in the group row. */
    private fun cleanMethodShortLabel(method: String) = when (method) {
        MemoryCleaner.METHOD_SAFETY_GUARD -> R.string.clean_method_safety_guard_short
        MemoryCleaner.METHOD_FORCE_STOP -> R.string.clean_method_force_stop_short
        else -> R.string.clean_method_standard_short
    }

    private fun cleanMethodLabel(method: String) = when (method) {
        MemoryCleaner.METHOD_SAFETY_GUARD -> R.string.clean_method_safety_guard
        MemoryCleaner.METHOD_FORCE_STOP -> R.string.clean_method_force_stop
        else -> R.string.clean_method_standard
    }

    // ---- Hidden apps ----

    private fun hiddenPage() = Page(str(R.string.group_hidden)) {
        if (store.hidden.isEmpty()) return@Page listOf(Row(str(R.string.hidden_empty)))
        store.hidden.toList().map { pkg ->
            val entry = app.apps.find(pkg)
            Row(entry?.label ?: pkg, str(R.string.hidden_restore), appIcon = entry) {
                store.unhide(listOf(pkg))
                onHomeChanged()
                panel.refresh()
            }
        }
    }

    // ---- Shared pages ----

    /** Single choice: the current value is checked; choosing goes back. */
    private fun optionsPage(title: String, options: List<Pair<Int, String>>, current: Int, set: (Int) -> Unit) =
        Page(title) {
            options.map { (value, label) ->
                Row(label, mark(value == current)) {
                    set(value)
                    store.save()
                    panel.back()
                }
            }
        }

    /** App list with checkmarks; OK toggles the mark and saves right away. */
    /**
     * App list with switches. Checked apps come first, but the order is fixed
     * on entering the page, so rows don't jump while the user toggles them.
     */
    private fun appChecklist(title: String, hint: String, set: MutableSet<String>): Page {
        val selectedOnEnter = set.toSet()
        val ordered = app.apps.apps.sortedBy { it.pkg !in selectedOnEnter }
        return Page(title) {
            listOf(Row(hint)) + ordered.map { entry ->
                Row(entry.label, appIcon = entry, checked = entry.pkg in set) {
                    if (!set.remove(entry.pkg)) set.add(entry.pkg)
                    store.save()
                    panel.refresh()
                }
            }
        }
    }

    private fun start(intent: Intent) {
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.cannot_open, Toast.LENGTH_SHORT).show()
        }
    }

    private fun count(n: Int) = if (n == 0) MORE else "$n  $MORE"

    private fun mark(selected: Boolean) = if (selected) CHECK else ""

    private fun str(id: Int, vararg args: Any): String = activity.getString(id, *args)

    companion object {
        const val REQUEST_SHORTCUT = 1
        private const val MORE = "›"
        private const val CHECK = "✓"
        private const val RAM_PENDING = "…"
        private const val CAPTURE_SECONDS = 10
        private const val SERVICE_REFRESH_MS = 500L
        private val DOUBLE_TAP_OPTIONS = listOf(200, 250, 300, 350, 400, 500)
        private val LONG_PRESS_OPTIONS = listOf(400, 500, 600, 800, 1000)
        private val INTERVAL_OPTIONS = listOf(1000, 2000, 5000)
    }
}
