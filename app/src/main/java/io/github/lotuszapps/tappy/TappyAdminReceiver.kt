package io.github.lotuszapps.tappy

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * An active device admin cannot be uninstalled until it is deactivated, and the
 * deactivation screen is guarded by [BlockerService] while a block is active.
 * If Tappy is device owner, [BlockController] additionally hard-blocks uninstalls.
 */
class TappyAdminReceiver : DeviceAdminReceiver() {

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence? =
        if (TappyStore.state.value.blocking) context.getString(R.string.admin_disable_warning) else null

    companion object {
        fun component(context: Context) = ComponentName(context, TappyAdminReceiver::class.java)
    }
}
