# 会话、磁贴与音量保护交接记录

> 后续更新：2026-11-17 已在包含新 UI 的正式工作区继续复核，音量改为锁定可调小，旧磁贴中转服务已删除，最新验证为 706 项离线检查和八种模块裁剪通过。请先读 [最新审计复核与修复交接](redteam-review-2026-11-17.md)；下文保留 11 月 16 日的历史记录，冲突处以最新记录为准。

日期：2026-11-16。基于本地 `b1bec9d`（0.4.1），工作分支 `fix/session-state-modules`。本轮不改版本号，不安装设备，不提交或推送。既有四种界面和表圈滚动逻辑保留。下面区分代码修复、离线验证和待实机确认，不能据此扩大品牌兼容声明。

## 用户反馈及处理

| 反馈 | 代码中的原因或证据 | 本轮处理 |
| --- | --- | --- |
| 点磁贴的降噪或电量区域后，磁贴收起 | 两处原来均使用 `LaunchAction` 打开 `ModeCyclerActivity`；即使 Activity 立即结束，也发生了页面切换 | 改为 `LoadAction`，在 `TwsTileService` 内交给共享控制器处理。仅“打开控制”继续跳转主页 |
| 降噪整卡消失、重连时伸缩 | 未收到合法回包时 `caps()` 不含降噪，被当成确定不支持；磁贴重连又用初始能力覆盖历史能力 | `potentialCaps()` 表示候选能力；主页在确认前保留禁用卡片。同一设备曾确认的磁贴降噪栏保留位置，发指令仍检查当前会话能力 |
| 断开后仍显示旧电量、旧模式 | 历史快照用于恢复实时界面，连接状态没有独立来源 | `DeviceState` 只保存当前会话；磁贴每次请求查询目标地址的系统音频连接，断开显示未连接，无法确认显示连接待确认，均隐藏旧数值 |
| 音量误触 | 三种旧风格直接写系统音量；至上风格保护独立 | 四种风格共用 `VolumeGuard`，每次 `onResume` 锁定；到 70% 再锁，必须重新解锁。解锁手势本身不调音量 |
| vivo 音效状态不及时 | UI 在切换后的四秒内忽略回包；空间音频单字节成功 ACK 可能被当成关闭状态 | 删除时间屏蔽；目标与确认状态分开，按回包及查询更新；ACK 只有结果码时不发布状态 |

新磁贴点击携带渲染代数、操作和设备地址。宿主重发、过期渲染、换设备后的旧点击不会再次写入。相同渲染上的不同目标仍能覆盖上一目标。乐观高亮与确认档位分开，最长等待 12 秒；磁贴时间线也提供到期回退，避免进程被回收后永久显示“切换中”。旧 `ModeCyclerActivity/Service` 保留用于兼容宿主尚未替换的旧布局缓存。

磁贴先认可同地址仍存活的私有控制会话，兼容“音频留在手机、手表只控制”的用法；否则查询 A2DP、HFP，并在 API 33+ 且系统明确支持时查询 LE Audio。代理用完关闭，1.5 秒无法完成则显示待确认。不扫描、不增加后台定时连接。状态代表最近一次查询结果，系统延迟刷新、进程被杀或宿主未发起请求时，不能承诺即时更新。用户未同意连接通知，本轮没有加入通知或自动弹出控制页。

## 两张截图的具体判断

- **Xiaomi Buds 5 Pro 只有总电量**：当前仍是系统标准驱动回退，不是三格布局被挤掉。此型号的 Compact GAIA 与 Redmi RCSP/MMA 不同，现有注册器明确排除，继续保留此保护。公开探测记录中 `0x1A01` 返回值与实际电量不符，不能当成分耳电量；本轮未加入该型号的私有降噪或分耳电量适配。来源：[该型号的原始协议记录](https://github.com/ncr/omarchy-headphones/blob/main/PROTOCOL.md#xiaomi-buds-5-pro--compact-gaia-on-spp)。
- **OPPO Enco Air5s 只有左右耳电量**：名称可命中 OPOv1 驱动；截图本身已经有充电仓区域，值为未知。当前解析支持回包中的左、右、盒三种部件，耳机不报告盒电量时保持 `--`，不从左右耳推算。该型号确有 ANC，但本项目仍未实测它的私有降噪回包，因此卡片等待确认后才可写入。来源：[OPPO 官方规格说明](https://www.oppo.com/en/accessories/enco-air5s/)。截图不足以证明其降噪卡片消失或盒电量报文被丢失。
- 两个型号仍需要本机固件对应的只读查询回包或蓝牙抓包。已有型号的历史“实测”标记未被本轮离线测试覆盖或升级。

## 音量保护细节

三个旧界面的锁由 Canvas 绘制白色线条，不使用 Unicode 锁表情；字体或系统 Emoji 替换不会改变样式。长按解锁后松手，再拖动调整，点锁图标可立即重新锁定，不能把这个点击当成提高音量。至上风格保留原有长按/双击解锁入口，所有音量写入统一经过保护器。

70% 按系统实际档位向下取整，例如总共 15 档时停在 10 档。若进入时系统音量已经高于此值，应用不自行降低，但第一次继续增大仍要求额外确认。系统设置、其他应用、耳机实体按键不在此应用的写入拦截范围内。退出页面、切换风格再进入后都重新锁定。

## 模块边界与裁剪方式

```text
MainActivity -> MainPanels -> BatteryPanel / NoisePanel / VolumePanel / EffectsPanel
                          -> DeviceState（当前会话数据）
UI Actions -> HeadsetController -> DriverRegistry -> DriverModule -> 品牌协议
磁贴 LoadAction -> TileActions / TileCommand -> HeadsetController
TileStore（共享历史） -> 可选 TwsTileService -> 布局 / 版本资源 / 系统连接查询
```

`MainActivity` 管连接提示、导航、权限和页面生命周期；每个面板拥有控件绑定、渲染及监听。省电策略仍由共享控制器统一执行，不在 UI 面板里重复开连接。设备选择移到 `DeviceSelector`，协议匹配规则留在各品牌的 `Module` 中。

### 与正在开发的新 UI 协作

用户最新要求：新 UI 由另一位 AI 继续完成，本轮收尾不修改它的界面设计或未完成代码。当前构建验证的是本地已有界面及本轮逻辑，不代表尚未合入的新 UI 已验收。

- 新界面读取 `DeviceState`，通过 `MainPanel.Actions` 发出降噪、空间音频和 EQ 操作；不要从历史 `TileStore` 恢复实时连接状态，也不要另建一套蓝牙连接。
- 音量交互复用 `VolumePanel.volume()` 和同一 `VolumeGuard`，保留前台进入锁定、70% 再次解锁、暂停取消手势。新控件不能直接调用系统音量写入绕开保护。
- 若调整原控件 ID 或替换自定义 View，需同步对应面板的绑定和反射保留规则；不要整份覆盖 `MainActivity`、面板或控制器，把本轮逻辑覆盖回旧版本。
- 新 UI 合入后重新构建，并在 Watch7 上验证手势、圆屏布局和磁贴交互；本次离线结果不能替它完成这部分验收。

| 可选部分 | 入口 | 裁剪注意事项 |
| --- | --- | --- |
| 品牌驱动 | `DriverRegistry.MODULES` 中的类名，品牌内的静态 `Module implements DriverModule` | 删除对应驱动及专用解析器/同目录依赖；缺席自动跳过并使用 StandardClient。vivo 整包删除时同时移除 manifest 中的 `VivoProbe` receiver |
| 电量、降噪、音量、音效 UI | `MainPanels` 注册的四个 `*Panel` | 可分别删除面板类；原布局区域隐藏，至上画布对应区域不启用。XML 引用的公共 View 类属于共享 UI 基础层，不能只删 Java 而保留 XML 引用 |
| 磁贴 | `TwsTileService`、两类布局/资源、TileRenderSpec、TileFutures、TileActions/TileCommand、TileConnectionReceiver、ModeCyclerActivity/Service | 同时移除 manifest 的磁贴相关组件，可不打包 Tiles JAR；`TileStore/TileSnapshot/TileState` 仍是主页共享历史基础层，不一并删除 |

裁剪只在验证构建中排除文件，实际工作区保留全部功能。`scripts/test-modules.ps1` 不引用完整构建的 classpath，每次生成独立输出目录，防止旧 `.class` 伪装成裁剪成功。当前 D8 不混淆；可选 R8 精简构建已为字符串注册的模块、XML View、manifest 组件和反射刷新入口配置保留规则。

## 空间音频与 EQ 的实现范围

| 品牌 | 本轮落地内容 | 限制 |
| --- | --- | --- |
| vivo / iQOO | DeepX 预设 0/1/2/3/5；回执驱动 UI；空间查询/写入状态区分；写后读回 | 写入须先收到能力回包。空间开关状态变化不等于听感或头部追踪已验证，保留实验提示 |
| 华为 | FreeBuds 5i / 6i 的内置 EQ：`2B/4A` 查询、`2B/49` 写入，接入同一 EffectsPanel | 仅明确型号、完整合法 TLV、耳机实际报告的已知预设才开放。新增 EQ 未真机验证；不包含自定义频段编辑，也不借用该型号已有 ANC 实测标记宣称 EQ 实测 |
| 小米、OPPO、漫步者、Sony、Technics、RoseLink、NiceHCK | 保留现有电量/降噪解析；共用音效扩展接口已准备好 | 本轮未添加这些品牌的空间音频或 EQ 写命令。缺少匹配型号的证据时不猜指令，不新增空壳入口 |

协议参考按报文字段独立实现，没有复制上游程序：

- [TWS-Pods-PC vivo 协议](https://github.com/Zhaoyi-ya/TWS-Pods-PC/blob/3350f424c1ab69c4bc6cff92dd7b603fb0025db2/vivo/PROTOCOL.md)：DeepX 预设与空间音频实验状态。
- [OpenFreebuds EQ handler](https://github.com/melianmiko/OpenFreebuds/blob/0b4f14c0b500d491f0fb4ea38f2984ec334e3649/openfreebuds/driver/huawei/handler/config_equalizer.py) 和 [6i 型号配置](https://github.com/melianmiko/OpenFreebuds/blob/0b4f14c0b500d491f0fb4ea38f2984ec334e3649/openfreebuds/driver/huawei/driver/per_model/buds_6i.py)：只提取命令号、TLV 字段和内置预设 ID；没有移植其自定义 EQ 实现。
- [Sony MDR V1T1 定义](https://github.com/mos9527/SonyHeadphonesClient/blob/3b3df9f741a394685bd34eb178d3cc6856235ad5/libmdr/include/mdr/Generated/ProtocolV1T1.hpp)：存在独立的 EQ/环绕声能力与参数协议，不能据此假定当前所有 MDR V1/V2 型号都可直接写入同一格式。
- [Android 磁贴交互](https://developer.android.com/training/wearables/tiles/interactions)：应用内逻辑使用 LoadAction，页面导航使用 LaunchAction。
- [Android LE Audio 能力查询](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#isLeAudioSupported())：不把查询失败当成确定未连接。

## 构建、体积与验收

原有手工 `aapt2 -> javac -source 8 -target 8 -> d8 -> zipalign -> apksigner` 保留。源文件使用 UTF-8、显式匿名内部类，不引入 Gradle 或新的运行时库。

```powershell
.\scripts\build.bat
.\scripts\test-protocols.ps1
.\scripts\test-modules.ps1
.\scripts\build-compact.ps1
.\scripts\test-compact.ps1
```

精简步骤读取同一次普通构建的 class/JAR 产物，另外生成 `wearears-compact.apk`，不会覆盖 `wearears-debug.apk`。R8 关闭混淆及优化重写，仅裁掉未使用代码；保存 protobuf 反射字段和动态模块入口。普通版与精简版保持同一包名、版本和 debug 签名。具体最终体积与验证结果见本文件末尾的验收记录。

实机仍需确认：四风格首次进入/回前台锁定；从低音量拖过阈值及第二次解锁；磁贴连续点不同档、刷新后不收起；升级后旧布局缓存替换；蓝牙关闭、撤销权限、系统回收进程；无 ANC 设备；同品牌不同型号；中英文与圆屏/手机显示。没有设备测试时，不把编译通过写成“真机正常”。

### 最终验收记录（2026-11-16）

| 项目 | 结果 |
| --- | --- |
| 普通手工构建 | 六步完成，生成 `wearears-debug.apk` |
| 协议与状态回归 | 493 项通过；包括既有品牌解析、当前会话、磁贴点击去重、音量保护和音效报文 |
| 模块裁剪编译 | 8 种通过：no-drivers、no-battery、no-noise、no-volume、no-effects、no-panels、no-tiles、minimal |
| R8 后运行检查 | 四面板和九品牌模块入口可解析；型号匹配与 Xiaomi Buds 5 Pro 排除规则通过；Tile/Resources protobuf 序列化通过 |
| 精简 APK | 构建及签名校验通过，v3 签名有效 |
| 编码与语法 | 140 个 Java/XML 文件通过严格 UTF-8 检查；Java 源码未检出 lambda、方法引用、var 或 java.util.function |
| 差异检查 | `git diff --check` 通过 |

同一次源码构建的普通包为 **655,795 字节**，精简包为 **340,403 字节**，减少 **315,392 字节（48.1%）**。这衡量 APK 文件大小，不代表内存或续航降低同样比例。原有功能没有为此裁掉；八种裁剪组合仅用于验证模块边界。

用于识别本次产物的 SHA-256（后续新 UI 构建后会变化）：

```text
wearears-debug.apk
DD7820BCEE89C2745EE9DCB696FCC53875877E22BF93C65741AE4E9F73F47DA7

wearears-compact.apk
04DD740E832971191876465A7D117CB1601484215EE4E777193865A00A4C8AC8
```

构建仍有既有弃用 API / Java 8 bootstrap 提示，以及 R8 输入 JAR 的重复 `META-INF/MANIFEST.MF` 提示；本次均未阻断构建或回归。没有进行安装、真机操作、提交或推送；没有把另一位 AI 尚未完成的新 UI 纳入验收。
