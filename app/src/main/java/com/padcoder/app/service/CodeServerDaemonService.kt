package com.padcoder.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.padcoder.app.PadCoderApplication
import com.padcoder.app.bridge.TermuxBridge
import com.padcoder.app.manager.CodeServerManager
import kotlinx.coroutines.*

/**
 * code-server 守护前台服务。
 *
 * 职责：
 * 1. 绑定前台通知，防止 Android 系统杀死进程。
 * 2. 定期检查 code-server 健康状态。
 * 3. code-server 异常退出时自动重试启动。
 */
class CodeServerDaemonService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.padcoder.action.STOP_SERVICE"

        /** 健康检查间隔（毫秒） */
        private const val HEALTH_CHECK_INTERVAL_MS = 15_000L

        /** 最大重启次数（防止无限重启循环） */
        private const val MAX_RESTART_COUNT = 5
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var restartCount = 0
    private var healthCheckJob: Job? = null

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopDaemon()
                return START_NOT_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification("code-server 守护运行中"))

        // 启动健康检查协程
        startHealthCheck()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopDaemon()
        super.onDestroy()
    }

    // ─── 健康检查循环 ───────────────────────────────────

    /**
     * 定期检查 code-server 是否存活。
     *
     * 如果进程不在了且未超过最大重启次数，
     * 自动重新启动。
     */
    private fun startHealthCheck() {
        healthCheckJob?.cancel()
        healthCheckJob = serviceScope.launch {
            while (isActive) {
                delay(HEALTH_CHECK_INTERVAL_MS)

                try {
                    val isRunning = CodeServerManager.isServerRunning(this@CodeServerDaemonService)

                    if (!isRunning) {
                        handleServerDown()
                    } else {
                        // 正常运行，重置重启计数
                        restartCount = 0
                    }
                } catch (e: Exception) {
                    // 健康检查本身失败，先忽略
                    // （可能 Termux 暂时不可用）
                }
            }
        }
    }

    /**
     * code-server 进程不可达时的处理逻辑。
     */
    private fun handleServerDown() {
        restartCount++

        if (restartCount > MAX_RESTART_COUNT) {
            // 超过最大重启次数，停止守护
            updateNotification("code-server 多次重启失败，守护已暂停")
            stopSelf()
            return
        }

        // 更新通知
        updateNotification(
            "code-server 已断开，正在尝试重启 (第 $restartCount 次)..."
        )

        // 尝试重启
        serviceScope.launch {
            val result = CodeServerManager.startOnly(this@CodeServerDaemonService)

            if (result is TermuxBridge.CommandResult.Error) {
                // 重启失败，等待下次检查周期
                updateNotification(
                    "重启失败: ${result.message} (第 $restartCount 次)"
                )
            } else {
                updateNotification("code-server 已自动恢复 ✅")
            }
        }
    }

    // ─── 通知管理 ───────────────────────────────────────

    /**
     * 构建前台服务通知。
     */
    private fun buildNotification(message: String): Notification {
        val stopIntent = Intent(this, CodeServerDaemonService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(
            this,
            PadCoderApplication.NOTIFICATION_CHANNEL_ID
        )
            .setContentTitle("PadCoder")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_media_pause,
                "停止",
                stopPendingIntent
            )
            .build()
    }

    /**
     * 更新前台通知内容。
     */
    private fun updateNotification(message: String) {
        val notification = buildNotification(message)
        val manager = getSystemService(NOTIFICATION_SERVICE) as
                android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    // ─── 停止守护 ───────────────────────────────────────

    private fun stopDaemon() {
        serviceScope.launch {
            updateNotification("正在停止 code-server...")
            CodeServerManager.stopServer(this@CodeServerDaemonService)
        }
        healthCheckJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}