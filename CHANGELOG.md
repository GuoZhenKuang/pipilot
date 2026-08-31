# 更新日志

## v1.1.0 (2026-08-31)

- 新增 opt-in 后台运行监控：Pi 运行期间显示常驻通知，可随时停止监控（仅停本地监控，不中止运行）
- 运行结束时发送结果通知（完成 / 有报错），点击可直达应用聊天页
- 通知内容只使用通用文案，不包含会话内容、路径或令牌；仅本地尽力而为，杀掉应用后不保证继续

## v1.0.0 (2026-08-30)

首个正式版本（品牌名 PiPilot / 领航 Pi）。

- 界面与提示信息全量汉化
- 支持官方 Tailscale 与自建 Headscale（`*.tail.guozk.top`）的明文 WebSocket 连接
- 请求等待上限 10 秒提升至 30 秒；恢复会话后增加短暂身份复核，缓解 DERP 中继下的偶发超时与误报
- 应用包名迁移为 `top.guozk.pipilot`（从旧版本升级需卸载重装并重新扫码配对）
- GitHub Actions 完整门禁（单测 / ktlint / detekt / lint / 双打包 / Bridge 检查）与正式签名发布
- 源自 [ayagmar/pi-mobile](https://github.com/ayagmar/pi-mobile) 早期版本，现独立维护与发布
