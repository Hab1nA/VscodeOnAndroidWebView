#!/data/data/com.termux/files/usr/bin/python
"""
轻量级 Web 控制台服务：
- 提供 /api/start /api/stop /api/status 接口
- 使用内置 http.server 提供静态页面
"""

from __future__ import annotations

import argparse
import json
import os
import shlex
import shutil
import signal
import subprocess
import sys
import time
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

HOME = os.path.expanduser("~")
STATE_DIR = os.environ.get("CODE_SERVER_STATE_DIR", os.path.join(HOME, ".local", "share", "code-server-web"))
PID_FILE = os.path.join(STATE_DIR, "code-server.pid")
LOG_FILE = os.path.join(STATE_DIR, "code-server.log")
DEFAULT_BIND_ADDR = os.environ.get("CODE_SERVER_BIND_ADDR", "127.0.0.1:8080")
EXTRA_ARGS = os.environ.get("CODE_SERVER_EXTRA_ARGS", "")


def ensure_state_dir() -> None:
    os.makedirs(STATE_DIR, exist_ok=True)


def read_pid() -> int | None:
    try:
        with open(PID_FILE, "r", encoding="utf-8") as handle:
            return int(handle.read().strip())
    except (FileNotFoundError, ValueError):
        return None


def write_pid(pid: int) -> None:
    ensure_state_dir()
    with open(PID_FILE, "w", encoding="utf-8") as handle:
        handle.write(str(pid))


def remove_pid() -> None:
    try:
        os.remove(PID_FILE)
    except FileNotFoundError:
        return


def is_process_running(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def run_termux_command(command: str) -> None:
    if shutil.which(command) is None:
        raise RuntimeError(f"缺少命令：{command}（请先安装 termux-api）")
    subprocess.run([command], check=False)


def start_code_server() -> tuple[bool, str]:
    if shutil.which("code-server") is None:
        return False, "未检测到 code-server，请先运行 install.sh 安装。"

    pid = read_pid()
    if pid and is_process_running(pid):
        return False, f"code-server 已在运行 (PID {pid})。"

    try:
        run_termux_command("termux-wake-lock")
    except RuntimeError as exc:
        return False, str(exc)

    ensure_state_dir()
    cmd = ["code-server", "--bind-addr", DEFAULT_BIND_ADDR]
    if EXTRA_ARGS:
        cmd.extend(shlex.split(EXTRA_ARGS))

    try:
        with open(LOG_FILE, "ab") as log_handle:
            process = subprocess.Popen(
                cmd,
                stdout=log_handle,
                stderr=log_handle,
                preexec_fn=os.setsid,
                close_fds=True,
            )
    except OSError as exc:
        return False, f"启动失败：{exc}"

    write_pid(process.pid)
    return True, f"已启动 (PID {process.pid})，绑定地址 {DEFAULT_BIND_ADDR}"


def stop_code_server() -> tuple[bool, str]:
    pid = read_pid()
    if not pid:
        return False, "未发现运行中的 code-server。"

    if not is_process_running(pid):
        remove_pid()
        return False, "PID 文件已过期，未检测到运行中的进程。"

    try:
        os.killpg(pid, signal.SIGTERM)
    except ProcessLookupError:
        remove_pid()
        return False, "进程已退出。"

    deadline = time.time() + 8
    while time.time() < deadline:
        if not is_process_running(pid):
            remove_pid()
            try:
                run_termux_command("termux-wake-unlock")
            except RuntimeError:
                pass
            return True, "已停止 code-server。"
        time.sleep(0.3)

    try:
        os.killpg(pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    remove_pid()
    return True, "已强制停止 code-server。"


def status_payload() -> dict[str, object]:
    pid = read_pid()
    running = bool(pid and is_process_running(pid))
    if pid and not running:
        remove_pid()
    return {
        "ok": True,
        "running": running,
        "pid": pid if running else None,
        "bind_addr": DEFAULT_BIND_ADDR,
        "log_file": LOG_FILE,
    }


class DashboardHandler(SimpleHTTPRequestHandler):
    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path == "/api/status":
            return self._send_json(status_payload())
        return super().do_GET()

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path == "/api/start":
            ok, message = start_code_server()
            return self._send_json({"ok": ok, "message": message}, status=200 if ok else 409)
        if parsed.path == "/api/stop":
            ok, message = stop_code_server()
            return self._send_json({"ok": ok, "message": message}, status=200 if ok else 409)
        self.send_error(404, "Not Found")

    def _send_json(self, payload: dict[str, object], status: int = 200) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt: str, *args: object) -> None:
        sys.stdout.write("[web-ui] " + fmt % args + "\n")


def build_handler(static_dir: str):
    def handler(*args, **kwargs):
        DashboardHandler(*args, directory=static_dir, **kwargs)

    return handler


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="code-server Web 控制台")
    parser.add_argument("--port", type=int, default=8000, help="Web 控制台端口")
    parser.add_argument("--static-dir", required=True, help="静态页面目录")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not os.path.isdir(args.static_dir):
        print(f"静态目录不存在：{args.static_dir}", file=sys.stderr)
        return 1

    server = ThreadingHTTPServer(("127.0.0.1", args.port), build_handler(args.static_dir))
    print(f"[web-ui] 服务已启动：http://127.0.0.1:{args.port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[web-ui] 已停止")
    return 0


if __name__ == "__main__":
    sys.exit(main())
