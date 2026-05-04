#!/data/data/com.termux/files/usr/bin/bash
#===============================================================================
#  start_web_ui.sh — 一键启动 Web 管理控制台
#  功能: 检查环境 → 启用 Wake Lock → 启动 Python 后端
#  使用: ./start_web_ui.sh
#  依赖: code-server 需通过 pkg install code-server 安装 (运行 install.sh 完成)
#===============================================================================

set -euo pipefail

#===============================================================================
# 颜色输出定义
#===============================================================================
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
CYAN='\033[0;36m'
NC='\033[0m'

#===============================================================================
# 获取脚本所在的目录
#===============================================================================
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_SCRIPT="${SCRIPT_DIR}/web_ui/server.py"

#===============================================================================
# 步骤1: Termux 环境检测
#===============================================================================
check_termux() {
    if [ -z "${TERMUX_VERSION:-}" ] && [ ! -d "/data/data/com.termux/files/usr" ]; then
        echo -e "${RED}[ERROR]${NC} 此脚本必须在 Termux 环境中运行！"
        exit 1
    fi
    echo -e "${GREEN}[INFO]${NC} Termux 环境检测通过 ✓"
}

#===============================================================================
# 步骤2: 运行环境检查
#===============================================================================
check_environment() {
    echo -e "${CYAN}==>${NC} 检查运行环境..."

    # 检查 Python3
    if ! command -v python3 &>/dev/null && ! command -v python &>/dev/null; then
        echo -e "${RED}[ERROR]${NC} 未找到 Python，请先运行 install.sh 安装。"
        exit 1
    fi

    # 确定 Python 可执行文件
    if command -v python3 &>/dev/null; then
        PYTHON="python3"
    else
        PYTHON="python"
    fi

    echo -e "${GREEN}[INFO]${NC}  使用 Python: $(${PYTHON} --version 2>&1)"

    # 检查 server.py 是否存在
    if [ ! -f "${SERVER_SCRIPT}" ]; then
        echo -e "${RED}[ERROR]${NC} 未找到 ${SERVER_SCRIPT}，请确保在项目根目录下运行此脚本。"
        exit 1
    fi
    echo -e "${GREEN}[INFO]${NC}  后端脚本: ${SERVER_SCRIPT}"

    # 检查 code-server (通过 pkg install code-server 安装)
    if command -v code-server &>/dev/null; then
        echo -e "${GREEN}[INFO]${NC}  code-server 已安装 ✓ (路径: $(command -v code-server))"
    else
        echo -e "${YELLOW}[WARN]${NC} code-server 未安装，请先运行 install.sh (将执行 pkg install code-server)。"
        echo -e "${YELLOW}[WARN]${NC} Web 控制台仍可启动，但无法启动 code-server。"
    fi
}

#===============================================================================
# 步骤2: 启用 Wake Lock
#===============================================================================
enable_wake_lock() {
    echo ""
    echo -e "${CYAN}==>${NC} 启用 Wake Lock..."

    if command -v termux-wake-lock &>/dev/null; then
        termux-wake-lock 2>/dev/null || true
        echo -e "${GREEN}[INFO]${NC}  Wake Lock 已启用 ✓"
    else
        echo -e "${YELLOW}[WARN]${NC} termux-api 未安装，跳过 Wake Lock。"
        echo -e "${YELLOW}[WARN]${NC} 建议执行: pkg install termux-api -y"
    fi
}

#===============================================================================
# 步骤3: 获取设备 IP
#===============================================================================
get_device_ip() {
    local ip=""
    # 优先使用 ip 命令 (现代 Linux 标准), 回退到 ifconfig
    ip=$(ip addr show 2>/dev/null | grep -Eo 'inet ([0-9]+\.){3}[0-9]+' | grep -v '127.0.0.1' | awk '{print $2}' | head -1)
    if [ -z "${ip}" ]; then
        ip=$(ifconfig 2>/dev/null | grep -Eo 'inet (addr:)?([0-9]*\.){3}[0-9]*' | grep -Eo '([0-9]*\.){3}[0-9]*' | grep -v '127.0.0.1' | head -1)
    fi
    if [ -z "${ip}" ]; then
        ip="未知, 请在 Termux 中运行 ip addr 查看"
    fi
    echo "${ip}"
}

#===============================================================================
# 步骤4: 启动 Web 管理控制台
#===============================================================================
start_server() {
    echo ""
    echo -e "${GREEN}╔══════════════════════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║  正在启动 Web 管理控制台...                           ║${NC}"
    echo -e "${GREEN}╚══════════════════════════════════════════════════════════╝${NC}"
    echo ""

    local device_ip
    device_ip=$(get_device_ip)

    # 在场景中直接启动，让用户可以看到日志和按 Ctrl+C 停止
    echo -e "${YELLOW}提示:${NC}"
    echo -e "  在平板浏览器中打开以下任一地址:"
    echo ""
    echo -e "  ${CYAN}本地访问:${NC}  http://localhost:8000"
    echo -e "  ${CYAN}局域网访问:${NC} http://${device_ip}:8000"
    echo ""
    echo -e "  ${YELLOW}⚠ code-server 尚未启动！${NC}"
    echo -e "  ${YELLOW}请在打开的仪表盘页面点击「▶ 启动 VS Code」按钮${NC}"
    echo -e "  ${YELLOW}之后才能访问 http://${device_ip}:8080 进入 VS Code${NC}"
    echo ""
    echo -e "${YELLOW}按 Ctrl+C 停止 Web 管理服务${NC} (不会影响 code-server 运行)"
    echo -e "${YELLOW}======================================${NC}"
    echo ""

    # 直接在前台运行 Python 后端
    cd "${SCRIPT_DIR}/web_ui"
    exec ${PYTHON} server.py
}

#===============================================================================
# 主函数
#===============================================================================
main() {
    check_termux
    check_environment
    enable_wake_lock
    start_server
}

main "$@"