# 发现

## 分支整合

- `feature/popup-ui` 的 `5245c6d` 并不是“没合进去”：integration 的 `03948a6` 通过 `0ccb88d` 已经包含它，`git log integration..popup-ui` 为空。真正的问题是 `ContextMenuChecks`/`ContextMenuGeometry` 没有接进 `tests/run-checks.ps1`，导致那 32,004 项菜单几何断言从未在整合分支执行。
- `feature/mainactivity-mcp` 的 `093ca7f` 也已通过 `03948a6` 进入 integration；`git log integration..mainactivity-mcp` 为空。
- integration 的提交说明写有“修复导出异常”，但其 ancestry 中没有 `feature/storage-error` 的 `c2fb99a`；`git log integration..storage-error` 显示该提交确实缺失。合并后 `ExportFiles`、`StorageAccessActivity`、`ExportFileChecks` 才真正进入。
- `feature/2` 的 `f4cf6b6` 基于 main（已含 storage-error），提供新的简化首页、MCP 设置页和可选令牌；整合时必须用它覆盖 integration 里旧的 MCP UI/服务实现。
- 合并 `feature/2` 时，`WorkspaceUi` 的 popup 菜单逻辑与 storage 的导出/授权逻辑可以自动合并；`ToolIcon` 也能同时保留 popup 的 `records/overview/...` 图标和新的 `settings` 齿轮图标。
- 版本提升到 `0.6.0-three-features-test`，避免把“三分支 + 新 MCP”的整合包与旧 0.5.0 MCP 测试包混淆。

## 首页与 MCP

- 基线分支 `feature/2`（ce2d350）本身没有 MCP；远端 `feature/mainactivity-mcp` 有可用的后端和一套过密的首页。本轮只搬后端与设置能力，避免回退 0.4.4 的存储修复。
- 用户要的不是“更漂亮的 MCP 控制台”，而是把日常路径压成一个动作：`开启 MCP` → 看到两个地址 → 复制。因此首页不出现端口输入框，默认 2845 只在地址中自然带出；端口只在设置里出现。
- 令牌默认关闭会让同网设备直接读取，这是用户明确要求；首页用一行短警告说明风险，设置一键可达。局域网默认开启，关闭后只绑定 127.0.0.1。
- 设置页用独立 Activity 而不是对话框：横屏、320dp、大字体和软键盘下更容易保证滚动与输入框可见；运行中整个配置锁定，避免“看起来改了但没生效”。
- 地址来源是实际网卡 IPv4（wlan/wifi/eth/ap/swlan/rndis/bnep），不是猜测；无地址时明确提示，不展示 `0.0.0.0` 或占位地址。
- MCP 与悬浮工具保持独立：开启 MCP 不需要悬浮窗或 Root；退出悬浮工具不影响 MCP。首页两个动作互不依赖。
- instrumentation 里不能用 `startActivitySync` 把已存在的 singleTop 首页从后台带回；它等不到新的 Activity 记录。改为 `startActivity` 后等待 `onResume`，测试不再挂起。
- 模拟器验证用独立端口（首轮 2845，后续 18766/18765）避免与主机/其他测试冲突；最终官方 SDK 检查固定 18765，默认 2845 已在首轮真实绑定。
- 本工作区没有旧开发签名密钥，`build/signing/development.jks` 是新生成的；证书与 0.4.x 不同。测试包可覆盖安装当前模拟器，但覆盖 0.4.x 旧包需先卸载。
