package com.abhi.familyprotection

import android.content.Context
import android.util.Log

object AccessibilityGuard {
    private const val TAG = "AccessibilityGuard"

    fun heal(ctx: Context, reason: String) {
        val now = System.currentTimeMillis()
        if (!DnsPreferences.isActive(ctx) || now >= DnsPreferences.getExpiryMillis(ctx)) return
        if (BankingAccessibilityPause.isIntentionalPauseActive(ctx, now)) return
        if (BankingAccessibilityPause.isAccessibilityEnabled(ctx)) return // already fine, no loop

        AppLogger.log(ctx, "[HEAL] Triggered by $reason", TAG, "W")
        Log.w(TAG, "Healing accessibility triggered by $reason")
        VigilanceManager.recordViolation(ctx, "Accessibility healed: $reason")
        BankingAccessibilityPause.ensureServiceEnabled(ctx)
    }
}
