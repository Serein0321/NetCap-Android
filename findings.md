# 发现
- WorkspaceUi.exportSession 直接通过 FileOutputStream 写入目标目录下的 .partial 文件。
- Manifest 声明 MANAGE_EXTERNAL_STORAGE，需要确认是否引导用户授权及校验授权。
- 当前未见 AGENTS.md；工作区起始无用户未提交修改。
- 已确认选择器是悬浮工作台自己的路径选择器，并未使用 SAF；原逻辑仅使用 canWrite，不能代表受分区存储策略约束下的实际写入能力。
- 系统文档确认所有文件访问许可可以通过普通 File 路径访问共享存储，SAF 则仍限制内部存储根目录；本修复沿用自带选择器并按需引导已有 MANAGE_EXTERNAL_STORAGE 授权。
- 授权页面采用独立 Activity，暂时隐藏悬浮窗口并在返回时恢复选择和待执行操作；不改变 Root 权限。
- 找到原开发签名 C:\Users\28451\Desktop\内核抓包工具\5_APK\build\signing\development.jks，后续构建复用并核验与旧 APK 证书一致。
- 当前 adb 无连接设备，尚无手机验证。
