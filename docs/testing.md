# PiPilot 测试指南

> **设备边界：** 下文中的模拟器/真机、ADB、安装、截图、人工验收、connected 测试与性能基准命令由操作者负责。在操作者明确说出 `debug mode` 之前不要执行。日常开发使用本文末尾的非设备门禁。

## 在模拟器上运行

### 1. 启动模拟器

**方式 A：通过 Android Studio**
- 打开 Android Studio
- Tools → Device Manager → Create Device
- 选择一台手机（推荐 Pixel 7）
- 下载稳定的 Android API 37 系统镜像
- 点击启动按钮

**方式 B：命令行**

列出可用模拟器：
```bash
$ANDROID_HOME/emulator/emulator -list-avds
```

启动一台：
```bash
$ANDROID_HOME/emulator/emulator -avd Pixel_7_API_37 -netdelay none -netspeed full
```

### 2. 构建并安装

构建 debug APK：
```bash
./gradlew :app:assembleDebug
```

安装到运行中的模拟器：
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

或一步完成构建 + 安装：
```bash
./gradlew :app:installDebug
```

### 3. 启动应用

应用会出现在桌面；也可以通过 adb 启动：
```bash
adb shell am start -n top.guozk.pipilot/.MainActivity
```

### 4. 查看日志

实时查看日志：
```bash
# 应用全部日志
adb logcat | grep "PiMobile"

# 性能指标
adb logcat | grep "PerfMetrics"

# 掉帧检测
adb logcat | grep "FrameMetrics"

# 全部
adb logcat -s PiMobile:D PerfMetrics:D FrameMetrics:D
```

## 连接 Bridge 测试

应用需要 Bridge 才能工作：

### 1. 在笔记本上启动 Bridge

```bash
cd bridge
pnpm install  # 如未安装
pnpm start
```

Windows 下也可以在仓库根目录运行 `scripts\start-bridge.ps1` 一键完成自检与启动。连接前请核对已配置的主机与脱敏后的监听端口日志。

### 2. 配置应用

在模拟器的应用中：
1. 打开左侧抽屉 → **主机** → **添加主机**
2. 填写笔记本的 Tailscale IP 或 MagicDNS 主机名（`*.ts.net` 或自有域名）
3. 端口：`8787`（或 Bridge 实际使用的端口）
4. 令牌：`bridge/.env` 中设置的 `BRIDGE_AUTH_TOKEN`

### 3. 测试连接

如果应用显示「已连接」并列出会话，说明链路正常。

否则检查：
- 笔记本与模拟器宿主机上的 Tailscale 是否都在运行？
- 模拟器能否访问笔记本？可用 `adb shell ping 100.x.x.x` 测试
- Bridge 是否真的在运行？如果启用了可选的 /health 端点且该网卡允许访问，可在不携带凭据的前提下检查

## 常见问题

### 启动即显示「尚未配置主机」

首次启动的正常状态。打开左侧抽屉进入 **主机** 添加即可。

### 「连接失败」

- 检查两端的 Tailscale 是否在运行
- 核对 IP 地址是否正确
- 确认 Bridge 监听在可达地址上（`BRIDGE_HOST`，如 Tailscale IP 或 0.0.0.0）
- 检查 `bridge/.env` 的 `BRIDGE_PORT` 与 `BRIDGE_AUTH_TOKEN` 是否正确

### 会话不显示

- 确认笔记本上存在 `~/.pi/agent/sessions/`
- Bridge 需要对该目录有读取权限
- 查看 Bridge 日志中的报错

### 恢复会话时应用崩溃

- 抓取不含令牌、会话内容与隐私路径的脱敏堆栈
- 记录会话条目数量，以及失败发生在引导、首帧还是会话树刷新阶段
- 应用采用有界的初始历史与按代次门控的加载；OOM 或过期帧失败应视为回归，而不是大会话的预期行为

## 快速开发循环

快速迭代：

```bash
# 终端 1：保持日志开启
adb logcat | grep -E "PiMobile|PerfMetrics|FrameMetrics"

# 终端 2：改动后构建并安装
./gradlew :app:installDebug

# 应用保持打开，重装即生效
```

也可以使用 Android Studio 的「Apply Changes」对 Compose 预览热更新。

## 运行测试

Gradle 与编译使用 JDK 25，detekt 稳定 CLI 使用 JDK 21 工具链，另需 Android SDK platform 37.0 / build-tools 37.0.0、Node 24 LTS+、pnpm 10。

完整非设备门禁：

```bash
./gradlew clean ktlintCheck detekt test :benchmark:compileBenchmarkKotlin :app:lintDebug :app:assembleDebug :app:assembleRelease
./gradlew :app:compileDebugAndroidTestKotlin
(cd bridge && pnpm install --frozen-lockfile && pnpm run check && pnpm audit --prod)
```

聚焦聊天体验 v2 的单元测试：

```bash
./gradlew :app:testDebugUnitTest --tests 'top.guozk.pipilot.chat.*' --tests 'top.guozk.pipilot.ui.chat.*'
```

不启动模拟器/真机编译 connected 测试：

```bash
./gradlew :app:compileDebugAndroidTestKotlin
```

计划 006–011 的真机验收状态为 **PENDING — operator-owned**，计划 012 同样 **PENDING — operator-owned**。在操作者明确开启 `debug mode` 之前，不要运行模拟器、connected 测试、adb、安装 APK、性能基准、截图，也不要声称完成人工验收。

计划 013 的非设备测试覆盖：仅键值的置顶/隐藏持久化与损坏恢复；主机增删改生命周期；全主机筛选、主机切换与跨主机恢复下的 cwd 选择；隐私安全投影与搜索；缓存优先、双主机并发的刷新与部分失败隔离；确定性排序；以及快捷回复的活动/空闲/错误/取消/重复/空标识/并发切换分支。控制台的 Compose 测试覆盖空态与隐藏恢复、筛选、主机过期与默认卡片路径隐私；已编译但真机执行仍归操作者所有。真机验收启用后需验证：紧凑/展开布局、大字体、TalkBack 标签与焦点、键盘/输入法快捷回复、隐藏恢复、主机增删、单主机不可达，以及锁冲突时不接管、不自动跳转。

计划 012 真机验收启用后需采集的证据：

- 冷启动自定义 scheme 链接：精确匹配已配置端点与已验证共享源别名；
- 热启动/重复 Intent、两条快速链接、旋转/进程重建、取消与过期代次抑制；
- 未匹配与多义主机、缺失/无效令牌、认证 hello 别名变化、已撤销/缺失/已删除会话、状态损坏与锁拒绝；
- 从会话页创建/重复/复制/共享/撤销/重新生成链接；已配置源的浏览器落地页与无元数据通用页；
- 记录 APK/构建身份、不含机密的 Bridge 版本与配置、脱敏结果与时间戳。不要把包含隐私主机信息的链接截图或粘贴到公开报告。

真机验收启用后请参照 [`revival-acceptance.md`](revival-acceptance.md) 与 [`perf-baseline.md`](perf-baseline.md)。
