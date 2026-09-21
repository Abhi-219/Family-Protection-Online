package com.example.family

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.example.family.ui.theme.FamilyTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

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
    private const val KEY_TOTAL_YEARS = "total_years"
    private const val KEY_TOTAL_DAYS = "total_days"
    private const val KEY_TOTAL_HOURS = "total_hours"
    private const val KEY_TOTAL_MINS = "total_mins"
    private const val KEY_IS_ACTIVE = "is_active"
    private const val KEY_EXPIRY_MILLIS = "expiry_millis"
    private const val KEY_PREV_MODE = "prev_dns_mode"
    private const val KEY_PREV_SPECIFIER = "prev_dns_specifier"
    private const val KEY_LOCK_PASSWORD = "lock_password"
    private const val KEY_HAS_PASSWORD = "has_password"
    private const val KEY_INSTALL_TIME = "install_timestamp"
    private const val KEY_SCAN_PAUSE_UNTIL = "scan_pause_until"

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
        }
        
        editor.apply()
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
        getPrefs(context).edit()
            .putString(KEY_LOCK_PASSWORD, password)
            .putBoolean(KEY_HAS_PASSWORD, true)
            .apply()
    }

    fun getPassword(context: Context): String =
        getPrefs(context).getString(KEY_LOCK_PASSWORD, "") ?: ""

    fun hasPassword(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_HAS_PASSWORD, false)

    fun clearPassword(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_LOCK_PASSWORD)
            .remove(KEY_HAS_PASSWORD)
            .apply()
    }

    /** Suspends adult-content scanning only; DNS and uninstall protection stay armed. */
    fun pauseContentScanning(context: Context, untilMillis: Long) {
        getPrefs(context).edit().putLong(KEY_SCAN_PAUSE_UNTIL, untilMillis).apply()
    }

    fun getContentScanPauseUntil(context: Context): Long =
        getPrefs(context).getLong(KEY_SCAN_PAUSE_UNTIL, 0L)

    fun getDnsName(context: Context): String =
        getPrefs(context).getString(KEY_DNS_NAME, "") ?: ""

    fun getTotalYears(context: Context): String =
        getPrefs(context).getString(KEY_TOTAL_YEARS, "0") ?: "0"

    fun getTotalDays(context: Context): String =
        getPrefs(context).getString(KEY_TOTAL_DAYS, "7") ?: "7"

    fun getTotalHours(context: Context): String =
        getPrefs(context).getString(KEY_TOTAL_HOURS, "0") ?: "0"

    fun getTotalMinutes(context: Context): String =
        getPrefs(context).getString(KEY_TOTAL_MINS, "0") ?: "0"

    fun isActive(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_IS_ACTIVE, false)

    fun getExpiryMillis(context: Context): Long =
        getPrefs(context).getLong(KEY_EXPIRY_MILLIS, 0L)
}

object DnsController {
    fun applyDns(context: Context, hostname: String): Result<Unit> {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val admin = FamilyDeviceAdminReceiver.getComponentName(context)

        return try {
            if (dpm != null && dpm.isDeviceOwnerApp(context.packageName)) {
                // Enterprise-grade lock: Set as Global Settings via Device Owner
                dpm.setGlobalSetting(admin, "private_dns_mode", "hostname")
                dpm.setGlobalSetting(admin, "private_dns_specifier", hostname)
            } else {
                // Fallback for non-Device Owner (requires WRITE_SECURE_SETTINGS)
                Settings.Global.putString(context.contentResolver, "private_dns_mode", "hostname")
                Settings.Global.putString(context.contentResolver, "private_dns_specifier", hostname)
            }
            Result.success(Unit)
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
        val admin = FamilyDeviceAdminReceiver.getComponentName(context)

        return try {
            val prevMode = DnsPreferences.getPreviousDnsMode(context)
            val prevSpecifier = DnsPreferences.getPreviousDnsSpecifier(context)

            if (dpm != null && dpm.isDeviceOwnerApp(context.packageName)) {
                dpm.setGlobalSetting(admin, "private_dns_mode", prevMode)
                dpm.setGlobalSetting(admin, "private_dns_specifier", prevSpecifier)
            } else {
                Settings.Global.putString(context.contentResolver, "private_dns_mode", prevMode)
                Settings.Global.putString(context.contentResolver, "private_dns_specifier", prevSpecifier)
            }
            DnsPreferences.clearPreviousDns(context)

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

    // Dialog states
    var showPermissionDialog by remember { mutableStateOf(false) }
    var showLockConfirmDialog by remember { mutableStateOf(false) }
    var showUnlockPasswordDialog by remember { mutableStateOf(false) }
    var showAccessibilityHelpDialog by remember { mutableStateOf(false) }
    var showPauseScanDialog by remember { mutableStateOf(false) }
    var unlockPasswordAttempt by remember { mutableStateOf("") }
    var passwordErrorText by remember { mutableStateOf<String?>(null) }

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

    // Permissions are granted outside the app, so re-read them whenever we return to the front.
    LaunchedEffect(isForeground) {
        if (isForeground) {
            isDeviceAdmin = FamilyDeviceAdminReceiver.isDeviceAdmin(context)
            isDeviceOwner = FamilyDeviceAdminReceiver.isDeviceOwner(context)
            isAccessibilityEnabled = AntiUninstallAccessibilityService.isEnabled(context)
        }
    }

    // Live countdown, Expiry handling & Anti-Tamper Loop
    LaunchedEffect(isActive, isForeground) {
        var tick = 0
        while (isActive && isForeground) {
            currentTimeMillis = System.currentTimeMillis()

            // Permission state almost never changes; re-query it every 30s instead of every tick.
            if (tick % 6 == 0) {
                isDeviceAdmin = FamilyDeviceAdminReceiver.isDeviceAdmin(context)
                isDeviceOwner = FamilyDeviceAdminReceiver.isDeviceOwner(context)
                isAccessibilityEnabled = AntiUninstallAccessibilityService.isEnabled(context)
            }
            tick++

            // Check status and update UI, but let DnsForegroundService handle the enforcement
            if (currentTimeMillis >= expiryMillis) {
                isActive = false
                hasPasswordActive = false
                DnsForegroundService.stop(context)
            }

            delay(5000L)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Family DNS & Uninstall Guard", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        // Pinned Bottom Bar: ALWAYS 100% visible regardless of scroll or password toggle!
        bottomBar = {
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
                    if (!isActive) {
                        if (!isAccessibilityEnabled) {
                            Text(
                                text = "⚠️ THE MOST IMPORTANT STEP: Enable the Guard in Accessibility Settings before locking!",
                                color = Color.Red,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        
                        Button(
                            onClick = {
                                if (!isAccessibilityEnabled) {
                                    Toast.makeText(context, "MANDATORY: Please Enable Guard in Settings first!", Toast.LENGTH_LONG).show()
                                    showAccessibilityHelpDialog = true
                                    return@Button
                                }
                                
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
                            Text("Lock & Activate for $durationLabel", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
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
                                Text("🔑 Unlock / Deactivate with Password", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            }

                            OutlinedButton(
                                onClick = {
                                    unlockPasswordAttempt = ""
                                    passwordErrorText = null
                                    showPauseScanDialog = true
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("⏸️ Wrongly blocked? Pause content filter 5 min", fontSize = 12.sp)
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
                                    text = "🔒 Locked & Non-Uninstallable Until Expiry",
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF616161),
                                    fontSize = 14.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    // Emergency Restore Button (Always visible, disabled when lock is active)
                    OutlinedButton(
                        onClick = {
                            FamilyDeviceAdminReceiver.setUninstallBlocked(context, false)
                            DnsController.deactivateAndRestorePrevious(context)
                            Toast.makeText(context, "System restrictions cleared and settings restored!", Toast.LENGTH_LONG).show()
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
                DnsProvider("AdGuard (Family) | family.adguard-dns.com", "family.adguard-dns.com", "Blocks ads, trackers, and adult content"),
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
                        val savedPassword = DnsPreferences.getPassword(context)
                        if (unlockPasswordAttempt.trim() == savedPassword) {
                            showUnlockPasswordDialog = false
                            DnsController.deactivateAndRestorePrevious(context)
                            FamilyDeviceAdminReceiver.setUninstallBlocked(context, false)
                            DnsExpiryReceiver.cancelExpiryAlarm(context)
                            DnsForegroundService.stop(context)
                            DnsPreferences.clearPassword(context)
                            isActive = false
                            hasPasswordActive = false
                            DnsPreferences.saveDnsState(context, dnsName, totalYears, totalDays, totalHours, totalMinutes, false, 0L)
                            Toast.makeText(context, "Password verified! App unlocked and uninstall allowed.", Toast.LENGTH_LONG).show()
                        } else {
                            passwordErrorText = "Incorrect password! Lock remains active."
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

    // --- Pause Content Filter Dialog (False Positive Recovery) ---
    if (showPauseScanDialog) {
        AlertDialog(
            onDismissRequest = { showPauseScanDialog = false },
            title = { Text("⏸️ Pause Content Filter") },
            text = {
                Column {
                    Text("This pauses adult-content scanning for 5 minutes. DNS filtering and uninstall protection stay fully active.\n")
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
                        if (unlockPasswordAttempt.trim() == DnsPreferences.getPassword(context)) {
                            showPauseScanDialog = false
                            DnsPreferences.pauseContentScanning(
                                context,
                                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5)
                            )
                            Toast.makeText(context, "Content filter paused for 5 minutes.", Toast.LENGTH_LONG).show()
                        } else {
                            passwordErrorText = "Incorrect password!"
                        }
                    }
                ) {
                    Text("Pause 5 min")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPauseScanDialog = false }) {
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
                        val result = DnsController.activateWithPriority(context, trimmedDns)
                        if (result.isSuccess) {
                            val newExpiry = System.currentTimeMillis() + totalDurationMillis
                            expiryMillis = newExpiry
                            isActive = true

                            // Handle password
                            if (usePasswordToggle && passwordInput.isNotBlank()) {
                                DnsPreferences.savePassword(context, passwordInput.trim())
                                hasPasswordActive = true
                            } else {
                                DnsPreferences.clearPassword(context)
                                hasPasswordActive = false
                            }

                            DnsPreferences.saveDnsState(context, trimmedDns, totalYears, totalDays, totalHours, totalMinutes, true, newExpiry)
                            DnsExpiryReceiver.scheduleExpiryAlarm(context, newExpiry)
                            FamilyDeviceAdminReceiver.setUninstallBlocked(context, true)
                            DnsForegroundService.start(context)
                            Toast.makeText(context, "Lock engaged for $durationLabelFormatted!", Toast.LENGTH_SHORT).show()
                        } else {
                            showPermissionDialog = true
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
    if (showPermissionDialog) {
        val adbCommand = "adb shell pm grant com.example.family android.permission.WRITE_SECURE_SETTINGS"
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
