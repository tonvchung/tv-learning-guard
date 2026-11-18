package com.icybard.learningguard.data

import android.content.Context
import android.provider.Settings
import com.icybard.learningguard.core.GuardConfig
import com.icybard.learningguard.core.QuizPhase
import com.icybard.learningguard.core.QuizProgress
import org.json.JSONObject

class GuardStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("guard_state", Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    var config: GuardConfig
        get() = GuardConfig.fromJson(JSONObject(prefs.getString("config", "{}") ?: "{}"))
        set(value) = prefs.edit().putString("config", value.toJson().toString()).apply()

    var accumulatedPlayMs: Long
        get() = prefs.getLong("accumulatedPlayMs", 0L)
        set(value) = prefs.edit().putLong("accumulatedPlayMs", value.coerceAtLeast(0L)).apply()

    var progress: QuizProgress
        get() = QuizProgress(
            phase = runCatching {
                QuizPhase.valueOf(prefs.getString("phase", QuizPhase.IDLE.name) ?: QuizPhase.IDLE.name)
            }.getOrDefault(QuizPhase.QUIZ_PENDING),
            currentStreak = prefs.getInt("currentStreak", 0),
            consecutiveWrong = prefs.getInt("consecutiveWrong", 0),
            questionSerial = prefs.getLong("questionSerial", 0L),
        )
        set(value) = prefs.edit()
            .putString("phase", value.phase.name)
            .putInt("currentStreak", value.currentStreak)
            .putInt("consecutiveWrong", value.consecutiveWrong)
            .putLong("questionSerial", value.questionSerial)
            .apply()

    var currentQuestionId: String?
        get() = prefs.getString("currentQuestionId", null)
        set(value) = prefs.edit().putString("currentQuestionId", value).apply()

    var feedbackSelectedOptionId: String?
        get() = prefs.getString("feedbackSelectedOptionId", null)
        set(value) = prefs.edit().putString("feedbackSelectedOptionId", value).apply()

    var feedbackRemainingMs: Long
        get() = prefs.getLong("feedbackRemainingMs", 0L)
        set(value) = prefs.edit().putLong("feedbackRemainingMs", value.coerceAtLeast(0L)).apply()

    var feedbackEndsElapsed: Long
        get() = prefs.getLong("feedbackEndsElapsed", 0L)
        set(value) = prefs.edit().putLong("feedbackEndsElapsed", value).apply()

    var cooldownRemainingMs: Long
        get() = prefs.getLong("cooldownRemainingMs", 0L)
        set(value) = prefs.edit().putLong("cooldownRemainingMs", value.coerceAtLeast(0L)).apply()

    var cooldownEndsElapsed: Long
        get() = prefs.getLong("cooldownEndsElapsed", 0L)
        set(value) = prefs.edit().putLong("cooldownEndsElapsed", value).apply()

    var answerRemainingMs: Long
        get() = prefs.getLong("answerRemainingMs", 0L)
        set(value) = prefs.edit().putLong("answerRemainingMs", value.coerceAtLeast(0L)).apply()

    var answerEndsElapsed: Long
        get() = prefs.getLong("answerEndsElapsed", 0L)
        set(value) = prefs.edit().putLong("answerEndsElapsed", value).apply()

    var savedBootCount: Int
        get() = prefs.getInt("savedBootCount", -1)
        set(value) = prefs.edit().putInt("savedBootCount", value).apply()

    var resumePackage: String?
        get() = prefs.getString("resumePackage", null)
        set(value) = prefs.edit().putString("resumePackage", value).apply()

    var resumeRequested: Boolean
        get() = prefs.getBoolean("resumeRequested", false)
        set(value) = prefs.edit().putBoolean("resumeRequested", value).apply()

    var lastMediaDiagnostic: String
        get() = prefs.getString("lastMediaDiagnostic", "尚未检测") ?: "尚未检测"
        set(value) = prefs.edit().putString("lastMediaDiagnostic", value).apply()

    var pinConfigured: Boolean
        get() = prefs.getBoolean("pinConfigured", false)
        set(value) = prefs.edit().putBoolean("pinConfigured", value).apply()

    var questionHistory: QuestionHistory
        get() {
            val stored = prefs.getString("questionHistory", null)
            if (!stored.isNullOrBlank()) {
                runCatching { return QuestionHistory.fromJson(JSONObject(stored)) }
            }
            return QuestionHistory(recentIds = recentQuestionIds())
        }
        set(value) = prefs.edit().putString("questionHistory", value.toJson().toString()).apply()

    private fun recentQuestionIds(): List<String> =
        (prefs.getString("recentQuestionIds", "") ?: "").split(',').filter { it.isNotBlank() }

    fun currentBootCount(): Int = runCatching {
        Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT)
    }.getOrDefault(-1)

    fun saveClockCheckpoint() {
        prefs.edit().putInt("savedBootCount", currentBootCount()).apply()
    }

    fun completeRound() {
        progress = QuizProgress(questionSerial = progress.questionSerial)
        accumulatedPlayMs = 0L
        currentQuestionId = null
        resumePackage = null
        resumeRequested = false
        cooldownRemainingMs = 0L
        cooldownEndsElapsed = 0L
        answerRemainingMs = 0L
        answerEndsElapsed = 0L
        feedbackSelectedOptionId = null
        feedbackRemainingMs = 0L
        feedbackEndsElapsed = 0L
    }
}
