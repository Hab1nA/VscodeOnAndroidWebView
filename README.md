# Termux Code-Server 一键部署与管理

在 Android 平板的 Termux 环境中安装、管理并通过网页端运行 [code-server](https://github.com/coder/code-server)（VS Code Server）。

---

## 项目文件结构

```
VscodeOnAndroidWebView/
├── install.sh              # 一键安装脚本 (pkg install code-server 模式)
├── uninstall.sh            # 安全卸载脚本 (仅清理文件, 不卸载系统依赖)
├── start_web_ui.sh         # 启动 Web 管理控制台
├── web_ui/
│   ├── server.py           # Python3 极简后端 (零外部依赖)
│   └── index.html          # Web 仪表盘前端页面
└── README.md               # 本文件
```

---

## 快速开始

### 前置条件

- Android 设备已安装 [Termux](https://termux.com/) (推荐从 F-Droid 下载最新版)
- 网络连接正常

### 第一步：将项目文件复制到 Termux

将本项目的所有文件拷贝到 Termux 的存储空间中。例如放在 `~/VscodeOnAndroidWebView/` 目录下。

### 第二步：赋予脚本执行权限

```bash
cd ~/VscodeOnAndroidWebView
chmod +x install.sh uninstall.sh start_web_ui.sh
```

### 第三步：执行一键安装

```bash
./install.sh
```

该脚本将自动完成以下操作：
1. 更新 Termux 包管理器
2. **加载 x11-repo 和 tur-repo 仓库源**（code-server 位于 tur-repo 中）
3. 安装基础依赖（Python、termux-api）
4. **通过 `pkg install code-server` 安装 Termux 原生适配的 code-server**
5. 生成配置文件及随机密码
6. 启用 Wake Lock 防止后台被杀

> **安装方式说明**：code-server 现在通过 Termux 的 `pkg` 包管理器直接安装，不再从 GitHub Release 下载预编译包。
> tur-repo（Termux User Repository）包含了社区维护的 code-server 包，已针对 Android Bionic libc 做好适配，无需手动处理 node 兼容性问题。
>
> 安装完成后，**请务必记下终端中显示的登录密码！**

### 第四步：启动 Web 管理控制台

```bash
./start_web_ui.sh
```

启动后，终端会显示管理页面的访问地址，例如：
```
本地访问:    http://localhost:8000
局域网访问:  http://192.168.1.100:8000
```

### 第五步：在平板浏览器中打开管理页面

在平板浏览器地址栏输入上一步显示的局域网地址，进入 Web 仪表盘。

在管理页面上：
- 点击 **「▶ 启动 VS Code」** 按钮启动 code-server
- 点击 **「■ 停止 VS Code」** 按钮停止 code-server
- 状态指示器会实时显示 code-server 运行状态

启动成功后，页面会显示 VS Code Server 的访问地址（默认 `http://<设备IP>:8080`），点击即可在浏览器中打开 VS Code。

---

## 常用命令速查

| 操作 | 命令 |
|---|---|
| 安装 code-server | `./install.sh` |
| 启动 Web 管理台 | `./start_web_ui.sh` |
| 手动启动 code-server | `code-server` |
| 查找密码 | `cat ~/.config/code-server/config.yaml` |
| 查看日志 | `cat ~/.config/code-server/code-server.log` |
| 安全卸载 | `./uninstall.sh` |
| 释放 Wake Lock | `termux-wake-unlock` |
| 更新 code-server | `pkg upgrade code-server -y` |

---

## 端口说明

| 端口 | 服务 | 用途 |
|---|---|---|
| `8000` | Web 管理控制台 (server.py) | 启动/停止 code-server、查看状态 |
| `8080` | code-server (VS Code) | 在浏览器中打开 VS Code 编辑器 |

---

## 常见问题

### Q: 安装时提示找不到 code-server 包？
A: code-server 位于 tur-repo 仓库中。请确认：
1. `install.sh` 脚本已自动执行 `pkg install x11-repo -y` 和 `pkg install tur-repo -y`
2. 如果手动安装，请先执行：
   ```bash
   pkg install x11-repo -y
   pkg install tur-repo -y
   pkg update
   pkg install code-server -y
   ```

### Q: tur-repo 安装失败？
A: 请检查：
1. 网络是否能正常访问 Termux 包仓库
2. 尝试手动执行 `termux-change-repo` 更换镜像源
3. 确保 Termux 版本较新（推荐从 F-Droid 安装最新版）

### Q: 浏览器打不开管理页面？
A: 请确认：
1. 平板和手机/设备在同一局域网内
2. Termux 已获取存储和网络权限
3. 防火墙未阻止 8000/8080 端口

### Q: 切到后台后 code-server 被系统杀掉了？
A: 安装脚本已自动调用 `termux-wake-lock`。如果仍然被杀，请在 Termux 中手动执行：
```bash
termux-wake-lock
```

### Q: 忘记 code-server 密码了怎么办？
A: 密码存储在 `~/.config/code-server/config.yaml` 文件中：
```bash
cat ~/.config/code-server/config.yaml | grep password
```

### Q: 如何更新 code-server？
A: 通过 pkg 更新即可：
```bash
pkg update && pkg upgrade code-server -y
```
或者重新运行安装脚本（会自动跳过已安装的包）：
```bash
./install.sh
```

### Q: 卸载脚本会删除我的 Python 和其他系统包吗？
A: **不会。** `uninstall.sh` 仅通过 `pkg uninstall code-server` 卸载 code-server，并清理相关配置文件、数据目录和本项目文件。**绝不会**卸载任何系统级依赖包（python、termux-api 等）。tur-repo 仓库源也默认保留，如需清除请手动执行 `pkg uninstall tur-repo -y`。

---

## 技术栈

- **code-server**: 通过 Termux pkg 包管理器安装（tur-repo 社区仓库，已适配 Termux 环境）
- **后端**: Python3 标准库 `http.server`（零外部依赖）
- **前端**: 纯 HTML/CSS/JS（无框架，极简设计）
- **进程管理**: Shell 脚本 + PID 文件
- **保活**: Termux Wake Lock API

---

## 许可证

MIT License