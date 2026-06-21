package com.younes.app.timer

import android.content.SharedPreferences

enum class AlertLevel {
    NONE,
    WARNING_15,
    WARNING_5,
    WARNING_1,
    FINISHED
}

class ExamTimerManager {

    private var startTimestamp: Long = 0L
    private var durationMillis: Long = 0L
    private var pausedRemainingMillis: Long = 0L
    var isRunning: Boolean = false
        private set
    var isPaused: Boolean = false
        private set

    fun start(durationMinutes: Int) {
        durationMillis = durationMinutes * 60_000L
        startTimestamp = SystemClock.elapsedRealtime()
        pausedRemainingMillis = 0L
        isRunning = true
        isPaused = false
    }

    fun pause() {
        if (isRunning && !isPaused) {
            pausedRemainingMillis = getRemainingMillis()
            isPaused = true
        }
    }

    fun resume() {
        if (isRunning && isPaused) {
            startTimestamp = SystemClock.elapsedRealtime()
            durationMillis = pausedRemainingMillis
            pausedRemainingMillis = 0L
            isPaused = false
        }
    }

    fun reset() {
        startTimestamp = 0L
        durationMillis = 0L
        pausedRemainingMillis = 0L
        isRunning = false
        isPaused = false
    }

    fun addMinutes(minutes: Int) {
        val additionalMillis = minutes * 60_000L
        if (isPaused) {
            pausedRemainingMillis += additionalMillis
        } else if (isRunning) {
            durationMillis += additionalMillis
        }
    }

    fun stop() {
        reset()
    }

    fun getRemainingMillis(): Long {
        if (!isRunning) return 0L

        return if (isPaused) {
            pausedRemainingMillis
        } else {
            val elapsed = SystemClock.elapsedRealtime() - startTimestamp
            (durationMillis - elapsed).coerceAtLeast(0L)
        }
    }

    fun isFinished(): Boolean {
        return getRemainingMillis() <= 0L
    }

    fun getProgress(): Float {
        if (durationMillis <= 0L) return 0f
        val remaining = getRemainingMillis()
        return ((durationMillis - remaining).toFloat() / durationMillis.toFloat()).coerceIn(0f, 1f)
    }

    fun getFormattedRemaining(): String {
        val remainingSeconds = getRemainingMillis() / 1_000L
        val hours = remainingSeconds / 3600
        val minutes = (remainingSeconds % 3600) / 60
        val seconds = remainingSeconds % 60

        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    fun getAlertLevel(): AlertLevel {
        val remainingMinutes = getRemainingMillis() / 60_000L

        return when {
            remainingMinutes <= 0L -> AlertLevel.FINISHED
            remainingMinutes <= 1L -> AlertLevel.WARNING_1
            remainingMinutes <= 5L -> AlertLevel.WARNING_5
            remainingMinutes <= 15L -> AlertLevel.WARNING_15
            else -> AlertLevel.NONE
        }
    }

    fun saveState(prefs: SharedPreferences, examId: String) {
        prefs.edit().apply {
            putLong(getKey(examId, "startTimestamp"), startTimestamp)
            putLong(getKey(examId, "durationMillis"), durationMillis)
            putLong(getKey(examId, "pausedRemainingMillis"), pausedRemainingMillis)
            putBoolean(getKey(examId, "isRunning"), isRunning)
            putBoolean(getKey(examId, "isPaused"), isPaused)
            apply()
        }
    }

    fun restoreState(prefs: SharedPreferences, examId: String): Boolean {
        val wasRunning = prefs.getBoolean(getKey(examId, "isRunning"), false)
        if (!wasRunning) return false

        startTimestamp = prefs.getLong(getKey(examId, "startTimestamp"), 0L)
        durationMillis = prefs.getLong(getKey(examId, "durationMillis"), 0L)
        pausedRemainingMillis = prefs.getLong(getKey(examId, "pausedRemainingMillis"), 0L)
        isRunning = wasRunning
        isPaused = prefs.getBoolean(getKey(examId, "isPaused"), false)

        return true
    }

    fun clearState(prefs: SharedPreferences, examId: String) {
        prefs.edit().apply {
            remove(getKey(examId, "startTimestamp"))
            remove(getKey(examId, "durationMillis"))
            remove(getKey(examId, "pausedRemainingMillis"))
            remove(getKey(examId, "isRunning"))
            remove(getKey(examId, "isPaused"))
            apply()
        }
    }

    private fun getKey(examId: String, key: String): String {
        return "timer_${examId}_$key"
    }
}

object SystemClock {
    @JvmStatic
    fun elapsedRealtime(): Long {
        return android.os.SystemClock.elapsedRealtime()
    }
}
