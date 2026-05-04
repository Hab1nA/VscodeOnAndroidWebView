#!/data/data/com.termux/files/usr/bin/bash
# Termux 一键安装脚本：安装 code-server 运行所需依赖与本项目所需组件
# 使用前请赋予执行权限：chmod +x install.sh

set -Eeuo pipefail
IFS=$'\n\t'

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STATE_DIR="${HOME}/.local/share/code-server-web"

log() {
  printf '[install] %s\n' "$*"
}

warn() {
  printf '[install][warn] %s\n' "$*" >&2
}

run_with_retry() {
  local -r max_attempts=3
  local -r delay_seconds=2
  local attempt=1
  while true; do
    if "$@"; then
      return 0
    fi
    if (( attempt >= max_attempts )); then
      return 1
    fi
    warn "命令失败，${delay_seconds}s 后重试：$*"
    sleep "${delay_seconds}"
    attempt=$((attempt + 1))
  done
}

ensure_termux() {
  if [[ -z "${PREFIX:-}" || ! -d "${PREFIX}" ]]; then
    warn "未检测到 Termux 环境变量 PREFIX，仍将尝试继续执行。"
  fi
}

main() {
  ensure_termux

  log "更新 Termux 软件源..."
  if ! run_with_retry pkg update -y; then
    warn "pkg update 失败，继续尝试后续步骤。"
  fi

  log "升级基础包..."
  if ! run_with_retry pkg upgrade -y; then
    warn "pkg upgrade 失败，继续尝试后续步骤。"
  fi

  log "安装运行所需依赖：termux-api、python、code-server..."
  if ! run_with_retry pkg install -y termux-api python code-server; then
    warn "依赖安装失败，请检查网络或镜像源后重试。"
    exit 1
  fi

  log "准备运行时目录..."
  mkdir -p "${STATE_DIR}"

  log "安装完成。"
  log "项目目录：${PROJECT_DIR}"
  log "请运行：chmod +x start_web_ui.sh uninstall.sh"
}

main "$@"
