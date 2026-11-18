package com.icybard.learningguard.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Random

data class QuestionOption(val id: String, val text: String)

data class Question(
    val id: String,
    val subject: String,
    val unit: String,
    val knowledgePoint: String,
    val difficulty: Int,
    val stem: String,
    val options: List<QuestionOption>,
    val answerId: String,
    val explanation: String,
) {
    fun displayOptions(serial: Long): List<QuestionOption> {
        val result = options.toMutableList()
        result.shuffle(Random(id.hashCode().toLong() xor serial))
        return result
    }
}

data class QuestionHistory(
    val recentIds: List<String> = emptyList(),
    val wrongIds: Set<String> = emptySet(),
    val seenBySubject: Map<String, Set<String>> = emptyMap(),
    val correctLockedBySubject: Map<String, Set<String>> = emptyMap(),
) {
    fun afterAnswer(question: Question, correct: Boolean): QuestionHistory {
        val subjectCorrect = correctLockedBySubject[question.subject].orEmpty()
        return copy(
            wrongIds = if (correct) wrongIds - question.id else wrongIds + question.id,
            correctLockedBySubject = correctLockedBySubject + (
                question.subject to if (correct) subjectCorrect + question.id else subjectCorrect - question.id
                ),
        )
    }

    fun toJson(): JSONObject = JSONObject()
        .put("recentIds", JSONArray(recentIds))
        .put("wrongIds", JSONArray(wrongIds.toList()))
        .put("seenBySubject", subjectMapToJson(seenBySubject))
        .put("correctLockedBySubject", subjectMapToJson(correctLockedBySubject))

    companion object {
        fun fromJson(root: JSONObject): QuestionHistory = QuestionHistory(
            recentIds = root.optJSONArray("recentIds").toStringList(),
            wrongIds = root.optJSONArray("wrongIds").toStringList().toSet(),
            seenBySubject = subjectMapFromJson(root.optJSONObject("seenBySubject")),
            correctLockedBySubject = subjectMapFromJson(root.optJSONObject("correctLockedBySubject")),
        )

        private fun JSONArray?.toStringList(): List<String> {
            if (this == null) return emptyList()
            return (0 until length()).map { getString(it) }
        }

        private fun subjectMapToJson(value: Map<String, Set<String>>): JSONObject {
            val root = JSONObject()
            value.forEach { (subject, ids) -> root.put(subject, JSONArray(ids.toList())) }
            return root
        }

        private fun subjectMapFromJson(root: JSONObject?): Map<String, Set<String>> {
            if (root == null) return emptyMap()
            val result = mutableMapOf<String, Set<String>>()
            root.keys().forEach { subject ->
                result[subject] = root.optJSONArray(subject).toStringList().toSet()
            }
            return result
        }
    }
}

data class QuestionSelection(val question: Question, val history: QuestionHistory)

class QuestionBank internal constructor(private val questions: List<Question>) {
    init {
        require(questions.isNotEmpty() && questions.map { it.id }.toSet().size == questions.size)
    }

    fun byId(id: String?): Question? = questions.firstOrNull { it.id == id }

    /**
     * Correct answers stay locked until every question in that subject has appeared.
     * Wrong questions are the deliberate exception: they can repeat early and receive 65% pool weight.
     */
    fun next(history: QuestionHistory, random: Random = Random()): QuestionSelection {
        var normalized = history
        questions.groupBy(Question::subject).forEach { (subject, subjectQuestions) ->
            val allIds = subjectQuestions.map { it.id }.toSet()
            if (allIds.isNotEmpty() && normalized.seenBySubject[subject].orEmpty().containsAll(allIds)) {
                normalized = normalized.copy(
                    seenBySubject = normalized.seenBySubject + (subject to emptySet()),
                    correctLockedBySubject = normalized.correctLockedBySubject + (subject to emptySet()),
                )
            }
        }

        val recent = normalized.recentIds.takeLast(RECENT_AVOIDANCE_COUNT).toSet()
        val wrongPool = questions.filter { it.id in normalized.wrongIds }
        val regularPool = questions.filter {
            it.id !in normalized.wrongIds && it.id !in normalized.correctLockedBySubject[it.subject].orEmpty()
        }
        var pool = when {
            wrongPool.isEmpty() -> regularPool
            regularPool.isEmpty() -> wrongPool
            random.nextDouble() < WRONG_POOL_WEIGHT -> wrongPool
            else -> regularPool
        }
        val otherPool = if (pool === wrongPool) regularPool else wrongPool
        val withoutRecent = pool.filterNot { it.id in recent }
        val otherWithoutRecent = otherPool.filterNot { it.id in recent }
        pool = when {
            withoutRecent.isNotEmpty() -> withoutRecent
            otherWithoutRecent.isNotEmpty() -> otherWithoutRecent
            pool.isNotEmpty() -> pool
            else -> questions
        }
        val question = pool[random.nextInt(pool.size)]
        val subjectSeen = normalized.seenBySubject[question.subject].orEmpty()
        normalized = normalized.copy(
            recentIds = (normalized.recentIds + question.id).takeLast(RECENT_AVOIDANCE_COUNT),
            seenBySubject = normalized.seenBySubject + (question.subject to subjectSeen + question.id),
        )
        return QuestionSelection(question, normalized)
    }

    companion object {
        private const val WRONG_POOL_WEIGHT = 0.65
        private const val RECENT_AVOIDANCE_COUNT = 20

        fun load(context: Context): QuestionBank {
            val text = context.assets.open("questions.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(text)
            val array = root.getJSONArray("questions")
            val result = mutableListOf<Question>()
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val optionArray = item.getJSONArray("options")
                val options = (0 until optionArray.length()).map { optionIndex ->
                    val option = optionArray.getJSONObject(optionIndex)
                    QuestionOption(option.getString("id"), option.getString("text"))
                }
                require(options.size == 4 && options.map { it.id }.toSet().size == 4)
                require(options.any { it.id == item.getString("answerId") })
                result += Question(
                    id = item.getString("id"),
                    subject = item.getString("subject"),
                    unit = item.getString("unit"),
                    knowledgePoint = item.getString("knowledgePoint"),
                    difficulty = item.getInt("difficulty"),
                    stem = item.getString("stem"),
                    options = options,
                    answerId = item.getString("answerId"),
                    explanation = item.optString("explanation"),
                )
            }
            return QuestionBank(result)
        }
    }
}
