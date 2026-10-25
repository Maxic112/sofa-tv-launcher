package dev.sofatv.launcher

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Sleep timer. It only counts time; the one-minute warning and putting the TV to sleep are
 * done by the key service (overlay and GLOBAL_ACTION_LOCK_SCREEN), so it won't start without it.
 */
class SleepTimer {
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) = listeners.add(listener)

    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    private fun onChanged() = listeners.toList().forEach { it() }

    private var endAt = 0L

    val isRunning: Boolean
        get() = endAt > 0

    val remainingMs: Long
        get() = if (endAt > 0) (endAt - SystemClock.elapsedRealtime()).coerceAtLeast(0) else 0

    /** Duration of the last run in seconds, so the picker starts focused on it. */
    var lastSeconds = 0
        private set

    private val warn = Runnable { KeyService.instance?.showSleepWarning() }
    private val sleep = Runnable {
        endAt = 0
        onChanged()
        KeyService.instance?.goToSleep()
    }

    fun start(seconds: Int) {
        lastSeconds = seconds
        schedule(seconds * 1000L)
    }

    fun extend(minutes: Int) = schedule(remainingMs + minutes * 60_000L)

    private fun schedule(durationMs: Long) {
        cancelCallbacks()
        endAt = SystemClock.elapsedRealtime() + durationMs
        handler.postDelayed(warn, (durationMs - WARNING_MS).coerceAtLeast(0))
        handler.postDelayed(sleep, durationMs)
        onChanged()
    }

    fun cancel() {
        if (endAt == 0L) return
        cancelCallbacks()
        endAt = 0
        onChanged()
    }

    private fun cancelCallbacks() {
        handler.removeCallbacks(warn)
        handler.removeCallbacks(sleep)
    }

    companion object {
        /** Warn one minute ahead; a timer shorter than a minute warns right away. */
        const val WARNING_MS = 60_000L

        /** Presets in seconds. 15 s is a quick check that the TV really goes to sleep. */
        val PRESETS = intArrayOf(15, 15 * 60, 30 * 60, 45 * 60, 60 * 60, 90 * 60)
        const val EXTEND_MINUTES = 15
    }
}
