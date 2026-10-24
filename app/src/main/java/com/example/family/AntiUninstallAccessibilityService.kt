package com.abhi.familyprotection

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import kotlinx.coroutines.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class AntiUninstallAccessibilityService : AccessibilityService() {

    private data class AppScanState(
        var lastScanTime: Long = 0L,
        var lastAiScanTime: Long = 0L,
        var lastBlockedTime: Long = 0L
    )

    private enum class PackageClass { WHITELISTED, BLOCKED_ADULT, RISKY, NORMAL }

    private val appScanStates = ConcurrentHashMap<String, AppScanState>()
    private val packageClasses = ConcurrentHashMap<String, PackageClass>()
    private var appListPreferences: SharedPreferences? = null
    private val appListPreferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> packageClasses.clear() }
    private val scanInProgress = AtomicBoolean(false)

    /**
     * Watches ENABLED_ACCESSIBILITY_SERVICES for external changes (e.g. Recents bypass).
     * If the lock is active and our service disappears from the list, re-add it immediately.
     * This fires even while the Recents overlay is on top, covering the gap that the
     * per-event tamper guard cannot see.
     */
    private val accessibilitySettingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            val context = applicationContext
            val now = System.currentTimeMillis()
            if (!DnsPreferences.isActive(context) ||
                now >= DnsPreferences.getExpiryMillis(context)) return
            if (BankingAccessibilityPause.isIntentionalPauseActive(context, now)) return

            if (!BankingAccessibilityPause.isAccessibilityEnabled(context)) {
                AppLogger.log(
                    context,
                    "[SELF_RESTORE] Accessibility service removed externally (Recents bypass?); restoring.",
                    TAG, "W"
                )
                Log.w(TAG, "Accessibility removed externally - restoring immediately")
                if (!BankingAccessibilityPause.ensureServiceEnabled(context)) {
                    AppLogger.log(
                        context,
                        "[SELF_RESTORE_FAILED] Could not re-enable Accessibility service",
                        TAG,
                        "E"
                    )
                }
            }
        }
    }
    @Volatile
    private var accessibilitySettingsObserverRegistered = false

    private val powerManager by lazy { getSystemService(Context.POWER_SERVICE) as? PowerManager }
    private val NORMAL_SCAN_COOLDOWN = 2000L
    private val AGGRESSIVE_SCAN_COOLDOWN = 500L
    private val MEDIUM_SCAN_COOLDOWN = 1000L
    private val NORMAL_AI_COOLDOWN = 3000L
    private val AGGRESSIVE_AI_COOLDOWN = 1000L
    private val MEDIUM_AI_COOLDOWN = 2000L
    private val AGGRESSIVE_WINDOW = 15 * 60 * 1000L
    private val HOT_AGGRESSIVE_WINDOW = 2 * 60 * 1000L
    private val MEDIUM_AGGRESSIVE_WINDOW = 7 * 60 * 1000L
    
    private val tempLockedApps = ConcurrentHashMap<String, Long>()
    private val pipDismissTargets = ConcurrentHashMap<String, Long>()
    private val LOCKOUT_DURATION_MS = 5 * 60 * 1000L
    private var lastPipDismissScanTime = 0L

    private lateinit var smartDetector: SmartContentDetector
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())

    private val actionRegex = Regex("(force stop|deactivate|clear storage|clear data|turn off|disable|device admin|accessibility|uninstall)", setOf(RegexOption.IGNORE_CASE))
    // Matches the real package ID, the full app label, or just "Family" (word-bounded),
    // but NOT phrases like "managed by family" used by MDM solutions.
    private val familyRegex = Regex("(?<!managed by\\s)(family protection|com\\.abhi\\.familyprotection|\\bfamily\\b)", setOf(RegexOption.IGNORE_CASE))
    
    // Browsers and social media where AI detection is needed
    private val browserApps = setOf(
        "com.android.chrome", "org.mozilla.firefox", "com.opera.browser",
        "com.microsoft.emmx", "com.brave.browser", "com.duckduckgo.mobile.android",
        "com.sec.android.app.sbrowser", "com.vivaldi.browser", "com.kiwibrowser.browser",
        "org.torproject.torbrowser", "com.ecosia.android", "com.yandex.browser",
        "com.UCMobile.intl", "mobi.mgeek.TunnyBrowser", "com.android.browser",
        "com.qwant.liberty", "com.ghostery.android.ghostery", "com.fingerprintbrowser"
    )
    
    private val socialMediaApps = setOf(
        "com.instagram.android", "com.facebook.katana", "com.zhiliaoapp.musically",
        "com.twitter.android", "com.snapchat.android", "org.telegram.messenger",
        "com.discord", "com.reddit.frontpage", "com.pinterest", "com.tumblr",
        "com.facebook.orca", "com.instagram.barcelona", "com.viber.voip",
        "jp.naver.line.android", "com.tencent.mm", "com.vkontakte.android",
        "com.quora.android", "com.google.android.apps.dynamite", "com.signal",
        "com.kakao.talk", "com.Slack", "com.clubhouse.app", "com.hinge.app",
        "com.bumble.app", "com.tinder", "com.pof.android", "com.hily.app",
        "com.okcupid.okcupid", "com.grindrapp.android", "com.match.android.matchmobile",
        "com.dating.android", "com.likee.video", "com.bigo.live", "tv.twitch.android.app",
        "com.huya.kiwi", "com.kwai.video", "com.ss.android.ugc.trill"
    )

    private val blockedAdultApps = setOf(
        "com.bumble.app",       // Bumble
        "com.tinder",           // Tinder
        "com.pof.android",      // Plenty of Fish (POF)
        "com.hily.app"          // Hily
    )

    // Work/productivity apps (never block)
    private val whitelistPackages = setOf(
        "com.google.android.gm", "com.microsoft.office.outlook","com.linkedin.android",
        "us.zoom.videomeetings", "com.microsoft.teams", "com.google.android.apps.docs",
        "com.google.android.calendar", "com.whatsapp", "com.google.android.youtube",
        "com.google.android.apps.messaging"
    )

    companion object {
        private const val TAG = "AntiUninstallService"
        private const val VERBOSE = false

        // Set true to log "would block" verdicts without acting, for tuning false positives.
        private const val DRY_RUN = false

        private const val EVENT_THROTTLE_MS = 50L
        private const val CONFIRM_DELAY_MS = 700L
        private const val AGGRESSIVE_REARM_CONFIDENCE = 0.95f

        private val RELEVANT_EVENT_TYPES =
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
        
        fun isEnabled(context: Context): Boolean {
            val expectedServiceName = "${context.packageName}/${AntiUninstallAccessibilityService::class.java.name}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.contains(expectedServiceName)
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(applicationContext)
        appListPreferences = applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences("custom_app_lists", Context.MODE_PRIVATE)
            .also { it.registerOnSharedPreferenceChangeListener(appListPreferenceListener) }
        smartDetector = SmartContentDetector(applicationContext)
        Log.d(TAG, "Smart AI detector initialized")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Filtering in the framework is cheaper than discarding unwanted events in our callback.
        serviceInfo?.let { info ->
            info.eventTypes = RELEVANT_EVENT_TYPES
            info.notificationTimeout = EVENT_THROTTLE_MS
            serviceInfo = info
        }
        if (!accessibilitySettingsObserverRegistered) {
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
            accessibilitySettingsObserverRegistered = true
        }
        Log.i(TAG, "Accessibility service connected; app and keyword monitoring is active")
        AppLogger.log(
            applicationContext,
            "Accessibility service connected; app and keyword monitoring is active",
            TAG,
            "I"
        )
    }

    private fun classify(pkgName: String): PackageClass {
        packageClasses[pkgName]?.let { return it }
        val context = applicationContext
        val allBlockedAdult = AppListPreferences.getAllBlockedAdultApps(context)
        val allSocialMedia = AppListPreferences.getAllSocialMediaApps(context)
        val supervisedPatterns = AppListPreferences.getAllSupervisedPatterns(context)

        val packageClass = when {
            pkgName.contains("launcher", ignoreCase = true) ||
                pkgName.contains("systemui", ignoreCase = true) ||
                pkgName.contains("com.abhi.familyprotection") -> PackageClass.WHITELISTED

            allBlockedAdult.any { pattern ->
                AppListPreferences.matchesPattern(pkgName, pattern)
            } -> PackageClass.BLOCKED_ADULT

            supervisedPatterns.any { pattern ->
                AppListPreferences.matchesPattern(pkgName, pattern)
            } -> PackageClass.RISKY

            whitelistPackages.any { pkgName.contains(it, ignoreCase = true) } -> PackageClass.WHITELISTED

            browserApps.any { pkgName.contains(it, ignoreCase = true) } ||
                allSocialMedia.any { pattern -> AppListPreferences.matchesPattern(pkgName, pattern) } -> PackageClass.RISKY

            else -> PackageClass.NORMAL
        }
        return packageClasses.putIfAbsent(pkgName, packageClass) ?: packageClass
    }

    private fun getScanCooldown(elapsedSinceBlock: Long): Long = when {
        elapsedSinceBlock < HOT_AGGRESSIVE_WINDOW -> AGGRESSIVE_SCAN_COOLDOWN
        elapsedSinceBlock < MEDIUM_AGGRESSIVE_WINDOW -> MEDIUM_SCAN_COOLDOWN
        elapsedSinceBlock < AGGRESSIVE_WINDOW -> NORMAL_SCAN_COOLDOWN
        else -> NORMAL_SCAN_COOLDOWN
    }

    private fun getAiCooldown(elapsedSinceBlock: Long): Long = when {
        elapsedSinceBlock < HOT_AGGRESSIVE_WINDOW -> AGGRESSIVE_AI_COOLDOWN
        elapsedSinceBlock < MEDIUM_AGGRESSIVE_WINDOW -> MEDIUM_AI_COOLDOWN
        elapsedSinceBlock < AGGRESSIVE_WINDOW -> NORMAL_AI_COOLDOWN
        else -> NORMAL_AI_COOLDOWN
    }

    private fun isSystemSettingsUi(pkgName: String): Boolean =
        pkgName.contains("settings", ignoreCase = true) ||
            pkgName.contains("packageinstaller", ignoreCase = true) ||
            pkgName.contains("permissioncontroller", ignoreCase = true) ||
            pkgName.contains("securitycenter", ignoreCase = true) ||
            pkgName.contains("systemmanager", ignoreCase = true)

    /** True when the foreground UI is the OS package-installer uninstall confirmation dialog. */
    private fun isPackageInstallerUi(pkgName: String): Boolean =
        pkgName.contains("packageinstaller", ignoreCase = true) ||
            pkgName == "com.android.packageinstaller" ||
            pkgName == "com.google.android.packageinstaller" ||
            pkgName.endsWith(".packageinstaller")

    /**
     * True when the event comes from a home-screen launcher.
     * During long-press drag, the launcher itself emits accessibility events that contain
     * the app label and the "Uninstall" drop-zone text.
     */
    private fun isLauncherUi(pkgName: String): Boolean =
        pkgName.contains("launcher", ignoreCase = true) ||
            pkgName.contains("home", ignoreCase = true) ||
            pkgName == "com.google.android.apps.nexuslauncher" ||
            pkgName == "com.samsung.android.app.launcher" ||
            pkgName == "com.miui.home" ||
            pkgName == "com.oneplus.launcher" ||
            pkgName == "com.huawei.android.launcher" ||
            pkgName == "com.nothing.launcher"

    // Phrases shown by Android's uninstall confirmation dialog (Package Installer)
    private val uninstallConfirmRegex = Regex(
        "(do you want to uninstall|uninstall this app|remove this app|app will be deleted)",
        setOf(RegexOption.IGNORE_CASE)
    )

    // Text shown in the launcher's long-press drag-to-uninstall drop zone
    private val launcherUninstallRegex = Regex(
        "\\buninstall\\b",
        setOf(RegexOption.IGNORE_CASE)
    )

    private fun isBankingOrFinancialApp(pkgName: String): Boolean {
        if (pkgName.isEmpty()) return false
        val lowerPkg = pkgName.lowercase()
        return lowerPkg.contains("bank") ||
            lowerPkg.contains("paytm") ||
            lowerPkg.contains("phonepe") ||
            lowerPkg.contains("paisa") ||
            lowerPkg.contains("upi") ||
            lowerPkg.contains("wallet") ||
            lowerPkg.contains("financial") ||
            lowerPkg.contains("sbi") ||
            lowerPkg.contains("hdfc") ||
            lowerPkg.contains("icici") ||
            lowerPkg.contains("axis") ||
            lowerPkg.contains("kotak") ||
            lowerPkg.contains("baroda") ||
            lowerPkg.contains("paypal") ||
            lowerPkg.contains("revolut") ||
            lowerPkg.contains("chase") ||
            lowerPkg.contains("wellsfargo") ||
            lowerPkg.contains("citibank")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if ((event.eventType and RELEVANT_EVENT_TYPES) == 0) return

        // Nothing on screen to police while the device is asleep.
        if (powerManager?.isInteractive == false) return

        val context = applicationContext
        val pkgName = event.packageName?.toString() ?: ""

        dismissLockedPictureInPicture()

        // Completely ignore banking, payment, and financial apps
        if (isBankingOrFinancialApp(pkgName)) return

        val eventTime = System.currentTimeMillis()
        val isDnsLockActive = DnsPreferences.isActive(context) &&
            eventTime < DnsPreferences.getExpiryMillis(context)
        val isIntentionalAccessibilityPause =
            BankingAccessibilityPause.isIntentionalPauseActive(context, eventTime)

        // ── All-Windows Tamper Scan (covers Recents-overlay bypass) ─────────────────────────────
        // Run on every event so that a Settings window sitting *below* the Recents overlay is
        // caught even when the event's packageName belongs to the launcher.
        val isDeviceAdminLocal = FamilyDeviceAdminReceiver.isDeviceAdmin(context)
        checkAllWindowsForTampering(
            isDeviceAdminLocal && isDnsLockActive && !isIntentionalAccessibilityPause
        )

        // ── Hard Uninstall Block ─────────────────────────────────────────────────
        // Only needed when Device Owner is NOT set.
        // When Device Owner (ADB-level) IS active, setUninstallBlocked() already makes uninstall
        // impossible at the system level with no accessibility overhead — skip this entire guard.
        val isDeviceOwner = FamilyDeviceAdminReceiver.isDeviceOwner(context)
        // isDeviceAdminLocal already computed above for the all-windows scan; reuse it here.
        val isDeviceAdmin = isDeviceAdminLocal

        if (!isDeviceOwner && isDeviceAdmin) {
            // ── 1. Package Installer / Settings uninstall confirmation dialog ────
            if (isPackageInstallerUi(pkgName) || isSystemSettingsUi(pkgName)) {
                val rootNode = rootInActiveWindow
                if (rootNode != null) {
                    val textList = mutableListOf<String>()
                    extractText(rootNode, textList, 0, 50)
                    val combinedText = textList.joinToString(" ").lowercase()

                    val isUninstallDialog = uninstallConfirmRegex.containsMatchIn(combinedText)
                    val isOurApp = familyRegex.containsMatchIn(combinedText)

                    if (isUninstallDialog && isOurApp) {
                        Log.w(TAG, "BLOCKED: Uninstall confirmation dialog for our app via $pkgName")
                        AppLogger.log(context, "[UNINSTALL_BLOCK] pkg=$pkgName | source=dialog | action=2xBACK+HOME", "AntiUninstallService", "E")
                        dismissTamperingScreen()
                        return
                    }
                }
            }

            // ── 2. Launcher long-press drag-to-Uninstall drop zone ───────────────
            // When a user long-presses our app icon and drags it to the "Uninstall" zone,
            // the launcher emits accessibility events showing "Uninstall" and our app label.
            if (isLauncherUi(pkgName)) {
                val rootNode = rootInActiveWindow
                if (rootNode != null) {
                    val textList = mutableListOf<String>()
                    extractText(rootNode, textList, 0, 50)
                    val combinedText = textList.joinToString(" ").lowercase()

                    val isDragToUninstall = launcherUninstallRegex.containsMatchIn(combinedText)
                    val isOurApp = familyRegex.containsMatchIn(combinedText)

                    if (isDragToUninstall && isOurApp) {
                        Log.w(TAG, "BLOCKED: Launcher drag-to-uninstall detected for our app via $pkgName")
                        AppLogger.log(context, "[UNINSTALL_BLOCK] pkg=$pkgName | source=launcher-drag | action=HOME", "AntiUninstallService", "E")
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        Handler(Looper.getMainLooper()).postDelayed({
                            performGlobalAction(GLOBAL_ACTION_HOME)
                        }, 300L)
                        return
                    }
                }
            }
        }

        // ── Settings Tamper Guard (Force-Stop / Disable / Clear Data / Accessibility Off) ────────────
        // Active when Device Admin / Owner or Lock is enabled.
        if (isDeviceAdmin && isSystemSettingsUi(pkgName)) {
            val rootNode = rootInActiveWindow
            if (rootNode != null && isSettingsTargetingOurApp(rootNode)) {
                Log.w(TAG, "BLOCKED: Settings tampering detected specifically targeting our app - $pkgName")
                AppLogger.log(context, "[SETTINGS_TAMPER] pkg=$pkgName | action=2xBACK+HOME", "AntiUninstallService", "W")
                dismissTamperingScreen()
                return
            }
        }

        // ── App & Keyword Blocking (only during an active DNS schedule) ──
        val currentTime = eventTime
        // Reaching this callback means Android has connected and enabled this service.
        // Do not re-check ENABLED_ACCESSIBILITY_SERVICES here: OEMs can update that setting
        // asynchronously, which incorrectly caused a connected service to ignore all events.
        if (!isDnsLockActive) return

        // An Accessibility pause is enforced by disabling this service. If the user manually
        // re-enables it during the timer, resume enforcement immediately; only the explicit
        // content-scan pause should suppress checks while the service is running.
        if (currentTime < DnsPreferences.getContentScanPauseUntil(context)) {
            return
        }

        // 0. Classify app dynamically (never block launchers, system UI, or whitelisted apps)
        val pkgClass = classify(pkgName)
        if (pkgClass == PackageClass.WHITELISTED) return

        if (pkgClass == PackageClass.RISKY) {
            val rootNode = rootInActiveWindow
            if (rootNode != null && rootNode.packageName?.toString() == pkgName) {
                val visibleText = mutableListOf<String>()
                extractText(rootNode, visibleText, 0, 120)
                val matchedKeyword = KeywordPreferences.findHardKeyword(
                    context,
                    visibleText.joinToString(" ")
                )
                if (matchedKeyword != null) {
                    val appName = SmsSender.resolveAppName(context, pkgName)
                    AppLogger.log(
                        context,
                        "[KEYWORD_BLOCK] app=$appName | pkg=$pkgName | keyword=\"$matchedKeyword\" | action=HOME | timedLockout=false",
                        TAG,
                        "E"
                    )
                    pipDismissTargets[pkgName] = currentTime + 60_000L
                    Log.w(TAG, "Keyword block: app=$appName pkg=$pkgName keyword=\"$matchedKeyword\"")
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    Handler(Looper.getMainLooper()).postDelayed({
                        dismissLockedPictureInPicture(force = true)
                    }, 400L)
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (rootInActiveWindow?.packageName?.toString() == pkgName) {
                            performGlobalAction(GLOBAL_ACTION_HOME)
                        }
                    }, 250L)
                    return
                }
            }
        }

        // 1. Enforce custom/adult blocked apps (e.g. com.facebook.katana, Bumble, Tinder)
        if (pkgClass == PackageClass.BLOCKED_ADULT) {
            val appName = SmsSender.resolveAppName(context, pkgName)
            Log.w(TAG, "BLOCKED: Adult/custom blocked app launch - $appName ($pkgName)")
            AppLogger.log(
                context,
                "[APP_BLOCK] app=$appName | pkg=$pkgName | rule=immediate_block_list | action=HOME | timedLockout=5m",
                "AntiUninstallService",
                "E"
            )
            tempLockedApps[pkgName] = System.currentTimeMillis() + LOCKOUT_DURATION_MS
            pipDismissTargets[pkgName] = tempLockedApps[pkgName] ?: 0L
            performGlobalAction(GLOBAL_ACTION_HOME)
            Handler(Looper.getMainLooper()).postDelayed({
                dismissLockedPictureInPicture(force = true)
            }, 400L)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "⛔ Blocked app ($pkgName)", Toast.LENGTH_LONG).show()
            }
            SmsSender.sendBlockedAppAlert(
                context,
                SmsSender.resolveAppName(context, pkgName),
                pkgName
            )
            return
        }

        // The AI content detector is also tied to the DNS schedule.
        if (!isDnsLockActive) return

        // 5-minute lockout check: If this app was thrown away for a violation, block it from opening for 5 minutes
        if (pkgName.isNotBlank()) {
            val lockoutUntil = tempLockedApps[pkgName]
            if (lockoutUntil != null) {
                val remainingMs = lockoutUntil - System.currentTimeMillis()
                if (remainingMs > 0) {
                    val remainingMin = (remainingMs / 1000) / 60
                    val remainingSec = (remainingMs / 1000) % 60
                    Log.w(TAG, "Found video .LOCKED APP LAUNCH BLOCKED: $pkgName (Remaining: ${remainingMin}m ${remainingSec}s)")
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(
                            context,
                            "⛔ App locked for ${remainingMin}m ${remainingSec}s due to violation",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return
                } else {
                    // Lockout duration expired: remove and resume normal/aggressive checks
                    tempLockedApps.remove(pkgName)
                    Log.d(TAG, "5-min lockout expired for $pkgName. Resuming monitoring.")
                    AppLogger.log(context, "[EXPIRY] Lockout expired for $pkgName | resumed monitoring", "AntiUninstallService", "I")
                }
            }
        }

        val scanState = appScanStates.getOrPut(pkgName) { AppScanState() }
        val elapsedSinceBlock = currentTime - scanState.lastBlockedTime
        val scanCooldown = getScanCooldown(elapsedSinceBlock)
        if (currentTime - scanState.lastScanTime < scanCooldown) return
        scanState.lastScanTime = currentTime

        // AI-powered adult content detection (only for browsers/social media)
        if (pkgClass != PackageClass.RISKY) return
        if (currentTime < DnsPreferences.getContentScanPauseUntil(context)) return

        val aiCooldown = getAiCooldown(elapsedSinceBlock)
        if (currentTime - scanState.lastAiScanTime > aiCooldown &&
            scanInProgress.compareAndSet(false, true)
        ) {
            scanState.lastAiScanTime = currentTime
            
            if (VERBOSE) Log.d(TAG, "Starting AI detection for: $pkgName")
            
            serviceScope.launch {
                try {
                    val currentRoot = rootInActiveWindow ?: return@launch
                    if (currentRoot.packageName?.toString() != pkgName) {
                        if (VERBOSE) Log.d(TAG, "Foreground changed before analysis; skipping")
                        return@launch
                    }

                    // Screenshot is captured only if the detector actually needs pixels or OCR.
                    val capture: suspend () -> Bitmap? = {
                        if (rootInActiveWindow?.packageName?.toString() == pkgName) captureScreenshot() else null
                    }

                    val result = smartDetector.analyzeContent(pkgName, currentRoot, capture)
                    if (!result.isBlocked) {
                        if (VERBOSE) Log.d(TAG, "Content allowed: $pkgName - ${result.reason}")
                        return@launch
                    }

                    // Half-loaded pages cause most false positives, so score twice before acting.
                    if (result.method != "explicit") {
                        delay(CONFIRM_DELAY_MS)
                        val confirmRoot = rootInActiveWindow ?: return@launch
                        if (confirmRoot.packageName?.toString() != pkgName) return@launch
                        if (!smartDetector.analyzeContent(pkgName, confirmRoot, capture).isBlocked) {
                            Log.w(TAG, "Unconfirmed block ignored for $pkgName - ${result.reason}")
                            return@launch
                        }
                    }

                    if (result.confidence >= AGGRESSIVE_REARM_CONFIDENCE) {
                        scanState.lastBlockedTime = System.currentTimeMillis()
                    }

                    withContext(Dispatchers.Main) {
                        if (DRY_RUN) {
                            Log.w(TAG, "DRY RUN would block $pkgName - ${result.reason}")
                        } else {
                            Log.w(TAG, "BLOCKING content: $pkgName - ${result.reason}")
                            blockCurrentApp(pkgName, result.reason)
                            Toast.makeText(
                                context,
                                "⛔ BLOCKED: ${result.reason}\nConfidence: ${(result.confidence * 100).toInt()}%",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "AI detection failed for $pkgName", e)
                } finally {
                    scanInProgress.set(false)
                }
            }
        }
    }

    private fun blockCurrentApp(pkgName: String, reason: String) {
        val lockoutUntil = System.currentTimeMillis() + LOCKOUT_DURATION_MS
        tempLockedApps[pkgName] = lockoutUntil
        pipDismissTargets[pkgName] = lockoutUntil
        val appName = SmsSender.resolveAppName(applicationContext, pkgName)
        AppLogger.log(
            applicationContext,
            "[CONTENT_BLOCK] app=$appName | pkg=$pkgName | reason=$reason | action=HOME | timedLockout=5m",
            "AntiUninstallService",
            "E"
        )

        val actionAccepted = performGlobalAction(GLOBAL_ACTION_HOME)
        Log.w(TAG, "Home action accepted=$actionAccepted, reason=$reason, app locked for 5m: $pkgName")
        Handler(Looper.getMainLooper()).postDelayed({
            dismissLockedPictureInPicture(force = true)
        }, 400L)
        Handler(Looper.getMainLooper()).postDelayed({
            val foregroundPackage = rootInActiveWindow?.packageName?.toString()
            if (foregroundPackage == pkgName || (foregroundPackage != null &&
                (browserApps.contains(foregroundPackage) || socialMediaApps.contains(foregroundPackage)))) {
                Log.w(TAG, "Foreground still blocked app: $foregroundPackage; retrying Home")
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }, 250L)
    }

    private fun dismissLockedPictureInPicture(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!DnsPreferences.isActive(applicationContext) ||
            now >= DnsPreferences.getExpiryMillis(applicationContext)
        ) {
            return
        }
        if (!force && now - lastPipDismissScanTime < 500L) return
        lastPipDismissScanTime = now

        pipDismissTargets.entries.removeAll { it.value <= now }
        val targets = pipDismissTargets.keys
        if (targets.isEmpty()) return

        val currentWindows = windows ?: return
        for (pipWindow in currentWindows) {
            if (!pipWindow.isInPictureInPictureMode) continue

            val pipRoot = pipWindow.root ?: continue
            val packageName = pipRoot.packageName?.toString() ?: continue
            if (packageName !in targets) continue

            val pipBounds = Rect().also(pipWindow::getBoundsInScreen)
            if (performDismissAction(pipRoot)) {
                logPipDismissed(packageName, "dismiss action")
                pipDismissTargets.remove(packageName)
                return
            }

            if (clickCloseControl(pipRoot)) {
                logPipDismissed(packageName, "PiP close control")
                pipDismissTargets.remove(packageName)
                return
            }

            for (controlWindow in currentWindows) {
                if (controlWindow.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
                val controlBounds = Rect().also(controlWindow::getBoundsInScreen)
                if (!Rect.intersects(pipBounds, controlBounds)) continue

                val controlRoot = controlWindow.root ?: continue
                if (clickCloseControl(controlRoot)) {
                    logPipDismissed(packageName, "system PiP close control")
                    pipDismissTargets.remove(packageName)
                    return
                }
            }
        }
    }

    private fun performDismissAction(
        node: AccessibilityNodeInfo,
        depth: Int = 0,
        remainingNodes: IntArray = intArrayOf(120)
    ): Boolean {
        if (depth > 8 || remainingNodes[0]-- <= 0) return false
        if (node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_DISMISS } &&
            node.performAction(AccessibilityNodeInfo.ACTION_DISMISS)
        ) {
            return true
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            if (performDismissAction(child, depth + 1, remainingNodes)) return true
        }
        return false
    }

    private fun clickCloseControl(
        node: AccessibilityNodeInfo,
        depth: Int = 0,
        remainingNodes: IntArray = intArrayOf(120)
    ): Boolean {
        if (depth > 8 || remainingNodes[0]-- <= 0) return false
        val label = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            .joinToString(" ")
            .trim()
            .lowercase()
        val isCloseControl = label == "close" ||
            label == "dismiss" ||
            label == "close pip" ||
            label == "close picture in picture" ||
            label == "close picture-in-picture" ||
            label == "close video"
        if (isCloseControl && node.isClickable &&
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        ) {
            return true
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            if (clickCloseControl(child, depth + 1, remainingNodes)) return true
        }
        return false
    }

    private fun logPipDismissed(packageName: String, method: String) {
        val message = "Dismissed PiP for blocked app $packageName using $method"
        Log.i(TAG, message)
        AppLogger.log(applicationContext, "[PIP_DISMISS] pkg=$packageName | method=$method", TAG, "I")
    }

    private suspend fun captureScreenshot(): Bitmap? = suspendCancellableCoroutine { continuation ->
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            Log.w(TAG, "Screenshot API not available (requires Android 11+)")
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        Log.d(TAG, "Attempting screenshot capture...")
        try {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                application.mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        try {
                            val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                                screenshotResult.hardwareBuffer,
                                screenshotResult.colorSpace
                            )
                            val bitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            screenshotResult.hardwareBuffer.close()
                            Log.d(TAG, "Screenshot captured successfully")
                            if (continuation.isActive) continuation.resume(bitmap)
                        } catch (e: Exception) {
                            Log.e(TAG, "Screenshot conversion failed", e)
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "Screenshot failed with code: $errorCode")
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Screenshot access denied; continuing with visible-text analysis", e)
            if (continuation.isActive) continuation.resume(null)
        }
    }

    /**
     * Scans ALL currently visible windows (not just the event source) for an Accessibility
     * settings page that targets this app.  This catches the Recents-overlay bypass where the
     * Settings window sits *behind* the overview screen: the event package is the launcher, so
     * the normal per-event check never fires, but the Settings window is still listed here.
     */
    /**
     * Executes (2x GLOBAL_ACTION_BACK) + GLOBAL_ACTION_HOME with a follow-up pulse.
     * 1st BACK closes any confirmation dialog or bottom sheet.
     * 2nd BACK pops the Accessibility toggle subpage off the Activity stack back to the list.
     * HOME returns the device to the launcher screen.
     * This ensures that reopening Overview/Recents will NOT resume directly onto the toggle switch.
     */
    private fun dismissTamperingScreen() {
        performGlobalAction(GLOBAL_ACTION_BACK)
        performGlobalAction(GLOBAL_ACTION_BACK)
        performGlobalAction(GLOBAL_ACTION_BACK)
        performGlobalAction(GLOBAL_ACTION_HOME)
        Handler(Looper.getMainLooper()).postDelayed({
            val currentRoot = rootInActiveWindow
            val currentPkg = currentRoot?.packageName?.toString() ?: ""
            if (isSystemSettingsUi(currentPkg) || isPackageInstallerUi(currentPkg)) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                performGlobalAction(GLOBAL_ACTION_BACK)
                performGlobalAction(GLOBAL_ACTION_BACK)
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }, 150L)
    }

    /**
     * Scans ALL currently visible windows for an Accessibility settings page or App Info page
     * that specifically targets THIS app. It does not block other accessibility services.
     */
    private fun checkAllWindowsForTampering(isDeviceAdmin: Boolean) {
        if (!isDeviceAdmin) return
        val currentWindows = windows ?: return
        for (window in currentWindows) {
            val root = window.root ?: continue
            val pkg = root.packageName?.toString() ?: continue
            if (!isSystemSettingsUi(pkg)) continue

            if (isSettingsTargetingOurApp(root)) {
                Log.w(TAG, "BLOCKED (all-windows scan): Settings tampering detected targeting our app in $pkg")
                AppLogger.log(
                    applicationContext,
                    "[SETTINGS_TAMPER] pkg=$pkg | source=all-windows | action=2xBACK+HOME",
                    TAG, "W"
                )
                dismissTamperingScreen()
                return
            }
        }
    }

    /**
     * Accurately determines if the displayed Settings screen or dialog specifically targets
     * OUR app (Family Protection) for uninstallation, force stop, clearing data, or disabling
     * its accessibility service.
     *
     * Crucially allows users to browse the general Accessibility list and configure/disable
     * any OTHER accessibility services (e.g. TalkBack) without interference.
     */
    private fun isSettingsTargetingOurApp(rootNode: AccessibilityNodeInfo): Boolean {
        val textList = mutableListOf<String>()
        extractText(rootNode, textList, 0, 50)
        val combinedText = textList.joinToString(" ").lowercase()

        // If our app name or package is not present at all on screen, it is not targeting us.
        if (!familyRegex.containsMatchIn(combinedText)) return false

        // 1. Direct destructive actions on our App Info page in Settings
        val hasDestructiveAction = combinedText.contains("force stop") ||
            combinedText.contains("clear storage") ||
            combinedText.contains("clear data") ||
            combinedText.contains("uninstall") ||
            combinedText.contains("deactivate") ||
            combinedText.contains("disable app") ||
            combinedText.contains("turn off app")

        if (hasDestructiveAction) return true

        // 2. Specific toggle page or confirmation dialog for Family Protection Accessibility
        // Standard Android strings on the dedicated toggle screen for a service:
        // - "Use Family Protection" / "Use Family"
        // - "Stop Family Protection?" / "Stop Family?"
        // - "Turn off Family Protection?" / "Turn off Family?"
        // - "Allow Family Protection to have full control"
        // - "Family Protection shortcut"
        val isOurAccessibilityToggleOrDialog = combinedText.contains("use family") ||
            combinedText.contains("stop family") ||
            combinedText.contains("turn off family") ||
            combinedText.contains("allow family protection") ||
            combinedText.contains("family protection shortcut") ||
            combinedText.contains("family shortcut")

        if (isOurAccessibilityToggleOrDialog) return true

        // 3. Screen title / top header is specifically our app AND the page contains an interactive switch/toggle
        // (Handles customized vendor ROMs like Xiaomi/MIUI, Vivo, Oppo, Samsung that might use different toggle labels)
        val firstFewTexts = textList.take(5).joinToString(" ").lowercase()
        val isTitleOurApp = familyRegex.containsMatchIn(firstFewTexts) &&
            !firstFewTexts.contains("downloaded") &&
            !firstFewTexts.contains("installed") &&
            !firstFewTexts.contains("accessibility")
        if (isTitleOurApp && containsSwitchWidget(rootNode)) {
            return true
        }

        return false
    }

    private fun containsSwitchWidget(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth > 6) return false
        val className = node.className?.toString() ?: ""
        if (className.contains("Switch", ignoreCase = true) ||
            className.contains("ToggleButton", ignoreCase = true) ||
            className.contains("CheckBox", ignoreCase = true)
        ) {
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (containsSwitchWidget(child, depth + 1)) return true
        }
        return false
    }

    private fun extractText(node: AccessibilityNodeInfo, textList: MutableList<String>, depth: Int, maxNodes: Int) {
        if (textList.size >= maxNodes || depth > 5) return
        
        node.text?.toString()?.let { if (it.isNotBlank()) textList.add(it) }
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) textList.add(it) }
        
        for (i in 0 until node.childCount) {
            if (textList.size >= maxNodes) return
            node.getChild(i)?.let { extractText(it, textList, depth + 1, maxNodes) }
        }
    }
    
    override fun onInterrupt() {}
    
    override fun onDestroy() {
        // A restarted service begins in normal scan mode; the aggressive window is session-only.
        contentResolver.unregisterContentObserver(accessibilitySettingsObserver)
        serviceScope.cancel()
        appScanStates.clear()
        appListPreferences?.unregisterOnSharedPreferenceChangeListener(appListPreferenceListener)
        appListPreferences = null
        packageClasses.clear()
        scanInProgress.set(false)
        smartDetector.cleanup()
        super.onDestroy()
    }
}
