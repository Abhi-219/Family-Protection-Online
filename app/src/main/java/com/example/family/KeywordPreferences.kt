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

    private fun getPrefs(context: Context): SharedPreferences {
        return context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
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
    }

    fun removeHardKeyword(context: Context, keyword: String) {
        val current = getCustomHardKeywords(context).toMutableSet()
        if (current.remove(keyword.trim().lowercase())) {
            getPrefs(context).edit().putStringSet(KEY_CUSTOM_HARD_KEYWORDS, current).apply()
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
    }

    fun removeException(context: Context, phrase: String) {
        val current = getCustomExceptions(context).toMutableSet()
        if (current.remove(phrase.trim().lowercase())) {
            getPrefs(context).edit().putStringSet(KEY_CUSTOM_EXCEPTIONS, current).apply()
        }
    }

    fun getAllHardKeywords(context: Context): Set<String> {
        return (DEFAULT_HARD_RED + getCustomHardKeywords(context)).toSet()
    }

    fun findHardKeyword(context: Context, visibleText: String): String? {
        var searchableText = visibleText
        getAllExceptions(context).forEach { exception ->
            searchableText = buildRegex(exception, plural = false)
                .replace(searchableText, " ")
        }

        return getAllHardKeywords(context)
            .sortedByDescending { it.length }
            .firstOrNull { keyword ->
                buildRegex(keyword).containsMatchIn(searchableText)
            }
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
                            sb.append(".*") // ** matches across multiple words & spaces
                            i++
                        } else {
                            sb.append("[\\p{L}\\p{N}]*") // * matches alphanumeric characters within a single word
                        }
                    }
                    ' ' -> sb.append("[\\s\\-]+")
                    else -> sb.append(Regex.escape(c.toString()))
                }
                i++
            }
            sb.append("(?![\\p{L}\\p{N}])")
            return Regex(sb.toString(), RegexOption.IGNORE_CASE)
        }
        val body = Regex.escape(phrase).replace(" ", "\\E[\\s\\-]+\\Q")
        val s = if (plural) "s?" else ""
        return Regex("(?<![\\p{L}\\p{N}])$body$s(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    }
}
