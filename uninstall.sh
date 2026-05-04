#!/data/data/com.termux/files/usr/bin/bash
# Termux 卸载/回滚脚本：仅清理本项目与 code-server 的配置与数据目录
# 使用前请赋予执行权限：chmod +x uninstall.sh

set -Eeuo pipefail
IFS=$'\n\t'

log() {
  printf '[uninstall] %s\n' "$*"
}

remove_path() {
  local target="$1"
  if [[ -e "${target}" ]]; then
    rm -rf -- "${target}"
    log "已删除：${target}"
  else
    log "未找到：${target}"
  fi
}

main() {
  log "开始清理 code-server 相关目录（不会卸载 pkg/apt 依赖包）..."

  remove_path "${HOME}/.config/code-server"
  remove_path "${HOME}/.local/share/code-server"
  remove_path "${HOME}/.cache/code-server"
  remove_path "${HOME}/.local/share/code-server-web"

  log "清理完成。"
}

main "$@"
