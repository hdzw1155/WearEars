package com.hdz.wearcontrol;

import android.bluetooth.BluetoothDevice;

/**
 * 各品牌 TWS 耳机客户端的统一接口。
 * UI 只面对这套接口; 各品牌私有协议在对应实现类里消化。
 */
public interface TwsClient {

    /** UI 层降噪三态(与品牌线格式无关, 各实现自行映射) */
    int MODE_ANC = 0;
    int MODE_OFF = 1;
    int MODE_TRANS = 2;

    interface Listener {
        void onConnected(String deviceName);

        void onDisconnected(String reason);

        void onError(String msg);

        /** 电量; 0xFF(255) = 未上报; chargeFlags bit0/1/2 = 左/右/盒充电中 */
        void onBattery(int left, int right, int box, int chargeFlags);

        /** 降噪模式(MODE_* 常量); -1 = 未知/不支持查询 */
        void onNoiseMode(int mode);
    }

    void connect(BluetoothDevice device);

    void disconnect();

    boolean isConnected();

    void addListener(Listener l);

    void removeListener(Listener l);

    void queryBattery();

    void queryNoise();

    /** 切换降噪三态; 不支持的模式由实现自行 onError 提示 */
    void setNoiseMode(int mode);

    /** 品牌名, 用于界面展示与日志 */
    String brandName();
}
