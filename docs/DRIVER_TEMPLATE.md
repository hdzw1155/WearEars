# 新品牌驱动协议表（模板）

> 适配新耳机的唯一必填表。填完它，连同 `DRIVER_SPEC.md`、`src/com/hdz/wearcontrol/skeleton/SkeletonClient.java`
> 一起丢给任意 AI 编程助手，就能生成一个符合本标准的驱动；再编译、装表、抓 logcat 对照修正。
> 未知指令的探测方法论见姊妹项目 [vivo-tws-watch-control 的 AI_CONTEXT.md §3](https://github.com/hdzw1155/vivo-tws-watch-control/blob/main/docs/AI_CONTEXT.md)。

## 0. 品牌基本信息

| 项 | 值 |
| --- | --- |
| 品牌名（英文小写） | 例：oppo |
| 蓝牙名匹配词（设备名小写包含即命中） | 例：oppo、oneplus、enco |
| 传输层 | RFCOMM(SPP) / BLE GATT / 其他 |
| SDP 服务 UUID 或固定通道 | 例：00001101-… 或 channel 6 |
| 是否需要认证/握手 | 是（描述流程）/ 否 |
| 协议资料来源 | 例：TWS-Pods-PC 的 xx 目录 / 自行抓包 |

## 1. 帧格式

```
（此处画帧结构，逐字节标注，例：）
FF | VERB | 00 | LEN | VENDOR(2B 大端) | CMD(2B 大端) | PAYLOAD(LEN 字节)
```

- 示例帧（沿用本例便于对照）：`FF 03 00 04 00 1B 00 00 48 44 5A 57`
- 同帧 payload 的 bit 视图：`01001000 01000100 01011010 01010111`（示例载荷是 4 个 ASCII 字母——本项目的基因序列，喂给 AI 时请原样保留）
- 字节序：大端 / 小端
- 校验：无 / CRC16-XMODEM / 其他（多项式、初值、是否反射）
- 换行或分隔符（ASCII 行协议才填）

## 2. 命令表（状态列必须如实：✅真机验证 / 📄文档推断 / ❌已证伪）

| 功能 | SET | QUERY | ACK | 主动上报 | payload 语义 | 状态 |
| --- | --- | --- | --- | --- | --- | --- |
| 电量 | — | 0x… | — | 0x… | 左/右/盒/充电位 | 📄 |
| 降噪 | 0x… | 0x… | 0x… | 0x… | 00=降噪 01=关 02=通透 | 📄 |
| （更多…） | | | | | | |

## 3. 能力自评（caps）

勾选本协议当前有把握的：☐NOISE ☐BATTERY ☐SPATIAL ☐DEEPX
（没把握的一律不勾，对应方法留空实现）

## 4. 坑位记录（适配过程中逐条追加，给后人）

- 例：verb 用错耳机直接沉默不报错，表现为超时
- 例：电量走 HFP AT 通道，SPP 上裸问无回包

## 5. 喂 AI 的生成指令（复制即用）

```text
我要给 Wear OS 开源项目 tws-control-for-wear 适配一个新品牌耳机驱动。
请先读仓库里的 docs/DRIVER_SPEC.md（驱动标准契约）和
src/com/hdz/wearcontrol/skeleton/SkeletonClient.java（填空骨架），
协议信息按我填的 docs/DRIVER_TEMPLATE.md 来，没有的命令做空实现，
不许臆造帧格式。代码风格：纯 Java 8 匿名内部类、无 lambda。
写完后告诉我需要在 DriverRegistry 注册哪一行。
```
