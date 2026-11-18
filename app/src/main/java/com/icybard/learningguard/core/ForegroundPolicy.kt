package com.icybard.learningguard.core

data class ForegroundRecord(
    val packageName: String,
    val timestamp: Long,
)

object ForegroundPolicy {
    private val transientSystemUiPackages = setOf(
        "com.android.systemui",
        "com.mitv.systemui",
        "com.xiaomi.mitv.systemui",
    )

    fun isTransientSystemUi(packageName: String): Boolean = packageName in transientSystemUiPackages

    fun latestRelevantForeground(records: List<ForegroundRecord>): String? = records
        .asSequence()
        .filterNot { isTransientSystemUi(it.packageName) }
        .maxByOrNull { it.timestamp }
        ?.packageName
}
