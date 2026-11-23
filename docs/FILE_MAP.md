# WearEars 文件导航与改动指南

本文给下一位维护者或接手的 AI 使用。所有链接从本文所在的 `docs/` 出发。在仓库根目录运行文中的命令。

## 先跟一笔“从磁贴切换降噪”的操作

1. [TwsTileService](../app/src/com/hdz/wearcontrol/TwsTileService.java) 收到磁贴请求，[TileLedger](../app/src/com/hdz/wearcontrol/TileLedger.java) 消费点击编号，[TileActions](../app/src/com/hdz/wearcontrol/TileActions.java) 为每一组界面按钮绑定令牌和设备地址，服务确认当前版本免责、实际连接和有效点击后交给协调器核对目标。被门禁挡下的旧点击在同意后也应失效。
2. [ControlHub](../app/src/com/hdz/wearcontrol/ControlHub.java) 把操作交给 [Transactions](../core/src/com/hdz/wearcontrol/Transactions.java)，把设备选择、连接及重试交给 [ConnectionOwner](../app/src/com/hdz/wearcontrol/ConnectionOwner.java)。读取到当前档位、能力已确认、命令已写出、耳机确认了新档位，是四件可分别发生的事。
3. [DriverRegistry](../app/src/com/hdz/wearcontrol/DriverRegistry.java) 选择驱动，[WireSession](../app/src/com/hdz/wearcontrol/WireSession.java) 为这一次连接创建独立的执行线程与端口。驱动依照品牌协议选择 RFCOMM 或 GATT，产出查询、设置字节并解码回包。
4. [SessionReports](../app/src/com/hdz/wearcontrol/SessionReports.java) 关联会话、请求与写出证据，[DeviceState](../core/src/com/hdz/wearcontrol/DeviceState.java) 保存已确认字段，[Transactions](../core/src/com/hdz/wearcontrol/Transactions.java) 决定事务是否完成。[TileStore](../app/src/com/hdz/wearcontrol/TileStore.java) 保存该地址的快照，[TileState](../app/src/com/hdz/wearcontrol/TileState.java) 为下一次磁贴渲染准备数据。
5. 要复现“点击后回到上一档”，先在 [CoreChecks](../tests/CoreChecks.java) 排连续点击和迟到回包，再在 [WireChecks](../tests/WireChecks.java) 核对该品牌写出和确认回包的先后顺序。涉及真实回连时追加 [RfcommChecks](../tests/RfcommChecks.java) 或 [PlatformChecks](../tests/platform/com/hdz/wearcontrol/PlatformChecks.java)。

## 核心规则与执行边界

| 如果要改 | 文件 | 职责 |
|---|---|---|
| 操作与能力 | [Controls](../core/src/com/hdz/wearcontrol/Controls.java)、[DriverModule](../core/src/com/hdz/wearcontrol/DriverModule.java)、[WireDriver](../core/src/com/hdz/wearcontrol/WireDriver.java)、[SoundPreset](../core/src/com/hdz/wearcontrol/SoundPreset.java) | 能力位、命令及监听接口。品牌工厂的匹配与创建。单次驱动会话可用的收发、定时、时钟接口。音效预设值。每款设备用当前驱动上报的能力裁剪操作。 |
| 选设备 | [Selection](../core/src/com/hdz/wearcontrol/Selection.java)、[DeviceSelector](../app/src/com/hdz/wearcontrol/DeviceSelector.java)、[AudioConnections](../app/src/com/hdz/wearcontrol/AudioConnections.java)、[BluetoothAccess](../app/src/com/hdz/wearcontrol/BluetoothAccess.java) | 纯规则处理显式地址、同级歧义、手机排除与系统查询不完整。Android 层收集配对、设备类别和音频 Profile，检查权限。优先把可复现的多设备组合写进 [CoreChecks](../tests/CoreChecks.java)。 |
| 生命周期 | [ConnectionOwner](../app/src/com/hdz/wearcontrol/ConnectionOwner.java)、[TwsClient](../app/src/com/hdz/wearcontrol/TwsClient.java)、[ControlHub](../app/src/com/hdz/wearcontrol/ControlHub.java) | 主线程上由连接所有者处理选择、代次、重试、连接超时与关闭。客户端接口定义连接/查询/控制。协调器连接界面观察者、当前设备和事务，并负责前台刷新。 |
| 操作确认与实时状态 | [Transactions](../core/src/com/hdz/wearcontrol/Transactions.java)、[SessionReports](../app/src/com/hdz/wearcontrol/SessionReports.java)、[DeviceState](../core/src/com/hdz/wearcontrol/DeviceState.java) | 事务保存最新意图、分辨等待能力/等待写出/等待确认。回报适配层附带当前会话和请求证据。设备状态只合并有效字段。部分刷新回包能更新界面，缺失字段可在当前连接上补查。 |
| 传输生命周期 | [WireSession](../app/src/com/hdz/wearcontrol/WireSession.java)、[AndroidSockets](../app/src/com/hdz/wearcontrol/AndroidSockets.java)、[RfcommLink](../core/src/com/hdz/wearcontrol/RfcommLink.java)、[ProtocolWriter](../core/src/com/hdz/wearcontrol/ProtocolWriter.java) | 会话拥有驱动、串行回调线程及传输资源。Android 适配器创建标准和固定信道 socket。RFCOMM 执行 secure/insecure/固定信道回退、可取消阻塞连接和有界接收。写队列维持顺序并回报实际写出。 |
| BLE 写入 | [GattLink](../app/src/com/hdz/wearcontrol/GattLink.java)、[GattPump](../core/src/com/hdz/wearcontrol/GattPump.java) | Android 层发现特征并完成 CCCD 订阅、等待稳定、过滤失效回调。发送泵按 20 字节分片，串行等待写回调并遵守驱动提供的间隔。覆盖点分别在 [PlatformChecks](../tests/platform/com/hdz/wearcontrol/PlatformChecks.java) 和 [CoreChecks](../tests/CoreChecks.java)。 |
| 标准蓝牙 | [StandardClient](../app/src/com/hdz/wearcontrol/StandardClient.java)、[BluetoothLink](../app/src/com/hdz/wearcontrol/BluetoothLink.java)、[LinkState](../core/src/com/hdz/wearcontrol/LinkState.java) | 兜底客户端读取系统公开状态与电量广播。磁贴按地址查询当前链路，用已连接、已断开和未知三个结果区分系统证据。新增品牌名匹配从 [DriverRegistry](../app/src/com/hdz/wearcontrol/DriverRegistry.java) 与品牌工厂入手。 |

## 私有协议与证据

“有实测记录”表示旧版在所列机型上验证过相关协议。移植后的驱动可用 [ProtocolChecks](../tests/ProtocolChecks.java) 核对字节和解析，用 [WireChecks](../tests/WireChecks.java) 核对实际工厂、握手、查询和写入时序。新 APK 和具体固件的互操作仍需真机记录。

| 品牌与证据 | 改哪里 | 必须保留的线格式与时序 |
|---|---|---|
| vivo / iQOO，有实测记录，vivo TWS Air3 Pro | [VivoGaiaClient](../drivers/vivo/src/com/hdz/wearcontrol/vivo/VivoGaiaClient.java)、[VivoProtocol](../drivers/vivo/src/com/hdz/wearcontrol/vivo/VivoProtocol.java) | GAIA-over-RFCOMM、固定信道 13、失败冷却 2000 ms。按 AIR3_PRO、FAMILY_V4、TWS_3E 配置处理线格式，流解析要能从错位恢复。DeepX `0118/0218` 有效档为 0/1/2/3/5。空间音频开关 `0139/0239` 属实验功能。 |
| 华为 / 荣耀，有实测记录，FreeBuds 6i、FreeBuds SE 4 ANC、HONOR Earbuds X9 | [HuaweiSppClient](../drivers/huawei/src/com/hdz/wearcontrol/huawei/HuaweiSppClient.java)、[HuaweiSoundProtocol](../drivers/huawei/src/com/hdz/wearcontrol/huawei/HuaweiSoundProtocol.java) | `5A`、CRC16-XMODEM、TLV。取消蓝牙发现，固定信道 1/16。无回执写出后等 400 ms 再回读。音效查询/设置为 `4A/49`。 |
| 小米 / Redmi，有实测记录，Redmi Buds 6 | [XiaomiMmaClient](../drivers/xiaomi/src/com/hdz/wearcontrol/xiaomi/XiaomiMmaClient.java)、[XiaomiProtocol](../drivers/xiaomi/src/com/hdz/wearcontrol/xiaomi/XiaomiProtocol.java)、[XiaomiAuth](../drivers/xiaomi/src/com/hdz/wearcontrol/xiaomi/XiaomiAuth.java) | RCSP 帧头 `FE DC BA`、帧尾 `EF`。E21 双向认证、Redmi Buds 5 免认证分支，固定信道 6/29。**禁止发送操作码 `0x06`，它会断链。** |
| OPPO / 一加 / realme，有实测记录，Enco Air5 Pro、Enco Free4 | [OppoOpoClient](../drivers/oppo/src/com/hdz/wearcontrol/oppo/OppoOpoClient.java)、[OppoProtocol](../drivers/oppo/src/com/hdz/wearcontrol/oppo/OppoProtocol.java) | OPOv1 + LEB128、insecure 回退及信道 15。十步握手首步 300 ms、步进 200 ms、末步 400 ms，相邻步骤依赖实际写出。写掩码与读/推送掩码分别编解码。 |
| 原道 NiceHCK，有实测记录，OriG in | [NiceHckClient](../drivers/nicehck/src/com/hdz/wearcontrol/NiceHckClient.java)、[NiceHckProtocol](../drivers/nicehck/src/com/hdz/wearcontrol/NiceHckProtocol.java) | `4E` 小端帧、150 ms 回读，失败冷却 1500 ms。 |
| 漫步者 EDIFIER，实验性 | [EdifierClient](../drivers/edifier/src/com/hdz/wearcontrol/EdifierClient.java)、[EdifierProtocol](../drivers/edifier/src/com/hdz/wearcontrol/EdifierProtocol.java) | V1/V2、XOR `A5` 与明文方言（FitBuds Turbo）。安全命令集 `D0/F2/CC/C1`。SPP 失败转 BLE，按 UUID 找特征，20 字节分片、30 ms 间隔并保留末片等待，失败冷却 1500 ms。 |
| 弱水时砂 RoseLink，实验性 | [RoseLinkClient](../drivers/rose/src/com/hdz/wearcontrol/RoseLinkClient.java)、[RoseProtocol](../drivers/rose/src/com/hdz/wearcontrol/RoseProtocol.java) | SPP 失败转 BLE，分片间隔 40 ms、查询去重 300 ms。就绪条件为有效状态包，订阅成功只说明传输已准备。 |
| Sony MDR，实验性 | [SonyClient](../drivers/sony/src/com/hdz/wearcontrol/SonyClient.java)、[SonyProtocol](../drivers/sony/src/com/hdz/wearcontrol/SonyProtocol.java) | 查 MDR 的通知与状态回报、帧序号、确认和重试。维持单个在途序号。 |
| Technics EAH-AZ，实验性 | [TechnicsClient](../drivers/technics/src/com/hdz/wearcontrol/TechnicsClient.java)、[TechnicsProtocol](../drivers/technics/src/com/hdz/wearcontrol/TechnicsProtocol.java) | 查 RACE 帧、型号匹配、查询顺序与状态回读。 |

增删品牌从 [drivers/modules.json](../drivers/modules.json) 开始，工厂实现 [DriverModule](../core/src/com/hdz/wearcontrol/DriverModule.java)，新建的驱动实现 [WireDriver](../core/src/com/hdz/wearcontrol/WireDriver.java)。[build.py](../build.py) 在构建目录生成 `InstalledDrivers.java`，用 Java 编译器校验工厂引用。[DriverRegistry](../app/src/com/hdz/wearcontrol/DriverRegistry.java) 负责唯一匹配与标准兜底。更改硬件支持范围时，同时检查协议命令、设备能力上报、界面控件和未选中该驱动的裁剪包。

## 免责、磁贴和手表界面

| 改动入口 | 对应文件和职责 |
|---|---|
| 首次启动、升级、权限与快捷入口 | [Consent](../core/src/com/hdz/wearcontrol/Consent.java) 校验已同意的版本，[AppPrefs](../app/src/com/hdz/wearcontrol/AppPrefs.java) 读取当前安装版本并保存确认，[MainActivity](../app/src/com/hdz/wearcontrol/MainActivity.java) 接受确认。[ModeCyclerActivity](../app/src/com/hdz/wearcontrol/ModeCyclerActivity.java) 将旧快捷入口带回主界面。[AndroidManifest.xml](../app/AndroidManifest.xml) 声明组件、权限与可选手表特性。验证升级前生成的磁贴点击和复用持久状态见 [PlatformChecks](../tests/platform/com/hdz/wearcontrol/PlatformChecks.java)。 |
| 磁贴点击、缓存与刷新 | [TileCommand](../core/src/com/hdz/wearcontrol/TileCommand.java) 定义带令牌、地址和档位的命令。[TileLedger](../app/src/com/hdz/wearcontrol/TileLedger.java) 持久化与消费令牌。[TileActions](../app/src/com/hdz/wearcontrol/TileActions.java) 为磁贴按钮生成带地址和令牌的动作。[TwsTileService](../app/src/com/hdz/wearcontrol/TwsTileService.java) 是系统入口。[TileSnapshot](../core/src/com/hdz/wearcontrol/TileSnapshot.java) 规定快照、连接证据和待确认档位的显示规则。[TileStore](../app/src/com/hdz/wearcontrol/TileStore.java) 按设备缓存。[TileConnectionReceiver](../app/src/com/hdz/wearcontrol/TileConnectionReceiver.java) 接受系统广播并触发更新。 |
| 磁贴绘制 | [TileState](../app/src/com/hdz/wearcontrol/TileState.java)、[TileRenderSpec](../app/src/com/hdz/wearcontrol/TileRenderSpec.java)、[TileLayouts](../app/src/com/hdz/wearcontrol/TileLayouts.java)、[TileResources](../app/src/com/hdz/wearcontrol/TileResources.java)、[TileFutures](../app/src/com/hdz/wearcontrol/TileFutures.java) 管理渲染输入、布局、图片资源与服务所需的异步结果。[NeoTilePainter](../app/src/com/hdz/wearcontrol/NeoTilePainter.java)、[SuprematistTilePainter](../app/src/com/hdz/wearcontrol/SuprematistTilePainter.java) 分别画新系列与至上风格。 |
| 主屏与设置 | [MainActivity](../app/src/com/hdz/wearcontrol/MainActivity.java) 绑定生命周期、状态和用户操作。[MainPanel](../app/src/com/hdz/wearcontrol/MainPanel.java) 定义面板契约，[MainPanels](../app/src/com/hdz/wearcontrol/MainPanels.java) 组装面板。[SettingsActivity](../app/src/com/hdz/wearcontrol/SettingsActivity.java) 管设置和选择项。[StyleChoice](../core/src/com/hdz/wearcontrol/StyleChoice.java) 归一化旧风格编号，保留至上、新极简、新经典、新液位。[AppLocale](../app/src/com/hdz/wearcontrol/AppLocale.java)、[中文资源](../app/res/values/strings.xml)、[英文资源](../app/res/values-en/strings.xml) 对应语言。 |
| 电量、降噪、音效与空间音频 | [BatteryPanel](../app/src/com/hdz/wearcontrol/BatteryPanel.java) 渲染电量和未知值。[NoisePanel](../app/src/com/hdz/wearcontrol/NoisePanel.java) 渲染降噪能力和待确认档位。[EffectsPanel](../app/src/com/hdz/wearcontrol/EffectsPanel.java) 绑定音效，以及空间音频开启时的确认弹窗。修改可点击条件时对照 [DeviceState](../core/src/com/hdz/wearcontrol/DeviceState.java) 和主界面操作入口。 |
| 系统媒体音量 | [VolumePanel](../app/src/com/hdz/wearcontrol/VolumePanel.java) 接入音量广播和系统写入，[VolumeGuard](../core/src/com/hdz/wearcontrol/VolumeGuard.java) 管默认锁定、安全上限和二次解锁。[VolumeBarView](../app/src/com/hdz/wearcontrol/VolumeBarView.java)、[SuprematistDialView](../app/src/com/hdz/wearcontrol/SuprematistDialView.java) 共享护栏。系统最大档位可能无法被 10 整除，边界按整数档验证。 |
| 手表布局与表冠 | [新系列布局](../app/res/layout/activity_main_neo.xml)、[至上布局](../app/res/layout/activity_main_supreme.xml)、[设置布局](../app/res/layout/activity_settings.xml) 决定控件位置。[AppScrollView](../app/src/com/hdz/wearcontrol/AppScrollView.java)、[ArcIndicatorView](../app/src/com/hdz/wearcontrol/ArcIndicatorView.java)、[WatchHaptics](../app/src/com/hdz/wearcontrol/WatchHaptics.java) 接入滚动、弧形滚动条和震感。[ScrollFeedback](../core/src/com/hdz/wearcontrol/ScrollFeedback.java)、[ScrollIndicatorMotion](../core/src/com/hdz/wearcontrol/ScrollIndicatorMotion.java) 给离线测试提供纯计算规则。 |
| 小控件与皮肤 | [NeoStyle](../app/src/com/hdz/wearcontrol/NeoStyle.java)、[NeoBatteryView](../app/src/com/hdz/wearcontrol/NeoBatteryView.java)、[NeoBatteryArt](../app/src/com/hdz/wearcontrol/NeoBatteryArt.java)、[NeoVolumeArt](../app/src/com/hdz/wearcontrol/NeoVolumeArt.java) 管新系列配色、电量和音量绘图。[NoiseSegmentedView](../app/src/com/hdz/wearcontrol/NoiseSegmentedView.java)、[StatusDotView](../app/src/com/hdz/wearcontrol/StatusDotView.java)、[SnapChipRow](../app/src/com/hdz/wearcontrol/SnapChipRow.java) 管分段降噪、状态点、选项滚动。[SuprematistControlView](../app/src/com/hdz/wearcontrol/SuprematistControlView.java)、[SuprematistDecorView](../app/src/com/hdz/wearcontrol/SuprematistDecorView.java)、[SuprematistFanDeckView](../app/src/com/hdz/wearcontrol/SuprematistFanDeckView.java)、[SuprematistPageRailView](../app/src/com/hdz/wearcontrol/SuprematistPageRailView.java) 管至上的控制、装饰、卡片与页轨。 |

## 构建与测试入口

| 我要验证什么 | 命令与测试文件 |
|---|---|
| 纯核心 | `py -X utf8 build.py core`。[CoreChecks](../tests/CoreChecks.java) 在 JVM 上验证意图覆盖、状态合并、协议能力、选择、GATT 发送泵等规则。 |
| 协议和真实驱动的离线行为 | `py -X utf8 build.py test`。[ProtocolChecks](../tests/ProtocolChecks.java) 含协议向量、异常字节、音量/滚动/快照用例。[WireChecks](../tests/WireChecks.java) 给各品牌驱动注入可控端口、时钟及写回调。 |
| RFCOMM 阻塞、拥塞或写出时机 | 同一 `test` 命令。[RfcommChecks](../tests/RfcommChecks.java) 用可控 socket/流覆盖候选端点、阻塞连接取消、接收积压和慢写入。 |
| GATT 平台回调、版本门禁与磁贴点击持久化 | `py -X utf8 tests/platform_checks.py` 可单独运行。[platform_checks.py](../tests/platform_checks.py) 先以真实 SDK 编译生产适配器，再用 [Android 测试替身](../tests/platform/android)、[MemorySettings](../tests/platform/com/hdz/wearcontrol/MemorySettings.java) 和 [PlatformChecks](../tests/platform/com/hdz/wearcontrol/PlatformChecks.java) 驱动回调与存储。 |
| 可安装 APK 与驱动裁剪 | `py -X utf8 build.py verify` 运行测试并使用默认本地调试密钥签名并生成 `build/WearEars.apk`。仅打包用 `py -X utf8 build.py apk`。裁剪示例为 `py -X utf8 build.py apk --drivers vivo,huawei` 或 `--drivers none`。裁剪包带独立文件名，可与全驱动包并存。 |
| SDK、签名、依赖和授权声明 | [build.py](../build.py) 管 JDK 17、SDK 34、Java 编译、资源打包、DEX、对齐与签名。[build.bat](../build.bat) 是 Windows 包装入口。变更离线 jar 时核对 [SHA256SUMS](../vendor/SHA256SUMS) 与 [第三方说明](../vendor/NOTICE.md)，项目授权见 [LICENSE](../LICENSE) 和 [NOTICE](../NOTICE)。 |

标准蓝牙主动刷新由 [StandardClient](../app/src/com/hdz/wearcontrol/StandardClient.java) 查询系统音频 Profile。电量依赖系统广播。遇到电量稳定时刷新超时、Profile 不完整引发误断开或旧回调误判，运行 `py -X utf8 tests/platform_checks.py`，用 [StandardRefreshChecks](../tests/platform/com/hdz/wearcontrol/StandardRefreshChecks.java) 定位。

当问题涉及 Watch7 射频连接、Android 厂商蓝牙栈、耳机固件或表冠手感，记录 APK 版本、机型、固件、入口和操作顺序，并分别写下手表显示、实际耳机行为和离线测试结果。
