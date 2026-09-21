package com.example.family

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FamilyDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "FamilyDeviceAdmin"

        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context, FamilyDeviceAdminReceiver::class.java)
        }

        fun isDeviceAdmin(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            return dpm?.isAdminActive(getComponentName(context)) == true
        }

        fun isDeviceOwner(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            return dpm?.isDeviceOwnerApp(context.packageName) == true
        }

        /**
         * Controls uninstalling only this Family app, not other apps on the device.
         */
        fun setUninstallBlocked(context: Context, blocked: Boolean) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
            val component = getComponentName(context)

            try {
                if (dpm.isDeviceOwnerApp(context.packageName)) {
                    // Remove the old system-wide restriction if a previous build added it.
                    dpm.clearUserRestriction(component, UserManager.DISALLOW_UNINSTALL_APPS)
                    // This affects only context.packageName.
                    dpm.setUninstallBlocked(component, context.packageName, blocked)
                    Log.d(TAG, "Family app uninstall blocked=$blocked; other apps remain uninstallable")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set hardware restrictions", e)
            }
        }
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence? {
        val isActive = DnsPreferences.isActive(context)
        val expiryMillis = DnsPreferences.getExpiryMillis(context)

        if (isActive && System.currentTimeMillis() < expiryMillis) {
            val formatter = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            return "⚠️ STRICT LOCK ACTIVE! Device settings remain restricted until ${formatter.format(Date(expiryMillis))}."
        }
        return null
    }
}
