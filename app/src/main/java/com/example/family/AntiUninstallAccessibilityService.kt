package com.example.family

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
    private val scanInProgress = AtomicBoolean(false)
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
    
    private lateinit var smartDetector: SmartContentDetector
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())

    private val actionRegex = Regex("(force stop|deactivate|clear storage|clear data|turn off|disable|device admin|accessibility)", RegexOption.IGNORE_CASE)
    private val familyRegex = Regex("(?<!managed by\\s)family|com\\.example\\.family", RegexOption.IGNORE_CASE)
    
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
        "com.facebook.orca", "com.instagram.barcelona",
        "com.google.android.apps.messaging", "com.viber.voip",
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
        "com.google.android.calendar", "com.whatsapp", "com.google.android.youtube"
    )

    companion object {
        private const val TAG = "AntiUninstallService"
        private const val VERBOSE = false

        // Set true to log "would block" verdicts without acting, for tuning false positives.
        private const val DRY_RUN = false

        private const val EVENT_THROTTLE_MS = 100L
        private const val CONFIRM_DELAY_MS = 700L
        private const val AGGRESSIVE_REARM_CONFIDENCE = 0.95f

        private val RELEVANT_EVENT_TYPES =
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        
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
    }

    private fun classify(pkgName: String): PackageClass = when {
        pkgName.contains("launcher", ignoreCase = true) ||
            pkgName.contains("systemui", ignoreCase = true) ||
            pkgName.contains("com.example.family") ||
            whitelistPackages.any { pkgName.contains(it) } -> PackageClass.WHITELISTED

        blockedAdultApps.any {
            pkgName == it || (pkgName.startsWith(it) && pkgName.length > it.length && pkgName[it.length] == '.')
        } -> PackageClass.BLOCKED_ADULT

        browserApps.any { pkgName.contains(it) } ||
            socialMediaApps.any { pkgName.contains(it) } -> PackageClass.RISKY

        else -> PackageClass.NORMAL
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if ((event.eventType and RELEVANT_EVENT_TYPES) == 0) return

        // Nothing on screen to police while the device is asleep.
        if (powerManager?.isInteractive == false) return

        val context = applicationContext

        // Only enforce while lock is active
        if (!DnsPreferences.isActive(context)) return
        val expiryMillis = DnsPreferences.getExpiryMillis(context)
        if (System.currentTimeMillis() >= expiryMillis) return

        val pkgName = event.packageName?.toString() ?: ""

        // 0. Never block launchers, system UI, or work apps
        val pkgClass = packageClasses.getOrPut(pkgName) { classify(pkgName) }
        if (pkgClass == PackageClass.WHITELISTED) return

        if (pkgClass == PackageClass.BLOCKED_ADULT) {
            Log.w(TAG, "BLOCKED: Adult app launch - $pkgName")
            performGlobalAction(GLOBAL_ACTION_HOME)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Blocked app", Toast.LENGTH_LONG).show()
            }
            return
        }

        val currentTime = System.currentTimeMillis()
        val scanState = appScanStates.getOrPut(pkgName) { AppScanState() }
        val elapsedSinceBlock = currentTime - scanState.lastBlockedTime
        val scanCooldown = getScanCooldown(elapsedSinceBlock)
        if (currentTime - scanState.lastScanTime < scanCooldown) return
        scanState.lastScanTime = currentTime

        // Tamper wording only counts inside system settings UI; elsewhere "family" plus
        // "turn off" is ordinary app text.
        if (isSystemSettingsUi(pkgName)) {
            val rootNode = rootInActiveWindow ?: return
            val textList = mutableListOf<String>()
            extractText(rootNode, textList, 0, 30) // Limit to 30 nodes for speed

            val combinedText = textList.joinToString(" ").lowercase()
            val isDnsSettings = combinedText.contains("dns") || combinedText.contains("private dns")
            val isTargetingThisApp = familyRegex.containsMatchIn(combinedText) &&
                actionRegex.containsMatchIn(combinedText)

            if (isTargetingThisApp || isDnsSettings) {
                Log.w(TAG, "BLOCKED: Settings tampering - $pkgName")
                performGlobalAction(GLOBAL_ACTION_HOME)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "⛔ BLOCKED: Settings tampering attempt", Toast.LENGTH_LONG).show()
                }
            }
            return
        }

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
                            blockCurrentApp(result.reason)
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

    private fun blockCurrentApp(reason: String) {
        val actionAccepted = performGlobalAction(GLOBAL_ACTION_HOME)
        Log.w(TAG, "Home action accepted=$actionAccepted, reason=$reason")
        Handler(Looper.getMainLooper()).postDelayed({
            val foregroundPackage = rootInActiveWindow?.packageName?.toString()
            if (foregroundPackage != null &&
                (browserApps.contains(foregroundPackage) || socialMediaApps.contains(foregroundPackage))) {
                Log.w(TAG, "Foreground still blocked app: $foregroundPackage; retrying Home")
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }, 250L)
    }

    private suspend fun captureScreenshot(): Bitmap? = suspendCancellableCoroutine { continuation ->
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            Log.w(TAG, "Screenshot API not available (requires Android 11+)")
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        Log.d(TAG, "Attempting screenshot capture...")
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
        serviceScope.cancel()
        appScanStates.clear()
        packageClasses.clear()
        scanInProgress.set(false)
        smartDetector.cleanup()
        super.onDestroy()
    }
}
