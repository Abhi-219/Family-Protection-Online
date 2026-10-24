package com.abhi.familyprotection

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log

enum class BankingPauseStartResult {
    STARTED,
    COOLDOWN_ACTIVE,
    SERVICE_NOT_ENABLED,
    WRITE_PERMISSION_MISSING,
    SETTING_WRITE_FAILED
}

object BankingAccessibilityPause {
    private const val TAG = "BankingAccessibilityPause"
    private const val PREF_NAME = "banking_accessibility_pause"
    private const val KEY_RESTORE_AT = "restore_at"
    const val ACTION_REENABLE = "com.abhi.familyprotection.ACTION_REENABLE_ACCESSIBILITY"
    private const val REQUEST_CODE = 1002

    private fun getPrefs(context: Context) =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun component(context: Context) =
        ComponentName(context, AntiUninstallAccessibilityService::class.java).flattenToString()

    fun start(context: Context, restoreAt: Long): BankingPauseStartResult {
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            return BankingPauseStartResult.WRITE_PERMISSION_MISSING
        }

        val resolver = context.contentResolver
        val enabled = readEnabledServices(resolver)
        val serviceComponent = component(context)
        if (enabled.none { it.equals(serviceComponent, ignoreCase = true) }) {
            return BankingPauseStartResult.SERVICE_NOT_ENABLED
        }

        if (!getPrefs(context).edit().putLong(KEY_RESTORE_AT, restoreAt).commit()) {
            Log.e(TAG, "Could not persist the accessibility restore deadline")
            return BankingPauseStartResult.SETTING_WRITE_FAILED
        }

        try {
            scheduleRestore(context, restoreAt)
        } catch (e: Exception) {
            Log.e(TAG, "Could not schedule accessibility re-enable", e)
            getPrefs(context).edit().remove(KEY_RESTORE_AT).commit()
            return BankingPauseStartResult.SETTING_WRITE_FAILED
        }

        return try {
            if (writeEnabledServices(context, enabled.filterNot {
                    it.equals(serviceComponent, ignoreCase = true)
                }) && readEnabledServices(resolver).none {
                    it.equals(serviceComponent, ignoreCase = true)
                }) {
                Log.i(TAG, "Accessibility service disabled until $restoreAt")
                BankingPauseStartResult.STARTED
            } else {
                Log.e(TAG, "Android did not remove the accessibility service from enabled services")
                restore(context)
                BankingPauseStartResult.SETTING_WRITE_FAILED
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not disable the accessibility service", e)
            restore(context)
            BankingPauseStartResult.SETTING_WRITE_FAILED
        }
    }

    fun restoreAt(context: Context): Long = getPrefs(context).getLong(KEY_RESTORE_AT, 0L)

    fun hasPendingRestore(context: Context): Boolean = restoreAt(context) > 0L

    fun restoreIfDue(context: Context, now: Long = System.currentTimeMillis()) {
        val restoreAt = restoreAt(context)
        if (restoreAt > 0L && now >= restoreAt) restore(context)
    }

    fun restoreAfterBoot(context: Context, now: Long = System.currentTimeMillis()) {
        val restoreAt = restoreAt(context)
        if (restoreAt == 0L) return
        if (now >= restoreAt) {
            restore(context)
        } else {
            try {
                scheduleRestore(context, restoreAt)
            } catch (e: Exception) {
                Log.e(TAG, "Could not reschedule accessibility re-enable", e)
            }
        }
    }

    fun isIntentionalPauseActive(context: Context, now: Long = System.currentTimeMillis()): Boolean =
        restoreAt(context) > now

    fun isServiceEnabled(context: Context): Boolean {
        val serviceComponent = component(context)
        return readEnabledServices(context.contentResolver).any {
            it.equals(serviceComponent, ignoreCase = true)
        }
    }

    fun isAccessibilityEnabled(context: Context): Boolean =
        isServiceEnabled(context) && isAccessibilityGloballyEnabled(context)

    private fun isAccessibilityGloballyEnabled(context: Context): Boolean =
        Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        ) == 1

    @Synchronized
    fun ensureServiceEnabled(context: Context): Boolean {
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Cannot restore Accessibility service: WRITE_SECURE_SETTINGS is not granted")
            return false
        }

        return try {
            val current = readEnabledServices(context.contentResolver)
            val serviceComponent = component(context)
            val wasServiceEnabled = current.any { it.equals(serviceComponent, ignoreCase = true) }
            if (!wasServiceEnabled && !writeEnabledServices(context, current + serviceComponent)) {
                Log.e(TAG, "Android did not re-enable the Accessibility service")
                return false
            }
            if (!isAccessibilityGloballyEnabled(context) &&
                !Settings.Secure.putInt(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    1
                )
            ) {
                Log.e(TAG, "Could not enable Android Accessibility services")
                return false
            }
            if (!isServiceEnabled(context) || !isAccessibilityGloballyEnabled(context)) {
                Log.e(TAG, "Android did not enable the Accessibility service")
                return false
            }
            Log.w(TAG, "Accessibility service re-enabled while protection is active")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied while re-enabling the Accessibility service", e)
            false
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not re-enable the Accessibility service", e)
            false
        }
    }

    fun restore(context: Context): Boolean {
        val restoreAt = restoreAt(context)
        if (restoreAt == 0L) return true

        if (!ensureServiceEnabled(context)) {
            Log.e(TAG, "Could not re-enable Accessibility; scheduled retry remains active")
            return false
        }
        if (!getPrefs(context).edit().remove(KEY_RESTORE_AT).commit()) {
            Log.e(TAG, "Accessibility was re-enabled, but could not clear the restore deadline")
            return false
        }
        cancelRestore(context)
        Log.i(TAG, "Accessibility service re-enabled")
        return true
    }

    private fun readEnabledServices(resolver: android.content.ContentResolver): List<String> =
        Settings.Secure.getString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty().split(':').filter { it.isNotBlank() }

    private fun writeEnabledServices(context: Context, services: List<String>): Boolean =
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            services.distinct().joinToString(":")
        )

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, BankingAccessibilityPauseReceiver::class.java).apply {
            action = ACTION_REENABLE
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun scheduleRestore(context: Context, restoreAt: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: error("AlarmManager is unavailable")
        val pendingIntent = pendingIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            alarmManager.canScheduleExactAlarms()
        ) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                restoreAt,
                pendingIntent
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                restoreAt,
                pendingIntent
            )
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, restoreAt, pendingIntent)
        }
    }

    private fun cancelRestore(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(pendingIntent(context))
    }
}

class BankingAccessibilityPauseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            BankingAccessibilityPause.ACTION_REENABLE ->
                BankingAccessibilityPause.restoreIfDue(context)
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED ->
                BankingAccessibilityPause.restoreAfterBoot(context)
        }
    }
}
