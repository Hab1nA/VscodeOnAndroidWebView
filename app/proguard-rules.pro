# PadCoder ProGuard Rules
#
# 核心原则：保留所有 Termux IPC 相关类，避免混淆导致 Intent 通信失败

# ── TermuxBridge & IPC ──
-keep class com.padcoder.app.bridge.** { *; }
-keep class com.padcoder.app.manager.** { *; }

# ── ViewModel (避免反射问题) ──
-keep class com.padcoder.app.viewmodel.** { *; }

# ── 数据类（UiState 等） ──
-keep class com.padcoder.app.ui.UiState { *; }
-keep class com.padcoder.app.ui.UpdateInfo { *; }
-keep class com.padcoder.app.ui.UiEvent { *; }
-keep class com.padcoder.app.ui.UpdateComponent { *; }

# ── WebView ──
-keep class com.padcoder.app.webview.** { *; }

# ── 服务 ──
-keep class com.padcoder.app.service.** { *; }

# ── Application ──
-keep class com.padcoder.app.PadCoderApplication { *; }

# ── Kotlin coroutines ──
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ── Material Components ──
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**