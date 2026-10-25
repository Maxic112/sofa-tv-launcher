package dev.sofatv.launcher

import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

sealed class HomeItem(val id: Long)

class HeaderItem(id: Long, val title: String) : HomeItem(id)

class AppItem(id: Long, val app: AppEntry, val favorite: Boolean, val moving: Boolean) : HomeItem(id)

/** A TV input tile ([TvInputs]). */
class InputItem(id: Long, val input: TvInputs.Input) : HomeItem(id)

/** The "Show / Hide all apps" button below the favorites. */
class ButtonItem(id: Long, val title: String) : HomeItem(id)

/** Section headers, the "all apps" button and the tiles, all in one grid. */
class HomeAdapter(
    private val tiles: TileCache,
    private val listener: Listener,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface Listener {
        fun onLaunch(item: AppItem)
        fun onMenu(item: AppItem)
        fun onToggleAllApps()
        fun onInput(item: InputItem)
    }

    private class Holder(view: View) : RecyclerView.ViewHolder(view)

    var items: List<HomeItem> = emptyList()
        private set

    /** Every tile created: only a few (visible ones plus the RecyclerView cache); needed to drop bitmaps. */
    private val tileViews = ArrayList<TileView>()

    init {
        setHasStableIds(true)
    }

    fun submit(newItems: List<HomeItem>) {
        val old = items
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].id == newItems[n].id
            override fun areContentsTheSame(o: Int, n: Int): Boolean {
                val a = old[o]
                val b = newItems[n]
                return when {
                    a is HeaderItem && b is HeaderItem -> a.title == b.title
                    a is ButtonItem && b is ButtonItem -> a.title == b.title
                    a is AppItem && b is AppItem -> a.app.tileKey == b.app.tileKey && a.moving == b.moving
                    a is InputItem && b is InputItem -> a.input.label == b.input.label && a.input.icon == b.input.icon
                    else -> false
                }
            }
        })
        items = newItems
        diff.dispatchUpdatesTo(this)
    }

    fun positionOf(id: Long) = items.indexOfFirst { it.id == id }

    /** The launcher is hidden: release the bitmaps so their memory goes back to the system. */
    fun releaseBitmaps() = tileViews.forEach { it.setBitmap(null) }

    fun restoreBitmaps() = tileViews.forEach { view -> view.app?.let { tiles.bind(view, it) } }

    override fun getItemCount() = items.size

    override fun getItemId(position: Int) = items[position].id

    override fun getItemViewType(position: Int) = when (items[position]) {
        is HeaderItem -> TYPE_HEADER
        is ButtonItem -> TYPE_BUTTON
        is AppItem -> TYPE_TILE
        is InputItem -> TYPE_INPUT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val res = parent.resources
        val gap = res.getDimensionPixelSize(R.dimen.tile_gap)
        if (viewType == TYPE_HEADER) {
            val header = TextView(parent.context).apply {
                setTextColor(context.getColor(R.color.text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(gap / 2, gap, gap / 2, gap / 2) }
            }
            return Holder(header)
        }
        if (viewType == TYPE_BUTTON) {
            // Styled like the header buttons; sized to the text, so the row spans the grid with the button on the left.
            val button = TextView(parent.context, null, 0, R.style.TopButton).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    res.getDimensionPixelSize(R.dimen.all_apps_button_height),
                ).apply { setMargins(gap / 2, gap, gap / 2, gap / 2) }
                setOnClickListener { listener.onToggleAllApps() }
            }
            return Holder(button)
        }
        val tile = TileView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(tiles.width, tiles.height).apply {
                setMargins(gap / 2, gap / 2, gap / 2, gap / 2)
            }
        }
        if (viewType == TYPE_INPUT) {
            val holder = Holder(tile)
            tile.isLongClickable = false
            tile.setOnClickListener { (items.getOrNull(holder.bindingAdapterPosition) as? InputItem)?.let(listener::onInput) }
            return holder
        }
        tileViews.add(tile)
        val holder = Holder(tile)
        tile.setOnClickListener { appAt(holder)?.let(listener::onLaunch) }
        tile.setOnLongClickListener { appAt(holder)?.let(listener::onMenu); true }
        return holder
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is HeaderItem -> (holder.itemView as TextView).text = item.title
            is ButtonItem -> (holder.itemView as TextView).text = item.title
            is AppItem -> {
                val tile = holder.itemView as TileView
                tile.moving = item.moving
                tiles.bind(tile, item.app)
            }
            is InputItem -> (holder.itemView as TileView).setInput(item.input)
        }
    }

    private fun appAt(holder: RecyclerView.ViewHolder): AppItem? =
        items.getOrNull(holder.bindingAdapterPosition) as? AppItem

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_TILE = 1
        const val TYPE_BUTTON = 2
        const val TYPE_INPUT = 3

        fun isTile(viewType: Int) = viewType == TYPE_TILE || viewType == TYPE_INPUT
    }
}
