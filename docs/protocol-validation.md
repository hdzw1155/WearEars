# 设备协议验证记录（2026-11-07）

本轮在 0.4.0 本地工作区处理设备协议，保留 0.3.5 的三套外观、设置页样式、表圈滚动、音量控制和磁贴即时目标高亮。新增协议均为实验性，未在 WearEars 上完成耳机实测。公开项目的实测记录是实现依据，不等于本项目实测通过。

## 已修正

| 范围 | 修正及结果 |
| --- | --- |
| 漫步者 | 修正 V1 / V2 发包多余字节；校验长度与校验和，保留分片；支持已知明文 FitBuds Turbo 与 XOR 方言。F2 优先于 D0，避免稍晚收到整机值后错误切成单电池。只解释证据充分的左右耳字段。BLE 等通知订阅成功后才查询。 |
| 小米 / Redmi | 校验认证应答、序号及 E21 结果，错误认证不进入就绪状态；保留分片魔数；处理 F4 状态通知；FF 不再当作充电电量。Buds 5 无认证读取，Buds 3 Pro 使用认证 app 4，6 Play / 6 Lite 使用原认证 app 1。Buds 5 / 3 Pro 用配置 11 写模式，6 Lite 用 TARGET_INFO 写模式。 |
| 弱水时砂 | 修正 V2 多余零字节及误加查询参数；按型号选择 RS / V2，取消超时后猜帧族；修复半包丢失、SPP 直到退出读循环才通知连接的错误。BLE 先订阅再发查询，完整查询分片发送；删除驱动内部重复心跳，由连接控制器统一轮询。 |
| 原道 | 有界缓冲处理杂乱数据与非法长度，保留半包；主动退出后取消延时任务；保留已实测的 OriG in 指令及模式映射。 |
| OPPO / 一加 / realme | 长度限制在当前帧内，异常 payload 不再越界读到下一包；拒绝超长 LEB128、截断电量列表和异常返回状态。保留旧 ANC 反馈位 0x02，同时接受 Enco Air3 Pro 实测位 0x10；不再从任意包尾猜模式。 |
| 华为 | 未知降噪枚举不再被解释成“关闭”；其余原协议与模式参数保留。 |
| 磁贴与动态能力 | 新连接仍在确认降噪能力时保留操作目标；获得合法模式后再发送。电量回包不能独自确认一次降噪切换成功。 |

所有解析器的内存都有上限。协议回执决定确认状态，ACK 和写入成功均不直接当作模式切换成功。

## 型号与功能边界

| 驱动 | 当前边界 |
| --- | --- |
| Edifier BES | 仍按既有品牌候选查找 SPP / BLE；仅合法电量 / 模式回报确认方言。D0 是整机电量；F2 目前仅解释左右耳。未知 group 不写；抗风噪不冒充三态之一。 |
| Xiaomi RCSP | 明确区分 Redmi Buds 5、3 Pro、6 Lite。6 Play / 6 Active 不开放 ANC；其他 RCSP 候选须先认证并返回有效能力和模式。Xiaomi Buds 5 Pro 是另一套 Compact GAIA，暂走系统电量，未宣称全小米或 FE95 BLE 适配。 |
| Rose RS | OpenFun、OpenLink、OpenFree / OpenFeel、SportFree、BudsFree / BudsFree i。开放式 Open / Sport 型号不显示 ANC。 |
| Rose V2 | CERAMICS-U（含 HSC-2710）、CERAMICS-MK2（含 5736 / 8912f）、BudsFeel MK2、Astro X、OpenFeel L / MK2、SportFeel-8912f。名字先规范化并去掉 ROSE / ROSESELSA / Ruoshui 前缀，再精确匹配；有效状态决定能力。 |
| Rose 其他 | CERAMICS / X、AGATE 等 F8，ZT，以及无法区分芯片版本的 SportFeel 暂走系统电量；不把品牌名等同于协议兼容。 |
| Sony | 候选为 WH-1000XM3/4/5/6、WF-1000XM3/4/5/6、WH-CH720N、WF-C700N、WF-LS900N。按版本回包选择 V1 / V2；只开放下述已实现格式，其他功能保持不可用。名称候选不代表逐型号实测。 |
| Technics | 候选为 AZ40、AZ40M2、AZ60、AZ60M2、AZ70W、AZ80、AZ100，允许 Technics / EAH 前缀。电量、三态分别经合法回包确认；不包括 EAH-A800 或其他 Panasonic 耳机。 |

修改蓝牙名称、未收录的型号别名和新固件可能使匹配失败。需要具体型号及真实收发记录才能扩大边界，不能据品牌名推断所有协议一致。

## 新驱动协议表

| 项目 | Sony MDR | Technics RACE |
| --- | --- | --- |
| 传输 | RFCOMM；V1 UUID 96cc203e-5068-46ad-b32d-e316f5e069ba，V2 UUID 956c7b26-d49a-4ba8-b03f-b17d393cb6e2 | RFCOMM；00000000-0000-0000-0099-aabbccddeeff，失败尝试标准 SPP |
| 建链 | 查询 00 00，合法版本响应的长度区分 V1 / V2 | 直接查询 000A 与电量 |
| 帧 | 3E / 3C 包围；类型、交替序号、4 字节大端长度、载荷、加和校验；3C/3D/3E 用 3D 转义 | 05、类型、2 字节小端长度、2 字节 RACE ID、载荷 |
| 确认 | 数据包回复相反序号 ACK；单条在途指令，最多发送 3 次 | 写序列间隔 120 ms；只用 000A 读回确认模式 |
| 电量 | V1 查询 10、回报 11/13；V2 查询 22、回报 23/25；子类型 0 整机、1 左右、2 盒 | 0CD6 的 5D 通知区分右/左，0040 的 5B 响应为盒；短 ACK 不当作电量 |
| 三态 | 66 查询、67/69 回报、68 写。V1 仅子类型 02；V2 仅 15/17/19 的完整已知格式 | 000A 返回成功状态、模式与两个合法等级后开放三态；写 0068 + 000B，按目标追加 0039 或前置 0022 |
| 保留字段 | Sony 写入从已确认状态复制，保留可用的通透等级、语音与附加字段 | 使用参考序列的默认 ANC 100 / 通透 50 |
| 未实现 | 纯环境声、自动模式、EQ、固件、其他 MDR 子类型 | 自适应 ANC UI、游戏模式、等级调节 UI、A800 |

RfcommClient 共用传输与会话取消，不连接音频路由、不注册新后台扫描、不增加周期任务。新类均已被 DriverRegistry 使用，没有未启用页面或实验视图。

## 验证方法

在项目根目录执行：

```powershell
.\scripts\build.bat
.\scripts\test-protocols.ps1
```

第一条沿用原手工 aapt2 / javac / d8 / zipalign / apksigner 流程，输出 wearears-debug.apk。第二条使用已构建的 TwsClient 常量及本地 JDK，运行纯 Java 离线协议检查，不连接蓝牙。

本轮 260 项检查覆盖公开帧真值、每个分片边界、拼包、坏校验、错误长度、噪声前缀、转义、非法模式、电量缺失与 0%、认证结果与型号分流。Java 8 编译通过，无 Lambda / 方法引用 / java.util.function。

构建与这些检查不能验证实际 BluetoothGatt / RFCOMM 调度、耳机固件行为和 Wear OS 磁贴宿主。没有安装到手表，没有续航测试，没有提交或推送 Git。真机仍应确认：首次打开同步、手机占用控制通道时的失败恢复、连接中退出、磁贴连续切换、耳机按键修改模式、标准电量回退，以及三套旧风格的显示。

## 原始资料

以下只用于协议事实、字段和公开报文对照；未把外部应用源代码、固件、图片或 APK 加入本项目。

- [Edifier BES 协议与抓包](https://github.com/silverpoetry/HyperEars/blob/127a0acace002f69f640d4ec95c13371b613cf76/docs/edifier-bes-protocol.md)，配合 [Edifier V1 文档](https://github.com/GlitchPunkWTF/edifier-proto)。
- [Redmi Buds 6 Play / 6 Lite 协议](https://github.com/creeperkafasi/redmi-buds-linux/blob/e8a9b48b7c15cd4a9dec2fc9d1779a7a1bafad6a/PROTOCOL.md)。
- [Redmi Buds 5 / 3 Pro、Sony WH-CH720N、Enco Air3 Pro 实际通信记录](https://github.com/ncr/omarchy-headphones/blob/main/PROTOCOL.md)，读取日期 2026-11-07。
- [RoseLink 协议 1.0](https://github.com/IRDecoyFlare/roseselsa-RoseLink-Protocol/blob/main/RoseLink_Communication_Protocol_en.md)，文档整理日期 2026-10-22，读取日期 2026-11-07。
- [Sony MDR V1 / V2 字段定义](https://github.com/mos9527/SonyHeadphonesClient/tree/2edf09098db27642c89e533bf86c0a6b162f6ad4/libmdr)，与 WH-CH720N 抓包交叉核对。
- [Technics EAH-AZ RACE 记录](https://github.com/silverpoetry/HyperEars/blob/127a0acace002f69f640d4ec95c13371b613cf76/docs/technics-race-protocol.md)，其参考实现版本为 [TechincsPods a378106](https://github.com/MartinKayJr/TechincsPods/tree/a378106659d4a90ed7ac28a4ac94d592bebbec85)。
