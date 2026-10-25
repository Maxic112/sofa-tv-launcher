package dev.sofatv.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import android.widget.TextView
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.StatFs
import java.io.File
import java.net.Inet4Address
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

/** Metrics snapshot. null means the metric is unavailable on this device. */
class Stats(
    val memTotal: Long,
    val memAvailable: Long,
    val swapUsed: Long,
    val diskTotal: Long,
    val diskFree: Long,
    val cpuTempC: Float?,
    val cpuFreqMhz: Int?,
    val rxBytesPerSec: Long?,
    val txBytesPerSec: Long?,
    val ip: String?,
    val wifiRssi: Int?,
    val ethernet: Boolean,
)

/**
 * Periodic metrics collection. Polling runs only while there is at least one listener
 * (the home status line or the overlay panel) and the screen is on.
 * Files are read on a background thread; listeners are called on the main one.
 */
class StatsMonitor(
    private val context: Context,
    private val executor: Executor,
    private val store: SettingsStore,
) {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<(Stats) -> Unit>()
    private var screenOn = true
    private var running = false
    private var reading = false

    var last: Stats? = null
        private set

    // For network speed: the previous byte counters.
    private var lastRx = -1L
    private var lastTx = -1L
    private var lastTrafficAt = 0L

    /** Temperature file, looked up once; "" means not found or not readable. */
    private var tempFile: String? = null

    private val tick = Runnable { poll() }

    fun addListener(listener: (Stats) -> Unit) {
        listeners.add(listener)
        last?.let(listener)
        update()
    }

    fun removeListener(listener: (Stats) -> Unit) {
        listeners.remove(listener)
        update()
    }

    fun setScreenOn(on: Boolean) {
        screenOn = on
        update()
    }

    /** Re-deliver the last snapshot (e.g. the sleep timer changed, so the line gets redrawn). */
    fun redeliver() {
        last?.let { stats -> listeners.toList().forEach { it(stats) } }
    }

    private fun update() {
        val shouldRun = screenOn && listeners.isNotEmpty()
        if (shouldRun == running) return
        running = shouldRun
        main.removeCallbacks(tick)
        if (running) {
            lastRx = -1
            poll()
        }
    }

    private fun poll() {
        if (!running || reading) return
        reading = true
        executor.execute {
            val stats = read()
            main.post {
                reading = false
                last = stats
                if (running) {
                    listeners.toList().forEach { it(stats) }
                    main.postDelayed(tick, store.statsIntervalMs.toLong())
                }
            }
        }
    }

    private fun read(): Stats {
        val mem = readMeminfo()
        val disk = StatFs(Environment.getDataDirectory().path)
        val now = SystemClock.elapsedRealtime()
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        var rxRate: Long? = null
        var txRate: Long? = null
        if (lastRx >= 0 && rx >= lastRx && now > lastTrafficAt) {
            val seconds = (now - lastTrafficAt) / 1000.0
            rxRate = ((rx - lastRx) / seconds).toLong()
            txRate = ((tx - lastTx) / seconds).toLong()
        }
        lastRx = rx
        lastTx = tx
        lastTrafficAt = now

        val network = readNetwork()
        return Stats(
            memTotal = mem["MemTotal"] ?: 0,
            memAvailable = mem["MemAvailable"] ?: 0,
            swapUsed = (mem["SwapTotal"] ?: 0) - (mem["SwapFree"] ?: 0),
            diskTotal = disk.totalBytes,
            diskFree = disk.availableBytes,
            cpuTempC = readTemperature(),
            cpuFreqMhz = readCpuFreq(),
            rxBytesPerSec = rxRate,
            txBytesPerSec = txRate,
            ip = network.ip,
            wifiRssi = network.rssi,
            ethernet = network.ethernet,
        )
    }

    /** /proc/meminfo values in bytes. */
    private fun readMeminfo(): Map<String, Long> {
        val result = HashMap<String, Long>()
        try {
            File("/proc/meminfo").forEachLine { line ->
                val name = line.substringBefore(':')
                if (name in MEMINFO_KEYS) {
                    line.substringAfter(':').trim().substringBefore(' ').toLongOrNull()?.let { result[name] = it * 1024 }
                }
            }
        } catch (e: Exception) {
            // No access: show zeros; the other metrics are unaffected.
        }
        return result
    }

    private fun readTemperature(): Float? {
        val path = tempFile ?: findTemperatureFile().also { tempFile = it }
        if (path.isEmpty()) return null
        val raw = readNumber(path) ?: return null
        // Usually millidegrees; some firmware reports whole degrees.
        return if (raw > 1000) raw / 1000f else raw.toFloat()
    }

    /** Find the CPU zone by its type (cpu/soc), otherwise the first readable one. */
    private fun findTemperatureFile(): String {
        val zones = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }
            ?.sortedBy { it.name } ?: return ""
        val readable = zones.filter { readNumber("${it.path}/temp")?.let { t -> t > 0 } == true }
        val preferred = readable.firstOrNull { zone ->
            val type = runCatching { File(zone, "type").readText().lowercase() }.getOrDefault("")
            THERMAL_TYPES.any { it in type }
        }
        return (preferred ?: readable.firstOrNull())?.let { "${it.path}/temp" } ?: ""
    }

    /** Highest current frequency across cores. */
    private fun readCpuFreq(): Int? {
        var max = -1L
        // availableProcessors doesn't count offline cores, so probe a few extra.
        for (cpu in 0 until maxOf(Runtime.getRuntime().availableProcessors(), MAX_CPUS)) {
            val khz = readNumber("/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_cur_freq") ?: continue
            if (khz > max) max = khz
        }
        return if (max > 0) (max / 1000).toInt() else null
    }

    private class Network(val ip: String?, val rssi: Int?, val ethernet: Boolean)

    private fun readNetwork(): Network {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return Network(null, null, false)
        val active = cm.activeNetwork ?: return Network(null, null, false)
        val caps = cm.getNetworkCapabilities(active)
        val ip = cm.getLinkProperties(active)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
        val ethernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        var rssi: Int? = null
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            if (Build.VERSION.SDK_INT >= 29 && caps.signalStrength != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) {
                rssi = caps.signalStrength
            } else {
                @Suppress("DEPRECATION")
                rssi = context.getSystemService(WifiManager::class.java)?.connectionInfo?.rssi
                    ?.takeIf { it in -126..-1 }
            }
        }
        return Network(ip, rssi, ethernet)
    }

    private fun readNumber(path: String): Long? = try {
        File(path).readText().trim().toLongOrNull()
    } catch (e: Exception) {
        null
    }

    private companion object {
        val MEMINFO_KEYS = setOf("MemTotal", "MemAvailable", "SwapTotal", "SwapFree")
        val THERMAL_TYPES = listOf("cpu", "soc", "tsens")
        const val MAX_CPUS = 8
    }
}

/** Panel line: "02:15 · 42 min · 1.0/1.9 GB · 4.2/8.5 GB · 52° 1.4 GHz · …", with icons. */
object StatsFormat {
    private const val SEPARATOR = "  ·  "
    private const val ICON_PLACEHOLDER = "\u00A0"
    private const val ICON_SCALE = 1.15f

    const val CLOCK = "clock"
    const val SLEEP = "sleep"
    const val RAM = "ram"
    const val SWAP = "swap"
    const val DISK = "disk"
    const val CPU = "cpu"
    const val TRAFFIC = "traffic"
    const val NETWORK = "network"
    const val IP = "ip"

    /** All metrics in display order, with labels for the "What to show" settings. */
    val ITEMS: List<Pair<String, Int>> = listOf(
        CLOCK to R.string.item_clock,
        SLEEP to R.string.item_sleep,
        RAM to R.string.item_ram,
        SWAP to R.string.item_swap,
        DISK to R.string.item_disk,
        CPU to R.string.item_cpu,
        TRAFFIC to R.string.item_traffic,
        NETWORK to R.string.item_network,
        IP to R.string.item_ip,
    )

    /**
     * Metric icons, used in [line] and in the "What to show" settings. In the line, traffic
     * uses ↓↑ arrows in the text instead of an icon; a wired network gets its own Ethernet icon.
     */
    val ICONS: Map<String, Int> = mapOf(
        CLOCK to R.drawable.ic_schedule,
        SLEEP to R.drawable.ic_bedtime,
        RAM to R.drawable.ic_memory,
        SWAP to R.drawable.ic_swap_horiz,
        DISK to R.drawable.ic_storage,
        CPU to R.drawable.ic_developer_board,
        TRAFFIC to R.drawable.ic_swap_vert,
        NETWORK to R.drawable.ic_wifi,
        IP to R.drawable.ic_lan,
    )

    /**
     * Line for [view]: metrics with icons instead of "RAM", "Storage", "CPU" captions.
     * Icons take this view's text size and color. [items] says which metrics to show.
     */
    fun line(view: TextView, stats: Stats?, sleepRemainingMs: Long, items: Set<String>): CharSequence {
        val context = view.context
        val parts = ArrayList<Pair<Int?, String>>()
        if (CLOCK in items) parts.add(ICONS[CLOCK] to SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()))
        if (SLEEP in items && sleepRemainingMs > 0) parts.add(ICONS[SLEEP] to sleepShort(context, sleepRemainingMs))
        if (stats != null) {
            if (RAM in items) parts.add(ICONS[RAM] to pair(context, stats.memTotal - stats.memAvailable, stats.memTotal))
            if (SWAP in items && stats.swapUsed > 0) parts.add(ICONS[SWAP] to size(context, stats.swapUsed))
            if (DISK in items) parts.add(ICONS[DISK] to pair(context, stats.diskTotal - stats.diskFree, stats.diskTotal))
            val cpu = listOfNotNull(
                stats.cpuTempC?.let { context.getString(R.string.stat_temp, it.toInt()) },
                stats.cpuFreqMhz?.let { context.getString(R.string.stat_freq, it / 1000f) },
            )
            if (CPU in items && cpu.isNotEmpty()) parts.add(ICONS[CPU] to cpu.joinToString(" "))
            if (TRAFFIC in items && stats.rxBytesPerSec != null && stats.txBytesPerSec != null) {
                parts.add(null to context.getString(R.string.stat_traffic, rate(context, stats.rxBytesPerSec), rate(context, stats.txBytesPerSec)))
            }
            if (NETWORK in items) {
                when {
                    stats.ethernet -> parts.add(R.drawable.ic_settings_ethernet to context.getString(R.string.stat_ethernet))
                    stats.wifiRssi != null -> parts.add(ICONS[NETWORK] to context.getString(R.string.stat_dbm, stats.wifiRssi))
                }
            }
            if (IP in items) stats.ip?.let { parts.add(ICONS[IP] to it) }
        }

        val size = (view.textSize * ICON_SCALE).toInt()
        val color = view.currentTextColor
        val text = SpannableStringBuilder()
        parts.forEachIndexed { i, (icon, value) ->
            if (i > 0) text.append(SEPARATOR)
            if (icon != null) {
                val start = text.length
                text.append(ICON_PLACEHOLDER)
                text.setSpan(IconSpan(icon(context, icon, size, color)), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.append(' ')
            }
            text.append(value)
        }
        return text
    }

    /** Line icons are the same on every update, so don't recreate them every 2 seconds. */
    private val icons = HashMap<String, Drawable>()

    private fun icon(context: Context, res: Int, size: Int, color: Int): Drawable =
        icons.getOrPut("$res:$size:$color") {
            context.getDrawable(res)!!.mutate().apply {
                setTint(color)
                setBounds(0, 0, size, size)
            }
        }

    /** Icon centered on the text line (ImageSpan puts it on the baseline by default, above the middle). */
    private class IconSpan(drawable: Drawable) : ImageSpan(drawable) {
        override fun draw(
            canvas: Canvas, text: CharSequence?, start: Int, end: Int,
            x: Float, top: Int, y: Int, bottom: Int, paint: Paint,
        ) {
            val fm = paint.fontMetricsInt
            val center = y + (fm.ascent + fm.descent) / 2
            canvas.save()
            canvas.translate(x, (center - drawable.bounds.height() / 2).toFloat())
            drawable.draw(canvas)
            canvas.restore()
        }
    }

    /** "42 min" / "12 s": timer remainder next to the icon. */
    private fun sleepShort(context: Context, remainingMs: Long): String = if (remainingMs >= 60_000) {
        context.getString(R.string.stat_sleep_short, ((remainingMs + 59_999) / 60_000).toInt())
    } else {
        context.getString(R.string.stat_sleep_short_seconds, ((remainingMs + 999) / 1000).toInt())
    }

    /** "Sleep in 42 min" / "Sleep in 12 s". */
    fun sleepLeft(context: Context, remainingMs: Long): String = if (remainingMs >= 60_000) {
        context.getString(R.string.stat_sleep, ((remainingMs + 59_999) / 60_000).toInt())
    } else {
        context.getString(R.string.stat_sleep_seconds, ((remainingMs + 999) / 1000).toInt())
    }

    /** "used / total": when the units match, the unit is written once, e.g. "1.5/3.7 GB". */
    private fun pair(context: Context, used: Long, total: Long): String {
        val a = size(context, used)
        val b = size(context, total)
        val unit = b.substringAfter(' ')
        return if (a.substringAfter(' ') == unit) "${a.substringBefore(' ')}/$b" else "$a / $b"
    }

    /** Bytes → "930 MB" / "1.9 GB". */
    private fun size(context: Context, bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb < 1000) context.getString(R.string.unit_mb, mb) else context.getString(R.string.unit_gb, mb / 1024)
    }

    /** Bytes/s → "850 kbit/s" / "12.4 Mbit/s". */
    private fun rate(context: Context, bytesPerSec: Long): String {
        val kbit = bytesPerSec * 8 / 1000.0
        return if (kbit < 1000) context.getString(R.string.unit_kbit, kbit) else context.getString(R.string.unit_mbit, kbit / 1000)
    }
}
