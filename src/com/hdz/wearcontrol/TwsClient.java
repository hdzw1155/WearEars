package com.hdz.wearcontrol;

import android.bluetooth.BluetoothDevice;
import android.content.Context;

/**
 * 各品牌 TWS 耳机客户端的统一接口(驱动标准)。
 * UI 只面对这套接口: 能力用 caps() 判断、成熟度用 verifLevel() 判断,不许 instanceof 品牌。
 * 各品牌私有协议在对应实现类里消化。新品牌适配流程见 docs/DRIVER_SPEC.md 与
 * docs/DRIVER_TEMPLATE.md(填协议表喂 AI 即可生成驱动)。
 */
public interface TwsClient {

    /** UI 层降噪三态(与品牌线格式无关, 各实现自行映射) */
    int MODE_ANC = 0;
    int MODE_OFF = 1;
    int MODE_TRANS = 2;

    // ---- 能力位: 驱动自述, UI 用 (client.caps() & CAP_X) != 0 门控 ----
    int CAP_NOISE = 1;    // 降噪三态切换
    int CAP_BATTERY = 2;  // 三段电量
    int CAP_SPATIAL = 4;  // 空间音频开关(实验功能)
    int CAP_DEEPX = 8;    // 音效档位(实验功能)

    // ---- 验证等级: 如实标注, UI 据此挂"实验性支持·未验证" ----
    int VERIF_REAL = 0;     // 本项目真机验证过
    int VERIF_UNTESTED = 1; // 协议来自社区逆向文档, 未在本项目真机验证

    interface Listener {
        void onConnected(String deviceName);

        void onDisconnected(String reason);

        void onError(String msg);

        /** 电量; 0xFF(255) = 未上报; chargeFlags bit0/1/2 = 左/右/盒充电中 */
        void onBattery(int left, int right, int box, int chargeFlags);

        /** 降噪模式(MODE_* 常量); -1 = 未知/不支持查询 */
        void onNoiseMode(int mode);

        /** 空间音频状态; null = 未知/未确认(指令为实验性) */
        void onSpatial(Boolean state);

        /** DeepX 音效模式; null = 未知/未确认 */
        void onDeepX(Integer mode);
    }

    void connect(BluetoothDevice device);

    void disconnect();

    boolean isConnected();

    void addListener(Listener l);

    void removeListener(Listener l);

    /** 注入系统上下文(华为 HFP 电量通道等需要); 由创建方在 connect 前调用, 可能传 null */
    void init(Context ctx);

    void queryBattery();

    void queryNoise();

    /** 切换降噪三态; 不支持的模式由实现自行 onError 提示 */
    void setNoiseMode(int mode);

    // ---- 实验能力: 无此能力的驱动空实现即可, UI 已按 caps() 隐藏入口 ----

    void querySpatial();

    void setSpatial(boolean on);

    void queryDeepX();

    void setDeepX(int mode);

    /** 能力位组合 */
    int caps();

    /** 验证等级(VERIF_*) */
    int verifLevel();

    /** 品牌名, 用于界面展示与日志 */
    String brandName();
}
