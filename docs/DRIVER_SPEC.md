# TWS 驱动标准（DRIVER_SPEC）

> 给想给本项目适配新品牌耳机的人（或 AI）看的接口契约。
> 配套文档：`DRIVER_TEMPLATE.md`（新品牌协议表模板，填完喂 AI 即可生成驱动）。

## 1. 角色分工

```
MainActivity / UI 层
    │  只认 TwsClient 接口：caps() 判能力、verifLevel() 判成熟度，
    │  全工程禁止 instanceof 具体品牌类
    ▼
TwsClient（驱动标准接口）
    ▲
    │  实现：消化品牌私有协议（帧格式/命令号/认证/CRC）
各品牌驱动（vivo/ huawei/ xiaomi/ …）
```

新品牌适配 = **一个驱动类 + DriverRegistry 里一行注册**，UI、布局、表冠、省电策略全部复用。

## 2. 生命周期

1. `init(Context)`——创建方在 connect 前注入系统上下文；不需要系统服务的驱动空实现；必须容忍 `ctx == null`。
2. `addListener(Listener)`——订阅回调，支持多订阅者。
3. `connect(BluetoothDevice)`——内部自起线程，**不得阻塞调用方**；连上后自行完成握手/认证/首查，然后开始回调。
4. `disconnect()`——幂等；关闭 socket、注销系统服务代理（如 HFP）、停掉所有内部线程。
5. 重连策略归 UI 管，驱动不自作主张重连。

## 3. 能力位 caps()

| 位 | 含义 | UI 行为 |
| --- | --- | --- |
| CAP_NOISE | 降噪三态切换 | 显示三个模式按钮 |
| CAP_BATTERY | 三段电量 | 显示电量卡片 |
| CAP_SPATIAL | 空间音频开关 | 显示开发者区空间音频开关（实验） |
| CAP_DEEPX | 音效档位 | 显示开发者区音效按钮（实验） |

没有的能力：相关 query/set 方法做**空实现**即可，UI 已按 caps() 隐藏入口，不会调到。

## 4. 验证等级 verifLevel()

- `VERIF_REAL`：在本项目里真机验证过——UI 不加提示。
- `VERIF_UNTESTED`：协议来自社区逆向文档，未真机验证——UI 自动挂"（实验性支持·未验证）"。

诚实标注是硬要求：新驱动上线一律 `VERIF_UNTESTED`，直到有贡献者真机验证并更新
README 支持表后再改。

## 5. Listener 回调语义（7 个）

| 回调 | 线程 | 未知/不支持时的约定 |
| --- | --- | --- |
| onConnected(name) | 后台线程 | — |
| onDisconnected(reason) | 后台线程 | reason 说人话 |
| onError(msg) | 后台线程 | msg 说人话，带排查线索 |
| onBattery(l,r,box,flags) | 后台线程 | 未上报用 0xFF；flags bit0/1/2=左/右/盒充电中 |
| onNoiseMode(mode) | 后台线程 | 未知用 -1 |
| onSpatial(Boolean) | 后台线程 | 未知/解析失败用 null |
| onDeepX(Integer) | 后台线程 | 未知/解析失败用 null |

UI 侧自行切主线程；驱动侧回调前先查 closing 守卫（旧连接的残留帧不得上 UI）。

## 6. 实现守则（都是踩过坑换来的）

1. `socket`/`out` 等跨线程字段必须 `volatile`（连接线程写、UI 线程读）。
2. 读流错位时**单字节滑动重同步**找帧头，整块丢弃会漏真帧。
3. 未知帧全量打日志（hex），这是协议分析的 ground truth。
4. 不支持的操作（如华为通透）用 `onError` 明说，不要静默。
5. connect 失败先小睡 1~2 秒再回调：上一个连接刚死时耳机侧槽位要几秒才释放，
   立刻重试会陷入连-断循环。
6. 纯 Java 8、匿名内部类，禁 lambda（免 Gradle 构建链没有 core-lambda-stubs）。

## 7. 注册

在 `DriverRegistry` 里加一个 `DriverInfo`（brandName / matchWords / isFallback / create）
并放进 `DRIVERS` 数组。精确品牌词排前面，泛词（tws/air/buds 之类）只能做兜底殿后。
