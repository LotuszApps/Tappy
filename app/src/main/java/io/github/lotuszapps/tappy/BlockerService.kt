package io.github.lotuszapps.tappy

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * Watches the foreground window. While a block is active it:
 *  - on a full day, sends you home and shows [BlockOverlay] when a blocked app comes to the front;
 *  - on a timed day, asks before letting a blocked app open for a few minutes, then sends you home;
 *  - sends you home from any Settings / package-installer screen that mentions Tappy, so the
 *    accessibility service, device admin and the app itself can't be turned off or removed.
 */
class BlockerService : AccessibilityService() {

    private var lastBounceAt = 0L
    private var protectedFor = -1L
    private var protectedPackages = emptySet<String>()
    private var lastGuardCheckAt = 0L
    private lateinit var appLabel: String
    private lateinit var overlay: BlockOverlay
    private val handler = Handler(Looper.getMainLooper())

    /** Package in front, as of the latest event. */
    private var foregroundPkg: String? = null

    /** Timed-day sessions: package to the uptime-clock time its current opening ends. */
    private val sessionEnds = mutableMapOf<String, Long>()
    private var sessionsFor = -1L

    /** Timed day: the app whose "open it for N minutes?" question is showing. */
    private var confirmingPkg: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        appLabel = getString(R.string.app_name)
        overlay = BlockOverlay(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val state = TappyStore.state.value
        if (!state.blocking) {
            sessionEnds.clear()
            return
        }
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        val pkg = event.packageName?.toString() ?: return

        // Only act on the window actually in front (ignores overlays / background updates).
        val root = rootInActiveWindow ?: return
        try {
            if (root.packageName?.toString() != pkg) return
            if (pkg != foregroundPkg) {
                foregroundPkg = pkg
                pruneSessions()
                // Left the app while its question was up (home, recents, shade): drop the question.
                // Coming back asks again.
                if (confirmingPkg != null && confirmingPkg != pkg) {
                    confirmingPkg = null
                    overlay.hide()
                }
            }
            if (pkg == packageName) return
            BlockController.syncOwnerSuspension(this)

            if (pkg in state.blockedPackages && !isProtected(pkg, state)) {
                if (BlockController.currentMode(state) == BlockController.Mode.TIMED) {
                    timedOpen(pkg, state)
                } else {
                    bounce(REASON_APP, pkg)
                }
                return
            }

            if (isGuardedPackage(pkg)) {
                val now = SystemClock.uptimeMillis()
                if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && now - lastGuardCheckAt < 250) return
                lastGuardCheckAt = now
                if (root.findAccessibilityNodeInfosByText(appLabel).isNotEmpty()) {
                    bounce(REASON_PROTECTED, pkg)
                }
            }
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }

    private fun isGuardedPackage(pkg: String): Boolean =
        pkg == "com.android.settings" ||
            pkg.endsWith(".settings") ||
            pkg.contains("packageinstaller") ||
            pkg == "com.miui.securitycenter" ||
            pkg == "com.google.android.permissioncontroller"

    /** Protected apps are resolved once per block; default apps rarely change mid-block. */
    private fun isProtected(pkg: String, state: TappyStore.State): Boolean {
        if (protectedFor != state.blockStartedAt) {
            protectedPackages = ProtectedApps.packages(this)
            protectedFor = state.blockStartedAt
        }
        return pkg in protectedPackages
    }

    /** Full day: send the app away and say why. Also used for protected settings screens. */
    private fun bounce(reason: String, pkg: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastBounceAt < 600) return
        lastBounceAt = now
        if (endBlockIfAutoUnlockPassed()) return

        performGlobalAction(GLOBAL_ACTION_HOME)
        if (reason == REASON_PROTECTED) {
            overlay.show(
                "Tappy is locked",
                "Tappy's settings can't be changed and it can't be uninstalled while a block is active. Tap your tag to end the block first.",
            )
            return
        }
        val attempts = TappyStore.recordAttempt(pkg).also { AttemptNotifier.show(this, pkg, it) }
        overlay.show(
            "${AttemptNotifier.labelOf(this, pkg)} is blocked",
            AttemptNotifier.describe(attempts) + "\n\nTap your Tappy tag to unlock your apps." + autoUnlockNote(),
        )
    }

    /**
     * Timed day: each opening of a blocked app first asks whether you really want it for a few
     * minutes. Coming back to it while that time is still running continues the same opening;
     * after it runs out you're sent home.
     */
    private fun timedOpen(pkg: String, state: TappyStore.State) {
        if (sessionsFor != state.blockStartedAt) {
            sessionEnds.clear()
            sessionsFor = state.blockStartedAt
        }
        val now = SystemClock.elapsedRealtime()
        val end = sessionEnds[pkg]
        if (end != null) {
            if (now >= end) timeUp(pkg) // the timer is late (e.g. the phone was asleep)
            return
        }
        if (confirmingPkg == pkg) return
        // Just sent home (Not now / Time's up): ignore the app's last events while home takes over.
        if (SystemClock.uptimeMillis() - lastBounceAt < 600) return
        if (endBlockIfAutoUnlockPassed()) return

        val minutes = state.timedMinutes
        val label = AttemptNotifier.labelOf(this, pkg)
        val opens = TappyStore.recordAttempt(pkg).also { AttemptNotifier.show(this, pkg, it) }
        confirmingPkg = pkg
        overlay.show(
            "Open $label?",
            "Do you really want to open $label for $minutes ${if (minutes == 1) "minute" else "minutes"}?" +
                "\n\nYou've tried $opens× since you locked.",
            listOf(
                "Not now" to {
                    confirmingPkg = null
                    lastBounceAt = SystemClock.uptimeMillis()
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    Unit
                },
                "Open" to {
                    confirmingPkg = null
                    startSession(pkg, minutes)
                },
            ),
        )
    }

    private fun startSession(pkg: String, minutes: Int) {
        sessionEnds[pkg] = SystemClock.elapsedRealtime() + minutes * 60_000L
        handler.postDelayed({ onSessionTimer(pkg) }, minutes * 60_000L)
        Toast.makeText(this, "${AttemptNotifier.labelOf(this, pkg)}: $minutes min", Toast.LENGTH_SHORT).show()
    }

    private fun onSessionTimer(pkg: String) {
        val end = sessionEnds[pkg] ?: return
        if (SystemClock.elapsedRealtime() < end) return // superseded by a newer opening
        if (foregroundPkg == pkg && TappyStore.state.value.blocking) timeUp(pkg) else sessionEnds.remove(pkg)
    }

    private fun timeUp(pkg: String) {
        sessionEnds.remove(pkg)
        lastBounceAt = SystemClock.uptimeMillis()
        performGlobalAction(GLOBAL_ACTION_HOME)
        overlay.show(
            "Time's up",
            "That was your ${TappyStore.state.value.timedMinutes} minutes of ${AttemptNotifier.labelOf(this, pkg)}." + autoUnlockNote(),
        )
    }

    /** Openings that ran out while you were elsewhere are over; the next open starts a new one. */
    private fun pruneSessions() {
        val now = SystemClock.elapsedRealtime()
        sessionEnds.entries.removeAll { (pkg, end) -> pkg != foregroundPkg && now >= end }
    }

    private fun endBlockIfAutoUnlockPassed(): Boolean {
        if (!BlockController.checkAutoUnlock(this)) return false
        Toast.makeText(this, BlockController.describe(this, BlockController.TagResult.AUTO_RELEASED), Toast.LENGTH_SHORT).show()
        return true
    }

    private fun autoUnlockNote(): String {
        val at = TappyStore.state.value.autoUnlockAt
        return if (at == 0L) "" else "\nApps come back by themselves at ${BlockController.formatUnlockTime(this, at)}."
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::overlay.isInitialized) overlay.hide()
        super.onDestroy()
    }

    companion object {
        private const val REASON_APP = "app"
        private const val REASON_PROTECTED = "protected"

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val me = "${context.packageName}/${BlockerService::class.java.name}"
            val meShort = "${context.packageName}/.${BlockerService::class.java.simpleName}"
            return enabled.split(':').any { it.equals(me, true) || it.equals(meShort, true) }
        }
    }
}
