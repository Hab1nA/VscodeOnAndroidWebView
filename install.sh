#!/data/data/com.termux/files/usr/bin/bash
#===============================================================================
#  install.sh — Termux 环境下自动安装 code-server
#  适用平台: Android Termux (ARM64 / ARM / x86_64)
#  功能:   安装依赖 → 部署 code-server → 生成配置 → 保活wake-lock
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
# 步骤2: 安装基础依赖 (仅维持 code-server 运行的底层包)
#===============================================================================
step_install_deps() {
    log_step "正在安装 code-server 运行所需的基础依赖..."

    # 基础依赖说明:
    # - python:      运行 Web UI 管理控制台后端 (标准库 http.server)
    # - termux-api:  提供 termux-wake-lock 等 Termux API
    # - wget:        下载 code-server Release 包
    # - tar:         解压 .tar.gz
    # - curl:        查询 GitHub API 获取最新版本号
    local deps=(python termux-api wget tar curl)

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
# 步骤3: 部署 code-server (从 GitHub Release 下载预编译包)
#===============================================================================
step_install_code_server() {
    log_step "正在安装 code-server (GitHub Release 预编译包)..."

    # 如果已经安装过, 询问是否覆盖
    if command -v code-server &>/dev/null; then
        log_warn "检测到已安装的 code-server: $(command -v code-server)"
        log_warn "如需重新安装，请先运行 ./uninstall.sh 清理后再执行本脚本。"
        log_info "跳过安装步骤。"
        code-server --version 2>&1 | head -3 || true
        return 0
    fi

    # 确保 PREFIX 已设置 (部分非 Termux bash 环境下可能缺失)
    local PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"

    #---------------------------------------------------------------------
    # 3.1: 检测 CPU 架构
    #---------------------------------------------------------------------
    local arch
    arch=$(uname -m)
    local release_arch=""
    case "${arch}" in
        aarch64)
            release_arch="arm64"
            ;;
        armv7l|armv8l)
            # 部分 32 位 ARM 设备可能需要 arm 包; 尝试识别
            release_arch="arm64"
            log_warn "检测到 32 位 ARM (${arch}), 将尝试 arm64 包。"
            log_warn "如果启动失败，请手动确认你的设备架构。"
            ;;
        x86_64)
            release_arch="amd64"
            ;;
        *)
            log_error "不支持的 CPU 架构: ${arch}"
            log_error "支持: aarch64 (arm64), x86_64 (amd64)"
            exit 1
            ;;
    esac
    log_info "CPU 架构: ${arch} → Release 架构: ${release_arch}"

    #---------------------------------------------------------------------
    # 3.2: 获取 GitHub 最新版本号
    #---------------------------------------------------------------------
    log_info "正在查询 GitHub 最新 Release 版本..."
    local latest_version
    latest_version=$(curl -sSL "https://api.github.com/repos/coder/code-server/releases/latest" 2>/dev/null \
        | grep '"tag_name"' \
        | head -1 \
        | sed -E 's/.*"tag_name": *"([^"]+)".*/\1/')

    if [ -z "${latest_version}" ]; then
        log_warn "无法通过 GitHub API 获取版本号，使用已知稳定版本 v4.117.0"
        latest_version="v4.117.0"
    fi
    log_info "最新版本: ${latest_version}"

    # 去掉前缀 'v' (如果存在) 用于构造下载 URL 中的文件名
    # GitHub Release 文件名格式: code-server-4.117.0-linux-arm64.tar.gz
    local version_no_v="${latest_version#v}"

    #---------------------------------------------------------------------
    # 3.3: 下载预编译包
    #---------------------------------------------------------------------
    local download_url="https://github.com/coder/code-server/releases/download/${latest_version}/code-server-${version_no_v}-linux-${release_arch}.tar.gz"
    local tmp_file="${TMPDIR:-/tmp}/code-server-${version_no_v}-linux-${release_arch}.tar.gz"
    local install_dir="${PREFIX}/opt/code-server"

    log_info "下载地址: ${download_url}"
    log_info "正在下载 (这可能需要几分钟, 取决于网络速度)..."

    # 分离执行与判断, 避免 2>&1 吞噬错误信息
    wget --show-progress -O "${tmp_file}" "${download_url}"
    local wget_exit=$?
    if [ ${wget_exit} -eq 0 ]; then
        log_info "下载完成 ✓"
    else
        log_error "下载失败! wget 退出码: ${wget_exit}"
        log_error "URL: ${download_url}"
        log_error "请检查: 1) 网络是否畅通  2) GitHub 是否可达"
        log_error "你可以手动下载后解压到 ${install_dir}/ 并创建符号链接:"
        log_error "  wget ${download_url}"
        log_error "  tar -xzf code-server-*.tar.gz -C ${install_dir} --strip-components=1"
        exit 1
    fi

    #---------------------------------------------------------------------
    # 3.4: 解压并安装
    #---------------------------------------------------------------------
    log_info "正在解压到 ${install_dir}..."

    # 清理可能存在的旧安装
    rm -rf "${install_dir}" 2>/dev/null || true
    mkdir -p "${install_dir}"

    # 解压 tarball (里面包含一个 code-server-*-linux-arm64/ 目录)
    if tar -xzf "${tmp_file}" -C "${install_dir}" --strip-components=1; then
        log_info "解压完成 ✓"
    else
        log_error "解压失败! tar 可能不支持该格式。"
        rm -f "${tmp_file}"
        exit 1
    fi

    # 清理临时文件
    rm -f "${tmp_file}" 2>/dev/null || true

    #---------------------------------------------------------------------
    # 3.5: 创建符号链接
    #---------------------------------------------------------------------
    log_info "正在创建符号链接到 ${PREFIX}/bin/code-server..."
    ln -sf "${install_dir}/bin/code-server" "${PREFIX}/bin/code-server"
    log_info "符号链接创建完成 ✓"

    #---------------------------------------------------------------------
    # 3.6: 验证安装
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
        log_error "尝试手动运行: ${install_dir}/bin/code-server --version"
        exit 1
    fi
}

#===============================================================================
# 步骤4: 生成配置文件
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
# 步骤5: 保活 - 启用 Wake Lock
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
# 步骤6: 安装后信息汇总
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
    echo -e "${CYAN}║   版本: 1.0.0                                         ║${NC}"
    echo -e "${CYAN}╚══════════════════════════════════════════════════════════╝${NC}"
    echo ""

    check_termux
    step_update_pkg
    step_install_deps
    step_install_code_server
    step_generate_config
    step_enable_wake_lock
    print_summary

    log_info "安装流程全部完成！请按照上方说明开始使用。"
}

# 执行主函数
main "$@"