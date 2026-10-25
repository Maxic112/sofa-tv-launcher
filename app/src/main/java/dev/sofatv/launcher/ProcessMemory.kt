package dev.sofatv.launcher

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.util.concurrent.Executor

/**
 * How much RAM the launcher itself uses. An app can always see its own process, so this
 * works without root on any Android. Values are PSS in KB, as in `dumpsys meminfo`; the
 * launcher has a single process, and the key service runs in it too.
 *
 * [Debug.getMemoryInfo] reads /proc/self/smaps, which takes tens of milliseconds on slow
 * TVs, so we measure in the background and only on demand (when settings open).
 * It cannot see GPU memory (window buffers, textures): the driver accounts for it via
 * memtrack, and only the system can query that. Graphics comes from [ActivityManager]:
 * on Android 10+ it gives regular apps a cached value refreshed every few minutes.
 */
class ProcessMemory(context: Context, private val executor: Executor) {

    /** [other] is everything outside the named groups: stack, system, misc. */
    class Snapshot(val total: Int, val graphics: Int, val native: Int, val java: Int, val code: Int) {
        val other: Int
            get() = (total - graphics - native - java - code).coerceAtLeast(0)
    }

    private val main = Handler(Looper.getMainLooper())
    private val am = context.getSystemService(ActivityManager::class.java)

    /** The latest measurement; null until the first one. */
    var latest: Snapshot? = null
        private set

    fun measure(onDone: () -> Unit) {
        executor.execute {
            val local = Debug.MemoryInfo()
            Debug.getMemoryInfo(local)
            val system = try {
                am.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()
            } catch (e: Exception) {
                null
            }
            fun Debug.MemoryInfo?.summary(name: String) = this?.getMemoryStat("summary.$name")?.toIntOrNull() ?: 0
            val localGraphics = local.summary("graphics")
            val graphics = maxOf(localGraphics, system.summary("graphics"))
            val snapshot = Snapshot(
                total = local.totalPss - localGraphics + graphics,
                graphics = graphics,
                native = local.summary("native-heap"),
                java = local.summary("java-heap"),
                code = local.summary("code"),
            )
            main.post {
                latest = snapshot
                onDone()
            }
        }
    }
}
