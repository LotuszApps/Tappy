package io.github.lotuszapps.tappy

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * Watches the foreground window. While a block is active it:
 *  - sends you home and shows [BlockedActivity] when a blocked app comes to the front;
 *  - does the same for any Settings / package-installer screen that mentions Tappy, so the
 *    accessibility service, device admin and the app itself can't be turned off or removed.
 */
class BlockerService : AccessibilityService() {

    private var lastBounceAt = 0L
    private var protectedFor = -1L
    private var protectedPackages = emptySet<String>()
    private var lastGuardCheckAt = 0L
    private lateinit var appLabel: String

    override fun onServiceConnected() {
        super.onServiceConnected()
        appLabel = getString(R.string.app_name)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val state = TappyStore.state.value
        if (!state.blocking) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        // Only act on the window actually in front (ignores overlays / background updates).
        val root = rootInActiveWindow ?: return
        try {
            if (root.packageName?.toString() != pkg) return

            if (pkg in state.blockedPackages && !isProtected(pkg, state)) {
                bounce(BlockedActivity.REASON_APP, pkg)
                return
            }

            if (isGuardedPackage(pkg)) {
                val now = SystemClock.uptimeMillis()
                if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && now - lastGuardCheckAt < 250) return
                lastGuardCheckAt = now
                if (root.findAccessibilityNodeInfosByText(appLabel).isNotEmpty()) {
                    bounce(BlockedActivity.REASON_PROTECTED, pkg)
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

    private fun bounce(reason: String, pkg: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastBounceAt < 600) return
        lastBounceAt = now
        // Past the auto-unlock time: end the block instead of bouncing.
        if (BlockController.checkAutoUnlock(this)) {
            Toast.makeText(this, BlockController.describe(this, BlockController.TagResult.AUTO_RELEASED), Toast.LENGTH_SHORT).show()
            return
        }
        performGlobalAction(GLOBAL_ACTION_HOME)
        startActivity(
            Intent(this, BlockedActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                .putExtra(BlockedActivity.EXTRA_REASON, reason)
                .putExtra(BlockedActivity.EXTRA_PACKAGE, pkg)
        )
    }

    override fun onInterrupt() = Unit

    companion object {
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
