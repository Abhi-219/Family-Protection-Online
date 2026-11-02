package com.abhi.familyprotection

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class DnsForegroundService : Service() {
    @Volatile private var serviceIsRunning = false
    private var dnsObserverRegistered = false
    private var accessibilityObserverRegistered = false
    private var screenReceiverRegistered = false
    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var hyperAlertJob: Job? = null
    private var bankingMonitorJob: Job? = null

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    hyperAlertJob?.cancel()
                    bankingMonitorJob?.cancel()
                    serviceScope.launch {
                        VigilanceWatchdogAlarm.cancel(applicationContext)
                        val bankingPackage =
                            BankingAccessibilityPause.automaticBankingPackage(applicationContext)
                        if (bankingPackage != null) {
                            val restored = BankingAccessibilityPause.endAutomaticPause(applicationContext)
                            AppLogger.log(
                                applicationContext,
                                "[BANKING] screen off pkg=$bankingPackage accessibilityRestored=$restored",
                                "DnsForegroundService",
                                if (restored) "I" else "E"
                            )
                            if (!DnsPreferences.isActive(applicationContext)) stopSelf()
                        }
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    serviceScope.launch {
                        restoreAccessibilityIfTampered()
                        enforceDns()
                    }
                    serviceScope.launch {
                        syncVigilanceState()
                        ensureBankingForegroundMonitor()
                    }
                }
            }
        }
    }
    
    // Track last enforcement to prevent spam
    private var lastEnforcementTime = 0L
    private val ENFORCEMENT_COOLDOWN = 1000L // 1 second cooldown
    private val enforcementPending = AtomicBoolean(false)
    
    private val dnsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            serviceScope.launch { enforceDns() }
        }
    }

    private val accessibilitySettingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            serviceScope.launch { restoreAccessibilityIfTampered() }
        }
    }

    // Also run on screen-on: OEM task killers (Vivo iManager) can disable the service while the screen is off.
    private fun restoreAccessibilityIfTampered() {
        val now = System.currentTimeMillis()
        if (!DnsPreferences.isActive(applicationContext) ||
            now >= DnsPreferences.getExpiryMillis(applicationContext) ||
            BankingAccessibilityPause.isIntentionalPauseActive(applicationContext, now) ||
            BankingAccessibilityPause.isAccessibilityEnabled(applicationContext)
        ) {
            return
        }

        Log.w("DnsForegroundService", "Accessibility was disabled during protection; restoring")
        VigilanceManager.recordViolation(applicationContext, "Accessibility disabled externally")
        if (!BankingAccessibilityPause.ensureServiceEnabled(applicationContext)) {
            AppLogger.log(
                applicationContext,
                "[SELF_RESTORE_FAILED] Could not restore Accessibility service",
                "DnsForegroundService",
                "E"
            )
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 888
        private const val CHANNEL_ID = "dns_service_channel"
        @Volatile var instance: DnsForegroundService? = null
        val isRunning: Boolean
            get() = instance?.serviceIsRunning == true

        fun start(context: Context): Boolean {
            return try {
                val intent = Intent(context, DnsForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (e: Exception) {
                Log.e("DnsForegroundService", "Failed to start foreground service", e)
                false
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DnsForegroundService::class.java))
        }

        fun updateVigilanceState(context: Context) {
            instance?.syncVigilanceState() ?: start(context)
        }
    }

    fun syncVigilanceState() {
        val context = applicationContext
        if (!DnsPreferences.isActive(context)) {
            hyperAlertJob?.cancel()
            VigilanceWatchdogAlarm.cancel(context)
            return
        }

        val powerManager = getSystemService(POWER_SERVICE) as? PowerManager
        if (powerManager?.isInteractive == false) {
            hyperAlertJob?.cancel()
            VigilanceWatchdogAlarm.cancel(context)
            return
        }

        val tier = VigilanceManager.getCurrentTier(context)
        Log.d("DnsForegroundService", "Current Vigilance Tier: $tier")

        when (tier) {
            VigilanceTier.HYPER_ALERT -> {
                VigilanceWatchdogAlarm.cancel(context)
                if (hyperAlertJob?.isActive != true) {
                    hyperAlertJob = serviceScope.launch {
                        while (isActive) {
                            val power = getSystemService(POWER_SERVICE) as? PowerManager
                            if (power?.isInteractive == false) break

                            if (VigilanceManager.getCurrentTier(applicationContext) != VigilanceTier.HYPER_ALERT) {
                                syncVigilanceState()
                                break
                            }

                            enforceDns()
                            delay(60_000L) // 60s in-memory loop
                        }
                    }
                }
            }
            VigilanceTier.ELEVATED -> {
                hyperAlertJob?.cancel()
                VigilanceWatchdogAlarm.schedule(context, 60_000L) // 1m window alarm
            }
            VigilanceTier.STANDARD -> {
                hyperAlertJob?.cancel()
                VigilanceWatchdogAlarm.schedule(context, 300_000L) // 5m window alarm
            }
            VigilanceTier.DORMANT -> {
                hyperAlertJob?.cancel()
                VigilanceWatchdogAlarm.cancel(context) // Dormant calm
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        ensureServiceScopeActive()
        if (!serviceIsRunning) {
            serviceIsRunning = true
            createNotificationChannel()
            val bankingPauseActive =
                BankingAccessibilityPause.automaticBankingPackage(applicationContext) != null
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(
                    if (bankingPauseActive) "Banking App Compatibility Active"
                    else "Family DNS Guard Active"
                )
                .setContentText(
                    if (bankingPauseActive) {
                        "Accessibility will resume when you leave the banking app."
                    } else {
                        "DNS and Uninstall protection is currently locked."
                    }
                )
                .setSmallIcon(R.drawable.app_logo)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .build()

            startForeground(NOTIFICATION_ID, notification)
            
            if (!dnsObserverRegistered) {
                contentResolver.registerContentObserver(
                    Settings.Global.getUriFor("private_dns_mode"),
                    true,
                    dnsObserver
                )
                contentResolver.registerContentObserver(
                    Settings.Global.getUriFor("private_dns_specifier"),
                    true,
                    dnsObserver
                )
                dnsObserverRegistered = true
            }
            if (!accessibilityObserverRegistered) {
                contentResolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                    false,
                    accessibilitySettingsObserver
                )
                contentResolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
                    false,
                    accessibilitySettingsObserver
                )
                accessibilityObserverRegistered = true
            }
            if (!screenReceiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                }
                registerReceiver(screenStateReceiver, filter)
                screenReceiverRegistered = true
            }
            
            serviceScope.launch { enforceDns() }
            syncVigilanceState()
        }
        ensureBankingForegroundMonitor()
        return START_STICKY
    }

    private fun ensureBankingForegroundMonitor() {
        if (bankingMonitorJob?.isActive == true) return
        val bankPackage = BankingAccessibilityPause.automaticBankingPackage(applicationContext) ?: return
        if (!BankingAccessibilityPause.hasUsageAccess(applicationContext)) {
            Log.e("DnsForegroundService", "Usage Access was revoked during an automatic banking pause")
            val restored = BankingAccessibilityPause.endAutomaticPause(applicationContext)
            AppLogger.log(
                applicationContext,
                "[BANKING] usage access revoked pkg=$bankPackage accessibilityRestored=$restored",
                "DnsForegroundService",
                if (restored) "W" else "E"
            )
            return
        }

        bankingMonitorJob = serviceScope.launch {
            // Start from the pause time so stale foreground events cannot immediately restore the service.
            var lastQueryTime = BankingAccessibilityPause.automaticPauseStartedAt(applicationContext)
                .takeIf { it > 0L } ?: System.currentTimeMillis()
            var foregroundPackage = bankPackage
            var nonBankingForegroundSince: Long? = null
            var usageCheckCounter = 0
            while (isActive) {
                if (usageCheckCounter++ % 5 == 0) {
                    if (!BankingAccessibilityPause.hasUsageAccess(applicationContext)) {
                        Log.e("DnsForegroundService", "Usage Access was revoked; restoring accessibility")
                        val restored = BankingAccessibilityPause.endAutomaticPause(applicationContext)
                        AppLogger.log(
                            applicationContext,
                            "[BANKING] usage access revoked pkg=$bankPackage accessibilityRestored=$restored",
                            "DnsForegroundService",
                            if (restored) "W" else "E"
                        )
                        break
                    }
                }

                val now = System.currentTimeMillis()
                val resumedPackage = try {
                    BankingAccessibilityPause.latestResumedPackage(
                        applicationContext,
                        lastQueryTime,
                        now
                    )
                } catch (e: Exception) {
                    Log.e("DnsForegroundService", "Could not read foreground app usage events", e)
                    AppLogger.log(
                        applicationContext,
                        "[BANKING] foreground query failed pkg=$bankPackage error=${e.javaClass.simpleName}",
                        "DnsForegroundService",
                        "E"
                    )
                    null
                }
                lastQueryTime = now
                var restrictedForeground = false
                if (resumedPackage != null) {
                    foregroundPackage = resumedPackage
                    if (BankingAccessibilityPause.isBankingOrFinancialApp(applicationContext, foregroundPackage)) {
                        nonBankingForegroundSince = null
                    } else {
                        if (nonBankingForegroundSince == null) nonBankingForegroundSince = now
                        restrictedForeground =
                            BankingAccessibilityPause.isRestrictedFromPause(applicationContext, foregroundPackage)
                    }
                }
                val leftBankingAppAt = nonBankingForegroundSince
                // Blocked/supervised apps must not inherit the banking grace period.
                if (leftBankingAppAt != null && (restrictedForeground || now - leftBankingAppAt >= 2_000L)) {
                    if (BankingAccessibilityPause.endAutomaticPause(applicationContext)) {
                        Log.i(
                            "DnsForegroundService",
                            "Accessibility restored after leaving banking app $bankPackage"
                        )
                        AppLogger.log(
                            applicationContext,
                            "[BANKING] restored pkg=$bankPackage foreground=$foregroundPackage " +
                                "nonBankingDurationMs=${now - leftBankingAppAt}",
                            "DnsForegroundService",
                            "I"
                        )
                        if (!DnsPreferences.isActive(applicationContext)) stopSelf()
                        break
                    }
                    Log.e("DnsForegroundService", "Could not restore accessibility after banking app")
                    AppLogger.log(
                        applicationContext,
                        "[BANKING] restore failed pkg=$bankPackage foreground=$foregroundPackage",
                        "DnsForegroundService",
                        "E"
                    )
                }
                delay(2000L)
            }
        }
    }

    private suspend fun enforceDns() {
        if (!enforcementPending.compareAndSet(false, true)) return
        try {
            val waitMillis = ENFORCEMENT_COOLDOWN - (System.currentTimeMillis() - lastEnforcementTime)
            if (waitMillis > 0) delay(waitMillis)
            lastEnforcementTime = System.currentTimeMillis()

            val currentTime = lastEnforcementTime
            val context = applicationContext
            val isLockActive = DnsPreferences.isActive(context)
            val expiryMillis = DnsPreferences.getExpiryMillis(context)
            val dnsName = DnsPreferences.getDnsName(context)

            if (isLockActive && currentTime < expiryMillis) {
                try {
                    val sysMode = Settings.Global.getString(contentResolver, "private_dns_mode")
                    val sysSpecifier = Settings.Global.getString(contentResolver, "private_dns_specifier")

                    if (sysMode != "hostname" || sysSpecifier != dnsName) {
                        if (DnsPreferences.isDnsLockConfigured(context)) {
                            VigilanceManager.recordViolation(context, "Private DNS tampered")
                        }
                        val result = DnsController.applyDns(context, dnsName)
                        if (result.isSuccess) {
                            Log.d("DnsForegroundService", "DNS restored to $dnsName")
                        } else {
                            Log.e(
                                "DnsForegroundService",
                                "Could not restore Private DNS: ${result.exceptionOrNull()?.message}"
                            )
                        }
                    } else {
                        DnsPreferences.setDnsLockConfigured(context, true)
                    }
                } catch (e: Exception) {
                    Log.e("DnsForegroundService", "DNS enforcement failed", e)
                    AppLogger.log(
                        context,
                        "[DNS_ENFORCEMENT_ERROR] ${e.message ?: e.javaClass.simpleName}",
                        "DnsForegroundService",
                        "E"
                    )
                }
            } else if (isLockActive && currentTime >= expiryMillis) {
                DnsController.deactivateAndRestorePrevious(context)
                FamilyDeviceAdminReceiver.setUninstallBlocked(context, false)
                DnsPreferences.saveDnsState(context, dnsName, "0", "7", "0", "0", false, 0L)
                DnsPreferences.clearPassword(context)
                VigilanceWatchdogAlarm.cancel(context)
                WatchdogScheduler.cancel(context)
                stopSelf()
            }
        } finally {
            enforcementPending.set(false)
        }
    }

    private fun ensureServiceScopeActive() {
        if (serviceScope.coroutineContext[Job]?.isActive != true) {
            serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID, "DNS Protection Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        instance = null
        hyperAlertJob?.cancel()
        bankingMonitorJob?.cancel()
        if (screenReceiverRegistered) {
            try { unregisterReceiver(screenStateReceiver) } catch (_: Exception) {}
            screenReceiverRegistered = false
        }
        if (dnsObserverRegistered) {
            contentResolver.unregisterContentObserver(dnsObserver)
            dnsObserverRegistered = false
        }
        if (accessibilityObserverRegistered) {
            contentResolver.unregisterContentObserver(accessibilitySettingsObserver)
            accessibilityObserverRegistered = false
        }
        serviceScope.cancel()
        serviceIsRunning = false
        enforcementPending.set(false)
        super.onDestroy()
    }
}
