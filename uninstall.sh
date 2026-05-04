#!/data/data/com.termux/files/usr/bin/bash
#===============================================================================
#  uninstall.sh — 安全卸载 code-server 及本项目产生的文件
#  重要: 仅清理安装脚本和 code-server 产生的文件/目录/配置
#        绝不卸载任何系统级依赖包 (如 nodejs, python 等)
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
# 确认操作
#===============================================================================
confirm_uninstall() {
    echo ""
    echo -e "${YELLOW}╔══════════════════════════════════════════════════════════╗${NC}"
    echo -e "${YELLOW}║  警告：此操作将清理 code-server 相关的配置和数据！    ║${NC}"
    echo -e "${YELLOW}║  注意：不会卸载系统级依赖包 (nodejs, python 等)。     ║${NC}"
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

    # 方式2: 杀死所有可能的残留进程
    pkill -f "code-server" 2>/dev/null && log_info "已终止残留的 code-server 进程 ✓" || true
    sleep 1

    log_info "code-server 进程检查完成 ✓"
}

#===============================================================================
# 步骤2: 卸载 code-server 可执行文件 (仅清理安装时部署的部分)
#===============================================================================
step_uninstall_code_server() {
    log_step "正在卸载 code-server (GitHub Release 安装)..."

    # 移除符号链接
    if [ -L "${PREFIX}/bin/code-server" ]; then
        rm -f "${PREFIX}/bin/code-server"
        log_info "已删除符号链接: ${PREFIX}/bin/code-server ✓"
    elif [ -f "${PREFIX}/bin/code-server" ]; then
        rm -f "${PREFIX}/bin/code-server"
        log_info "已删除文件: ${PREFIX}/bin/code-server ✓"
    fi

    # 移除安装目录
    if [ -d "${PREFIX}/opt/code-server" ]; then
        rm -rf "${PREFIX}/opt/code-server"
        log_info "已删除安装目录: ${PREFIX}/opt/code-server ✓"
    fi

    # 清理可能遗留的临时下载文件
    rm -f "${TMPDIR:-/tmp}/code-server-*.tar.gz" 2>/dev/null || true

    log_info "code-server 可执行文件清理完成 ✓"
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
# 步骤4: 清理本项目目录自身
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
        cd "$HOME"  # 先切换到 HOME 再删除
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
    echo -e "${RED}║   仅清理文件, 不动系统包                              ║${NC}"
    echo -e "${RED}╚══════════════════════════════════════════════════════════╝${NC}"
    echo ""

    confirm_uninstall
    step_stop_code_server
    step_uninstall_code_server
    step_clean_config
    step_clean_project

    echo ""
    echo -e "${GREEN}╔══════════════════════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║  卸载完成！                                           ║${NC}"
    echo -e "${GREEN}║  系统级依赖包 (nodejs, python, termux-api 等)        ║${NC}"
    echo -e "${GREEN}║  均已保留，未被删除。                                ║${NC}"
    echo -e "${GREEN}╚══════════════════════════════════════════════════════════╝${NC}"
    echo ""
}

main "$@"