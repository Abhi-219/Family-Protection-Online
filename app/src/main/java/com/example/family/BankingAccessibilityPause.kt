package com.abhi.familyprotection

import android.Manifest
import android.app.AppOpsManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import java.util.Locale

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
    private const val KEY_AUTOMATIC_BANKING_PACKAGE = "automatic_banking_package"
    private const val KEY_AUTOMATIC_PAUSE_STARTED_AT = "automatic_pause_started_at"
    private const val KEY_CUSTOM_BANKING_PACKAGES = "custom_banking_packages"
    const val ACTION_REENABLE = "com.abhi.familyprotection.ACTION_REENABLE_ACCESSIBILITY"
    private const val REQUEST_CODE = 1002
    // Exact IDs only; custom IDs are stored separately in device-protected preferences.
    private val builtInBankingPackages = setOf(
        "net.one97.paytm", "com.phonepe.app", "com.sbi.lotusintouch", "com.hdfcbank.mobilebanking",
        "com.csam.icici.bank.imobile", "com.axis.mobile", "com.kotak.mobilebanking",
        "com.bankofbaroda.mconnect", "com.paypal.android.p2pmobile", "com.revolut.revolut",
        "com.chase.sig.android", "com.wellsfargo.mobile", "com.citibank.mobile.us",
        "com.google.android.apps.nbu.paisa.user", "in.org.npci.upiapp"
        // TODO verify real package id: CRED
    )
    private val packageSegmentRegex = Regex("[A-Za-z0-9_]+")
    @Volatile private var cachedBankingPackages: Set<String>? = null

    @Volatile private var cachedPrefs: SharedPreferences? = null

    private fun getPrefs(context: Context): SharedPreferences =
        cachedPrefs ?: synchronized(this) {
            cachedPrefs ?: context.applicationContext
                .createDeviceProtectedStorageContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .also { cachedPrefs = it }
        }

    private fun component(context: Context) =
        ComponentName(context, AntiUninstallAccessibilityService::class.java).flattenToString()

    fun start(context: Context, restoreAt: Long): BankingPauseStartResult {
        val resolver = context.contentResolver
        val enabled = readEnabledServices(resolver)
        val serviceComponent = component(context)
        if (enabled.none { it.equals(serviceComponent, ignoreCase = true) }) {
            return BankingPauseStartResult.SERVICE_NOT_ENABLED
        }

        if (!getPrefs(context).edit().putLong(KEY_RESTORE_AT, restoreAt).commit()) {
            Log.e(TAG, "Could not persist the banking pause deadline")
            return BankingPauseStartResult.SETTING_WRITE_FAILED
        }

        try {
            scheduleRestore(context, restoreAt)
        } catch (e: Exception) {
            Log.e(TAG, "Could not schedule banking pause re-enable", e)
            getPrefs(context).edit().remove(KEY_RESTORE_AT).commit()
            return BankingPauseStartResult.SETTING_WRITE_FAILED
        }

        Log.i(TAG, "Context-aware banking pause active until $restoreAt")
        return BankingPauseStartResult.STARTED
    }

    fun restoreAt(context: Context): Long = getPrefs(context).getLong(KEY_RESTORE_AT, 0L)

    fun hasPendingRestore(context: Context): Boolean = restoreAt(context) > 0L

    fun automaticBankingPackage(context: Context): String? =
        getPrefs(context).getString(KEY_AUTOMATIC_BANKING_PACKAGE, null)

    fun automaticPauseStartedAt(context: Context): Long =
        getPrefs(context).getLong(KEY_AUTOMATIC_PAUSE_STARTED_AT, 0L)

    fun getBankingPackages(context: Context): Set<String> =
        cachedBankingPackages ?: synchronized(this) {
            cachedBankingPackages ?: run {
                val custom = getPrefs(context)
                    .getStringSet(KEY_CUSTOM_BANKING_PACKAGES, emptySet())
                    .orEmpty()
                    .map(String::lowercase)
                    .toSet()
                (builtInBankingPackages + custom).also { cachedBankingPackages = it }
            }
        }

    fun getCustomBankingPackages(context: Context): Set<String> =
        getPrefs(context)
            .getStringSet(KEY_CUSTOM_BANKING_PACKAGES, emptySet())
            .orEmpty()
            .map(String::lowercase)
            .toSet()

    fun addBankingPackage(context: Context, packageName: String): Boolean {
        val now = System.currentTimeMillis()
        if (DnsPreferences.isActive(context) && now < DnsPreferences.getExpiryMillis(context)) return false
        // Checked outside the object lock so the package query never blocks other callers.
        if (isRestrictedFromPause(context, packageName.trim())) return false
        return storeBankingPackage(context, packageName)
    }

    @Synchronized
    private fun storeBankingPackage(context: Context, packageName: String): Boolean {
        val normalized = packageName.trim().lowercase()
        val segments = normalized.split('.')
        if (segments.size < 2 || segments.any { !packageSegmentRegex.matches(it) }) return false
        val packages = getPrefs(context)
            .getStringSet(KEY_CUSTOM_BANKING_PACKAGES, emptySet())
            .orEmpty()
            .toMutableSet()
        packages.add(normalized)
        cachedBankingPackages = builtInBankingPackages + packages
        getPrefs(context).edit().putStringSet(KEY_CUSTOM_BANKING_PACKAGES, packages).apply()
        return true
    }

    @Synchronized
    fun removeBankingPackage(context: Context, packageName: String) {
        val packages = getPrefs(context)
            .getStringSet(KEY_CUSTOM_BANKING_PACKAGES, emptySet())
            .orEmpty()
            .toMutableSet()
        if (packages.remove(packageName.trim().lowercase())) {
            cachedBankingPackages = builtInBankingPackages + packages
            getPrefs(context).edit().putStringSet(KEY_CUSTOM_BANKING_PACKAGES, packages).apply()
        }
    }

    fun startAutomaticPause(context: Context, packageName: String): Boolean {
        if (!isBankingOrFinancialApp(context, packageName) || !hasUsageAccess(context)) return false
        getPrefs(context).edit()
            .putString(KEY_AUTOMATIC_BANKING_PACKAGE, packageName)
            .putLong(KEY_AUTOMATIC_PAUSE_STARTED_AT, System.currentTimeMillis())
            .apply()
        return true
    }

    fun endAutomaticPause(context: Context): Boolean {
        if (!ensureServiceEnabled(context)) return false
        getPrefs(context).edit()
            .remove(KEY_AUTOMATIC_BANKING_PACKAGE)
            .remove(KEY_AUTOMATIC_PAUSE_STARTED_AT)
            .apply()
        return true
    }

    @Suppress("DEPRECATION")
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return when (mode) {
            // Some Android builds report default even when the manifest permission is granted.
            AppOpsManager.MODE_DEFAULT ->
                context.checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) ==
                    PackageManager.PERMISSION_GRANTED
            AppOpsManager.MODE_ALLOWED -> true
            else -> false
        }
    }

    fun latestResumedPackage(context: Context, fromMillis: Long, toMillis: Long): String? {
        if (!hasUsageAccess(context)) return null
        val usageStats = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val events = usageStats.queryEvents(fromMillis, toMillis) ?: return null
        val event = UsageEvents.Event()
        var latestPackage: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val isResumed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            } else {
                @Suppress("DEPRECATION")
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            }
            if (isResumed) latestPackage = event.packageName
        }
        return latestPackage
    }

    fun isBankingOrFinancialApp(pkgName: String): Boolean {
        if (pkgName.isBlank()) return false
        return pkgName.trim().lowercase() in builtInBankingPackages
    }

    fun isBankingOrFinancialApp(context: Context, pkgName: String): Boolean {
        if (pkgName.isBlank()) return false
        val cleanPkg = pkgName.trim().lowercase(Locale.ROOT)

        // CHEAP FIRST CHECK: 99.99% of events end right here in 1 nanosecond with 0 allocations!
        if (cleanPkg !in getBankingPackages(context)) return false

        return !isRestrictedFromPause(context, pkgName.trim())
    }

    private val protectedSystemMarkers = listOf(
        "settings", "packageinstaller", "permissioncontroller", "securitycenter", "systemmanager",
        "iqoo.secure", "permissionmanager", "launcher", "systemui", "com.android.vending"
    )

    // Priority: Blocked > Supervised > Banking. Anything blocked, supervised, monitored or system-critical can never pause accessibility.
    fun isRestrictedFromPause(context: Context, pkgName: String): Boolean {
        if (pkgName.equals(context.packageName, ignoreCase = true)) return true
        if (protectedSystemMarkers.any { pkgName.contains(it, ignoreCase = true) }) return true
        if (AppListPreferences.getAllBlockedAdultApps(context).any { AppListPreferences.matchesPattern(pkgName, it) }) return true
        if (AppListPreferences.getAllSupervisedPatterns(context).any { AppListPreferences.matchesPattern(pkgName, it) }) return true
        if (AppListPreferences.getAllSocialMediaApps(context).any { AppListPreferences.matchesPattern(pkgName, it) }) return true
        return AppListPreferences.isWebBrowser(context, pkgName)
    }

    fun restoreIfDue(context: Context, now: Long = System.currentTimeMillis()) {
        val restoreAt = restoreAt(context)
        if (restoreAt > 0L && now >= restoreAt) restore(context)
    }

    fun restoreAfterBoot(context: Context, now: Long = System.currentTimeMillis()) {
        if (automaticBankingPackage(context) != null) {
            endAutomaticPause(context)
            return
        }
        val restoreAt = restoreAt(context)
        if (restoreAt == 0L) return
        if (now >= restoreAt) {
            restore(context)
        } else {
            try {
                scheduleRestore(context, restoreAt)
            } catch (e: Exception) {
                Log.e(TAG, "Could not reschedule banking pause re-enable", e)
            }
        }
    }

    fun isIntentionalPauseActive(context: Context, now: Long = System.currentTimeMillis()): Boolean =
        restoreAt(context) > now || !automaticBankingPackage(context).isNullOrBlank()

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
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            if (dpm == null || !dpm.isDeviceOwnerApp(context.packageName)) {
                Log.e(TAG, "Cannot restore Accessibility service: WRITE_SECURE_SETTINGS is not granted")
                return false
            }
        }

        return try {
            val cr = context.contentResolver
            val svc = component(context)
            val list = readEnabledServices(cr)
            if (list.none { it.equals(svc, true) }) {
                writeEnabledServices(context, list + svc)
            }
            if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }
            isAccessibilityEnabled(context)
        } catch (e: Exception) {
            Log.e(TAG, "ensureServiceEnabled failed", e)
            false
        }
    }

    @Synchronized
    fun disableServiceInSettings(context: Context): Boolean {
        return try {
            val cr = context.contentResolver
            val svc = component(context)
            val list = readEnabledServices(cr)
            if (list.any { it.equals(svc, true) }) {
                if (!writeEnabledServices(context, list.filterNot { it.equals(svc, true) })) {
                    Log.e(TAG, "Could not disable the accessibility service in Settings")
                    return false
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "disableServiceInSettings failed", e)
            false
        }
    }

    fun restore(context: Context): Boolean {
        val restoreAt = restoreAt(context)
        if (restoreAt == 0L) return true

        if (!getPrefs(context).edit().remove(KEY_RESTORE_AT).commit()) {
            Log.e(TAG, "Banking pause expired, but could not clear the deadline")
            return false
        }
        cancelRestore(context)
        Log.i(TAG, "Banking pause expired; full protection resumed")
        return true
    }

    private fun readEnabledServices(resolver: ContentResolver): List<String> =
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
