# 首页收敛与 MCP 连接

## 目标

把 NetCap 首页从“MCP 控制台”收敛为两块：悬浮工作台入口与 MCP 连接。MCP 固定默认端口 2845，首页不展示端口输入；局域网默认开启、令牌默认关闭，端口/局域网/令牌放进独立设置页。开启后直接展示本机与局域网地址。

## 阶段

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
