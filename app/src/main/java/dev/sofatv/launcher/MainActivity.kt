package dev.sofatv.launcher

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : Activity(), HomeAdapter.Listener {
    private lateinit var app: App
    private lateinit var grid: RecyclerView
    private lateinit var layout: GridLayoutManager
    private lateinit var adapter: HomeAdapter
    private lateinit var content: ViewGroup
    private lateinit var panel: SettingsPanel
    private lateinit var pages: SettingsPages
    private lateinit var settingsButton: View
    private lateinit var sleepButton: TextView
    private lateinit var updateBox: View
    private val onUpdateChanged: () -> Unit = {
        updateUpdateButton()
        panel.refresh()
    }
    private val handler = Handler(Looper.getMainLooper())

    /** Package of the favorite currently being moved with the arrows. */
    private var movingPkg: String? = null
    private var focusedOnce = false

    /** "All apps" is expanded (when it collapses under a button). Home collapses it back. */
    private var allAppsExpanded = false

    /** Stable tile ids: "f:pkg" for favorites, "a:pkg" for the main grid. */
    private val ids = HashMap<String, Long>()

    private val onAppsChanged: () -> Unit = { rebuild() }

    private lateinit var status: TextView
    private val onStats: (Stats) -> Unit = { stats ->
        status.text = StatsFormat.line(status, stats, app.sleepTimer.remainingMs, app.store.statusItems)
    }
    private var statusListening = false

    /** Remaining time on the sleep timer button: updated on change and every few seconds. */
    private val onSleepChanged: () -> Unit = { updateSleepButton() }
    private val sleepTick = Runnable { updateSleepButton() }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Language.wrap(newBase, App.of(newBase).store.language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = App.of(this)
        // The app renders in software by default (manifest); GPU is enabled for this window
        // when chosen in settings. The flag only works if set before setContentView.
        if (app.store.hardwareAcceleration) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            )
        }
        setContentView(R.layout.activity_main)

        grid = findViewById(R.id.grid)
        status = findViewById(R.id.status)
        content = findViewById(R.id.content)
        adapter = HomeAdapter(app.tiles, this)
        setUpGrid()

        panel = SettingsPanel(this, findViewById<FrameLayout>(R.id.root))
        pages = SettingsPages(this, panel) { onHomeSettingsChanged() }
        panel.onVisibilityChanged = { open ->
            // While the panel is open, focus doesn't go to the tiles and buttons beneath it.
            content.descendantFocusability =
                if (open) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
            if (!open) settingsButton.requestFocus()
        }

        settingsButton = findViewById(R.id.settings)
        sleepButton = findViewById(R.id.sleep)
        val clean = findViewById<View>(R.id.clean)
        settingsButton.setOnClickListener { pages.openRoot() }
        clean.setOnClickListener { app.cleaner.clean(packageName) }
        sleepButton.setOnClickListener { showSleepTimer() }
        updateBox = findViewById(R.id.update_box)
        val update = findViewById<View>(R.id.update)
        update.setOnClickListener { pages.openUpdates(install = true) }
        update.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus -> if (hasFocus) layout.scrollToPosition(0) }
        app.updater.addListener(onUpdateChanged)
        updateUpdateButton()
        // Focus in the header: scroll the grid to the top so the first section title is visible.
        val toTop = View.OnFocusChangeListener { _, hasFocus -> if (hasFocus) layout.scrollToPosition(0) }
        settingsButton.onFocusChangeListener = toTop
        clean.onFocusChangeListener = toTop
        sleepButton.onFocusChangeListener = toTop

        app.apps.addListener(onAppsChanged)
        if (app.apps.loaded) rebuild()

        // Recreated to apply a setting: take the user back to the settings, at the same row.
        app.reopenSettings?.let { where ->
            app.reopenSettings = null
            pages.reopen(where)
        }
    }

    override fun onDestroy() {
        app.apps.removeListener(onAppsChanged)
        app.updater.removeListener(onUpdateChanged)
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        adapter.restoreBitmaps()
        applyStatusVisibility()
        app.sleepTimer.addListener(onSleepChanged)
        updateSleepButton()
    }

    override fun onResume() {
        super.onResume()
        // The overlay panel hides on the home screen, which has its own status line.
        app.homeVisible = true
        KeyService.instance?.setHomeVisible(true)
        // Back from Accessibility: the service state may have changed.
        panel.refresh()
        app.updater.checkIfDue()
    }

    override fun onPause() {
        super.onPause()
        app.homeVisible = false
        KeyService.instance?.setHomeVisible(false)
    }

    override fun onStop() {
        super.onStop()
        setStatusListening(false)
        app.sleepTimer.removeListener(onSleepChanged)
        handler.removeCallbacks(sleepTick)
        adapter.releaseBitmaps()
        app.tiles.release()
    }

    /** Status line on the home screen: shown and polled only when enabled in settings. */
    private fun applyStatusVisibility() {
        val visible = app.store.statusOnHome
        status.visibility = if (visible) View.VISIBLE else View.GONE
        setStatusListening(visible)
    }

    private fun setStatusListening(listen: Boolean) {
        if (listen == statusListening) return
        statusListening = listen
        if (listen) app.stats.addListener(onStats) else app.stats.removeListener(onStats)
    }

    /** From settings: hidden apps or the status line changed. */
    private fun onHomeSettingsChanged() {
        rebuild()
        applyStatusVisibility()
    }

    /** The "Update" button with its orange dot is shown only while a newer release is known. */
    private fun updateUpdateButton() {
        val show = app.updater.available != null
        if (!show && updateBox.hasFocus()) settingsButton.requestFocus()
        updateBox.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun updateSleepButton() {
        val timer = app.sleepTimer
        sleepButton.text = if (timer.isRunning) {
            StatsFormat.sleepLeft(this, timer.remainingMs)
        } else {
            getString(R.string.sleep_button)
        }
        handler.removeCallbacks(sleepTick)
        if (timer.isRunning) handler.postDelayed(sleepTick, SLEEP_TICK_MS)
    }

    /** The timer window is drawn by the key service, since that's what can put the TV to sleep. */
    private fun showSleepTimer() {
        val service = KeyService.instance
        if (service != null) service.showSleepPicker()
        else Toast.makeText(this, R.string.sleep_needs_service, Toast.LENGTH_LONG).show()
    }

    // A plain Activity without AndroidX has no Activity Result API, so classic onActivityResult it is.
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SettingsPages.REQUEST_SHORTCUT) pages.onShortcutResult(data.takeIf { resultCode == RESULT_OK })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home on the home screen goes back to the top, as in the stock launcher.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            if (panel.isOpen) panel.close()
            finishMove()
            if (allAppsExpanded) {
                allAppsExpanded = false
                rebuild()
            }
            layout.scrollToPosition(0)
            focusFirstTile()
        }
    }

    private fun setUpGrid() {
        val geometry = Grid.of(resources, app.store)
        val span = geometry.span
        val gap = geometry.gap
        val rows = resources.displayMetrics.heightPixels / (geometry.tileHeight + gap) + 1
        app.tiles.configure(geometry.tileWidth, geometry.tileHeight, span * rows)
        // Room at the top for the scaled-up focused tile, which isn't drawn beyond the grid bounds.
        grid.setPadding(geometry.side, gap / 2, geometry.side, gap * 2)

        layout = HomeLayoutManager(this, span)
        layout.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) =
                if (adapter.getItemViewType(position) == HomeAdapter.TYPE_TILE) 1 else span
        }
        grid.layoutManager = layout
        grid.adapter = adapter
        // No animations: changes apply to the same views, so focus isn't lost.
        grid.itemAnimator = null
        // The scaled-up focused tile is drawn over its neighbors.
        grid.setChildDrawingOrderCallback { count, i ->
            val focused = grid.indexOfChild(grid.focusedChild)
            when {
                focused < 0 -> i
                i == count - 1 -> focused
                i >= focused -> i + 1
                else -> i
            }
        }
    }

    private fun idOf(key: String) = ids.getOrPut(key) { ids.size.toLong() }

    private fun rebuild() {
        val store = app.store
        val apps = app.apps.apps
        val byPkg = apps.associateBy { it.pkg }
        val items = ArrayList<HomeItem>(apps.size + 2)

        val favorites = store.favorites.mapNotNull { byPkg[it] }
        if (favorites.isNotEmpty()) {
            items.add(HeaderItem(ID_HEADER_FAVORITES, getString(R.string.section_favorites)))
            favorites.forEach { items.add(AppItem(idOf("f:" + it.pkg), it, true, it.pkg == movingPkg)) }
        }
        val rest = apps.filter { !store.isFavorite(it.pkg) && it.pkg !in store.hidden }
        // With favorites, "All apps" can collapse under a button, as in Android TV:
        // the home screen shows only what's needed, and collapsed tiles aren't created at all.
        val collapsible = store.collapseAllApps && favorites.isNotEmpty()
        if (collapsible) {
            val title = if (allAppsExpanded) getString(R.string.all_apps_hide) else getString(R.string.all_apps_show, rest.size)
            items.add(ButtonItem(ID_ALL_APPS_BUTTON, title))
        } else {
            items.add(HeaderItem(ID_HEADER_ALL, getString(R.string.section_all)))
        }
        if (!collapsible || allAppsExpanded) {
            rest.forEach { items.add(AppItem(idOf("a:" + it.pkg), it, false, false)) }
        }
        adapter.submit(items)

        if (!focusedOnce) {
            focusedOnce = true
            focusFirstTile()
        }
    }

    private fun focusFirstTile() {
        val position = adapter.items.indexOfFirst { it is AppItem }
        if (position >= 0) focusPosition(position)
    }

    /** Focuses the tile at [position] (or the nearest tile before it). */
    private fun focusPosition(position: Int) {
        val items = adapter.items
        var target = position.coerceAtMost(items.size - 1)
        while (target >= 0 && items[target] !is AppItem) target--
        if (target < 0) target = items.indexOfFirst { it is AppItem }
        if (target < 0) return
        if (layout.findViewByPosition(target) == null) {
            // First tile of a section: scroll to its header so it doesn't go off screen.
            val header = target - 1
            layout.scrollToPosition(if (header >= 0 && items[header] is HeaderItem) header else target)
        }
        requestFocusAt(target, FOCUS_ATTEMPTS)
    }

    /** The view only exists after layout, so wait a few frames for it. */
    private fun requestFocusAt(position: Int, attempts: Int) {
        val view = layout.findViewByPosition(position)
        if (view != null && !grid.isLayoutRequested) {
            view.requestFocus()
        } else if (attempts > 0) {
            grid.postOnAnimation { requestFocusAt(position, attempts - 1) }
        }
    }

    /**
     * Rebuilds the grid after an action on the tile at [position] and focuses
     * the nearest remaining tile: the tile itself may have moved to another section.
     */
    private fun rebuildFocusingNear(position: Int) {
        val old = adapter.items
        val candidates = (old.drop(position + 1) + old.take(position).asReversed()).filterIsInstance<AppItem>()
        rebuild()
        val target = candidates.firstNotNullOfOrNull { item -> adapter.positionOf(item.id).takeIf { it >= 0 } }
        if (target != null) focusPosition(target) else focusFirstTile()
    }

    override fun onToggleAllApps() {
        allAppsExpanded = !allAppsExpanded
        // The same button (same id) stays in place and focused; only its label changes.
        rebuild()
    }

    override fun onLaunch(item: AppItem) {
        if (movingPkg != null) return
        start(item.app.launchIntent())
    }

    override fun onMenu(item: AppItem) {
        if (movingPkg != null) return
        val app = item.app
        val position = adapter.positionOf(item.id)
        val items = ArrayList<TileMenu.Item>()
        if (item.favorite) {
            items.add(TileMenu.Item(getString(R.string.menu_remove_favorite), R.drawable.ic_bookmark_remove) { toggleFavorite(app, position) })
            items.add(TileMenu.Item(getString(R.string.menu_move), R.drawable.ic_open_with) { startMove(app) })
        } else {
            items.add(TileMenu.Item(getString(R.string.menu_add_favorite), R.drawable.ic_bookmark_add) { toggleFavorite(app, position) })
        }
        items.add(TileMenu.Item(getString(R.string.menu_hide), R.drawable.ic_visibility_off) {
            this.app.store.hide(app.pkg)
            rebuildFocusingNear(position)
        })
        items.add(TileMenu.Item(getString(R.string.menu_info), R.drawable.ic_info) {
            start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.pkg, null)))
        })
        if (!app.isSystem) {
            items.add(TileMenu.Item(getString(R.string.menu_uninstall), R.drawable.ic_delete, danger = true) {
                start(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.pkg, null)))
            })
        }
        val titleIcon = try {
            app.info.loadIcon(packageManager)
        } catch (e: Exception) {
            null
        }
        TileMenu.show(this, app.label, titleIcon, items)
    }

    private fun toggleFavorite(entry: AppEntry, position: Int) {
        app.store.toggleFavorite(entry.pkg)
        rebuildFocusingNear(position)
    }

    private fun startMove(entry: AppEntry) {
        movingPkg = entry.pkg
        rebuild()
        Toast.makeText(this, R.string.move_hint, Toast.LENGTH_SHORT).show()
    }

    private fun moveBy(delta: Int) {
        val pkg = movingPkg ?: return
        if (!app.store.moveFavorite(pkg, delta)) return
        rebuild()
        val position = adapter.positionOf(idOf("f:$pkg"))
        if (position >= 0) focusPosition(position)
    }

    private fun finishMove() {
        if (movingPkg == null) return
        movingPkg = null
        app.store.save()
        rebuild()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        if (panel.isOpen) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> if (down && event.repeatCount == 0) panel.back()
                KeyEvent.KEYCODE_MENU -> Unit
                else -> return super.dispatchKeyEvent(event)
            }
            return true
        }
        if (movingPkg != null) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (down) moveBy(-1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) moveBy(1)
                KeyEvent.KEYCODE_DPAD_UP -> if (down) moveBy(-layout.spanCount)
                KeyEvent.KEYCODE_DPAD_DOWN -> if (down) moveBy(layout.spanCount)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK ->
                    if (down) finishMove()
                else -> return super.dispatchKeyEvent(event)
            }
            return true
        }
        when (event.keyCode) {
            // The home screen is the root: Back does nothing here.
            KeyEvent.KEYCODE_BACK -> return true
            KeyEvent.KEYCODE_MENU -> {
                if (down && event.repeatCount == 0) {
                    val focused = grid.focusedChild
                    val item = focused?.let { adapter.items.getOrNull(grid.getChildAdapterPosition(it)) }
                    if (item is AppItem) onMenu(item)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun start(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val ID_HEADER_FAVORITES = -1L
        const val ID_HEADER_ALL = -2L
        const val ID_ALL_APPS_BUTTON = -3L
        const val FOCUS_ATTEMPTS = 5
        const val SLEEP_TICK_MS = 5_000L
    }
}
