package com.abhi.familyprotection

import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
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
    private const val MAX_LOG_ENTRY_CHARS = 2048
    private const val LOG_FILE_NAME = "security_terminal_logs.txt"

    private data class PendingLog(val file: File, val entry: String, val generation: Long)

    private val logQueue = ConcurrentLinkedDeque<String>()
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
                delay(75L)
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

    private fun getPrefs(context: Context): SharedPreferences {
        return context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
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
                    if (file.exists() && logQueue.isEmpty()) {
                        readLastLogLines(file).forEach(logQueue::add)
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
        while (logQueue.size > MAX_MEMORY_LOGS) {
            logQueue.pollFirst()
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
                        file.writeText(readLastLogLines(file).joinToString(separator = "\n", postfix = "\n"))
                    } catch (e: IOException) {
                        Log.e("AppLogger", "Unable to persist app log entries", e)
                    } catch (e: SecurityException) {
                        Log.e("AppLogger", "Unable to access saved app log file", e)
                    }
                }
        }
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

    private fun getLogFile(context: Context): File {
        val dir = context.createDeviceProtectedStorageContext().filesDir
        return File(dir, LOG_FILE_NAME)
    }
}
