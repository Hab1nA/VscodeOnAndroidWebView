package com.padcoder.app.manager

import android.content.Context
import com.padcoder.app.bridge.TermuxBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * code-server 进程的完整生命周期管理。
 *
 * 状态机：
 *   IDLE → DOWNLOADING → INSTALLED → STARTING → RUNNING
 *     ↑                    ↓            ↓          ↓
 *     └──── STOPPED ←──────┴────────────┴──────────┘
 */
object CodeServerManager {

    const val DEFAULT_PORT = 8080
    private const val INSTANCE_ID = "padcoder-main"

    /**
     * 服务状态枚举
     */
    enum class ServerState {
        IDLE,
        DOWNLOADING,
        INSTALLED,
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED,
        ERROR
    }

    /**
     * 单条日志记录
     */
    data class LogEntry(
        val timestamp: Long = System.currentTimeMillis(),
        val level: String = "INFO",
        val message: String
    )

    /**
     * 服务状态数据类
     */
    data class ServerStatus(
        val state: ServerState = ServerState.IDLE,
        val port: Int = DEFAULT_PORT,
        val nodeVersion: String? = null,
        val codeServerVersion: String? = null,
        val pid: Int? = null,
        val errorMessage: String? = null,
        val downloadProgress: Float = 0f,
        val logLines: List<LogEntry> = emptyList(),
        val currentStep: String = ""
    )

    // ─── 状态管理 ───────────────────────────────────────

    private val _statusFlow = MutableStateFlow(ServerStatus())
    val statusFlow: StateFlow<ServerStatus> = _statusFlow.asStateFlow()

    /**
     * 获取当前状态快照
     */
    fun getCurrentStatus(): ServerStatus = _statusFlow.value

    // ─── 安装与启动（公开 API）─────────────────────────

    /**
     * 一键安装并启动 code-server。
     *
     * 流程：
     * 1. 确保 Node.js 可用
     * 2. 通过 npm 全局安装 code-server（如果未安装）
     * 3. 启动 code-server 并监听端口
     *
     * @return Flow 持续发射状态更新，直到 RUNNING 或 ERROR
     */
    fun installAndStart(context: Context): Flow<ServerStatus> = flow {

        val basePath = TermuxBridge.getTermuxAccessiblePath()
        var logs = _statusFlow.value.logLines

        // ── Step 1: 检查 Node.js ──
        emitStatus(ServerState.STARTING, logs, "正在检测 Node.js 版本...")

        val nodeVersion = TermuxBridge.checkNodeVersion(context)
        if (nodeVersion == null) {
            logs = appendLog(logs, message = "Node.js 未安装，开始安装...")
            emitStatus(ServerState.DOWNLOADING, logs, "Step 1/2: 正在更新 pkg 源列表...", progress = 0.1f)
            // installNodeJS 内部已拆为两步：pkg update (3min) + pkg install nodejs-lts (15min)
            // 这里再写一行日志反映中间状态
            logs = appendLog(logs, message = "正在更新 pkg 源列表 (最长 3 分钟)...")
            emitStatus(ServerState.DOWNLOADING, logs, "Step 1/2: 更新 pkg 源列表中...", progress = 0.2f)

            val installResult = TermuxBridge.installNodeJS(context)
            if (installResult is TermuxBridge.CommandResult.Error) {
                logs = appendLog(logs, "ERROR", "Node.js 安装失败: ${installResult.message}")
                val status = ServerStatus(
                    state = ServerState.ERROR,
                    errorMessage = "Node.js 安装失败: ${installResult.message}",
                    logLines = logs,
                    currentStep = "安装失败"
                )
                _statusFlow.value = status
                emit(status)
                return@flow
            }
            logs = appendLog(logs, message = "Node.js 安装完成")
            emitStatus(ServerState.DOWNLOADING, logs, "Node.js 安装完成", progress = 1.0f)
        } else {
            logs = appendLog(logs, message = "Node.js 已安装: $nodeVersion")
        }

        val actualNodeVersion = TermuxBridge.checkNodeVersion(context)
        logs = appendLog(logs, message = "确认 Node.js 版本: ${actualNodeVersion ?: "未知"}")
        emitStatus(ServerState.STARTING, logs, "Node.js 版本: ${actualNodeVersion ?: "未知"}",
            nodeVersion = actualNodeVersion)

        // ── Step 2: 检查/安装 code-server ──
        val csInstalled = TermuxBridge.checkCodeServerVersion(context) != null
        if (!csInstalled) {
            logs = appendLog(logs, message = "code-server 未安装，开始全局安装...")
            emitStatus(ServerState.DOWNLOADING, logs, "正在安装 code-server (npm install -g)...", progress = 0f)

            val installCsResult = installCodeServer(context)
            if (installCsResult is TermuxBridge.CommandResult.Error) {
                logs = appendLog(logs, "ERROR", "code-server 安装失败: ${installCsResult.message}")
                val status = ServerStatus(
                    state = ServerState.ERROR,
                    errorMessage = "code-server 安装失败: ${installCsResult.message}",
                    logLines = logs,
                    currentStep = "安装失败"
                )
                _statusFlow.value = status
                emit(status)
                return@flow
            }
            logs = appendLog(logs, message = "code-server 安装完成")
            emitStatus(ServerState.DOWNLOADING, logs, "code-server 安装完成", progress = 1.0f)
        } else {
            val currentCsVersion = TermuxBridge.checkCodeServerVersion(context)
            logs = appendLog(logs, message = "code-server 已安装: ${currentCsVersion ?: "未知"}")
        }

        val csVersion = TermuxBridge.checkCodeServerVersion(context)
        logs = appendLog(logs, message = "确认 code-server 版本: ${csVersion ?: "未知"}")
        emitStatus(ServerState.INSTALLED, logs, "环境就绪: Node ${actualNodeVersion}, code-server ${csVersion}",
            downloadProgress = 1.0f, codeServerVersion = csVersion)

        // ── Step 3: 启动 code-server ──
        logs = appendLog(logs, message = "正在启动 code-server (端口 $DEFAULT_PORT)...")
        emitStatus(ServerState.STARTING, logs, "正在启动 code-server...")

        val startResult = startCodeServer(context, basePath)
        if (startResult is TermuxBridge.CommandResult.Error) {
            logs = appendLog(logs, "ERROR", "code-server 启动失败: ${startResult.message}")
            val status = ServerStatus(
                state = ServerState.ERROR,
                errorMessage = "code-server 启动失败: ${startResult.message}",
                logLines = logs,
                currentStep = "启动失败"
            )
            _statusFlow.value = status
            emit(status)
            return@flow
        }
        logs = appendLog(logs, message = "code-server 后台进程已启动, 等待端口就绪...")

        // ── Step 4: 等待端口就绪 ──
        val portReady = waitForPort(context, DEFAULT_PORT, timeoutMs = 30_000L)
        if (!portReady) {
            logs = appendLog(logs, "WARN", "端口 $DEFAULT_PORT 在 30 秒内未就绪，检查进程...")
            // 二次确认：检查进程是否存活
            val isRunning = isServerRunning(context)
            if (!isRunning) {
                logs = appendLog(logs, "ERROR", "code-server 进程未运行，启动失败")
                val status = ServerStatus(
                    state = ServerState.ERROR,
                    errorMessage = "code-server 端口 $DEFAULT_PORT 未在 30 秒内就绪，且进程未运行",
                    logLines = logs,
                    currentStep = "启动失败"
                )
                _statusFlow.value = status
                emit(status)
                return@flow
            } else {
                logs = appendLog(logs, "WARN", "端口检测超时但进程存活，可能仍在初始化中，放行")
            }
        } else {
            logs = appendLog(logs, message = "端口 $DEFAULT_PORT 就绪 ✅")
        }

        // 成功
        val runningStatus = ServerStatus(
            state = ServerState.RUNNING,
            port = DEFAULT_PORT,
            nodeVersion = actualNodeVersion,
            codeServerVersion = csVersion,
            logLines = logs,
            currentStep = "运行中"
        )
        _statusFlow.value = runningStatus
        emit(runningStatus)

    }.flowOn(Dispatchers.IO)

    // ─── 日志辅助方法 ───────────────────────────────────

    private fun appendLog(
        existing: List<LogEntry>,
        level: String = "INFO",
        message: String
    ): List<LogEntry> {
        val entry = LogEntry(level = level, message = message)
        // 只保留最近 200 行，防止内存膨胀
        return (existing + entry).takeLast(200)
    }

    private fun emitStatus(
        state: ServerState,
        logs: List<LogEntry>,
        step: String,
        progress: Float = 0f,
        nodeVersion: String? = null,
        codeServerVersion: String? = null,
        downloadProgress: Float = 0f
    ) {
        val current = _statusFlow.value
        _statusFlow.value = current.copy(
            state = state,
            logLines = logs,
            currentStep = step,
            downloadProgress = if (progress > 0f) progress else current.downloadProgress,
            nodeVersion = nodeVersion ?: current.nodeVersion,
            codeServerVersion = codeServerVersion ?: current.codeServerVersion
        )
    }

    /**
     * 停止 code-server 进程。
     *
     * 策略：
     * 1. 优先使用 kill 发送 SIGTERM（优雅关闭）
     * 2. 如果 2 秒后仍未停止，发送 SIGKILL
     */
    suspend fun stopServer(context: Context) {
        _statusFlow.value = _statusFlow.value.copy(state = ServerState.STOPPING)

        TermuxBridge.executeCommand(
            context = context,
            command = """
                PIDS=${'$'}(pgrep -f "code-server.*${DEFAULT_PORT}" 2>/dev/null)
                if [ -n "${'$'}PIDS" ]; then
                    echo "Found code-server processes: ${'$'}PIDS"
                    kill ${'$'}PIDS 2>/dev/null
                    sleep 2
                    PIDS=${'$'}(pgrep -f "code-server.*${DEFAULT_PORT}" 2>/dev/null)
                    if [ -n "${'$'}PIDS" ]; then
                        kill -9 ${'$'}PIDS 2>/dev/null
                    fi
                    echo "STOPPED"
                else
                    echo "NO_PROCESS_FOUND"
                fi
            """.trimIndent()
        )

        _statusFlow.value = ServerStatus(state = ServerState.STOPPED)
    }

    /**
     * 检查 code-server 进程是否在运行。
     */
    suspend fun isServerRunning(context: Context): Boolean {
        val result = TermuxBridge.executeCommand(
            context = context,
            command = "pgrep -f 'code-server.*$DEFAULT_PORT' > /dev/null 2>&1 && echo 'RUNNING' || echo 'NOT_RUNNING'"
        )
        return result is TermuxBridge.CommandResult.Success &&
                result.output.contains("RUNNING")
    }

    /**
     * 仅启动（不包含安装步骤），适用于 code-server 已安装的场景。
     */
    suspend fun startOnly(context: Context): TermuxBridge.CommandResult {
        val basePath = TermuxBridge.getTermuxAccessiblePath()
        return startCodeServer(context, basePath)
    }

    // ─── 内部方法 ───────────────────────────────────────

    /**
     * 在 Termux 中安装 code-server。
     *
     * 核心命令：npm install -g code-server
     * --unsafe-perm 在某些 Termux 环境中是必需的。
     */
    private suspend fun installCodeServer(context: Context): TermuxBridge.CommandResult {
        val installScript = "npm install -g code-server --unsafe-perm 2>&1 && echo 'INSTALL_DONE'"

        return TermuxBridge.executeCommand(
            context = context,
            command = installScript,
            isBackground = false,
            timeoutMs = 900_000L  // npm install -g 可能需要下载 npm 包，给 15 分钟
        )
    }

    /**
     * 启动 code-server 服务。
     *
     * 关键参数：
     * - --bind-addr 0.0.0.0:PORT  绑定到所有接口
     * - --auth none              关闭密码认证（本地使用）
     * - --disable-telemetry      禁用遥测
     * - --disable-update-check   禁用自动更新检查
     */
    private suspend fun startCodeServer(
        context: Context,
        basePath: String
    ): TermuxBridge.CommandResult {
        val workspacePath = "$basePath/workspace"
        val dataPath = "$basePath/code-server-data"
        val logPath = "$basePath/code-server.log"

        val startScript = """
            mkdir -p "$workspacePath" "$dataPath"
            nohup /data/data/com.termux/files/usr/bin/code-server \
                --bind-addr 0.0.0.0:${DEFAULT_PORT} \
                --auth none \
                --disable-telemetry \
                --disable-update-check \
                --user-data-dir "$dataPath" \
                "$workspacePath" \
                > "$logPath" 2>&1 &
            echo "PID=${'$'}!"
        """.trimIndent()

        return TermuxBridge.executeCommand(
            context = context,
            command = startScript,
            isBackground = false
        )
    }

    /**
     * 等待端口就绪。
     *
     * 通过向 Termux 发送 curl 命令轮询端口状态。
     * 每秒轮询一次，直到超时或就绪。
     */
    private suspend fun waitForPort(
        context: Context,
        port: Int,
        timeoutMs: Long
    ): Boolean {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val result = TermuxBridge.executeCommand(
                context = context,
                command = """
                    if curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:$port 2>/dev/null; then
                        echo "PORT_READY"
                    else
                        echo "PORT_NOT_READY"
                    fi
                """.trimIndent()
            )

            if (result is TermuxBridge.CommandResult.Success &&
                result.output.contains("PORT_READY")
            ) {
                return true
            }

            delay(1000L)
        }

        return false
    }
}