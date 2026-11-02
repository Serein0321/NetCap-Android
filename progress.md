# 进度

- 2026-11-01：确认旧首页把 MCP 端口、令牌、局域网和客户端配置全部摊开；用户要求固定 2845、首页只留一个启动按钮，令牌/端口收进设置。
- 已保留旧 MCP 后端（McpServer/McpService/McpTools），将 `McpSettings` 默认改为端口 2845、局域网开、令牌关；令牌改为可选，McpServer 在 token 为 null 时不校验 Authorization。
- 已重写首页：品牌头 + 悬浮工作台 + MCP 连接；MCP 未开启只有一个按钮，开启后显示本机/局域网地址、令牌提示和停止入口；齿轮打开独立设置页。
- 设置页包含局域网、访问令牌（显示/复制/重新生成，剪贴板敏感标记）和端口（1024–65535）；运行中锁定。
- 主机回归 17,731 项通过（含 60 项 MCP）；官方 Python MCP SDK 与 Android instrumentation 90 项通过。
- Android 验证覆盖默认 2845、地址复制、无令牌/有令牌 HTTP、端口占用重试、局域网关闭、横屏、后台、840×1800 + 1.4 字号、设置页键盘避让。
- 最终 APK：build/outputs/NetCap-0.5.0-mcp-test.apk；SHA256 3240B46D700122BB1B14993A437A8EE6E3E357915C5868946164E0E281253D39。
- 已更新 README、MCP.md、VERIFICATION.md。未连接实体手机，未验证跨设备 Wi‑Fi/热点、Root 并发读取和厂商后台策略。
