package com.example.family

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

class DnsExpiryReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "DnsExpiryReceiver"
        const val ACTION_DEACTIVATE_DNS = "com.example.family.ACTION_DEACTIVATE_DNS"
        private const val REQUEST_CODE = 1001

        fun scheduleExpiryAlarm(context: Context, expiryMillis: Long) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, DnsExpiryReceiver::class.java).apply {
                action = ACTION_DEACTIVATE_DNS
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, expiryMillis, pendingIntent)
                } else {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, expiryMillis, pendingIntent)
                }
                Log.d(TAG, "Expiry alarm scheduled for $expiryMillis")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule alarm", e)
            }
        }

        fun cancelExpiryAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, DnsExpiryReceiver::class.java).apply {
                action = ACTION_DEACTIVATE_DNS
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
            Log.d(TAG, "Expiry alarm cancelled")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DEACTIVATE_DNS -> {
                Log.d(TAG, "Timer expired. Restoring previous DNS settings...")
                deactivateAndRestore(context)
                DnsForegroundService.stop(context)
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                Log.d(TAG, "Device rebooted (or locked boot). Checking DNS status...")
                val isActive = DnsPreferences.isActive(context)
                val expiryMillis = DnsPreferences.getExpiryMillis(context)
                val now = System.currentTimeMillis()

                if (isActive) {
                    if (now >= expiryMillis) {
                        deactivateAndRestore(context)
                        DnsForegroundService.stop(context)
                    } else {
                        // Restore DNS immediately
                        val currentDns = DnsPreferences.getDnsName(context)
                        if (currentDns.isNotEmpty()) {
                            DnsController.applyDns(context, currentDns)
                        }
                        scheduleExpiryAlarm(context, expiryMillis)
                        DnsForegroundService.start(context)
                    }
                }
            }
        }
    }

    private fun deactivateAndRestore(context: Context) {
        FamilyDeviceAdminReceiver.setUninstallBlocked(context, false)
        DnsController.deactivateAndRestorePrevious(context)
        val dnsName = DnsPreferences.getDnsName(context)
        val totalYears = DnsPreferences.getTotalYears(context)
        val totalDays = DnsPreferences.getTotalDays(context)
        val totalHours = DnsPreferences.getTotalHours(context)
        val totalMins = DnsPreferences.getTotalMinutes(context)
        DnsPreferences.saveDnsState(context, dnsName, totalYears, totalDays, totalHours, totalMins, false, 0L)
        DnsPreferences.clearPassword(context)
    }
}
