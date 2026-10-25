# TWS Control for Wear

在 **Wear OS 手表**上直接控制各品牌 TWS 耳机——降噪切换、电量查询，不依赖手机。手表通过蓝牙直连耳机发送各厂商私有协议指令，耳机可同时保持与手机的音频连接。

vivo 单品牌版的老项目在 [vivo-tws-watch-control](https://github.com/hdzw1155/vivo-tws-watch-control)，本仓库是它的多品牌重构版。

- 个人免费开源，非官方工具
- 不收集、不上传任何数据
- 按"现状"提供，兼容性可能随耳机固件更新而变化
- **严禁商业倒卖**：仅供非商业学习交流，禁止打包收费或在闲鱼 / 淘宝等平台售卖

## 支持情况

| 品牌 | 传输 | 降噪 | 电量 | 验证状态 |
| --- | --- | --- | --- | --- |
| vivo / iQOO TWS 家族 | RFCOMM (GAIA) | 开/关/通透三态 | 左/右/盒 | ✅ 实测（Galaxy Watch7 + Air3 Pro 全功能） |
| 华为 FreeBuds / HONOR | RFCOMM (SPP) | 开/关（通透协议未公开） | 多数机型查不到（走 HFP 通道） | ⚠ 按协议文档实现，未实测 |
| 小米 / Redmi Buds | RFCOMM (MMA) | 开/关/通透三态 | 左/右/盒（含充电状态） | ⚠ 认证算法 36/36 离线验证，未上耳机实测 |

手表端：实测三星 Galaxy Watch7（Wear OS），其他 Wear OS 3+ 手表理论可用。

## 功能

- 降噪三态切换（当前模式高亮；华为机型暂不支持通透）
- 左/右耳 + 充电盒电量与充电状态
- 断线自动清空、自动重连
- vivo 机型：开发者设置里还有空间音频 / DeepX 音效实验功能（连点 5 下"开发者设置"开启，仅限 Air3 Pro，详见应用内免责声明）

## 架构

各品牌协议互相隔离，加新品牌只需实现一个类、注册一行：

```
TwsClient          统一接口(驱动标准: 连接/电量/降噪三态 + 能力位 + 验证等级)
├── vivo/VivoGaiaClient      GAIA 私有协议(真机验证)
├── huawei/HuaweiSppClient   SPP + CRC16/XMODEM 帧(未真机验证)
├── xiaomi/XiaomiMmaClient   MMA 帧 + 挑战-应答认证(未真机验证)
└── DriverRegistry           按蓝牙设备名自动挑品牌, 匹配规则内聚在各驱动里
```

UI 层只认 `TwsClient`：能力用 `caps()` 门控、成熟度用 `verifLevel()` 标注，代码里不出现任何品牌判断。

小米的认证算法（`xiaomi/XiaomiAuth.java`）是 `libxm_bluetooth.so` 逆向算法的独立 Java 移植，与公开的 36 组 Unicorn oracle 真值逐字节一致后才上表。

## 适配新耳机

抓到了新品牌的蓝牙协议（或想给现有协议补命令）？流程已经模板化：

1. 按模板填一张协议表：`docs/DRIVER_TEMPLATE.md`（帧格式 / 命令表 / 匹配词 / 坑位记录）
2. 把填好的表 + `docs/DRIVER_SPEC.md`（驱动标准契约）+ `src/.../skeleton/SkeletonClient.java`（填空骨架）交给任意 AI 编程助手，生成驱动
3. 在 `DriverRegistry` 注册一行，编译装表，logcat 抓原始帧对照修正

协议探测的方法论（探针轮换 / 官方 App 交叉对照 / 沉默≠不支持）见姊妹项目 [vivo-tws-watch-control 的 AI_CONTEXT.md](https://github.com/hdzw1155/vivo-tws-watch-control/blob/main/docs/AI_CONTEXT.md)。

## 安装

1. 手表先与耳机完成蓝牙配对（设置 → 连接 → 蓝牙）
2. 装 APK（任选）：
   - 电脑 `adb install -r tws-control-wear-debug.apk`
   - 手机装 **WearOS 工具箱** → 连接手表 → 安装本地 APK

## 构建

免 Gradle，Windows 下两条命令：`scripts\setup-env.bat`（首次装环境）→ `scripts\build.bat`（出 APK）。纯 Java 零第三方依赖。

## ⚠️ 免责

App 首次启动有确认弹窗，页面底部常驻免责声明。核心内容：

- 本项目为第三方非官方社区工具，仅供个人学习研究和技术交流，与 vivo / 华为 / 小米及其关联公司无任何关联，未获其授权或背书；商标归各自权利人所有，仅作兼容性说明
- 按现状提供，不提供任何明示或默示担保；私有协议可能随固件更新变更
- 实验性功能切换瞬间可能出现突发爆音、音量突变，操作前务必摘下耳机并调低音量，听力损伤不可逆；勿在驾驶、骑行等场景使用
- 使用风险（含听力不适、财产损失、固件异常、数据丢失、软件安全风险、保修失效、违反第三方条款）由使用者自行承担，法律强制不得排除的责任除外

如权利人认为本项目侵犯其合法权益，请联系作者，将及时处理。

## 🙏 致谢

协议知识全部来自社区公开逆向成果：

- **[Zhaoyi-ya/TWS-Pods-PC](https://github.com/Zhaoyi-ya/TWS-Pods-PC)** — vivo 全套协议文档与真机抓包；华为 SPP 协议记录（上游为 HuaweiPods 项目，GPL-3.0）；小米 MMA 协议与认证算法复刻（36 组真值）
- **[silverpoetry/HyperEars](https://github.com/silverpoetry/HyperEars)** — vivo/iQOO 家族协议画像与机型线格式配置
- **[Star-ZER0/Pods-Protocol-Reverse](https://github.com/Star-ZER0/Pods-Protocol-Reverse)** — vivo 公共协议画像
- 前作 [vivo-tws-watch-control](https://github.com/hdzw1155/vivo-tws-watch-control) 的全部实机调优经验

**关于华为部分与 GPL-3.0 的边界**：本项目的华为实现是阅读上述仓库的**协议描述文档**（帧格式 / CRC 算法 / 命令号）后独立编写的 Java 代码，未复制、未参考其 GPL 代码实现；协议规格本身属于功能性事实，不受版权保护。小米部分同理（认证算法为按公开算法描述的独立移植，并与公开真值对拍验证）。因此本项目整体保持 MIT 许可。

## 📢 授权与声明

- 本项目免费提供给个人用于学习、研究与非商业用途，欢迎 Fork 与 PR
- **严禁商业倒卖**：严禁将本项目源码或编译后的 APK 打包收费，或在闲鱼、淘宝、拼多多、付费社群及应用商店二次售卖、付费代装；不得抹除署名集成进闭源商业产品。如发现倒卖、冒充原创等行为，作者保留平台投诉（DMCA）等维权权利
- 参考或使用本项目代码请保留版权声明并注明来源；使用底层协议参数请一并致谢上述上游项目
- 欢迎厂商工程师参考采纳（按 MIT 直接引用即可），请在说明中保留来源署名

## 📄 许可证

[MIT](LICENSE)（附带上述使用声明）。零第三方依赖、零数据收集。
