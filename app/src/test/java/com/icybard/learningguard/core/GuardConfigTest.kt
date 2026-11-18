package com.icybard.learningguard.core

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardConfigTest {
    @Test fun `legacy configuration keeps defaults`() {
        val config = GuardConfig.fromJson(JSONObject().put("enabled", true))
        assertTrue(config.guardCloudEnabled)
        assertTrue(config.guardWebTvEnabled)
        assertTrue(config.answerFeedbackSec == 6)
    }

    @Test fun `answer feedback duration survives JSON round trip and partial updates`() {
        val config = GuardConfig().copy(answerFeedbackSec = 10)
        val restored = GuardConfig.fromJson(config.toJson())
        assertTrue(restored.answerFeedbackSec == 10)
        assertTrue(restored.updated(JSONObject().put("requiredStreak", 4)).answerFeedbackSec == 10)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `answer feedback duration rejects values outside supported range`() {
        GuardConfig().updated(JSONObject().put("answerFeedbackSec", 2))
    }

    @Test fun `individual app switches survive JSON round trip`() {
        val config = GuardConfig().copy(guardCloudEnabled = false, guardWebTvEnabled = true)
        val restored = GuardConfig.fromJson(config.toJson())
        assertFalse(restored.guardCloudEnabled)
        assertTrue(restored.guardWebTvEnabled)
    }

    @Test fun `partial update preserves individual app choices`() {
        val config = GuardConfig().copy(guardCloudEnabled = false, guardWebTvEnabled = false)
        val updated = config.updated(JSONObject().put("requiredStreak", 4))
        assertFalse(updated.guardCloudEnabled)
        assertFalse(updated.guardWebTvEnabled)
        assertTrue(updated.requiredStreak == 4)
    }
}
