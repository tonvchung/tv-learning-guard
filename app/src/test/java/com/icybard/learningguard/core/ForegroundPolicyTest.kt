package com.icybard.learningguard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundPolicyTest {
    @Test fun `transient system UI does not replace current media app`() {
        val foreground = ForegroundPolicy.latestRelevantForeground(
            listOf(
                ForegroundRecord("com.ktcp.video", 100L),
                ForegroundRecord("com.xiaomi.mitv.systemui", 200L),
            ),
        )
        assertEquals("com.ktcp.video", foreground)
    }

    @Test fun `launcher remains a real foreground transition`() {
        val foreground = ForegroundPolicy.latestRelevantForeground(
            listOf(
                ForegroundRecord("com.ktcp.video", 100L),
                ForegroundRecord("app.orchard.tv.icybard", 200L),
            ),
        )
        assertEquals("app.orchard.tv.icybard", foreground)
    }

    @Test fun `only transient system UI produces no foreground app`() {
        val foreground = ForegroundPolicy.latestRelevantForeground(
            listOf(ForegroundRecord("com.android.systemui", 100L)),
        )
        assertNull(foreground)
    }
}
