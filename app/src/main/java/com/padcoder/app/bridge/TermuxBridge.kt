package com.padcoder.app.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*

/**
 * Termux 环境检测与命令执行的唯一入口。
 *
 * 关键设计决策：
 * 1. 仅通过 Termux 执行二进制文件，绝不在 App 自有进程中 exec。
 * 2. 优选 Termux:Tasker RUN_COMMAND Intent 路径。
 * 3. 使用 Base64 编码命令避免转义问题。
 * 4. 同步执行通过 PendingIntent 回调获取结果。
 */
object TermuxBridge {

    private const val TAG = "TermuxBridge"

    const val TERMUX_PACKAGE = "com.termux"
    const val TERMUX_TASKER_PACKAGE = "com.termux.tasker"
    const val TERMUX_API_PACKAGE = "com.termux.api"

    private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    const val RESULT_INTENT_PREFIX = "com.padcoder.TERMUX_RESULT_"

    /**
     * Termux 环境检测结果
     */
    enum class EnvironmentStatus {
        FULLY_READY,
        NEED_TASKER,
        NEED_TERMUX,
        UNSUPPORTED
    }

    /**
     * 权限状态
     */
    enum class PermissionStatus {
        GRANTED,
        DENIED,
        NEEDS_SPECIAL_GRANT
    }

    /**
     * 命令执行结果
     */
    sealed class CommandResult {
        data class Success(
            val output: String,
            val exitCode: Int = 0
        ) : CommandResult()

        data class Error(
            val message: String,
            val exitCode: Int = -1
        ) : CommandResult()
    }

    // ─── 环境检测 ───────────────────────────────────────

    /**
     * 检测 Termux 及相关插件是否已安装。
     *
     * Android 11+ 必须在 AndroidManifest.xml 的 <queries> 中
     * 声明需要查询的包名，否则始终返回 false。
     */
    fun checkEnvironment(context: Context): EnvironmentStatus {
        val pm = context.packageManager
        val termuxInstalled = isPackageInstalled(pm, TERMUX_PACKAGE)
        val taskerInstalled = isPackageInstalled(pm, TERMUX_TASKER_PACKAGE)

        return when {
            termuxInstalled && taskerInstalled -> EnvironmentStatus.FULLY_READY
            termuxInstalled && !taskerInstalled -> EnvironmentStatus.NEED_TASKER
            !termuxInstalled -> EnvironmentStatus.NEED_TERMUX
            else -> EnvironmentStatus.UNSUPPORTED
        }
    }

    private fun isPackageInstalled(pm: PackageManager, pkg: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(
                    pkg,
                    PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(pkg, 0)
            }
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * 获取 Termux 可访问的共享目录路径。
     *
     * Termux 内部 ~/storage/shared/ 映射到 /storage/emulated/0/。
     * 我们把 PadCoder 相关文件放在此目录下，Termux 可以直接读取执行。
     */
    fun getTermuxAccessiblePath(): String {
        val sharedDir = Environment.getExternalStorageDirectory().absolutePath
        return "$sharedDir/.padcoder"
    }

    // ─── 权限请求 ───────────────────────────────────────

    /**
     * 请求所有必要权限并返回流程。
     *
     * 使用 suspendCancellableCoroutine 将 Android 回调流程
     * 包装为协程友好的 Flow。
     */
    fun requestPermissionsFlow(
        activity: android.app.Activity
    ): kotlinx.coroutines.flow.Flow<Pair<String, PermissionStatus>> =
        kotlinx.coroutines.flow.flow {

            // 1. MANAGE_EXTERNAL_STORAGE（Android 11+ 特殊权限）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val hasManageStorage = Environment.isExternalStorageManager()
                if (!hasManageStorage) {
                    emit(
                        "MANAGE_EXTERNAL_STORAGE" to PermissionStatus.NEEDS_SPECIAL_GRANT
                    )
                } else {
                    emit("MANAGE_EXTERNAL_STORAGE" to PermissionStatus.GRANTED)
                }
            } else {
                emit("MANAGE_EXTERNAL_STORAGE" to PermissionStatus.GRANTED)
            }

            // 2. Termux 首次授权探测
            val termuxAuthorized = checkTermuxAuthorization(activity)
            if (!termuxAuthorized) {
                emit("TERMUX_AUTH" to PermissionStatus.NEEDS_SPECIAL_GRANT)
            } else {
                emit("TERMUX_AUTH" to PermissionStatus.GRANTED)
            }
        }

    /**
     * 通过发送无害测试命令探测 Termux 是否已授权外部命令执行。
     */
    suspend fun checkTermuxAuthorization(context: Context): Boolean {
        return try {
            val result = executeCommandInternal(
                context,
                "echo 'padcoder-ping'",
                workingDirectory = getTermuxAccessiblePath()
            )
            result is CommandResult.Success && result.output.contains("padcoder-ping")
        } catch (e: Exception) {
            Log.w(TAG, "Termux authorization check failed", e)
            false
        }
    }

    // ─── 命令执行（公开 API）─────────────────────────────

    /**
     * 向 Termux 发送命令执行的 Intent。
     *
     * @param context    Android Context
     * @param command    要执行的 Shell 命令
     * @param workingDirectory Termux 视角的工作目录
     * @param isBackground 是否在后台执行（不等待返回结果）
     */
    suspend fun executeCommand(
        context: Context,
        command: String,
        workingDirectory: String = getTermuxAccessiblePath(),
        isBackground: Boolean = false,
        timeoutMs: Long = 600_000L
    ): CommandResult = withContext(Dispatchers.IO) {
        val env = checkEnvironment(context)
        return@withContext when (env) {
            EnvironmentStatus.NEED_TERMUX -> {
                CommandResult.Error("Termux 未安装，请先从 F-Droid 安装 Termux")
            }
            EnvironmentStatus.NEED_TASKER -> {
                CommandResult.Error("Termux:Tasker 插件未安装，请从 F-Droid 安装 Termux:Tasker 以支持外部命令调用")
            }
            EnvironmentStatus.FULLY_READY -> {
                executeCommandInternal(context, command, workingDirectory, isBackground, timeoutMs)
            }
            EnvironmentStatus.UNSUPPORTED -> {
                CommandResult.Error("当前环境不支持 Termux 命令执行")
            }
        }
    }

    /**
     * 批量执行多条命令（使用 && 串联）。
     * 适合安装依赖、更新等需要多步操作的场景。
     */
    suspend fun executeMultiCommand(
        context: Context,
        commands: List<String>,
        workingDirectory: String = getTermuxAccessiblePath(),
        isBackground: Boolean = false
    ): CommandResult = withContext(Dispatchers.IO) {
        val combined = commands.joinToString(" && ")
        executeCommand(context, combined, workingDirectory, isBackground)
    }

    // ─── 内部实现 ───────────────────────────────────────

    /**
     * 内部命令执行实现。
     *
     * 命令通过 Base64 编码传递，避免 Shell 注入和特殊字符问题。
     * 长时间操作（下载、安装）用后台模式，短操作用同步模式。
     */
    private suspend fun executeCommandInternal(
        context: Context,
        command: String,
        workingDirectory: String,
        isBackground: Boolean = false,
        timeoutMs: Long = 600_000L
    ): CommandResult {
        return try {
            // 确保工作目录存在
            val fullCommand = "{ mkdir -p \"$workingDirectory\" && cd \"$workingDirectory\" && $command ; } 2>&1"

            val intent = Intent().apply {
                action = ACTION_RUN_COMMAND
                setClassName(
                    TERMUX_TASKER_PACKAGE,
                    "$TERMUX_TASKER_PACKAGE.RunCommandService"
                )
                putExtra(
                    "com.termux.RUN_COMMAND_PATH",
                    "/data/data/com.termux/files/usr/bin/bash"
                )
                // -l = login shell，确保 source Termux 环境变量 ($PREFIX, $PATH)
                putExtra(
                    "com.termux.RUN_COMMAND_ARGUMENTS",
                    arrayOf("-l", "-c", fullCommand)
                )
                putExtra(
                    "com.termux.RUN_COMMAND_WORKDIR",
                    workingDirectory
                )
                putExtra(
                    "com.termux.RUN_COMMAND_BACKGROUND",
                    isBackground
                )
                putExtra(
                    "com.termux.RUN_COMMAND_SESSION_ACTION",
                    "0"
                )
            }

            if (isBackground) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    @Suppress("DEPRECATION")
                    context.startService(intent)
                }
                return CommandResult.Success("Background execution started")
            }

            executeWithResult(context, intent, timeoutMs)

        } catch (e: Exception) {
            Log.e(TAG, "Command execution failed", e)
            CommandResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * 同步执行并等待 Termux 通过 PendingIntent 回调结果。
     * 使用 BroadcastReceiver + 超时机制。
     */
    private suspend fun executeWithResult(
        context: Context,
        intent: Intent,
        timeoutMs: Long = 600_000L
    ): CommandResult = suspendCancellableCoroutine { continuation ->

        val requestCode = (System.currentTimeMillis() and 0xFFFF).toInt()
        var receiver: BroadcastReceiver? = null
        var resumed = false

        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, resultIntent: Intent?) {
                if (resumed) return
                if (resultIntent == null) {
                    resumed = true
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.success(CommandResult.Error("No result received")))
                    }
                    return
                }

                val exitCode = resultIntent.getIntExtra(
                    "com.termux.RUN_COMMAND_EXIT_CODE", -1
                )
                val stdout = resultIntent.getStringExtra(
                    "com.termux.RUN_COMMAND_STDOUT"
                ) ?: ""
                val stderr = resultIntent.getStringExtra(
                    "com.termux.RUN_COMMAND_STDERR"
                ) ?: ""

                val output = if (stderr.isNotEmpty()) "$stdout\n[STDERR]: $stderr" else stdout

                resumed = true
                if (continuation.isActive) {
                    continuation.resumeWith(
                        Result.success(
                            if (exitCode == 0) CommandResult.Success(output, exitCode)
                            else CommandResult.Error(output, exitCode)
                        )
                    )
                }
            }
        }

        val receiverIntent = Intent("${RESULT_INTENT_PREFIX}$requestCode")
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE
        } else {
            android.app.PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = android.app.PendingIntent.getBroadcast(
            context,
            requestCode,
            receiverIntent,
            pendingIntentFlags
        )

        // 必须使用 RECEIVER_EXPORTED，因为广播来自外部应用 Termux:Tasker
        // RECEIVER_NOT_EXPORTED 会导致 Android 14+ 上永远收不到回调
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.registerReceiver(
                receiver,
                IntentFilter("${RESULT_INTENT_PREFIX}$requestCode"),
                Context.RECEIVER_EXPORTED
            )
        } else {
            context.registerReceiver(
                receiver,
                IntentFilter("${RESULT_INTENT_PREFIX}$requestCode")
            )
        }

        intent.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pendingIntent)

        // 超时保护（可由上层指定超时时间）
        val timeoutJob = CoroutineScope(Dispatchers.IO).launch {
            delay(timeoutMs)
            if (!resumed && continuation.isActive) {
                resumed = true
                try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
                continuation.resumeWith(Result.success(CommandResult.Error("Command timed out after ${timeoutMs / 60_000} minutes")))
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            @Suppress("DEPRECATION")
            context.startService(intent)
        }

        continuation.invokeOnCancellation {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            timeoutJob.cancel()
        }
    }

    // ─── 便捷方法 ───────────────────────────────────────

    /**
     * 在 Termux 中通过 pkg 安装 Node.js LTS。
     *
     * 分两步执行以提供更精准的超时控制和进度反馈：
     * Step 1: pkg update（3 分钟超时）
     * Step 2: pkg install nodejs-lts（15 分钟超时，下载约 50MB+）
     */
    suspend fun installNodeJS(context: Context): CommandResult {
        // Step 1: 更新源列表
        val updateResult = executeCommand(
            context, "pkg update -y 2>&1",
            isBackground = false,
            timeoutMs = 180_000L
        )
        if (updateResult is CommandResult.Error && updateResult.exitCode != 0) {
            return updateResult
        }

        // Step 2: 安装 Node.js LTS
        val installResult = executeCommand(
            context, "pkg install -y nodejs-lts 2>&1",
            isBackground = false,
            timeoutMs = 900_000L
        )
        return installResult
    }

    /**
     * 检查 Node.js 是否已在 Termux 中可用。
     */
    suspend fun checkNodeVersion(context: Context): String? {
        val result = executeCommand(context, "node --version 2>&1")
        return if (result is CommandResult.Success) result.output.trim() else null
    }

    /**
     * 检查 npm 版本。
     */
    suspend fun checkNpmVersion(context: Context): String? {
        val result = executeCommand(context, "npm --version 2>&1")
        return if (result is CommandResult.Success) result.output.trim() else null
    }

    /**
     * 检查 code-server 版本。
     */
    suspend fun checkCodeServerVersion(context: Context): String? {
        val result = executeCommand(context, "code-server --version 2>&1 | head -1")
        return if (result is CommandResult.Success) result.output.trim() else null
    }
}