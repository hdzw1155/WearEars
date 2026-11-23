# WearEars 下一代底层架构与交接

**交接日期为 2026-11-23。** 这是新仓 versionCode 16 的第一个开发基线。已接入九个私有驱动和标准系统蓝牙，产出可供安装测试的签名 APK。旧仓 versionCode 15 及其未提交的可靠性补丁是协议、交互和回归资料来源。新建了连接生命周期、事务引擎、会话端口和编译期驱动装配。沿用既有协议证据、测试向量和四种保留风格的美术。面向实际故障的逐文件索引见 [文件导航与改动指南](FILE_MAP.md)。

## 从一笔降噪操作理解架构

1. 用户在主界面选档，或在磁贴按下某档。磁贴先在本地账本消费操作编号，核对当前版本免责、设备地址和实时连接。表盘宿主重送已消费的同一次点击时，它不能再次触发写入。
2. `ControlHub` 接收意图，由 `ConnectionOwner` 保证有可用设备和会话。`Transactions` 等待设备报告能力，只有具备降噪能力时才能提交写入。如果已确认就是目标档位，而且没有更早已发出的相反命令，这一笔可以直接完成。
3. `WireSession` 在本次连接自己的线程调用品牌驱动，驱动把统一的 ANC／关闭／通透转为机型要求的字节。`RfcommLink` 或 `GattLink` 完成物理写出后回调。用户在排队期间改选另一档时，旧请求在认领写入前会核对身份。
4. 耳机回报经过同一会话的解码器。`SessionReports` 在切换回主线程前保存来源、当时的请求和写出阶段。有效回报更新 `DeviceState`。能归给当前请求的模式证据才结束事务。`TileStore` 持久化该设备的快照，Activity 和磁贴分别按所需字段渲染。

## 谁负责什么

| 层与执行位置 | 主要文件 | 维护边界与设计理由 |
| --- | --- | --- |
| 领域规则，纯 JVM | [Selection](../core/src/com/hdz/wearcontrol/Selection.java)、[DeviceState](../core/src/com/hdz/wearcontrol/DeviceState.java)、[Transactions](../core/src/com/hdz/wearcontrol/Transactions.java) | 选择、真实状态、用户意图分别建模。偏好地址优先，过滤手机，同级冲突交给用户选择。系统 Profile 资料不全单独反馈。可以用可控时钟复现跨回包时序。 |
| Android 协调与连接，主线程 | [ControlHub](../app/src/com/hdz/wearcontrol/ControlHub.java)、[ConnectionOwner](../app/src/com/hdz/wearcontrol/ConnectionOwner.java)、[SessionReports](../app/src/com/hdz/wearcontrol/SessionReports.java) | 协调前台与磁贴的共同需求。连接拥有选择代数、当前客户端、连接截止与重试。事务独立拥有超时、回读和最终确认。断线先使旧来源失效，再拆连接。 |
| 驱动注册与能力 | [modules.json](../drivers/modules.json)、[DriverModule](../core/src/com/hdz/wearcontrol/DriverModule.java)、[DriverRegistry](../app/src/com/hdz/wearcontrol/DriverRegistry.java)、[Controls](../core/src/com/hdz/wearcontrol/Controls.java) | 构建生成带具体构造器的 `InstalledDrivers.java`。拼错或缺失工厂会导致编译失败。零个或多个模块匹配设备名都走标准蓝牙。模块裁剪决定 APK 装入哪些驱动。当前设备的能力位另由运行时证据决定。 |
| 品牌协议，一条会话线程 | [WireDriver](../core/src/com/hdz/wearcontrol/WireDriver.java)、[WireSession](../app/src/com/hdz/wearcontrol/WireSession.java)、[drivers](../drivers) | 每次新连接创建独立驱动实例与 HandlerThread。字节解析、认证、握手步骤、冷却和回读由品牌拥有。驱动只依赖端口，测试可替换时钟和传输。 |
| RFCOMM／GATT | [RfcommLink](../core/src/com/hdz/wearcontrol/RfcommLink.java)、[AndroidSockets](../app/src/com/hdz/wearcontrol/AndroidSockets.java)、[GattPump](../core/src/com/hdz/wearcontrol/GattPump.java)、[GattLink](../app/src/com/hdz/wearcontrol/GattLink.java) | RFCOMM 在独立 I/O 线程执行阻塞连接和读写，可通过关闭本次 socket 中止。BLE 按 UUID、特征属性和 CCCD 回执推进，延迟就绪后才开放写入。分片写入单步串行，两种传输各自管理积压和关闭。 |
| 界面、磁贴与门禁 | [MainActivity](../app/src/com/hdz/wearcontrol/MainActivity.java)、[EffectsPanel](../app/src/com/hdz/wearcontrol/EffectsPanel.java)、[VolumeGuard](../core/src/com/hdz/wearcontrol/VolumeGuard.java)、[TwsTileService](../app/src/com/hdz/wearcontrol/TwsTileService.java)、[TileLedger](../app/src/com/hdz/wearcontrol/TileLedger.java) | 主界面确认当前版本免责。磁贴记账、查连接、读快照，旧快捷入口引导回主界面。空间音频开启需确认当前会话。音量默认允许调低，增大需解锁，到 70% 的实际档位边界再确认。 |
| 系统兜底与构建 | [StandardClient](../app/src/com/hdz/wearcontrol/StandardClient.java)、[build.py](../build.py)、[离线依赖](../vendor/NOTICE.md) | 未识别品牌从系统 Profile 和电量广播取证。无可信电量时显示未知。JDK 17 编译 Java 11，SDK 34 的工具编译资源、DEX、对齐和签名。无网络也能编译并验证裁剪后的产品。 |

### 维护时要保留的时间边界

- 连接层给设备选择、连接、协议就绪分别处理状态。物理连接与品牌握手有 40 秒总预算。RFCOMM 候选每次最多 4 秒，顺序为各服务的安全／不安全 socket，再走固定信道。前台失败重试的三个间隔为 3、6、12 秒。
- 控制事务从协议就绪起计时，时限 12 秒。遇到部分回包时，第一次超时保留健康连接，按缺失字段补查一次。界面可以显示新意图，但确认值只来自设备。写完成回调比设备回复晚的情况有延迟回读兜底。
- RFCOMM 接收端最多积压 8 个 512 字节数据块，写队列 32 项。GATT 先确认订阅，再按 20 字节写入。实际间隔和末片等待由驱动指定。修改线程或缓冲区时，要复跑阻塞连接、缓慢写入、旧回调和洪峰测试。
- 协议的固定信道、认证方言、查询/写入掩码、禁发命令和冷却值逐品牌列在 [协议文件表](FILE_MAP.md)。涉及新机型的写操作，先把已知良好帧和拒绝样例加入协议测试。Sony、Technics、漫步者、弱水时砂在此版本仍标实验性。

## 验证与尚待实机验收

在仓库根目录运行 `py -X utf8 build.py verify`。它先在 JVM 跑核心、协议、RFCOMM、品牌驱动线序以及 Android GATT／门禁回归，再构建全量 APK、校验签名和 ZIP 对齐。Android 平台测试先用 SDK 34 的 API 编译相关生产代码，之后由 JVM 替身控制回调。还可分别打只含标准系统蓝牙的包及只含 vivo／华为的包，命令见 [README](../README.md)。

| 验收顺序 | 操作与通过标准 | 出问题先看 |
| --- | --- | --- |
| 1. 安装与升级门禁 | 在 Watch7 和一台手机冷启动，同意前点磁贴和旧快捷入口，记录应用是否只引导确认。用同一签名的 code 15 → 16 覆盖升级，保持磁贴在桌面。同意后只有新的点击能控制耳机。 | AppPrefs、TileLedger、TwsTileService、ModeCyclerActivity |
| 2. 选机及保护 | 同时连接两副同优先级耳机、显式固定一副、关闭蓝牙再恢复。多台时可选择，手机被排除，Profile 尚未返回时提示重试。未识别品牌保持系统路径。 | AudioConnections、DeviceSelector、Selection、StandardClient |
| 3. 第一条真实控制链 | 优先使用有历史基线的 Air3 Pro、FreeBuds 6i 或 Redmi Buds 6。先读电量和初始档位，再连续点三档，核对耳机最终档位、主界面和磁贴一致。断开、重连及换机后检查旧回报。 | 对应品牌 Client、WireSession、Transactions、SessionReports |
| 4. 分品牌时序与兜底 | 在可用耳机上记录固件、RFCOMM 实际选中端点、首次就绪时间、查询和切档回报。额外验证 OPPO 完整十步握手，实验性的 Edifier／Rose 分别记录 SPP 与 BLE 路径。 | 对应品牌 Protocol 与 Client、RfcommLink、GattLink、GattPump |
| 5. 手表交互 | 四种风格各验主界面和磁贴，切中英、滚动表冠、检查圆屏边缘。用低系统音量验默认锁定与 70% 档位保护，用取消和确认验空间音频开启。电量无法读取时应显示未知。 | StyleChoice、AppScrollView、ArcIndicatorView、WatchHaptics、EffectsPanel、VolumePanel |

当前没有本次开发 APK 在 Watch7、手机或耳机上的安装与交互记录。验收时请将设备、固件、APK 哈希、入口、系统可见状态、实际耳机反馈分开记录。历史机型依据见 [README](../README.md)。本地调试签名与历史发布签名的匹配须由持有人核实，升级门禁也可用同一测试密钥签两个版本演练。

### 这次交接对应的实测记录

| 检查入口 | 通过数 | 本次验证的内容 |
| --- | ---: | --- |
| CoreChecks | 41 | 选机、事务竞争、部分回包、低电量边界、免责版本、风格迁移和分片节奏 |
| ProtocolChecks | 713 | 已知协议向量、状态解析和原有交互规则 |
| RfcommChecks | 17 | 回退、可取消阻塞连接、慢写入、有界读取 |
| WireChecks | 167 | 真实品牌驱动接入测试端口后的握手、写出、回读和失败路径 |
| PlatformChecks | 64 | GATT 回调、特征属性、CCCD、API 分支、版本免责与磁贴记账 |
| [StandardRefreshChecks](../tests/platform/com/hdz/wearcontrol/StandardRefreshChecks.java) | 8 | 未识别耳机显式刷新走系统 Profile，电量无新广播可以完成。资料不全时保留连接，旧会话回调失效。 |

共 1,010 项离线断言通过。三种签名包的 DEX 类定义已分别核出 9／0／2 个品牌模块，三者均包含标准蓝牙客户端。编译后的资源表含 156 项默认中文与对应英文字符串，设置布局各含四种界面和磁贴风格，磁贴另有跟随选项。完整包 SHA-256 为 `fc881e86636570d89f33e9441b68974cffcb6d4773ec79f4d2490168fde8dff2`，用于识别本次调试产物。

## 留给下一位 AI 的修改顺序

先从上表选择一个能观察到的故障，在 [文件导航](FILE_MAP.md) 中定位拥有该规则的类，再在同层测试里写出能重现的输入与预期。变更私有协议时保留历史帧。变更线程时使乱序、回调延迟和关闭成为可控输入。变更磁贴时同时覆盖进程重建、换机和升级后的旧点击。最后运行全量 `verify`，若改动涉及注册或能力裁剪，再打 `--drivers none` 与一组混合驱动 APK。
