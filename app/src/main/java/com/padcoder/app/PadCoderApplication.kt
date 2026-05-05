package com.padcoder.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

/**
 * PadCoder Application 类。
 *
 * 初始化全局组件：
 * - 前台服务通知渠道
 * - 全局异常处理
 * - WebView 预热（可选）
 */
class PadCoderApplication : Application() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "padcoder_daemon"
        const val NOTIFICATION_CHANNEL_NAME = "PadCoder 服务守护"

        lateinit var instance: PadCoderApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
    }

    /**
     * 创建前台服务所需的通知渠道。
     * Android 8.0+ 必须。
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "用于维持 code-server 后台运行的通知"
                setShowBadge(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}