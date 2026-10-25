package dev.sofatv.launcher

import android.app.Activity
import android.app.AlertDialog
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Tile menu (long OK / Menu): a plain Android list in a dialog, so navigation and sounds are
 * native, but with an icon on every item; the dangerous item ("Uninstall") is red.
 */
object TileMenu {

    class Item(val label: String, val icon: Int, val danger: Boolean = false, val action: () -> Unit)

    fun show(activity: Activity, title: String, titleIcon: Drawable?, items: List<Item>) {
        val adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(position: Int) = items[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                row(activity, items[position])
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setIcon(titleIcon)
            .setAdapter(adapter) { _, which -> items[which].action() }
            .show()
    }

    private fun row(activity: Activity, item: Item): View {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val color = activity.getColor(if (item.danger) R.color.danger else R.color.text_primary)
        val icon = ImageView(activity).apply {
            setImageResource(item.icon)
            setColorFilter(color)
        }
        val text = TextView(activity).apply {
            text = item.label
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(24), dp(8), dp(24), dp(8))
            addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) })
            addView(text)
        }
    }
}
