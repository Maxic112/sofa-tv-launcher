package dev.sofatv.launcher

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Windows over any app (TYPE_ACCESSIBILITY_OVERLAY, so no separate "display over other
 * apps" permission is needed): status panel, sleep timer picker, warning.
 */
class Overlays(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val app = App.of(context)
    private val handler = Handler(Looper.getMainLooper())
    private val res = context.resources

    private var panel: TextView? = null
    private val onStats: (Stats) -> Unit = { stats -> panel?.let { it.text = line(it, stats) } }

    /** The Sofa TV home screen already shows the same metrics in its line, and the panel would cover the buttons. */
    private var onHome = false

    /**
     * The current window with buttons (timer or warning) and its auto-close. Each window has its
     * own close task that closes only it: a shared task left over from a previous window
     * used to close the next one too early.
     */
    private var dialog: View? = null
    private var autoClose: Runnable? = null
    private var countdown: Runnable? = null

    /** The panel is shown temporarily ([peekPanel]) and hides by itself. */
    private var panelTemporary = false
    private val hideTemporaryPanel = Runnable { hidePanel() }

    /** Show / hide for good (the same as the "Over apps" setting). */
    fun togglePanel() = setPanelEnabled(!app.store.statusOverlay)

    /**
     * The overlay panel is on for good; this is saved in settings and
     * restored when the service starts. If the panel was shown temporarily, it stays.
     */
    fun setPanelEnabled(enabled: Boolean) {
        app.store.statusOverlay = enabled
        app.store.save()
        handler.removeCallbacks(hideTemporaryPanel)
        panelTemporary = false
        if (enabled) {
            if (panel == null) showPanel()
        } else {
            hidePanel()
        }
    }

    /** The service started: bring the panel back if it was on. */
    fun restorePanel() {
        if (app.store.statusOverlay && panel == null) showPanel()
    }

    /** Show for [PANEL_PEEK_MS]; calling again extends it. A panel that is on for good is left alone. */
    fun peekPanel() {
        if (panel != null && !panelTemporary) return
        if (panel == null) showPanel()
        panelTemporary = true
        handler.removeCallbacks(hideTemporaryPanel)
        handler.postDelayed(hideTemporaryPanel, PANEL_PEEK_MS)
    }

    private fun showPanel() {
        val view = TextView(context).apply {
            setTextColor(context.getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setBackgroundResource(R.drawable.overlay_bg)
            val pad = dp(12)
            setPadding(pad, dp(6), pad, dp(6))
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            text = line(this, app.stats.last)
            visibility = if (onHome) View.GONE else View.VISIBLE
        }
        // Full screen width with equal side margins: a window sized to its text
        // came out narrow and cut off some metrics.
        val params = params(focusable = false, gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            width = res.displayMetrics.widthPixels - 2 * dp(PANEL_MARGIN_DP)
            x = 0
        }
        wm.addView(view, params)
        panel = view
        app.stats.addListener(onStats)
    }

    fun setOnHome(home: Boolean) {
        onHome = home
        panel?.visibility = if (home) View.GONE else View.VISIBLE
    }

    private fun hidePanel() {
        handler.removeCallbacks(hideTemporaryPanel)
        panelTemporary = false
        val view = panel ?: return
        app.stats.removeListener(onStats)
        wm.removeView(view)
        panel = null
    }

    private fun line(view: TextView, stats: Stats?) =
        StatsFormat.line(view, stats, app.sleepTimer.remainingMs, app.store.statusItems)

    fun showSleepPicker() {
        val timer = app.sleepTimer
        val subtitle = if (timer.isRunning) {
            context.getString(R.string.sleep_running, minutesLeft(), clockAt(timer.remainingMs))
        } else {
            context.getString(R.string.sleep_not_set)
        }
        val root = dialogRoot(context.getString(R.string.sleep_title), subtitle, R.drawable.ic_bedtime)
        val row = buttonRow(root)
        val off = button(row, context.getString(R.string.sleep_off)) {
            timer.cancel()
            closeDialog()
            toast(context.getString(R.string.sleep_cancelled))
        }
        var focus: View = off
        for (seconds in SleepTimer.PRESETS) {
            val label = if (seconds < 60) context.getString(R.string.sleep_seconds, seconds)
            else context.getString(R.string.sleep_minutes, seconds / 60)
            val b = button(row, label) {
                timer.start(seconds)
                closeDialog()
                toast(context.getString(R.string.sleep_started, clockAt(timer.remainingMs)))
            }
            val wanted = if (timer.isRunning) timer.lastSeconds else DEFAULT_SECONDS
            if (seconds == wanted) focus = b
        }
        showDialog(root, focus)
    }

    fun showSleepWarning() {
        val timer = app.sleepTimer
        if (!timer.isRunning) return
        val root = dialogRoot(context.getString(R.string.sleep_warning, secondsLeft()), null, R.drawable.ic_warning)
        val title = root.getChildAt(0) as TextView
        val row = buttonRow(root)
        button(row, context.getString(R.string.sleep_extend, SleepTimer.EXTEND_MINUTES)) {
            timer.extend(SleepTimer.EXTEND_MINUTES)
            closeDialog()
            toast(context.getString(R.string.sleep_started, clockAt(timer.remainingMs)))
        }
        val disable = button(row, context.getString(R.string.sleep_disable)) {
            timer.cancel()
            closeDialog()
            toast(context.getString(R.string.sleep_cancelled))
        }
        button(row, context.getString(R.string.sleep_now)) {
            closeDialog()
            timer.cancel()
            KeyService.instance?.goToSleep()
        }
        // Focus goes to "Turn off timer" in the middle: the most common answer to "The TV will turn off".
        showDialog(root, disable, closeAfterMs = timer.remainingMs + 1000)
        val tick = object : Runnable {
            override fun run() {
                if (dialog !== root) return
                title.text = context.getString(R.string.sleep_warning, secondsLeft())
                handler.postDelayed(this, 1000)
            }
        }
        countdown = tick
        handler.postDelayed(tick, 1000)
    }

    fun closeDialog() {
        autoClose?.let(handler::removeCallbacks)
        autoClose = null
        countdown?.let(handler::removeCallbacks)
        countdown = null
        val view = dialog ?: return
        wm.removeView(view)
        dialog = null
    }

    /**
     * Full-screen progress screen that hides the settings flashing beneath it. Not focusable:
     * the settings window must stay active, since that's where the service presses buttons.
     */
    private var progress: LinearLayout? = null
    private var progressText: TextView? = null

    fun showProgress(title: String, text: String) {
        hideProgress()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(context.getColor(R.color.background))
            addView(text(title, 24f, R.color.text_primary).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
        val line = text(text, 16f, R.color.text_secondary).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(line)
        val params = params(focusable = false, gravity = Gravity.CENTER).apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            x = 0
            y = 0
        }
        wm.addView(root, params)
        progress = root
        progressText = line
    }

    fun updateProgress(text: String) {
        progressText?.text = text
    }

    fun hideProgress() {
        progress?.let(wm::removeView)
        progress = null
        progressText = null
    }

    fun release() {
        hidePanel()
        closeDialog()
        hideProgress()
    }

    private fun showDialog(root: View, focus: View, closeAfterMs: Long = DIALOG_TIMEOUT_MS) {
        closeDialog()
        // Android first measures a wrap-content window at regular dialog width (~880 px on a TV),
        // and the button row doesn't fit: the last button shrinks to zero. So we measure ourselves.
        root.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val maxWidth = res.displayMetrics.widthPixels - 2 * dp(PANEL_MARGIN_DP)
        // Focus is set before showing: then Android doesn't put it on the first button itself, and the very first frame
        // is drawn with the right button focused, without a visible jump.
        focus.requestFocus()
        wm.addView(root, params(focusable = true, gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            width = root.measuredWidth.coerceAtMost(maxWidth)
            x = 0
            y = dp(64)
        })
        dialog = root
        // Fallback in case the early focus didn't take.
        focus.post { if (!focus.isFocused) focus.requestFocus() }
        scheduleClose(root, closeAfterMs)
    }

    /** Close exactly [root] after [delayMs] if it is still on screen by then. */
    private fun scheduleClose(root: View, delayMs: Long) {
        autoClose?.let(handler::removeCallbacks)
        val task = Runnable { if (dialog === root) closeDialog() }
        autoClose = task
        handler.postDelayed(task, delayMs)
    }

    /** Root of a window with buttons: Back closes it, any key press postpones the auto-close. */
    private fun dialogRoot(title: String, subtitle: String?, icon: Int): LinearLayout {
        val root = object : LinearLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                // The window is already closed but its removal from WindowManager is deferred: late
                // key presses must neither close nor schedule closing of the next window.
                if (dialog !== this) return true
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) closeDialog()
                    return true
                }
                if (countdown == null) scheduleClose(this, DIALOG_TIMEOUT_MS)
                return super.dispatchKeyEvent(event)
            }
        }
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundResource(R.drawable.overlay_bg)
        val pad = dp(24)
        root.setPadding(pad, dp(20), pad, dp(20))
        // Icon to the left of the title, in the text color, sized to the title line.
        root.addView(text(title, 20f, R.color.text_primary).apply {
            val drawable = context.getDrawable(icon)?.mutate()?.apply {
                setTint(context.getColor(R.color.text_primary))
                setBounds(0, 0, dp(24), dp(24))
            }
            setCompoundDrawablesRelative(drawable, null, null, null)
            compoundDrawablePadding = dp(12)
        })
        subtitle?.let { root.addView(text(it, 15f, R.color.text_secondary)) }
        return root
    }

    private fun buttonRow(root: LinearLayout): LinearLayout {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(16) })
        return row
    }

    private fun button(row: LinearLayout, label: String, onClick: () -> Unit): TextView {
        val b = text(label, 16f, R.color.text_primary).apply {
            setBackgroundResource(R.drawable.button_bg)
            gravity = Gravity.CENTER
            val pad = dp(16)
            setPadding(pad, 0, pad, 0)
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
        }
        // Spacing only between buttons, so the window's side margins stay equal.
        row.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply {
            if (row.childCount > 0) marginStart = dp(8)
        })
        return b
    }

    private fun text(value: String, sp: Float, color: Int) = TextView(context).apply {
        text = value
        setTextColor(context.getColor(color))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    private fun params(focusable: Boolean, gravity: Int): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!focusable) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = gravity
            x = dp(24)
            y = dp(16)
        }
    }

    private fun minutesLeft() = ((app.sleepTimer.remainingMs + 59_999) / 60_000).toInt()

    private fun secondsLeft() = ((app.sleepTimer.remainingMs + 999) / 1000).toInt()

    private fun clockAt(inMs: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(System.currentTimeMillis() + inMs))

    private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int) = (value * res.displayMetrics.density).toInt()

    private companion object {
        const val DIALOG_TIMEOUT_MS = 15_000L
        const val PANEL_MARGIN_DP = 24
        const val PANEL_PEEK_MS = 5_000L
        const val DEFAULT_SECONDS = 30 * 60
    }
}
