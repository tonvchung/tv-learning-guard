package com.icybard.learningguard.data

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionBankTest {
    @Test fun `wrong pool receives weighted priority`() {
        val bank = bank(question("wrong"), question("regular"))
        val history = QuestionHistory(wrongIds = setOf("wrong"))
        assertEquals("wrong", bank.next(history, FixedRandom(0.1)).question.id)
        assertEquals("regular", bank.next(history, FixedRandom(0.9)).question.id)
    }

    @Test fun `wrong question can repeat before the subject is exhausted`() {
        val bank = bank(question("wrong"), question("regular-1"), question("regular-2"))
        val history = QuestionHistory(
            wrongIds = setOf("wrong"),
            seenBySubject = mapOf("math" to setOf("wrong")),
        )
        val selection = bank.next(history, FixedRandom(0.1))
        assertEquals("wrong", selection.question.id)
    }

    @Test fun `correct answer removes wrong mark and locks the question`() {
        val current = question("m1")
        val history = QuestionHistory(wrongIds = setOf("m1")).afterAnswer(current, true)
        assertFalse("m1" in history.wrongIds)
        assertTrue("m1" in history.correctLockedBySubject["math"].orEmpty())
    }

    @Test fun `wrong answer joins wrong set and removes correct lock`() {
        val current = question("m1")
        val history = QuestionHistory(
            correctLockedBySubject = mapOf("math" to setOf("m1")),
        ).afterAnswer(current, false)
        assertTrue("m1" in history.wrongIds)
        assertFalse("m1" in history.correctLockedBySubject["math"].orEmpty())
    }

    @Test fun `correct lock is released only after full subject coverage`() {
        val bank = bank(question("m1"), question("m2"))
        val firstCycle = QuestionHistory(
            correctLockedBySubject = mapOf("math" to setOf("m1")),
            seenBySubject = mapOf("math" to setOf("m1")),
        )
        val second = bank.next(firstCycle, FixedRandom(0.9))
        assertEquals("m2", second.question.id)
        assertEquals(setOf("m1", "m2"), second.history.seenBySubject["math"])

        val nextCycle = bank.next(second.history, FixedRandom(0.9))
        assertEquals("m1", nextCycle.question.id)
        assertEquals(setOf("m1"), nextCycle.history.seenBySubject["math"])
    }

    @Test fun `subject cycles and correct locks are independent`() {
        val bank = bank(question("m1"), question("m2"), question("c1", "chinese"))
        val history = QuestionHistory(
            correctLockedBySubject = mapOf("math" to setOf("m1")),
            seenBySubject = mapOf("math" to setOf("m1")),
        )
        val selection = bank.next(history, FixedRandom(0.9, 1))
        assertEquals("c1", selection.question.id)
        assertEquals(setOf("m1"), selection.history.correctLockedBySubject["math"])
        assertEquals(setOf("m1"), selection.history.seenBySubject["math"])
    }

    @Test fun `recent questions are avoided when another candidate exists`() {
        val bank = bank(question("m1"), question("m2"))
        val selection = bank.next(QuestionHistory(recentIds = listOf("m1")), FixedRandom(0.9))
        assertEquals("m2", selection.question.id)
        assertEquals(listOf("m1", "m2"), selection.history.recentIds)
    }

    @Test fun `history survives JSON persistence`() {
        val history = QuestionHistory(
            recentIds = listOf("m1", "c1"),
            wrongIds = setOf("m1"),
            seenBySubject = mapOf("math" to setOf("m1"), "chinese" to setOf("c1")),
            correctLockedBySubject = mapOf("chinese" to setOf("c1")),
        )
        assertEquals(history, QuestionHistory.fromJson(history.toJson()))
    }

    private fun bank(vararg questions: Question) = QuestionBank(questions.toList())

    private fun question(id: String, subject: String = "math") = Question(
        id = id,
        subject = subject,
        unit = "测试",
        knowledgePoint = "测试",
        difficulty = 1,
        stem = id,
        options = (1..4).map { QuestionOption("o$it", "选项 $it") },
        answerId = "o1",
        explanation = "",
    )

    private class FixedRandom(
        private val doubleValue: Double,
        private val intValue: Int = 0,
    ) : Random() {
        override fun nextDouble(): Double = doubleValue
        override fun nextInt(bound: Int): Int = intValue.coerceIn(0, bound - 1)
    }
}
