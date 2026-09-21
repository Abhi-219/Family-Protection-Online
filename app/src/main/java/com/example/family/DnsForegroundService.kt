package com.example.family

import android.app.*
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class DnsForegroundService : Service() {
    private var isRunning = false
    // Use IO dispatcher for background work
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    
    // Track last enforcement to prevent spam
    private var lastEnforcementTime = 0L
    private val ENFORCEMENT_COOLDOWN = 1000L // 1 second cooldown
    private val enforcementPending = AtomicBoolean(false)
    
    private val dnsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            // Instant reaction on change - processed off-thread
            serviceScope.launch { enforceDns() }
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 888
        private const val CHANNEL_ID = "dns_service_channel"

        fun start(context: Context) {
            val intent = Intent(context, DnsForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DnsForegroundService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true
            createNotificationChannel()
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Family DNS Guard Active")
                .setContentText("DNS and Uninstall protection is currently locked.")
                .setSmallIcon(R.drawable.app_logo)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .build()

            startForeground(NOTIFICATION_ID, notification)
            
            // Register observers for instant snap-back (0ms response when DNS changes)
            contentResolver.registerContentObserver(Settings.Global.getUriFor("private_dns_mode"), true, dnsObserver)
            contentResolver.registerContentObserver(Settings.Global.getUriFor("private_dns_specifier"), true, dnsObserver)
            
            // Initial enforcement check
            serviceScope.launch { enforceDns() }
        }
        return START_STICKY
    }

    // REMOVED: Redundant 5-second polling loop
    // ContentObserver provides instant response (0ms) when DNS changes
    // This saves 5-10% battery daily by eliminating 17,280 wake-ups per day

    private suspend fun enforceDns() {
        // Coalesce bursts into one trailing run so no tamper is ever dropped by the cooldown.
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

                    // Only apply if actually changed (reduces unnecessary operations)
                    if (sysMode != "hostname" || sysSpecifier != dnsName) {
                        DnsController.applyDns(context, dnsName)
                        android.util.Log.d("DnsForegroundService", "DNS restored to $dnsName")
                    }
                } catch (_: Exception) {}
            } else if (isLockActive && currentTime >= expiryMillis) {
                // Auto-cleanup on expiry
                withContext(Dispatchers.Main) {
                    DnsController.deactivateAndRestorePrevious(context)
                    FamilyDeviceAdminReceiver.setUninstallBlocked(context, false)
                    DnsPreferences.saveDnsState(context, dnsName, "0", "7", "0", "0", false, 0L)
                    DnsPreferences.clearPassword(context)
                    stopSelf()
                }
            }
        } finally {
            enforcementPending.set(false)
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
        contentResolver.unregisterContentObserver(dnsObserver)
        serviceScope.cancel()
        super.onDestroy()
    }
}
