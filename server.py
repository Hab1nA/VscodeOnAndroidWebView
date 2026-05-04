#!/usr/bin/env python3
# =============================================================================
# server.py
# VS Code Android 控制面板后端服务
# 基于 Flask 提供 RESTful 接口，管理 code-server 进程
# =============================================================================

import logging
import os
import secrets
import subprocess
import signal
import threading
import time
import socket
from pathlib import Path

from flask import Flask, render_template, jsonify, request, abort

app = Flask(__name__)
logger = logging.getLogger(__name__)

# ---------- 配置常量 ----------
CODE_SERVER_PORT      = 8080        # code-server 监听端口
DASHBOARD_PORT        = 9090        # 控制面板自身监听端口
CODE_SERVER_CMD       = "code-server"  # code-server 可执行文件名
WORKSPACE_DIR         = str(Path.home() / "workspace")  # 默认工作空间目录
PID_FILE              = str(Path.home() / ".code-server.pid")  # PID 文件路径
STARTUP_TIMEOUT_SECS  = 8           # 等待 code-server 端口就绪的超时秒数
STARTUP_POLL_INTERVAL = 0.5         # 端口就绪轮询间隔（秒）

# CSRF 令牌：启动时生成一次，通过渲染的 HTML 页面分发给浏览器。
# 这可以防止跨站点请求伪造——其他网站无法读取本页面中的令牌。
_CSRF_TOKEN = secrets.token_hex(32)

# 防止并发 POST /api/start 同时启动多个 code-server 实例
_start_lock = threading.Lock()


# ---------- 工具函数 ----------

def _is_port_open(port: int) -> bool:
    """检测本地指定端口是否处于监听状态。"""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(0.5)
        return s.connect_ex(("127.0.0.1", port)) == 0


def _read_pid() -> int | None:
    """从 PID 文件读取 code-server 进程 ID。"""
    try:
        with open(PID_FILE, "r") as f:
            return int(f.read().strip())
    except (FileNotFoundError, ValueError):
        return None


def _write_pid(pid: int) -> None:
    """将进程 ID 写入 PID 文件。"""
    with open(PID_FILE, "w") as f:
        f.write(str(pid))


def _remove_pid() -> None:
    """删除 PID 文件。"""
    try:
        os.remove(PID_FILE)
    except FileNotFoundError:
        pass


def _is_code_server_pid(pid: int) -> bool:
    """
    通过读取 /proc/<pid>/cmdline 验证该 PID 确实属于 code-server 进程。
    防止操作系统 PID 复用导致 PID 文件匹配到无关进程。
    """
    try:
        with open(f"/proc/{pid}/cmdline", "rb") as f:
            cmdline = f.read().replace(b"\x00", b" ").decode(errors="replace")
        return CODE_SERVER_CMD in cmdline
    except (FileNotFoundError, PermissionError):
        return False


def get_status() -> dict:
    """
    获取 code-server 当前状态。

    Returns:
        dict: 包含 running (bool) 和 pid (int|None) 的字典。
    """
    pid = _read_pid()
    running = False

    if pid:
        try:
            os.kill(pid, 0)  # 信号 0：仅检测进程是否存在
            if _is_code_server_pid(pid):
                running = True
            else:
                # PID 存在但不是 code-server（PID 被复用），清理陈旧文件
                _remove_pid()
                pid = None
        except (ProcessLookupError, PermissionError):
            _remove_pid()
            pid = None

    return {"running": running, "pid": pid}


# ---------- CSRF 保护 ----------

@app.before_request
def _check_csrf() -> None:
    """
    对所有 POST 请求校验 CSRF 令牌。
    令牌通过渲染的 HTML 页面（meta 标签）传递给浏览器，
    并由 JavaScript 在每次请求时以 X-CSRF-Token 头携带。
    """
    if request.method == "POST":
        token = request.headers.get("X-CSRF-Token", "")
        if not secrets.compare_digest(token, _CSRF_TOKEN):
            abort(403)


# ---------- 路由 ----------

@app.route("/")
def index():
    """渲染控制面板主页，并将 CSRF 令牌嵌入页面。"""
    return render_template("index.html", port=CODE_SERVER_PORT, csrf_token=_CSRF_TOKEN)


@app.route("/api/status")
def api_status():
    """
    GET /api/status
    返回 code-server 运行状态。
    """
    return jsonify(get_status())


@app.route("/api/start", methods=["POST"])
def api_start():
    """
    POST /api/start
    启动 code-server（如果尚未运行）。使用锁防止并发重复启动。
    """
    with _start_lock:
        status = get_status()
        if status["running"]:
            return jsonify({"success": True, "message": "code-server 已经在运行中", "pid": status["pid"]})

        # 确保工作空间目录存在
        os.makedirs(WORKSPACE_DIR, exist_ok=True)

        try:
            proc = subprocess.Popen(
                [
                    CODE_SERVER_CMD,
                    "--bind-addr", f"0.0.0.0:{CODE_SERVER_PORT}",
                    WORKSPACE_DIR,
                ],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                start_new_session=True,  # 脱离父进程，在后台独立运行
            )
            _write_pid(proc.pid)

            # 等待最多 STARTUP_TIMEOUT_SECS 秒，直到端口就绪
            poll_count = int(STARTUP_TIMEOUT_SECS / STARTUP_POLL_INTERVAL)
            for _ in range(poll_count):
                time.sleep(STARTUP_POLL_INTERVAL)
                # 进程提前退出（配置错误、权限问题等）
                if proc.poll() is not None:
                    _remove_pid()
                    return jsonify({
                        "success": False,
                        "message": "code-server 启动失败，请检查配置后重试",
                    }), 500
                if _is_port_open(CODE_SERVER_PORT):
                    return jsonify({
                        "success": True,
                        "message": f"code-server 已启动，端口 {CODE_SERVER_PORT}",
                        "pid": proc.pid,
                    })

            # 超时：再次确认进程仍在运行，否则报告失败
            if proc.poll() is not None:
                _remove_pid()
                return jsonify({
                    "success": False,
                    "message": "code-server 启动失败，请检查配置后重试",
                }), 500

            return jsonify({
                "success": True,
                "message": "code-server 启动中，请稍后刷新页面",
                "pid": proc.pid,
            })

        except FileNotFoundError:
            return jsonify({"success": False, "message": f"未找到 {CODE_SERVER_CMD}，请先运行 install.sh"}), 500
        except Exception:
            logger.exception("启动 code-server 时发生错误")
            return jsonify({"success": False, "message": "启动失败，请查看 Termux 日志获取详情"}), 500


@app.route("/api/stop", methods=["POST"])
def api_stop():
    """
    POST /api/stop
    停止 code-server 进程。
    """
    status = get_status()
    if not status["running"]:
        return jsonify({"success": True, "message": "code-server 当前未运行"})

    pid = status["pid"]

    # 优先通过 PID 文件中的 PID 终止进程
    if pid:
        try:
            os.kill(pid, signal.SIGTERM)
            _remove_pid()
            time.sleep(1)
            return jsonify({"success": True, "message": f"code-server（PID {pid}）已停止"})
        except (ProcessLookupError, PermissionError):
            _remove_pid()
            logger.exception("通过 PID 停止 code-server 时发生错误")
            return jsonify({"success": False, "message": "停止失败，请查看 Termux 日志获取详情"}), 500

    # 兜底：通过 pkill 按进程名终止
    result = subprocess.run(["pkill", "-f", "code-server"], capture_output=True)
    _remove_pid()
    if result.returncode == 0:
        return jsonify({"success": True, "message": "code-server 已通过 pkill 停止"})
    return jsonify({"success": False, "message": "未能找到 code-server 进程"}), 500


# ---------- 主入口 ----------

if __name__ == "__main__":
    print(f"🚀 控制面板启动中，访问 http://localhost:{DASHBOARD_PORT}")
    print(f"   VS Code 地址（启动后）：http://localhost:{CODE_SERVER_PORT}")
    app.run(host="0.0.0.0", port=DASHBOARD_PORT, debug=False)
