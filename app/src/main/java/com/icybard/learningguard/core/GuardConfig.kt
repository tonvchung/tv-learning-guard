package com.icybard.learningguard.core

import org.json.JSONObject

data class GuardConfig(
    val enabled: Boolean = true,
    val triggerPlayMs: Long = 5 * 60 * 1000L,
    val requiredStreak: Int = 3,
    val wrongStreakThreshold: Int = 2,
    val wrongCooldownSec: Int = 120,
    val answerTimeoutSec: Int = 60,
    val answerFeedbackSec: Int = 6,
    val autoResume: Boolean = true,
    val allowForegroundEstimate: Boolean = true,
    val guardCloudEnabled: Boolean = true,
    val guardWebTvEnabled: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("triggerPlayMs", triggerPlayMs)
        put("requiredStreak", requiredStreak)
        put("wrongStreakThreshold", wrongStreakThreshold)
        put("wrongCooldownSec", wrongCooldownSec)
        put("answerTimeoutSec", answerTimeoutSec)
        put("answerFeedbackSec", answerFeedbackSec)
        put("autoResume", autoResume)
        put("allowForegroundEstimate", allowForegroundEstimate)
        put("guardCloudEnabled", guardCloudEnabled)
        put("guardWebTvEnabled", guardWebTvEnabled)
    }

    fun updated(json: JSONObject): GuardConfig {
        val candidate = copy(
            enabled = if (json.has("enabled")) json.optBoolean("enabled") else enabled,
            triggerPlayMs = if (json.has("triggerPlayMs")) json.optLong("triggerPlayMs") else triggerPlayMs,
            requiredStreak = if (json.has("requiredStreak")) json.optInt("requiredStreak") else requiredStreak,
            wrongCooldownSec = if (json.has("wrongCooldownSec")) json.optInt("wrongCooldownSec") else wrongCooldownSec,
            answerTimeoutSec = if (json.has("answerTimeoutSec")) json.optInt("answerTimeoutSec") else answerTimeoutSec,
            answerFeedbackSec = if (json.has("answerFeedbackSec")) json.optInt("answerFeedbackSec") else answerFeedbackSec,
            autoResume = if (json.has("autoResume")) json.optBoolean("autoResume") else autoResume,
            allowForegroundEstimate = if (json.has("allowForegroundEstimate")) {
                json.optBoolean("allowForegroundEstimate")
            } else {
                allowForegroundEstimate
            },
            guardCloudEnabled = if (json.has("guardCloudEnabled")) {
                json.optBoolean("guardCloudEnabled")
            } else {
                guardCloudEnabled
            },
            guardWebTvEnabled = if (json.has("guardWebTvEnabled")) {
                json.optBoolean("guardWebTvEnabled")
            } else {
                guardWebTvEnabled
            },
        )
        require(candidate.triggerPlayMs in 60_000L..7_200_000L) { "triggerPlayMs must be 60000..7200000" }
        require(candidate.requiredStreak in 1..10) { "requiredStreak must be 1..10" }
        require(candidate.wrongCooldownSec in 30..600) { "wrongCooldownSec must be 30..600" }
        require(candidate.answerTimeoutSec in 0..180) { "answerTimeoutSec must be 0 or 30..180" }
        require(candidate.answerFeedbackSec in 3..30) { "answerFeedbackSec must be 3..30" }
        require(candidate.answerTimeoutSec == 0 || candidate.answerTimeoutSec >= 30) {
            "answerTimeoutSec must be 0 or 30..180"
        }
        return candidate
    }

    companion object {
        fun fromJson(json: JSONObject): GuardConfig = GuardConfig().updated(json)
    }
}
