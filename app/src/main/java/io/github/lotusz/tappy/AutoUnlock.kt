package io.github.lotusz.tappy

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import java.util.Calendar

/**
 * Ends a block by itself at a fixed time of day (10 PM by default).
 *
 * The deadline is fixed when the block starts. Within the same boot it's measured on the
 * uptime clock, so moving the phone's clock forward doesn't end the block early.
 */
object AutoUnlock {

    private const val REQUEST_CODE = 1

    /** Next wall-clock time at [minuteOfDay] strictly after [now]. */
    fun nextOccurrence(now: Long, minuteOfDay: Int): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    fun bootCount(context: Context): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    private fun sameBoot(context: Context, state: TappyStore.State): Boolean {
        val boot = bootCount(context)
        return if (boot != -1 && state.blockStartedBootCount != -1) {
            boot == state.blockStartedBootCount
        } else {
            SystemClock.elapsedRealtime() >= state.blockStartedElapsed
        }
    }

    fun isDue(context: Context): Boolean {
        val s = TappyStore.state.value
        if (!s.blocking || s.autoUnlockAt == 0L) return false
        return if (sameBoot(context, s)) {
            SystemClock.elapsedRealtime() - s.blockStartedElapsed >= s.autoUnlockAt - s.blockStartedAt
        } else {
            // After a reboot the uptime clock restarts, so fall back to the wall clock.
            System.currentTimeMillis() >= s.autoUnlockAt
        }
    }

    /** Wakes the phone around the deadline. [isDue] is also checked on every app switch, so this is a backstop. */
    fun schedule(context: Context) {
        val s = TappyStore.state.value
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pendingIntent(context)
        if (!s.blocking || s.autoUnlockAt == 0L) {
            am.cancel(pi)
            return
        }
        val (type, at) = if (sameBoot(context, s)) {
            AlarmManager.ELAPSED_REALTIME_WAKEUP to s.blockStartedElapsed + (s.autoUnlockAt - s.blockStartedAt)
        } else {
            AlarmManager.RTC_WAKEUP to s.autoUnlockAt
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(type, at, pi)
        } else {
            am.setAndAllowWhileIdle(type, at, pi)
        }
    }

    private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, AutoUnlockReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Fires at the auto-unlock time, and re-arms the alarm after a reboot. */
class AutoUnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BlockController.checkAutoUnlock(context)) AutoUnlock.schedule(context)
    }
}
