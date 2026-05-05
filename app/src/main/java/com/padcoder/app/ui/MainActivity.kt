package com.padcoder.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.padcoder.app.R
import com.padcoder.app.bridge.TermuxBridge
import com.padcoder.app.manager.CodeServerManager
import com.padcoder.app.viewmodel.AppViewModel
import com.padcoder.app.webview.KeyInterceptWebView
import kotlinx.coroutines.launch

/**
 * PadCoder 主 Activity。
 *
 * 核心职责：
 * 1. 管理全屏 WebView（加载 code-server UI）
 * 2. 协调按键拦截
 * 3. 展示环境状态 UI（设置向导 / 诊断面板）
 * 4. 处理权限请求流程
 */
class MainActivity : ComponentActivity() {

    private lateinit var viewModel: AppViewModel

    // ─── UI 控件 ───────────────────────────────────────
    private lateinit var webView: KeyInterceptWebView
    private lateinit var setupOverlay: View
    private lateinit var statusCard: MaterialCardView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnSetup: MaterialButton
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnDiagnostics: MaterialButton
    private lateinit var btnUpdate: MaterialButton
    private lateinit var btnUninstall: MaterialButton
    private lateinit var logCard: MaterialCardView
    private lateinit var logTextView: TextView
    private lateinit var currentStepText: TextView
    private lateinit var logScrollView: ScrollView

    // ─── 权限请求 Launcher ─────────────────────────────
    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.checkPermissions()
    }

    // ─── 生命周期 ──────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 沉浸式全屏
        setupImmersiveMode()

        setContentView(R.layout.activity_main)

        // 初始化 ViewModel
        viewModel = AppViewModel(application)

        // 初始化 UI 控件
        bindViews()

        // 设置 WebView
        setupWebView()

        // 设置按钮监听
        setupButtonListeners()

        // 设置返回键回调（替代 deprecated onBackPressed）
        setupBackPressCallback()

        // 观察状态变化
        observeState()

        // 初始化环境检测
        viewModel.initialize()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()

        // 恢复后重新检查权限（用户可能从设置页返回）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            viewModel.checkPermissions()
        }
    }

    override fun onPause() {
        webView.onPause()
        webView.pauseTimers()
        webView.resetModifierState()
        super.onPause()
    }

    override fun onDestroy() {
        // 停止 code-server（如果正在运行）
        lifecycleScope.launch {
            if (CodeServerManager.getCurrentStatus().state ==
                CodeServerManager.ServerState.RUNNING
            ) {
                CodeServerManager.stopServer(applicationContext)
            }
        }
        webView.destroy()
        super.onDestroy()
    }

    // ─── 返回键处理（替代 deprecated onBackPressed）───

    private fun setupBackPressCallback() {
        onBackPressedDispatcher.addCallback(this) {
            // 如果 WebView 可以回退，则在 WebView 内回退
            if (webView.canGoBack() && webView.visibility == View.VISIBLE) {
                webView.goBack()
            } else {
                // 由系统处理（退出 Activity）
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }
    }

    // ─── 沉浸式全屏 ────────────────────────────────────

    private fun setupImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ─── UI 绑定 ───────────────────────────────────────

    private fun bindViews() {
        webView = findViewById(R.id.webview)
        setupOverlay = findViewById(R.id.setup_overlay)
        statusCard = findViewById(R.id.status_card)
        statusText = findViewById(R.id.status_text)
        progressBar = findViewById(R.id.progress_bar)
        btnSetup = findViewById(R.id.btn_setup)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        btnDiagnostics = findViewById(R.id.btn_diagnostics)
        btnUpdate = findViewById(R.id.btn_update)
        btnUninstall = findViewById(R.id.btn_uninstall)
        logCard = findViewById(R.id.log_card)
        logTextView = findViewById(R.id.log_text_view)
        currentStepText = findViewById(R.id.current_step_text)
        logScrollView = findViewById(R.id.log_scroll_view)
    }

    // ─── WebView 配置 ──────────────────────────────────

    private fun setupWebView() {
        webView.apply {
            setKeyboardCaptureEnabled(true)
        }
    }

    // ─── 按钮监听 ──────────────────────────────────────

    private fun setupButtonListeners() {
        btnSetup.setOnClickListener {
            viewModel.installAndStartCodeServer()
        }

        btnStart.setOnClickListener {
            viewModel.startOnly()
        }

        btnStop.setOnClickListener {
            viewModel.stopCodeServer()
        }

        btnDiagnostics.setOnClickListener {
            showDiagnosticsDialog()
        }

        btnUpdate.setOnClickListener {
            viewModel.checkForUpdates()
        }

        btnUninstall.setOnClickListener {
            showUninstallConfirmDialog()
        }
    }

    // ─── 状态观察 ──────────────────────────────────────

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updateUI(state)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    handleEvent(event)
                }
            }
        }
    }

    /**
     * 根据 UiState 更新所有 UI 控件。
     */
    private fun updateUI(state: UiState) {
        // ── 环境状态 ──
        when (state.environmentStatus) {
            TermuxBridge.EnvironmentStatus.FULLY_READY -> {
                statusText.text = "✅ 环境就绪"
            }
            TermuxBridge.EnvironmentStatus.NEED_TASKER -> {
                statusText.text = "⚠️ 请安装 Termux:Tasker"
            }
            TermuxBridge.EnvironmentStatus.NEED_TERMUX -> {
                statusText.text = "❌ 未检测到 Termux"
            }
            TermuxBridge.EnvironmentStatus.UNSUPPORTED -> {
                statusText.text = "❌ 设备不支持"
            }
        }

        // ── 权限状态 ──
        if (!state.permissionsReady && state.environmentStatus !=
            TermuxBridge.EnvironmentStatus.NEED_TERMUX
        ) {
            statusText.text = "${statusText.text}\n🔒 权限未完全授予"
        }

        // ── 服务状态 ──
        when (state.serverStatus.state) {
            CodeServerManager.ServerState.IDLE -> {
                progressBar.visibility = View.GONE
                btnStart.isEnabled = true
                btnStop.isEnabled = false
            }
            CodeServerManager.ServerState.DOWNLOADING -> {
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = false
                progressBar.progress = (state.serverStatus.downloadProgress * 100).toInt()
                statusText.text = "📥 下载中..."
                btnSetup.isEnabled = false
                btnStart.isEnabled = false
                btnStop.isEnabled = false
            }
            CodeServerManager.ServerState.INSTALLED -> {
                progressBar.visibility = View.GONE
                statusText.text = "📦 code-server ${state.serverStatus.codeServerVersion ?: ""}"
                btnStart.isEnabled = true
                btnStop.isEnabled = false
            }
            CodeServerManager.ServerState.STARTING -> {
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = true
                statusText.text = "🚀 启动中..."
                btnStart.isEnabled = false
                btnStop.isEnabled = false
            }
            CodeServerManager.ServerState.RUNNING -> {
                progressBar.visibility = View.GONE
                statusText.text = "🟢 code-server 运行中 (端口 ${state.serverStatus.port})"
                btnStart.isEnabled = false
                btnStop.isEnabled = true
                btnSetup.isEnabled = false
                // 隐藏设置面板，显示 WebView
                showWebView()
            }
            CodeServerManager.ServerState.STOPPING -> {
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = true
                statusText.text = "🛑 停止中..."
                btnStop.isEnabled = false
            }
            CodeServerManager.ServerState.STOPPED -> {
                progressBar.visibility = View.GONE
                statusText.text = "⏹️ code-server 已停止"
                btnStart.isEnabled = true
                btnStop.isEnabled = false
                showSetupOverlay()
            }
            CodeServerManager.ServerState.ERROR -> {
                progressBar.visibility = View.GONE
                statusText.text = "❌ ${state.serverStatus.errorMessage ?: "未知错误"}"
                btnSetup.isEnabled = true
                btnStart.isEnabled = true
                btnStop.isEnabled = false
            }
        }

        // ── 更新状态 ──
        if (state.isUpdating) {
            progressBar.visibility = View.VISIBLE
            progressBar.isIndeterminate = false
            progressBar.progress = (state.updateProgress * 100).toInt()
            statusText.text = "🔄 更新中..."
            btnUpdate.isEnabled = false
        }

        // ── 实时日志面板 ──
        updateLogPanel(state)

        // ── 加载状态 ──
        if (state.isLoading && state.serverStatus.state == CodeServerManager.ServerState.IDLE) {
            progressBar.visibility = View.VISIBLE
            progressBar.isIndeterminate = true
        }
    }

    /**
     * 更新实时日志面板。
     * 当有日志内容或非空闲状态时显示面板。
     */
    private fun updateLogPanel(state: UiState) {
        val logs = state.serverStatus.logLines
        val currentStep = state.serverStatus.currentStep
        val serverState = state.serverStatus.state

        // 仅在非空闲/非运行状态且有日志时显示面板
        val shouldShow = logs.isNotEmpty() &&
            serverState != CodeServerManager.ServerState.IDLE &&
            serverState != CodeServerManager.ServerState.RUNNING

        logCard.visibility = if (shouldShow) View.VISIBLE else View.GONE

        if (shouldShow) {
            // 格式化日志（最近 50 行，避免一次性渲染过多内容）
            val displayLogs = logs.takeLast(50)
            val formatted = displayLogs.joinToString("\n") { entry ->
                val levelMark = when (entry.level) {
                    "ERROR" -> "❌"
                    "WARN" -> "⚠️"
                    else -> "  "
                }
                "$levelMark ${entry.message}"
            }
            logTextView.text = formatted.ifEmpty { "等待操作..." }
            currentStepText.text = currentStep

            // 自动滚动到底部
            logScrollView.post {
                logScrollView.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    /**
     * 处理一次性事件（Toast、导航等）。
     */
    private fun handleEvent(event: UiEvent) {
        when (event) {
            is UiEvent.ShowToast -> {
                Toast.makeText(this, event.message, Toast.LENGTH_LONG).show()
                viewModel.clearToast()
            }
            is UiEvent.NavigateTo -> {
                handleNavigation(event.destination)
                viewModel.clearNavigation()
            }
            is UiEvent.RefreshCompleted -> {
                viewModel.checkPermissions()
            }
        }
    }

    // ─── 导航处理 ──────────────────────────────────────

    private fun handleNavigation(destination: String) {
        when {
            destination.startsWith("webview:") -> {
                val url = destination.removePrefix("webview:")
                loadCodeServerUI(url)
            }
            destination == "diagnostics-report" -> {
                // 诊断报告已在 showDiagnosticsDialog 中处理
            }
        }
    }

    /**
     * 在 WebView 中加载 code-server 界面。
     */
    private fun loadCodeServerUI(url: String) {
        webView.loadUrl(url)
        showWebView()
    }

    // ─── UI 切换 ───────────────────────────────────────

    /**
     * 显示 WebView，隐藏设置面板。
     */
    private fun showWebView() {
        webView.visibility = View.VISIBLE
        setupOverlay.visibility = View.GONE
        webView.requestFocus()
    }

    /**
     * 显示设置面板，隐藏 WebView。
     */
    private fun showSetupOverlay() {
        webView.visibility = View.GONE
        setupOverlay.visibility = View.VISIBLE
    }

    // ─── 对话框 ────────────────────────────────────────

    /**
     * 显示诊断对话框。
     */
    private fun showDiagnosticsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_diagnostics, null)
        val reportText = dialogView.findViewById<TextView>(R.id.diagnostics_text)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("系统诊断")
            .setView(dialogView)
            .setPositiveButton("关闭", null)
            .create()

        dialog.show()

        // 流式更新诊断报告
        lifecycleScope.launch {
            viewModel.runDiagnostics().collect { line ->
                reportText.append(line + "\n")
            }
        }
    }

    /**
     * 显示卸载确认对话框。
     */
    private fun showUninstallConfirmDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("确认清理")
            .setMessage("将停止 code-server 并删除所有相关文件。是否继续？")
            .setPositiveButton("确认清理") { _, _ ->
                viewModel.uninstall()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 显示 Termux 未安装的引导对话框。
     */
    private fun showTermuxGuideDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("需要 Termux")
            .setMessage(
                "PadCoder 依赖 Termux 环境来运行 code-server。\n\n" +
                        "请按以下步骤操作：\n" +
                        "1. 从 F-Droid 安装 Termux\n" +
                        "2. 安装 Termux:Tasker 插件\n" +
                        "3. 在 Termux 中运行 pkg update 初始化环境\n" +
                        "4. 返回 PadCoder 继续设置"
            )
            .setPositiveButton("前往 F-Droid") { _, _ ->
                try {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://f-droid.org/packages/com.termux/")
                        )
                    )
                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        "无法打开浏览器，请手动访问 f-droid.org",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton("稍后", null)
            .show()
    }

    // ─── 权限处理 ──────────────────────────────────────

    /**
     * 请求 MANAGE_EXTERNAL_STORAGE 权限。
     */
    private fun requestManageStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MaterialAlertDialogBuilder(this)
                .setTitle("需要存储权限")
                .setMessage(
                    "PadCoder 需要「所有文件访问权限」才能将 code-server 文件" +
                            "写入 Termux 可访问的共享存储位置。"
                )
                .setPositiveButton("前往设置") { _, _ ->
                    val intent = viewModel.getManageStorageIntent()
                    manageStorageLauncher.launch(intent)
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    /**
     * 检查并请求缺失的权限。
     */
    fun checkAndRequestPermissions() {
        val state = viewModel.uiState.value

        if (state.environmentStatus == TermuxBridge.EnvironmentStatus.NEED_TERMUX) {
            showTermuxGuideDialog()
            return
        }

        if (state.environmentStatus == TermuxBridge.EnvironmentStatus.NEED_TASKER) {
            Toast.makeText(
                this,
                "请从 F-Droid 安装 Termux:Tasker 插件以启用命令执行功能",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // 检查 MANAGE_EXTERNAL_STORAGE
        val permDetails = state.permissionDetails
        if (permDetails["MANAGE_EXTERNAL_STORAGE"] ==
            TermuxBridge.PermissionStatus.NEEDS_SPECIAL_GRANT
        ) {
            requestManageStoragePermission()
            return
        }

        // 检查 Termux 授权
        if (permDetails["TERMUX_AUTH"] ==
            TermuxBridge.PermissionStatus.NEEDS_SPECIAL_GRANT
        ) {
            Toast.makeText(
                this,
                "请在 Termux 中授权 PadCoder 的命令执行请求\n" +
                        "（首次运行时 Termux 会弹出授权对话框）",
                Toast.LENGTH_LONG
            ).show()
            // 发送一次授权探测命令以触发 Termux 的授权弹窗
            lifecycleScope.launch {
                TermuxBridge.checkTermuxAuthorization(this@MainActivity)
            }
            return
        }
    }
}