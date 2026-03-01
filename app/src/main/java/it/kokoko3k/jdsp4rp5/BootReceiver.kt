package it.kokoko3k.jdsp4rp5

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import it.kokoko3k.jdsp4rp5.R

fun getApplicationName(context: Context): String {
    val applicationInfo = context.applicationInfo
    val stringId = applicationInfo.labelRes
    return if (stringId == 0) applicationInfo.nonLocalizedLabel.toString() else context.getString(stringId)
}

class BootReceiver : BroadcastReceiver() {

    private val tag = "BootReceiver"
    private val jdspBootFailureCountKey = "jdspBootFailureCount"
    private val bootFailureThreshold = 3

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(tag, "BootReceiver.onReceive() called")

        if (intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            Log.d(tag, "Received LOCKED_BOOT_COMPLETED: skipping JDSP setup until BOOT_COMPLETED")
            return
        }

        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(tag, "Received BOOT_COMPLETED intent")

            val canPostNotifications =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
            if (!canPostNotifications) {
                Log.d(tag, "POST_NOTIFICATIONS not granted, continuing without notification")
            }

            //Enable JDSP at boot?
            val sharedPrefs = context.getSharedPreferences(JdspUtils.PREFS_NAME, Context.MODE_PRIVATE)
            val isJdspEnabledAtBoot = sharedPrefs.getBoolean(JdspUtils.JDSP_BOOT_ENABLED_KEY, false)
            val mediaOnly = sharedPrefs.getBoolean(JdspUtils.JDSP_MEDIA_ONLY_KEY, false)
            if (isJdspEnabledAtBoot) {
                if (canPostNotifications) {
                    createNotificationChannel(context)
                }
                Log.d(tag, "Enabling JamesDSP at boot...")
                val result = JdspUtils.enableJdsp(context, mediaOnly)
                if (!result.success) {
                    val failures = sharedPrefs.getInt(jdspBootFailureCountKey, 0) + 1
                    sharedPrefs.edit().putInt(jdspBootFailureCountKey, failures).apply()
                    Log.e(
                        tag,
                        "Boot JDSP enable failed (#$failures): exitCode=${result.exitCode}, state=${result.runtimeState}, log=${result.logPath}, err=${result.errorSummary}"
                    )
                    if (canPostNotifications) {
                        showFailureNotification(
                            context,
                            "JDSP boot enable failed ($failures/$bootFailureThreshold)."
                        )
                    }
                    if (failures >= bootFailureThreshold) {
                        sharedPrefs.edit()
                            .putBoolean(JdspUtils.JDSP_BOOT_ENABLED_KEY, false)
                            .putInt(jdspBootFailureCountKey, 0)
                            .apply()
                        Log.e(tag, "Auto-disabled boot autostart after repeated failures.")
                        if (canPostNotifications) {
                            showFailureNotification(
                                context,
                                "JDSP boot autostart disabled after repeated failures."
                            )
                        }
                    }
                } else {
                    sharedPrefs.edit().putInt(jdspBootFailureCountKey, 0).apply()
                    Log.d(tag, "Boot JDSP enable succeeded: state=${result.runtimeState}")
                    if (canPostNotifications) {
                        showNotification(context, "JDSP enabled at boot.")
                    }
                }
            } else {
                Log.d(tag, "Not enabling JamesDSP at boot...")
            }


        } else {
            Log.d(tag, "Intent received: ${intent.action}")
        }
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "boot_channel"
            val name = "Boot Notification Channel"
            val descriptionText = "Channel for boot notifications"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(tag, "Notify channel created")
        }
    }

    private fun showNotification(context: Context, text: String) {
        val builder = NotificationCompat.Builder(context, "boot_channel")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getApplicationName(context))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            with(NotificationManagerCompat.from(context)) {
                val notificationId = 123
                notify(notificationId, builder.build())
                Log.d(tag, "Notifica mostrata con ID: $notificationId")
            }
        } else {
            Log.d(tag, "Unable to show notification it seems permession POST_NOTIFICATIONS is missing")
        }
    }

    private fun showFailureNotification(context: Context, text: String) {
        val builder = NotificationCompat.Builder(context, "boot_channel")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getApplicationName(context))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            with(NotificationManagerCompat.from(context)) {
                val notificationId = 124
                notify(notificationId, builder.build())
            }
        }
    }
}
