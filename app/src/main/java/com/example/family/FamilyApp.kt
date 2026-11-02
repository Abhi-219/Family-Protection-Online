package com.abhi.familyprotection

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FamilyApp : Application() {
    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            try {
                AppLogger.init(applicationContext)
                if (DnsPreferences.isActive(applicationContext)) {
                    FamilyDeviceAdminReceiver.setUninstallBlocked(applicationContext, true)
                }
            } catch (_: Exception) {}
        }
    }
}
