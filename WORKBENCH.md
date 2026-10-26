# NetCap 数据工作台与可靠性验证

0.3.1会话库、设置与新操作位置见[LIBRARY.md](LIBRARY.md)，以下抓包与编号测试流程继续适用。

本版是开发测试版。没有启动模拟器，也没有连接用户手机执行 Root 测试。安装包可覆盖先前使用同一开发签名的版本；先保留重要会话的完整 ZIP。

## 手机操作

1. 点击启动，展开悬浮球；首次预热会请求 Root，完成授权后恢复工作台。
2. 选择目标，设置采集接口与协议。需要调查覆盖盲区时，开启“研究模式：独立保留归属未知包”。设置只影响下一次抓包。
3. 抓包页显示采集计时、已保存包数/字节、包速率/流量速率、收发与质量计数。
4. 停止后先排空与同步，再自动检查完整性；状态显示“正在检查完整性”，完成后可打开报告。
5. 数据页点记录查看，点左侧框勾选，长按打开操作；恰好勾选两条可比较，保存后在“已保存”查看与命名。
6. 分组支持连接、协议、长度；点分组进入“关联”。这是独立临时视图，不会修改全部视图的条件或保存列表。
7. 更多菜单可切换主会话/归属未知、跟随最新、返回最新、打开报告与导出。

横屏为单行导航和紧凑记录，详情关闭放在右上角。列表按窗口分页，最多驻留 1536 条，不删除磁盘记录；接近上下边缘自动补页。每次最多勾选 512 条，便于批量整理。

## 文件与计数

| 文件/计数 | 含义 |
|---|---|
| capture_*.pcap / packets.idx | 主会话原包与索引 |
| unattributed/ | 可选的未知归属独立原包、索引、状态、报告与依据 |
| attribution.jsonl | 每个已保存包的 socket 匹配类型、UID、等待时长、决策时刻、接口和原因 |
| network-events.jsonl | 系统可见网络变化与接口快照；Android16 公共回调记录热点/网络共享接口，旧版或失败写 unavailable |
| bookmarks.json | 收藏序号与别名，不修改原包 |
| integrity.txt / integrity.json | 可读概览与详细完整性证据 |
| kernelDrops | 累加读取的 socket 内核丢弃；涵盖所观测链路流量，并非游戏丢包率 |
| queueDrops | 进入内部有界队列失败 |
| unresolved | 未知归属且按策略排除 |
| unknownWritten | 归属未知且成功写入独立区域 |
| protocolFiltered / otherUidFiltered | 明确协议过滤 / 已识别其他 UID；合计 filtered |
| truncated | 原始长度超过捕获长度 |
| parseFailedSaved | 原包已保存，网络/传输头无法完整解析；不包含“未知游戏协议”的情况 |
| writeFailure / unsavedAccepted | 写盘或同步出错标记 / 已入队但未写出的数量；已写出不等同已持久化成功 |
| pendingExpired / pendingLimit | 暂存归属等待到期 / 8 MiB 暂存已满；不是额外丢弃总数，最终结果还需看保留或排除 |
| kernelStatsSamples / kernelStatsFailures | 内核统计读取成功 / 失败次数 |

暂存最多 2 秒，socket 快照约每 100 ms 更新。新连接即便等待后仍可能已关闭，不能据此保证归属完整。精确端点匹配、通配 socket 匹配都是用户态依据，不能冒充逐包 PID 证据。

主会话 PCAP 导出只含主会话。切换未知视图可单独导出未知 PCAP；完整 ZIP 包含两个区域及所有证据。

## 怎样读完整性报告

- 文件：逐条扫描经典 PCAP 边界，核对索引中的文件、偏移、长度与序号。经典 PCAP 没有结束标记，正常边界不证明进程正常结束。
- TCP：合并已观察的字节区间，允许重传、乱序及常见 32 位回绕；区间缺口不等同网络实际丢包。未见 SYN/FIN、无载荷或连接复用时须结合“无法判断”。
- KCP：只有载荷完整符合标准 KCP 帧结构时才作为候选检查 PUSH 序号。加密、私有包头和变体未覆盖，不能认定某游戏就是 KCP。
- 分片：按地址、标识、协议、接口、方向归类。首尾缺失、重叠、疑似标识复用或超限从严标为无法判断；不替代完整 IP 重组。
- 每类 4096 组、合计 200000 区间，超限记录 resourceLimitEvents；文本概览各列前 100 条流，完整证据在 JSON。
- “已观察区间未见缺口”仅是局部证据；首尾未抓到、过滤、内核/队列丢弃、未知归属排除、未选接口仍可能造成遗漏。

## 电脑上的离线回归

SDK36、JDK17+、Python3 可用后，在项目目录运行：

```powershell
.\tests\run-checks.ps1 -SdkPath 'D:\Android\SDK' -JdkPath 'D:\Android\Android studio\jbr'
.\tests\run-checks.ps1 -Traffic
```

JSON 测试依赖仅下载到 build，版本与 SHA-256 固定，不打入 APK。离线检查生成合成 PCAP，不访问用户真实抓包。

-Traffic 仅在本机 127.0.0.1 / ::1 验证，服务器和客户端均为本项目脚本；不访问公网。

## 手机连续编号验收

测试脚本只需 Python3 标准库，可在手机 Termux 等环境运行；本轮未在用户手机验证该运行环境。先在 NetCap 选择整机 IP、所有接口，开启抓包，再运行测试。验证应用归属时再改为选择测试程序所在应用，例如 Termux 主进程；比较研究模式开关的差别。

同一手机打开两个终端：

```sh
# 终端 A：IPv4 自有回显服务
python traffic_probe.py --serve --host 127.0.0.1 --seconds 120 --output server-v4.json
```

```sh
# 终端 B：长/短 UDP、长/短 TCP，负载中包含连续编号
python traffic_probe.py --host 127.0.0.1 --udp 1000 --short-udp 30 --tcp 100 --short-tcp 30 --output client-v4.json
```

IPv6 对应把两端地址改为 `::1`。接收完成后停止 NetCap，保留主会话与未知区域的完整 ZIP。回环会在不同方向产生重复观测，不能直接把 PCAP 包总数和发送次数比较。

把导出的 integrity.json 与端点结果放在一起：

```sh
python compare_probe.py --client client-v4.json --server server-v4.json --integrity integrity.json
```

对比程序按 run ID、类型及序号查看 UDP 请求/回显的唯一编号数量。主/未知报告可同时传入，但不会盲目相加不同报告的唯一数量。TCP 的逻辑消息与网络分段不是一一对应，先比较端点计数，再检查 TCP 字节区间。

### 分阶段测试矩阵

| 场景 | 做法 | 核对内容 |
|---|---|---|
| 新建/短连接 | 增加 --short-udp、--short-tcp | 未知保留/排除、暂存原因、端点与捕获编号 |
| IPv6 | 两端使用 ::1 或自有 IPv6 地址 | IPv6 包与双向结果 |
| 持续流量 | 增加 --udp，逐步提高 --rate 与 --size | 端点收发、内核/队列丢弃、编号证据、温度与磁盘 |
| Wi-Fi/移动切换 | 对自有可公网访问测试服务保持发包，手动切换 | 网络事件、接口变更、端点失败与捕获缺口分开 |
| VPN/热点 | 分别改变实际网络环境，重复相同 run | 实际接口、是否重复观测、热点角色是否 unavailable |
| 长时间 | 先数分钟，再一小时或更长 | 内存、写盘、分段、停止排空、最终报告 |

负载参数示例：`--udp 100000 --rate 2000 --size 1200`。这是待手机执行的受控测试，并不意味着已经通过该负载。

## Android 内置离线自检

设备安装后，可从有权限的终端运行：

```sh
su -c 'am instrument -w io.github.netcap.app/.SelfTest'
```

该入口会在应用缓存目录生成合成数据，包含旧解析/跨段写盘回归，以及新的精确/通配/歧义归属判断和 10 个主会话包、10 个未知包独立写盘。它不采集真实网络，不代替上述 AF_PACKET 验收。本轮只编译了此入口，没有在手机执行。


## 参考接口

- [Linux packet(7)](https://man7.org/linux/man-pages/man7/packet.7.html)：接口绑定、方向与统计读后重置。
- [KCP 官方实现](https://github.com/skywind3000/kcp/blob/master/ikcp.c)：标准头与序号格式，仅用于候选结构检查。
- [Android TetheringManager](https://developer.android.com/reference/android/net/TetheringManager)：API36 公共网络共享接口回调，只有观察，没有启停热点操作。
