package dev.sofatv.launcher

import android.content.res.Resources

/**
 * Home screen grid geometry in pixels: how many columns and how large the tiles are.
 *
 * "Auto" ([SettingsStore.gridColumns] = 0) uses standard 160×90 dp banners and as many
 * columns as fit (5 on 1080p at density 320). A fixed column count stretches the tiles
 * across the full width between the margins. Height is 16:9 of the tile width,
 * multiplied by [SettingsStore.tileHeightPercent].
 */
class Grid private constructor(
    val span: Int,
    val autoSpan: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val gap: Int,
    /** Left and right margin that centers the grid. */
    val side: Int,
) {
    companion object {
        /** Choices in settings; 0 means "Auto". */
        val COLUMNS = listOf(0, 3, 4, 5, 6, 7, 8, 9, 10)
        val HEIGHT_PERCENTS = listOf(70, 80, 90, 100, 110, 125, 150)

        fun of(res: Resources, store: SettingsStore): Grid {
            val gap = res.getDimensionPixelSize(R.dimen.tile_gap)
            val screen = res.displayMetrics.widthPixels
            val usable = screen - 2 * (res.getDimensionPixelSize(R.dimen.screen_padding) - gap / 2)
            val baseWidth = res.getDimensionPixelSize(R.dimen.tile_width)
            val baseHeight = res.getDimensionPixelSize(R.dimen.tile_height)
            val autoSpan = (usable / (baseWidth + gap)).coerceAtLeast(1)
            val span = store.gridColumns.takeIf { it > 0 } ?: autoSpan
            // Same column count as "Auto": keep the standard tiles instead of stretching them.
            val width = if (span == autoSpan) baseWidth else (usable / span - gap).coerceAtLeast(gap)
            val height = (width.toLong() * baseHeight * store.tileHeightPercent / (baseWidth * 100L)).toInt().coerceAtLeast(gap)
            val side = (screen - span * (width + gap)) / 2
            return Grid(span, autoSpan, width, height, gap, side)
        }
    }
}
