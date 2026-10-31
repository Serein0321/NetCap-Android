# 调研记录

- MainActivity 是原生 Java Activity，目前只有工具启动入口；无 Gradle，使用 build.ps1 / Android SDK 36 手工打包。
- 既有主题由 ThemeUi / ThemePalette 统一管理，首页应复用语义色。
- 当前 Manifest 没有 INTERNET 权限，新增服务需补充。
- 服务设计采用 /mcp Streamable HTTP JSON 响应，兼容 2025-03-26、2025-06-18、2025-11-25 initialize 协议。
- 协议资料：https://modelcontextprotocol.io/specification/2025-11-25/basic/transports 与 /server/tools。当前官方搜索结果还包含 2026-07-28 新版本，交付需明确本次支持版本。
- 数据工具复用真实 PCAP/64 字节索引；只接受会话列表返回的散列 ID，拒绝任意路径，单次查询最多扫描 4096 行，最多返回 128 包。
- 模拟器首次截图显示两类地址与复制入口可见；已修正有效端口恢复后错误图标残留。Android 测试使用独立 AVD，未安装或改动已连接的真实手机。
- 官方 Python SDK 1.27.1 可用；本机 httpx 受环境代理影响，回环测试必须 trust_env=False，避免请求错误走代理。
