package com.hdz.wearcontrol;

/** 当前协议会话的状态。历史快照不能写回这里；可独立于 Android 和磁贴进行验证。 */
final class DeviceState {
    static final int UNKNOWN = 0, SUPPORTED = 1, UNSUPPORTED = 2;
    String address = "", name = "";
    boolean connected;
    int caps, potentialCaps;
    int left = -1, right = -1, box = -1, charge = -1, mode = -1;
    Boolean spatial;
    Integer equalizer;
    int spatialRevision, equalizerRevision;
    SoundPreset[] presets = new SoundPreset[0];

    void begin(String address, String name, int caps, int potentialCaps) {
        if (this.address.equals(address) && (name == null || name.isEmpty())) name = this.name;
        disconnect();
        presets = new SoundPreset[0];
        this.address = address;
        this.name = name == null ? "" : name;
        capabilities(caps, potentialCaps);
    }

    void capabilities(int caps, int potentialCaps) {
        boolean changedTopology = ((this.caps ^ caps) & TwsClient.CAP_BATTERY_SINGLE) != 0;
        this.caps = caps;
        this.potentialCaps = potentialCaps | caps;
        if (changedTopology) left = right = box = charge = -1;
    }

    int capability(int cap) {
        if (connected && (caps & cap) != 0) return SUPPORTED;
        return (potentialCaps & cap) != 0 ? UNKNOWN : UNSUPPORTED;
    }

    void battery(int left, int right, int box, int charge) {
        if (!connected) return;
        // 分片报告只合并当前会话，重连后缺少的耳机仍显示未知。
        if (known(left)) this.left = left;
        if ((caps & TwsClient.CAP_BATTERY_SINGLE) == 0) {
            if (known(right)) this.right = right;
            if (known(box)) this.box = box;
            if (charge >= 0 && charge <= 7) this.charge = charge;
        }
    }

    void disconnect() {
        connected = false;
        caps = 0;
        left = right = box = charge = mode = -1;
        spatial = null;
        equalizer = null;
        spatialRevision = equalizerRevision = 0;
    }

    static boolean known(int value) { return value >= 0 && value <= 100; }
}
