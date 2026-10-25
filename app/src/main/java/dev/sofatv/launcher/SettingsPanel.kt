package dev.sofatv.launcher

import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * Settings side panel on the right, as in Android TV settings: a page is a list of rows
 * "icon, title, value / switch"; OK runs a row, Back goes to the page
 * above or closes the panel. Page rows are rebuilt every time the page is shown, so
 * values are always current; [refresh] updates them in place so switches animate smoothly.
 */
class SettingsPanel(private val context: Context, private val container: FrameLayout) {

    /** Row color: normal, warning (orange), dangerous action (red). */
    enum class Tone { NORMAL, WARNING, DANGER }

    /**
     * [value] goes on the right: "2 s", "›", "✓" (a long one goes on a second line under the title);
     * [checked] != null makes a toggle row with a switch on the right; [icon] is the icon on the left,
     * [appIcon] replaces it with an app icon; [badge] adds an orange dot after the title;
     * [onClick] == null makes the row read-only.
     */
    class Row(
        val title: String,
        val value: String? = null,
        val icon: Int? = null,
        val checked: Boolean? = null,
        val tone: Tone = Tone.NORMAL,
        val appIcon: AppEntry? = null,
        val badge: Boolean = false,
        val onClick: (() -> Unit)? = null,
    )

    class Page(val title: String, val rows: () -> List<Row>)

    /** A rendered row: its view and the parts [refresh] updates in place. */
    private inner class RowView(var row: Row, val view: LinearLayout) {
        val icon = ImageView(context)
        val title = TextView(context)
        val value = TextView(context)
        val toggle = Switch(context)
        val badge = View(context).apply { setBackgroundResource(R.drawable.update_dot) }
        val inlineValue = isInline(row)

        /** Can be updated in place if the new row has the same shape. */
        fun fits(other: Row) = (other.icon != null) == (row.icon != null) &&
            (other.appIcon != null) == (row.appIcon != null) &&
            other.tone == row.tone &&
            (other.checked != null) == (row.checked != null) &&
            (other.onClick != null) == (row.onClick != null) &&
            isInline(other) == inlineValue &&
            other.value.isNullOrEmpty() == row.value.isNullOrEmpty()

        fun bind(newRow: Row) {
            row = newRow
            newRow.icon?.let(icon::setImageResource)
            newRow.appIcon?.let { app ->
                // Another app's icon loads in the background; the row may have changed by then.
                if (icon.tag != app.tileKey) icon.setImageDrawable(null)
                icon.tag = app.tileKey
                tiles.loadIcon(app, dp(ICON_DP)) { bitmap -> if (icon.tag == app.tileKey) icon.setImageBitmap(bitmap) }
            }
            title.text = newRow.title
            badge.visibility = if (newRow.badge) View.VISIBLE else View.GONE
            value.text = newRow.value.orEmpty()
            // setChecked on a visible switch animates the thumb.
            newRow.checked?.let { if (toggle.isChecked != it) toggle.isChecked = it }
        }
    }

    private val res = context.resources
    private val tiles = App.of(context).tiles
    private val stack = ArrayList<Page>()
    private val rowViews = ArrayList<RowView>()

    private val titleView = TextView(context).apply {
        setTextColor(context.getColor(R.color.text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        setPadding(dp(32), dp(32), dp(32), dp(16))
    }
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = NativeFocusScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        setPadding(dp(16), 0, dp(16), dp(24))
        addView(list)
    }
    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(context.getColor(R.color.panel))
        addView(titleView)
        addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        visibility = View.GONE
    }

    /** Called on open and close: the home screen blocks focus beneath the panel. */
    var onVisibilityChanged: ((Boolean) -> Unit)? = null

    val isOpen: Boolean
        get() = panel.visibility == View.VISIBLE

    private val panelWidth = (res.displayMetrics.widthPixels * WIDTH_FRACTION).toInt().coerceAtLeast(dp(MIN_WIDTH_DP))

    init {
        container.addView(panel, FrameLayout.LayoutParams(panelWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
    }

    fun open(page: Page, focusIndex: Int = 0) {
        stack.clear()
        stack.add(page)
        panel.visibility = View.VISIBLE
        onVisibilityChanged?.invoke(true)
        render(focusIndex)
    }

    /** [from] is the row of the current page Back returns to when navigating other than by clicking it. */
    fun push(page: Page, focusIndex: Int = 0, from: Int? = null) {
        from?.let(returnFocus::add)
        stack.add(page)
        render(focusIndex)
    }

    /** Back: to the page above (focus on the row we came from) or close the panel. */
    fun back() {
        if (stack.size <= 1) return close()
        stack.removeAt(stack.size - 1)
        render(focusIndex = returnFocus.removeLastOrNull() ?: 0)
    }

    fun close() {
        stack.clear()
        returnFocus.clear()
        panel.visibility = View.GONE
        list.removeAllViews()
        rowViews.clear()
        onVisibilityChanged?.invoke(false)
    }

    /**
     * Refresh the current page. If the set of rows is the same, values change in place:
     * focus is kept and switches animate smoothly; otherwise the page is rebuilt.
     */
    fun refresh() {
        if (!isOpen) return
        val page = stack.lastOrNull() ?: return
        val rows = page.rows()
        if (rows.size == rowViews.size && rows.indices.all { rowViews[it].fits(rows[it]) }) {
            rows.forEachIndexed { i, row -> rowViews[i].bind(row) }
            return
        }
        val focused = rowViews.indexOfFirst { it.view.hasFocus() }
        render(focusIndex = focused.coerceAtLeast(0))
    }

    /** Rows we went deeper from, so Back can return focus to them. */
    private val returnFocus = ArrayList<Int>()

    private fun render(focusIndex: Int) {
        val page = stack.lastOrNull() ?: return
        titleView.text = page.title
        list.removeAllViews()
        rowViews.clear()
        val depth = stack.size
        page.rows().forEachIndexed { index, row ->
            val rowView = createRow(row)
            // Only action rows get a listener: with one, a view on Android 8+ becomes
            // focusable by itself, and the remote would stop on hints and warnings.
            if (row.onClick != null) {
                rowView.view.setOnClickListener {
                    // The row opened a deeper page: remember where to return focus.
                    val before = stack.size
                    rowView.row.onClick?.invoke()
                    if (stack.size > before && stack.size > depth) returnFocus.add(index)
                }
            }
            list.addView(rowView.view)
            rowViews.add(rowView)
        }
        val focusable = rowViews.map { it.view }.filter { it.isFocusable }
        // ScrollView only scrolls to the focused row, and hints and warnings
        // above the first row can't take focus, so on the way up we reveal them by scrolling to the top.
        focusable.firstOrNull()?.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) scroll.post { scroll.smoothScrollTo(0, 0) }
        }
        val target = rowViews.getOrNull(focusIndex)?.view?.takeIf { it.isFocusable } ?: focusable.firstOrNull()
        // Right away rather than after a frame, otherwise focus visibly jumps from the first row to the right one.
        // ScrollView scrolls to the row by itself after layout.
        target?.requestFocus()
        target?.post { if (!target.isFocused) target.requestFocus() }
        if (focusIndex == 0) scroll.scrollTo(0, 0)
    }

    /** A short value ("2 s", "✓") goes on the right; a long one on a second line under the title. */
    private fun isInline(row: Row) = row.value.orEmpty().length <= INLINE_VALUE_MAX

    private fun createRow(row: Row): RowView {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val holder = RowView(row, container)
        val clickable = row.onClick != null
        val color = context.getColor(
            when {
                row.tone == Tone.WARNING -> R.color.warning
                row.tone == Tone.DANGER -> R.color.danger
                clickable -> R.color.text_primary
                else -> R.color.text_secondary
            },
        )

        if (row.icon != null || row.appIcon != null) {
            // App icons aren't tinted since they're colored; Material icons take the row color.
            if (row.icon != null) holder.icon.setColorFilter(color)
            container.addView(holder.icon, LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)).apply { marginEnd = dp(16) })
        }

        holder.title.apply {
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (row.tone == Tone.WARNING && !clickable) 14f else 16f)
        }
        holder.value.apply {
            setTextColor(context.getColor(R.color.text_secondary))
            if (holder.inlineValue) {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(dp(16), 0, 0, 0)
                isSingleLine = true
            } else {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(2), 0, 0)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
        }
        val hasValue = !row.value.isNullOrEmpty()
        // The title and its badge dot: the dot follows the text rather than sitting at the row's edge.
        val titleLine = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(holder.title)
            addView(holder.badge, LinearLayout.LayoutParams(dp(BADGE_DP), dp(BADGE_DP)).apply { marginStart = dp(10) })
        }
        if (holder.inlineValue) {
            container.addView(titleLine, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (hasValue) container.addView(holder.value)
        } else {
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleLine)
                addView(holder.value)
            }
            container.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        if (row.checked != null) {
            // The switch only shows state: focus and clicks belong to the whole row.
            holder.toggle.apply {
                isFocusable = false
                isClickable = false
                isChecked = row.checked
                showText = false
            }
            container.addView(holder.toggle, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(16) })
        }

        if (clickable) {
            container.setBackgroundResource(R.drawable.row_bg)
            container.isFocusable = true
            container.isClickable = true
        } else {
            container.isFocusable = false
        }
        holder.bind(row)
        return holder
    }

    private fun dp(value: Int) = (value * res.displayMetrics.density).toInt()

    /**
     * ScrollView handles arrows itself (finds the next row and focuses it),
     * bypassing the system and its navigation sound. Arrows are handed to the system instead:
     * Android moves focus (with sound), and ScrollView scrolls to the focused row itself.
     */
    private class NativeFocusScrollView(context: Context) : ScrollView(context) {
        override fun executeKeyEvent(event: KeyEvent) = false
    }

    private companion object {
        const val WIDTH_FRACTION = 0.36f
        const val MIN_WIDTH_DP = 360
        const val INLINE_VALUE_MAX = 14
        const val ICON_DP = 24
        const val BADGE_DP = 8
    }
}
