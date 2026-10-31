# 首页 MCP 服务

## 目标
在 MainActivity 直接配置和启停可用的 MCP 服务，显示并复制本机/局域网地址，保持悬浮工作台原有行为。

## 阶段
1. 项目与协议调研：complete
2. 实现只读 MCP 协议、数据工具和独立前台服务：complete
3. 实现首页连接面板、状态与配置：complete
4. 构建、协议测试、界面检查及交付说明：complete

## 决策
- 首页采用滚动式工具入口 + MCP 服务面板，配置内联展示，不加入悬浮弹窗。
- 首批提供有界只读数据分析工具；默认仅本机，局域网由用户主动开启，所有请求需访问令牌。
- 独立前台服务，不要求悬浮窗权限或 Root 即可浏览已有数据。

## 错误
- 首次构建 ClipboardManager 同名导入：改用 android.content.ClipboardManager 显式限定。
- 首页整文件 patch 不支持同路径 delete/add：拆为顺序执行的两次 patch，已完成。
- 现有 AVD 含不同签名的 NetCap，安装被拒绝；保留其数据，改用独立测试 AVD。
- Android 测试的 HttpURLConnection 被平台明文策略阻止：测试改用真实原始 HTTP socket，不扩大正式应用的联网策略。
- adb pull 中文目标路径异常：先提取到 ASCII 临时目录，再用 PowerShell 复制到工作区。
- 首次截图发现有效端口仍保留错误图标：输入恢复有效时立即清除校验错误，并加入 Android 回归。
- Android 测试返回 singleTop 首页时 startActivitySync 等待新 Activity 导致停滞：改用普通启动并等待 resumed 回调。
- Instrumentation 结束会终止被测进程：新增受控等待，在 runner 活着时用官方 SDK 测真实 Android 服务。
- 横屏截图显示纵向卡片浪费可用宽度：宽屏改为工具/MCP 双列，窄屏保持单列滚动。
- 最终滚动截图发现内容可绘制到状态栏下：启用 ScrollView padding 裁切；键盘出现后主动滚动到输入框，并增加可见区域断言。
- 更严格键盘断言发现 requestRectangleOnScreen 未确保输入框露出：改为在窗口布局完成后计算输入框内容坐标并显式滚动；重跑 Android 检查。
