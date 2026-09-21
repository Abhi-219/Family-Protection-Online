package com.example.family

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.gms.tflite.java.TfLite
import org.tensorflow.lite.InterpreterApi
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * On-device adult content detector (v4).
 *
 * Order of checks:
 *  1. Benign phrases ("food porn", "naked eye") are blanked out first
 *  2. Explicit adult site names / HARD_RED (one hit) -> block, ignores the whitelist
 *  3. Text score / whitelist
 *  4. SOFT_RED needs corroboration: a second hit, a strong pattern, or an NSFW image
 *  5. Image score (only if text not decisive)
 *  6. Decision: text-only, image-only, or text+image combined
 *
 * Search autocomplete suggestions are scored separately (discounted, no repeat bonus)
 * and are NOT red-list checked, so typing "theory of sex" doesn't block just because a
 * suggestion row shows something else.
 */
class SmartContentDetector(private val context: Context) {

    private val imageLabeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.75f)
            .build()
    )
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var nsfwInterpreter: InterpreterApi? = null

    // Reused across passes; callers must serialise analyzeContent (the service does).
    private val nsfwPixels by lazy { IntArray(NSFW_INPUT_SIZE * NSFW_INPUT_SIZE) }
    private val nsfwInput: ByteBuffer by lazy {
        ByteBuffer.allocateDirect(NSFW_INPUT_SIZE * NSFW_INPUT_SIZE * 3 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
    }

    private class TextPattern(val phrase: String, val weight: Float, val weak: Boolean = false) {
        val regex = buildRegex(phrase)
    }

    private data class TextResult(
        val score: Float,
        val counts: Map<TextPattern, Int>,
        val strong: Map<TextPattern, Int>,
        val weak: Map<TextPattern, Int>
    )

    private class ScreenText(val query: String, val suggestions: String, val page: String)

    private data class TextAssessment(
        val mainText: String,
        val mainResult: TextResult,
        val effectiveTextScore: Float,
        val softHits: List<String>,
        val immediateResult: DetectionResult? = null
    )

    companion object {
        private const val TAG = "SmartContentDetector"

        // Set true once to see which accessibility view IDs Chrome uses, then adjust the ID lists below.
        private const val DEBUG_NODE_IDS = false

        // Per-pass diagnostic logging is expensive to build; keep it off in release builds.
        private const val VERBOSE_LOGS = false

        private const val NSFW_INPUT_SIZE = 224
        private const val IMAGE_MAX_DIM = 640       // labeling/NSFW input; OCR keeps native resolution

        // ---- Tunable thresholds (loosened) ----
        private const val TEXT_ONLY_BLOCK = 1.0f      // needs several terms stacking
        private const val IMAGE_ONLY_BLOCK = 0.92f
        private const val COMBINED_TEXT_MIN = 0.6f
        private const val COMBINED_IMAGE_MIN = 0.6f
        private const val IMAGE_SKIP_TEXT = 1.0f      // skip image analysis if text already decisive
        private const val LABEL_MIN_CONFIDENCE = 0.9f
        private const val OCR_TOP_SKIP = 0.10f        // ignore top 10% of screenshot
        private const val REPEAT_BONUS = 0.0f         // 0 = repeated words don't inflate the score
        private const val REPEAT_BONUS_MAX_STEPS = 4
        private const val SUGGESTION_FACTOR = 0.3f    // autocomplete rows count only 30%
        private const val SOFT_RED_TEXT_SCORE = 0.6f  // one ambiguous term, enough to pair with an image

        // Accessibility view-ID fragments across Chromium, Firefox and Samsung Internet
        private val URL_BAR_IDS = listOf("url_bar", "url_view", "urlbar", "location_bar_edit_text")
        private val SUGGESTION_IDS = listOf("suggestion", "omnibox", "awesomebar", "autocomplete", "line_1", "line_2")

        // Whole word/phrase; spaces in phrases also match hyphens/multiple spaces; optional plural "s"
        private fun buildRegex(phrase: String, plural: Boolean = true): Regex {
            val body = Regex.escape(phrase).replace(" ", "\\E[\\s\\-]+\\Q")
            val s = if (plural) "s?" else ""
            return Regex("(?<![\\p{L}\\p{N}])$body$s(?![\\p{L}\\p{N}])")
        }

        // Safe domains (matched as domains)
        private val WHITELIST_DOMAINS = setOf(
            "wikipedia.org", ".edu", ".ac.uk", ".ac.in", "coursera.org", "udemy.com",
            "khanacademy.org", "stackoverflow.com", "github.com", "scholar.google.com",
            "mayoclinic.org", "webmd.com", "healthline.com", "nih.gov", "who.int",
            "linkedin.com", "indeed.com", "zoom.us"
        )

        // Explicit adult sites
        private val EXPLICIT_KEYWORDS = setOf(
            "pornhub", "xvideos", "xnxx", "redtube", "youporn", "xhamster",
            "brazzers", "porn.com", "xxx.com", "chaturbate", "onlyfans.com",
            "pornhd", "tube8", "spankwire", "keezmovies", "pornmd", "eporner",
            "motherless", "tnaflix", "slutload", "cam4", "bongacams"
        )

        // Unambiguous: one hit blocks, even on a whitelisted domain.
        private val HARD_RED: List<Pair<String, Regex>> = listOf(
            "handjob", "hanndjob", "hand job", "blowjob", "blow job",
            "gangbang", "cumshot", "pornstar", "porn star",
            "porn video", "hentai", "creampie", "deepthroat", "footjob", "rimjob", "live sex",
            "blue film", "jerk off",
            "dick pic", "big dick", "suck dick", "dick sucking", "dick sucker",
            "black dick", "hard dick"
        ).map { it to buildRegex(it) }

        // Common in ordinary use, so these need a second signal before they block.
        private val SOFT_RED: List<Pair<String, Regex>> = listOf(
            "porn", "anal", "erotic", "nude", "xxx",
            "horny", "whore", "slut", "threesome", "milf"
        ).map { it to buildRegex(it) }

        // Blanked out before any matching so their substrings can never register as a hit.
        private val EXCEPTIONS: List<Regex> = listOf(
            "food porn", "car porn", "poverty porn",
            "naked eye", "naked truth",
            "horny toad", "horny lizard",
            "moby dick", "dick van dyke", "dick cheney",
            "cum laude", "slut shaming",
            "anal fissure", "anal fistula", "anal cancer", "anal gland",
            "nude lipstick", "nude heels", "nude shade"
        ).map { buildRegex(it) }

        // Words that count as safe/educational context (used to discount soft terms)
        private val SAFE_CONTEXT = setOf(
            "university", "education", "medical", "health", "research",
            "academic", "science", "wikipedia"
        )
        private val EDU_CONTEXT = setOf(
            "theory", "education", "biology", "health", "research", "definition", "meaning",
            "what is", "how does", "study", "science", "psychology", "anatomy", "history",
            "hormone", "reproduction", "puberty", "textbook", "wikipedia"
        )
        // Never discounted by educational context
        private val HARD_PHRASES = setOf(
            "porn video", "porn", "xxx", "live sex", "adult video", "sex video", "chudai", "hentai","desi sex", "desi porn", "desi sex video", "desi porn video"
        )

        // ML Kit labels: exact match only
        private val NSFW_LABELS = setOf("nudity", "pornography", "erotic", "lingerie", "underwear")

        // Scoring patterns (terms on HARD_RED/SOFT_RED are handled there and omitted here)
        private val TEXT_PATTERNS = listOf(
            // strong
            TextPattern("porn video", 0.95f), TextPattern("porn", 0.9f),
            TextPattern("p0rn", 0.9f), TextPattern("pr0n", 0.9f),
            TextPattern("adult video", 0.9f), TextPattern("chudai", 0.9f),
            TextPattern("sex video", 0.85f), TextPattern("desi sex", 0.85f),
            TextPattern("desi porn", 0.95f), TextPattern("desi sex video", 0.9f),
            TextPattern("desi porn video", 0.95f),
            TextPattern("anal sex", 0.85f), TextPattern("sex tape", 0.8f),
            TextPattern("sex chat", 0.8f), TextPattern("orgy", 0.8f),
            TextPattern("nudes", 0.8f), TextPattern("rule 34", 0.8f),
            TextPattern("cam girl", 0.8f), TextPattern("camgirl", 0.8f),
            TextPattern("adult content", 0.8f), TextPattern("confirm you are 18", 0.8f),
            TextPattern("must be 18", 0.8f), TextPattern("over 18 only", 0.8f),
            TextPattern("naked", 0.7f), TextPattern("explicit", 0.7f),
            TextPattern("sexual content", 0.7f), TextPattern("verify your age", 0.7f),
            TextPattern("masturbate", 0.7f), TextPattern("dildo", 0.7f), TextPattern("bdsm", 0.7f),
            TextPattern("webcam", 0.6f), TextPattern("age verification", 0.6f),
            TextPattern("bf video", 0.6f),
            // ambiguous (loosened)
            TextPattern("pussy", 0.5f), TextPattern("cum", 0.4f), TextPattern("cumming", 0.4f),
            TextPattern("squirt", 0.4f), TextPattern("squirting", 0.4f),
            TextPattern("tits", 0.4f), TextPattern("titties", 0.4f),
            TextPattern("masturbation", 0.4f), TextPattern("oral sex", 0.4f),
            TextPattern("sex", 0.3f), TextPattern("s3x", 0.3f), TextPattern("boobs", 0.3f),
            TextPattern("cock", 0.3f), TextPattern("orgasm", 0.3f),
            TextPattern("fetish", 0.3f), TextPattern("vibrator", 0.3f),
            // weak: counted only if a strong pattern also matched
            TextPattern("cam", 0.5f, weak = true), TextPattern("adult", 0.4f, weak = true),
            TextPattern("hookup", 0.4f, weak = true), TextPattern("escort", 0.4f, weak = true),
            TextPattern("dick", 0.4f, weak = true), TextPattern("ass", 0.3f, weak = true),
            TextPattern("sexy", 0.3f, weak = true), TextPattern("dating", 0.3f, weak = true),
            TextPattern("meet singles", 0.3f, weak = true), TextPattern("mature", 0.3f, weak = true),
            TextPattern("hot", 0.2f, weak = true), TextPattern("18+", 0.1f, weak = true)
        )

        private fun wordRegex(word: String) =
            Regex("(?<![\\p{L}\\p{N}])${Regex.escape(word)}(?![\\p{L}\\p{N}])")

        private fun domainRegex(domain: String) =
            Regex("${Regex.escape(domain)}(?![\\p{L}\\p{N}])")

        // Precompiled so a pass doesn't rebuild ~70 identical regexes every time it runs.
        private val EXPLICIT_PATTERNS = EXPLICIT_KEYWORDS.map { it to wordRegex(it) }
        private val SAFE_CONTEXT_PATTERNS = SAFE_CONTEXT.map { wordRegex(it) }
        private val EDU_PATTERNS = EDU_CONTEXT.map { wordRegex(it) }
        private val WHITELIST_DOMAIN_PATTERNS = WHITELIST_DOMAINS.map { domainRegex(it) }
    }

    suspend fun analyzeContent(
        packageName: String,
        rootNode: AccessibilityNodeInfo?,
        screenshotProvider: (suspend () -> Bitmap?)? = null
    ): DetectionResult = withContext(Dispatchers.Default) {

        try {
            // Step 1: Extract text, split by source
            val screen = extractScreenText(rootNode)
            val suggestionsVisible = screen.suggestions.isNotBlank()
            val suggestionText = applyExceptions(screen.suggestions)
            val queryText = screen.query
            var pageText = screen.page

            // Step 2: assess accessibility text before paying for a screenshot/OCR pass.
            var mainText = applyExceptions("$queryText $pageText".trim())
            hardBlock(mainText)?.let { return@withContext it }
            var assessment = assessText(packageName, mainText, suggestionText)
            assessment.immediateResult?.let { return@withContext it }

            // OCR also captures the autocomplete dropdown, so skip it while suggestions are showing.
            var screenshot: Bitmap? = null
            if (screenshotProvider != null && !suggestionsVisible) {
                screenshot = screenshotProvider()
                val ocr = screenshot?.let { extractImageText(it) }.orEmpty()
                if (ocr.isNotBlank()) {
                    pageText = "$pageText ${ocr.lowercase()}"
                    mainText = applyExceptions("$queryText $pageText".trim())
                    hardBlock(mainText)?.let { return@withContext it }
                    assessment = assessText(packageName, mainText, suggestionText)
                    assessment.immediateResult?.let { return@withContext it }
                }
            }

            // Step 3: Image analysis, only when text isn't already decisive.
            val imageScore = if (assessment.effectiveTextScore >= IMAGE_SKIP_TEXT) {
                0f
            } else {
                if (screenshot == null) screenshot = screenshotProvider?.invoke()
                screenshot?.let { analyzeImage(it) } ?: 0f
            }

            // Step 4: Decision
            val reason = when {
                assessment.effectiveTextScore >= TEXT_ONLY_BLOCK -> "Adult text detected"
                imageScore >= IMAGE_ONLY_BLOCK -> "Adult image detected"
                assessment.effectiveTextScore >= COMBINED_TEXT_MIN && imageScore >= COMBINED_IMAGE_MIN ->
                    if (assessment.softHits.isNotEmpty()) "Adult term + image: ${assessment.softHits.joinToString(", ")}"
                    else "Adult text and image detected"
                else -> null
            }
            val blocked = reason != null
            val finalScore = minOf(1f, maxOf(assessment.effectiveTextScore, imageScore))

            if (blocked) {
                Log.w(TAG, "BLOCK $packageName | text=${assessment.effectiveTextScore} image=$imageScore | " +
                        "soft=${assessment.softHits.joinToString(",")} | " +
                        "matched=${assessment.mainResult.counts.keys.joinToString { it.phrase }} | " +
                        "sample=${assessment.mainText.take(160)}")
            } else if (VERBOSE_LOGS) {
                Log.d(TAG, "Result: package=$packageName | text=${assessment.effectiveTextScore} | image=$imageScore | " +
                        "matched=${assessment.mainResult.counts.keys.joinToString { it.phrase }} | blocked=false")
            }

            DetectionResult(
                isBlocked = blocked,
                confidence = finalScore,
                reason = reason ?: "Safe",
                method = "combined"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Analysis failed", e)
            DetectionResult(false, 0f, "Error: ${e.message}", "error")
        }
    }

    private fun assessText(packageName: String, mainText: String, suggestionText: String): TextAssessment {
        val eduHits = EDU_PATTERNS.count { it.containsMatchIn(mainText) }
        val softFactor = when {
            eduHits >= 3 -> 0.3f
            eduHits >= 1 -> 0.6f
            else -> 1f
        }

        val mainResult = scoreText(mainText, REPEAT_BONUS, softFactor)
        val suggestionResult = scoreText(suggestionText, 0f, softFactor)
        val suggestionScore = suggestionResult.score * SUGGESTION_FACTOR
        val textScore = maxOf(mainResult.score, suggestionScore)

        if (VERBOSE_LOGS) {
            Log.d(TAG, "Text sample: ${mainText.take(200)}")
            Log.d(TAG, "Educational hits: $eduHits, soft factor: $softFactor")
            Log.d(TAG, "Pattern frequency: " + mainResult.counts.entries
                .sortedByDescending { it.value }
                .joinToString(", ") { "${it.key.phrase}=${it.value}" })
            Log.d(TAG, "Strong used: ${mainResult.strong.entries.joinToString { "${it.key.phrase}=${it.value}" }}")
            Log.d(TAG, "Weak used: ${mainResult.weak.entries.joinToString { "${it.key.phrase}=${it.value}" }}")
            Log.d(TAG, "Suggestion matches: ${suggestionResult.counts.keys.joinToString { it.phrase }}")
            Log.d(TAG, "Text score: main=${mainResult.score}, suggestions=$suggestionScore, final=$textScore")
        }

        if (mainResult.strong.isEmpty()) {
            val safeWord = SAFE_CONTEXT_PATTERNS.any { it.containsMatchIn(mainText) }
            val safeDomain = WHITELIST_DOMAIN_PATTERNS.any { it.containsMatchIn(mainText) }
            if (safeWord || safeDomain) {
                return TextAssessment(
                    mainText,
                    mainResult,
                    0f,
                    emptyList(),
                    DetectionResult(false, 0f, "Educational/safe content", "whitelist")
                )
            }
        }

        val softHits = SOFT_RED.mapNotNull { (phrase, regex) -> phrase.takeIf { regex.containsMatchIn(mainText) } }
        if (softHits.size >= 2 || (softHits.isNotEmpty() && mainResult.strong.isNotEmpty())) {
            Log.w(TAG, "SOFT RED BLOCK: ${softHits.joinToString(", ")} | " +
                    "strong=${mainResult.strong.keys.joinToString { it.phrase }}")
            return TextAssessment(
                mainText,
                mainResult,
                0.9f,
                softHits,
                DetectionResult(true, 0.9f, "Adult terms: ${softHits.joinToString(", ")}", "redlist-soft")
            )
        }

        val effectiveTextScore = if (softHits.isNotEmpty()) maxOf(textScore, SOFT_RED_TEXT_SCORE) else textScore
        if (effectiveTextScore >= TEXT_ONLY_BLOCK) {
            Log.w(TAG, "TEXT BLOCK $packageName | text=$effectiveTextScore | " +
                    "matched=${mainResult.counts.keys.joinToString { it.phrase }} | sample=${mainText.take(160)}")
            return TextAssessment(
                mainText,
                mainResult,
                effectiveTextScore,
                softHits,
                DetectionResult(true, minOf(1f, effectiveTextScore), "Adult text detected", "text")
            )
        }

        return TextAssessment(mainText, mainResult, effectiveTextScore, softHits)
    }

    /** Blanks benign phrases so their substrings can't register as a hit. */
    private fun applyExceptions(text: String): String {
        if (text.isBlank()) return text
        var out = text
        for (e in EXCEPTIONS) out = e.replace(out, " ")
        return out
    }

    /** Explicit-site and hard red-list checks; these block regardless of page context. */
    private fun hardBlock(text: String): DetectionResult? {
        if (text.isBlank()) return null

        for ((keyword, regex) in EXPLICIT_PATTERNS) {
            if (regex.containsMatchIn(text)) {
                Log.w(TAG, "EXPLICIT SITE DETECTED: $keyword")
                return DetectionResult(true, 0.98f, "Adult site: $keyword", "explicit")
            }
        }

        val hits = HARD_RED.mapNotNull { (phrase, regex) -> phrase.takeIf { regex.containsMatchIn(text) } }
        if (hits.isNotEmpty()) {
            Log.w(TAG, "HARD RED HIT: ${hits.joinToString(", ")}")
            return DetectionResult(true, 0.97f, "Red list: ${hits.joinToString(", ")}", "redlist")
        }
        return null
    }

    /** Scores text with pattern weights. Weak words count only if a strong one matched. */
    private fun scoreText(text: String, repeatBonus: Float, softFactor: Float): TextResult {
        if (text.isBlank()) return TextResult(0f, emptyMap(), emptyMap(), emptyMap())

        val counts = linkedMapOf<TextPattern, Int>()
        for (p in TEXT_PATTERNS) {
            val c = p.regex.findAll(text).count()
            if (c > 0) counts[p] = c
        }

        // Drop a pattern if a longer matched pattern contains it ("porn" inside "porn video")
        val kept = counts.filter { (p, _) ->
            counts.keys.none { o -> o !== p && o.phrase.length > p.phrase.length && o.phrase.contains(p.phrase) }
        }
        val strong = kept.filterKeys { !it.weak }
        val weak = if (strong.isNotEmpty()) kept.filterKeys { it.weak } else emptyMap()

        var score = 0f
        (strong + weak).forEach { (p, count) ->
            val steps = (count - 1).coerceAtMost(REPEAT_BONUS_MAX_STEPS)
            val s = p.weight * (1f + repeatBonus * steps)
            score += if (p.phrase in HARD_PHRASES) s else s * softFactor
        }
        return TextResult(score, counts, strong, weak)
    }

    private suspend fun analyzeImage(bitmap: Bitmap): Float = withContext(Dispatchers.Default) {
        val small = downscale(bitmap, IMAGE_MAX_DIM)
        try {
            val modelScore = analyzeWithNsfwModel(small)
            var labelScore = 0f

            val labels = imageLabeler.process(InputImage.fromBitmap(small, 0)).await()

            labels.forEach { label ->
                val text = label.text.lowercase()
                val confidence = label.confidence
                if (VERBOSE_LOGS && confidence > 0.5f) {
                    Log.d(TAG, "Label: $text (confidence: ${(confidence * 100).toInt()}%)")
                }
                if (text in NSFW_LABELS && confidence >= LABEL_MIN_CONFIDENCE) {
                    labelScore = maxOf(labelScore, confidence)
                    Log.w(TAG, "NSFW label matched: $text (${(confidence * 100).toInt()}%)")
                }
            }

            maxOf(labelScore, modelScore)
        } catch (e: Exception) {
            Log.e(TAG, "Image analysis failed", e)
            0f
        } finally {
            if (small !== bitmap) small.recycle()
        }
    }

    /** Shrinks to [maxDim] on the longest side; returns the original when it already fits. */
    private fun downscale(bitmap: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDim) return bitmap
        val scale = maxDim.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private suspend fun analyzeWithNsfwModel(bitmap: Bitmap): Float {
        val resized = Bitmap.createScaledBitmap(bitmap, NSFW_INPUT_SIZE, NSFW_INPUT_SIZE, true)
        try {
            resized.getPixels(nsfwPixels, 0, NSFW_INPUT_SIZE, 0, 0, NSFW_INPUT_SIZE, NSFW_INPUT_SIZE)
        } finally {
            if (resized !== bitmap) resized.recycle()
        }

        nsfwInput.rewind()
        for (pixel in nsfwPixels) {
            nsfwInput.putFloat((pixel and 0xFF) - 104f)
            nsfwInput.putFloat(((pixel shr 8) and 0xFF) - 117f)
            nsfwInput.putFloat(((pixel shr 16) and 0xFF) - 123f)
        }
        nsfwInput.rewind()

        val output = Array(1) { FloatArray(2) }
        getNsfwInterpreter().run(nsfwInput, output)
        val score = output[0][1].coerceIn(0f, 1f)
        if (VERBOSE_LOGS) Log.d(TAG, "TFLite NSFW score: $score")
        return score
    }

    private fun loadModelFile(fileName: String): ByteBuffer {
        val descriptor = context.assets.openFd(fileName)
        return FileInputStream(descriptor.fileDescriptor).use { inputStream ->
            inputStream.channel.map(
                FileChannel.MapMode.READ_ONLY,
                descriptor.startOffset,
                descriptor.declaredLength
            )
        }
    }

    private suspend fun getNsfwInterpreter(): InterpreterApi {
        return nsfwInterpreter ?: run {
            TfLite.initialize(context).await()
            InterpreterApi.create(
                loadModelFile("nsfw.tflite"),
                InterpreterApi.Options()
                    .setNumThreads(2)
                    .setRuntime(InterpreterApi.Options.TfLiteRuntime.FROM_SYSTEM_ONLY)
            ).also { nsfwInterpreter = it }
        }
    }

    /** OCR on the screenshot, skipping the top strip (address bar / search box). */
    private suspend fun extractImageText(bitmap: Bitmap): String = withContext(Dispatchers.Default) {
        var target: Bitmap? = null
        try {
            val top = (bitmap.height * OCR_TOP_SKIP).toInt()
            val height = bitmap.height - top
            target = if (height > 0) Bitmap.createBitmap(bitmap, 0, top, bitmap.width, height) else bitmap
            textRecognizer.process(InputImage.fromBitmap(target, 0)).await().text
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot text recognition failed", e)
            ""
        } finally {
            target?.let { if (it !== bitmap) it.recycle() }
        }
    }

    /** Collects accessibility text, split into URL/query, autocomplete suggestions, and page text. */
    private fun extractScreenText(root: AccessibilityNodeInfo?): ScreenText {
        val q = StringBuilder()
        val s = StringBuilder()
        val p = StringBuilder()
        if (root != null) collect(root, q, s, p)
        return ScreenText(q.toString().lowercase(), s.toString().lowercase(), p.toString().lowercase())
    }

    private fun collect(node: AccessibilityNodeInfo, q: StringBuilder, s: StringBuilder, p: StringBuilder) {
        val id = (node.viewIdResourceName ?: "").lowercase()
        val texts = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            .filter { it.isNotBlank() }

        for (t in texts) {
            if (DEBUG_NODE_IDS) Log.d(TAG, "NODE id=$id class=${node.className} text=${t.take(40)}")
            when {
                URL_BAR_IDS.any { id.contains(it) } || node.isEditable -> q.append(t).append(' ')
                SUGGESTION_IDS.any { id.contains(it) } -> s.append(t).append(' ')
                else -> p.append(t).append(' ')
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collect(it, q, s, p) }
        }
    }

    fun cleanup() {
        imageLabeler.close()
        textRecognizer.close()
        nsfwInterpreter?.close()
        nsfwInterpreter = null
    }
}

data class DetectionResult(
    val isBlocked: Boolean,
    val confidence: Float,
    val reason: String,
    val method: String
)
