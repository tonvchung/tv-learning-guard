package com.icybard.learningguard

import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.view.accessibility.AccessibilityEvent
import com.icybard.learningguard.core.ForegroundPolicy
import com.icybard.learningguard.core.ForegroundRecord
import com.icybard.learningguard.core.PlaybackDebt
import com.icybard.learningguard.core.QuizAction
import com.icybard.learningguard.core.QuizPhase
import com.icybard.learningguard.core.QuizReducer
import com.icybard.learningguard.core.QuizTransition
import com.icybard.learningguard.data.GuardStore
import com.icybard.learningguard.data.Question
import com.icybard.learningguard.data.QuestionBank
import com.icybard.learningguard.parent.ParentServer
import com.icybard.learningguard.parent.QrCodes
import com.icybard.learningguard.ui.QuizOverlayView
import org.json.JSONArray
import org.json.JSONObject

class GuardAccessibilityService : AccessibilityService(), QuizOverlayView.Callbacks {
    private lateinit var store: GuardStore
    private lateinit var bank: QuestionBank
    private lateinit var mediaManager: MediaSessionManager
    private lateinit var usageManager: UsageStatsManager
    private lateinit var audioManager: AudioManager
    private lateinit var handler: Handler
    private lateinit var server: ParentServer
    private val reducer = QuizReducer()
    private val controllers = mutableListOf<MediaController>()
    private val directControllers = mutableMapOf<String, MediaController>()
    private val controlledPackages = buildSet {
        add("com.ktcp.video")
        add("com.tonvchung.webtv")
        if (BuildConfig.DEBUG) add("com.tonvchung.webtv.debug")
    }
    private val webTvConnectors by lazy {
        controlledPackages.filter { it.startsWith("com.tonvchung.webtv") }.map { packageName ->
            WebTvSessionConnector(
                this,
                packageName,
                object : WebTvSessionConnector.Callbacks {
                    override fun onConnected(packageName: String, controller: MediaController) {
                        directControllers[packageName] = controller
                    }

                    override fun onDisconnected(packageName: String) {
                        directControllers.remove(packageName)
                    }
                },
            )
        }
    }
    private var overlay: QuizOverlayView? = null
    private var foregroundPackage: String? = null
    private var lastTickAt = 0L
    private var lastPersistenceAt = 0L
    private var lastMediaRefreshAt = 0L
    private var lastForegroundRefreshAt = 0L
    private var lastForegroundRecoveryAt = 0L
    private var foregroundRecoveryCount = 0
    private var lastAdbCheckAt = 0L
    private var inputAvailableAt = 0L
    private var fallbackFocusHeld = false
    private var running = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            super.onPlaybackStateChanged(state)
        }
    }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener {
        refreshControllers(it)
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            val elapsed = if (lastTickAt == 0L) 0L else (now - lastTickAt).coerceAtLeast(0L)
            lastTickAt = now
            if (now - lastForegroundRefreshAt > 2_000L) {
                refreshForegroundFromUsage()
                lastForegroundRefreshAt = now
            }
            if (now - lastMediaRefreshAt > 5_000L) {
                refreshMediaSessions()
                webTvConnectors.forEach { it.refresh() }
                lastMediaRefreshAt = now
            }
            if (now - lastAdbCheckAt > 15_000L) {
                AdbWatchdog.ensure(this@GuardAccessibilityService)
                lastAdbCheckAt = now
            }
            when (store.progress.phase) {
                QuizPhase.COOLDOWN -> tickCooldown(now)
                QuizPhase.QUIZ_ACTIVE -> tickAnswer(now)
                QuizPhase.ANSWER_FEEDBACK -> tickAnswerFeedback(now)
                QuizPhase.QUIZ_PENDING -> beginPendingQuiz()
                else -> tickPlayback(elapsed)
            }
            if (now - lastPersistenceAt >= 5_000L) {
                persistClocks(now)
                lastPersistenceAt = now
            }
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        store = GuardStore(this)
        bank = QuestionBank.load(this)
        mediaManager = getSystemService(MediaSessionManager::class.java)
        usageManager = getSystemService(UsageStatsManager::class.java)
        audioManager = getSystemService(AudioManager::class.java)
        handler = Handler(Looper.getMainLooper())
        activeInstance = this
        GuardWatchdogService.start(this)
        server = ParentServer(
            this,
            store,
            onConfigChanged = ::applyConfig,
            onUnlock = ::unlockFromParent,
            diagnostics = ::diagnostics,
        )
        runCatching { server.start(5_000, false) }.onFailure {
            store.lastMediaDiagnostic = "局域网设置服务启动失败：${it.javaClass.simpleName}"
        }
        val listener = ComponentName(this, GuardNotificationListenerService::class.java)
        runCatching { NotificationListenerService.requestRebind(listener) }
        runCatching {
            mediaManager.addOnActiveSessionsChangedListener(
                sessionsChanged,
                listener,
                handler,
            )
        }.onFailure { store.lastMediaDiagnostic = "媒体会话权限尚未授予或系统尚未绑定" }
        running = true
        lastTickAt = SystemClock.elapsedRealtime()
        refreshForegroundFromUsage()
        restoreTask()
        handler.post(ticker)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event?.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return
        val packageName = event.packageName?.toString() ?: return
        if (packageName == this.packageName || ForegroundPolicy.isTransientSystemUi(packageName)) return
        foregroundPackage = packageName
        returnToQuizIfNeeded()
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        running = false
        if (::handler.isInitialized) handler.removeCallbacksAndMessages(null)
        persistClocks(SystemClock.elapsedRealtime())
        webTvConnectors.forEach { it.close() }
        directControllers.clear()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        running = false
        if (::handler.isInitialized) handler.removeCallbacksAndMessages(null)
        if (::store.isInitialized) persistClocks(SystemClock.elapsedRealtime())
        if (::mediaManager.isInitialized) {
            runCatching { mediaManager.removeOnActiveSessionsChangedListener(sessionsChanged) }
        }
        clearControllers()
        webTvConnectors.forEach { it.close() }
        directControllers.clear()
        releaseFallbackFocus()
        PlaybackPauseActivity.activeInstance?.finish()
        removeOverlay()
        activeInstance = null
        if (::server.isInitialized) server.stop()
        super.onDestroy()
    }

    /** Debug-only capture of the laid-out view, independent of MIUI's broken compositor screenshots. */
    fun captureQuizForValidation() {
        if (!BuildConfig.DEBUG) return
        val view = overlay?.takeIf { it.isAttachedToWindow && it.width > 0 && it.height > 0 } ?: return
        runCatching {
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val output = java.io.File(filesDir, "validation/quiz-overlay.png")
            output.parentFile?.mkdirs()
            java.io.FileOutputStream(output).use { stream ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
            }
        }
    }

    override fun onAnswer(optionId: String) {
        val now = SystemClock.elapsedRealtime()
        if (now < inputAvailableAt || store.progress.phase != QuizPhase.QUIZ_ACTIVE) return
        val question = bank.byId(store.currentQuestionId) ?: return
        inputAvailableAt = now + INPUT_DEBOUNCE_MS
        val correct = optionId == question.answerId
        store.questionHistory = store.questionHistory.afterAnswer(question, correct)
        if (correct) {
            applyTransition(reducer.answer(
                store.progress,
                true,
                store.config.requiredStreak,
                store.config.wrongStreakThreshold,
            ))
        } else {
            beginAnswerFeedback(question, optionId)
        }
    }

    private fun beginAnswerFeedback(question: Question, selectedOptionId: String?) {
        if (store.progress.phase != QuizPhase.QUIZ_ACTIVE) return
        applyTransition(reducer.beginWrongFeedback(store.progress))
        store.feedbackSelectedOptionId = selectedOptionId
        val feedbackMs = store.config.answerFeedbackSec * 1000L
        store.feedbackRemainingMs = feedbackMs
        store.feedbackEndsElapsed = SystemClock.elapsedRealtime() + feedbackMs
        store.answerRemainingMs = 0L
        store.answerEndsElapsed = 0L
        store.saveClockCheckpoint()
        ensureOverlay()
        renderFeedback(question)
    }

    private fun finishAnswerFeedback() {
        if (store.progress.phase != QuizPhase.ANSWER_FEEDBACK) return
        clearAnswerFeedback()
        applyTransition(reducer.finishWrongFeedback(
            store.progress,
            store.config.requiredStreak,
            store.config.wrongStreakThreshold,
        ))
    }

    private fun clearAnswerFeedback() {
        store.feedbackSelectedOptionId = null
        store.feedbackRemainingMs = 0L
        store.feedbackEndsElapsed = 0L
    }

    private fun renderFeedback(question: Question) {
        ensureOverlay()
        overlay?.showFeedback(
            question,
            question.displayOptions(store.progress.questionSerial),
            store.feedbackSelectedOptionId,
            store.progress.currentStreak,
            store.progress.consecutiveWrong,
            store.config.requiredStreak,
            remainingTime(SystemClock.elapsedRealtime(), store.feedbackEndsElapsed, store.feedbackRemainingMs),
        )
    }

    override fun onGear() {
        val currentOverlay = overlay ?: return
        val url = issueParentEntryUrl() ?: return
        currentOverlay.showQr(QrCodes.create(url), url)
    }

    fun issueParentEntryUrl(): String? {
        if (!::server.isInitialized) return null
        return server.entryUrl()
    }

    private fun returnToQuizIfNeeded() {
        if (!running || !::handler.isInitialized) return
        handler.postDelayed({
            if (running && isTaskActive()) ensureOverlay()
        }, HOME_RETURN_DELAY_MS)
    }

    fun onPlaybackPauseActivityHidden() {
        if (!::handler.isInitialized) return
        removeOverlay()
        if (isTaskActive()) handler.postDelayed(
            { if (running && isTaskActive()) ensureOverlay() },
            HOME_RETURN_DELAY_MS,
        )
    }

    private fun isTaskActive(): Boolean = ::store.isInitialized && store.config.enabled && when (store.progress.phase) {
        QuizPhase.QUIZ_PENDING, QuizPhase.QUIZ_ACTIVE, QuizPhase.ANSWER_FEEDBACK, QuizPhase.COOLDOWN -> true
        else -> false
    }

    fun shouldKeepPlaybackPaused(): Boolean = isTaskActive()

    private fun isPackageGuardEnabled(packageName: String): Boolean = when (packageName) {
        "com.ktcp.video" -> store.config.guardCloudEnabled
        "com.tonvchung.webtv", "com.tonvchung.webtv.debug" -> store.config.guardWebTvEnabled
        else -> false
    }

    private fun tickPlayback(elapsedMs: Long) {
        if (!store.config.enabled) return
        val controlledPackage = foregroundPackage?.takeIf {
            it in controlledPackages && isPackageGuardEnabled(it)
        } ?: return
        val playing = isActuallyPlaying(controlledPackage)
        if (!playing) return
        val nextDebt = PlaybackDebt.advance(store.accumulatedPlayMs, elapsedMs, true)
        store.accumulatedPlayMs = nextDebt
        if (PlaybackDebt.shouldTrigger(nextDebt, store.config.triggerPlayMs)) {
            startQuiz(controlledPackage)
        }
    }

    private fun startQuiz(controlledPackage: String) {
        store.resumePackage = controlledPackage
        store.resumeRequested = isActuallyPlaying(controlledPackage)
        store.progress = store.progress.copy(phase = QuizPhase.QUIZ_PENDING)
        ensurePlaybackPauseActivity()
        requestPause(controlledPackage)
        beginPendingQuiz()
    }

    private fun beginPendingQuiz() {
        if (store.progress.phase != QuizPhase.QUIZ_PENDING) return
        ensurePlaybackPauseActivity()
        store.resumePackage?.let(::requestPause)
        applyTransition(reducer.begin(store.progress))
    }

    private fun tickAnswer(now: Long) {
        val remaining = remainingTime(now, store.answerEndsElapsed, store.answerRemainingMs)
        // Debug builds keep the current question open for TV remote and visual validation.
        if (!BuildConfig.DEBUG && store.config.answerTimeoutSec > 0 && remaining <= 0L) {
            val question = bank.byId(store.currentQuestionId)
            if (question != null) {
                store.questionHistory = store.questionHistory.afterAnswer(question, false)
                beginAnswerFeedback(question, null)
            } else {
                applyTransition(reducer.answer(
                    store.progress,
                    false,
                    store.config.requiredStreak,
                    store.config.wrongStreakThreshold,
                ))
            }
            return
        }
        // Retry a transient window-manager failure instead of leaving an invisible active task.
        ensureOverlay()
        overlay?.tick(remaining, 0L, store.progress.currentStreak, store.progress.consecutiveWrong, store.config.requiredStreak)
    }

    private fun tickAnswerFeedback(now: Long) {
        val remaining = remainingTime(now, store.feedbackEndsElapsed, store.feedbackRemainingMs)
        if (remaining <= 0L) {
            finishAnswerFeedback()
            return
        }
        ensureOverlay()
        overlay?.tickFeedback(remaining)
    }

    private fun tickCooldown(now: Long) {
        val remaining = remainingTime(now, store.cooldownEndsElapsed, store.cooldownRemainingMs)
        if (remaining <= 0L) {
            store.cooldownRemainingMs = 0L
            store.cooldownEndsElapsed = 0L
            applyTransition(reducer.finishCooldown(store.progress))
            return
        }
        ensureOverlay()
        overlay?.tick(0L, remaining, store.progress.currentStreak, store.progress.consecutiveWrong, store.config.requiredStreak)
    }

    private fun applyTransition(transition: QuizTransition) {
        store.progress = transition.progress
        when (transition.action) {
            QuizAction.NEXT_QUESTION -> showNextQuestion()
            QuizAction.START_COOLDOWN -> startCooldown()
            QuizAction.COMPLETE -> completeRound()
            QuizAction.NONE -> Unit
        }
    }

    private fun showNextQuestion() {
        val selection = bank.next(store.questionHistory)
        val question = selection.question
        store.questionHistory = selection.history
        store.currentQuestionId = question.id
        clearAnswerFeedback()
        val timeoutMs = store.config.answerTimeoutSec * 1000L
        store.answerRemainingMs = timeoutMs
        store.answerEndsElapsed = SystemClock.elapsedRealtime() + timeoutMs
        store.saveClockCheckpoint()
        inputAvailableAt = SystemClock.elapsedRealtime() + INPUT_DEBOUNCE_MS
        ensureOverlay()
        renderQuestion(question)
    }

    private fun renderQuestion(question: Question) {
        ensureOverlay()
        overlay?.showQuestion(
            question,
            question.displayOptions(store.progress.questionSerial),
            store.progress.currentStreak,
            store.progress.consecutiveWrong,
            store.config.requiredStreak,
            store.answerRemainingMs,
        )
    }

    private fun startCooldown() {
        val duration = store.config.wrongCooldownSec * 1000L
        store.cooldownRemainingMs = duration
        store.cooldownEndsElapsed = SystemClock.elapsedRealtime() + duration
        store.answerRemainingMs = 0L
        store.answerEndsElapsed = 0L
        store.saveClockCheckpoint()
        ensureOverlay()
        overlay?.showCooldown(
            duration,
            store.progress.currentStreak,
            store.progress.consecutiveWrong,
            store.config.requiredStreak,
        )
    }

    private fun completeRound() {
        val packageToResume = store.resumePackage
        val shouldResume = store.resumeRequested && store.config.autoResume
        store.completeRound()
        PlaybackPauseActivity.activeInstance?.finish()
        removeOverlay()
        if (shouldResume && packageToResume != null) {
            resumeControlledApp(packageToResume)
        } else {
            releaseFallbackFocus()
        }
    }

    private fun resumeControlledApp(packageName: String) {
        packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            runCatching { startActivity(launchIntent) }
                .onSuccess { foregroundPackage = packageName }
                .onFailure { store.lastMediaDiagnostic = "$packageName 返回前台失败：${it.javaClass.simpleName}" }
        }
        // WebView can remain buffering when play is delivered while its activity is still in the background.
        handler.postDelayed({ requestResume(packageName) }, RESUME_PLAY_DELAY_MS)
    }

    private fun unlockFromParent() {
        completeRound()
    }

    private fun applyConfig() {
        val hasActiveTask = isTaskActive() || when (store.progress.phase) {
            QuizPhase.QUIZ_PENDING, QuizPhase.QUIZ_ACTIVE, QuizPhase.ANSWER_FEEDBACK, QuizPhase.COOLDOWN -> true
            else -> false
        }
        val currentAppDisabled = hasActiveTask && store.resumePackage?.let {
            !isPackageGuardEnabled(it)
        } == true
        if (!store.config.enabled || currentAppDisabled) {
            // Saving a disabled guard is also the parent's explicit exit from the current round.
            // Clear persisted progress so re-enabling never resurrects an old question or cooldown.
            if (hasActiveTask || store.accumulatedPlayMs > 0L) {
                completeRound()
            } else {
                releaseFallbackFocus()
                removeOverlay()
            }
            return
        }
        when (store.progress.phase) {
            QuizPhase.QUIZ_ACTIVE -> bank.byId(store.currentQuestionId)?.let(::renderQuestion)
            QuizPhase.ANSWER_FEEDBACK -> bank.byId(store.currentQuestionId)?.let(::renderFeedback)
            QuizPhase.COOLDOWN -> ensureOverlay()
            else -> Unit
        }
    }

    private fun restoreTask() {
        val currentAppDisabled = store.resumePackage?.let { !isPackageGuardEnabled(it) } == true
        if (!store.config.enabled || currentAppDisabled) {
            // Also clean up tasks persisted by older versions that only hid the overlay.
            store.completeRound()
            releaseFallbackFocus()
            removeOverlay()
            return
        }
        val now = SystemClock.elapsedRealtime()
        store.resumePackage?.let(::requestPause)
        when (store.progress.phase) {
            QuizPhase.QUIZ_ACTIVE -> {
                restoreAnswerClock(now)
                val question = bank.byId(store.currentQuestionId)
                if (question == null) showNextQuestion() else renderQuestion(question)
            }
            QuizPhase.ANSWER_FEEDBACK -> {
                restoreFeedbackClock(now)
                val question = bank.byId(store.currentQuestionId)
                if (question == null) finishAnswerFeedback() else renderFeedback(question)
            }
            QuizPhase.COOLDOWN -> {
                restoreCooldownClock(now)
                ensureOverlay()
                overlay?.showCooldown(
                    store.cooldownRemainingMs,
                    store.progress.currentStreak,
                    store.progress.consecutiveWrong,
                    store.config.requiredStreak,
                )
            }
            QuizPhase.QUIZ_PENDING -> Unit
            else -> removeOverlay()
        }
        getSharedPreferences("guard_state", MODE_PRIVATE).edit().putBoolean("restoreAfterBoot", false).apply()
    }

    private fun restoreAnswerClock(now: Long) {
        val sameBoot = store.savedBootCount == store.currentBootCount() && store.savedBootCount != -1
        val remaining = if (sameBoot && store.answerEndsElapsed > 0L) {
            (store.answerEndsElapsed - now).coerceAtLeast(0L)
        } else {
            store.answerRemainingMs
        }
        store.answerRemainingMs = remaining
        store.answerEndsElapsed = now + remaining
        store.saveClockCheckpoint()
    }

    private fun restoreFeedbackClock(now: Long) {
        val sameBoot = store.savedBootCount == store.currentBootCount() && store.savedBootCount != -1
        val remaining = if (sameBoot && store.feedbackEndsElapsed > 0L) {
            (store.feedbackEndsElapsed - now).coerceAtLeast(0L)
        } else {
            store.feedbackRemainingMs
        }
        store.feedbackRemainingMs = remaining
        store.feedbackEndsElapsed = now + remaining
        store.saveClockCheckpoint()
    }

    private fun restoreCooldownClock(now: Long) {
        val sameBoot = store.savedBootCount == store.currentBootCount() && store.savedBootCount != -1
        val remaining = if (sameBoot && store.cooldownEndsElapsed > 0L) {
            (store.cooldownEndsElapsed - now).coerceAtLeast(0L)
        } else {
            store.cooldownRemainingMs
        }
        store.cooldownRemainingMs = remaining
        store.cooldownEndsElapsed = now + remaining
        store.saveClockCheckpoint()
    }

    private fun remainingTime(now: Long, endsAt: Long, savedRemaining: Long): Long =
        if (endsAt > 0L) (endsAt - now).coerceAtLeast(0L) else savedRemaining.coerceAtLeast(0L)

    private fun persistClocks(now: Long) {
        if (!::store.isInitialized) return
        when (store.progress.phase) {
            QuizPhase.COOLDOWN -> store.cooldownRemainingMs = remainingTime(
                now,
                store.cooldownEndsElapsed,
                store.cooldownRemainingMs,
            )
            QuizPhase.QUIZ_ACTIVE -> store.answerRemainingMs = remainingTime(
                now,
                store.answerEndsElapsed,
                store.answerRemainingMs,
            )
            QuizPhase.ANSWER_FEEDBACK -> store.feedbackRemainingMs = remainingTime(
                now,
                store.feedbackEndsElapsed,
                store.feedbackRemainingMs,
            )
            else -> Unit
        }
        store.saveClockCheckpoint()
    }

    fun onSetupVisibilityChanged(visible: Boolean) {
        if (!::handler.isInitialized) return
        if (visible) {
            // MIUI force-stops an app when its setup activity leaves the foreground while
            // an accessibility window exists. Remove that window before the Home transition.
            removeOverlay()
        } else {
            handler.postDelayed(
                { if (running && isTaskActive()) ensureOverlay() },
                SETUP_RETURN_DELAY_MS,
            )
        }
    }

    private fun ensurePlaybackPauseActivity() {
        if (!isTaskActive() || SetupActivity.isVisible || PlaybackPauseActivity.activeInstance != null) return
        runCatching {
            startActivity(
                Intent(this, PlaybackPauseActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            )
        }.onFailure {
            store.lastMediaDiagnostic = "播放暂停页面启动失败：${it.javaClass.simpleName}"
        }
    }

    private fun ensureOverlay() {
        ensurePlaybackPauseActivity()
        if (SetupActivity.isVisible) return
        // addView assigns the parent before dispatchAttachedToWindow. Rendering immediately
        // after addView must not interpret that short interval as a missing window and add it twice.
        if (overlay?.isAttachedToWindow == true || overlay?.parent != null) {
            overlay?.requestFocus()
            return
        }
        runCatching {
            val quizView = overlay ?: QuizOverlayView(this, this).also { overlay = it }
            quizView.isFocusable = true
            quizView.isFocusableInTouchMode = true
            val params = android.view.WindowManager.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                android.graphics.PixelFormat.OPAQUE,
            )
            getSystemService(android.view.WindowManager::class.java).addView(quizView, params)
            quizView.requestFocus()
            renderAttachedTask()
        }.onFailure {
            overlay = null
            store.lastMediaDiagnostic = "全屏答题层创建失败：${it.javaClass.simpleName}"
        }
    }

    private fun removeOverlay() {
        overlay?.let { view ->
            if (view.isAttachedToWindow || view.parent != null) runCatching {
                getSystemService(android.view.WindowManager::class.java).removeViewImmediate(view)
            }
        }
        overlay = null
    }

    private fun renderAttachedTask() {
        val view = overlay ?: return
        when (store.progress.phase) {
            QuizPhase.QUIZ_ACTIVE -> bank.byId(store.currentQuestionId)?.let { question ->
                view.showQuestion(
                    question,
                    question.displayOptions(store.progress.questionSerial),
                    store.progress.currentStreak,
                    store.progress.consecutiveWrong,
                    store.config.requiredStreak,
                    remainingTime(SystemClock.elapsedRealtime(), store.answerEndsElapsed, store.answerRemainingMs),
                )
            }
            QuizPhase.ANSWER_FEEDBACK -> bank.byId(store.currentQuestionId)?.let { question ->
                view.showFeedback(
                    question,
                    question.displayOptions(store.progress.questionSerial),
                    store.feedbackSelectedOptionId,
                    store.progress.currentStreak,
                    store.progress.consecutiveWrong,
                    store.config.requiredStreak,
                    remainingTime(SystemClock.elapsedRealtime(), store.feedbackEndsElapsed, store.feedbackRemainingMs),
                )
            }
            QuizPhase.COOLDOWN -> view.showCooldown(
                remainingTime(SystemClock.elapsedRealtime(), store.cooldownEndsElapsed, store.cooldownRemainingMs),
                store.progress.currentStreak,
                store.progress.consecutiveWrong,
                store.config.requiredStreak,
            )
            else -> Unit
        }
    }

    private fun refreshForegroundFromUsage() {
        if (!::usageManager.isInitialized) return
        runCatching {
            val now = System.currentTimeMillis()
            val elapsed = SystemClock.elapsedRealtime()
            // A rebound service and a TV resumed from standby may not emit a new state-change event.
            // Re-bootstrap while a non-controlled package is cached; real launcher events still win.
            val needsRecovery = foregroundPackage == null || foregroundPackage == packageName ||
                foregroundPackage !in controlledPackages
            val recoveryDue = lastForegroundRecoveryAt == 0L ||
                elapsed - lastForegroundRecoveryAt >= FOREGROUND_RECOVERY_INTERVAL_MS
            val longQuery = needsRecovery && recoveryDue
            if (longQuery) {
                lastForegroundRecoveryAt = elapsed
                foregroundRecoveryCount += 1
            }
            val queryWindowMs = if (longQuery) 24 * 60 * 60 * 1000L else 60_000L
            val events = usageManager.queryEvents(now - queryWindowMs, now)
            val event = UsageEvents.Event()
            val foregroundRecords = mutableListOf<ForegroundRecord>()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    foregroundRecords += ForegroundRecord(event.packageName, event.timeStamp)
                }
            }
            ForegroundPolicy.latestRelevantForeground(foregroundRecords)?.let { foregroundPackage = it }
        }
    }

    private fun allControllers(): List<MediaController> = controllers + directControllers.values

    private fun isActuallyPlaying(packageName: String): Boolean {
        val matching = allControllers().filter { it.packageName == packageName }
        if (matching.isEmpty()) {
            if (packageName == "com.ktcp.video") {
                val audioActive = ::audioManager.isInitialized && audioManager.isMusicActive
                val playing = store.config.allowForegroundEstimate && audioActive
                store.lastMediaDiagnostic = if (playing) {
                    "云视听极光前台且媒体音频活跃：按播放计时"
                } else {
                    "云视听极光无媒体音频：暂停计时"
                }
                return playing
            }
            store.lastMediaDiagnostic = "$packageName 没有可用媒体会话，使用家长配置的降级策略"
            return store.config.allowForegroundEstimate
        }
        val playing = matching.any { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        store.lastMediaDiagnostic = if (playing) "$packageName 媒体会话：播放中" else "$packageName 媒体会话：未播放"
        return playing
    }

    private fun requestPause(packageName: String) {
        val matching = allControllers().filter { it.packageName == packageName }
        if (matching.isEmpty()) {
            if (!fallbackFocusHeld && ::audioManager.isInitialized) {
                val result = audioManager.requestAudioFocus(
                    focusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN,
                )
                fallbackFocusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
            store.lastMediaDiagnostic = if (fallbackFocusHeld) {
                "$packageName 无媒体会话，已通过后台生命周期和持续音频焦点请求暂停"
            } else {
                "$packageName 无媒体会话，音频焦点暂停请求未获系统接受"
            }
            return
        }
        matching.forEach { runCatching { it.transportControls.pause() } }
        store.lastMediaDiagnostic = "$packageName 已发送暂停命令，实际状态继续监测"
    }

    private fun requestResume(packageName: String) {
        allControllers().filter { it.packageName == packageName }.forEach {
            runCatching { it.transportControls.play() }
        }
        releaseFallbackFocus()
    }

    private fun releaseFallbackFocus() {
        if (!fallbackFocusHeld) return
        fallbackFocusHeld = false
        if (::audioManager.isInitialized) audioManager.abandonAudioFocus(focusListener)
    }

    private fun refreshMediaSessions() {
        runCatching {
            val active = mediaManager.getActiveSessions(
                ComponentName(this, GuardNotificationListenerService::class.java),
            )
            refreshControllers(active)
        }.onFailure {
            if (directControllers.isEmpty()) {
                store.lastMediaDiagnostic = "媒体会话读取失败：${it.javaClass.simpleName}，使用前台与音频焦点降级"
            }
        }
    }

    @Synchronized
    private fun refreshControllers(active: List<MediaController>?) {
        if (active == null) return
        val newIds = active.map { it.packageName }.toSet()
        val oldIds = controllers.map { it.packageName }.toSet()
        if (newIds == oldIds && active.size == controllers.size) return
        clearControllers()
        active.forEach { controller ->
            runCatching { controller.registerCallback(controllerCallback, handler) }
            controllers += controller
        }
    }

    @Synchronized
    private fun clearControllers() {
        controllers.forEach { runCatching { it.unregisterCallback(controllerCallback) } }
        controllers.clear()
    }

    private fun diagnostics(): JSONObject = JSONObject()
        .put("serviceConnected", running)
        .put("notificationListenerConnected", GuardNotificationListenerService.connected)
        .put("foregroundPackage", foregroundPackage ?: JSONObject.NULL)
        .put("mediaSessionPackages", JSONArray(allControllers().map { it.packageName }.distinct()))
        .put("accumulatedPlayMs", store.accumulatedPlayMs)
        .put("phase", store.progress.phase.name)
        .put("currentStreak", store.progress.currentStreak)
        .put("consecutiveWrong", store.progress.consecutiveWrong)
        .put("questionSerial", store.progress.questionSerial)
        .put("currentQuestionId", store.currentQuestionId ?: JSONObject.NULL)
        .put("answerRemainingMs", remainingTime(SystemClock.elapsedRealtime(), store.answerEndsElapsed, store.answerRemainingMs))
        .put("feedbackRemainingMs", remainingTime(SystemClock.elapsedRealtime(), store.feedbackEndsElapsed, store.feedbackRemainingMs))
        .put("cooldownRemainingMs", remainingTime(SystemClock.elapsedRealtime(), store.cooldownEndsElapsed, store.cooldownRemainingMs))
        .put("config", store.config.toJson())
        .put("fallbackFocusHeld", fallbackFocusHeld)
        .put("playbackPauseActivityVisible", PlaybackPauseActivity.activeInstance != null)
        .put("lastMediaDiagnostic", store.lastMediaDiagnostic)
        .put("wrongQuestionCount", store.questionHistory.wrongIds.size)
        .put("subjectSeenCounts", JSONObject().apply {
            store.questionHistory.seenBySubject.forEach { (subject, ids) -> put(subject, ids.size) }
        })
        .put("foregroundRecoveryCount", foregroundRecoveryCount)
        .put("pinConfigured", store.pinConfigured)

    companion object {
        @Volatile
        var activeInstance: GuardAccessibilityService? = null
            private set

        private const val INPUT_DEBOUNCE_MS = 400L
        private const val HOME_RETURN_DELAY_MS = 300L
        private const val SETUP_RETURN_DELAY_MS = 1_200L
        private const val RESUME_PLAY_DELAY_MS = 800L
        private const val FOREGROUND_RECOVERY_INTERVAL_MS = 30_000L
    }
}
