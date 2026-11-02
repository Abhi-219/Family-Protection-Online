package com.abhi.familyprotection

import com.abhi.familyprotection.ui.theme.FamilyTheme

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abhi.familyprotection.ui.theme.FamilyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.io.IOException
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MainActivity : ComponentActivity() {
    private var isForeground by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FamilyTheme {
                DnsControlScreen(isForeground)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        isForeground = true
    }

    override fun onStop() {
        super.onStop()
        isForeground = false
    }
}

object DnsPreferences {
    private const val PREF_NAME = "dns_settings"
    private const val KEY_DNS_NAME = "dns_name"
    const val DEFAULT_DNS_HOSTNAME = "family.adguard-dns.com"
    private const val KEY_TOTAL_YEARS = "total_years"
    private const val KEY_TOTAL_DAYS = "total_days"
    private const val KEY_TOTAL_HOURS = "total_hours"
    private const val KEY_TOTAL_MINS = "total_mins"
    private const val KEY_IS_ACTIVE = "is_active"
    private const val KEY_DNS_LOCK_CONFIGURED = "dns_lock_configured"
    private const val KEY_EXPIRY_MILLIS = "expiry_millis"
    private const val KEY_PREV_MODE = "prev_dns_mode"
    private const val KEY_PREV_SPECIFIER = "prev_dns_specifier"
    private const val KEY_LOCK_PASSWORD = "lock_password"
    private const val KEY_HAS_PASSWORD = "has_password"
    private const val KEY_INSTALL_TIME = "install_timestamp"
    private const val KEY_SCAN_PAUSE_UNTIL = "scan_pause_until"
    private const val KEY_SMS_ALERTS_ENABLED = "sms_alerts_enabled"
    private const val KEY_BANKING_PAUSE_UNTIL = "banking_pause_until"
    private const val KEY_BANKING_PAUSE_COOLDOWN_UNTIL = "banking_pause_cooldown_until"
    private const val PASSWORD_KEY_ALIAS = "dns_lock_password_key"
    private const val ENCRYPTED_PASSWORD_PREFIX = "enc:v1:"
    private const val GCM_IV_LENGTH = 12

    fun areSmsAlertsEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_SMS_ALERTS_ENABLED, false)

    fun setSmsAlertsEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SMS_ALERTS_ENABLED, enabled).apply()
    }

    fun getBankingPauseUntil(context: Context): Long =
        getPrefs(context).getLong(KEY_BANKING_PAUSE_UNTIL, 0L)

    fun getBankingPauseCooldownUntil(context: Context): Long =
        getPrefs(context).getLong(KEY_BANKING_PAUSE_COOLDOWN_UNTIL, 0L)

    fun isBankingPauseActive(context: Context): Boolean =
        System.currentTimeMillis() < getBankingPauseUntil(context) &&
            !AntiUninstallAccessibilityService.isEnabled(context)

    fun isOnBankingPauseCooldown(context: Context): Boolean =
        System.currentTimeMillis() < getBankingPauseCooldownUntil(context)

    @Synchronized
    fun startBankingPause(context: Context): BankingPauseStartResult {
        val now = System.currentTimeMillis()
        val cooldownUntil = getBankingPauseCooldownUntil(context)
        if (now < cooldownUntil || BankingAccessibilityPause.hasPendingRestore(context)) {
            return BankingPauseStartResult.COOLDOWN_ACTIVE
        }
        val pauseUntil = now + (5 * 60 * 1000L) // 5 minutes
        val newCooldown = pauseUntil + (12 * 60 * 60 * 1000L) // 12 hours cooldown after pause
        val pausePrefs = getPrefs(context)
        if (!pausePrefs.edit()
            .putLong(KEY_BANKING_PAUSE_UNTIL, pauseUntil)
            .putLong(KEY_BANKING_PAUSE_COOLDOWN_UNTIL, newCooldown)
            .commit()
        ) {
            return BankingPauseStartResult.SETTING_WRITE_FAILED
        }

        val startResult = BankingAccessibilityPause.start(context, pauseUntil)
        if (startResult != BankingPauseStartResult.STARTED) {
            pausePrefs.edit()
                .remove(KEY_BANKING_PAUSE_UNTIL)
                .remove(KEY_BANKING_PAUSE_COOLDOWN_UNTIL)
                .commit()
        }
        return startResult
    }

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    // Read on every accessibility event, so the device-protected context wrapper is built once.
    private fun getPrefs(context: Context): SharedPreferences =
        cachedPrefs ?: synchronized(this) {
            cachedPrefs ?: context.applicationContext
                .createDeviceProtectedStorageContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .also { cachedPrefs = it }
        }

    fun saveDnsState(
        context: Context,
        dnsName: String,
        totalYears: String,
        totalDays: String,
        totalHours: String,
        totalMins: String,
        isActive: Boolean,
        expiryMillis: Long
    ) {
        val editor = getPrefs(context).edit()
            .putString(KEY_DNS_NAME, dnsName)
            .putString(KEY_TOTAL_YEARS, totalYears)
            .putString(KEY_TOTAL_DAYS, totalDays)
            .putString(KEY_TOTAL_HOURS, totalHours)
            .putString(KEY_TOTAL_MINS, totalMins)
            .putBoolean(KEY_IS_ACTIVE, isActive)
            .putLong(KEY_EXPIRY_MILLIS, expiryMillis)
        
        if (isActive) {
            val now = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
            editor.putString(KEY_INSTALL_TIME, now)
        } else {
            editor.putBoolean(KEY_DNS_LOCK_CONFIGURED, false)
        }
        
        editor.apply()
    }

    fun isDnsLockConfigured(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_DNS_LOCK_CONFIGURED, false)

    fun setDnsLockConfigured(context: Context, configured: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DNS_LOCK_CONFIGURED, configured).apply()
    }

    fun getInstallTimestamp(context: Context): String =
        getPrefs(context).getString(KEY_INSTALL_TIME, "Never") ?: "Never"

    fun getAppInstallTime(context: Context): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
            formatter.format(Date(packageInfo.lastUpdateTime))
        } catch (_: Exception) {
            "Unknown"
        }
    }
    
    fun savePreviousDns(context: Context, mode: String, specifier: String) {
        val pref = getPrefs(context)
        if (!pref.contains(KEY_PREV_MODE)) {
            pref.edit()
                .putString(KEY_PREV_MODE, mode)
                .putString(KEY_PREV_SPECIFIER, specifier)
                .apply()
        }
    }

    fun getPreviousDnsMode(context: Context): String =
        getPrefs(context).getString(KEY_PREV_MODE, "opportunistic") ?: "opportunistic"

    fun getPreviousDnsSpecifier(context: Context): String =
        getPrefs(context).getString(KEY_PREV_SPECIFIER, "") ?: ""

    fun clearPreviousDns(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_PREV_MODE)
            .remove(KEY_PREV_SPECIFIER)
            .apply()
    }

    fun savePassword(context: Context, password: String) {
        val encryptedPassword = encryptPassword(password)
        check(getPrefs(context).edit()
            .putString(KEY_LOCK_PASSWORD, encryptedPassword)
            .putBoolean(KEY_HAS_PASSWORD, true)
            .commit()) { "Unable to save encrypted lock password" }
    }

    fun getPassword(context: Context): String {
        val prefs = getPrefs(context)
        val storedPassword = prefs.getString(KEY_LOCK_PASSWORD, "") ?: ""
        if (storedPassword.isEmpty()) return ""
        if (storedPassword.startsWith(ENCRYPTED_PASSWORD_PREFIX)) {
            return decryptPassword(storedPassword.removePrefix(ENCRYPTED_PASSWORD_PREFIX))
        }

        val encryptedPassword = encryptPassword(storedPassword)
        check(prefs.edit().putString(KEY_LOCK_PASSWORD, encryptedPassword).commit()) {
            "Unable to migrate plaintext lock password to encrypted storage"
        }
        return storedPassword
    }

    fun hasPassword(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_HAS_PASSWORD, false)

    fun clearPassword(context: Context) {
        check(getPrefs(context).edit()
            .remove(KEY_LOCK_PASSWORD)
            .remove(KEY_HAS_PASSWORD)
            .commit()) { "Unable to clear lock password" }
    }

    private fun encryptPassword(password: String): String {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreatePasswordKey())
            val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            val payload = cipher.iv + encrypted
            ENCRYPTED_PASSWORD_PREFIX + android.util.Base64.encodeToString(
                payload,
                android.util.Base64.NO_WRAP
            )
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException("Unable to encrypt lock password with Android Keystore", e)
        } catch (e: IOException) {
            throw IllegalStateException("Unable to load Android Keystore", e)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("Unable to encrypt lock password with Android Keystore", e)
        }
    }

    private fun decryptPassword(payload: String): String {
        return try {
            val bytes = android.util.Base64.decode(payload, android.util.Base64.NO_WRAP)
            require(bytes.size > GCM_IV_LENGTH) { "Encrypted password payload is invalid" }
            val iv = bytes.copyOfRange(0, GCM_IV_LENGTH)
            val encrypted = bytes.copyOfRange(GCM_IV_LENGTH, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreatePasswordKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException("Unable to decrypt lock password with Android Keystore", e)
        } catch (e: IOException) {
            throw IllegalStateException("Unable to load Android Keystore", e)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("Unable to decrypt lock password with Android Keystore", e)
        }
    }

    private fun getOrCreatePasswordKey(): SecretKey {
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(PASSWORD_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                PASSWORD_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return keyGenerator.generateKey()
    }

    /** Suspends adult-content scanning only; DNS and uninstall protection stay armed. */
    fun pauseContentScanning(context: Context, untilMillis: Long) {
        getPrefs(context).edit().putLong(KEY_SCAN_PAUSE_UNTIL, untilMillis).apply()
    }

    fun getContentScanPauseUntil(context: Context): Long =
        getPrefs(context).getLong(KEY_SCAN_PAUSE_UNTIL, 0L)

    fun getDnsName(context: Context): String =
        getPrefs(context).getString(KEY_DNS_NAME, DEFAULT_DNS_HOSTNAME) ?: DEFAULT_DNS_HOSTNAME

    fun getTotalYears(context: Context): String =
        getPrefs(context).getString(KEY_TOTAL_YEARS, "0") ?: "0"

    fun getTotalDays(context: Context): String =
        getPrefs(context).getString(KEY_TOTAL_DAYS, "0") ?: "0"

    fun getTotalHours(context: Context): String =
        getPrefs(context).getString(KEY_TOTAL_HOURS, "0") ?: "0"

    fun getTotalMinutes(context: Context): String =
        getPrefs(context).getString(KEY_TOTAL_MINS, "3") ?: "3"

    fun isActive(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_IS_ACTIVE, false)

    fun getExpiryMillis(context: Context): Long =
        getPrefs(context).getLong(KEY_EXPIRY_MILLIS, 0L)

    private const val KEY_LAST_VIOLATION_TIME = "last_violation_timestamp"

    fun recordViolationTimestamp(context: Context, timestamp: Long = System.currentTimeMillis()) {
        getPrefs(context).edit().putLong(KEY_LAST_VIOLATION_TIME, timestamp).apply()
    }

    fun getLastViolationTimestamp(context: Context): Long =
        getPrefs(context).getLong(KEY_LAST_VIOLATION_TIME, 0L)
}

object DnsController {
    fun applyDns(context: Context, hostname: String): Result<Unit> {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        return try {
            if (dpm != null && dpm.isDeviceOwnerApp(context.packageName)) {
                val success = DnsLock.lock(context, hostname)
                if (success) {
                    DnsPreferences.setDnsLockConfigured(context, true)
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Failed to apply Device Owner DNS lock"))
                }
            } else {
                val modeUpdated = Settings.Global.putString(
                    context.contentResolver,
                    "private_dns_mode",
                    "hostname"
                )
                val specifierUpdated = Settings.Global.putString(
                    context.contentResolver,
                    "private_dns_specifier",
                    hostname
                )
                if (!modeUpdated || !specifierUpdated) {
                    return Result.failure(Exception("Could not apply Private DNS settings"))
                }
                DnsPreferences.setDnsLockConfigured(context, true)
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun activateWithPriority(context: Context, hostname: String): Result<Unit> {
        return try {
            val currentMode = Settings.Global.getString(context.contentResolver, "private_dns_mode") ?: "opportunistic"
            val currentSpecifier = Settings.Global.getString(context.contentResolver, "private_dns_specifier") ?: ""

            DnsPreferences.savePreviousDns(context, currentMode, currentSpecifier)
            applyDns(context, hostname)
        } catch (e: SecurityException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun deactivateAndRestorePrevious(context: Context): Result<Unit> {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        return try {
            if (dpm != null && dpm.isDeviceOwnerApp(context.packageName)) {
                DnsLock.unlock(context)
            } else {
                val prevMode = DnsPreferences.getPreviousDnsMode(context)
                val prevSpecifier = DnsPreferences.getPreviousDnsSpecifier(context)
                Settings.Global.putString(context.contentResolver, "private_dns_mode", prevMode)
                Settings.Global.putString(context.contentResolver, "private_dns_specifier", prevSpecifier)
            }
            DnsPreferences.clearPreviousDns(context)
            DnsPreferences.setDnsLockConfigured(context, false)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

data class DnsProvider(val name: String, val hostname: String, val description: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsControlScreen(isForeground: Boolean = true) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val dateFormatter = remember { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()) }
    val appInstallTime = remember { DnsPreferences.getAppInstallTime(context) }

    var dnsName by remember { mutableStateOf(DnsPreferences.getDnsName(context)) }
    var expanded by remember { mutableStateOf(false) }
    var totalYears by remember { mutableStateOf(DnsPreferences.getTotalYears(context)) }
    var totalDays by remember { mutableStateOf(DnsPreferences.getTotalDays(context)) }
    var totalHours by remember { mutableStateOf(DnsPreferences.getTotalHours(context)) }
    var totalMinutes by remember { mutableStateOf(DnsPreferences.getTotalMinutes(context)) }

    var isActive by remember { mutableStateOf(DnsPreferences.isActive(context)) }
    var expiryMillis by remember { mutableLongStateOf(DnsPreferences.getExpiryMillis(context)) }
    val lastActivation = remember(isActive) { DnsPreferences.getInstallTimestamp(context) }

    // Password states
    var usePasswordToggle by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    var hasPasswordActive by remember { mutableStateOf(DnsPreferences.hasPassword(context)) }

    var selectedMainTab by remember { mutableStateOf(0) } // 0 = DNS Protection, 1 = Accessibility Protection
    var selectedSettingsPage by remember { mutableStateOf<SettingsPage?>(null) }
    val drawerState = androidx.compose.material3.rememberDrawerState(DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    var isBankingPaused by remember { mutableStateOf(DnsPreferences.isBankingPauseActive(context)) }
    var hasBankingUsageAccess by remember { mutableStateOf(BankingAccessibilityPause.hasUsageAccess(context)) }
    var showBankingAppPicker by remember { mutableStateOf(false) }
    var isLoadingBankingApps by remember { mutableStateOf(false) }
    var installedBankingCandidates by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var customBankingPackages by remember {
        mutableStateOf(BankingAccessibilityPause.getCustomBankingPackages(context))
    }
    var bankingPauseRemainingSec by remember { mutableLongStateOf(maxOf(0L, (DnsPreferences.getBankingPauseUntil(context) - System.currentTimeMillis()) / 1000L)) }
    var isOnBankingCooldown by remember { mutableStateOf(DnsPreferences.isOnBankingPauseCooldown(context)) }
    var bankingCooldownRemainingSec by remember { mutableLongStateOf(maxOf(0L, (DnsPreferences.getBankingPauseCooldownUntil(context) - System.currentTimeMillis()) / 1000L)) }

    fun launchBankingAppWithAccessibilityPaused(packageName: String) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            Toast.makeText(context, "Could not find a launch activity for this app.", Toast.LENGTH_LONG).show()
            return
        }
        if (!BankingAccessibilityPause.isBankingOrFinancialApp(context, packageName)) {
            Toast.makeText(context, "Select this app as a banking app first.", Toast.LENGTH_LONG).show()
            return
        }
        if (!BankingAccessibilityPause.hasUsageAccess(context)) {
            Toast.makeText(context, "Enable Usage Access before opening banking apps.", Toast.LENGTH_LONG).show()
            return
        }

        if (!BankingAccessibilityPause.isServiceEnabled(context)) {
            try {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
            } catch (e: Exception) {
                Log.e("MainActivity", "Could not launch banking app $packageName", e)
                Toast.makeText(context, "Could not open the banking app.", Toast.LENGTH_LONG).show()
            }
            return
        }

        if (!BankingAccessibilityPause.startAutomaticPause(context, packageName)) {
            Toast.makeText(context, "Could not prepare the banking accessibility pause.", Toast.LENGTH_LONG).show()
            return
        }
        if (!DnsForegroundService.start(context)) {
            BankingAccessibilityPause.endAutomaticPause(context)
            Toast.makeText(context, "Could not start the banking foreground monitor.", Toast.LENGTH_LONG).show()
            return
        }
        if (!BankingAccessibilityPause.disableServiceInSettings(context)) {
            BankingAccessibilityPause.endAutomaticPause(context)
            if (!DnsPreferences.isActive(context)) DnsForegroundService.stop(context)
            Toast.makeText(context, "Could not pause accessibility for the banking app.", Toast.LENGTH_LONG).show()
            return
        }

        coroutineScope.launch {
            var serviceDisabled = false
            var attempts = 0
            while (!serviceDisabled && attempts < 20) {
                serviceDisabled = !BankingAccessibilityPause.isServiceEnabled(context)
                if (!serviceDisabled) {
                    delay(50L)
                }
                attempts++
            }
            if (!serviceDisabled) {
                BankingAccessibilityPause.endAutomaticPause(context)
                if (!DnsPreferences.isActive(context)) DnsForegroundService.stop(context)
                Toast.makeText(context, "Accessibility did not turn off; banking app was not opened.", Toast.LENGTH_LONG).show()
                return@launch
            }

            try {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
            } catch (e: Exception) {
                Log.e("MainActivity", "Could not launch banking app $packageName", e)
                BankingAccessibilityPause.endAutomaticPause(context)
                if (!DnsPreferences.isActive(context)) DnsForegroundService.stop(context)
                Toast.makeText(context, "Could not open the banking app.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Dialog states
    var showPermissionDialog by remember { mutableStateOf(false) }
    var showLockConfirmDialog by remember { mutableStateOf(false) }
    var showUnlockPasswordDialog by remember { mutableStateOf(false) }
    var showAccessibilityHelpDialog by remember { mutableStateOf(false) }
    var showBankingPausePasswordDialog by remember { mutableStateOf(false) }
    var unlockPasswordAttempt by remember { mutableStateOf("") }
    var bankingPasswordAttempt by remember { mutableStateOf("") }
    var passwordErrorText by remember { mutableStateOf<String?>(null) }
    var bankingPasswordErrorText by remember { mutableStateOf<String?>(null) }

    // Protection flags
    var isDeviceAdmin by remember { mutableStateOf(FamilyDeviceAdminReceiver.isDeviceAdmin(context)) }
    var isDeviceOwner by remember { mutableStateOf(FamilyDeviceAdminReceiver.isDeviceOwner(context)) }
    var isAccessibilityEnabled by remember { mutableStateOf(AntiUninstallAccessibilityService.isEnabled(context)) }

    var currentTimeMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Duration calculation helpers
    val yInt = totalYears.toLongOrNull() ?: 0L
    val dInt = totalDays.toLongOrNull() ?: 0L
    val hInt = totalHours.toLongOrNull() ?: 0L
    val mInt = totalMinutes.toLongOrNull() ?: 0L
    val totalDurationMillis = (yInt * 365 * 24 * 60 * 60 * 1000L) + (dInt * 24 * 60 * 60 * 1000L) + (hInt * 60 * 60 * 1000L) + (mInt * 60 * 1000L)

    val durationLabel = buildString {
        if (yInt > 0) append("${yInt}y ")
        if (dInt > 0) append("${dInt}d ")
        if (hInt > 0) append("${hInt}h ")
        if (mInt > 0) append("${mInt}m")
        if (isEmpty()) append("0m")
    }.trim()

    // Live countdown and timer updates (pure memory calculation, no IPC overhead)
    LaunchedEffect(isForeground) {
        if (isForeground) {
            isDeviceAdmin = FamilyDeviceAdminReceiver.isDeviceAdmin(context)
            isDeviceOwner = FamilyDeviceAdminReceiver.isDeviceOwner(context)
            isAccessibilityEnabled = AntiUninstallAccessibilityService.isEnabled(context)
            while (true) {
                val now = System.currentTimeMillis()
                currentTimeMillis = now
                hasBankingUsageAccess = BankingAccessibilityPause.hasUsageAccess(context)
                val bankingUntil = DnsPreferences.getBankingPauseUntil(context)
                val bankingCooldown = DnsPreferences.getBankingPauseCooldownUntil(context)
                isBankingPaused = now < bankingUntil && !isAccessibilityEnabled
                bankingPauseRemainingSec = maxOf(0L, (bankingUntil - now) / 1000L)
                isOnBankingCooldown = now < bankingCooldown
                bankingCooldownRemainingSec = maxOf(0L, (bankingCooldown - now) / 1000L)

                if (currentTimeMillis >= expiryMillis && expiryMillis > 0L) {
                    isActive = false
                    hasPasswordActive = false
                    DnsForegroundService.stop(context)
                    VigilanceWatchdogAlarm.cancel(context)
                    WatchdogScheduler.cancel(context)
                }

                delay(1000L)
            }
        }
    }

    // Periodic permission/service status check on IO dispatcher to avoid main thread binder contention
    LaunchedEffect(isForeground) {
        if (isForeground) {
            while (true) {
                val admin = withContext(Dispatchers.IO) {
                    FamilyDeviceAdminReceiver.isDeviceAdmin(context)
                }
                val owner = withContext(Dispatchers.IO) {
                    FamilyDeviceAdminReceiver.isDeviceOwner(context)
                }
                val accessibility = withContext(Dispatchers.IO) {
                    AntiUninstallAccessibilityService.isEnabled(context)
                }
                isDeviceAdmin = admin
                isDeviceOwner = owner
                isAccessibilityEnabled = accessibility
                delay(5000L)
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    "Settings",
                    modifier = Modifier.padding(16.dp),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                NavigationDrawerItem(
                    label = { Text("Protection") },
                    selected = selectedSettingsPage == null,
                    onClick = {
                        selectedSettingsPage = null
                        coroutineScope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                SettingsPage.values().forEach { page ->
                    NavigationDrawerItem(
                        label = { Text(page.title) },
                        selected = selectedSettingsPage == page,
                        onClick = {
                            selectedSettingsPage = page
                            coroutineScope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
        }
    ) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    TextButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                        Text("☰", fontSize = 20.sp)
                    }
                },
                title = {
                    Text(
                        selectedSettingsPage?.let { "Settings · ${it.title}" } ?: "Family DNS & Uninstall Guard",
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        // Pinned Bottom Bar: ALWAYS 100% visible regardless of scroll or password toggle!
        bottomBar = {
            if (selectedSettingsPage == null) {
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (selectedMainTab == 0) {
                        // --- Part 1: DNS Protection Bottom Bar Actions ---
                        if (!isActive) {
                            Button(
                                onClick = {
                                    val trimmedDns = dnsName.trim()

                                    if (trimmedDns.isEmpty()) {
                                        Toast.makeText(context, "Please enter a valid DNS hostname", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    if (totalDurationMillis <= 0L) {
                                        Toast.makeText(context, "Please set at least 1 minute duration", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    if (usePasswordToggle && passwordInput.trim().isEmpty()) {
                                        Toast.makeText(context, "Please enter a password or disable the password toggle", Toast.LENGTH_LONG).show()
                                        return@Button
                                    }

                                    showLockConfirmDialog = true
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Lock & Activate DNS for $durationLabel", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            if (hasPasswordActive) {
                                Button(
                                    onClick = {
                                        unlockPasswordAttempt = ""
                                        passwordErrorText = null
                                        showUnlockPasswordDialog = true
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("🔑 Unlock / Deactivate DNS with Password", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFFE0E0E0), shape = RoundedCornerShape(12.dp))
                                        .padding(vertical = 14.dp, horizontal = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "🔒 DNS Locked for Whole Period",
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF616161),
                                        fontSize = 14.sp,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }

                        // Emergency Restore Button (DNS Tab)
                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch(Dispatchers.IO) {
                                    DnsController.deactivateAndRestorePrevious(context)
                                    withContext(Dispatchers.Main) {
                                        FamilyDeviceAdminReceiver.setUninstallBlocked(context, true)
                                        Toast.makeText(context, "System restrictions cleared and settings restored. App uninstall remains blocked.", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            enabled = !isActive,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color.Red,
                                disabledContentColor = Color.LightGray
                            )
                        ) {
                            Text("⚠️ Stuck? Force Restore System Settings", fontSize = 12.sp)
                        }
                    } else {
                        // --- Part 2: Accessibility Bottom Bar Actions (15 Mins Pause) ---
                        if (isBankingPaused) {
                            val mins = bankingPauseRemainingSec / 60L
                            val secs = bankingPauseRemainingSec % 60L
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        "⚡ Accessibility Paused (${mins}m ${secs}s left)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = Color(0xFF2E7D32)
                                    )
                                    Text(
                                        "Accessibility will automatically re-enable when the timer ends.",
                                        fontSize = 11.sp,
                                        color = Color.DarkGray
                                    )
                                }
                            }
                        } else if (isOnBankingCooldown) {
                            val hours = bankingCooldownRemainingSec / 3600L
                            val mins = (bankingCooldownRemainingSec % 3600L) / 60L
                            val secs = bankingCooldownRemainingSec % 60L
                            val cooldownText = if (hours > 0) "${hours}h ${mins}m" else "${mins}m ${secs}s"
                            Text(
                                "This cooldown affects only the manual emergency pause. Automatic banking-app detection remains active.",
                                fontSize = 12.sp,
                                color = Color.DarkGray
                            )
                            OutlinedButton(
                                onClick = {
                                    Toast.makeText(
                                        context,
                                        "12-Hour Cooldown Active. Next pause available in $cooldownText",
                                        Toast.LENGTH_LONG
                                    ).show()
                                },
                                enabled = false,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("⏳ 12h Cooldown Active ($cooldownText left)", fontSize = 12.sp, color = Color.Gray)
                            }
                        } else {
                            Button(
                                onClick = {
                                    when (DnsPreferences.startBankingPause(context)) {
                                        BankingPauseStartResult.STARTED -> {
                                            val now = System.currentTimeMillis()
                                            isBankingPaused = true
                                            bankingPauseRemainingSec = maxOf(
                                                0L,
                                                (DnsPreferences.getBankingPauseUntil(context) - now) / 1000L
                                            )
                                            isOnBankingCooldown = true
                                            bankingCooldownRemainingSec = maxOf(
                                                0L,
                                                (DnsPreferences.getBankingPauseCooldownUntil(context) - now) / 1000L
                                            )
                                            isAccessibilityEnabled = false
                                            Toast.makeText(
                                                context,
                                                "Emergency accessibility pause active for 5 minutes. A 12-hour cooldown follows.",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                        BankingPauseStartResult.COOLDOWN_ACTIVE -> {
                                            isOnBankingCooldown = true
                                            bankingCooldownRemainingSec = maxOf(
                                                0L,
                                                (DnsPreferences.getBankingPauseCooldownUntil(context) - System.currentTimeMillis()) / 1000L
                                            )
                                        }
                                        BankingPauseStartResult.SERVICE_NOT_ENABLED ->
                                            Toast.makeText(context, "Enable this app's Accessibility service first.", Toast.LENGTH_LONG).show()
                                        BankingPauseStartResult.WRITE_PERMISSION_MISSING ->
                                            Toast.makeText(context, "The required secure-settings permission is not granted.", Toast.LENGTH_LONG).show()
                                        BankingPauseStartResult.SETTING_WRITE_FAILED ->
                                            Toast.makeText(context, "Could not start emergency pause. Check the app log for details.", Toast.LENGTH_LONG).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("🛑 Emergency Accessibility Pause for 5 Mins (12h Cooldown)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (selectedSettingsPage != null) {
                selectedSettingsPage?.let { page ->
                    SettingsSidebarContent(
                        page = page,
                        isProtectionLocked = (isActive && currentTimeMillis < expiryMillis) ||
                            currentTimeMillis < DnsPreferences.getBankingPauseUntil(context)
                    )
                }
            } else {
            // --- Logo Section ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = CircleShape,
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    modifier = Modifier.size(100.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.app_logo),
                        contentDescription = "App Logo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // --- 2 Prominent Separate Tabs ---
            TabRow(
                selectedTabIndex = selectedMainTab,
                containerColor = Color(0xFFE2E8F0),
                contentColor = Color.Black,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
            ) {
                Tab(
                    selected = selectedMainTab == 0,
                    onClick = { selectedMainTab = 0 },
                    text = { Text("🌐 1. DNS Protection", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                )
                Tab(
                    selected = selectedMainTab == 1,
                    onClick = { selectedMainTab = 1 },
                    text = { Text("🛡️ 2. Accessibility", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                )
            }

            if (selectedMainTab == 0) {
            // --- Status & Strict Lock Card ---
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isActive) Color(0xFFFFF3E0) else Color(0xFFECEFF1)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "Lock Status", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Box(
                            modifier = Modifier
                                .background(
                                    color = if (isActive) Color(0xFFE65100) else Color(0xFF546E7A),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (isActive) {
                                    if (hasPasswordActive) "🔒 LOCKED (PASSWORD PROTECTED)" else "🔒 STRICTLY LOCKED"
                                } else "UNLOCKED",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                    }

                    if (isActive && expiryMillis > currentTimeMillis) {
                        Spacer(modifier = Modifier.height(8.dp))
                        val remainingMillis = expiryMillis - currentTimeMillis
                        val daysRemaining = TimeUnit.MILLISECONDS.toDays(remainingMillis)
                        val hoursRemaining = TimeUnit.MILLISECONDS.toHours(remainingMillis) % 24
                        val minutesRemaining = TimeUnit.MILLISECONDS.toMinutes(remainingMillis) % 60
                        val secondsRemaining = TimeUnit.MILLISECONDS.toSeconds(remainingMillis) % 60

                        Text(text = "Remaining until natural unlock:", fontSize = 13.sp, color = Color.DarkGray)
                        Text(
                            text = "${daysRemaining}d ${hoursRemaining}h ${minutesRemaining}m ${secondsRemaining}s",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFFBF360C)
                        )

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Unlocks on: ${dateFormatter.format(Date(expiryMillis))}",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )

                        val prevDns = DnsPreferences.getPreviousDnsSpecifier(context).ifEmpty { "Automatic (Default)" }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Previous DNS to restore: $prevDns",
                            fontSize = 12.sp,
                            color = Color(0xFF455A64)
                        )

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (hasPasswordActive) "🔑 Password protection is ACTIVE (can unlock early with password)"
                            else "⛔ No password configured (strict lock until expiration)",
                            fontSize = 12.sp,
                            color = if (hasPasswordActive) Color(0xFF1B5E20) else Color(0xFFC62828),
                            fontWeight = FontWeight.Medium
                        )
                    } else {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "No DNS is currently locked. Configure duration, DNS, and optional password below.",
                            fontSize = 13.sp,
                            color = Color.DarkGray
                        )
                    }
                }
            }

            // --- Uninstall Protection Status Card ---
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isDeviceOwner || isAccessibilityEnabled) Color(0xFFE8F5E9) else Color(0xFFFFF8E1)
                )
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(
                        text = if (isDeviceOwner) "🛡️ Strict Uninstall Lock: HARDWARE ARMED"
                        else if (isAccessibilityEnabled && isDeviceAdmin) "🛡️ Strict Uninstall Lock: 100% ARMED"
                        else if (isAccessibilityEnabled) "🛡️ Anti-Uninstall Guard: ACTIVE (Accessibility)"
                        else if (isDeviceAdmin) "🛡️ Uninstall Protection: ACTIVE (Device Admin)"
                        else "⚠️ Uninstall Protection: NOT CONFIGURED",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = if (isDeviceOwner || isAccessibilityEnabled) Color(0xFF2E7D32) else Color(0xFFE65100)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isDeviceOwner) "App cannot be uninstalled from Settings, Launcher, or ADB until schedule expires or password is provided."
                        else if (isAccessibilityEnabled) "Active Guard blocks attempts to uninstall, force stop, or clear data in Settings without unlocking first."
                        else "Enable Anti-Uninstall Guard or Device Admin so this app cannot be uninstalled during the lock period.",
                        fontSize = 12.sp,
                        color = Color.DarkGray
                    )

                    if (!isDeviceOwner && (!isAccessibilityEnabled || !isDeviceAdmin)) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!isAccessibilityEnabled) {
                                OutlinedButton(
                                    onClick = { showAccessibilityHelpDialog = true },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Enable Guard (Settings)", fontSize = 11.sp, textAlign = TextAlign.Center)
                                }
                            }
                            if (!isDeviceAdmin) {
                                OutlinedButton(
                                    onClick = {
                                        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, FamilyDeviceAdminReceiver.getComponentName(context))
                                            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Protects DNS from being uninstalled during active schedules.")
                                        }
                                        context.startActivity(intent)
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Enable Device Admin", fontSize = 11.sp, textAlign = TextAlign.Center)
                                }
                            }
                        }

                    }
                }
            }

            // --- DNS Hostname Input & Presets ---
            val dnsProviders = listOf(
                DnsProvider("AdGuard (Family) | family.adguard-dns.com", DnsPreferences.DEFAULT_DNS_HOSTNAME, "Blocks ads, trackers, and adult content"),
                DnsProvider("AdGuard (Standard) | dns.adguard.com", "dns.adguard.com", "Blocks ads and trackers"),
                DnsProvider("Cloudflare (Family) | family.cloudflare-dns.com ", "family.cloudflare-dns.com", "Blocks malware and adult content"),
                DnsProvider("Cloudflare (Standard) | 1dot1dot1dot1.cloudflare-dns.com", "1dot1dot1dot1.cloudflare-dns.com", "Fastest privacy-focused DNS"),
                DnsProvider("Google Public DNS | dns.google", "dns.google", "Reliable and fast (no blocking)"),
                DnsProvider("CleanBrowsing (Family) | family.cleanbrowsing.org", "family.cleanbrowsing.org", "Strict filter: no adult content"),
                DnsProvider("NextDNS (Standard) | dns.nextdns.io", "dns.nextdns.io", "Highly customizable filtering"),
                DnsProvider("Custom / Manual", "", "Type your own hostname below")
            )

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { if (!isActive) expanded = !expanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = dnsName,
                    onValueChange = { if (!isActive) dnsName = it },
                    label = { Text("DNS Name / Hostname") },
                    placeholder = { Text("e.g. family.cloudflare-dns.com") },
                    singleLine = true,
                    enabled = !isActive,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    dnsProviders.forEach { provider ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(provider.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(provider.description, fontSize = 11.sp, color = Color.Gray)
                                }
                            },
                            onClick = {
                                dnsName = provider.hostname
                                expanded = false
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                        )
                    }
                }
            }

            // --- Duration Inputs: Years, Days, Hours, and Minutes ---
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Lock Duration:",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = Color.DarkGray
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Years
                    OutlinedTextField(
                        value = totalYears,
                        onValueChange = { if (it.all { ch -> ch.isDigit() }) totalYears = it },
                        label = { Text("Years") },
                        placeholder = { Text("0") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        enabled = !isActive,
                        modifier = Modifier.weight(1f)
                    )

                    // Days
                    OutlinedTextField(
                        value = totalDays,
                        onValueChange = { if (it.all { ch -> ch.isDigit() }) totalDays = it },
                        label = { Text("Days") },
                        placeholder = { Text("0") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        enabled = !isActive,
                        modifier = Modifier.weight(1f)
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Hours
                    OutlinedTextField(
                        value = totalHours,
                        onValueChange = { if (it.all { ch -> ch.isDigit() }) totalHours = it },
                        label = { Text("Hours") },
                        placeholder = { Text("0") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        enabled = !isActive,
                        modifier = Modifier.weight(1f)
                    )

                    // Minutes
                    OutlinedTextField(
                        value = totalMinutes,
                        onValueChange = { if (it.all { ch -> ch.isDigit() }) totalMinutes = it },
                        label = { Text("Minutes") },
                        placeholder = { Text("0") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        enabled = !isActive,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // --- Password Protection Toggle & Input (Before Activation) ---
            if (!isActive) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF3F4F6))
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Password to Uninstall / Unlock",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "Require a password to deactivate or uninstall before time expires.",
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )
                            }
                            Switch(
                                checked = usePasswordToggle,
                                onCheckedChange = { usePasswordToggle = it }
                            )
                        }

                        if (usePasswordToggle) {
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedTextField(
                                value = passwordInput,
                                onValueChange = { passwordInput = it },
                                label = { Text("Enter Password (Single entry)") },
                                placeholder = { Text("Set your unlock password") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "💡 No double-check required. Once specified time expires naturally, the password deletes automatically.",
                                fontSize = 11.sp,
                                color = Color(0xFF1976D2)
                            )
                        }
                    }
                }
            }

            } // closes if (selectedMainTab == 0)
            else {
                // ==========================================
                // PART 2: ACCESSIBILITY PROTECTION TAB
                // ==========================================
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isDeviceOwner || isAccessibilityEnabled) Color(0xFFE8F5E9) else Color(0xFFFFF8E1)
                    )
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(
                            text = if (isDeviceOwner) "🛡️ Anti-Uninstall Guard: HARDWARE ARMED"
                            else if (isAccessibilityEnabled) "🛡️ Anti-Uninstall Guard: ACTIVE"
                            else "⚠️ Anti-Uninstall Guard: NOT ENABLED",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = if (isDeviceOwner || isAccessibilityEnabled) Color(0xFF2E7D32) else Color(0xFFE65100)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isAccessibilityEnabled) "Active Accessibility Guard prevents unauthorized uninstallation or tampering in Settings."
                            else "Enable Accessibility Guard so this app cannot be uninstalled during protection schedules.",
                            fontSize = 12.sp,
                            color = Color.DarkGray
                        )

                        if (!isAccessibilityEnabled) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { showAccessibilityHelpDialog = true },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Enable Guard in Accessibility Settings", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF3F4F6))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Banking app accessibility pause",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            if (hasBankingUsageAccess) {
                                "Usage Access is enabled. Accessibility pauses only for selected apps in the foreground."
                            } else {
                                "Enable Usage Access so accessibility can resume after you leave a selected bank app."
                            },
                            fontSize = 12.sp,
                            color = Color.DarkGray
                        )
                        if (!hasBankingUsageAccess) {
                            OutlinedButton(
                                onClick = {
                                    try {
                                        context.startActivity(
                                            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                        )
                                    } catch (e: Exception) {
                                        Log.e("MainActivity", "Could not open Usage Access settings", e)
                                        Toast.makeText(
                                            context,
                                            "Could not open Usage Access settings.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Enable Usage Access")
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                if (installedBankingCandidates.isEmpty()) {
                                    isLoadingBankingApps = true
                                    coroutineScope.launch {
                                        try {
                                            val candidates = withContext(Dispatchers.IO) {
                                                val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
                                                    addCategory(Intent.CATEGORY_LAUNCHER)
                                                }
                                                @Suppress("DEPRECATION")
                                                context.packageManager.queryIntentActivities(launcherIntent, 0)
                                                    .mapNotNull { resolved ->
                                                        val appInfo = resolved.activityInfo.applicationInfo
                                                            ?: return@mapNotNull null
                                                        val packageName = resolved.activityInfo.packageName
                                                        if (packageName == context.packageName) return@mapNotNull null
                                                        val label = context.packageManager
                                                            .getApplicationLabel(appInfo)
                                                            .toString()
                                                        packageName to label
                                                    }
                                                    .distinctBy { it.first }
                                                    .sortedBy { it.second.lowercase(Locale.getDefault()) }
                                            }
                                            installedBankingCandidates = candidates
                                            customBankingPackages =
                                                BankingAccessibilityPause.getCustomBankingPackages(context)
                                            showBankingAppPicker = true
                                        } catch (e: Exception) {
                                            Log.e("MainActivity", "Could not load installed apps", e)
                                            Toast.makeText(
                                                context,
                                                "Could not load installed apps.",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        } finally {
                                            isLoadingBankingApps = false
                                        }
                                    }
                                } else {
                                    customBankingPackages =
                                        BankingAccessibilityPause.getCustomBankingPackages(context)
                                    showBankingAppPicker = true
                                }
                            },
                            enabled = !isLoadingBankingApps,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (isLoadingBankingApps) "Loading installed apps..."
                                else "Choose banking apps"
                            )
                        }
                        Text(
                            "${customBankingPackages.size} custom app(s) selected",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- Footer Info ---
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Developer: Abhi Das ©2026(India)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray
                )
                Text(
                    text = "Last Installation: $appInstallTime",
                    fontSize = 10.sp,
                    color = Color.LightGray
                )
                Text(
                    text = "Last Activation: $lastActivation",
                    fontSize = 10.sp,
                    color = Color.LightGray
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    // --- Unlock With Password Dialog (Active Mode) ---
    if (showUnlockPasswordDialog) {
        AlertDialog(
            onDismissRequest = { showUnlockPasswordDialog = false },
            title = { Text("🔑 Enter Password to Unlock") },
            text = {
                Column {
                    Text("Enter the password you created to lift the uninstall restriction and deactivate DNS early:\n")
                    OutlinedTextField(
                        value = unlockPasswordAttempt,
                        onValueChange = {
                            unlockPasswordAttempt = it
                            passwordErrorText = null
                        },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = passwordErrorText != null,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (passwordErrorText != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = passwordErrorText ?: "",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val savedPassword = try {
                            DnsPreferences.getPassword(context)
                        } catch (e: IllegalStateException) {
                            passwordErrorText = "Could not read the saved password. Protection remains active."
                            null
                        }
                        if (savedPassword != null) {
                            if (unlockPasswordAttempt.trim() == savedPassword) {
                                showUnlockPasswordDialog = false
                                coroutineScope.launch(Dispatchers.IO) {
                                    DnsController.deactivateAndRestorePrevious(context)
                                    withContext(Dispatchers.Main) {
                                        FamilyDeviceAdminReceiver.setUninstallBlocked(context, true)
                                        DnsExpiryReceiver.cancelExpiryAlarm(context)
                                        DnsForegroundService.stop(context)
                                        VigilanceWatchdogAlarm.cancel(context)
                                        WatchdogScheduler.cancel(context)
                                        try { DnsPreferences.clearPassword(context) } catch (_: Exception) {}
                                        isActive = false
                                        hasPasswordActive = false
                                        DnsPreferences.saveDnsState(context, dnsName, totalYears, totalDays, totalHours, totalMinutes, false, 0L)
                                        Toast.makeText(context, "Password verified! DNS lock unlocked. App uninstall remains blocked.", Toast.LENGTH_LONG).show()
                                    }
                                }
                            } else {
                                passwordErrorText = "Incorrect password! Lock remains active."
                            }
                        }
                    }
                ) {
                    Text("Unlock")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnlockPasswordDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- Banking Pause Password Dialog ---
    if (showBankingPausePasswordDialog) {
        AlertDialog(
            onDismissRequest = { showBankingPausePasswordDialog = false },
            title = { Text("🔑 Password Required") },
            text = {
                Column {
                    Text("Enter your password to authorize the 15-minute banking pause:\n")
                    OutlinedTextField(
                        value = bankingPasswordAttempt,
                        onValueChange = {
                            bankingPasswordAttempt = it
                            bankingPasswordErrorText = null
                        },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = bankingPasswordErrorText != null,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (bankingPasswordErrorText != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = bankingPasswordErrorText ?: "",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val savedPassword = try {
                            DnsPreferences.getPassword(context)
                        } catch (e: IllegalStateException) {
                            bankingPasswordErrorText = "Could not read the saved password."
                            null
                        }
                        if (savedPassword != null) {
                            if (bankingPasswordAttempt.trim() == savedPassword) {
                                showBankingPausePasswordDialog = false
                                when (DnsPreferences.startBankingPause(context)) {
                                    BankingPauseStartResult.STARTED -> {
                                        val now = System.currentTimeMillis()
                                        isBankingPaused = true
                                        bankingPauseRemainingSec = maxOf(
                                            0L,
                                            (DnsPreferences.getBankingPauseUntil(context) - now) / 1000L
                                        )
                                        isOnBankingCooldown = true
                                        bankingCooldownRemainingSec = maxOf(
                                            0L,
                                            (DnsPreferences.getBankingPauseCooldownUntil(context) - now) / 1000L
                                        )
                                        Toast.makeText(
                                            context,
                                            "Password verified! Emergency pause active for 5 minutes.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                    BankingPauseStartResult.COOLDOWN_ACTIVE -> {
                                        isOnBankingCooldown = true
                                        bankingCooldownRemainingSec = maxOf(
                                            0L,
                                            (DnsPreferences.getBankingPauseCooldownUntil(context) - System.currentTimeMillis()) / 1000L
                                        )
                                        Toast.makeText(context, "12-Hour Cooldown Active.", Toast.LENGTH_LONG).show()
                                    }
                                    else -> Toast.makeText(context, "Could not start banking pause.", Toast.LENGTH_LONG).show()
                                }
                            } else {
                                bankingPasswordErrorText = "Incorrect password!"
                            }
                        }
                    }
                ) {
                    Text("Verify & Pause")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBankingPausePasswordDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- Warning Confirmation Dialog Before Locking ---
    if (showLockConfirmDialog) {
        val durationLabelFormatted = buildString {
            if (yInt > 0) append("${yInt} year(s) ")
            if (dInt > 0) append("${dInt} day(s) ")
            if (hInt > 0) append("${hInt} hour(s) ")
            if (mInt > 0) append("${mInt} minute(s)")
            if (isEmpty()) append("0 minutes")
        }.trim()

        val futureDate = dateFormatter.format(Date(System.currentTimeMillis() + totalDurationMillis))

        AlertDialog(
            onDismissRequest = { showLockConfirmDialog = false },
            title = { Text("⚠️ Confirm Lock & Submit") },
            text = {
                Text(
                    "You are activating DNS and uninstall restrictions for $durationLabelFormatted.\n\n" +
                            "• DNS: ${dnsName.trim()}\n" +
                            "• Locked until: $futureDate\n" +
                            (if (usePasswordToggle) "• Unlock method: Password required to unlock or uninstall early\n"
                            else "• Unlock method: Strictly locked until expiry (no early unlock)\n") +
                            "\nAre you sure you want to proceed?"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLockConfirmDialog = false
                        val trimmedDns = dnsName.trim()
                        coroutineScope.launch(Dispatchers.IO) {
                            val result = DnsController.activateWithPriority(context.applicationContext, trimmedDns)
                            val errorMsg = result.exceptionOrNull()?.message ?: "Unknown DPM error"
                            Log.d("DnsActivation", "Activation result: isSuccess=${result.isSuccess}, error=$errorMsg")
                            withContext(Dispatchers.Main) {
                                if (result.isSuccess) {
                                    val newExpiry = System.currentTimeMillis() + totalDurationMillis
                                    expiryMillis = newExpiry
                                    isActive = true

                                    // Handle password
                                    if (usePasswordToggle && passwordInput.isNotBlank()) {
                                        try {
                                            DnsPreferences.savePassword(context, passwordInput.trim())
                                            hasPasswordActive = true
                                        } catch (_: Exception) {}
                                    } else {
                                        try {
                                            DnsPreferences.clearPassword(context)
                                        } catch (_: Exception) {}
                                        hasPasswordActive = false
                                    }

                                    DnsPreferences.saveDnsState(context, trimmedDns, totalYears, totalDays, totalHours, totalMinutes, true, newExpiry)
                                    DnsExpiryReceiver.scheduleExpiryAlarm(context, newExpiry)
                                    FamilyDeviceAdminReceiver.setUninstallBlocked(context, true)
                                    DnsForegroundService.start(context.applicationContext)
                                    WatchdogScheduler.schedule(context.applicationContext)
                                    Toast.makeText(context, "Lock engaged for $durationLabelFormatted!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Log.e("DnsActivation", "Activation failed: $errorMsg")
                                    Toast.makeText(context, "Activation failed: $errorMsg", Toast.LENGTH_LONG).show()
                                    showPermissionDialog = true
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text("Yes, Submit & Lock")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLockConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- ADB Permission Dialog ---
    if (showBankingAppPicker) {
        AlertDialog(
            onDismissRequest = { showBankingAppPicker = false },
            title = { Text("Banking apps") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(450.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "Select exact apps to pause accessibility for. Use Open beside a bank app to disable accessibility before launching it.",
                        fontSize = 13.sp
                    )
                    if (installedBankingCandidates.isEmpty()) {
                        Text("No launchable apps were found.", fontSize = 12.sp, color = Color.Gray)
                    }
                    installedBankingCandidates.forEach { (packageName, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (customBankingPackages.contains(packageName)) {
                                        BankingAccessibilityPause.removeBankingPackage(context, packageName)
                                    } else if (!BankingAccessibilityPause.addBankingPackage(context, packageName)) {
                                        Toast.makeText(context, "Could not add $packageName", Toast.LENGTH_SHORT).show()
                                    }
                                    customBankingPackages =
                                        BankingAccessibilityPause.getCustomBankingPackages(context)
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = customBankingPackages.contains(packageName),
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        if (!BankingAccessibilityPause.addBankingPackage(context, packageName)) {
                                            Toast.makeText(context, "Could not add $packageName", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        BankingAccessibilityPause.removeBankingPackage(context, packageName)
                                    }
                                    customBankingPackages =
                                        BankingAccessibilityPause.getCustomBankingPackages(context)
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(label)
                                Text(packageName, fontSize = 11.sp, color = Color.Gray)
                            }
                            if (BankingAccessibilityPause.isBankingOrFinancialApp(context, packageName)) {
                                TextButton(
                                    onClick = {
                                        showBankingAppPicker = false
                                        launchBankingAppWithAccessibilityPaused(packageName)
                                    }
                                ) {
                                    Text("Open")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBankingAppPicker = false }) {
                    Text("Done")
                }
            }
        )
    }

    if (showPermissionDialog) {
        val adbCommand = "adb shell pm grant com.abhi.familyprotection android.permission.WRITE_SECURE_SETTINGS"
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("Permission Required") },
            text = {
                Column {
                    Text("Android requires WRITE_SECURE_SETTINGS permission to manage system Private DNS.\n\nConnect your device via USB and run this ADB command:\n")
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF1F1F1), shape = RoundedCornerShape(6.dp))
                            .padding(8.dp)
                    ) {
                        Text(
                            text = adbCommand,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = Color(0xFF1E88E5)
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    clipboardManager.setText(AnnotatedString(adbCommand))
                    Toast.makeText(context, "Command copied to clipboard!", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Copy Command")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) { Text("Close") }
            }
        )
    }

    // --- Accessibility Help Dialog ---
    if (showAccessibilityHelpDialog) {
        AlertDialog(
            onDismissRequest = { showAccessibilityHelpDialog = false },
            title = { Text("🛡️ How to Enable Guard") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Because this is an APK, Android (especially Vivo) requires an extra step to enable security features:")

                    Text("1. Exit this app and long-press the 'Family' icon.", fontSize = 13.sp)
                    Text("2. Tap 'App Info' (the 'i' icon).", fontSize = 13.sp)
                    Text("3. Tap the 3 dots (⋮) in the top-right corner.", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("4. Select 'Allow restricted settings'.", fontWeight = FontWeight.Bold, color = Color(0xFFD32F2F), fontSize = 13.sp)
                    Text("5. Now return here and click 'Open Settings' to enable the Guard service.", fontSize = 13.sp)

                    Spacer(modifier = Modifier.height(4.dp))
                    Text("💡 Tip: Also set Battery to 'Unrestricted' in App Info so the guard never stops.", fontSize = 12.sp, color = Color.Gray)
                }
            },
            confirmButton = {
                Button(onClick = {
                    showAccessibilityHelpDialog = false
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    context.startActivity(intent)
                }) {
                    Text("Open Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAccessibilityHelpDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    }
}
