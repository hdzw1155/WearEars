# WearEars

在 Wear OS 手表或 Android 手机查看已配对耳机的状态，并在机型支持时切换降噪或音效。此仓是下一代底座的开发基线。应用标识为 `com.hdz.wearcontrol`，开发版本为 `0.5.0-dev`（versionCode 16），最低 Android 11（API 30），目标 API 34。

新进仓可先读 [底层架构与交接](docs/HANDOFF.md)，再用 [按问题查文件](docs/FILE_MAP.md) 定位代码和对应测试。九个品牌驱动均可从构建中裁剪，未知品牌只使用系统蓝牙状态。主界面与磁贴保留至上、新极简、新经典、新液位，兼容旧版保存的风格选项。

## 在 Windows 离线构建

准备 Python 3.9+ 的 `py` 启动器、JDK 17、Android SDK Platform 34 和 Build Tools 34.0.0。默认 JDK 根目录为 `%USERPROFILE%\android-tools\jdk-ms`，SDK 根目录为 `%LOCALAPPDATA%\Android\Sdk`。可分别用 `WEAREARS_JDK` 和 `ANDROID_SDK_ROOT` 指定。Wear Tiles 等依赖随仓附带。

```powershell
py -X utf8 build.py core
py -X utf8 build.py test
py -X utf8 build.py verify
```

`core` 运行核心规则测试。`test` 运行全部离线检查，包含需要 SDK 34 的平台适配层。`verify` 追加全量 APK 构建、签名和对齐验证。结果位于 `build/WearEars.apk`。仅打包或裁剪时使用以下命令。

```powershell
py -X utf8 build.py apk --drivers none
py -X utf8 build.py apk --drivers vivo,huawei
```

首次构建会创建仓库本地调试密钥 `.local/debug.p12`。覆盖已有安装需要相同签名。复测从旧版升级时须由持有旧版签名者自行签两个版本。可配置 `WEAREARS_KEYSTORE`、`WEAREARS_KEY_ALIAS`、`WEAREARS_STORE_PASS`，必要时设置 `WEAREARS_KEY_PASS`。这些值和密钥均保存在源码归档之外。

## 机型证据与使用须知

旧版项目记录过 vivo TWS Air3 Pro、华为 FreeBuds 6i / SE 4 ANC、HONOR Earbuds X9、Redmi Buds 6、OPPO Enco Air5 Pro / Enco Free4 和 NiceHCK OriG in。每款机型的功能范围取决于协议及实际回报。漫步者、弱水时砂、Sony MDR 与 Technics EAH-AZ 在此开发基线列为实验性。新底座当前的验收证据为离线回归、打包和签名验证，真机验证单见 [交接指南](docs/HANDOFF.md)。

应用首次安装及版本号更新后，请先在主界面阅读并确认使用须知。私有协议及实验性功能有设备异常和意外音量变化的风险。尝试实验性控制前请摘下耳机并调低音量。空间音频开关需另外确认，系统音量采用默认锁定和二次解锁。所有通信在本机与配对设备之间进行。

## 致谢与授权

项目继承 hdzw1155 原作的协议向量、机型经验和手表视觉资产。协议资料来源包括 TWS-Pods-PC、HyperEars、Pods-Protocol-Reverse、QuickBuds、SonyHeadphonesClient、redmi-buds-linux、OpenXia、omarchy-headphones、RoseLink-Protocol、rosebuds、NiceHCK_Controller 及其 Windows 实现。本项目的具体归属和上游说明见 [NOTICE](NOTICE)，源码许可见 [LICENSE](LICENSE)，AndroidX、Guava 与 Protocol Buffers 说明见 [离线依赖声明](vendor/NOTICE.md)。
