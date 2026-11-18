package com.icybard.learningguard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceComponentListTest {
    private val guard = "com.icybard.learningguard/com.icybard.learningguard.GuardAccessibilityService"
    private val aerial = "app.orchard.tv.icybard/app.orchard.tv.guard.HomeGuardService"

    @Test fun `merge preserves other enabled services`() {
        assertEquals("$aerial:$guard", ServiceComponentList.merge(aerial, guard))
    }

    @Test fun `merge does not duplicate shorthand or full component names`() {
        val shorthand = "com.icybard.learningguard/.GuardAccessibilityService"
        assertTrue(ServiceComponentList.contains(shorthand, guard))
        assertEquals(shorthand, ServiceComponentList.merge(shorthand, guard))
        assertEquals(guard, ServiceComponentList.merge(guard, guard))
    }

    @Test fun `empty and null settings are handled without a placeholder`() {
        assertFalse(ServiceComponentList.contains(null, guard))
        assertEquals(guard, ServiceComponentList.merge(null, guard))
        assertEquals(guard, ServiceComponentList.merge("null", guard))
    }

    @Test fun `remove deletes only the target component including shorthand`() {
        val shorthand = "com.icybard.learningguard/.GuardAccessibilityService"
        assertEquals(aerial, ServiceComponentList.remove("$aerial:$shorthand", guard))
        assertEquals(aerial, ServiceComponentList.remove(aerial, guard))
    }
}
