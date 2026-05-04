#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
===============================================================================
  server.py — Termux code-server Web 管理控制台后端
  基于 Python3 标准库 http.server, 零外部依赖
  功能: 启动/停止/状态查询 code-server 进程
  依赖: code-server 需通过 pkg install code-server 安装 (运行 install.sh 完成)
===============================================================================
"""

import http.server
import json
import os
import signal
import socket
import subprocess
import sys
import threading
import time
from http.server import ThreadingHTTPServer

#===============================================================================
# 配置常量
#===============================================================================
HOST = "0.0.0.0"
PORT = 8000
PID_FILE = os.path.expanduser("~/.config/code-server/code-server.pid")
CODE_SERVER_BIN = "code-server"
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
WEB_UI_DIR = SCRIPT_DIR  # index.html 与 server.py 在同一目录

#===============================================================================
# 工具函数
#===============================================================================

def get_local_ip():
    """获取设备的局域网 IP 地址"""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def is_code_server_running():
    """检查 code-server 进程是否正在运行"""
    pid = read_pid()
    if pid is not None:
        try:
            os.kill(pid, 0)  # 信号0仅检查进程是否存在
            return True
        except OSError:
            pass
    return False


def read_pid():
    """从 PID 文件读取进程 ID"""
    try:
        if os.path.exists(PID_FILE):
            with open(PID_FILE, "r") as f:
                return int(f.read().strip())
    except (ValueError, IOError):
        pass
    return None


def write_pid(pid):
    """将进程 ID 写入 PID 文件"""
    pid_dir = os.path.dirname(PID_FILE)
    os.makedirs(pid_dir, exist_ok=True)
    with open(PID_FILE, "w") as f:
        f.write(str(pid))


def remove_pid_file():
    """删除 PID 文件"""
    try:
        if os.path.exists(PID_FILE):
            os.remove(PID_FILE)
    except OSError:
        pass


#===============================================================================
# API 请求处理
#===============================================================================

class APIHandler(http.server.BaseHTTPRequestHandler):
    """HTTP 请求处理器"""

    def log_message(self, format, *args):
        """自定义日志格式"""
        sys.stderr.write("[API] %s - %s\n" % (self.address_string(), format % args))

    def send_json_response(self, data, status=200):
        """发送 JSON 响应"""
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(json.dumps(data, ensure_ascii=False).encode("utf-8"))

    def send_html_response(self, content, status=200):
        """发送 HTML 响应"""
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        self.wfile.write(content.encode("utf-8") if isinstance(content, str) else content)

    def do_GET(self):
        """处理 GET 请求"""
        if self.path == "/" or self.path == "/index.html":
            self.serve_index_html()
        elif self.path == "/api/status":
            self.handle_status()
        else:
            self.send_json_response({"error": "Not Found"}, 404)

    def do_POST(self):
        """处理 POST 请求"""
        if self.path == "/api/start":
            self.handle_start()
        elif self.path == "/api/stop":
            self.handle_stop()
        else:
            self.send_json_response({"error": "Not Found"}, 404)

    def do_OPTIONS(self):
        """处理 CORS 预检请求"""
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    #-----------------------------------------------------------------------
    # 静态页面服务
    #-----------------------------------------------------------------------
    def serve_index_html(self):
        """返回仪表盘前端页面"""
        index_path = os.path.join(WEB_UI_DIR, "index.html")
        try:
            with open(index_path, "r", encoding="utf-8") as f:
                content = f.read()
            self.send_html_response(content)
        except FileNotFoundError:
            self.send_json_response({"error": "index.html not found"}, 500)

    #-----------------------------------------------------------------------
    # API: /api/status
    #-----------------------------------------------------------------------
    def handle_status(self):
        """查询 code-server 状态"""
        running = is_code_server_running()
        pid = read_pid()
        local_ip = get_local_ip()
        result = {
            "running": running,
            "pid": pid,
            "code_server_url": f"http://{local_ip}:8080",
            "web_ui_url": f"http://{local_ip}:{PORT}",
        }
        self.send_json_response(result)

    #-----------------------------------------------------------------------
    # API: /api/start
    #-----------------------------------------------------------------------
    def handle_start(self):
        """启动 code-server"""
        if is_code_server_running():
            self.send_json_response({
                "success": False,
                "message": "code-server 已在运行中。",
                "pid": read_pid(),
            })
            return

        proc = None
        try:
            # 后台启动 code-server
            log_file = os.path.expanduser("~/.config/code-server/code-server.log")
            os.makedirs(os.path.dirname(log_file), exist_ok=True)

            with open(log_file, "a") as log_f:
                proc = subprocess.Popen(
                    [CODE_SERVER_BIN],
                    stdout=log_f,
                    stderr=subprocess.STDOUT,
                    stdin=subprocess.DEVNULL,
                    preexec_fn=os.setsid,  # 创建新的进程会话, 独立于父进程
                    cwd=os.path.expanduser("~"),
                )

            # 写入 PID
            write_pid(proc.pid)

            # 等待 code-server 绑定端口 (最多等待 10 秒)
            port_ready = False
            for i in range(20):
                time.sleep(0.5)
                # 先检查进程是否已退出
                proc.poll()
                if proc.returncode is not None:
                    break
                # 检查端口是否在监听
                try:
                    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                    sock.settimeout(1)
                    if sock.connect_ex(("127.0.0.1", 8080)) == 0:
                        sock.close()
                        port_ready = True
                        break
                    sock.close()
                except Exception:
                    pass

            # 验证结果
            proc.poll()
            if not port_ready:
                # 启动失败: 提取日志尾部供排查
                remove_pid_file()
                err_msg = "code-server 启动失败："
                if proc.returncode is not None:
                    err_msg += f"进程退出，返回码: {proc.returncode}。"
                else:
                    # 进程仍在运行但端口未监听（尝试强制终止）
                    try:
                        pgid = os.getpgid(proc.pid)
                        os.killpg(pgid, signal.SIGTERM)
                    except (ProcessLookupError, OSError, PermissionError):
                        pass
                    err_msg += "端口 8080 在 10 秒内未开始监听。"

                # 读取日志最后 5 行
                log_tail = ""
                try:
                    with open(log_file, "r") as lf:
                        lines = lf.readlines()
                        if lines:
                            log_tail = "".join(lines[-5:])
                except Exception:
                    pass

                if log_tail:
                    err_msg += f"\n日志尾部:\n{log_tail.rstrip()}"

                self.send_json_response({
                    "success": False,
                    "message": err_msg,
                }, 500)
                return

            local_ip = get_local_ip()
            self.send_json_response({
                "success": True,
                "message": "code-server 启动成功！",
                "pid": proc.pid,
                "url": f"http://{local_ip}:8080",
            })

        except FileNotFoundError:
            remove_pid_file()
            self.send_json_response({
                "success": False,
                "message": "未找到 code-server 命令，请先运行 install.sh 安装 (将执行 pkg install code-server)。",
            }, 500)
        except Exception as e:
            remove_pid_file()
            # 清理可能已启动但 PID 未正确记录的残留进程
            try:
                if proc is not None and proc.poll() is None:
                    pgid = os.getpgid(proc.pid)
                    os.killpg(pgid, signal.SIGTERM)
                    time.sleep(0.5)
                    os.killpg(pgid, signal.SIGKILL)
            except (ProcessLookupError, OSError, PermissionError, Exception):
                pass
            self.send_json_response({
                "success": False,
                "message": f"启动失败: {str(e)}",
            }, 500)

    #-----------------------------------------------------------------------
    # API: /api/stop
    #-----------------------------------------------------------------------
    def handle_stop(self):
        """停止 code-server"""
        if not is_code_server_running():
            remove_pid_file()
            self.send_json_response({
                "success": False,
                "message": "code-server 未在运行。",
            })
            return

        pid = read_pid()
        try:
            if pid is not None:
                # 尝试向整个进程组发送 SIGTERM
                os.killpg(os.getpgid(pid), signal.SIGTERM)
                # 等待进程结束
                for _ in range(10):  # 最多等待 5 秒
                    try:
                        os.kill(pid, 0)
                        time.sleep(0.5)
                    except OSError:
                        break
                else:
                    # 进程未响应 SIGTERM, 强制终止
                    os.killpg(os.getpgid(pid), signal.SIGKILL)
        except (ProcessLookupError, OSError):
            pass  # 进程可能已经结束
        finally:
            remove_pid_file()

        # 兜底: 用 pkill 精确匹配清理残留进程
        try:
            subprocess.run(
                ["pkill", "-x", "code-server"],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
        except FileNotFoundError:
            # pkill 不可用时的 fallback: 手动遍历进程
            try:
                import glob
                for _pid_file in glob.glob("/proc/*/cmdline"):
                    try:
                        with open(_pid_file, "rb") as _f:
                            _cmd = _f.read().replace(b"\0", b" ").decode("utf-8", errors="replace")
                            if "code-server" in _cmd:
                                _p = os.path.basename(os.path.dirname(_pid_file))
                                if _p.isdigit():
                                    os.kill(int(_p), signal.SIGKILL)
                    except (OSError, ValueError, ProcessLookupError):
                        pass
            except Exception:
                pass
        except Exception:
            pass

        self.send_json_response({
            "success": True,
            "message": "code-server 已停止。",
        })


#===============================================================================
# 主入口
#===============================================================================

def main():
    """启动 Web 管理控制台 HTTP 服务"""
    local_ip = get_local_ip()

    print("")
    print("╔══════════════════════════════════════════════════════════╗")
    print("║   Termux code-server Web 管理控制台                    ║")
    print("║   Python3 极简后端, 零外部依赖                        ║")
    print("║   安装: pkg install tur-repo && pkg install code-server ║")
    print("╠══════════════════════════════════════════════════════════╣")
    print(f"║   本地访问: http://localhost:{PORT}                 ║")
    if local_ip != "127.0.0.1":
        print(f"║   局域网访问: http://{local_ip}:{PORT}             ║")
    print("╚══════════════════════════════════════════════════════════╝")
    print("")
    print("按 Ctrl+C 停止 Web 管理服务 (不会影响 code-server 运行)")
    print("")

    server = ThreadingHTTPServer((HOST, PORT), APIHandler)

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n正在关闭 Web 管理控制台...")
        server.shutdown()
        print("Web 管理控制台已停止。code-server 进程不受影响。")
        sys.exit(0)


if __name__ == "__main__":
    main()