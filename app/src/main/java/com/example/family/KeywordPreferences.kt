package com.abhi.familyprotection

import android.content.Context
import android.content.SharedPreferences

/**
 * Stores and manages customizable hard blacklisted keywords and false-positive exceptions.
 * Persists user-defined keywords in device-protected storage.
 */
object KeywordPreferences {
    private const val PREF_NAME = "custom_keyword_rules"
    private const val KEY_CUSTOM_HARD_KEYWORDS = "custom_hard_keywords"
    private const val KEY_CUSTOM_EXCEPTIONS = "custom_exceptions"

    // Shared hard-red list used by both the Accessibility service and content detector.
    val DEFAULT_HARD_RED = listOf(
        "porn", "porno", "xxx", "nude", "naked", "erotic", "adult video", "xvideo", "xhamster", "chudai",
        "handjob", "hanndjob", "hand job", "blowjob", "blow job",
        "gangbang", "cumshot", "pornstar", "porn star",
        "porn video", "hentai", "creampie", "deepthroat", "footjob", "rimjob", "live sex",
        "blue film", "jerk off",
        "dick pic", "big dick", "suck dick", "dick sucking", "dick sucker",
        "black dick", "hard dick"
    )

    // Built-in default benign exception phrases
    val DEFAULT_EXCEPTIONS = listOf(
        "food porn", "car porn", "poverty porn",
        "naked eye", "naked truth",
        "horny toad", "horny lizard",
        "moby dick", "dick van dyke", "dick cheney",
        "cum laude", "slut shaming",
        "anal fissure", "anal fistula", "anal cancer", "anal gland",
        "nude lipstick", "nude heels", "nude shade"
    )

    private class CompiledKeywords(
        val exceptions: List<Regex>,
        val keywords: List<Pair<String, Regex>>
    )

    @Volatile private var compiledCache: CompiledKeywords? = null

    private fun getCompiled(context: Context): CompiledKeywords {
        compiledCache?.let { return it }
        return synchronized(this) {
            compiledCache ?: CompiledKeywords(
                exceptions = getAllExceptions(context).map { buildRegex(it, plural = false) },
                keywords = getAllHardKeywords(context)
                    .sortedByDescending { it.length }
                    .map { it to buildRegex(it) }
            ).also { compiledCache = it }
        }
    }

    fun clearCache() {
        compiledCache = null
    }

    @Volatile private var cachedPrefs: SharedPreferences? = null

    private fun getPrefs(context: Context): SharedPreferences =
        cachedPrefs ?: synchronized(this) {
            cachedPrefs ?: context.applicationContext
                .createDeviceProtectedStorageContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .also { cachedPrefs = it }
        }

    fun getCustomHardKeywords(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_CUSTOM_HARD_KEYWORDS, emptySet()) ?: emptySet()
    }

    fun addHardKeyword(context: Context, keyword: String) {
        val trimmed = keyword.trim().lowercase()
        if (trimmed.isEmpty()) return
        val current = getCustomHardKeywords(context).toMutableSet()
        current.add(trimmed)
        getPrefs(context).edit().putStringSet(KEY_CUSTOM_HARD_KEYWORDS, current).apply()
        clearCache()
    }

    fun removeHardKeyword(context: Context, keyword: String) {
        val current = getCustomHardKeywords(context).toMutableSet()
        if (current.remove(keyword.trim().lowercase())) {
            getPrefs(context).edit().putStringSet(KEY_CUSTOM_HARD_KEYWORDS, current).apply()
            clearCache()
        }
    }

    fun getCustomExceptions(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_CUSTOM_EXCEPTIONS, emptySet()) ?: emptySet()
    }

    fun addException(context: Context, phrase: String) {
        val trimmed = phrase.trim().lowercase()
        if (trimmed.isEmpty()) return
        val current = getCustomExceptions(context).toMutableSet()
        current.add(trimmed)
        getPrefs(context).edit().putStringSet(KEY_CUSTOM_EXCEPTIONS, current).apply()
        clearCache()
    }

    fun removeException(context: Context, phrase: String) {
        val current = getCustomExceptions(context).toMutableSet()
        if (current.remove(phrase.trim().lowercase())) {
            getPrefs(context).edit().putStringSet(KEY_CUSTOM_EXCEPTIONS, current).apply()
            clearCache()
        }
    }

    fun getAllHardKeywords(context: Context): Set<String> {
        return (DEFAULT_HARD_RED + getCustomHardKeywords(context)).toSet()
    }

    fun findHardKeyword(context: Context, visibleText: String): String? {
        if (visibleText.isBlank()) return null
        val c = getCompiled(context)
        var text = visibleText
        for (ex in c.exceptions) {
            text = ex.replace(text, " ")
        }
        return c.keywords.firstOrNull { (_, re) -> re.containsMatchIn(text) }?.first
    }

    fun getAllExceptions(context: Context): Set<String> {
        return (DEFAULT_EXCEPTIONS + getCustomExceptions(context)).toSet()
    }

    fun buildRegex(phrase: String, plural: Boolean = true): Regex {
        val trimmed = phrase.trim().lowercase()
        if (trimmed.contains("*")) {
            val sb = StringBuilder("(?<![\\p{L}\\p{N}])")
            var i = 0
            while (i < trimmed.length) {
                when (val c = trimmed[i]) {
                    '*' -> {
                        if (i + 1 < trimmed.length && trimmed[i + 1] == '*') {
                            sb.append(".*?")
                            i++
                        } else {
                            sb.append("[^\\s]*?")
                        }
                    }
                    else -> sb.append(Regex.escape(c.toString()))
                }
                i++
            }
            sb.append("(?![\\p{L}\\p{N}])")
            return Regex(sb.toString(), RegexOption.IGNORE_CASE)
        } else {
            val escaped = Regex.escape(trimmed)
            val patternStr = if (plural && !trimmed.endsWith("s")) {
                "(?<![\\p{L}\\p{N}])$escaped(?:s|es)?(?![\\p{L}\\p{N}])"
            } else {
                "(?<![\\p{L}\\p{N}])$escaped(?![\\p{L}\\p{N}])"
            }
            return Regex(patternStr, RegexOption.IGNORE_CASE)
        }
    }
}
