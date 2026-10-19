package app.tappy

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.SystemClock
import android.os.UserManager
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.util.Log
import java.util.Date

/** Starts and ends blocks, and applies the extra device-owner policies when available. */
object BlockController {

    enum class TagResult { NOT_PAIRED, WRONG_TAG, NO_APPS, SETUP_INCOMPLETE, ENGAGED, RELEASED, AUTO_RELEASED }

    private const val TAG = "Tappy"

    fun onTagScanned(context: Context, tagId: String): TagResult {
        val state = TappyStore.state.value
        return when {
            state.pairedTagId == null -> TagResult.NOT_PAIRED
            !state.pairedTagId.equals(tagId, ignoreCase = true) -> TagResult.WRONG_TAG
            // Past the auto-unlock time: end the block rather than treat the tap as "start a new one".
            checkAutoUnlock(context) -> TagResult.AUTO_RELEASED
            state.blocking -> {
                release(context)
                TagResult.RELEASED
            }
            else -> tryEngage(context)
        }
    }

    /** Starts a block from the in-app button, without the tag. Ending it still needs the tag (or auto-unlock). */
    fun lockNow(context: Context): TagResult {
        if (TappyStore.state.value.pairedTagId == null) return TagResult.NOT_PAIRED
        checkAutoUnlock(context) // clears a block whose auto-unlock time already passed
        if (TappyStore.state.value.blocking) return TagResult.ENGAGED
        return tryEngage(context)
    }

    private fun tryEngage(context: Context): TagResult = when {
        blockablePackages(context).isEmpty() -> TagResult.NO_APPS
        !isProtectionReady(context) -> TagResult.SETUP_INCOMPLETE
        else -> {
            engage(context)
            TagResult.ENGAGED
        }
    }

    fun describe(context: Context, result: TagResult): String = when (result) {
        TagResult.NOT_PAIRED -> "No tag paired yet. Open Tappy to pair one."
        TagResult.WRONG_TAG -> "This isn't your Tappy tag."
        TagResult.NO_APPS -> "Pick some apps to block in Tappy first."
        TagResult.SETUP_INCOMPLETE -> "Finish setup in Tappy first (blocker service and uninstall protection)."
        TagResult.ENGAGED -> TappyStore.state.value.autoUnlockAt.let { at ->
            if (at == 0L) "Blocked. Tap your tag to unlock." else "Blocked until ${formatUnlockTime(context, at)}. Tap your tag to unlock sooner."
        }
        TagResult.RELEASED -> "Unlocked. Your apps are back."
        TagResult.AUTO_RELEASED -> "Auto-unlock time passed. Your apps are back."
    }

    fun formatUnlockTime(context: Context, at: Long): String {
        val time = DateFormat.getTimeFormat(context).format(Date(at))
        return if (DateUtils.isToday(at)) time else "$time tomorrow"
    }

    /** The selection minus apps that may never be blocked (see [ProtectedApps]). */
    fun blockablePackages(context: Context): Set<String> =
        TappyStore.state.value.blockedPackages - ProtectedApps.packages(context)

    /** Ends the block if its auto-unlock time has passed. Returns true if it did. */
    fun checkAutoUnlock(context: Context): Boolean {
        if (!AutoUnlock.isDue(context)) return false
        release(context)
        return true
    }

    private fun engage(context: Context) {
        val s = TappyStore.state.value
        val now = System.currentTimeMillis()
        val autoUnlockAt = if (s.autoUnlockEnabled) AutoUnlock.nextOccurrence(now, s.autoUnlockMinuteOfDay) else 0L
        TappyStore.startBlock(now, SystemClock.elapsedRealtime(), AutoUnlock.bootCount(context), autoUnlockAt)
        applyOwnerPolicies(context, blocking = true)
        AutoUnlock.schedule(context)
    }

    private fun release(context: Context) {
        applyOwnerPolicies(context, blocking = false)
        TappyStore.endBlock()
        AutoUnlock.schedule(context)
    }

    fun emergencyRelease(context: Context): Boolean {
        if (!TappyStore.state.value.blocking) return false
        applyOwnerPolicies(context, blocking = false)
        val released = TappyStore.consumeEmergencyUnlock()
        AutoUnlock.schedule(context)
        return released
    }

    /** A block only starts if it can actually be enforced and Tappy can't simply be uninstalled. */
    fun isProtectionReady(context: Context): Boolean =
        isAdminActive(context) && (BlockerService.isEnabled(context) || isDeviceOwner(context))

    fun isDeviceOwner(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(context.packageName)

    fun isAdminActive(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java)
            .isAdminActive(TappyAdminReceiver.component(context))

    /**
     * As device owner Tappy can do this properly: suspend the blocked apps at the OS level,
     * hard-block its own uninstall and disable safe mode (which would otherwise bypass it).
     */
    private fun applyOwnerPolicies(context: Context, blocking: Boolean) {
        if (!isDeviceOwner(context)) return
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = TappyAdminReceiver.component(context)
        // Unsuspend the whole selection on release, in case the protected set changed mid-block.
        val packages = (if (blocking) blockablePackages(context) else TappyStore.state.value.blockedPackages).toTypedArray()
        try {
            dpm.setUninstallBlocked(admin, context.packageName, blocking)
            val failed = dpm.setPackagesSuspended(admin, packages, blocking)
            if (failed.isNotEmpty()) Log.w(TAG, "Could not (un)suspend: ${failed.joinToString()}")
            if (blocking) {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
            } else {
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Device owner policy failed", e)
        }
    }

    /** Lets the user hand back device-owner rights (only allowed while not blocking). */
    fun clearDeviceOwner(context: Context): Boolean {
        if (TappyStore.state.value.blocking || !isDeviceOwner(context)) return false
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        applyOwnerPolicies(context, blocking = false)
        @Suppress("DEPRECATION")
        dpm.clearDeviceOwnerApp(context.packageName)
        return true
    }

    /** Deactivates uninstall protection so the app can be removed (only allowed while not blocking). */
    fun deactivateAdmin(context: Context): Boolean {
        if (TappyStore.state.value.blocking || isDeviceOwner(context)) return false
        context.getSystemService(DevicePolicyManager::class.java)
            .removeActiveAdmin(TappyAdminReceiver.component(context))
        return true
    }
}
