package io.github.lotusz.tappy

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import android.provider.Settings
import android.telecom.TelecomManager

/**
 * Apps that must never be blocked: blocking them would hide incoming calls or ringing alarms,
 * or make the phone hard to recover (Settings, the home screen). Resolved per device so it
 * covers OEM dialers and clocks.
 */
object ProtectedApps {

    fun packages(context: Context): Set<String> {
        val pm = context.packageManager
        val result = mutableSetOf(context.packageName, "com.android.settings", "com.android.phone")

        context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let(result::add)

        listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(AlarmClock.ACTION_SET_ALARM),
            Intent(AlarmClock.ACTION_SHOW_ALARMS),
            Intent(Settings.ACTION_SETTINGS),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
        ).forEach { intent ->
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .mapTo(result) { it.activityInfo.packageName }
        }
        return result
    }
}
