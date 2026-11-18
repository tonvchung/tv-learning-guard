package com.icybard.learningguard.core

/** Merges enabled-service settings without dropping components owned by other apps. */
object ServiceComponentList {
    fun contains(current: String?, component: String): Boolean =
        components(current).any { normalize(it) == normalize(component) }

    fun merge(current: String?, component: String): String {
        if (contains(current, component)) return current.orEmpty()
        return (components(current) + component).joinToString(":")
    }

    fun remove(current: String?, component: String): String =
        components(current).filter { normalize(it) != normalize(component) }.joinToString(":")

    private fun components(current: String?): List<String> =
        current.orEmpty().split(':').filter { it.isNotBlank() && it != "null" }

    private fun normalize(component: String): String {
        val packageName = component.substringBefore('/')
        val className = component.substringAfter('/', "")
        return if (className.startsWith('.')) "$packageName/$packageName$className" else component
    }
}
