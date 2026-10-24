package com.abhi.familyprotection

import android.content.Context
import android.content.SharedPreferences

/**
 * Stores and manages customizable lists of blocked adult apps and monitored social/browser apps.
 * Merges built-in defaults with user-added apps via SharedPreferences.
 */
object AppListPreferences {
    private const val PREF_NAME = "custom_app_lists"
    private const val KEY_CUSTOM_BLOCKED_ADULT = "custom_blocked_adult_apps"
    private const val KEY_CUSTOM_SOCIAL_MEDIA = "custom_social_media_apps"
    private const val KEY_CUSTOM_SUPERVISED_PATTERNS = "custom_supervised_package_patterns"

    // Built-in defaults
    val DEFAULT_BLOCKED_ADULT_APPS = setOf(
        "com.bumble.app",       // Bumble
        "com.tinder",           // Tinder
        "com.pof.android",      // Plenty of Fish (POF)
        "com.hily.app"          // Hily
    )

    val DEFAULT_SOCIAL_MEDIA_APPS = setOf(
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

    val DEFAULT_BROWSER_APPS = setOf(
        "com.android.chrome", "org.mozilla.firefox", "com.opera.browser",
        "com.microsoft.emmx", "com.brave.browser", "com.duckduckgo.mobile.android",
        "com.sec.android.app.sbrowser", "com.vivaldi.browser", "com.kiwibrowser.browser",
        "org.torproject.torbrowser", "com.ecosia.android", "com.yandex.browser",
        "com.UCMobile.intl", "mobi.mgeek.TunnyBrowser", "com.android.browser",
        "com.qwant.liberty", "com.ghostery.android.ghostery", "com.fingerprintbrowser"
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getCustomBlockedAdultApps(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_CUSTOM_BLOCKED_ADULT, emptySet()) ?: emptySet()
    }

    fun addBlockedAdultApp(context: Context, packageName: String) {
        val trimmed = packageName.trim()
        if (trimmed.isEmpty()) return
        val current = getCustomBlockedAdultApps(context).toMutableSet()
        current.add(trimmed)
        getPrefs(context).edit().putStringSet(KEY_CUSTOM_BLOCKED_ADULT, current).apply()
    }

    fun removeBlockedAdultApp(context: Context, packageName: String) {
        val current = getCustomBlockedAdultApps(context).toMutableSet()
        if (current.remove(packageName.trim())) {
            getPrefs(context).edit().putStringSet(KEY_CUSTOM_BLOCKED_ADULT, current).apply()
        }
    }

    fun getCustomSocialMediaApps(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_CUSTOM_SOCIAL_MEDIA, emptySet()) ?: emptySet()
    }

    fun addSocialMediaApp(context: Context, packageName: String) {
        val trimmed = packageName.trim()
        if (trimmed.isEmpty()) return
        val current = getCustomSocialMediaApps(context).toMutableSet()
        current.add(trimmed)
        getPrefs(context).edit().putStringSet(KEY_CUSTOM_SOCIAL_MEDIA, current).apply()
    }

    fun removeSocialMediaApp(context: Context, packageName: String) {
        val current = getCustomSocialMediaApps(context).toMutableSet()
        if (current.remove(packageName.trim())) {
            getPrefs(context).edit().putStringSet(KEY_CUSTOM_SOCIAL_MEDIA, current).apply()
        }
    }

    fun getCustomSupervisedPatterns(context: Context): Set<String> =
        getPrefs(context).getStringSet(KEY_CUSTOM_SUPERVISED_PATTERNS, emptySet()) ?: emptySet()

    fun addSupervisedPattern(context: Context, pattern: String) {
        val trimmed = pattern.trim()
        require(isValidPackagePattern(trimmed)) { "Invalid package-name pattern" }
        val current = getCustomSupervisedPatterns(context).toMutableSet()
        current.add(trimmed)
        getPrefs(context).edit().putStringSet(KEY_CUSTOM_SUPERVISED_PATTERNS, current).apply()
    }

    fun removeSupervisedPattern(context: Context, pattern: String) {
        val current = getCustomSupervisedPatterns(context).toMutableSet()
        if (current.remove(pattern.trim())) {
            getPrefs(context).edit().putStringSet(KEY_CUSTOM_SUPERVISED_PATTERNS, current).apply()
        }
    }

    fun getAllBlockedAdultApps(context: Context): Set<String> {
        return DEFAULT_BLOCKED_ADULT_APPS + getCustomBlockedAdultApps(context)
    }

    fun getAllSocialMediaApps(context: Context): Set<String> {
        return DEFAULT_SOCIAL_MEDIA_APPS + getCustomSocialMediaApps(context)
    }

    fun getAllSupervisedPatterns(context: Context): Set<String> =
        getCustomSupervisedPatterns(context)

    fun isValidPackageName(packageName: String): Boolean =
        isValidPackagePattern(packageName)

    fun isValidPackagePattern(pattern: String): Boolean {
        val segments = pattern.trim().split('.')
        return segments.size >= 2 && segments.all { segment ->
            segment == "*" || segment == "**" || segment.matches(Regex("[A-Za-z0-9_]+"))
        }
    }

    fun wildcardToPackageRegex(pattern: String): Regex {
        require(isValidPackagePattern(pattern)) { "Invalid package-name pattern" }
        val segments = pattern.trim().lowercase().split('.')
        val regexString = buildString {
            append("^")
            segments.forEachIndexed { index, segment ->
                if (index > 0) append("\\.")
                when (segment) {
                    "*" -> append("[^.]+")
                    "**" -> append("[^.]+(?:\\.[^.]+)*")
                    else -> append(Regex.escape(segment))
                }
            }
            append("$")
        }
        return Regex(regexString, RegexOption.IGNORE_CASE)
    }

    fun matchesPattern(pkgName: String, pattern: String): Boolean {
        val p = pattern.trim().lowercase()
        val target = pkgName.trim().lowercase()
        if (p.isEmpty() || target.isEmpty()) return false

        if (!p.contains("*")) return target == p
        if (!isValidPackagePattern(p)) return false
        return wildcardToPackageRegex(p).matches(target)
    }
}
