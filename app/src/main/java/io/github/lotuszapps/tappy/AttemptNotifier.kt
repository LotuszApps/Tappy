package io.github.lotuszapps.tappy

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** One quiet notification per blocked app, counting how often it was opened during this block. */
object AttemptNotifier {

    private const val CHANNEL_ID = "blocked_attempts"

    fun labelOf(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }

    fun describe(attempts: Int): String = when {
        attempts <= 1 -> "First try since you locked it."
        else -> "You've tried to open it $attempts times since you locked it."
    }

    fun show(context: Context, pkg: String, attempts: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Blocked attempts", NotificationManager.IMPORTANCE_LOW).apply {
                description = "How often you tried to open a blocked app"
                setShowBadge(false)
            }
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val label = labelOf(context, pkg)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_lock)
            .setContentTitle("$label: $attempts ${if (attempts == 1) "attempt" else "attempts"}")
            .setContentText(describe(attempts))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        // Same ID per app, so the count updates in place instead of stacking up.
        NotificationManagerCompat.from(context).notify(pkg.hashCode(), notification)
    }

    fun clear(context: Context) = NotificationManagerCompat.from(context).cancelAll()
}
