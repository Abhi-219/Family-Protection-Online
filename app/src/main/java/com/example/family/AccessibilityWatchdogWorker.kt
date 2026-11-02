package com.abhi.familyprotection

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

class AccessibilityWatchdogWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val context = applicationContext
        val now = System.currentTimeMillis()
        val isLockActive = DnsPreferences.isActive(context) && now < DnsPreferences.getExpiryMillis(context)
        if (!isLockActive) return Result.success()

        if (BankingAccessibilityPause.isIntentionalPauseActive(context, now)) return Result.success()

        if (!AntiUninstallAccessibilityService.isEnabled(context)) {
            Log.w("AccessibilityWatchdog", "Watchdog detected accessibility disabled during active lock; restoring.")
            AppLogger.log(context, "[WATCHDOG_RESTORE] Accessibility service disabled externally; restoring via WorkManager", "Watchdog", "W")
            BankingAccessibilityPause.ensureServiceEnabled(context)
        }

        if (DnsForegroundService.instance == null || !DnsForegroundService.isRunning) {
            DnsForegroundService.start(context)
        }
        return Result.success()
    }
}

object WatchdogScheduler {
    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()
        val request = PeriodicWorkRequestBuilder<AccessibilityWatchdogWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "AccessibilityWatchdog",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancel(context: Context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork("AccessibilityWatchdog")
        } catch (_: Exception) {}
    }
}
