# WearEars 下一代底层架构与交接

**更新日期：2026-11-24（Wave 1–5 架构演进）。** 当前仍处于 `0.5.0-dev`（versionCode 16）开发基线，在既有九品牌底层连接与磁贴底座之上，已完成：
1. **Wave 1**：`VolumeGuard.observe()` 外部降音撤销高音授权、`DeviceState` 空地址归一及既有驱动审计修复；
2. **Wave 2**：通用特性引擎（`FeatureId`/`FeatureDescriptor`/`FeatureTransactions`）、三层安全门禁（`SafetyGates`）、渐进披露面板与 Android 手机竖屏适配（`DeviceForm`、480dp 列宽、系统滚动条、至上底部切页条、226 项中英文对等）；
3. **Wave 3**：HMOV 四大品牌（vivo/iQOO、华为/荣耀、小米/Redmi、OPPO/一加/realme）深度协议扩展与运行时能力/黑名单门控；
4. **Wave 4**：手表/手机单端本地空间音频三层架构（Layer 1 本机 DSP、Layer 2 纯 Java 360° 双耳 HRTF 实验室、Layer 3 防双重空间化仲裁）；
5. **Wave 5**：剩余五品牌（NiceHCK/Edifier/Sony/Technics/RoseLink）高回报扩展与文档/工作区同步。

按故障查文件请直接翻 [文件导航与改动指南](FILE_MAP.md)。

## 分层架构与职责边界

| 层与执行位置 | 主要文件 | 维护边界与设计理由 |
| --- | --- | --- |
| 领域规则与纯 DSP（纯 JVM） | [Selection](../core/src/com/hdz/wearcontrol/Selection.java)、[DeviceState](../core/src/com/hdz/wearcontrol/DeviceState.java)、[Transactions](../core/src/com/hdz/wearcontrol/Transactions.java)、[FeatureTransactions](../core/src/com/hdz/wearcontrol/FeatureTransactions.java)、[VolumeGuard](../core/src/com/hdz/wearcontrol/VolumeGuard.java)、[WearEarsBinauralKernel](../core/src/com/hdz/wearcontrol/WearEarsBinauralKernel.java) | 选机、真实状态、主降噪事务、按特性隔离的扩展事务、音量护栏与 48 kHz 零 GC 双耳 HRTF 算法均无 Android 依赖，可在 JVM 上以确定性时钟与信号测试。 |
| 协调与连接（主线程） | [ControlHub](../app/src/com/hdz/wearcontrol/ControlHub.java)、[ConnectionOwner](../app/src/com/hdz/wearcontrol/ConnectionOwner.java)、[SessionReports](../app/src/com/hdz/wearcontrol/SessionReports.java) | 统一服务主界面与磁贴；连接层管理代次、40s 握手总预算与 3/6/12s 重试；事务层独立负责超时（主降噪 12s、扩展特性 7s、查找耳机 4s）与回读。 |
| 驱动装配与品牌协议（独立会话线程） | [modules.json](../drivers/modules.json)、[DriverRegistry](../app/src/com/hdz/wearcontrol/DriverRegistry.java)、[WireDriver](../core/src/com/hdz/wearcontrol/WireDriver.java)、[WireSession](../app/src/com/hdz/wearcontrol/WireSession.java)、[drivers](../drivers) | 编译期生成 `InstalledDrivers.java`；每条连接独占驱动实例与串行线程；各品牌按探测回包动态 `confirmFeature()`，未通过型号/位图/固件门控的特性绝不暴露写入入口，危险操作码在协议编码层硬拦截。 |
| RFCOMM / GATT 与标准蓝牙 | [RfcommLink](../core/src/com/hdz/wearcontrol/RfcommLink.java)、[AndroidSockets](../app/src/com/hdz/wearcontrol/AndroidSockets.java)、[GattLink](../app/src/com/hdz/wearcontrol/GattLink.java)、[GattPump](../core/src/com/hdz/wearcontrol/GattPump.java)、[StandardClient](../app/src/com/hdz/wearcontrol/StandardClient.java) | RFCOMM 保持 4s 单候选超时、8×512B 有界读缓冲与 32 项写队列；GATT 先确认 CCCD 再 20B 串行分片；未知品牌零私有特征，走系统 Profile + 电量广播。 |
| 本机单端空间音频与实验室 | [WearEarsSpatialEffect](../app/src/com/hdz/wearcontrol/WearEarsSpatialEffect.java)、[SystemSpatializer](../app/src/com/hdz/wearcontrol/SystemSpatializer.java)、[SpatialLabActivity](../app/src/com/hdz/wearcontrol/SpatialLabActivity.java)、[SpatialLabDialView](../app/src/com/hdz/wearcontrol/SpatialLabDialView.java) | **边界清晰**：本机单端 DSP 仅处理运行 WearEars 这台手表或手机本机输出的媒体流（无法拦截另一台手机的音频）。Layer 1 优先绑定播放器 `sessionId > 0`（仅在此路径挂 `EnvironmentalReverb`），无播放 45s 自动释放；ROM 拒控时降级 `UNSUPPORTED`。Layer 3 监听耳机硬件空间音频与 OS `Spatializer`，任一激活即旁路本机 DSP。Layer 2 实验室以 `SPATIALIZATION_BEHAVIOR_NEVER` 独占试听，超 70% 音量自动 `-12 dBFS` 限幅并在 `onPause` 立即停播释放。 |
| 界面、双端适配、安全门禁与磁贴 | [MainActivity](../app/src/com/hdz/wearcontrol/MainActivity.java)、[SettingsActivity](../app/src/com/hdz/wearcontrol/SettingsActivity.java)、[DeviceForm](../app/src/com/hdz/wearcontrol/DeviceForm.java)、[SafetyGates](../app/src/com/hdz/wearcontrol/SafetyGates.java)、[DeviceFeaturesPanel](../app/src/com/hdz/wearcontrol/DeviceFeaturesPanel.java)、[EffectsPanel](../app/src/com/hdz/wearcontrol/EffectsPanel.java)、[NoisePanel](../app/src/com/hdz/wearcontrol/NoisePanel.java)、[VolumePanel](../app/src/com/hdz/wearcontrol/VolumePanel.java)、[TwsTileService](../app/src/com/hdz/wearcontrol/TwsTileService.java) | 自动识别手表圆屏与手机竖屏；Neo 与至上风格均支持动态特性展开与诚实的能力局限说明卡；查找耳机强制执行三层佩戴保护（在耳硬拦截 -> 听觉确认 -> 响铃一键停止+30s 自动停+实时入耳急停）；实验性特性带 `[实验]` 标记与首次弹窗确认。 |

### 维护时必须保留的关键不变量

- **时间与缓冲**：连接总预算 40s，RFCOMM 单候选 4s，前台重试 3/6/12s；主降噪事务 12s，通用特性 7s（1s 延迟回读），查找耳机写出确认 4s、最长响铃 30s；本机 DSP 静音 45s 自动释放。
- **跨车道联动**：切换主降噪模式自动取消在途的 `ANC_LEVEL`/`TRANS_MODE`；关闭硬件空间音频自动取消在途的 `SPATIAL_SCENE`/`HEAD_TRACKING`；开启本机单端空间音频时若硬件空间音频正开启，会自动请求关闭后者。
- **协议安全红线**（详表见 [FILE_MAP.md](FILE_MAP.md)）：
  - 小米禁发 `0x03/0x06/0x0B/0xC1-C4/0xD0-D3/0xE1-E7` 与 Config ID `52,75,77,86,119,127,0xEEEE`，保留 `0x02`/`0xF3` 顺序，不写抗风噪；
  - 华为 HONOR 永不发 `2B 2E/2F`，全系禁发 `2B 33`；
  - OPPO 禁发 `0x0F00/0xEF01-EF09/0x0405/0x040B`，`0x0404` 后必回读 `0x010C`，抗风噪仅在 `0x810D` 确认后开放；
  - vivo 禁发 `0x014D/0x014A/0x014C` 与 `0x0220`；
  - Technics 严禁探测未知 Airoha RACE ID；Edifier 初始握手仅限 `D0/F2/CC/C1` 且禁发 `0x49`；NiceHCK 禁发 `0x0204`；RoseLink 禁发 `32`/`2B`；Sony 禁发 `3C .. 02`。
  - 反相布尔牢记：小米佩戴检测（`0x0A`/`0x06`）、Sony V2 免摘（`F8 0C`）/摘下暂停（`F8 01`）、RoseLink 入耳检测（`08`）均为 `0x00=ON, 0x01=OFF`。

## 离线验证记录与尚待真机验收

在仓库根目录运行 `py -X utf8 build.py verify` 可执行全套 JVM 回归、平台测试与 APK 签名对齐验证。

### Wave 1–4 已落盘离线回归基线

| 检查套件 | 已验通过数 | 覆盖重点 |
| --- | ---: | --- |
| [CoreChecks](../tests/CoreChecks.java) | 41 | 选机、主事务竞争、部分回包、免责版本、风格迁移、GATT 分片节奏 |
| [AuditRegressionChecks](../tests/AuditRegressionChecks.java) | 413 | `VolumeGuard.observe()` 外部降音撤销高音授权（全档位 2..100）、`DeviceState` 空地址归一 |
| [FeatureEngineChecks](../tests/FeatureEngineChecks.java) | 67 | 通用特性注册/写入/父子取消、查找耳机 30s 自动停与入耳/断链急停 |
| [ProtocolChecks](../tests/ProtocolChecks.java) | 713 | 既有九品牌协议向量、异常字节与基础交互规则 |
| [RfcommChecks](../tests/RfcommChecks.java) | 17 | RFCOMM 回退、可取消阻塞连接、慢写与有界读 |
| [Wave1DriverFixChecks](../tests/Wave1DriverFixChecks.java) | 139 | Wave 1 各品牌驱动边界与稳定性修复 |
| [WireChecks](../tests/WireChecks.java) | 168 | 真实品牌驱动接入测试端口的握手、写出、回读与失败路径 |
| [VivoExtChecks](../tests/VivoExtChecks.java) | 284 | vivo/iQOO 扩展探测、佩戴/低延迟/查找/固件/双设备只读/EQ 与空间音频门控 |
| [HuaweiExtChecks](../tests/HuaweiExtChecks.java) | 68 | 华为/荣耀机型归一、HONOR 隔离、ANC 表位切换、抗风噪/空间/低延迟/佩戴/EQ |
| [XiaomiExtChecks](../tests/XiaomiExtChecks.java) | 200 | 小米/Redmi 禁发拦截、VID:PID 能力表、反相佩戴、自适应、查找耳机急停与空间音频 |
| [OppoExtChecks](../tests/OppoExtChecks.java) | 904 | OPPO/一加/realme 握手零成本发现、PID/位图门控、`0x0404` 回读与禁发拦截 |
| [SpatialDspChecks](../tests/SpatialDspChecks.java) | 480,029 | 双耳 HRTF ITD/ILD/耳廓/FDN 数学特性、10s 扫频零 NaN 与峰值安全限幅 |
| [platform_checks.py](../tests/platform_checks.py)（含 [PlatformChecks](../tests/platform/com/hdz/wearcontrol/PlatformChecks.java) & [StandardRefreshChecks](../tests/platform/com/hdz/wearcontrol/StandardRefreshChecks.java)） | 226 + 89 + 23 | 226 项 ZH/EN 字符串对等、Android GATT/CCCD/免责/磁贴账本、标准蓝牙 Profile 刷新 |
| Wave 5 其余五品牌扩展（`tests/OtherBrandsExtChecks.java`） | 随 Wave 5 合入后由 `build.py verify` 自动运行 | NiceHCK 固件 `>=4.08` 门控、Edifier 深度/风噪/低延迟、Sony V2 反相与能力探测、Technics 强度保留、RoseLink V2 项 |

Wave 4 结束时的全量签名包 `build/WearEars.apk` 已通过 `aapt2`、`javac --release 11`、`D8`、`zipalign -c 4` 与 `apksigner verify`；Wave 5 代码合入后须重跑 `py -X utf8 build.py verify` 与 `py -X utf8 build.py apk --drivers none` 并记录最新 SHA-256。

### 尚待真机实测验收清单

当前尚无本版开发 APK 在真实 Watch7、Android 手机或各品牌耳机上的实机运行记录。真机验收请分别记录设备型号、系统版本、耳机固件、APK 哈希、入口表现与实听反馈：

| 验收项 | 操作与通过标准 | 出问题先看 |
| --- | --- | --- |
| 1. 门禁、升级与双端布局 | Watch7 与手机冷启动：未同意前磁贴/旧快捷入口只引导确认；同签名 code 15→16 升级后旧磁贴点击失效。核对手机端 480dp 居中、系统滚动条、设置日夜状态栏及至上底部切页条。 | AppPrefs、TileLedger、DeviceForm、MainActivity、SettingsActivity |
| 2. 音量与查找耳机安全 | 用系统音量键在 >70% 与 <70% 间切换，确认降至 70% 以下后高音量授权自动撤销。连接支持佩戴状态的耳机，验证入耳时查找耳机被拦截、摘下后需确认、响铃时放回耳中立刻停响。 | VolumeGuard、VolumePanel、SafetyGates、FeatureTransactions |
| 3. HMOV 与五品牌协议 | 优先在有历史基线的 Air3 Pro / FreeBuds 6i / Redmi Buds 6 / Enco Air5 Pro / Free4 / OriG in 上核对基础控制无回退，再逐项勾选扩展特性（降噪档位、通透、低延迟、佩戴、固件等），确认实测行为与标记一致。实验品牌先摘耳低音量验证。 | 对应品牌 Protocol / Client、WireSession |
| 4. 本机单端空间音频与仲裁 | 在手表或手机本机播放音乐，开启本机单端空间音频并切换 4 场景；核对停播 45s 后自动释放；开启硬件空间音频或系统 OS 空间音频时确认状态变为旁路且无叠音；在无音效支持 ROM 上确认平滑降级 `UNSUPPORTED`。 | WearEarsSpatialEffect、SystemSpatializer、EffectsPanel |
| 5. 360° 双耳 HRTF 实验室 | 分别在 Watch7（表冠 15° 刻度）与手机（触屏拖拽）试听 360° 声像移动、头宽与耳廓调参；把媒体音量拉高至 >70% 验证 `-12 dBFS` 限幅与横幅；按 Home / 锁屏验证声音立即停止且无音频焦点残留。 | SpatialLabActivity、SpatialLabDialView、WearEarsBinauralKernel |

## 留给下一位维护者的修改顺序

先从真机现象或离线失败用例出发，在 [FILE_MAP.md](FILE_MAP.md) 定位规则归属层，在同层 `*Checks.java` 补上可重现输入与断言后再改实现。改协议必保历史黄金帧与禁发黑名单；改空间 DSP 必保无 NaN/峰值上限与防重仲裁；改 UI 须同步检查中英文 226+ 键对等以及手表/手机双端布局。收尾前运行 `py -X utf8 build.py verify` 与 `py -X utf8 build.py apk --drivers none`。
