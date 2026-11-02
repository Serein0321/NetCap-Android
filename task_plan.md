# 分支整合与首页/MCP

## 目标

把 `feature/popup-ui`、`feature/storage-error`、`feature/mainactivity-mcp` 和 `feature/2` 收敛到 `integration/three-features`，解决冲突，重新构建验证并推送 GitHub。

## 阶段

1. 抓取远端、比对四个 feature 与 integration：complete
2. 审查 popup-ui 是否真正进入整合分支及其测试接线：complete
3. 合并 `feature/storage-error` 的导出/共享存储修复：complete
4. 合并 `feature/2` 的简化首页与可选 MCP：complete
5. 版本提升到 0.6.0，补齐 ContextMenuChecks/ExportFileChecks：complete
6. 主机回归、构建、模拟器 MCP 检查、官方 SDK：complete
7. 推送 `integration/three-features` 到 GitHub：pending

## 审计结论

- `feature/popup-ui` 的 `5245c6d` 已在 `0ccb88d` 合并进 integration；`git log integration..popup-ui` 为空。
- `feature/mainactivity-mcp` 的 `093ca7f` 也已在 `03948a6` 合并。
- integration 的提交说明声称包含导出修复，但 ancestry 中没有 `c2fb99a`；这是实际缺口。
- popup-ui 的 `ContextMenuChecks` 未接入 `tests/run-checks.ps1`，所以之前整合分支没有跑那 32,004 项断言。

## 上一阶段：首页收敛与 MCP

1. 盘点旧首页与 MCP 实现，确定默认值与保留边界：complete
2. 重写 MainActivity 与 McpSettingsActivity，加入 HomeStyle/Ids：complete
3. 调整 McpSettings 默认值、地址枚举与可选令牌：complete
4. 重写 Android instrumentation 与官方 SDK 检查：complete
5. 构建、主机回归、模拟器 UI/键盘/旋转验证：complete
6. 更新 README、MCP.md、VERIFICATION 与交付说明：complete

## 错误

- `startActivitySync` 用于把已存在的单例 MainActivity 从后台带回时，不会产生新的 Activity 记录，instrumentation 永久等待。改为 `startActivity` + 等待 `onResume`，不阻塞。
- Windows PowerShell 5.1 默认没有 `IO.Compression.ZipFile`，测试打包需补 `System.IO.Compression.FileSystem`；`.ps1` 中文脚本需 UTF-8 BOM。
- 模拟器 AVD 锁文件位于工作区外，sandbox 下无法写入；本轮以显式授权运行模拟器完成验证。
- 设置页首次请求软键盘时 `ime=0`；在测试中设置 `show_ime_with_hard_keyboard=1`、`SOFT_INPUT_STATE_ALWAYS_VISIBLE` 与 `SHOW_FORCED` 后，键盘正常显示且端口输入框在键盘上方。
- 构建脚本在本工作区新生成开发签名密钥，证书与 0.4.x 归档包不同；覆盖安装旧测试包需先卸载，正式发行需独立受控密钥。
- `git fetch` 在 schannel 下报 `SEC_E_NO_CREDENTIALS`；使用 `git -c http.sslBackend=openssl fetch` 成功。
