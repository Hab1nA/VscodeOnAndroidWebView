package com.padcoder.app.ui

import com.padcoder.app.bridge.TermuxBridge
import com.padcoder.app.manager.CodeServerManager

/**
 * UI 层统一状态模型。
 *
 * 将权限状态、服务状态、更新状态整合为单一 State，
 * ViewModel 负责驱动状态转换，UI 层仅消费。
 */
data class UiState(
    // ─── 环境状态 ───
    val environmentStatus: TermuxBridge.EnvironmentStatus =
        TermuxBridge.EnvironmentStatus.NEED_TERMUX,

    // ─── 权限状态 ───
    val permissionsReady: Boolean = false,
    val permissionDetails: Map<String, TermuxBridge.PermissionStatus> = emptyMap(),

    // ─── 服务状态 ───
    val serverStatus: CodeServerManager.ServerStatus = CodeServerManager.ServerStatus(),

    // ─── 更新状态 ───
    val updateAvailable: Boolean = false,
    val updateInfo: UpdateInfo? = null,
    val updateProgress: Float = 0f,
    val isUpdating: Boolean = false,

    // ─── 全局标志 ───
    val isLoading: Boolean = false,
    val toastMessage: String? = null,
    val navigationTarget: String? = null
)

/**
 * 版本更新信息
 */
data class UpdateInfo(
    val component: UpdateComponent,
    val currentVersion: String,
    val latestVersion: String,
    val downloadUrl: String,
    val releaseNotes: String = ""
)

/**
 * 可更新的组件
 */
enum class UpdateComponent(val displayName: String) {
    NODE_JS("Node.js"),
    CODE_SERVER("code-server")
}

/**
 * UI 事件（一次性事件，如 Toast、导航）
 */
sealed class UiEvent {
    data class ShowToast(val message: String) : UiEvent()
    data class NavigateTo(val destination: String) : UiEvent()
    object RefreshCompleted : UiEvent()
}