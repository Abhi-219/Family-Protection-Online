package com.example.family

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
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

    private val appScanStates = ConcurrentHashMap<String, AppScanState>()
    private val scanInProgress = AtomicBoolean(false)
    private val NORMAL_SCAN_COOLDOWN = 2000L
    private val AGGRESSIVE_SCAN_COOLDOWN = 500L
    private val NORMAL_AI_COOLDOWN = 3000L
    private val AGGRESSIVE_AI_COOLDOWN = 1000L
    private val AGGRESSIVE_WINDOW = 15 * 60 * 1000L
    
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val context = applicationContext

        // Only enforce while lock is active
        if (!DnsPreferences.isActive(context)) {
            Log.d(TAG, "DNS lock not active, skipping")
            return
        }
        val expiryMillis = DnsPreferences.getExpiryMillis(context)
        if (System.currentTimeMillis() >= expiryMillis) {
            Log.d(TAG, "DNS lock expired, skipping")
            return
        }

        val pkgName = event.packageName?.toString() ?: ""
        Log.d(TAG, "Checking package: $pkgName")
        
        // 0. Never block launchers, system UI, or work apps
        if (pkgName.contains("launcher", ignoreCase = true) || 
            pkgName.contains("systemui", ignoreCase = true) || 
            pkgName.contains("com.example.family") || 
            whitelistPackages.any { pkgName.contains(it) }) {
            Log.d(TAG, "Whitelisted package: $pkgName")
            return
        }

        val isBlockedAdultApp = blockedAdultApps.any {
            pkgName == it || pkgName.startsWith("$it.")
        }
        if (isBlockedAdultApp) {
            Log.w(TAG, "BLOCKED: Adult app launch - $pkgName")
            performGlobalAction(GLOBAL_ACTION_HOME)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Blocked app", Toast.LENGTH_LONG).show()
            }
            return
        }

        val currentTime = System.currentTimeMillis()
        val scanState = appScanStates.getOrPut(pkgName) { AppScanState() }
        val aggressive = currentTime - scanState.lastBlockedTime < AGGRESSIVE_WINDOW
        val scanCooldown = if (aggressive) AGGRESSIVE_SCAN_COOLDOWN else NORMAL_SCAN_COOLDOWN
        if (currentTime - scanState.lastScanTime < scanCooldown) return
        scanState.lastScanTime = currentTime
        
        Log.d(TAG, "Starting content scan for: $pkgName")
        
        val rootNode = rootInActiveWindow ?: return
        val textList = mutableListOf<String>()
        extractText(rootNode, textList, 0, 30) // Limit to 30 nodes for speed
        
        val combinedText = textList.joinToString(" ").lowercase()
        
        // Check for Settings/DNS tampering attempts
        val isDnsSettings = pkgName.contains("settings", ignoreCase = true) && 
                           (combinedText.contains("dns") || combinedText.contains("private dns"))
        
        val isActionBlocked = actionRegex.containsMatchIn(combinedText)
        val isFamilyTargeted = familyRegex.containsMatchIn(combinedText)

        // Block DNS/Settings tampering
        if ((isFamilyTargeted && isActionBlocked) || isDnsSettings) {
            Log.w(TAG, "BLOCKED: Settings tampering - $pkgName")
            performGlobalAction(GLOBAL_ACTION_HOME)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "⛔ BLOCKED: Settings tampering attempt", Toast.LENGTH_LONG).show()
            }
            return
        }
        
        // AI-powered adult content detection (only for browsers/social media)
        val isRiskyApp = browserApps.any { pkgName.contains(it) } || 
                        socialMediaApps.any { pkgName.contains(it) }
        
        Log.d(TAG, "Is risky app: $isRiskyApp, pkgName: $pkgName")
        
        val aiCooldown = if (aggressive) AGGRESSIVE_AI_COOLDOWN else NORMAL_AI_COOLDOWN
        if (isRiskyApp &&
            currentTime - scanState.lastAiScanTime > aiCooldown &&
            scanInProgress.compareAndSet(false, true)
        ) {
            scanState.lastAiScanTime = currentTime
            
            Log.d(TAG, "Starting AI detection for: $pkgName")
            
            // Run AI detection asynchronously with screenshot
            serviceScope.launch {
                try {
                    val screenshot = captureScreenshot()
                    val currentPackage = rootInActiveWindow?.packageName?.toString()
                    if (currentPackage != pkgName) {
                        Log.d(TAG, "Skipping stale AI result: foreground changed to $currentPackage")
                        return@launch
                    }

                    val currentRoot = rootInActiveWindow ?: return@launch
                    Log.d(TAG, "Running AI analysis with screenshot: ${screenshot != null}")
                    val result = smartDetector.analyzeContent(pkgName, currentRoot, screenshot)
                    
                    Log.d(TAG, "AI result: blocked=${result.isBlocked}, confidence=${result.confidence}, reason=${result.reason}")
                    
                    if (result.isBlocked) {
                        scanState.lastBlockedTime = System.currentTimeMillis()
                        withContext(Dispatchers.Main) {
                            Log.w(TAG, "BLOCKING content: $pkgName - ${result.reason}")
                            blockCurrentApp(result.reason)
                            Toast.makeText(
                                context,
                                "⛔ BLOCKED: ${result.reason}\nConfidence: ${(result.confidence * 100).toInt()}%",
                                Toast.LENGTH_LONG
                            ).show()
                            
                            Log.w(TAG, "Content blocked: $pkgName - ${result.reason}")
                        }
                    } else {
                        Log.d(TAG, "Content allowed: $pkgName - ${result.reason}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "AI detection failed for $pkgName", e)
                } finally {
                    scanInProgress.set(false)
                }
            }
        } else {
            Log.d(TAG, "Skipping AI scan - not risky app or cooldown active")
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
            node.getChild(i)?.let { extractText(it, textList, depth + 1, maxNodes) }
        }
    }
    
    override fun onInterrupt() {}
    
    override fun onDestroy() {
        // A restarted service begins in normal scan mode; the aggressive window is session-only.
        serviceScope.cancel()
        appScanStates.clear()
        scanInProgress.set(false)
        smartDetector.cleanup()
        super.onDestroy()
    }
}
