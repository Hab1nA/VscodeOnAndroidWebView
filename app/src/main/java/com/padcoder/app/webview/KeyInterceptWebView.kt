package com.padcoder.app.webview

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * 深度定制的 WebView，核心使命：
 *
 * 拦截物理键盘的所有组合键，防止被 Android 系统（尤其是 VIVO Origin OS）消费，
 * 确保所有按键事件透传至 VS Code Web 端。
 *
 * 关键拦截策略：
 * 1. dispatchKeyEvent 层面拦截所有包含 Ctrl/Alt/Meta 修饰键的组合键。
 * 2. 使用 WebView 内部的快捷键注入机制（dispatchKeyEvent → WebView InputConnection）。
 * 3. 特别处理 Ctrl+W（关闭标签页）、Ctrl+Space（输入法切换）等高频冲突键。
 * 4. Android 16 下，system bar 手势优先级降低，物理键盘处理更加可控。
 */
class KeyInterceptWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "KeyInterceptWebView"

        // ─── VS Code 核心快捷键白名单 ─────────────────
        // 这些键的组合（无论是否带修饰键）都必须透传给 WebView
        // VS Code 会在内部处理它们的语义

        /** Ctrl 组合键白名单：必须透传的键码 */
        val VSCODE_CTRL_COMBOS = setOf(
            KeyEvent.KEYCODE_S,          // Ctrl+S: 保存
            KeyEvent.KEYCODE_W,          // Ctrl+W: 关闭标签页
            KeyEvent.KEYCODE_T,          // Ctrl+T: 新标签页
            KeyEvent.KEYCODE_N,          // Ctrl+N: 新窗口/新文件
            KeyEvent.KEYCODE_P,          // Ctrl+P: 快速打开
            KeyEvent.KEYCODE_K,          // Ctrl+K: 命令面板前缀
            KeyEvent.KEYCODE_G,          // Ctrl+G: 跳转到行
            KeyEvent.KEYCODE_D,          // Ctrl+D: 选中下一个
            KeyEvent.KEYCODE_F,          // Ctrl+F: 查找
            KeyEvent.KEYCODE_H,          // Ctrl+H: 替换
            KeyEvent.KEYCODE_B,          // Ctrl+B: 侧边栏
            KeyEvent.KEYCODE_J,          // Ctrl+J: 终端面板
            KeyEvent.KEYCODE_MINUS,       // Ctrl+-: 缩小
            KeyEvent.KEYCODE_EQUALS,      // Ctrl+= / Ctrl++: 放大
            KeyEvent.KEYCODE_0,          // Ctrl+0: 重置缩放
            KeyEvent.KEYCODE_TAB,        // Ctrl+Tab: 切换标签页
            KeyEvent.KEYCODE_LEFT_BRACKET, // Ctrl+[: 缩进减少
            KeyEvent.KEYCODE_RIGHT_BRACKET, // Ctrl+]: 缩进增加
            KeyEvent.KEYCODE_SLASH,      // Ctrl+/: 注释切换
            KeyEvent.KEYCODE_BACKSLASH,  // Ctrl+\: 拆分编辑器
            KeyEvent.KEYCODE_1,          // Ctrl+1: 第1个编辑器组
            KeyEvent.KEYCODE_2,          // Ctrl+2
            KeyEvent.KEYCODE_3,          // Ctrl+3
            KeyEvent.KEYCODE_4,          // Ctrl+4
            KeyEvent.KEYCODE_5,          // Ctrl+5
            KeyEvent.KEYCODE_6,          // Ctrl+6
            KeyEvent.KEYCODE_7,          // Ctrl+7
            KeyEvent.KEYCODE_8,          // Ctrl+8
            KeyEvent.KEYCODE_9,          // Ctrl+9
            KeyEvent.KEYCODE_Z,          // Ctrl+Z: 撤销
            KeyEvent.KEYCODE_Y,          // Ctrl+Y: 重做
            KeyEvent.KEYCODE_X,          // Ctrl+X: 剪切
            KeyEvent.KEYCODE_C,          // Ctrl+C: 复制
            KeyEvent.KEYCODE_V,          // Ctrl+V: 粘贴
            KeyEvent.KEYCODE_A,          // Ctrl+A: 全选
            KeyEvent.KEYCODE_ENTER,      // Ctrl+Enter: 插入新行
            KeyEvent.KEYCODE_SHIFT_LEFT,  // Ctrl+Shift+... 组合
            KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_SPACE,      // Ctrl+Space: 触发建议
            KeyEvent.KEYCODE_PERIOD,     // Ctrl+.: 快速修复
            KeyEvent.KEYCODE_COMMA,      // Ctrl+,: 设置
            KeyEvent.KEYCODE_F,          // Ctrl+F: 搜索
            KeyEvent.KEYCODE_E,          // Ctrl+E: 快速打开最近
            KeyEvent.KEYCODE_Q,          // Ctrl+Q: 快速打开视图
            KeyEvent.KEYCODE_R,          // Ctrl+R: 最近项目
            KeyEvent.KEYCODE_M,          // Ctrl+M: Tab 切换
        )

        /** Alt 组合键白名单 */
        val VSCODE_ALT_COMBOS = setOf(
            KeyEvent.KEYCODE_DPAD_UP,    // Alt+Up: 上移行
            KeyEvent.KEYCODE_DPAD_DOWN,  // Alt+Down: 下移行
            KeyEvent.KEYCODE_DPAD_LEFT,  // Alt+Left: 后退
            KeyEvent.KEYCODE_DPAD_RIGHT, // Alt+Right: 前进
            KeyEvent.KEYCODE_Z,          // Alt+Z: 切换自动换行
            KeyEvent.KEYCODE_TAB,        // Alt+Tab: 本应用内切换（非系统级）
            KeyEvent.KEYCODE_1,          // Alt+1~9: 切换标签页组
            KeyEvent.KEYCODE_2,
            KeyEvent.KEYCODE_3,
            KeyEvent.KEYCODE_4,
            KeyEvent.KEYCODE_5,
            KeyEvent.KEYCODE_6,
            KeyEvent.KEYCODE_7,
            KeyEvent.KEYCODE_8,
            KeyEvent.KEYCODE_9,
            KeyEvent.KEYCODE_ENTER,      // Alt+Enter: 全选引用
            KeyEvent.KEYCODE_F12,        // Alt+F12: 终端焦点
        )

        /** 系统级需要强制消费的键（彻底禁止系统响应） */
        val SYSTEM_BLOCK_KEYS = setOf(
            KeyEvent.KEYCODE_HOME,       // Home 键
            KeyEvent.KEYCODE_BACK,       // 返回键（在 WebView 全屏时）
            KeyEvent.KEYCODE_APP_SWITCH, // 多任务键
            KeyEvent.KEYCODE_VOLUME_UP,  // 音量键（防止误触）
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        )
    }

    // ─── 状态标志 ───────────────────────────────────────

    /** 是否启用完全键盘捕获模式 */
    private var isKeyboardCaptureEnabled = true

    /** 当前的修饰键状态 */
    private var currentModifiers = 0

    // ─── 初始化 ─────────────────────────────────────────

    init {
        setupWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            // 允许 WebView 接收键盘焦点
            isFocusable = true
            isFocusableInTouchMode = true
        }

        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 页面加载后注入键盘事件处理器
                injectKeyboardHelper()
            }
        }

        // 确保 WebView 可获取焦点
        requestFocus()
    }

    // ─── 核心按键拦截 ───────────────────────────────────

    /**
     * 重写 dispatchKeyEvent - 这是拦截系统快捷键的最早切入点。
     *
     * 策略：
     * 1. 如果修饰键激活（Ctrl/Alt/Meta），检查白名单 → 透传到 WebView
     * 2. 系统级快捷键（如 Alt+Tab、Ctrl+Esc）→ 在 App 内消费掉
     * 3. 普通键 → 正常处理
     *
     * @return true 表示事件已被消费（系统不会再处理）
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!isKeyboardCaptureEnabled) {
            return super.dispatchKeyEvent(event)
        }

        val keyCode = event.keyCode
        val action = event.action
        val metaState = event.metaState
        val hasCtrl = event.isCtrlPressed
        val hasAlt = event.isAltPressed
        val hasMeta = event.isMetaPressed  // Meta = Windows/Super 键
        val hasShift = event.isShiftPressed

        // 更新修饰键状态追踪
        if (action == KeyEvent.ACTION_DOWN) {
            currentModifiers = 0
            if (hasCtrl) currentModifiers = currentModifiers or KeyEvent.META_CTRL_ON
            if (hasAlt) currentModifiers = currentModifiers or KeyEvent.META_ALT_ON
            if (hasMeta) currentModifiers = currentModifiers or KeyEvent.META_META_ON
            if (hasShift) currentModifiers = currentModifiers or KeyEvent.META_SHIFT_ON
        }

        // ── 场景 1：系统级强制拦截键 ─────────────────
        if (keyCode in SYSTEM_BLOCK_KEYS) {
            // 如果是音量键，我们不消费它，让系统处理
            if (keyCode in setOf(
                    KeyEvent.KEYCODE_VOLUME_UP,
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    KeyEvent.KEYCODE_VOLUME_MUTE
                )
            ) {
                return super.dispatchKeyEvent(event)
            }
            // Home/Back/AppSwitch 在 WebView 活跃时拦截
            return true
        }

        // ── 场景 2：Ctrl+组合键 ──────────────────────
        if (hasCtrl && keyCode in VSCODE_CTRL_COMBOS) {
            // 强制将事件发送到 WebView 内部，阻止系统消费
            return dispatchToWebView(event)
        }

        // ── 场景 3：Alt+组合键 ───────────────────────
        if (hasAlt && keyCode in VSCODE_ALT_COMBOS) {
            return dispatchToWebView(event)
        }

        // ── 场景 4：Meta (Super/Win) 组合键 ─────────
        if (hasMeta) {
            // Meta+任意键都透传（VS Code 可能用到）
            return dispatchToWebView(event)
        }

        // ── 场景 5：Ctrl 单独按下/释放（Ctrl+Click 选择多光标）──
        if (keyCode == KeyEvent.KEYCODE_CTRL_LEFT ||
            keyCode == KeyEvent.KEYCODE_CTRL_RIGHT
        ) {
            return dispatchToWebView(event)
        }

        // ── 场景 6：ESC 键 ───────────────────────────
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            return dispatchToWebView(event)
        }

        // ── 场景 7：F 功能键 (F1~F12) ───────────────
        if (keyCode in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12) {
            return dispatchToWebView(event)
        }

        // ── 默认：正常的按键分发 ─────────────────────
        return super.dispatchKeyEvent(event)
    }

    /**
     * 将按键事件直接分发到 WebView 内部，绕过 Android 系统的按键处理链。
     *
     * 注意：不调用 super.dispatchKeyEvent()，因为那会触发 Activity 的
     * onKeyDown/onKeyUp 回调，而在 Activity 层面，系统可能会抢先处理。
     */
    private fun dispatchToWebView(event: KeyEvent): Boolean {
        return try {
            // 直接调用 WebView 的 onKeyDown/onKeyUp
            // 这会经过 WebView 内部的按键处理 → InputConnection → JS
            @Suppress("DEPRECATION")
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    onKeyDown(event.keyCode, event)
                }
                KeyEvent.ACTION_UP -> {
                    onKeyUp(event.keyCode, event)
                }
            }
            true
        } catch (e: Exception) {
            // 如果 WebView 内部处理失败，回退到默认行为
            super.dispatchKeyEvent(event)
        }
    }

    // ─── JS 键盘辅助注入 ─────────────────────────────────

    /**
     * 在 VS Code 加载完成后注入 JavaScript，用于：
     *
     * 1. 屏蔽 VS Code 端的某些浏览器快捷键处理（防止 double handling）。
     * 2. 确保物理键盘事件被正确识别（防止 VS Code 认为没有键盘）。
     * 3. 上报按键事件用于调试。
     */
    private fun injectKeyboardHelper() {
        val js = """
            (function() {
                // 告诉 VS Code 物理键盘可用
                try {
                    if (window.navigator && window.navigator.keyboard) {
                        console.log('[PadCoder] Physical keyboard detected');
                    }
                } catch(e) {}

                // 确保 keydown/keyup 事件不被 preventDefault
                document.addEventListener('keydown', function(e) {
                    // 记录所有 Ctrl 组合键用于调试
                    if (e.ctrlKey && !e.metaKey) {
                        console.log('[PadCoder] Ctrl+' + e.key.toUpperCase() + ' intercepted');
                    }
                }, { capture: true, passive: false });

                // 禁用 VS Code 的 Alt 键菜单激活（防止 Alt 键触发浏览器菜单栏）
                document.addEventListener('keydown', function(e) {
                    if (e.altKey && e.keyCode === 18) {
                        e.preventDefault();
                    }
                }, { capture: true });

                // 通知 WebView 已就绪
                if (window.Android) {
                    window.Android.onWebViewReady();
                }
            })();
        """.trimIndent()

        evaluateJavascript(js, null)
    }

    // ─── 公开控制方法 ───────────────────────────────────

    /**
     * 切换键盘捕获模式。
     *
     * 在用户需要访问 Android 系统功能（如通知栏）时，
     * 可以临时关闭捕获模式。
     */
    fun setKeyboardCaptureEnabled(enabled: Boolean) {
        isKeyboardCaptureEnabled = enabled
    }

    fun isKeyboardCaptureEnabled(): Boolean = isKeyboardCaptureEnabled

    /**
     * 获取当前修饰键状态，用于 UI 提示（如状态栏显示 Caps 锁）。
     */
    fun getCurrentModifiers(): Int = currentModifiers

    /**
     * 重置修饰键状态（在 Activity onPause 时调用，
     * 防止从其他 App 回来时修饰键状态残留）。
     */
    fun resetModifierState() {
        currentModifiers = 0
        // 清除所有按下的键
        clearFocus()
        requestFocus()
    }
}