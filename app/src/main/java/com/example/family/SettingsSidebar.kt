package com.abhi.familyprotection

import android.widget.Toast
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.IOException

enum class SettingsPage(val title: String) {
    LOGS("Logs"),
    APPS("Apps"),
    KEYWORD_BLOCK("Keyword Block"),
    SMS_ALERTS("SMS Alerts")
}

@Composable
fun SettingsSidebarContent(page: SettingsPage, isProtectionLocked: Boolean) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(page.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        when (page) {
            SettingsPage.LOGS -> LogsSettings()
            SettingsPage.APPS -> AppsSettings(isProtectionLocked)
            SettingsPage.KEYWORD_BLOCK -> KeywordSettings(isProtectionLocked)
            SettingsPage.SMS_ALERTS -> SmsAlertsSettings()
        }
    }
}

@Composable
private fun SmsAlertsSettings() {
    val context = LocalContext.current
    var isEnabled by remember {
        mutableStateOf(DnsPreferences.areSmsAlertsEnabled(context))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            DnsPreferences.setSmsAlertsEnabled(context, true)
            isEnabled = true
        } else {
            DnsPreferences.setSmsAlertsEnabled(context, false)
            isEnabled = false
            Toast.makeText(context, "SMS permission is required to send alerts.", Toast.LENGTH_LONG).show()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Guardian SMS alerts", fontWeight = FontWeight.SemiBold)
            Text(
                "When enabled, Family Protection sends alerts about detected keywords or blocked apps to ${SmsSender.GUARDIAN_NUMBER}. Enabling this gives your consent to send those SMS messages.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (isEnabled) "Consent enabled" else "Consent off")
                Switch(
                    checked = isEnabled,
                    onCheckedChange = { enabled ->
                        if (!enabled) {
                            DnsPreferences.setSmsAlertsEnabled(context, false)
                            isEnabled = false
                        } else if (
                            context.checkSelfPermission(Manifest.permission.SEND_SMS) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            DnsPreferences.setSmsAlertsEnabled(context, true)
                            isEnabled = true
                        } else {
                            permissionLauncher.launch(Manifest.permission.SEND_SMS)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun LogsSettings() {
    val context = LocalContext.current
    var isEnabled by remember { mutableStateOf(AppLogger.isLoggingEnabled(context)) }
    var logs by remember { mutableStateOf(AppLogger.getLogs()) }

    LaunchedEffect(Unit) {
        AppLogger.init(context)
        while (true) {
            logs = AppLogger.getLogs()
            delay(750L)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("App logging", fontWeight = FontWeight.SemiBold)
            Text(
                "Only logs recorded by com.abhi.familyprotection; Android system and other-app logs are not read.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = isEnabled,
            onCheckedChange = {
                isEnabled = it
                AppLogger.setLoggingEnabled(context, it)
            }
        )
    }

    OutlinedButton(
        onClick = {
            try {
                AppLogger.clearLogs(context)
                logs = emptyList()
            } catch (e: IOException) {
                Toast.makeText(context, "Could not clear saved logs: ${e.message}", Toast.LENGTH_LONG).show()
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Clear displayed logs")
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("com.abhi.familyprotection", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            if (logs.isEmpty()) {
                Text("No app logs recorded.", fontSize = 12.sp)
            } else {
                logs.takeLast(150).asReversed().forEach { entry ->
                    Text(
                        entry,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        color = if ("[KEYWORD_BLOCK]" in entry) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AppsSettings(isProtectionLocked: Boolean) {
    val context = LocalContext.current
    var packageInput by remember { mutableStateOf("") }
    var patternInput by remember { mutableStateOf("") }
    var blockedApps by remember { mutableStateOf(AppListPreferences.getCustomBlockedAdultApps(context).toList().sorted()) }
    var supervisedPatterns by remember { mutableStateOf(AppListPreferences.getCustomSupervisedPatterns(context).toList().sorted()) }

    SettingsCard(title = "Immediate Block Apps") {
        Text(
            if (isProtectionLocked) {
                "You can add entries while protection is locked; existing entries cannot be removed."
            } else {
                "Add package names or patterns. * matches one segment; ** matches one or more segments. Existing entries can be removed while unlocked."
            },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = packageInput,
            onValueChange = { packageInput = it },
            label = { Text("Package name or pattern") },
            placeholder = { Text("com.example.* or com.example.**") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                val packagePattern = packageInput.trim()
                if (!AppListPreferences.isValidPackagePattern(packagePattern)) {
                    Toast.makeText(context, "Enter a valid package name or pattern.", Toast.LENGTH_SHORT).show()
                } else if (packagePattern in AppListPreferences.getAllBlockedAdultApps(context)) {
                    Toast.makeText(context, "That package pattern is already on the block list.", Toast.LENGTH_SHORT).show()
                } else {
                    AppListPreferences.addBlockedAdultApp(context, packagePattern)
                    blockedApps = AppListPreferences.getCustomBlockedAdultApps(context).toList().sorted()
                    packageInput = ""
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Add blocked app")
        }

        if (blockedApps.isEmpty()) {
            Text("No entries.", fontSize = 12.sp)
        } else {
            blockedApps.forEach { packageName ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(packageName, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    OutlinedButton(
                        onClick = {
                            AppListPreferences.removeBlockedAdultApp(context, packageName)
                            blockedApps = AppListPreferences.getCustomBlockedAdultApps(context).toList().sorted()
                        },
                        enabled = !isProtectionLocked
                    ) {
                        Text("Remove")
                    }
                }
            }
        }
    }

    SettingsCard(title = "Supervised Apps") {
        Text(
            if (isProtectionLocked) {
                "You can add entries while protection is locked; existing entries cannot be removed."
            } else {
                "Matching apps are checked by Accessibility protection. * matches one package segment; ** matches one or more segments. Entries can be removed while unlocked."
            },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = patternInput,
            onValueChange = { patternInput = it },
            label = { Text("Package pattern") },
            placeholder = { Text("com.appname.*") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Examples: com.appname.*, com.appname.**, *.appname.*",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = {
                val pattern = patternInput.trim()
                if (!AppListPreferences.isValidPackagePattern(pattern)) {
                    Toast.makeText(context, "Enter a valid package pattern.", Toast.LENGTH_SHORT).show()
                } else if (pattern in AppListPreferences.getCustomSupervisedPatterns(context)) {
                    Toast.makeText(context, "That pattern is already supervised.", Toast.LENGTH_SHORT).show()
                } else {
                    AppListPreferences.addSupervisedPattern(context, pattern)
                    supervisedPatterns = AppListPreferences.getCustomSupervisedPatterns(context).toList().sorted()
                    patternInput = ""
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Add supervised pattern")
        }
        if (supervisedPatterns.isEmpty()) {
            Text("No custom package patterns.", fontSize = 12.sp)
        } else {
            supervisedPatterns.forEach { pattern ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(pattern, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    OutlinedButton(
                        onClick = {
                            AppListPreferences.removeSupervisedPattern(context, pattern)
                            supervisedPatterns = AppListPreferences.getCustomSupervisedPatterns(context).toList().sorted()
                        },
                        enabled = !isProtectionLocked
                    ) {
                        Text("Remove")
                    }
                }
            }
        }
    }
}

@Composable
private fun KeywordSettings(isProtectionLocked: Boolean) {
    val context = LocalContext.current
    var keywordInput by remember { mutableStateOf("") }
    var customKeywords by remember { mutableStateOf(KeywordPreferences.getCustomHardKeywords(context).toList().sorted()) }

    SettingsCard(title = "Keyword Block") {
        Text(
            if (isProtectionLocked) {
                "You can add keywords while protection is locked; existing keywords cannot be removed."
            } else {
                "Visible text in supervised apps is checked against configured adult keywords. A match sends the app to Home; reopening is not placed on a timed lockout. Entries can be removed while unlocked."
            },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = keywordInput,
            onValueChange = { keywordInput = it },
            label = { Text("Custom keyword or phrase") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                val keyword = keywordInput.trim()
                if (keyword.isEmpty()) {
                    Toast.makeText(context, "Enter a keyword or phrase.", Toast.LENGTH_SHORT).show()
                } else if (keyword.lowercase() in KeywordPreferences.getAllHardKeywords(context)) {
                    Toast.makeText(context, "That keyword is already configured.", Toast.LENGTH_SHORT).show()
                } else {
                    KeywordPreferences.addHardKeyword(context, keyword)
                    customKeywords = KeywordPreferences.getCustomHardKeywords(context).toList().sorted()
                    keywordInput = ""
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Add keyword")
        }

        Spacer(modifier = Modifier.height(2.dp))
        if (customKeywords.isEmpty()) {
            Text("No custom keywords added.", fontSize = 12.sp)
        } else {
            customKeywords.forEach { keyword ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(keyword, fontSize = 12.sp)
                    OutlinedButton(
                        onClick = {
                            KeywordPreferences.removeHardKeyword(context, keyword)
                            customKeywords = KeywordPreferences.getCustomHardKeywords(context).toList().sorted()
                        },
                        enabled = !isProtectionLocked
                    ) {
                        Text("Remove")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, fontWeight = FontWeight.Bold)
            content()
        }
    }
}
