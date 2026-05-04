#!/data/data/com.termux/files/usr/bin/bash
# 启动本地 Web 控制台服务
# 使用前请赋予执行权限：chmod +x start_web_ui.sh

set -Eeuo pipefail
IFS=$'\n\t'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WEB_UI_PORT="${WEB_UI_PORT:-8000}"

log() {
  printf '[web-ui] %s\n' "$*"
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1
}

main() {
  if ! require_cmd python3; then
    log "未检测到 python3，请先运行 ./install.sh 安装依赖。"
    exit 1
  fi

  log "启动 Web 控制台..."
  log "访问地址：http://127.0.0.1:${WEB_UI_PORT}"
  log "按 Ctrl+C 可停止 Web 控制台。"

  exec python3 "${SCRIPT_DIR}/web_ui/server.py" \
    --port "${WEB_UI_PORT}" \
    --static-dir "${SCRIPT_DIR}/web_ui/static"
}

main "$@"
