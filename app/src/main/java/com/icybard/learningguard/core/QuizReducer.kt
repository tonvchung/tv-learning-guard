package com.icybard.learningguard.core

enum class QuizPhase {
    IDLE,
    QUIZ_PENDING,
    QUIZ_ACTIVE,
    ANSWER_FEEDBACK,
    COOLDOWN,
    PARENT_GRACE,
}

enum class QuizAction {
    NONE,
    NEXT_QUESTION,
    COMPLETE,
    START_COOLDOWN,
}

data class QuizProgress(
    val phase: QuizPhase = QuizPhase.IDLE,
    val currentStreak: Int = 0,
    val consecutiveWrong: Int = 0,
    val questionSerial: Long = 0,
)

data class QuizTransition(
    val progress: QuizProgress,
    val action: QuizAction,
)

/** Pure rule engine: no Android clock or storage, so every rule is unit-testable. */
class QuizReducer {
    fun begin(progress: QuizProgress): QuizTransition {
        if (progress.phase == QuizPhase.QUIZ_ACTIVE) return QuizTransition(progress, QuizAction.NONE)
        return QuizTransition(
            progress.copy(phase = QuizPhase.QUIZ_ACTIVE, questionSerial = progress.questionSerial + 1),
            QuizAction.NEXT_QUESTION,
        )
    }

    fun beginWrongFeedback(progress: QuizProgress): QuizTransition {
        if (progress.phase != QuizPhase.QUIZ_ACTIVE) return QuizTransition(progress, QuizAction.NONE)
        return QuizTransition(progress.copy(phase = QuizPhase.ANSWER_FEEDBACK), QuizAction.NONE)
    }

    fun finishWrongFeedback(
        progress: QuizProgress,
        requiredStreak: Int,
        wrongThreshold: Int = 2,
    ): QuizTransition {
        if (progress.phase != QuizPhase.ANSWER_FEEDBACK) return QuizTransition(progress, QuizAction.NONE)
        return answer(progress.copy(phase = QuizPhase.QUIZ_ACTIVE), false, requiredStreak, wrongThreshold)
    }

    fun answer(
        progress: QuizProgress,
        correct: Boolean,
        requiredStreak: Int,
        wrongThreshold: Int = 2,
    ): QuizTransition {
        require(requiredStreak > 0) { "requiredStreak must be positive" }
        require(wrongThreshold > 1) { "wrongThreshold must be greater than one" }
        if (progress.phase != QuizPhase.QUIZ_ACTIVE) {
            return QuizTransition(progress, QuizAction.NONE)
        }

        if (correct) {
            val nextStreak = progress.currentStreak + 1
            if (nextStreak >= requiredStreak) {
                return QuizTransition(
                    QuizProgress(questionSerial = progress.questionSerial),
                    QuizAction.COMPLETE,
                )
            }
            return QuizTransition(
                progress.copy(
                    currentStreak = nextStreak,
                    consecutiveWrong = 0,
                    questionSerial = progress.questionSerial + 1,
                ),
                QuizAction.NEXT_QUESTION,
            )
        }

        val nextWrong = progress.consecutiveWrong + 1
        if (nextWrong >= wrongThreshold) {
            return QuizTransition(
                progress.copy(
                    currentStreak = 0,
                    consecutiveWrong = nextWrong,
                    phase = QuizPhase.COOLDOWN,
                ),
                QuizAction.START_COOLDOWN,
            )
        }
        return QuizTransition(
            progress.copy(
                currentStreak = 0,
                consecutiveWrong = nextWrong,
                questionSerial = progress.questionSerial + 1,
            ),
            QuizAction.NEXT_QUESTION,
        )
    }

    fun finishCooldown(progress: QuizProgress): QuizTransition {
        if (progress.phase != QuizPhase.COOLDOWN) return QuizTransition(progress, QuizAction.NONE)
        return QuizTransition(
            progress.copy(
                phase = QuizPhase.QUIZ_ACTIVE,
                currentStreak = 0,
                consecutiveWrong = 0,
                questionSerial = progress.questionSerial + 1,
            ),
            QuizAction.NEXT_QUESTION,
        )
    }
}

object PlaybackDebt {
    fun advance(currentMs: Long, elapsedMs: Long, activelyPlaying: Boolean): Long {
        if (!activelyPlaying || elapsedMs <= 0L) return currentMs.coerceAtLeast(0L)
        return (currentMs.coerceAtLeast(0L) + elapsedMs).coerceAtMost(Long.MAX_VALUE)
    }

    fun shouldTrigger(accumulatedMs: Long, triggerMs: Long): Boolean =
        triggerMs > 0L && accumulatedMs >= triggerMs
}
