#!/data/data/com.termux/files/usr/bin/bash
# =============================================================================
# start_dashboard.sh
# 一键启动 VS Code Android 控制面板
# 日常使用：在 Termux 中运行此脚本即可
# =============================================================================

# 脚本所在目录（确保能在任意工作目录下执行）
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ---------- 颜色定义 ----------
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

DASHBOARD_PORT=9090
CODE_SERVER_PORT=8080

echo -e "${CYAN}"
echo "  ╔══════════════════════════════════════╗"
echo "  ║   VS Code Android 控制面板启动器     ║"
echo "  ╚══════════════════════════════════════╝"
echo -e "${NC}"

# ---------- 检查 Python ----------
if ! command -v python3 &>/dev/null && ! command -v python &>/dev/null; then
    echo -e "${YELLOW}[WARN]${NC} 未找到 Python，请先运行 install.sh 安装环境"
    exit 1
fi

PYTHON_CMD="python3"
command -v python3 &>/dev/null || PYTHON_CMD="python"

# ---------- 检查 Flask ----------
if ! $PYTHON_CMD -c "import flask" &>/dev/null; then
    echo -e "${YELLOW}[WARN]${NC} 未找到 Flask，正在自动安装..."
    pip install flask -q
fi

# ---------- 检查是否已有控制面板在运行 ----------
if lsof -iTCP:${DASHBOARD_PORT} -sTCP:LISTEN &>/dev/null 2>&1 || \
   nc -z 127.0.0.1 ${DASHBOARD_PORT} 2>/dev/null; then
    echo -e "${GREEN}[INFO]${NC}  控制面板已在运行，访问 http://localhost:${DASHBOARD_PORT}"
    echo -e "${GREEN}[INFO]${NC}  VS Code 地址：http://localhost:${CODE_SERVER_PORT}"
    exit 0
fi

# ---------- 启动 termux-wake-lock（如可用）----------
if command -v termux-wake-lock &>/dev/null; then
    termux-wake-lock 2>/dev/null &
    echo -e "${GREEN}[INFO]${NC}  termux-wake-lock 已启用"
fi

# ---------- 启动控制面板 ----------
cd "$SCRIPT_DIR"
echo -e "${GREEN}[INFO]${NC}  正在启动控制面板服务..."
echo -e "${GREEN}[INFO]${NC}  控制面板地址：http://localhost:${DASHBOARD_PORT}"
echo -e "${GREEN}[INFO]${NC}  VS Code 端口：${CODE_SERVER_PORT}（通过面板按钮启动）"
echo ""
echo -e "${YELLOW}  提示：按 Ctrl+C 可关闭控制面板（不会影响已运行的 VS Code）${NC}"
echo ""

exec $PYTHON_CMD "$SCRIPT_DIR/server.py"
