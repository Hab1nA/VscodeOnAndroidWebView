#!/data/data/com.termux/files/usr/bin/bash
# =============================================================================
# install.sh
# 在 Termux 环境中一键安装 VS Code (code-server) 开发环境
# 支持：Python 开发、HTML/前端开发、LaTeX 写作
# =============================================================================

set -e  # 遇到错误立即退出

# ---------- 颜色定义 ----------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# ---------- 工具函数 ----------
log_info()    { echo -e "${BLUE}[INFO]${NC}  $1"; }
log_success() { echo -e "${GREEN}[OK]${NC}    $1"; }
log_warn()    { echo -e "${YELLOW}[WARN]${NC}  $1"; }
log_error()   { echo -e "${RED}[ERROR]${NC} $1"; }
log_step()    { echo -e "\n${CYAN}========== $1 ==========${NC}"; }

# ---------- 欢迎横幅 ----------
clear
echo -e "${CYAN}"
echo "  ██╗   ██╗███████╗ ██████╗ ██████╗ ██████╗ ███████╗"
echo "  ██║   ██║██╔════╝██╔════╝██╔═══██╗██╔══██╗██╔════╝"
echo "  ██║   ██║███████╗██║     ██║   ██║██║  ██║█████╗  "
echo "  ╚██╗ ██╔╝╚════██║██║     ██║   ██║██║  ██║██╔══╝  "
echo "   ╚████╔╝ ███████║╚██████╗╚██████╔╝██████╔╝███████╗"
echo "    ╚═══╝  ╚══════╝ ╚═════╝ ╚═════╝ ╚═════╝ ╚══════╝"
echo -e "${NC}"
echo -e "${GREEN}  Android Termux VS Code 一键部署脚本${NC}"
echo -e "  Python / HTML / LaTeX 开发环境"
echo ""

# ---------- 1. 镜像源配置 ----------
log_step "步骤 1/7：配置 Termux 镜像源"
log_info "正在切换到清华大学镜像源以加速下载..."

# 备份原始镜像源列表（如果存在）
if [ -f "$PREFIX/etc/apt/sources.list" ]; then
    cp "$PREFIX/etc/apt/sources.list" "$PREFIX/etc/apt/sources.list.bak"
    log_info "已备份原始 sources.list 到 sources.list.bak"
fi

# 写入清华大学 Termux 镜像源
cat > "$PREFIX/etc/apt/sources.list" << 'EOF'
# 清华大学 Termux 镜像源
deb https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main stable main
EOF

log_success "镜像源已更新为清华大学镜像"

# ---------- 2. 系统更新 ----------
log_step "步骤 2/7：更新软件包列表并升级系统"
log_info "正在更新软件包列表..."
pkg update -y 2>&1 | tail -5
log_info "正在升级已安装软件包（可能需要几分钟）..."
pkg upgrade -y 2>&1 | tail -5
log_success "系统更新完成"

# ---------- 3. 安装核心工具 ----------
log_step "步骤 3/7：安装核心工具"
log_info "正在安装 curl、wget、git、openssh..."
pkg install -y curl wget git openssh 2>&1 | tail -3
log_success "核心工具安装完成：curl / wget / git / openssh"

# ---------- 4. 安装开发环境 ----------
log_step "步骤 4/7：安装开发运行环境"

log_info "正在安装 Python..."
pkg install -y python 2>&1 | tail -3
# 升级 pip 并安装常用 Python 包
pip install --upgrade pip -q
pip install flask requests 2>&1 | tail -3
log_success "Python 及 Flask 安装完成"

log_info "正在安装 Node.js（code-server 依赖）..."
pkg install -y nodejs 2>&1 | tail -3
log_success "Node.js 安装完成：$(node --version)"

# ---------- 5. 安装 code-server ----------
log_step "步骤 5/7：安装 code-server（VS Code Web 版）"
log_info "正在通过 npm 安装 code-server（这可能需要 5-15 分钟，请耐心等待）..."
log_warn "安装期间请保持网络连接，不要关闭 Termux"

npm install -g code-server 2>&1 | tail -5

if command -v code-server &>/dev/null; then
    log_success "code-server 安装成功：$(code-server --version | head -1)"
else
    log_warn "code-server 未在 PATH 中找到，尝试备用安装方式..."
    # 备用：使用官方安装脚本
    curl -fsSL https://code-server.dev/install.sh | sh
    log_success "code-server 安装完成（备用方式）"
fi

# 设置 code-server 默认密码（可修改）
VSCODE_PASSWORD="vscode123"
mkdir -p ~/.config/code-server
cat > ~/.config/code-server/config.yaml << EOF
bind-addr: 127.0.0.1:8080
auth: password
password: ${VSCODE_PASSWORD}
cert: false
EOF
log_success "code-server 配置已写入 ~/.config/code-server/config.yaml"
log_info "  默认密码：${YELLOW}${VSCODE_PASSWORD}${NC}（可在 ~/.config/code-server/config.yaml 中修改）"

# ---------- 6. 安装 TeXLive（LaTeX 支持）----------
log_step "步骤 6/7：安装 TeXLive（LaTeX 编译环境）"
log_warn "TeXLive 安装体积较大，可能需要 10-30 分钟，请耐心等待..."
log_info "正在安装 texlive..."
pkg install -y texlive 2>&1 | tail -5
log_success "TeXLive 安装完成"

# ---------- 7. 启用唤醒锁 & 收尾 ----------
log_step "步骤 7/7：配置 Termux 防后台清理"
log_info "正在启用 termux-wake-lock（需要 Termux:API 应用支持）..."

if command -v termux-wake-lock &>/dev/null; then
    termux-wake-lock
    log_success "termux-wake-lock 已启用，Termux 将在后台保持运行"
else
    log_warn "未找到 termux-wake-lock，请安装 Termux:API 应用并执行："
    log_warn "  pkg install termux-api && termux-wake-lock"
fi

# ---------- 安装完成 ----------
echo ""
echo -e "${GREEN}╔══════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║           🎉 所有组件安装完成！                 ║${NC}"
echo -e "${GREEN}╠══════════════════════════════════════════════════╣${NC}"
echo -e "${GREEN}║  启动控制面板：  bash start_dashboard.sh         ║${NC}"
echo -e "${GREEN}║  VS Code 地址：  http://localhost:8080            ║${NC}"
echo -e "${GREEN}║  默认密码：      ${VSCODE_PASSWORD}                        ║${NC}"
echo -e "${GREEN}╚══════════════════════════════════════════════════╝${NC}"
echo ""
log_info "接下来运行 ./start_dashboard.sh 启动 Web 控制面板"
