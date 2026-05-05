package com.vscodeonandroid.app;

import android.util.Log;

/**
 * Manages background services:
 * - localhost:8000 (Python web UI)
 * - localhost:8080 (code-server)
 *
 * Starts, stops, and checks status of services using TermuxExecutor.
 */
public class ServiceManager {

    private static final String TAG = "ServiceManager";
    private final TermuxExecutor executor;

    private boolean webUiRunning = false;
    private boolean codeServerRunning = false;

    public ServiceManager(TermuxExecutor executor) {
        this.executor = executor;
    }

    /**
     * Start all services: web UI (port 8000) and code-server (port 8080).
     */
    public String startAll() {
        StringBuilder result = new StringBuilder();
        result.append("=== 启动服务 ===\n");

        // Start web UI on port 8000
        result.append(startWebUi()).append("\n");

        // Start code-server on port 8080
        result.append(startCodeServer()).append("\n");

        return result.toString();
    }

    /**
     * Stop all running services.
     */
    public String stopAll() {
        StringBuilder result = new StringBuilder();
        result.append("=== 停止服务 ===\n");

        result.append(stopWebUi()).append("\n");
        result.append(stopCodeServer()).append("\n");

        return result.toString();
    }

    /**
     * Restart all services.
     */
    public String restartAll() {
        stopAll();
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return startAll();
    }

    /**
     * Start the web UI server on port 8000.
     */
    public String startWebUi() {
        if (webUiRunning) {
            return "ℹ️ Web UI 已在运行 (port 8000)";
        }
        // Check if port 8000 is already in use
        String portCheck = executor.execute("netstat -tlnp 2>/dev/null | grep ':8000' || ss -tlnp | grep ':8000' || echo 'not_listening'");
        if (!portCheck.contains("not_listening") && !portCheck.isEmpty()) {
            webUiRunning = true;
            return "✅ Web UI 检测到已在运行 (port 8000)";
        }
        // Start web UI in background
        String startCmd = "cd \"" + executor.getScriptsDir().getAbsolutePath()
                + "\" && nohup python3 server.py > /dev/null 2>&1 & echo 'started'";
        String result = executor.execute(startCmd);
        if (result.contains("started")) {
            webUiRunning = true;
            return "✅ Web UI 已启动 (port 8000)";
        }
        // Try start_web_ui.sh
        String scriptResult = executor.startWebUi();
        webUiRunning = !scriptResult.contains("ERROR");
        return scriptResult;
    }

    /**
     * Stop the web UI server on port 8000.
     */
    public String stopWebUi() {
        String result = executor.execute("fuser -k 8000/tcp 2>/dev/null || lsof -ti:8000 | xargs kill -9 2>/dev/null || echo 'no_process'");
        webUiRunning = false;
        if (result.contains("no_process")) {
            return "ℹ️ Web UI 未在运行";
        }
        return "✅ Web UI 已停止";
    }

    /**
     * Start code-server on port 8080.
     */
    public String startCodeServer() {
        if (codeServerRunning) {
            return "ℹ️ code-server 已在运行 (port 8080)";
        }
        // Check if port 8080 is already in use
        String portCheck = executor.execute("netstat -tlnp 2>/dev/null | grep ':8080' || ss -tlnp | grep ':8080' || echo 'not_listening'");
        if (!portCheck.contains("not_listening") && !portCheck.isEmpty()) {
            codeServerRunning = true;
            return "✅ code-server 检测到已在运行 (port 8080)";
        }
        String result = executor.execute("nohup code-server --bind-addr 0.0.0.0:8080 --auth none > /dev/null 2>&1 & echo 'started'");
        if (result.contains("started")) {
            codeServerRunning = true;
            return "✅ code-server 已启动 (port 8080)";
        }
        return "❌ code-server 启动失败: " + result;
    }

    /**
     * Stop code-server.
     */
    public String stopCodeServer() {
        String result = executor.execute("fuser -k 8080/tcp 2>/dev/null || lsof -ti:8080 | xargs kill -9 2>/dev/null || echo 'no_process'");
        codeServerRunning = false;
        if (result.contains("no_process")) {
            return "ℹ️ code-server 未在运行";
        }
        return "✅ code-server 已停止";
    }

    /**
     * Check current status of all managed services.
     */
    public String checkStatus() {
        StringBuilder status = new StringBuilder();
        status.append("=== 服务运行状态 ===\n\n");

        // Check port 8000
        String webUiCheck = executor.execute("netstat -tlnp 2>/dev/null | grep ':8000' || ss -tlnp | grep ':8000' || echo 'not_running'");
        if (webUiCheck.contains("not_running")) {
            status.append("🌐 Web UI (port 8000): ❌ 未运行\n");
            webUiRunning = false;
        } else {
            status.append("🌐 Web UI (port 8000): ✅ 运行中\n");
            webUiRunning = true;
        }

        // Check port 8080
        String codeServerCheck = executor.execute("netstat -tlnp 2>/dev/null | grep ':8080' || ss -tlnp | grep ':8080' || echo 'not_running'");
        if (codeServerCheck.contains("not_running")) {
            status.append("💻 code-server (port 8080): ❌ 未运行\n");
            codeServerRunning = false;
        } else {
            status.append("💻 code-server (port 8080): ✅ 运行中\n");
            codeServerRunning = true;
        }

        return status.toString();
    }

    public boolean isWebUiRunning() {
        return webUiRunning;
    }

    public boolean isCodeServerRunning() {
        return codeServerRunning;
    }
}