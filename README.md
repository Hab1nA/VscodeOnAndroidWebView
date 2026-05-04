# VscodeOnAndroidWebView

在 Android 平板的 Termux 环境中，一键部署 VS Code（code-server）开发环境，并提供可视化的 Web 控制面板进行管理。

支持用途：**Python 开发 · HTML/前端开发 · LaTeX 写作**

---

## 📁 目录结构

```
.
├── install.sh           # 一键安装脚本（首次使用时执行）
├── server.py            # 控制面板后端（Flask）
├── start_dashboard.sh   # 日常启动控制面板的快捷脚本
├── templates/
│   └── index.html       # 控制面板前端页面
└── README.md
```

---

## 🚀 快速开始

### 第一步：安装 Termux 及依赖应用

1. 从 [F-Droid](https://f-droid.org/) 安装最新版 **Termux**（不推荐 Google Play 的旧版本）。
2. （可选）安装 **Termux:API**，以启用 `termux-wake-lock` 防后台被杀。

### 第二步：克隆本项目

在 Termux 中执行：

```bash
pkg install git -y
git clone https://github.com/Hab1nA/VscodeOnAndroidWebView.git
cd VscodeOnAndroidWebView
```

### 第三步：运行安装脚本（仅首次）

```bash
bash install.sh
```

脚本将自动完成以下步骤：

| 步骤 | 内容 |
|------|------|
| 1 | 切换清华大学 Termux 镜像源 |
| 2 | 更新系统软件包 |
| 3 | 安装 `curl` / `wget` / `git` / `openssh` |
| 4 | 安装 Python 3 及 Flask、Node.js |
| 5 | 安装 `code-server`（耗时较长，请耐心等待） |
| 6 | 安装 TeXLive（LaTeX 编译，耗时较长） |
| 7 | 启用 `termux-wake-lock` |

> ⚠️ TeXLive 体积较大，安装时间约 10–30 分钟，请保持网络畅通。

### 第四步：日常使用——启动控制面板

```bash
bash start_dashboard.sh
```

然后在平板浏览器中访问：

```
http://localhost:9090
```

---

## 🖥️ Web 控制面板功能

| 功能 | 说明 |
|------|------|
| 状态指示 | 实时显示 code-server 运行/停止状态（带动态指示灯） |
| 启动按钮 | 后台静默拉起 code-server |
| 停止按钮 | 安全终止 code-server 进程 |
| 快速访问 | 服务运行时一键跳转到 VS Code 编辑器页面 |

---

## ⚙️ 配置说明

### 修改 VS Code 密码

编辑 `~/.config/code-server/config.yaml`：

```yaml
bind-addr: 127.0.0.1:8080
auth: password
password: 你的新密码
cert: false
```

### 端口说明

| 服务 | 默认端口 |
|------|---------|
| VS Code（code-server） | `8080` |
| Web 控制面板 | `9090` |

如需修改端口，分别编辑 `~/.config/code-server/config.yaml`（VS Code）和 `server.py` 顶部的 `CODE_SERVER_PORT` / `DASHBOARD_PORT` 常量。

---

## 💡 使用技巧

- **防止后台被杀**：在 Android 设置 → 电池优化 → 找到 Termux → 选择"不限制"。
- **局域网访问**：将 `server.py` 中 `CODE_SERVER_CMD` 启动参数的 `127.0.0.1` 改为 `0.0.0.0`，即可从同一 Wi-Fi 内的其他设备访问（默认已配置为 `0.0.0.0`）。
- **LaTeX 编译**：在 VS Code 中安装 `LaTeX Workshop` 插件，配合已安装的 `texlive` 即可直接编译 `.tex` 文件。
- **Python 虚拟环境**：建议在项目目录下使用 `python -m venv .venv` 创建独立虚拟环境。

---

## 📝 常见问题

**Q: code-server 安装失败？**  
A: 尝试切换网络（使用手机数据或代理），然后重新运行 `bash install.sh`。

**Q: 控制面板能打开但启动 VS Code 后无法访问？**  
A: 检查 `~/.config/code-server/config.yaml` 中 `bind-addr` 是否为 `0.0.0.0:8080`（允许本机访问）或 `127.0.0.1:8080`（仅本机）。

**Q: termux-wake-lock 报错？**  
A: 需要安装 Termux:API 应用，并在 Android 权限设置中授予相关权限。

---

## 📄 许可证

MIT License
