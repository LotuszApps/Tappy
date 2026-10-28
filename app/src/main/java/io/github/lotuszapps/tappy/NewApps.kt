package io.github.lotuszapps.tappy

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Apps installed during a lock: either blocked straight away, or offered with a notification
 * whose "Add to blocked apps" button blocks them (see [TappyStore.State.autoBlockNewApps]).
 */
object NewApps {

    private const val CHANNEL_ID = "new_apps"
    private const val ACTION_ADD = "io.github.lotuszapps.tappy.ADD_BLOCKED_APP"
    private const val EXTRA_PACKAGE = "package"

    /** Listens for installs while the blocker service runs. Updates of existing apps are ignored. */
    fun watch(context: Context): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                intent.data?.schemeSpecificPart?.let { onInstalled(context, it) }
            }
        }
        val filter = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        return receiver
    }

    private fun onInstalled(context: Context, pkg: String) {
        val state = TappyStore.state.value
        if (!state.blocking || pkg in state.blockedPackages) return
        // Only apps you can open from the launcher; protected ones (dialer, clock...) never.
        if (context.packageManager.getLaunchIntentForPackage(pkg) == null) return
        if (pkg in ProtectedApps.packages(context)) return

        val label = AttemptNotifier.labelOf(context, pkg)
        if (state.autoBlockNewApps) {
            BlockController.addBlockedApp(context, pkg)
            notify(context, pkg, "$label is blocked", "It was installed during a lock, so it's blocked until you tap your tag.", withAction = false)
        } else {
            notify(context, pkg, "New app: $label", "Installed during a lock. Add it to your blocked apps?", withAction = true)
        }
    }

    fun add(context: Context, pkg: String) {
        BlockController.addBlockedApp(context, pkg)
        NotificationManagerCompat.from(context).cancel(notificationId(pkg))
    }

    private fun notify(context: Context, pkg: String, title: String, text: String, withAction: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "New apps during a lock", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Apps installed while blocking, so you can add them to the block"
            }
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_lock)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
        if (withAction) {
            val add = PendingIntent.getBroadcast(
                context,
                notificationId(pkg),
                Intent(context, AddBlockedAppReceiver::class.java)
                    .setAction(ACTION_ADD)
                    .putExtra(EXTRA_PACKAGE, pkg),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "Add to blocked apps", add)
        }
        NotificationManagerCompat.from(context).notify(notificationId(pkg), builder.build())
    }

    // Offset so it never collides with the attempt-counter notification for the same app.
    private fun notificationId(pkg: String) = pkg.hashCode() xor 0x5EED

    /** Handles the notification's "Add to blocked apps" button. */
    class AddBlockedAppReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_ADD) return
            intent.getStringExtra(EXTRA_PACKAGE)?.let { add(context, it) }
        }
    }
}
