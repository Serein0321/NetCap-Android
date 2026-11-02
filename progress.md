# 进度

- 2026-11-02：完成 `integration/three-features` 分支审计。`feature/popup-ui`（`5245c6d`）实际已通过 `0ccb88d` 进入整合分支，`feature/mainactivity-mcp`（`093ca7f`）也已进入；真正缺失的是 `feature/storage-error`（`c2fb99a`）和 `feature/2` 的简化首页/MCP（`f4cf6b6`）。
- 长按菜单的 `ContextMenuChecks`/`ContextMenuGeometry` 原本没有接入 `tests/run-checks.ps1`，整合时补齐；32,004 项菜单几何断言现在会随主机回归执行。
- 已合并 `c2fb99a` 的导出/共享存储修复，再合并 `feature/2`；MCP 冲突统一采用 `feature/2` 的新实现，保留 popup-ui 的 `WorkspaceUi`、`ToolIcon` 图标和 `ContextMenuGeometry`。
- 版本提升为 `0.6.0-three-features-test`（versionCode 13），避免与旧 0.5.0 MCP 测试包混淆。
- 主机回归 49,735 项通过：5289 数据、1650 会话/几何、1579 TLS/主题、8043 画库/悬浮球、1057 文件工作区、35 归档、18 导出、32,004 长按菜单几何、60 MCP。
- Android API36.1 模拟器通过 90 项首页/MCP 检查；官方 Python MCP SDK（initialize 2025-11-25、六工具、真实 fixture）通过。
- 最终 APK：`build/outputs/NetCap-0.6.0-three-features-test.apk`；SHA-256 `E5D2FAC1C5B99E1FE06975E96A96B73D893AE3C2D30D0182A0E15A9D0DF173F6`。
- 已更新 README、MCP.md、VERIFICATION.md。长按菜单的实际触摸、系统“管理所有文件”授权和共享目录导出仍需设备验收；未验证实体手机跨设备 Wi‑Fi/热点、Root 并发读取和厂商后台策略。
