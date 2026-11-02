package com.abhi.familyprotection

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

class VigilanceWatchdogAlarm : BroadcastReceiver() {
    companion object {
        private const val TAG = "VigilanceWatchdogAlarm"
        const val ACTION_CHECK_VIGILANCE = "com.abhi.familyprotection.ACTION_CHECK_VIGILANCE"
        private const val REQUEST_CODE = 1003

        fun schedule(context: Context, windowMillis: Long) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager?.isInteractive == false) return // Screen OFF -> no alarm

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, VigilanceWatchdogAlarm::class.java).apply {
                action = ACTION_CHECK_VIGILANCE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            try {
                val triggerAt = SystemClock.elapsedRealtime() + windowMillis
                // Use ELAPSED_REALTIME with setWindow for power-efficient, tamper-proof OS batching while screen is ON
                alarmManager.setWindow(
                    AlarmManager.ELAPSED_REALTIME,
                    triggerAt,
                    windowMillis / 2,
                    pendingIntent
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule vigilance watchdog alarm", e)
            }
        }

        fun cancel(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, VigilanceWatchdogAlarm::class.java).apply {
                action = ACTION_CHECK_VIGILANCE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CHECK_VIGILANCE -> {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (powerManager?.isInteractive == false) return

                val now = System.currentTimeMillis()
                val isLockActive = DnsPreferences.isActive(context) && now < DnsPreferences.getExpiryMillis(context)
                if (isLockActive) {
                    // 1. Verify and Restore Private DNS
                    val dnsName = DnsPreferences.getDnsName(context)
                    if (dnsName.isNotBlank()) {
                        val sysMode = Settings.Global.getString(context.contentResolver, "private_dns_mode")
                        val sysSpecifier = Settings.Global.getString(context.contentResolver, "private_dns_specifier")
                        if (sysMode != "hostname" || sysSpecifier != dnsName) {
                            Log.w(TAG, "Watchdog restored Private DNS to $dnsName")
                            VigilanceManager.recordViolation(context, "Private DNS tampered externally")
                            DnsController.applyDns(context, dnsName)
                        }
                    }

                    // 2. Verify and Restore Accessibility Service
                    if (!BankingAccessibilityPause.isIntentionalPauseActive(context, now)) {
                        if (!AntiUninstallAccessibilityService.isEnabled(context)) {
                            Log.w(TAG, "Watchdog restored Accessibility Service")
                            VigilanceManager.recordViolation(context, "Accessibility service disabled externally")
                            BankingAccessibilityPause.ensureServiceEnabled(context)
                        }
                        if (DnsForegroundService.instance == null || !DnsForegroundService.isRunning) {
                            DnsForegroundService.start(context)
                        } else {
                            DnsForegroundService.instance?.syncVigilanceState()
                        }
                    }

                }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                if (DnsPreferences.isActive(context)) {
                    if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                        WatchdogScheduler.schedule(context)
                    }
                    DnsForegroundService.updateVigilanceState(context)
                }
            }
        }
    }
}
