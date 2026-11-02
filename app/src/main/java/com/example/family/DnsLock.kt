package com.abhi.familyprotection

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.UserManager
import android.util.Log

object DnsLock {
    private fun dpm(c: Context) = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private fun admin(c: Context) = FamilyDeviceAdminReceiver.getComponentName(c)

    fun lock(c: Context, host: String): Boolean {
        val policyManager = dpm(c)
        val adminComponent = admin(c)
        if (!policyManager.isDeviceOwnerApp(c.packageName)) {
            return false
        }
        try {
            policyManager.setGlobalSetting(adminComponent, "private_dns_mode", "hostname")
            policyManager.setGlobalSetting(adminComponent, "private_dns_specifier", host)

            listOf(
                UserManager.DISALLOW_CONFIG_PRIVATE_DNS,
                UserManager.DISALLOW_CONFIG_VPN,
                UserManager.DISALLOW_APPS_CONTROL
            ).forEach { restriction ->
                try {
                    policyManager.addUserRestriction(adminComponent, restriction)
                } catch (_: Exception) {}
            }
            return true
        } catch (e: Exception) {
            Log.e("DnsLock", "Failed to lock DNS via DevicePolicyManager", e)
            return false
        }
    }

    fun unlock(c: Context) {
        val policyManager = dpm(c)
        val adminComponent = admin(c)
        if (!policyManager.isDeviceOwnerApp(c.packageName)) return

        listOf(
            UserManager.DISALLOW_CONFIG_PRIVATE_DNS,
            UserManager.DISALLOW_CONFIG_VPN,
            UserManager.DISALLOW_APPS_CONTROL
        ).forEach { restriction ->
            try {
                policyManager.clearUserRestriction(adminComponent, restriction)
            } catch (_: Exception) {}
        }
        try {
            policyManager.setGlobalSetting(adminComponent, "private_dns_mode", "opportunistic")
            policyManager.setGlobalSetting(adminComponent, "private_dns_specifier", "")
        } catch (_: Exception) {}
    }
}
