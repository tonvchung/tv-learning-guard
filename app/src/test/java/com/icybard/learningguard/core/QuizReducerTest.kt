package com.icybard.learningguard.core

import org.junit.Assert.assertEquals
import org.junit.Test

class QuizReducerTest {
    private val reducer = QuizReducer()
    private val active = QuizProgress(phase = QuizPhase.QUIZ_ACTIVE)

    @Test fun `three consecutive correct answers complete the round`() {
        var progress = active
        progress = reducer.answer(progress, true, 3).progress
        progress = reducer.answer(progress, true, 3).progress
        val result = reducer.answer(progress, true, 3)
        assertEquals(QuizAction.COMPLETE, result.action)
        assertEquals(QuizPhase.IDLE, result.progress.phase)
    }

    @Test fun `wrong answer resets streak and changes question`() {
        val progress = active.copy(currentStreak = 2)
        val result = reducer.answer(progress, false, 3)
        assertEquals(QuizAction.NEXT_QUESTION, result.action)
        assertEquals(0, result.progress.currentStreak)
        assertEquals(1, result.progress.consecutiveWrong)
    }

    @Test fun `non-consecutive correct answers do not complete`() {
        var progress = active
        progress = reducer.answer(progress, true, 3).progress
        progress = reducer.answer(progress, false, 3).progress
        progress = reducer.answer(progress, true, 3).progress
        val result = reducer.answer(progress, true, 3)
        assertEquals(QuizAction.NEXT_QUESTION, result.action)
        assertEquals(2, result.progress.currentStreak)
    }

    @Test fun `two consecutive wrong answers start cooldown`() {
        val first = reducer.answer(active, false, 3)
        val second = reducer.answer(first.progress, false, 3)
        assertEquals(QuizAction.START_COOLDOWN, second.action)
        assertEquals(QuizPhase.COOLDOWN, second.progress.phase)
        assertEquals(0, second.progress.currentStreak)
    }

    @Test fun `correct answer interrupts wrong streak`() {
        var progress = reducer.answer(active, false, 3).progress
        progress = reducer.answer(progress, true, 3).progress
        val result = reducer.answer(progress, false, 3)
        assertEquals(1, result.progress.consecutiveWrong)
        assertEquals(QuizAction.NEXT_QUESTION, result.action)
    }

    @Test fun `cooldown completion keeps streak zero and starts new question`() {
        val cooling = active.copy(phase = QuizPhase.COOLDOWN, currentStreak = 0, consecutiveWrong = 2)
        val result = reducer.finishCooldown(cooling)
        assertEquals(QuizPhase.QUIZ_ACTIVE, result.progress.phase)
        assertEquals(0, result.progress.consecutiveWrong)
        assertEquals(0, result.progress.currentStreak)
        assertEquals(QuizAction.NEXT_QUESTION, result.action)
    }

    @Test fun `answers are ignored during cooldown`() {
        val cooling = active.copy(phase = QuizPhase.COOLDOWN, consecutiveWrong = 2)
        val result = reducer.answer(cooling, true, 3)
        assertEquals(QuizAction.NONE, result.action)
        assertEquals(cooling, result.progress)
    }

    @Test fun `repeated cooldown never auto completes`() {
        var progress = reducer.finishCooldown(active.copy(phase = QuizPhase.COOLDOWN)).progress
        progress = reducer.answer(progress, false, 3).progress
        val result = reducer.answer(progress, false, 3)
        assertEquals(QuizAction.START_COOLDOWN, result.action)
        assertEquals(QuizPhase.COOLDOWN, result.progress.phase)
    }

    @Test fun `wrong answer enters non-interactive feedback before changing question`() {
        val feedback = reducer.beginWrongFeedback(active)
        assertEquals(QuizPhase.ANSWER_FEEDBACK, feedback.progress.phase)
        assertEquals(QuizAction.NONE, feedback.action)
        val ignored = reducer.answer(feedback.progress, true, 3)
        assertEquals(QuizAction.NONE, ignored.action)
        assertEquals(feedback.progress, ignored.progress)
    }

    @Test fun `finished first wrong feedback advances to next question`() {
        val feedback = reducer.beginWrongFeedback(active).progress
        val result = reducer.finishWrongFeedback(feedback, 3)
        assertEquals(QuizAction.NEXT_QUESTION, result.action)
        assertEquals(1, result.progress.consecutiveWrong)
    }

    @Test fun `second wrong feedback finishes into cooldown`() {
        val first = reducer.finishWrongFeedback(reducer.beginWrongFeedback(active).progress, 3).progress
        val secondFeedback = reducer.beginWrongFeedback(first).progress
        val result = reducer.finishWrongFeedback(secondFeedback, 3)
        assertEquals(QuizAction.START_COOLDOWN, result.action)
        assertEquals(QuizPhase.COOLDOWN, result.progress.phase)
        assertEquals(2, result.progress.consecutiveWrong)
    }

    @Test fun `debt survives pauses and only advances during playback`() {
        val accumulated = PlaybackDebt.advance(180_000L, 30_000L, true)
        assertEquals(210_000L, PlaybackDebt.advance(accumulated, 60_000L, false))
        assertEquals(270_000L, PlaybackDebt.advance(accumulated, 60_000L, true))
    }

    @Test fun `debt triggers at exact threshold`() {
        assertEquals(false, PlaybackDebt.shouldTrigger(299_999L, 300_000L))
        assertEquals(true, PlaybackDebt.shouldTrigger(300_000L, 300_000L))
    }
}
