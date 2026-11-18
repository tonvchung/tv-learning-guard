package com.icybard.learningguard

import android.app.Activity
import android.app.AppOpsManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.icybard.learningguard.parent.PinManager

class SetupActivity : Activity() {
    private lateinit var pinManager: PinManager

    companion object {
        @Volatile
        var isVisible: Boolean = false
            private set
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isVisible = true
        pinManager = PinManager(this)
        runCatching {
            android.service.notification.NotificationListenerService.requestRebind(
                android.content.ComponentName(this, GuardNotificationListenerService::class.java),
            )
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        isVisible = true
        GuardAccessibilityService.activeInstance?.onSetupVisibilityChanged(true)
    }

    override fun onPause() {
        isVisible = false
        GuardAccessibilityService.activeInstance?.onSetupVisibilityChanged(false)
        super.onPause()
        // The setup page is a one-shot ADB entry, not a child-facing destination.
        // Do not leave its activity task alongside a restored accessibility window.
        finish()
    }

    private fun render() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(96, 64, 96, 64)
            setBackgroundColor(Color.rgb(7, 17, 31))
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val title = TextView(this).apply {
            text = if (pinManager.isConfigured) "学习守护 · 初始化状态" else "学习守护 · 首次初始化"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 28)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        content.addView(title)

        if (!pinManager.isConfigured) {
            content.addView(label("请设置 6 位家长 PIN。它不会显示在命令行或二维码中。"))
            val pin = passwordField("输入 6 位数字 PIN")
            val confirmation = passwordField("再次输入 PIN")
            content.addView(pin)
            content.addView(confirmation)
            content.addView(Button(this).apply {
                text = "保存 PIN 并完成初始化"
                textSize = 20f
                gravity = Gravity.CENTER
                setOnClickListener {
                    if (pin.text.toString() != confirmation.text.toString()) {
                        Toast.makeText(this@SetupActivity, "两次输入不一致", Toast.LENGTH_LONG).show()
                    } else if (!pin.text.toString().matches(Regex("\\d{6}"))) {
                        Toast.makeText(this@SetupActivity, "请输入 6 位数字", Toast.LENGTH_LONG).show()
                    } else {
                        pinManager.configure(pin.text.toString())
                        Toast.makeText(this@SetupActivity, "初始化完成", Toast.LENGTH_LONG).show()
                        render()
                    }
                }
            })
        } else {
            content.addView(label("PIN 已设置。日常规则请从答题页右上角齿轮扫码，在手机上修改。"))
            content.addView(TextView(this).apply {
                text = diagnostics()
                textSize = 18f
                setTextColor(Color.rgb(203, 213, 225))
                setLineSpacing(0f, 1.35f)
                setPadding(0, 20, 0, 20)
            })
            content.addView(Button(this).apply {
                text = "刷新权限自检"
                gravity = Gravity.CENTER
                setOnClickListener { render() }
            })
            content.addView(Button(this).apply {
                text = "显示家长设置二维码"
                gravity = Gravity.CENTER
                setOnClickListener {
                    val url = GuardAccessibilityService.activeInstance?.issueParentEntryUrl()
                    if (url == null) {
                        Toast.makeText(this@SetupActivity, "守护服务尚未就绪，请稍后重试", Toast.LENGTH_LONG).show()
                    } else {
                        showParentQr(url)
                    }
                }
            })
        }
        setContentView(
            content,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        content.requestFocus()
    }

    private fun showParentQr(url: String) {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(96, 64, 96, 64)
            setBackgroundColor(Color.rgb(7, 17, 31))
        }
        page.addView(android.widget.ImageView(this).apply {
            setImageBitmap(com.icybard.learningguard.parent.QrCodes.create(url, 512))
            setBackgroundColor(Color.WHITE)
            setPadding(16, 16, 16, 16)
            layoutParams = LinearLayout.LayoutParams(544, 544)
        })
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 0, 0, 0)
        }
        actions.addView(TextView(this).apply {
            text = "手机扫码进入家长设置"
            textSize = 30f
            setTextColor(Color.WHITE)
        })
        actions.addView(label("二维码与页面地址长期有效，可收藏到手机。下次在局域网内直接打开，输入家长 PIN 即可。"))
        actions.addView(Button(this).apply {
            text = "返回初始化状态"
            gravity = Gravity.CENTER
            setOnClickListener { render() }
        })
        page.addView(actions)
        setContentView(page)
        actions.getChildAt(2).requestFocus()
    }

    private fun passwordField(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        textSize = 22f
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        setTextColor(Color.WHITE)
        setHintTextColor(Color.rgb(148, 163, 184))
    }

    private fun label(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 19f
        setTextColor(Color.rgb(203, 213, 225))
        setPadding(0, 0, 0, 18)
    }

    private fun diagnostics(): String {
        val accessibility = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty().contains(packageName)
        val listeners = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners",
        ).orEmpty().contains(packageName)
        val appOps = getSystemService(AppOpsManager::class.java)
        val usage = runCatching {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                applicationInfo.uid,
                packageName,
            ) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
        return buildString {
            append(if (accessibility) "✓" else "✕").append(" 无障碍监控\n")
            when {
                GuardNotificationListenerService.connected -> append("✓ 媒体会话监听已绑定\n")
                listeners -> append("△ 媒体会话已授权，等待系统绑定\n")
                else -> append("✕ 媒体会话监听未授权\n")
            }
            append(if (usage) "✓" else "✕").append(" 使用情况访问\n")
            val state = com.icybard.learningguard.data.GuardStore(this@SetupActivity)
            append("\n任务状态：${state.progress.phase}，题目：${state.currentQuestionId ?: "无"}")
            append("\n触发配置：${state.config.triggerPlayMs} ms，守护开关：${state.config.enabled}")
            append("\n受控应用：云视听极光（${if (state.config.guardCloudEnabled) "开启" else "关闭"}）、")
            append("WebTV（${if (state.config.guardWebTvEnabled) "开启" else "关闭"}）")
            append("\n默认规则：播放 5 分钟，连续答对 3 题，连错 2 题冷却 120 秒")
        }
    }
}
