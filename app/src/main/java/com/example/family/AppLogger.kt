package com.abhi.familyprotection

import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.ParseException
import java.util.ArrayDeque
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Logcat-style, thread-safe logger for recording app security events, detections, and blocks.
 * Includes a power-saving toggle to completely bypass logging and conserve computation.
 */
object AppLogger {
    private const val PREF_NAME = "app_logger_prefs"
    private const val KEY_LOGGING_ENABLED = "terminal_logging_enabled"
    private const val MAX_MEMORY_LOGS = 300
    private const val MAX_PENDING_WRITES = 512
    private const val MAX_BATCH_SIZE = 50
    private const val MAX_LOG_ENTRY_CHARS = 1024
    // Trim to well below the cap so the file isn't rewritten on every batch.
    private const val MAX_FILE_BYTES = 256 * 1024L
    private const val TRIM_TARGET_BYTES = 128 * 1024L
    private const val MAX_LOG_AGE_MS = 7 * 24 * 60 * 60 * 1000L
    private const val AGE_PURGE_INTERVAL_MS = 60 * 60 * 1000L
    private const val LOG_FILE_NAME = "security_terminal_logs.txt"

    private data class PendingLog(val file: File, val entry: String, val generation: Long)

    private val logQueue = ConcurrentLinkedDeque<String>()
    // ConcurrentLinkedDeque.size is O(n), so track the count separately.
    private val memoryCount = AtomicInteger(0)
    private var lastAgePurgeAt = 0L
    private val timeFormatter = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileLock = Any()
    private val persistenceGeneration = AtomicLong(0L)
    private val pendingLogs = Channel<PendingLog>(MAX_PENDING_WRITES)
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        persistenceScope.launch {
            for (first in pendingLogs) {
                val batch = ArrayList<PendingLog>(MAX_BATCH_SIZE)
                batch.add(first)
                // Coalesce bursts into fewer flash writes; up to 1s of entries can be lost on a crash.
                delay(1_000L)
                while (batch.size < MAX_BATCH_SIZE) {
                    val next = pendingLogs.tryReceive().getOrNull() ?: break
                    batch.add(next)
                }
                persistBatch(batch)
            }
        }
    }

    @Volatile
    private var isInitialized = false

    @Volatile
    private var cachedLoggingEnabled: Boolean? = null

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    private fun getPrefs(context: Context): SharedPreferences =
        cachedPrefs ?: synchronized(this) {
            cachedPrefs ?: context.applicationContext
                .createDeviceProtectedStorageContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .also { cachedPrefs = it }
        }

    fun isLoggingEnabled(context: Context): Boolean {
        cachedLoggingEnabled?.let { return it }
        val enabled = getPrefs(context).getBoolean(KEY_LOGGING_ENABLED, true)
        cachedLoggingEnabled = enabled
        return enabled
    }

    fun setLoggingEnabled(context: Context, enabled: Boolean) {
        cachedLoggingEnabled = enabled
        getPrefs(context).edit().putBoolean(KEY_LOGGING_ENABLED, enabled).apply()
    }

    fun init(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            try {
                val file = getLogFile(context)
                synchronized(fileLock) {
                    if (file.exists()) trimFile(file)
                    if (file.exists() && logQueue.isEmpty()) {
                        readLastLogLines(file).forEach(logQueue::add)
                        memoryCount.set(logQueue.size)
                    }
                }
            } catch (e: IOException) {
                Log.e("AppLogger", "Unable to load saved app logs", e)
            }
            isInitialized = true
        }
    }

    fun log(
        context: Context? = null,
        message: String,
        tag: String = "AntiUninstallService",
        level: String = "I"
    ) {
        context?.let { ctx ->
            if (!isLoggingEnabled(ctx)) return // Omit logging process to save battery & computation
        }

        val timestamp = synchronized(timeFormatter) { timeFormatter.format(Date()) }
        val rawEntry = "$timestamp ${Process.myPid()} ${Process.myTid()} $level/$tag: $message"
        val entry = if (rawEntry.length > MAX_LOG_ENTRY_CHARS) {
            rawEntry.take(MAX_LOG_ENTRY_CHARS - 14) + " [truncated]"
        } else {
            rawEntry
        }

        logQueue.add(entry)
        if (memoryCount.incrementAndGet() > MAX_MEMORY_LOGS && logQueue.pollFirst() != null) {
            memoryCount.decrementAndGet()
        }

        context?.let { ctx ->
            val result = pendingLogs.trySend(
                PendingLog(getLogFile(ctx), entry, persistenceGeneration.get())
            )
            if (result.isFailure) {
                Log.w("AppLogger", "Dropping app log entry because the persistence queue is full")
            }
        }
    }

    fun getLogs(): List<String> {
        return logQueue.toList()
    }

    fun clearLogs(context: Context) {
        synchronized(fileLock) {
            persistenceGeneration.incrementAndGet()
            logQueue.clear()
            memoryCount.set(0)
            val file = getLogFile(context)
            if (file.exists() && !file.delete()) {
                throw IOException("Unable to delete the saved app log file")
            }
        }
    }

    private fun persistBatch(batch: List<PendingLog>) {
        synchronized(fileLock) {
            batch.asSequence()
                .filter { it.generation == persistenceGeneration.get() }
                .groupBy { it.file }
                .forEach { (file, entries) ->
                    try {
                        file.appendText(entries.joinToString(separator = "\n", postfix = "\n") { it.entry })
                        val now = System.currentTimeMillis()
                        if (file.length() > MAX_FILE_BYTES || now - lastAgePurgeAt > AGE_PURGE_INTERVAL_MS) {
                            lastAgePurgeAt = now
                            trimFile(file)
                        }
                    } catch (e: IOException) {
                        Log.e("AppLogger", "Unable to persist app log entries", e)
                    } catch (e: SecurityException) {
                        Log.e("AppLogger", "Unable to access saved app log file", e)
                    }
                }
        }
    }

    // Caller must hold fileLock. Keeps the newest entries within TRIM_TARGET_BYTES and MAX_LOG_AGE_MS.
    private fun trimFile(file: File) {
        val lines = file.readLines()
        val cutoff = System.currentTimeMillis() - MAX_LOG_AGE_MS
        val kept = ArrayList<String>()
        var bytes = 0L
        for (i in lines.indices.reversed()) {
            val line = lines[i].take(MAX_LOG_ENTRY_CHARS)
            val time = entryTimeMillis(line)
            if (time != null && time < cutoff) break
            bytes += line.length + 1
            if (bytes > TRIM_TARGET_BYTES) break
            kept.add(line)
        }
        if (kept.size == lines.size) return
        kept.reverse()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(if (kept.isEmpty()) "" else kept.joinToString(separator = "\n", postfix = "\n"))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    // Entries carry "MM-dd HH:mm:ss.SSS" without a year; assume the most recent matching date.
    private fun entryTimeMillis(line: String): Long? {
        if (line.length < 18) return null
        val parsed = try {
            synchronized(timeFormatter) { timeFormatter.parse(line.substring(0, 18)) }
        } catch (_: ParseException) {
            null
        } ?: return null
        val now = Calendar.getInstance()
        val cal = Calendar.getInstance().apply {
            time = parsed
            set(Calendar.YEAR, now.get(Calendar.YEAR))
        }
        if (cal.after(now)) cal.add(Calendar.YEAR, -1)
        return cal.timeInMillis
    }

    private fun readLastLogLines(file: File): List<String> {
        val lines = ArrayDeque<String>(MAX_MEMORY_LOGS)
        file.bufferedReader().useLines { allLines ->
            allLines.forEach { line ->
                if (lines.size == MAX_MEMORY_LOGS) lines.removeFirst()
                lines.addLast(line.take(MAX_LOG_ENTRY_CHARS))
            }
        }
        return lines.toList()
    }

    @Volatile private var cachedLogFile: File? = null

    private fun getLogFile(context: Context): File =
        cachedLogFile ?: synchronized(this) {
            cachedLogFile ?: File(
                context.applicationContext.createDeviceProtectedStorageContext().filesDir,
                LOG_FILE_NAME
            ).also { cachedLogFile = it }
        }
}
