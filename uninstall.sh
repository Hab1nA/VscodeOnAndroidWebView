#!/data/data/com.termux/files/usr/bin/bash
#===============================================================================
#  uninstall.sh — 安全卸载 code-server 及本项目产生的文件
#  重要: 仅清理 pkg 安装的 code-server 包和脚本产生的文件/目录/配置
#        绝不卸载任何系统级依赖包 (如 python 等)
#===============================================================================

set -euo pipefail

#===============================================================================
# 颜色输出定义
#===============================================================================
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

log_info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*"; }
log_step()  { echo -e "${CYAN}==>${NC} $*"; }

#===============================================================================
# 环境检测
#===============================================================================
check_termux() {
    if [ -z "${TERMUX_VERSION:-}" ] && [ ! -d "/data/data/com.termux/files/usr" ]; then
        log_error "此脚本必须在 Termux 环境中运行！"
        exit 1
    fi
    log_info "Termux 环境检测通过 ✓"
}

#===============================================================================
# 确认操作
#===============================================================================
confirm_uninstall() {
    echo ""
    echo -e "${YELLOW}╔══════════════════════════════════════════════════════════╗${NC}"
    echo -e "${YELLOW}║  警告：此操作将卸载 code-server 并清理相关配置和数据！║${NC}"
    echo -e "${YELLOW}║  注意：不会卸载系统级依赖包 (python 等)。             ║${NC}"
    echo -e "${YELLOW}╚══════════════════════════════════════════════════════════╝${NC}"
    echo ""
    read -r -p "确认卸载？请输入 yes 继续: " confirm
    if [ "${confirm}" != "yes" ]; then
        log_info "卸载已取消。"
        exit 0
    fi
}

#===============================================================================
# 步骤1: 停止正在运行的 code-server 进程
#===============================================================================
step_stop_code_server() {
    log_step "正在检查并停止 code-server 进程..."

    # 方式1: 通过 PID 文件终止
    local pid_file="$HOME/.config/code-server/code-server.pid"
    if [ -f "${pid_file}" ]; then
        local pid
        pid=$(cat "${pid_file}" 2>/dev/null || true)
        if [ -n "${pid}" ] && kill -0 "${pid}" 2>/dev/null; then
            kill "${pid}" 2>/dev/null && log_info "已终止 code-server 进程 (PID: ${pid}) ✓" || true
            sleep 1
            # 如果还没死, 强制终止
            kill -9 "${pid}" 2>/dev/null || true
        fi
        rm -f "${pid_file}"
    fi

    # 方式2: 精确匹配进程名，清理可能的残留进程
    pkill -x "code-server" 2>/dev/null && log_info "已终止残留的 code-server 进程 ✓" || true
    sleep 1

    log_info "code-server 进程检查完成 ✓"
}

#===============================================================================
# 步骤2: 通过 pkg 卸载 code-server 包
#===============================================================================
step_uninstall_code_server() {
    log_step "正在通过 pkg 卸载 code-server..."

    # 检查 code-server 是否通过 pkg 安装
    if pkg list-installed 2>/dev/null | grep -q "^code-server"; then
        log_info "正在卸载 code-server 包..."
        if pkg uninstall code-server -y 2>&1 | tail -10; then
            log_info "code-server 包卸载成功 ✓"
        else
            log_warn "code-server 卸载遇到问题，尝试强制清除..."
            pkg uninstall code-server -y 2>&1 || true
        fi
    else
        log_info "code-server 包未通过 pkg 安装或已卸载。"
    fi

    # 清理可能残留的旧版符号链接或安装目录（兼容旧版 GitHub Release 安装方式）
    if [ -L "${PREFIX}/bin/code-server" ] || [ -f "${PREFIX}/bin/code-server" ]; then
        rm -f "${PREFIX}/bin/code-server"
        log_info "已清理残留的 code-server 符号链接/文件 ✓"
    fi

    if [ -d "${PREFIX}/opt/code-server" ]; then
        rm -rf "${PREFIX}/opt/code-server"
        log_info "已清理残留的旧版安装目录: ${PREFIX}/opt/code-server ✓"
    fi

    # 清理可能遗留的临时下载文件
    rm -f "${TMPDIR:-/tmp}/code-server-*.tar.gz" 2>/dev/null || true

    log_info "code-server 卸载流程完成 ✓"
}

#===============================================================================
# 步骤3: 清理 code-server 配置和数据目录
#===============================================================================
step_clean_config() {
    log_step "正在清理 code-server 配置文件和数据..."

    local dirs_to_clean=(
        "$HOME/.config/code-server"
        "$HOME/.local/share/code-server"
        "$HOME/.cache/code-server"
    )

    for dir in "${dirs_to_clean[@]}"; do
        if [ -d "${dir}" ]; then
            rm -rf "${dir}"
            log_info "已删除目录: ${dir} ✓"
        fi
    done

    log_info "配置文件清理完成 ✓"
}

#===============================================================================
# 步骤4: 清理 tur-repo 仓库源 (可选, 仅当用户确认)
#===============================================================================
step_clean_tur_repo() {
    log_step "检查 tur-repo 仓库源..."

    if pkg list-installed 2>/dev/null | grep -q "^tur-repo"; then
        log_warn "tur-repo 仓库源仍处于安装状态。"
        log_warn "如果你不再需要 Termux 用户仓库中的其他包，可以手动卸载:"
        log_warn "  pkg uninstall tur-repo -y"
        log_info "跳过自动卸载 tur-repo（可能被其他包依赖）。"
    fi
}

#===============================================================================
# 步骤5: 清理本项目目录自身
#===============================================================================
step_clean_project() {
    log_step "正在清理项目目录..."

    # 获取当前脚本所在目录 (即项目目录)
    local project_dir
    project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

    log_warn "即将删除项目目录: ${project_dir}"
    log_warn "请确保你已经备份了需要保留的任何自定义文件。"

    read -r -p "确认删除项目目录？(yes/no) [no]: " confirm_dir
    if [ "${confirm_dir}" = "yes" ]; then
        cd "$HOME" 2>/dev/null || cd / || true  # 先切换到安全目录再删除
        rm -rf "${project_dir}"
        log_info "项目目录已删除 ✓"
    else
        log_info "跳过删除项目目录。"
    fi
}

#===============================================================================
# 主执行流程
#===============================================================================
main() {
    echo ""
    echo -e "${RED}╔══════════════════════════════════════════════════════════╗${NC}"
    echo -e "${RED}║   Termux code-server 安全卸载脚本                      ║${NC}"
    echo -e "${RED}║   仅卸载 code-server 及清理文件, 不动系统包           ║${NC}"
    echo -e "${RED}╚══════════════════════════════════════════════════════════╝${NC}"
    echo ""

    check_termux
    confirm_uninstall
    step_stop_code_server
    step_uninstall_code_server
    step_clean_config
    step_clean_tur_repo
    step_clean_project

    echo ""
    echo -e "${GREEN}╔══════════════════════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║  卸载完成！                                           ║${NC}"
    echo -e "${GREEN}║  系统级依赖包 (python, termux-api 等)                 ║${NC}"
    echo -e "${GREEN}║  均已保留，未被删除。                                ║${NC}"
    echo -e "${GREEN}║  如需清理 tur-repo 仓库源，请手动执行:               ║${NC}"
    echo -e "${GREEN}║    pkg uninstall tur-repo -y                          ║${NC}"
    echo -e "${GREEN}╚══════════════════════════════════════════════════════════╝${NC}"
    echo ""
}

main "$@"