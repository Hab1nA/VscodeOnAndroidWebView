#!/data/data/com.termux/files/usr/bin/bash
#===============================================================================
#  install.sh — Termux 环境下通过 pkg 自动安装 code-server
#  适用平台: Android Termux (支持所有 Termux 支持的架构)
#  功能:   加载 tur-repo 仓库源 → 安装依赖 → pkg install code-server → 生成配置 → 保活wake-lock
#===============================================================================

set -euo pipefail  # 严格模式: 遇错退出、未定义变量报错、管道报错

#===============================================================================
# 颜色输出定义
#===============================================================================
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

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
# 步骤1: 更新包管理器
#===============================================================================
step_update_pkg() {
    log_step "正在更新 Termux 包管理器索引..."
    if ! pkg update -y 2>&1 | tail -5; then
        log_warn "pkg update 遇到问题，尝试修复..."
        termux-change-repo 2>/dev/null || true
        pkg update -y || {
            log_error "pkg update 失败，请检查网络连接。"
            exit 1
        }
    fi
    log_info "包管理器索引更新完成 ✓"

    log_step "正在升级已安装的包..."
    pkg upgrade -y 2>&1 | tail -5 || log_warn "部分包升级失败，继续安装过程..."
    log_info "包升级完成 ✓"
}

#===============================================================================
# 步骤2: 加载 tur-repo 仓库源 (包含 code-server 包)
#===============================================================================
step_install_tur_repo() {
    log_step "正在加载 tur-repo 仓库源 (含有 code-server 包)..."

    # 检查是否已安装 x11-repo (tur-repo 的前置依赖)
    if pkg list-installed 2>/dev/null | grep -q "^x11-repo"; then
        log_info "x11-repo 已安装 ✓"
    else
        log_info "正在安装 x11-repo..."
        if pkg install x11-repo -y 2>&1 | tail -5; then
            log_info "x11-repo 安装成功 ✓"
        else
            log_warn "x11-repo 安装失败，尝试继续..."
        fi
    fi

    # 安装 tur-repo (Termux User Repository, 包含社区维护的 code-server 等包)
    if pkg list-installed 2>/dev/null | grep -q "^tur-repo"; then
        log_info "tur-repo 已安装 ✓"
    else
        log_info "正在安装 tur-repo..."
        if pkg install tur-repo -y 2>&1 | tail -5; then
            log_info "tur-repo 安装成功 ✓"
        else
            log_error "tur-repo 安装失败！code-server 位于此仓库中，无法继续安装。"
            log_error "请检查: 1) 网络是否畅通  2) x11-repo 是否已正确配置"
            exit 1
        fi
    fi

    # 刷新包索引以加载 tur-repo 中的新包列表
    log_info "正在刷新包索引以加载 tur-repo 仓库..."
    pkg update -y 2>&1 | tail -5 || {
        log_warn "包索引刷新遇到问题，但可能不影响后续安装。"
    }
    log_info "tur-repo 仓库源加载完成 ✓"
}

#===============================================================================
# 步骤3: 安装基础依赖
#===============================================================================
step_install_deps() {
    log_step "正在安装 code-server 运行所需的基础依赖..."

    # 基础依赖说明:
    # - python:      运行 Web UI 管理控制台后端 (标准库 http.server)
    # - termux-api:  提供 termux-wake-lock 等 Termux API，防止后台被杀
    # 注意: nodejs 不需要单独安装，code-server 包会自动依赖安装所需的 Node.js 版本
    local deps=(python termux-api)

    for dep in "${deps[@]}"; do
        log_info "  安装 ${dep}..."
        if pkg install "${dep}" -y 2>&1 | tail -3; then
            log_info "  ${dep} 安装成功 ✓"
        else
            log_error "  ${dep} 安装失败！"
            exit 1
        fi
    done

    log_info "所有基础依赖安装完成 ✓"
}

#===============================================================================
# 步骤4: 通过 pkg 安装 code-server
#===============================================================================
step_install_code_server() {
    log_step "正在通过 pkg 安装 code-server (Termux 原生包)..."

    # 如果已经安装过 code-server，给出提示
    if command -v code-server &>/dev/null; then
        log_warn "检测到已安装的 code-server: $(command -v code-server)"
        code-server --version 2>&1 | head -3 || true
        log_info "如需更新，请执行: pkg upgrade code-server -y"
        log_info "如需重新安装，请先运行 ./uninstall.sh 清理后再执行本脚本。"
        return 0
    fi

    # 通过 pkg 安装 code-server (Termux 社区包，已适配 Android Bionic libc)
    log_info "正在安装 code-server (这可能需要几分钟，取决于网络速度)..."
    if pkg install code-server -y 2>&1 | tail -10; then
        log_info "code-server 安装成功 ✓"
    else
        log_error "code-server 安装失败！"
        log_error "请检查: 1) tur-repo 是否已正确加载  2) 网络是否畅通"
        log_error "你可以手动尝试: pkg install code-server -y"
        exit 1
    fi

    #---------------------------------------------------------------------
    # 验证安装
    #---------------------------------------------------------------------
    log_step "验证 code-server 安装..."
    if command -v code-server &>/dev/null; then
        local cs_path
        cs_path=$(command -v code-server)
        log_info "code-server 可执行文件路径: ${cs_path}"
        code-server --version 2>&1 | head -3 || true
        log_info "code-server 安装验证通过 ✓"
    else
        log_error "code-server 命令不可用，安装可能失败，请手动检查。"
        log_error "尝试手动运行: code-server --version"
        exit 1
    fi
}

#===============================================================================
# 步骤5: 生成配置文件
#===============================================================================
step_generate_config() {
    log_step "正在生成 code-server 配置文件..."

    local config_dir="$HOME/.config/code-server"
    local config_file="${config_dir}/config.yaml"
    local data_dir="$HOME/.local/share/code-server"

    mkdir -p "${config_dir}" "${data_dir}"

    # 生成随机密码 (16位字母数字)
    local password
    password=$(< /dev/urandom tr -dc 'A-Za-z0-9' | head -c16)

    # 写入配置文件
    cat > "${config_file}" << EOF
# code-server 配置文件
# 由 install.sh 自动生成

bind-addr: 0.0.0.0:8080
auth: password
password: ${password}
cert: false
user-data-dir: ${data_dir}
EOF

    log_info "配置文件已生成: ${config_file}"
    echo ""
    echo -e "${YELLOW}╔══════════════════════════════════════════════════════════╗${NC}"
    echo -e "${YELLOW}║  重要！你的 code-server 登录密码:${NC}"
    echo -e "${GREEN}║  ${password}${NC}"
    echo -e "${YELLOW}║  请妥善保管此密码！你可以在以下文件中找到它:${NC}"
    echo -e "${YELLOW}║  ${config_file}${NC}"
    echo -e "${YELLOW}╚══════════════════════════════════════════════════════════╝${NC}"
    echo ""
}

#===============================================================================
# 步骤6: 保活 - 启用 Wake Lock
#===============================================================================
step_enable_wake_lock() {
    log_step "正在获取 Wake Lock 以防止 Android 系统杀死后台进程..."

    if command -v termux-wake-lock &>/dev/null; then
        termux-wake-lock 2>/dev/null || log_warn "Wake Lock 获取失败，可能影响后台运行稳定性。"
        log_info "Wake Lock 已启用 ✓"
    else
        log_warn "termux-api 未正确安装，Wake Lock 不可用。"
        log_warn "请手动执行: pkg install termux-api -y"
    fi
}

#===============================================================================
# 步骤7: 安装后信息汇总
#===============================================================================
print_summary() {
    local device_ip
    device_ip=$(ip addr show 2>/dev/null | grep -Eo 'inet ([0-9]+\.){3}[0-9]+' | grep -v '127.0.0.1' | awk '{print $2}' | head -1 || echo "未知")

    echo ""
    echo -e "${GREEN}╔══════════════════════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║         安装完成！以下是使用说明:                     ║${NC}"
    echo -e "${GREEN}╠══════════════════════════════════════════════════════════╣${NC}"
    echo -e "${GREEN}║                                                        ║${NC}"
    echo -e "${GREEN}║  1. 启动 Web 管理控制台:                               ║${NC}"
    echo -e "${GREEN}║     ./start_web_ui.sh                                  ║${NC}"
    echo -e "${GREEN}║                                                        ║${NC}"
    echo -e "${GREEN}║  2. 在平板浏览器中打开管理页面:                        ║${NC}"
    echo -e "${GREEN}║     http://${device_ip}:8000                      ║${NC}"
    echo -e "${GREEN}║                                                        ║${NC}"
    echo -e "${GREEN}║  3. 或手动启动 code-server:                            ║${NC}"
    echo -e "${GREEN}║     code-server                                        ║${NC}"
    echo -e "${GREEN}║                                                        ║${NC}"
    echo -e "${GREEN}║  VS Code Server 默认地址:                              ║${NC}"
    echo -e "${GREEN}║  http://${device_ip}:8080                         ║${NC}"
    echo -e "${GREEN}║                                                        ║${NC}"
    echo -e "${GREEN}╚══════════════════════════════════════════════════════════╝${NC}"
    echo ""
}

#===============================================================================
# 主执行流程
#===============================================================================
main() {
    echo ""
    echo -e "${CYAN}╔══════════════════════════════════════════════════════════╗${NC}"
    echo -e "${CYAN}║   Termux code-server 一键安装脚本                      ║${NC}"
    echo -e "${CYAN}║   版本: 2.0.0 (pkg install 模式)                      ║${NC}"
    echo -e "${CYAN}╚══════════════════════════════════════════════════════════╝${NC}"
    echo ""

    check_termux
    step_update_pkg
    step_install_tur_repo
    step_install_deps
    step_install_code_server
    step_generate_config
    step_enable_wake_lock
    print_summary

    log_info "安装流程全部完成！请按照上方说明开始使用。"
}

# 执行主函数
main "$@"