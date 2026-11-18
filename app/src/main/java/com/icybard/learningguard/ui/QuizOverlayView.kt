package com.icybard.learningguard.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.icybard.learningguard.R
import com.icybard.learningguard.data.Question
import com.icybard.learningguard.data.QuestionOption

/** A TV-first, focus-driven quiz surface hosted by the accessibility overlay window. */
class QuizOverlayView(
    context: Context,
    private val callbacks: Callbacks,
) : LinearLayout(context) {
    interface Callbacks {
        fun onAnswer(optionId: String)
        fun onGear()
    }

    private enum class Mode { QUESTION, FEEDBACK, COOLDOWN, QR }
    private var mode = Mode.QUESTION
    private var previousMode = Mode.QUESTION
    private var question: Question? = null
    private var options: List<QuestionOption> = emptyList()
    private var feedbackSelectedOptionId: String? = null
    private var streak = 0
    private var wrong = 0
    private var required = 3
    private var answerRemainingMs = 0L
    private var feedbackRemainingMs = 0L
    private var cooldownRemainingMs = 0L
    private lateinit var progressView: TextView
    private lateinit var timerView: TextView
    private val answerViews = mutableListOf<TextView>()
    private var virtualFocus = 0

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = true
        clipToPadding = true
        setPadding(88, 44, 88, 38)
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(5, 7, 13), Color.rgb(17, 28, 45), Color.rgb(5, 8, 15)),
        )
        isFocusable = false
        systemUiVisibility = SYSTEM_UI_FLAG_FULLSCREEN or SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            SYSTEM_UI_FLAG_IMMERSIVE_STICKY or SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed && com.icybard.learningguard.BuildConfig.DEBUG) {
            val children = (0 until childCount).joinToString { index ->
                getChildAt(index).let { "${it.javaClass.simpleName}[${it.left},${it.top},${it.right},${it.bottom}]" }
            }
            val ancestors = generateSequence(this as View) { it.parent as? View }
                .joinToString { "${it.javaClass.simpleName}(scale=${it.scaleX}x${it.scaleY},pivot=${it.pivotX}x${it.pivotY},translation=${it.translationX}x${it.translationY})" }
            android.util.Log.i(
                "QuizLayout",
                "root=[$left,$top,$right,$bottom] padding=[$paddingLeft,$paddingRight] " +
                    "display=${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels} " +
                    "density=${resources.displayMetrics.density} ancestors=$ancestors children=$children",
            )
        }
    }

    fun showQuestion(
        value: Question,
        displayOptions: List<QuestionOption>,
        currentStreak: Int,
        consecutiveWrong: Int,
        requiredStreak: Int,
        remainingMs: Long,
    ) {
        question = value
        options = displayOptions
        streak = currentStreak
        wrong = consecutiveWrong
        required = requiredStreak
        answerRemainingMs = if (com.icybard.learningguard.BuildConfig.DEBUG) 0L else remainingMs
        mode = Mode.QUESTION
        renderQuestion()
    }

    fun showFeedback(
        value: Question,
        displayOptions: List<QuestionOption>,
        selectedOptionId: String?,
        currentStreak: Int,
        consecutiveWrong: Int,
        requiredStreak: Int,
        remainingMs: Long,
    ) {
        question = value
        options = displayOptions
        feedbackSelectedOptionId = selectedOptionId
        feedbackRemainingMs = remainingMs
        streak = currentStreak
        wrong = consecutiveWrong
        required = requiredStreak
        mode = Mode.FEEDBACK
        renderFeedback()
    }

    fun showCooldown(remainingMs: Long, currentStreak: Int, consecutiveWrong: Int, requiredStreak: Int) {
        cooldownRemainingMs = remainingMs
        streak = currentStreak
        wrong = consecutiveWrong
        required = requiredStreak
        mode = Mode.COOLDOWN
        renderCooldown()
    }

    fun tick(answerMs: Long, cooldownMs: Long, currentStreak: Int, consecutiveWrong: Int, requiredStreak: Int) {
        answerRemainingMs = if (com.icybard.learningguard.BuildConfig.DEBUG) 0L else answerMs
        cooldownRemainingMs = cooldownMs
        streak = currentStreak
        wrong = consecutiveWrong
        required = requiredStreak
        if (::progressView.isInitialized) {
            progressView.text = "连续答对  $streak / $required     连续答错  $wrong / 2"
        }
        if (::timerView.isInitialized) {
            timerView.text = when (mode) {
                Mode.COOLDOWN -> "${(cooldownRemainingMs + 999) / 1000}"
                Mode.QUESTION -> if (answerRemainingMs > 0) "本题剩余 ${(answerRemainingMs + 999) / 1000} 秒  ·  答错立即换题" else "请认真思考  ·  答错立即换题"
                Mode.FEEDBACK -> "请记住正确答案  ·  ${(feedbackRemainingMs + 999) / 1000} 秒后继续"
                Mode.QR -> ""
            }
        }
    }

    fun tickFeedback(remainingMs: Long) {
        feedbackRemainingMs = remainingMs
        if (mode == Mode.FEEDBACK && ::timerView.isInitialized) {
            timerView.text = "请记住正确答案  ·  ${(feedbackRemainingMs + 999) / 1000} 秒后继续"
        }
    }

    fun showQr(bitmap: Bitmap, address: String) {
        previousMode = mode
        mode = Mode.QR
        answerViews.clear()
        removeAllViews()
        addView(TextView(context).apply {
            text = "家长扫码设置"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, 10, 0, 10)
        })
        addView(TextView(context).apply {
            text = "在手机浏览器中打开，验证家长 PIN 后调整规则"
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(MUTED)
            setPadding(0, 0, 0, 18)
        })
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            background = rounded(Color.WHITE, 28f)
            setPadding(24, 24, 24, 24)
            layoutParams = LayoutParams(440, 440).apply { gravity = Gravity.CENTER_HORIZONTAL }
            addView(ImageView(context).apply {
                setImageBitmap(bitmap)
                layoutParams = LayoutParams(392, 392)
                contentDescription = "手机局域网设置二维码"
            })
        })
        addView(TextView(context).apply {
            text = "手机需与电视连接同一局域网  ·  地址长期有效，可收藏\n$address"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(MUTED)
            setLineSpacing(0f, 1.25f)
            setPadding(0, 18, 0, 14)
        })
        val back = actionText("返回答题", true).apply {
            (this as RoundedTextView).cardFocused = true
            setTextColor(ACCENT)
            setOnClickListener { restoreAfterQr() }
        }
        addView(back)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Answer feedback is a configurable learning pause, not an interactive question state.
        if (mode == Mode.FEEDBACK) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) handleBack()
            return true
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            -> {
                if (event.action == KeyEvent.ACTION_DOWN && mode == Mode.QUESTION) moveFocus(event.keyCode)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            -> {
                if (event.action == KeyEvent.ACTION_UP) {
                    when (mode) {
                        Mode.QR -> restoreAfterQr()
                        Mode.COOLDOWN -> callbacks.onGear()
                        Mode.QUESTION -> activateVirtualFocus()
                        Mode.FEEDBACK -> Unit
                    }
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    fun handleBack(): Boolean {
        if (mode == Mode.QR) restoreAfterQr()
        return true
    }

    private fun moveFocus(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> virtualFocus = when {
                virtualFocus == GEAR_INDEX -> 0
                virtualFocus == 0 -> GEAR_INDEX
                else -> virtualFocus - 1
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> virtualFocus = when {
                virtualFocus == GEAR_INDEX -> 0
                else -> (virtualFocus + 1).coerceAtMost(answerViews.lastIndex)
            }
            else -> return
        }
        updateVirtualFocus()
    }

    private fun focusFirstAnswer() {
        virtualFocus = 0
        updateVirtualFocus()
    }

    private fun updateVirtualFocus() {
        answerViews.forEachIndexed { index, view ->
            val selected = index == virtualFocus
            (view as RoundedTextView).cardFocused = selected
            view.setTextColor(if (selected) Color.rgb(28, 28, 30) else Color.rgb(245, 245, 247))
            view.animate().cancel()
            view.scaleX = 1f
            view.scaleY = 1f
            if (selected) view.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
        }
        findViewWithTag<View>(GEAR_TAG)?.let { gear ->
            val selected = virtualFocus == GEAR_INDEX
            gear.isSelected = selected
            gear.alpha = if (selected) 1f else 0.72f
            animateFocus(gear, selected)
            if (selected) gear.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
        }
    }

    private fun activateVirtualFocus() {
        if (virtualFocus == GEAR_INDEX) {
            callbacks.onGear()
        } else {
            answerViews.getOrNull(virtualFocus)?.performClick()
        }
    }

    private fun restoreAfterQr() {
        mode = previousMode
        when (mode) {
            Mode.COOLDOWN -> renderCooldown()
            Mode.FEEDBACK -> renderFeedback()
            else -> renderQuestion()
        }
    }

    private fun renderQuestion() = renderQuestionMode(Mode.QUESTION)

    private fun renderFeedback() = renderQuestionMode(Mode.FEEDBACK)

    private fun renderQuestionMode(targetMode: Mode) {
        val current = question ?: return
        mode = targetMode
        answerViews.clear()
        removeAllViews()
        addHeader(subjectName(current.subject))
        addView(ScrollView(context).apply {
            isFocusable = false
            isVerticalScrollBarEnabled = false
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                setMargins(0, 22, 0, 20)
            }
            background = rounded(0x16FFFFFF, 30f, 0x24FFFFFF)
            addView(TextView(context).apply {
                text = current.stem
                textSize = 34f
                setTextColor(Color.WHITE)
                setLineSpacing(0f, 1.24f)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(38, 32, 38, 32)
            })
        })
        options.forEach { option ->
            val answer = answerButton(option)
            answerViews += answer
            addView(answer)
        }
        timerView = TextView(context).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(MUTED)
            setPadding(0, 18, 0, 0)
        }
        addView(timerView)
        tick(answerRemainingMs, cooldownRemainingMs, streak, wrong, required)
        post {
            if (mode == Mode.QUESTION) focusFirstAnswer()
        }
    }

    private fun renderCooldown() {
        mode = Mode.COOLDOWN
        answerViews.clear()
        removeAllViews()
        addHeader("休息一下")
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                setMargins(0, 24, 0, 24)
            }
            background = rounded(0x16FFFFFF, 30f, 0x24FFFFFF)
            addView(TextView(context).apply {
                text = "连续答错 2 题，先让眼睛休息一下"
                textSize = 31f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(255, 214, 165))
            })
            addView(TextView(context).apply {
                textSize = 112f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(0, 12, 0, 0)
                this@QuizOverlayView.timerView = this
            })
            addView(TextView(context).apply {
                text = "秒后可继续答题"
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(MUTED)
            })
            addView(TextView(context).apply {
                text = "倒计时期间不能选择、提交或跳题"
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(MUTED)
                setPadding(0, 28, 0, 0)
            })
        })
        tick(answerRemainingMs, cooldownRemainingMs, streak, wrong, required)
        virtualFocus = GEAR_INDEX
        post { updateVirtualFocus() }
    }

    private fun addHeader(subject: String) {
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 104)
        }
        header.addView(LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = "LEARNING BREAK"
                textSize = 13f
                letterSpacing = 0.18f
                includeFontPadding = false
                setTextColor(ACCENT)
                setPadding(0, 0, 0, 10)
            })
            addView(TextView(context).apply {
                text = subject
                textSize = 25f
                includeFontPadding = false
                setTextColor(Color.WHITE)
            })
        })
        progressView = TextView(context).apply {
            textSize = 18f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(MUTED)
            setPadding(16, 0, 22, 0)
        }
        header.addView(progressView)
        header.addView(ImageButton(context).apply {
            tag = GEAR_TAG
            setImageResource(R.drawable.ic_settings)
            contentDescription = "家长扫码设置"
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(17, 17, 17, 17)
            isFocusable = false
            isFocusableInTouchMode = false
            isEnabled = mode != Mode.FEEDBACK
            alpha = if (isEnabled) 0.72f else 0.35f
            background = rounded(0x18FFFFFF, 22f, 0x2EFFFFFF)
            layoutParams = LayoutParams(64, 64)
            setOnClickListener { if (mode != Mode.FEEDBACK) callbacks.onGear() }
        })
        addView(header)
    }

    private fun answerButton(option: QuestionOption): TextView {
        val feedback = mode == Mode.FEEDBACK
        val correct = feedback && option.id == question?.answerId
        val selectedWrong = feedback && option.id == feedbackSelectedOptionId && !correct
        val suffix = when {
            correct -> "    ✓ 正确答案"
            selectedWrong -> "    你的选择"
            else -> ""
        }
        return actionText(option.text + suffix, false).apply {
            textSize = 25f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(34, 0, 34, 0)
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 88).apply {
                setMargins(0, 0, 0, 12)
            }
            isClickable = !feedback
            if (!feedback) setOnClickListener { callbacks.onAnswer(option.id) }
            if (correct || selectedWrong) {
                (this as RoundedTextView).feedbackColor = if (correct) CORRECT_GREEN else WRONG_RED
                setTextColor(Color.rgb(5, 7, 13))
            }
        }
    }

    private fun actionText(value: String, centered: Boolean): TextView = RoundedTextView(context).apply {
        text = value
        textSize = 21f
        gravity = if (centered) Gravity.CENTER else Gravity.CENTER_VERTICAL
        setTextColor(Color.rgb(245, 245, 247))
        background = null
        isFocusable = false
        isFocusableInTouchMode = false
        isClickable = true
        if (centered) {
            layoutParams = LayoutParams(280, 66).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
    }

    /** The same drawable renderer as the other cards; focused options are never scaled. */
    private class RoundedTextView(context: Context) : TextView(context) {
        var cardFocused = false
            set(value) {
                field = value
                renderCard()
            }
        var feedbackColor: Int? = null
            set(value) {
                field = value
                renderCard()
            }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            renderCard()
        }

        private fun renderCard() {
            stateListAnimator = null
            elevation = 0f
            background = GradientDrawable().apply {
                setColor(feedbackColor ?: if (cardFocused) Color.rgb(245, 245, 247) else 0x20FFFFFF)
                cornerRadius = CORNER_RADIUS
                setStroke(1, if (cardFocused || feedbackColor != null) Color.TRANSPARENT else 0x2EFFFFFF)
            }
            clipToOutline = true
        }
    }

    private fun animateFocus(view: View, focused: Boolean) {
        view.animate().cancel()
        view.animate().scaleX(if (focused) 1.018f else 1f).scaleY(if (focused) 1.018f else 1f).setDuration(120).start()
    }

    private fun rounded(color: Int, radius: Float, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (stroke != Color.TRANSPARENT) setStroke(1, stroke)
    }

    private fun subjectName(value: String): String = when (value) {
        "chinese" -> "语文"
        "math" -> "数学"
        "english" -> "英语"
        else -> "学习"
    }

    companion object {
        private const val GEAR_TAG = "parent_settings"
        private const val GEAR_INDEX = 4
        private const val CORNER_RADIUS = 24f
        private val ACCENT = Color.rgb(128, 211, 255)
        private val MUTED = Color.rgb(174, 184, 201)
        private val CORRECT_GREEN = Color.rgb(134, 239, 172)
        private val WRONG_RED = Color.rgb(252, 165, 165)
    }
}
