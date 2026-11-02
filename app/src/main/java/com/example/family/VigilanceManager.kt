package com.abhi.familyprotection

import android.content.Context
import android.util.Log

enum class VigilanceTier {
    HYPER_ALERT,
    ELEVATED,
    STANDARD,
    DORMANT
}

object VigilanceManager {
    private const val TAG = "VigilanceManager"
    private const val THIRTY_MIN_MS = 30 * 60 * 1000L
    private const val TWO_HOURS_MS = 2 * 60 * 60 * 1000L
    private const val TWENTY_FOUR_HOURS_MS = 24 * 60 * 60 * 1000L

    fun recordViolation(context: Context, reason: String) {
        // Without a lock, tamper attempts are logged but must not prime the next lock into Hyper-Alert.
        if (!DnsPreferences.isActive(context)) {
            AppLogger.log(context, "[VIGILANCE_VIOLATION] Reason: $reason | No active lock; not escalating", TAG, "I")
            return
        }
        val now = System.currentTimeMillis()
        DnsPreferences.recordViolationTimestamp(context, now)
        Log.w(TAG, "Security violation recorded ($reason). Escalating to Hyper-Alert!")
        AppLogger.log(context, "[VIGILANCE_VIOLATION] Reason: $reason | Escalating to Hyper-Alert", TAG, "W")
        DnsForegroundService.updateVigilanceState(context)
    }

    fun getCurrentTier(context: Context): VigilanceTier {
        val lastViolation = DnsPreferences.getLastViolationTimestamp(context)
        if (lastViolation == 0L) return VigilanceTier.STANDARD

        val elapsed = System.currentTimeMillis() - lastViolation
        return if (elapsed < THIRTY_MIN_MS) {
            VigilanceTier.HYPER_ALERT
        } else if (elapsed < TWO_HOURS_MS) {
            VigilanceTier.ELEVATED
        } else if (elapsed < TWENTY_FOUR_HOURS_MS) {
            VigilanceTier.STANDARD
        } else {
            VigilanceTier.DORMANT
        }
    }
}
