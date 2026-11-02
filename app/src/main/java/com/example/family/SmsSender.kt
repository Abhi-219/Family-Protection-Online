package com.abhi.familyprotection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Sends an immediate SMS alert to the configured guardian phone number
 * whenever an adult keyword or blocked adult app is detected.
 */
object SmsSender {

    private const val TAG = "SmsSender"

    /** The guardian phone number that receives all alert SMS messages. */
    const val GUARDIAN_NUMBER = "+919836567559"
    private const val SMS_COOLDOWN_MS = 5 * 60 * 1000L
    private val smsCooldownMap = ConcurrentHashMap<String, Long>()

    /**
     * Sends an SMS alert reporting that an adult keyword was detected.
     */
    fun sendKeywordAlert(
        context: Context,
        appName: String,
        packageName: String,
        keyword: String
    ) {
        val now = System.currentTimeMillis()
        val lastSms = smsCooldownMap[packageName] ?: 0L
        if (now - lastSms < SMS_COOLDOWN_MS) {
            Log.d(TAG, "SMS alert for $packageName suppressed (cooldown active)")
            return
        }
        smsCooldownMap[packageName] = now

        val message = buildString {
            append("[ALERT] Family Protection Alert\n")
            append("App: $appName\n")
            append("Package: $packageName\n")
            append("Adult keyword detected: $keyword\n")
            append("Action: Blocked & screen locked.\n")

            val currentTime = LocalDateTime.now()
            val formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss")
            append("Time: ${currentTime.format(formatter)}\n")
        }
        sendSms(context, message)
    }

    /**
     * Sends an SMS alert reporting that a blocked adult app was launched.
     */
    fun sendBlockedAppAlert(
        context: Context,
        appName: String,
        packageName: String
    ) {
        val now = System.currentTimeMillis()
        val lastSms = smsCooldownMap[packageName] ?: 0L
        if (now - lastSms < SMS_COOLDOWN_MS) {
            Log.d(TAG, "SMS alert for $packageName suppressed (cooldown active)")
            return
        }
        smsCooldownMap[packageName] = now

        val message = buildString {
            append("[ALERT] Family Protection Alert\n")
            append("Blocked adult app launched: $appName\n")
            append("Package: $packageName\n")
            append("Action: App blocked & screen locked (5-min lockout applied).\n")
            
            val currentTime = LocalDateTime.now()
            val formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss")
            append("Time: ${currentTime.format(formatter)}\n")
        }
        sendSms(context, message)
    }

    /**
     * Resolves a human-readable app label from a package name.
     * Falls back to the package name itself if resolution fails.
     */
    fun resolveAppName(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    // ── Internal ────────────────────────────────────────────────────────────

    private fun sendSms(context: Context, message: String) {
        if (!DnsPreferences.areSmsAlertsEnabled(context)) {
            val logMsg = "SMS alert skipped because user consent is off"
            Log.d(TAG, logMsg)
            AppLogger.log(context, logMsg, TAG, "W")
            return
        }
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "SMS alert skipped because SEND_SMS permission is not granted")
            return
        }

        try {
            @Suppress("DEPRECATION")
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            val parts = smsManager.divideMessage(message)
            if (parts.size == 1) {
                smsManager.sendTextMessage(GUARDIAN_NUMBER, null, message, null, null)
            } else {
                smsManager.sendMultipartTextMessage(GUARDIAN_NUMBER, null, parts, null, null)
            }
            Log.i(TAG, "Alert SMS sent to $GUARDIAN_NUMBER")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS alert", e)
        }
    }
}
