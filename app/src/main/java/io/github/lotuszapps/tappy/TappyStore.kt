package io.github.lotuszapps.tappy

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persistent app state, mirrored into a [StateFlow] so the UI and the blocker see changes immediately. */
object TappyStore {

    data class State(
        val blocking: Boolean = false,
        val blockedPackages: Set<String> = emptySet(),
        val pairedTagId: String? = null,
        val emergencyUnlocksLeft: Int = EMERGENCY_UNLOCKS,
        /** Daily auto-unlock setting; only editable while not blocking. */
        val autoUnlockEnabled: Boolean = true,
        val autoUnlockMinuteOfDay: Int = DEFAULT_AUTO_UNLOCK_MINUTE,
        /** Days (Calendar.DAY_OF_WEEK) on which a block is timed instead of full; only editable while not blocking. */
        val timedDays: Set<Int> = emptySet(),
        /** On timed days, how long each opening of a blocked app may last. */
        val timedMinutes: Int = DEFAULT_TIMED_MINUTES,
        /** Apps installed during a lock: true = block them straight away, false = ask with a notification. */
        val autoBlockNewApps: Boolean = false,
        /** When the current block started, by wall clock, uptime clock and boot number. */
        val blockStartedAt: Long = 0L,
        val blockStartedElapsed: Long = 0L,
        val blockStartedBootCount: Int = -1,
        /** Wall-clock time the current block ends by itself, or 0 for never. Fixed when the block starts. */
        val autoUnlockAt: Long = 0L,
        /** How often each blocked app was opened during the current block. */
        val attempts: Map<String, Int> = emptyMap(),
    )

    const val EMERGENCY_UNLOCKS = 3
    const val DEFAULT_AUTO_UNLOCK_MINUTE = 22 * 60
    const val DEFAULT_TIMED_MINUTES = 5

    private const val KEY_BLOCKING = "blocking"
    private const val KEY_PACKAGES = "blocked_packages"
    private const val KEY_TAG = "paired_tag"
    private const val KEY_EMERGENCY = "emergency_unlocks_left"
    private const val KEY_AUTO_ENABLED = "auto_unlock_enabled"
    private const val KEY_AUTO_MINUTE = "auto_unlock_minute"
    private const val KEY_STARTED = "block_started_at"
    private const val KEY_STARTED_ELAPSED = "block_started_elapsed"
    private const val KEY_STARTED_BOOT = "block_started_boot"
    private const val KEY_AUTO_AT = "auto_unlock_at"
    private const val KEY_ATTEMPTS = "attempts"
    private const val KEY_TIMED_DAYS = "timed_days"
    private const val KEY_TIMED_MINUTES = "timed_minutes"
    private const val KEY_AUTO_BLOCK_NEW = "auto_block_new_apps"

    private lateinit var prefs: SharedPreferences
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("tappy", Context.MODE_PRIVATE)
        _state.value = State(
            blocking = prefs.getBoolean(KEY_BLOCKING, false),
            blockedPackages = prefs.getStringSet(KEY_PACKAGES, emptySet())!!.toSet(),
            pairedTagId = prefs.getString(KEY_TAG, null),
            emergencyUnlocksLeft = prefs.getInt(KEY_EMERGENCY, EMERGENCY_UNLOCKS),
            autoUnlockEnabled = prefs.getBoolean(KEY_AUTO_ENABLED, true),
            autoUnlockMinuteOfDay = prefs.getInt(KEY_AUTO_MINUTE, DEFAULT_AUTO_UNLOCK_MINUTE),
            timedDays = prefs.getStringSet(KEY_TIMED_DAYS, emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet(),
            timedMinutes = prefs.getInt(KEY_TIMED_MINUTES, DEFAULT_TIMED_MINUTES),
            autoBlockNewApps = prefs.getBoolean(KEY_AUTO_BLOCK_NEW, false),
            blockStartedAt = prefs.getLong(KEY_STARTED, 0L),
            blockStartedElapsed = prefs.getLong(KEY_STARTED_ELAPSED, 0L),
            blockStartedBootCount = prefs.getInt(KEY_STARTED_BOOT, -1),
            autoUnlockAt = prefs.getLong(KEY_AUTO_AT, 0L),
            attempts = prefs.getStringSet(KEY_ATTEMPTS, emptySet())!!.mapNotNull { entry ->
                val (pkg, count) = entry.split('\t').takeIf { it.size == 2 } ?: return@mapNotNull null
                count.toIntOrNull()?.let { pkg to it }
            }.toMap(),
        )
    }

    @Synchronized
    private fun update(transform: (State) -> State) {
        val next = transform(_state.value)
        // commit() rather than apply(): the block state must survive an immediate kill/reboot.
        prefs.edit()
            .putBoolean(KEY_BLOCKING, next.blocking)
            .putStringSet(KEY_PACKAGES, next.blockedPackages)
            .putString(KEY_TAG, next.pairedTagId)
            .putInt(KEY_EMERGENCY, next.emergencyUnlocksLeft)
            .putBoolean(KEY_AUTO_ENABLED, next.autoUnlockEnabled)
            .putInt(KEY_AUTO_MINUTE, next.autoUnlockMinuteOfDay)
            .putStringSet(KEY_TIMED_DAYS, next.timedDays.map { it.toString() }.toSet())
            .putInt(KEY_TIMED_MINUTES, next.timedMinutes)
            .putBoolean(KEY_AUTO_BLOCK_NEW, next.autoBlockNewApps)
            .putLong(KEY_STARTED, next.blockStartedAt)
            .putLong(KEY_STARTED_ELAPSED, next.blockStartedElapsed)
            .putInt(KEY_STARTED_BOOT, next.blockStartedBootCount)
            .putLong(KEY_AUTO_AT, next.autoUnlockAt)
            .putStringSet(KEY_ATTEMPTS, next.attempts.map { (pkg, count) -> "$pkg\t$count" }.toSet())
            .commit()
        _state.value = next
    }

    /** Selection, pairing and the auto-unlock time are frozen while a block is active. */
    fun setBlockedPackages(packages: Set<String>): Boolean {
        if (_state.value.blocking) return false
        update { it.copy(blockedPackages = packages) }
        return true
    }

    fun setPairedTag(tagId: String?): Boolean {
        if (_state.value.blocking) return false
        update { it.copy(pairedTagId = tagId) }
        return true
    }

    fun setAutoUnlock(enabled: Boolean, minuteOfDay: Int): Boolean {
        if (_state.value.blocking) return false
        update { it.copy(autoUnlockEnabled = enabled, autoUnlockMinuteOfDay = minuteOfDay) }
        return true
    }

    /** Adding is always allowed, even mid-block: it only makes the lock stricter. */
    fun addBlockedPackage(pkg: String) = update { it.copy(blockedPackages = it.blockedPackages + pkg) }

    fun setAutoBlockNewApps(enabled: Boolean): Boolean {
        if (_state.value.blocking) return false
        update { it.copy(autoBlockNewApps = enabled) }
        return true
    }

    fun setTimedSchedule(days: Set<Int>, minutes: Int): Boolean {
        if (_state.value.blocking) return false
        update { it.copy(timedDays = days, timedMinutes = minutes) }
        return true
    }

    fun startBlock(startedAt: Long, startedElapsed: Long, bootCount: Int, autoUnlockAt: Long) = update {
        it.copy(
            blocking = true,
            blockStartedAt = startedAt,
            blockStartedElapsed = startedElapsed,
            blockStartedBootCount = bootCount,
            autoUnlockAt = autoUnlockAt,
            attempts = emptyMap(),
        )
    }

    /** Counts one blocked open of [pkg] and returns its new total for this block. */
    fun recordAttempt(pkg: String): Int {
        var total = 0
        update {
            total = (it.attempts[pkg] ?: 0) + 1
            it.copy(attempts = it.attempts + (pkg to total))
        }
        return total
    }

    fun endBlock() = update {
        it.copy(
            blocking = false,
            blockStartedAt = 0L,
            blockStartedElapsed = 0L,
            blockStartedBootCount = -1,
            autoUnlockAt = 0L,
            attempts = emptyMap(),
        )
    }

    fun consumeEmergencyUnlock(): Boolean {
        if (!_state.value.blocking || _state.value.emergencyUnlocksLeft <= 0) return false
        update { it.copy(emergencyUnlocksLeft = it.emergencyUnlocksLeft - 1) }
        endBlock()
        return true
    }
}
