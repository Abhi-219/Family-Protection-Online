package com.abhi.familyprotection

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class AntiUninstallAccessibilityService : AccessibilityService() {

    private data class AppScanState(
        @Volatile var lastScanTime: Long = 0L,
        @Volatile var lastAiScanTime: Long = 0L,
        @Volatile var lastBlockedTime: Long = 0L,
        @Volatile var lastContentHash: Int? = null,
        @Volatile var lastContentWasBenign: Boolean = false
    )

    private enum class PackageClass { WHITELISTED, BLOCKED_ADULT, RISKY, NORMAL }

    private val appScanStates = ConcurrentHashMap<String, AppScanState>()
    private val packageClasses = ConcurrentHashMap<String, PackageClass>()
    private var appListPreferences: SharedPreferences? = null
    private val appListPreferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> packageClasses.clear() }
    private val scanInProgress = AtomicBoolean(false)
    private val isAllWindowsScanRunning = AtomicBoolean(false)
    private val forceAiScanPackages = ConcurrentHashMap.newKeySet<String>()

    /**
     * Watches ENABLED_ACCESSIBILITY_SERVICES for external changes (e.g. Recents bypass).
     * If the lock is active and our service disappears from the list, re-add it immediately.
     * This fires even while the Recents overlay is on top, covering the gap that the
     * per-event tamper guard cannot see.
     */


    private val powerManager by lazy { getSystemService(Context.POWER_SERVICE) as? PowerManager }
    private val NORMAL_SCAN_COOLDOWN = 2000L
    private val AGGRESSIVE_SCAN_COOLDOWN = 500L
    private val MEDIUM_SCAN_COOLDOWN = 1000L
    private val NORMAL_AI_COOLDOWN = 5000L
    private val AGGRESSIVE_AI_COOLDOWN = 2000L
    private val MEDIUM_AI_COOLDOWN = 2500L
    private val UNCHANGED_BENIGN_AI_COOLDOWN = 7000L
    private val AGGRESSIVE_WINDOW = 15 * 60 * 1000L
    private val HOT_AGGRESSIVE_WINDOW = 2 * 60 * 1000L
    private val MEDIUM_AGGRESSIVE_WINDOW = 7 * 60 * 1000L
    
    private val tempLockedApps = ConcurrentHashMap<String, Long>()
    private val pipDismissTargets = ConcurrentHashMap<String, Long>()
    private val LOCKOUT_DURATION_MS = 5 * 60 * 1000L
    private var lastPipDismissScanTime = 0L

    private lateinit var smartDetector: SmartContentDetector
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

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

        private const val CONFIRM_DELAY_MS = 700L
        private const val AGGRESSIVE_REARM_CONFIDENCE = 0.95f
        private const val SCROLL_SETTLE_MS = 1_000L
        private const val POWER_STATE_CACHE_MS = 30_000L
        private const val LOW_BATTERY_PERCENT = 15
        private const val POWER_SAVE_AI_FACTOR = 3L

        private val RELEVANT_EVENT_TYPES =
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or
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

    private var lastAllWindowsScanTime = 0L
    private val scanRequests = Channel<String>(Channel.CONFLATED)
    private var scanWorkerJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var stallRunnable: Runnable? = null
    @Volatile private var hasWarnedMissingUsageAccess = false
    private val bankingHandlingInProgress = AtomicBoolean(false)
    private var scrollSettleJob: Job? = null
    @Volatile private var powerFactor = 1L
    @Volatile private var powerFactorCheckedAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo?.let { info ->
            info.eventTypes = RELEVANT_EVENT_TYPES
            info.notificationTimeout = 250L
            serviceInfo = info
        }

        // Main thread stall detector (diagnostic tool, disabled in production)
        if (VERBOSE) {
            stallRunnable?.let { mainHandler.removeCallbacks(it) }
            var lastTick = SystemClock.elapsedRealtime()
            val runnable = object : Runnable {
                override fun run() {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastTick > 3000) {
                        AppLogger.log(applicationContext, "[MAIN_STALL] Main thread stalled for ${now - lastTick}ms", TAG, "E")
                        Log.e(TAG, "Main thread stalled for ${now - lastTick}ms")
                    }
                    lastTick = now
                    mainHandler.postDelayed(this, 1000)
                }
            }
            stallRunnable = runnable
            mainHandler.post(runnable)
        }

        // Single background worker loop for processing scan requests
        scanWorkerJob?.cancel()
        scanWorkerJob = serviceScope.launch {
            for (pkg in scanRequests) {
                try {
                    processEvent(pkg, pkg in forceAiScanPackages)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.e(TAG, "Background event processing error for $pkg", t)
                }
                delay(300L)
            }
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

        // Priority: own app > Blocked > Supervised > system/whitelist > browsers/social.
        val packageClass = when {
            pkgName.contains("com.abhi.familyprotection") -> PackageClass.WHITELISTED

            allBlockedAdult.any { pattern ->
                AppListPreferences.matchesPattern(pkgName, pattern)
            } -> PackageClass.BLOCKED_ADULT

            supervisedPatterns.any { pattern ->
                AppListPreferences.matchesPattern(pkgName, pattern)
            } -> PackageClass.RISKY

            pkgName.contains("launcher", ignoreCase = true) ||
                pkgName.contains("systemui", ignoreCase = true) -> PackageClass.WHITELISTED

            whitelistPackages.any { pkgName.contains(it, ignoreCase = true) } -> PackageClass.WHITELISTED

            browserApps.any { pkgName.contains(it, ignoreCase = true) } ||
                allSocialMedia.any { pattern -> AppListPreferences.matchesPattern(pkgName, pattern) } ||
                AppListPreferences.isWebBrowser(context, pkgName) -> PackageClass.RISKY

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

    // Slows only screenshot/AI scans; keyword, block-list and tamper checks stay at full speed.
    private fun aiPowerFactor(): Long {
        val now = SystemClock.elapsedRealtime()
        if (now - powerFactorCheckedAt < POWER_STATE_CACHE_MS) return powerFactor
        powerFactorCheckedAt = now
        val pm = powerManager
        val battery = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        val lowBattery = battery != null && !battery.isCharging && level in 1..LOW_BATTERY_PERCENT
        val hot = pm != null && pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        powerFactor = if (pm?.isPowerSaveMode == true || hot || lowBattery) POWER_SAVE_AI_FACTOR else 1L
        return powerFactor
    }

    private fun isSystemSettingsUi(pkgName: String): Boolean =
        pkgName.contains("settings", ignoreCase = true) ||
            pkgName.contains("packageinstaller", ignoreCase = true) ||
            pkgName.contains("permissioncontroller", ignoreCase = true) ||
            pkgName.contains("securitycenter", ignoreCase = true) ||
            pkgName.contains("systemmanager", ignoreCase = true) ||
            pkgName.contains("iqoo.secure", ignoreCase = true) ||
            pkgName.contains("permissionmanager", ignoreCase = true)

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

    private val emergencyDialerPackages = setOf(
        "com.android.dialer", "com.google.android.dialer", "com.samsung.android.incallui",
        "com.android.phone", "com.samsung.android.dialer", "com.vivo.daemon.socrates",
        "com.apple.movetoios", "com.android.server.telecom"
    )

    private fun isEmergencyDialer(pkgName: String): Boolean {
        if (pkgName.isEmpty()) return false
        val lower = pkgName.lowercase()
        return lower in emergencyDialerPackages || lower.contains("dialer") || lower.contains("incallui")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if ((event.eventType and RELEVANT_EVENT_TYPES) == 0) return
        if (powerManager?.isInteractive == false) return

        val pkgName = event.packageName?.toString() ?: ""
        if (pkgName.isEmpty()) return

        val now = System.currentTimeMillis()
        val isLockActive = DnsPreferences.isActive(applicationContext) &&
            now < DnsPreferences.getExpiryMillis(applicationContext)
        val hasLockedWindowTargets = tempLockedApps.isNotEmpty() || pipDismissTargets.isNotEmpty()
        val isWindowBoundaryEvent =
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val isSystemWindowSource = pkgName.contains("launcher", ignoreCase = true) ||
            pkgName.contains("systemui", ignoreCase = true)
        val shouldScanAllWindows = (isLockActive || hasLockedWindowTargets) &&
            (isWindowBoundaryEvent || isSystemWindowSource) &&
            now - lastAllWindowsScanTime > 700L &&
            isAllWindowsScanRunning.compareAndSet(false, true)
        if (shouldScanAllWindows) {
            lastAllWindowsScanTime = now
            serviceScope.launch(Dispatchers.IO) {
                try {
                    if (tempLockedApps.isNotEmpty() || pipDismissTargets.isNotEmpty()) {
                        dismissLockedPictureInPicture()
                        checkAllWindowsForLockedApps()
                    }

                    val context = applicationContext
                    val lockStillActive = DnsPreferences.isActive(context) &&
                        System.currentTimeMillis() < DnsPreferences.getExpiryMillis(context)
                    val isIntentionalAccessibilityPause =
                        BankingAccessibilityPause.isIntentionalPauseActive(context)
                    val isDeviceAdminLocal = FamilyDeviceAdminReceiver.isDeviceAdmin(context)

                    if (lockStillActive && isDeviceAdminLocal && !isIntentionalAccessibilityPause) {
                        checkAllWindowsForTampering(true)
                    }
                } finally {
                    isAllWindowsScanRunning.set(false)
                }
            }
        }

        // Use the event source package directly; the active root can still be the launcher during launch.
        if (pkgName.trim().lowercase() in BankingAccessibilityPause.getBankingPackages(applicationContext)) {
            if (bankingHandlingInProgress.compareAndSet(false, true)) {
                serviceScope.launch {
                    try {
                        if (!handleBankingForeground(pkgName)) scanRequests.trySend(pkgName)
                    } finally {
                        bankingHandlingInProgress.set(false)
                    }
                }
            }
            return
        }
        hasWarnedMissingUsageAccess = false

        // 2. Early Filter for Emergency Dialers & Whitelisted Packages
        if (isEmergencyDialer(pkgName)) return
        // Cache-only on the main thread; uncached packages are classified by the background worker.
        if (packageClasses[pkgName] == PackageClass.WHITELISTED) return

        // 3. Queue scan request
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            // Force the AI scan once the page settles, not on every scroll step.
            scrollSettleJob?.cancel()
            scrollSettleJob = serviceScope.launch {
                delay(SCROLL_SETTLE_MS)
                forceAiScanPackages.add(pkgName)
                scanRequests.trySend(pkgName)
            }
            return
        }
        scanRequests.trySend(pkgName)
    }

    // Runs off the main thread: settings writes, usage-stats and package queries are binder calls.
    private fun handleBankingForeground(bankingForegroundPackage: String): Boolean {
        if (!BankingAccessibilityPause.isBankingOrFinancialApp(applicationContext, bankingForegroundPackage)) {
            return false
        }
        val usageAccess = BankingAccessibilityPause.hasUsageAccess(applicationContext)
        if (!usageAccess) {
            Log.w(TAG, "Banking app detected but Usage Access is not granted; accessibility remains enabled")
            logBankingDecision(
                "detected pkg=$bankingForegroundPackage usageAccess=false pauseStarted=false " +
                    "serviceStartOk=false disableOk=false reason=usage_access_missing",
                "W"
            )
            if (!hasWarnedMissingUsageAccess) {
                mainHandler.post {
                    Toast.makeText(
                        applicationContext,
                        "Enable Usage Access in Family Protection to use banking apps",
                        Toast.LENGTH_LONG
                    ).show()
                }
                hasWarnedMissingUsageAccess = true
            }
            return true
        }
        hasWarnedMissingUsageAccess = false
        val pauseStarted =
            BankingAccessibilityPause.startAutomaticPause(applicationContext, bankingForegroundPackage)
        if (!pauseStarted) {
            logBankingDecision(
                "detected pkg=$bankingForegroundPackage usageAccess=true pauseStarted=false " +
                    "serviceStartOk=false disableOk=false reason=pause_persistence_failed",
                "E"
            )
            return true
        }

        val serviceStartOk = DnsForegroundService.start(applicationContext)
        if (!serviceStartOk) {
            Log.e(TAG, "Cannot start the banking foreground monitor")
            BankingAccessibilityPause.endAutomaticPause(applicationContext)
            logBankingDecision(
                "detected pkg=$bankingForegroundPackage usageAccess=true pauseStarted=true " +
                    "serviceStartOk=false disableOk=false reason=monitor_start_failed",
                "E"
            )
            return true
        }

        val disableOk = BankingAccessibilityPause.disableServiceInSettings(applicationContext)
        logBankingDecision(
            "detected pkg=$bankingForegroundPackage usageAccess=true pauseStarted=true " +
                "serviceStartOk=true disableOk=$disableOk" +
                if (disableOk) "" else " reason=service_disable_failed",
            if (disableOk) "I" else "E"
        )
        if (!disableOk) {
            Log.e(TAG, "Could not pause accessibility for foreground banking app")
            BankingAccessibilityPause.endAutomaticPause(applicationContext)
            if (!DnsPreferences.isActive(applicationContext)) {
                DnsForegroundService.stop(applicationContext)
            }
        }
        return true
    }

    private fun logBankingDecision(message: String, level: String) {
        AppLogger.log(applicationContext, "[BANKING] $message", TAG, level)
    }

    private suspend fun processEvent(pkgName: String, forceAiScan: Boolean) {
        val context = applicationContext
        val eventTime = System.currentTimeMillis()
        val isDnsLockActive = DnsPreferences.isActive(context) && eventTime < DnsPreferences.getExpiryMillis(context)

        // Uninstall & Settings Tamper Checks
        val isDeviceAdminLocal = FamilyDeviceAdminReceiver.isDeviceAdmin(context)
        val isDeviceOwner = FamilyDeviceAdminReceiver.isDeviceOwner(context)

        if (!isDeviceOwner && isDeviceAdminLocal) {
            if (isPackageInstallerUi(pkgName) || isSystemSettingsUi(pkgName)) {
                val rootNode = rootInActiveWindow
                if (rootNode != null) {
                    val textList = mutableListOf<String>()
                    extractText(rootNode, textList, 0, 50)
                    val combinedText = textList.joinToString(" ").lowercase()
                    if (uninstallConfirmRegex.containsMatchIn(combinedText) && familyRegex.containsMatchIn(combinedText)) {
                        Log.w(TAG, "BLOCKED: Uninstall confirmation dialog for our app via $pkgName")
                        AppLogger.log(context, "[UNINSTALL_BLOCK] pkg=$pkgName | source=dialog | action=2xBACK+HOME", "AntiUninstallService", "E")
                        dismissTamperingScreen()
                        return
                    }
                }
            }

            if (isLauncherUi(pkgName)) {
                val rootNode = rootInActiveWindow
                if (rootNode != null) {
                    val textList = mutableListOf<String>()
                    extractText(rootNode, textList, 0, 50)
                    val combinedText = textList.joinToString(" ").lowercase()
                    if (launcherUninstallRegex.containsMatchIn(combinedText) && familyRegex.containsMatchIn(combinedText)) {
                        Log.w(TAG, "BLOCKED: Launcher drag-to-uninstall detected for our app via $pkgName")
                        AppLogger.log(context, "[UNINSTALL_BLOCK] pkg=$pkgName | source=launcher-drag | action=LOCK", "AntiUninstallService", "E")
                        lockDeviceScreen()
                        return
                    }
                }
            }
        }

        if (isDeviceAdminLocal && isSystemSettingsUi(pkgName)) {
            val rootNode = rootInActiveWindow
            if (rootNode != null && isSettingsTargetingOurApp(rootNode)) {
                Log.w(TAG, "BLOCKED: Settings tampering detected specifically targeting our app - $pkgName")
                AppLogger.log(context, "[SETTINGS_TAMPER] pkg=$pkgName | action=2xBACK+HOME", "AntiUninstallService", "W")
                dismissTamperingScreen()
                return
            }
        }

        if (!isDnsLockActive) return

        // Moving the clock forward would end the lock early, so the Date & time page is off-limits while locked.
        if (isSystemSettingsUi(pkgName)) {
            val rootNode = rootInActiveWindow
            if (rootNode != null && isDateTimeSettingsScreen(rootNode)) {
                Log.w(TAG, "BLOCKED: Date & time settings opened during lock - $pkgName")
                AppLogger.log(context, "[CLOCK_TAMPER] pkg=$pkgName | action=2xBACK+HOME", TAG, "W")
                VigilanceManager.recordViolation(context, "Date & time settings opened during lock")
                dismissTamperingScreen()
                return
            }
        }

        if (eventTime < DnsPreferences.getContentScanPauseUntil(context)) return

        val pkgClass = classify(pkgName)
        if (pkgClass == PackageClass.WHITELISTED) return

        var currentScreenHash: Int? = null
        if (pkgClass == PackageClass.RISKY) {
            val rootNode = findRootForPackage(pkgName)
            if (rootNode != null) {
                val screenData = extractScreenData(rootNode, maxNodes = 120)
                currentScreenHash = screenData.contentHash
                val matchedKeyword = KeywordPreferences.findHardKeyword(
                    context,
                    screenData.visibleText.joinToString(" ")
                )
                if (matchedKeyword != null) {
                    val appName = SmsSender.resolveAppName(context, pkgName)
                    AppLogger.log(
                        context,
                        "[KEYWORD_BLOCK] app=$appName | pkg=$pkgName | keyword=\"$matchedKeyword\" | action=LOCK | timedLockout=false",
                        TAG,
                        "E"
                    )
                    pipDismissTargets[pkgName] = eventTime + 60_000L
                    Log.w(TAG, "Keyword block: app=$appName pkg=$pkgName keyword=\"$matchedKeyword\"")
                    lockDeviceScreen()
                    return
                }
            }
        }

        if (pkgClass == PackageClass.BLOCKED_ADULT) {
            val appName = SmsSender.resolveAppName(context, pkgName)
            Log.w(TAG, "BLOCKED: Adult/custom blocked app launch - $appName ($pkgName)")
            AppLogger.log(
                context,
                "[APP_BLOCK] app=$appName | pkg=$pkgName | rule=immediate_block_list | action=LOCK | timedLockout=5m",
                "AntiUninstallService",
                "E"
            )
            tempLockedApps[pkgName] = System.currentTimeMillis() + LOCKOUT_DURATION_MS
            pipDismissTargets[pkgName] = tempLockedApps[pkgName] ?: 0L
            lockDeviceScreen()
            return
        }

        if (pkgName.isNotBlank()) {
            val lockoutUntil = tempLockedApps[pkgName]
            if (lockoutUntil != null) {
                val remainingMs = lockoutUntil - System.currentTimeMillis()
                if (remainingMs > 0) {
                    Log.w(TAG, "LOCKED APP LAUNCH BLOCKED: $pkgName")
                    lockDeviceScreen()
                    return
                } else {
                    tempLockedApps.remove(pkgName)
                }
            }
        }

        val scanState = appScanStates.getOrPut(pkgName) { AppScanState() }
        val elapsedSinceBlock = eventTime - scanState.lastBlockedTime
        val scanCooldown = getScanCooldown(elapsedSinceBlock)
        if (!forceAiScan && eventTime - scanState.lastScanTime < scanCooldown) return
        if (forceAiScan && eventTime - scanState.lastAiScanTime < NORMAL_AI_COOLDOWN * aiPowerFactor()) return
        scanState.lastScanTime = eventTime

        if (pkgClass != PackageClass.RISKY) {
            forceAiScanPackages.remove(pkgName)
            return
        }

        val currentRoot = findRootForPackage(pkgName) ?: return
        val contentHash = currentScreenHash ?: extractScreenData(currentRoot, maxNodes = 120).contentHash
        val contentChanged = scanState.lastContentHash != contentHash
        val aiCooldown = if (!contentChanged && scanState.lastContentWasBenign && !forceAiScan) {
            UNCHANGED_BENIGN_AI_COOLDOWN
        } else {
            getAiCooldown(elapsedSinceBlock)
        }
        if (eventTime - scanState.lastAiScanTime > aiCooldown * aiPowerFactor() &&
            scanInProgress.compareAndSet(false, true)
        ) {
            scanState.lastAiScanTime = eventTime
            if (forceAiScan) forceAiScanPackages.remove(pkgName)
            try {
                val capture: suspend () -> Bitmap? = {
                    if (findRootForPackage(pkgName) != null) captureScreenshot() else null
                }

                val result = smartDetector.analyzeContent(pkgName, currentRoot, capture, forceAiScan)
                scanState.lastContentHash = contentHash
                scanState.lastContentWasBenign = !result.isBlocked && result.method != "error"
                    if (!result.isBlocked) {
                        if (VERBOSE) Log.d(TAG, "Content allowed: $pkgName - ${result.reason}")
                        return
                    }

                    // Half-loaded pages cause most false positives, so score twice before acting.
                    if (result.method != "explicit") {
                        delay(CONFIRM_DELAY_MS)
                        val confirmRoot = rootInActiveWindow ?: return
                        if (confirmRoot.packageName?.toString() != pkgName) return
                        if (!smartDetector.analyzeContent(pkgName, confirmRoot, capture, true).isBlocked) {
                            Log.w(TAG, "Unconfirmed block ignored for $pkgName - ${result.reason}")
                            return
                        }
                    }

                    if (result.confidence >= AGGRESSIVE_REARM_CONFIDENCE) {
                        scanState.lastBlockedTime = System.currentTimeMillis()
                    }

                    if (DRY_RUN) {
                        Log.w(TAG, "DRY RUN would block $pkgName - ${result.reason}")
                    } else {
                        Log.w(TAG, "BLOCKING content: $pkgName - ${result.reason}")
                        // Kept off Main: it resolves labels, writes prefs and sends SMS.
                        blockCurrentApp(pkgName, result.reason)
                        withContext(Dispatchers.Main) {
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

    private fun blockCurrentApp(pkgName: String, reason: String) {
        if (pkgName == applicationContext.packageName || pkgName.contains("launcher", ignoreCase = true) || isEmergencyDialer(pkgName)) return
        val lockoutUntil = System.currentTimeMillis() + LOCKOUT_DURATION_MS
        tempLockedApps[pkgName] = lockoutUntil
        pipDismissTargets[pkgName] = lockoutUntil
        val appName = SmsSender.resolveAppName(applicationContext, pkgName)
        AppLogger.log(
            applicationContext,
            "[CONTENT_BLOCK] app=$appName | pkg=$pkgName | reason=$reason | action=LOCK | timedLockout=5m",
            "AntiUninstallService",
            "E"
        )

        lockDeviceScreen()
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, "⛔ Blocked ($reason)", Toast.LENGTH_LONG).show()
        }
        SmsSender.sendBlockedAppAlert(applicationContext, appName, pkgName)
        Log.w(TAG, "Screen locked due to content violation, reason=$reason, app locked for 5m: $pkgName")
        Handler(Looper.getMainLooper()).postDelayed({
            dismissLockedPictureInPicture(force = true)
        }, 400L)
        Handler(Looper.getMainLooper()).postDelayed({
            val foregroundPackage = rootInActiveWindow?.packageName?.toString()
            if (foregroundPackage == pkgName || (foregroundPackage != null &&
                (browserApps.contains(foregroundPackage) || socialMediaApps.contains(foregroundPackage)))) {
                Log.w(TAG, "Foreground still blocked app: $foregroundPackage; retrying Lock")
                lockDeviceScreen()
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
                // Full-screen bitmap copy is ~10 MB; do it off the main thread.
                Dispatchers.Default.asExecutor(),
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        try {
                            val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                                screenshotResult.hardwareBuffer,
                                screenshotResult.colorSpace
                            )
                            val bitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            hardwareBitmap?.recycle()
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
        VigilanceManager.recordViolation(applicationContext, "Settings tampering dismissed")
        mainHandler.post {
            // Step 1: 1st Back
            performGlobalAction(GLOBAL_ACTION_BACK)

            mainHandler.postDelayed({
                // Step 2: 2nd Back
                performGlobalAction(GLOBAL_ACTION_BACK)

                mainHandler.postDelayed({
                    // Step 3: 3rd Back
                    performGlobalAction(GLOBAL_ACTION_BACK)

                    mainHandler.postDelayed({
                        // Step 4: Home
                        performGlobalAction(GLOBAL_ACTION_HOME)
                    }, 120L)
                }, 120L)
            }, 120L)
        }
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

    private fun checkAllWindowsForLockedApps() {
        val now = System.currentTimeMillis()
        tempLockedApps.entries.removeIf { it.value <= now }
        if (tempLockedApps.isEmpty()) return

        val currentWindows = windows ?: return
        for (window in currentWindows) {
            val root = window.root ?: continue
            val pkg = root.packageName?.toString() ?: continue
            if (pkg == applicationContext.packageName || pkg.contains("launcher", ignoreCase = true)) continue
            if (tempLockedApps.containsKey(pkg)) {
                Log.w(TAG, "BLOCKED (all-windows lockout scan): Locked app card/window detected in Recents/screen - $pkg")
                lockDeviceScreen()
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

        // 1.1 Battery optimization / restriction tampering
        val hasBatteryTampering = combinedText.contains("battery") &&
            (combinedText.contains("restricted") ||
             combinedText.contains("restrict") ||
             combinedText.contains("optimize") ||
             combinedText.contains("optimization") ||
             combinedText.contains("background"))

        if (hasBatteryTampering) return true

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

    private fun isDateTimeSettingsScreen(rootNode: AccessibilityNodeInfo): Boolean {
        val textList = mutableListOf<String>()
        extractText(rootNode, textList, 0, 50)
        val text = textList.joinToString(" ").lowercase()
        val isDateTimePage = text.contains("date & time") || text.contains("date and time")
        if (!isDateTimePage) return false
        return text.contains("automatic") || text.contains("auto-set") || text.contains("set time") ||
            text.contains("set date") || text.contains("network-provided") || text.contains("time zone")
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

    private fun findRootForPackage(targetPkg: String): AccessibilityNodeInfo? {
        val activeRoot = rootInActiveWindow
        if (activeRoot != null && activeRoot.packageName?.toString() == targetPkg) return activeRoot

        val currentWindows = windows ?: return activeRoot
        for (window in currentWindows) {
            val windowRoot = window.root ?: continue
            if (windowRoot.packageName?.toString() == targetPkg) {
                return windowRoot
            }
        }
        return activeRoot
    }

    private fun extractText(node: AccessibilityNodeInfo, textList: MutableList<String>, depth: Int, maxNodes: Int = 60) {
        if (textList.size >= maxNodes || depth > 5) return
        
        node.text?.toString()?.let { if (it.isNotBlank()) textList.add(it) }
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) textList.add(it) }
        
        for (i in 0 until node.childCount) {
            if (textList.size >= maxNodes) return
            node.getChild(i)?.let { extractText(it, textList, depth + 1, maxNodes) }
        }
    }

    private data class ExtractedScreenData(
        val visibleText: List<String>,
        val contentHash: Int
    )

    private fun extractScreenData(rootNode: AccessibilityNodeInfo, maxNodes: Int = 120): ExtractedScreenData {
        val visibleText = ArrayList<String>(maxNodes)
        extractText(rootNode, visibleText, 0, maxNodes = maxNodes)
        val contentHash = visibleText.joinToString("\u001F").hashCode()
        return ExtractedScreenData(visibleText, contentHash)
    }
    
    private fun lockDeviceScreen() {
        VigilanceManager.recordViolation(applicationContext, "Tamper / Content violation triggered")
        mainHandler.post {
            // Step 1: 1st Back press (0ms)
            performGlobalAction(GLOBAL_ACTION_BACK)

            mainHandler.postDelayed({
                // Step 2: 2nd Back press (120ms)
                performGlobalAction(GLOBAL_ACTION_BACK)

                mainHandler.postDelayed({
                    // Step 3: Home action (240ms)
                    performGlobalAction(GLOBAL_ACTION_HOME)

                    mainHandler.postDelayed({
                        // Step 4: Screen Lock (360ms)
                        val dpmService = getSystemService(DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                        val lockedByDeviceOwner = if (
                            dpmService != null && FamilyDeviceAdminReceiver.isDeviceOwner(applicationContext)
                        ) {
                            try {
                                dpmService.lockNow()
                                true
                            } catch (e: SecurityException) {
                                Log.e(TAG, "Device owner cannot lock the screen; using accessibility action", e)
                                false
                            }
                        } else {
                            false
                        }

                        if (!lockedByDeviceOwner) {
                            val lockedByAccessibility = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                            if (!lockedByAccessibility) {
                                Log.e(TAG, "Could not lock screen after handling violation")
                            }
                        }
                    }, 120L)
                }, 120L)
            }, 120L)
        }
    }

    override fun onInterrupt() {}
    
    override fun onDestroy() {
        stallRunnable?.let { mainHandler.removeCallbacks(it) }
        scanWorkerJob?.cancel()
        AccessibilityGuard.heal(applicationContext, "onDestroy")
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
