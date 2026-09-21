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
 * On-device adult content detector (v3).
 *
 * Order of checks:
 *  1. Explicit adult site names            -> block
 *  2. RED LIST (one hit)                   -> block
 *  3. Text score (loose, needs several terms) / whitelist
 *  4. Image score (only if text not decisive)
 *  5. Decision: text-only, image-only, or text+image combined
 *
 * Search autocomplete suggestions are scored separately (discounted, no repeat bonus)
 * and are NOT checked against the red list, so typing an educational phrase doesn't block
 * just because a suggestion row shows something else.
 */
class SmartContentDetector(private val context: Context) {

    private val imageLabeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.75f)
            .build()
    )
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var nsfwInterpreter: InterpreterApi? = null

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

    companion object {
        private const val TAG = "SmartContentDetector"

        // Set true once to see which accessibility view IDs Chrome uses, then adjust the ID lists below.
        private const val DEBUG_NODE_IDS = false

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

        // Accessibility view-ID fragments (Chrome may differ by version; verify with DEBUG_NODE_IDS)
        private val URL_BAR_IDS = listOf("url_bar")
        private val SUGGESTION_IDS = listOf("suggestion", "omnibox", "line_1", "line_2")

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
            "site1", "site2", "site3", "site4", "site5", "site6",
            "site7", "site8", "site9", "site10", "site11",
            "site12", "site13", "site14", "site15", "site16", "site17",
            "site18", "site19", "site20", "site21", "site22"
        )

        // RED LIST: one match = block immediately. Values are masked for source sharing.
        private val RED_LIST: List<Pair<String, Regex>> = listOf(
            "word1", "word2", "word3", "word4", "word5",
            "word6", "word7", "word8", "word9",
            "word10", "word11", "word12", "word13", "word14", "word15", "word16", "word17",
            "word18", "word19", "word20", "word21", "word22", "word23",
            "word24", "word25", "word26", "word27", "word28",
            "word29", "word30", "word31", "word32", "word33"
        ).map { it to buildRegex(it) }

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
            "word11", "word10", "word34", "word35", "word36", "word37", "word38", "word39", "word40", "word41", "word42", "word43"
        )

        // ML Kit labels: exact match only
        private val NSFW_LABELS = setOf("label1", "label2", "label3", "label4", "label5")

        // Scoring patterns (terms already on the RED_LIST are handled there and omitted here)
        private val TEXT_PATTERNS = listOf(
            // strong
            TextPattern("word11", 0.95f), TextPattern("word10", 0.9f),
            TextPattern("word44", 0.9f), TextPattern("word45", 0.9f),
            TextPattern("word36", 0.9f), TextPattern("word38", 0.9f),
            TextPattern("word37", 0.85f), TextPattern("word39", 0.85f),
            TextPattern("word40", 0.95f), TextPattern("word41", 0.9f),
            TextPattern("word42", 0.95f),
            TextPattern("word46", 0.85f), TextPattern("word47", 0.8f),
            TextPattern("word48", 0.8f), TextPattern("word49", 0.8f),
            TextPattern("word50", 0.8f), TextPattern("word51", 0.8f),
            TextPattern("word52", 0.8f), TextPattern("word53", 0.8f),
            TextPattern("word54", 0.8f), TextPattern("word55", 0.8f),
            TextPattern("word56", 0.7f), TextPattern("word57", 0.7f),
            TextPattern("word58", 0.7f), TextPattern("word59", 0.7f),
            TextPattern("word60", 0.7f), TextPattern("word61", 0.7f), TextPattern("word62", 0.7f),
            TextPattern("word63", 0.6f), TextPattern("word64", 0.6f),
            TextPattern("word65", 0.6f),
            // ambiguous (loosened)
            TextPattern("word66", 0.5f), TextPattern("word67", 0.4f), TextPattern("word68", 0.4f),
            TextPattern("word69", 0.4f), TextPattern("word70", 0.4f),
            TextPattern("word71", 0.4f), TextPattern("word72", 0.4f),
            TextPattern("word73", 0.4f), TextPattern("word74", 0.4f),
            TextPattern("word75", 0.3f), TextPattern("word76", 0.3f), TextPattern("word77", 0.3f),
            TextPattern("word78", 0.3f), TextPattern("word79", 0.3f),
            TextPattern("word80", 0.3f), TextPattern("word81", 0.3f),
            // weak: counted only if a strong pattern also matched
            TextPattern("word82", 0.5f, weak = true), TextPattern("word83", 0.4f, weak = true),
            TextPattern("word84", 0.4f, weak = true), TextPattern("word85", 0.4f, weak = true),
            TextPattern("word86", 0.4f, weak = true), TextPattern("word87", 0.3f, weak = true),
            TextPattern("word88", 0.3f, weak = true), TextPattern("word89", 0.3f, weak = true),
            TextPattern("word90", 0.3f, weak = true), TextPattern("word91", 0.3f, weak = true),
            TextPattern("word92", 0.2f, weak = true), TextPattern("word93", 0.1f, weak = true)
        )
    }

    suspend fun analyzeContent(
        packageName: String,
        rootNode: AccessibilityNodeInfo?,
        screenshot: Bitmap? = null
    ): DetectionResult = withContext(Dispatchers.Default) {

        Log.d(TAG, "DETECTOR VERSION: v3-redlist-loose")
        Log.d(TAG, "analyzeContent called for package: $packageName, screenshot: ${screenshot != null}")

        try {
            // Step 1: Extract text, split by source
            val screen = extractScreenText(rootNode)
            var pageText = screen.page
            val queryText = screen.query
            val suggestionText = screen.suggestions

            // OCR also captures the autocomplete dropdown, so skip it while suggestions are showing
            if (screenshot != null && suggestionText.isBlank()) {
                val ocr = extractImageText(screenshot)
                if (ocr.isNotBlank()) {
                    pageText = "$pageText ${ocr.lowercase()}"
                    Log.d(TAG, "Added OCR text: ${ocr.length} chars")
                } else {
                    Log.d(TAG, "OCR found no text in screenshot")
                }
            } else if (screenshot != null) {
                Log.d(TAG, "Suggestions visible; skipping OCR text")
            }

            val mainText = "$queryText $pageText".trim()
            Log.d(TAG, "Text length: main=${mainText.length}, suggestions=${suggestionText.length}")
            Log.d(TAG, "Query: ${queryText.take(100)}")
            Log.d(TAG, "Text sample: ${mainText.take(200)}")

            // Step 2: Explicit sites (query + page, not suggestions)
            for (keyword in EXPLICIT_KEYWORDS) {
                if (containsWord(mainText, keyword)) {
                    Log.w(TAG, "EXPLICIT SITE DETECTED: $keyword")
                    return@withContext DetectionResult(true, 0.98f, "Adult site: $keyword", "explicit")
                }
            }

            // Step 3: Red list, one hit blocks (before whitelist so nothing bypasses it)
            val redHits = RED_LIST.filter { (_, regex) -> regex.containsMatchIn(mainText) }.map { it.first }
            if (redHits.isNotEmpty()) {
                Log.w(TAG, "RED LIST HIT: ${redHits.joinToString(", ")}")
                return@withContext DetectionResult(true, 0.97f, "Red list: ${redHits.joinToString(", ")}", "redlist")
            }

            // Step 4: Text score
            val eduHits = EDU_CONTEXT.count { containsWord(mainText, it) }
            val softFactor = when {
                eduHits >= 3 -> 0.3f
                eduHits >= 1 -> 0.6f
                else -> 1f
            }
            Log.d(TAG, "Educational hits: $eduHits, soft factor: $softFactor")

            val mainResult = scoreText(mainText, REPEAT_BONUS, softFactor)
            val suggestionResult = scoreText(suggestionText, 0f, softFactor)
            val suggestionScore = suggestionResult.score * SUGGESTION_FACTOR
            val textScore = maxOf(mainResult.score, suggestionScore)

            if (mainResult.counts.isEmpty() && suggestionResult.counts.isEmpty()) {
                Log.d(TAG, "No text patterns matched")
            } else {
                Log.d(TAG, "Pattern frequency: " + mainResult.counts.entries
                    .sortedByDescending { it.value }
                    .joinToString(", ") { "${it.key.phrase}=${it.value}" })
                Log.d(TAG, "Strong used: ${mainResult.strong.entries.joinToString { "${it.key.phrase}=${it.value}" }}")
                Log.d(TAG, "Weak used: ${mainResult.weak.entries.joinToString { "${it.key.phrase}=${it.value}" }}")
                Log.d(TAG, "Suggestion matches: ${suggestionResult.counts.keys.joinToString { it.phrase }}")
            }
            Log.d(TAG, "Text score: main=${mainResult.score}, suggestions=$suggestionScore, final=$textScore")

            val topWords = mainText
                .split(Regex("[^\\p{L}\\p{N}+]+"))
                .filter { it.length > 2 }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(10)
            Log.d(TAG, "Top words: ${topWords.joinToString(", ") { "${it.key}=${it.value}" }}")

            // Step 5: Whitelist (only when no strong adult text)
            if (mainResult.strong.isEmpty()) {
                val safeWord = SAFE_CONTEXT.any { containsWord(mainText, it) }
                val safeDomain = WHITELIST_DOMAINS.any { containsDomain(mainText, it) }
                if (safeWord || safeDomain) {
                    Log.d(TAG, "Whitelisted (safeWord=$safeWord, safeDomain=$safeDomain)")
                    return@withContext DetectionResult(false, 0f, "Educational/safe content", "whitelist")
                }
            }

            // Step 6: Image analysis, only when text isn't already decisive
            val imageScore = when {
                screenshot == null -> {
                    Log.w(TAG, "No screenshot provided for analysis")
                    0f
                }
                textScore >= IMAGE_SKIP_TEXT -> {
                    Log.d(TAG, "Skipping image analysis; text already decisive: $textScore")
                    0f
                }
                else -> {
                    Log.d(TAG, "Analyzing image: ${screenshot.width}x${screenshot.height}")
                    analyzeImage(screenshot).also { Log.d(TAG, "Image score: $it") }
                }
            }

            // Step 7: Decision
            val reason = when {
                textScore >= TEXT_ONLY_BLOCK -> "Adult text detected"
                imageScore >= IMAGE_ONLY_BLOCK -> "Adult image detected"
                textScore >= COMBINED_TEXT_MIN && imageScore >= COMBINED_IMAGE_MIN -> "Adult text and image detected"
                else -> null
            }
            val blocked = reason != null
            val finalScore = minOf(1f, maxOf(textScore, imageScore))

            Log.d(TAG, "Result: package=$packageName | text=$textScore | image=$imageScore | " +
                    "matched=${mainResult.counts.keys.joinToString { it.phrase }} | blocked=$blocked")

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

    /** Scores text with pattern weights. Weak words count only if a strong one matched. */
    private fun scoreText(text: String, repeatBonus: Float, softFactor: Float): TextResult {
        if (text.isBlank()) return TextResult(0f, emptyMap(), emptyMap(), emptyMap())

        val counts = linkedMapOf<TextPattern, Int>()
        for (p in TEXT_PATTERNS) {
            val c = p.regex.findAll(text).count()
            if (c > 0) counts[p] = c
        }

        // Drop a pattern if a longer matched pattern contains it.
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
        try {
            val modelScore = analyzeWithNsfwModel(bitmap)
            val image = InputImage.fromBitmap(bitmap, 0)
            var labelScore = 0f

            val labels = imageLabeler.process(image).await()
            Log.d(TAG, "ML Kit detected ${labels.size} labels")

            labels.forEach { label ->
                val text = label.text.lowercase()
                val confidence = label.confidence
                if (confidence > 0.5f) {
                    Log.d(TAG, "Label: $text (confidence: ${(confidence * 100).toInt()}%)")
                }
                if (text in NSFW_LABELS && confidence >= LABEL_MIN_CONFIDENCE) {
                    labelScore = maxOf(labelScore, confidence)
                    Log.w(TAG, "NSFW label matched: $text (${(confidence * 100).toInt()}%)")
                }
            }
            if (labelScore == 0f) Log.d(TAG, "No NSFW labels detected in image")

            maxOf(labelScore, modelScore)
        } catch (e: Exception) {
            Log.e(TAG, "Image analysis failed", e)
            0f
        }
    }

    private suspend fun analyzeWithNsfwModel(bitmap: Bitmap): Float {
        val inputSize = 224
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val input = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputSize * inputSize)
        resized.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        pixels.forEach { pixel ->
            input.putFloat((pixel and 0xFF) - 104f)
            input.putFloat(((pixel shr 8) and 0xFF) - 117f)
            input.putFloat(((pixel shr 16) and 0xFF) - 123f)
        }
        input.rewind()

        val output = Array(1) { FloatArray(2) }
        getNsfwInterpreter().run(input, output)
        val score = output[0][1].coerceIn(0f, 1f)
        Log.d(TAG, "TFLite NSFW score: $score")
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
        try {
            val top = (bitmap.height * OCR_TOP_SKIP).toInt()
            val height = bitmap.height - top
            val target = if (height > 0) Bitmap.createBitmap(bitmap, 0, top, bitmap.width, height) else bitmap
            textRecognizer.process(InputImage.fromBitmap(target, 0)).await().text
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot text recognition failed", e)
            ""
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
                URL_BAR_IDS.any { id.contains(it) } -> q.append(t).append(' ')
                SUGGESTION_IDS.any { id.contains(it) } -> s.append(t).append(' ')
                else -> p.append(t).append(' ')
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collect(it, q, s, p) }
        }
    }

    /** Whole-word match: not preceded or followed by a letter/digit. */
    private fun containsWord(text: String, word: String): Boolean =
        Regex("(?<![\\p{L}\\p{N}])${Regex.escape(word)}(?![\\p{L}\\p{N}])").containsMatchIn(text)

    /** Domain match: may be preceded by anything, but not followed by a letter/digit. */
    private fun containsDomain(text: String, domain: String): Boolean =
        Regex("${Regex.escape(domain)}(?![\\p{L}\\p{N}])").containsMatchIn(text)

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