package com.hdz.wearcontrol;

/** Confirmed capabilities and protocol evidence for one device. */
public interface Controls {
    /** UI 层降噪三态(与品牌线格式无关, 各实现自行映射) */
    int MODE_ANC = 0;
    int MODE_OFF = 1;
    int MODE_TRANS = 2;

    // ---- 能力位: 驱动自述, UI 用 (client.caps() & CAP_X) != 0 门控 ----
    int CAP_NOISE = 1;    // 降噪三态切换
    int CAP_BATTERY = 2;  // 三段电量
    int CAP_BATTERY_SINGLE = 16;  // 只有整机总电量(标准协议/系统聚合), UI 换成单电量显示
    int CAP_SPATIAL = 4;  // 空间音频开关(实验功能)
    /** DeepX 音效(仅 vivo 系) */
    int CAP_DEEPX = 8;
    int CAP_EQUALIZER = 32;

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
        default void onEqualizer(Integer mode) { }
        default void onFeatureSpec(FeatureDescriptor spec) { }
        default void onFeatureValue(FeatureId id, int value) { }
        default void onFeatureText(FeatureId id, String text) { }
    }

    void queryBattery();

    void queryNoise();

    /** 切换降噪三态; 不支持的模式由实现自行 onError 提示 */
    void setNoiseMode(int mode);

    // ---- 实验能力: 无此能力的驱动空实现即可, UI 已按 caps() 隐藏入口 ----

    void querySpatial();

    void setSpatial(boolean on);

    void queryDeepX();

    void setDeepX(int mode);

    default SoundPreset[] equalizerPresets() { return new SoundPreset[0]; }
    default void queryEqualizer() { }
    default void setEqualizer(int preset) { }

    default FeatureDescriptor[] featureDescriptors() { return new FeatureDescriptor[0]; }
    default void queryFeature(FeatureId id) { }
    default void writeFeature(FeatureId id, int value, Runnable flushed) { }

    /** 能力位组合 */
    int caps();

    /** 可查询的能力范围。只用于保留等待确认的 UI，写命令仍以 caps() 为准。 */
    default int potentialCaps() { return caps(); }

    /** 验证等级(VERIF_*) */
    int verifLevel();

    /** 品牌名, 用于界面展示与日志 */
    String brandName();
}
