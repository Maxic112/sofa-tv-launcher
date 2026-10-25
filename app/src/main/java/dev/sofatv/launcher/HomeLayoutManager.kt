package dev.sofatv.launcher

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Home screen grid. When no tile exists in the pressed direction, GridLayoutManager offers
 * the nearest item, and above the first row that is a section header. A header cannot take
 * focus, so RecyclerView keeps focus where it is and the user cannot leave the grid for the
 * buttons at the top. By not offering the header, RecyclerView hands the search to the
 * system, which moves focus to the buttons (with the usual navigation sound).
 */
class HomeLayoutManager(context: Context, spanCount: Int) : GridLayoutManager(context, spanCount) {

    override fun onFocusSearchFailed(
        focused: View,
        direction: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State,
    ): View? {
        val candidate = super.onFocusSearchFailed(focused, direction, recycler, state)
        return candidate?.takeIf { it.hasFocusable() }
    }
}
