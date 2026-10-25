package dev.sofatv.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.util.LruCache
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executor

/**
 * Pre-rendered tile images, exactly tile-sized.
 *
 * Another app's banner or icon is loaded once and drawn straight into our own bitmap, so
 * the other APK's resources aren't kept around. Bitmaps are saved to disk as raw pixels,
 * so after memory is released (launcher hidden) tiles come back within milliseconds.
 */
class TileCache(context: Context, private val executor: Executor) {
    private val pm = context.packageManager
    private val main = Handler(Looper.getMainLooper())
    private val res = context.resources
    private val cacheDir = context.cacheDir

    /** Tile size and everything derived from it. Changed only on the main thread ([configure]). */
    private class Size(val width: Int, val height: Int, cacheDir: File) {
        val bytes = width * height * 4

        // "_r" marks tiles with rounded corners; a cache without rounding isn't picked up.
        val dir = File(cacheDir, "tiles_${width}x${height}_r")
    }

    @Volatile
    private var size = Size(res.getDimensionPixelSize(R.dimen.tile_width), res.getDimensionPixelSize(R.dimen.tile_height), cacheDir)

    val width: Int
        get() = size.width
    val height: Int
        get() = size.height

    /** Bumped on every size change: background renders of the old size are dropped when they return. */
    private var generation = 0

    private val radius = res.getDimension(R.dimen.tile_radius)

    init {
        // Caches in older formats are no longer needed: unrounded (before 0.5.7) and opaque (0.5.8).
        val legacy = size
        executor.execute {
            File(cacheDir, "tiles_${legacy.width}x${legacy.height}").deleteRecursively()
            File(cacheDir, "tiles_${legacy.width}x${legacy.height}_o").deleteRecursively()
        }
    }

    private var memory = createMemory(MEMORY_TILES)

    private fun createMemory(tiles: Int) = object : LruCache<String, Bitmap>(tiles * size.bytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Tiles waiting for their image; main thread only. */
    private val waiting = HashMap<String, ArrayList<TileView>>()

    private val tileColor = context.getColor(R.color.tile)
    private val baseHeight = res.getDimensionPixelSize(R.dimen.tile_height)
    private val baseIconSize = res.getDimensionPixelSize(R.dimen.tile_icon)
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_primary)
        textSize = res.getDimension(R.dimen.tile_label)
        textAlign = Paint.Align.CENTER
    }

    /**
     * Tile size from the grid settings ([Grid]); [visibleTiles] is how many tiles fit on screen.
     * That many (plus a margin) stay in memory, otherwise small tiles would be re-read from disk
     * while scrolling. Call it when tiles are unbound from views (new screen): old images are freed at once.
     */
    fun configure(width: Int, height: Int, visibleTiles: Int) {
        val tiles = maxOf(MEMORY_TILES, visibleTiles + visibleTiles / 4)
        if (width == size.width && height == size.height) {
            if (tiles * size.bytes > memory.maxSize()) memory.resize(tiles * size.bytes)
            return
        }
        release()
        waiting.clear()
        generation++
        size = Size(width, height, cacheDir)
        memory = createMemory(tiles)
        // Tiles of previous sizes on disk are no longer needed.
        val keep = size.dir.name
        executor.execute {
            cacheDir.listFiles()?.forEach { if (it.name.startsWith("tiles_") && it.name != keep) it.deleteRecursively() }
        }
    }

    fun bind(view: TileView, app: AppEntry) {
        view.app = app
        val key = app.tileKey
        val cached = memory.get(key)
        view.setBitmap(cached)
        if (cached != null) return

        waiting[key]?.let { views ->
            if (view !in views) views.add(view)
            return
        }
        waiting[key] = arrayListOf(view)
        val size = size
        val generation = generation
        executor.execute {
            val bitmap = readDisk(size, key) ?: render(size, app).also { writeDisk(size, key, it) }
            main.post {
                if (generation != this.generation) {
                    bitmap.recycle()
                    return@post
                }
                memory.put(key, bitmap)
                waiting.remove(key)?.forEach { if (it.app?.tileKey == key) it.setBitmap(bitmap) }
            }
        }
    }

    /**
     * Frees memory; tiles stay on disk. Call it once tiles are unbound from views: bitmaps
     * are recycled right away instead of waiting for garbage collection.
     */
    fun release() {
        val bitmaps = memory.snapshot().values
        memory.evictAll()
        bitmaps.forEach { it.recycle() }
    }

    /** Small app icons for lists in settings; keyed by tile key plus size. */
    private val icons = object : LruCache<String, Bitmap>(ICONS_IN_MEMORY) {}

    /**
     * App icon of [size] px for a list row. Loaded in the background (reading another APK takes
     * time), drawn once into a small bitmap and kept in memory; [onReady] runs on the main thread.
     */
    fun loadIcon(app: AppEntry, size: Int, onReady: (Bitmap) -> Unit) {
        val key = "${app.tileKey}@$size"
        icons.get(key)?.let { return onReady(it) }
        executor.execute {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            try {
                app.info.loadIcon(pm).apply { setBounds(0, 0, size, size) }.draw(Canvas(bitmap))
            } catch (e: Exception) {
                Log.w(TAG, "icon ${app.pkg}", e)
            }
            main.post {
                icons.put(key, bitmap)
                onReady(bitmap)
            }
        }
    }

    /** Deletes disk tiles of apps that are gone or were updated. Background thread. */
    fun prune(keys: Set<String>) {
        size.dir.listFiles()?.forEach { if (it.name !in keys) it.delete() }
    }

    /**
     * Finished tile with rounded corners. Rounding happens once here rather than on every frame:
     * software rendering then just copies pixels, and scrolling stays within the frame budget.
     */
    private fun render(size: Size, app: AppEntry): Bitmap {
        val width = size.width
        val height = size.height
        val content = drawContent(width, height, app)
        val rounded = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(content, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        Canvas(rounded).drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, paint)
        content.recycle()
        return rounded
    }

    private fun drawContent(width: Int, height: Int, app: AppEntry): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val banner = try {
            app.info.loadBanner(pm)
        } catch (e: Exception) {
            null
        }
        if (banner != null) {
            // Non-16:9 tile (custom height in settings): keep the banner undistorted, center-cropped.
            val bw = banner.intrinsicWidth
            val bh = banner.intrinsicHeight
            if (bw > 0 && bh > 0) {
                val scale = maxOf(width.toFloat() / bw, height.toFloat() / bh)
                val w = (bw * scale).toInt()
                val h = (bh * scale).toInt()
                val left = (width - w) / 2
                val top = (height - h) / 2
                banner.setBounds(left, top, left + w, top + h)
            } else {
                banner.setBounds(0, 0, width, height)
            }
            banner.draw(canvas)
            return bitmap
        }

        // No banner: a frame with the icon and label, as in the stock launcher.
        canvas.drawColor(tileColor)
        val iconSize = (baseIconSize * minOf(1f, height.toFloat() / baseHeight)).toInt()
        val labelHeight = labelPaint.fontSpacing
        val top = ((height - iconSize - labelHeight) / 2).toInt()
        val left = (width - iconSize) / 2
        try {
            val icon = app.info.loadIcon(pm)
            icon.setBounds(left, top, left + iconSize, top + iconSize)
            icon.draw(canvas)
        } catch (e: Exception) {
            Log.w(TAG, "icon ${app.pkg}", e)
        }
        val padding = height / 8f
        val label = TextUtils.ellipsize(app.label, labelPaint, width - 2 * padding, TextUtils.TruncateAt.END)
        val baseline = top + iconSize + labelHeight - labelPaint.descent()
        canvas.drawText(label, 0, label.length, width / 2f, baseline, labelPaint)
        return bitmap
    }

    private fun readDisk(size: Size, key: String): Bitmap? {
        val file = File(size.dir, key)
        if (file.length() != size.bytes.toLong()) return null
        return try {
            val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(file.readBytes()))
            bitmap
        } catch (e: Exception) {
            Log.w(TAG, "read $key", e)
            null
        }
    }

    private fun writeDisk(size: Size, key: String, bitmap: Bitmap) {
        try {
            size.dir.mkdirs()
            val buffer = ByteBuffer.allocate(size.bytes)
            bitmap.copyPixelsToBuffer(buffer)
            val tmp = File(size.dir, "$key.tmp")
            tmp.writeBytes(buffer.array())
            tmp.renameTo(File(size.dir, key))
        } catch (e: Exception) {
            Log.w(TAG, "write $key", e)
        }
    }

    private companion object {
        const val TAG = "TileCache"

        /** Tiles kept in memory: a 1080p screen fits ~25, the rest is headroom for scrolling. */
        const val MEMORY_TILES = 30

        /** List icons kept in memory: 64 × ~9 KB (48×48), about half a megabyte. */
        const val ICONS_IN_MEMORY = 64
    }
}
