package com.padcoder.app.viewmodel

import android.app.Application
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.padcoder.app.bridge.TermuxBridge
import com.padcoder.app.manager.CodeServerManager
import com.padcoder.app.ui.UiEvent
import com.padcoder.app.ui.UiState
import com.padcoder.app.ui.UpdateComponent
import com.padcoder.app.ui.UpdateInfo
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * 全局 ViewModel，管理整个应用的生命周期状态。
 *
 * 职责：
 * 1. 环境检测与权限管理
 * 2. code-server 安装/启动/停止
 * 3. 版本更新检查与执行
 * 4. 清理卸载
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>()
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    init {
        // 订阅 CodeServerManager 的状态流
        viewModelScope.launch {
            CodeServerManager.statusFlow.collect { serverStatus ->
                _uiState.update { state ->
                    state.copy(serverStatus = serverStatus)
                }
            }
        }
    }

    // ─── 环境检测 ───────────────────────────────────────

    /**
     * 初始化检测：检查 Termux 环境和权限状态。
     * 应在 Activity onCreate 时调用。
     */
    fun initialize() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, toastMessage = null) }

            val context = getApplication<Application>()

            // 步骤 1：检查 Termux 是否安装
            val envStatus = TermuxBridge.checkEnvironment(context)
            _uiState.update { it.copy(environmentStatus = envStatus) }

            if (envStatus == TermuxBridge.EnvironmentStatus.NEED_TERMUX) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        toastMessage = "未检测到 Termux，请先安装 Termux 和 Termux:Tasker"
                    )
                }
                return@launch
            }

            // 步骤 2：检查权限
            checkPermissions()
        }
    }

    /**
     * 检查关键权限。
     */
    fun checkPermissions() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val details = mutableMapOf<String, TermuxBridge.PermissionStatus>()

            // MANAGE_EXTERNAL_STORAGE（Android 11+）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val granted = Environment.isExternalStorageManager()
                details["MANAGE_EXTERNAL_STORAGE"] = if (granted) {
                    TermuxBridge.PermissionStatus.GRANTED
                } else {
                    TermuxBridge.PermissionStatus.NEEDS_SPECIAL_GRANT
                }
            } else {
                details["MANAGE_EXTERNAL_STORAGE"] = TermuxBridge.PermissionStatus.GRANTED
            }

            // Termux 授权检查
            val termuxAuthorized = TermuxBridge.checkTermuxAuthorization(
                getApplication()
            )
            details["TERMUX_AUTH"] = if (termuxAuthorized) {
                TermuxBridge.PermissionStatus.GRANTED
            } else {
                TermuxBridge.PermissionStatus.NEEDS_SPECIAL_GRANT
            }

            val allReady = details.values.all {
                it == TermuxBridge.PermissionStatus.GRANTED
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    permissionsReady = allReady,
                    permissionDetails = details
                )
            }

            if (!allReady) {
                _events.emit(
                    UiEvent.ShowToast("需要授予必要权限才能继续")
                )
            }
        }
    }

    /**
     * 获取 MANAGE_EXTERNAL_STORAGE 权限的 Intent。
     */
    fun getManageStorageIntent(): android.content.Intent {
        return android.content.Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            android.net.Uri.parse("package:${getApplication<Application>().packageName}")
        )
    }

    // ─── code-server 管理 ───────────────────────────────

    /**
     * 一键安装并启动 code-server。
     */
    fun installAndStartCodeServer() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, toastMessage = null) }

            val context = getApplication<Application>()

            CodeServerManager.installAndStart(context).collect { status ->
                _uiState.update { it.copy(serverStatus = status) }

                when (status.state) {
                    CodeServerManager.ServerState.RUNNING -> {
                        _events.emit(UiEvent.ShowToast("code-server 已启动"))
                        _events.emit(
                            UiEvent.NavigateTo("webview:http://127.0.0.1:${status.port}")
                        )
                    }
                    CodeServerManager.ServerState.ERROR -> {
                        _events.emit(
                            UiEvent.ShowToast(status.errorMessage ?: "启动失败")
                        )
                    }
                    else -> { /* 中间状态不需额外通知 */ }
                }
            }

            _uiState.update { it.copy(isLoading = false) }
        }
    }

    /**
     * 停止 code-server。
     */
    fun stopCodeServer() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            CodeServerManager.stopServer(getApplication())

            _uiState.update { it.copy(isLoading = false) }
            _events.emit(UiEvent.ShowToast("code-server 已停止"))
        }
    }

    /**
     * 仅启动（不安装），适用于 code-server 已安装的场景。
     */
    fun startOnly() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val context = getApplication<Application>()
            val result = CodeServerManager.startOnly(context)

            _uiState.update { it.copy(isLoading = false) }

            if (result is TermuxBridge.CommandResult.Success) {
                _events.emit(UiEvent.ShowToast("code-server 启动中..."))
            } else {
                _events.emit(UiEvent.ShowToast("启动失败"))
            }
        }
    }

    // ─── 版本检查与更新 ─────────────────────────────────

    /**
     * 检查 Node.js 和 code-server 的版本更新。
     *
     * 通过 npm view 和 curl GitHub API 获取最新版本信息。
     */
    fun checkForUpdates() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, updateInfo = null, updateAvailable = false) }

            val context = getApplication<Application>()

            // 检查 Node.js
            val currentNodeVersion = TermuxBridge.checkNodeVersion(context)

            // 通过 npm view 获取 Node.js latest version（在 Termux 环境中）
            val nodeUpdateResult = TermuxBridge.executeCommand(
                context = context,
                command = "npm view node version 2>&1"
            )

            // 检查 code-server
            val currentCsVersion = TermuxBridge.checkCodeServerVersion(context)

            // 通过 npm 获取 code-server 最新版本
            val csUpdateResult = TermuxBridge.executeCommand(
                context = context,
                command = "npm view code-server version 2>&1"
            )

            val updates = mutableListOf<UpdateInfo>()

            // 比对 Node.js 版本
            if (nodeUpdateResult is TermuxBridge.CommandResult.Success) {
                val latestNode = nodeUpdateResult.output.trim()
                if (currentNodeVersion != null && currentNodeVersion.trim() != latestNode) {
                    updates.add(
                        UpdateInfo(
                            component = UpdateComponent.NODE_JS,
                            currentVersion = currentNodeVersion.trim(),
                            latestVersion = latestNode,
                            downloadUrl = "",
                            releaseNotes = "新版本 $latestNode 可用"
                        )
                    )
                }
            }

            // 比对 code-server 版本
            if (csUpdateResult is TermuxBridge.CommandResult.Success) {
                val latestCs = csUpdateResult.output.trim()
                val currentCs = currentCsVersion?.trim() ?: ""
                if (currentCs.isNotEmpty() && currentCs != latestCs) {
                    updates.add(
                        UpdateInfo(
                            component = UpdateComponent.CODE_SERVER,
                            currentVersion = currentCs,
                            latestVersion = latestCs,
                            downloadUrl = "",
                            releaseNotes = "新版本 $latestCs 可用"
                        )
                    )
                }
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    updateAvailable = updates.isNotEmpty(),
                    updateInfo = updates.firstOrNull()
                )
            }

            if (updates.isEmpty()) {
                _events.emit(UiEvent.ShowToast("所有组件均为最新版本 ✅"))
            } else {
                val components = updates.joinToString(", ") { it.component.displayName }
                _events.emit(UiEvent.ShowToast("发现 $components 可更新"))
            }
        }
    }

    /**
     * 执行更新。
     *
     * 步骤：
     * 1. 停止正在运行的 code-server
     * 2. 更新 npm 全局包（code-server）或通过 pkg 更新 Node.js
     * 3. 刷新版本信息
     */
    fun performUpdate(component: UpdateComponent) {
        viewModelScope.launch {
            _uiState.update { it.copy(isUpdating = true, updateProgress = 0f) }

            val context = getApplication<Application>()

            // 先停止服务
            if (CodeServerManager.getCurrentStatus().state ==
                CodeServerManager.ServerState.RUNNING
            ) {
                CodeServerManager.stopServer(context)
                _uiState.update { it.copy(updateProgress = 0.1f) }
            }

            val updateScript = when (component) {
                UpdateComponent.NODE_JS -> """
                    pkg update -y 2>&1
                    pkg upgrade -y nodejs-lts 2>&1
                    node --version
                """.trimIndent()

                UpdateComponent.CODE_SERVER -> """
                    npm update -g code-server --unsafe-perm 2>&1
                    code-server --version 2>&1 | head -1
                """.trimIndent()
            }

            _uiState.update { it.copy(updateProgress = 0.3f) }

            val result = TermuxBridge.executeCommand(
                context = context,
                command = updateScript,
                isBackground = false
            )

            _uiState.update { it.copy(updateProgress = 1.0f) }

            when (result) {
                is TermuxBridge.CommandResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isUpdating = false,
                            updateAvailable = false,
                            updateInfo = null
                        )
                    }
                    _events.emit(UiEvent.ShowToast("${component.displayName} 更新成功"))
                    _events.emit(UiEvent.RefreshCompleted)
                }
                is TermuxBridge.CommandResult.Error -> {
                    _uiState.update { it.copy(isUpdating = false) }
                    _events.emit(
                        UiEvent.ShowToast("${component.displayName} 更新失败: ${result.message}")
                    )
                }
            }
        }
    }

    // ─── 诊断 ───────────────────────────────────────────

    /**
     * 全面的系统诊断。
     *
     * 检查项：
     * - Termux 环境状态
     * - Node.js 可用性及版本
     * - npm 可用性
     * - code-server 可用性及版本
     * - 端口占用情况
     */
    fun runDiagnostics(): Flow<String> = flow {
        val context = getApplication<Application>()

        emit("══════ PadCoder 诊断报告 ══════\n")

        // 1. Termux
        emit("[1/6] Termux 环境检查...")
        val env = TermuxBridge.checkEnvironment(context)
        emit("      Termux 状态: $env")

        // 2. Node.js
        emit("[2/6] Node.js 检查...")
        val nodeVersion = TermuxBridge.checkNodeVersion(context)
        emit("      Node.js 版本: ${nodeVersion ?: "未安装"}")

        // 3. npm
        emit("[3/6] npm 检查...")
        val npmVersion = TermuxBridge.checkNpmVersion(context)
        emit("      npm 版本: ${npmVersion ?: "未安装"}")

        // 4. code-server
        emit("[4/6] code-server 检查...")
        val csVersion = TermuxBridge.checkCodeServerVersion(context)
        emit("      code-server 版本: ${csVersion ?: "未安装"}")

        // 5. 端口占用
        emit("[5/6] 端口检查...")
        val portResult = TermuxBridge.executeCommand(
            context = context,
            command = """
                if command -v ss >/dev/null 2>&1; then
                    ss -tlnp 2>/dev/null | grep ':${CodeServerManager.DEFAULT_PORT}' || echo "端口 ${CodeServerManager.DEFAULT_PORT} 未被占用"
                elif command -v netstat >/dev/null 2>&1; then
                    netstat -tlnp 2>/dev/null | grep ':${CodeServerManager.DEFAULT_PORT}' || echo "端口 ${CodeServerManager.DEFAULT_PORT} 未被占用"
                else
                    echo "无法检测端口占用（缺少 ss/netstat）"
                fi
            """.trimIndent()
        )
        val portInfo = if (portResult is TermuxBridge.CommandResult.Success) {
            portResult.output.trim()
        } else {
            "端口检查失败"
        }
        emit("      $portInfo")

        // 6. 进程状态
        emit("[6/6] code-server 进程检查...")
        val isRunning = CodeServerManager.isServerRunning(context)
        emit("      code-server 运行中: $isRunning")

        emit("\n══════ 诊断完成 ══════")
    }

    /**
     * 运行诊断并显示 Toast 结果（UI 集成入口）。
     */
    fun runDiagnosticsAndReport() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val report = StringBuilder()
            runDiagnostics().collect { line ->
                report.appendLine(line)
            }

            _uiState.update { it.copy(isLoading = false) }
            _events.emit(UiEvent.ShowToast("诊断完成，详细信息已输出"))
            // 完整报告可供 UI 展示
            _events.emit(UiEvent.NavigateTo("diagnostics-report"))
        }
    }

    // ─── 清理与卸载 ─────────────────────────────────────

    /**
     * 一键清理：停止服务，清除所有生成文件。
     */
    fun uninstall() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val context = getApplication<Application>()

            // Step 1: 停止 code-server
            if (CodeServerManager.getCurrentStatus().state ==
                CodeServerManager.ServerState.RUNNING
            ) {
                CodeServerManager.stopServer(context)
            }

            // Step 2: 清除所有 PadCoder 相关文件
            val basePath = TermuxBridge.getTermuxAccessiblePath()
            val uninstallScript = """
                echo "正在清理 PadCoder 文件..."
                rm -rf "$basePath"
                echo "CLEANUP_DONE"
            """.trimIndent()

            val result = TermuxBridge.executeCommand(
                context = context,
                command = uninstallScript,
                isBackground = false
            )

            _uiState.update {
                UiState(
                    environmentStatus = it.environmentStatus
                )
            }

            _uiState.update { it.copy(isLoading = false) }

            when (result) {
                is TermuxBridge.CommandResult.Success -> {
                    _events.emit(UiEvent.ShowToast("清理完成，所有文件已删除"))
                }
                is TermuxBridge.CommandResult.Error -> {
                    _events.emit(UiEvent.ShowToast("清理过程中出现错误: ${result.message}"))
                }
            }
        }
    }

    /**
     * 清除一次性 Toast 消息。
     */
    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    /**
     * 清除导航目标。
     */
    fun clearNavigation() {
        _uiState.update { it.copy(navigationTarget = null) }
    }
}