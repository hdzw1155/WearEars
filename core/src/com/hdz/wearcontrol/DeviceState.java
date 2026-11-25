package com.hdz.wearcontrol;

import java.util.EnumMap;

/** 当前协议会话的状态。历史快照不能写回这里；可独立于 Android 和磁贴进行验证。 */
final class DeviceState {
    static final int UNKNOWN = 0, SUPPORTED = 1, UNSUPPORTED = 2;
    static final int WEAR_UNKNOWN = -1, WEAR_NONE = 0, WEAR_LEFT = 1, WEAR_RIGHT = 2, WEAR_BOTH = 3;
    String address = "", name = "";
    boolean connected;
    boolean parked;
    long session;
    int caps, potentialCaps;
    int left = -1, right = -1, box = -1, charge = -1, mode = -1;
    Boolean spatial;
    Integer equalizer;
    int spatialRevision, equalizerRevision;
    SoundPreset[] presets = new SoundPreset[0];
    final EnumMap<FeatureId, FeatureDescriptor> features = new EnumMap<>(FeatureId.class);
    final EnumMap<FeatureId, Integer> featureValues = new EnumMap<>(FeatureId.class);
    final EnumMap<FeatureId, Integer> featureRevisions = new EnumMap<>(FeatureId.class);
    final EnumMap<FeatureId, String> featureTexts = new EnumMap<>(FeatureId.class);
    int wearStatus = WEAR_UNKNOWN;
    String firmware = "", limitation = "";

    void begin(String address, String name, int caps, int potentialCaps) {
        session++;
        address = address == null ? "" : address;
        if (this.address.equals(address) && (name == null || name.isEmpty())) name = this.name;
        disconnect();
        presets = new SoundPreset[0];
        this.address = address;
        this.name = name == null ? "" : name;
        capabilities(caps, potentialCaps);
    }

    void capabilities(int caps, int potentialCaps) {
        boolean changedTopology = ((this.caps ^ caps) & Controls.CAP_BATTERY_SINGLE) != 0;
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
        if ((caps & Controls.CAP_BATTERY_SINGLE) == 0) {
            if (known(right)) this.right = right;
            if (known(box)) this.box = box;
            if (charge >= 0 && charge <= 7) this.charge = charge;
        }
    }

    /** 手机按需短连接唤醒同一台耳机时保留快照，避免界面闪烁或清空。 */
    void resumeParked(String address, String name) {
        String safe = address == null ? "" : address;
        if (!parked || !this.address.equals(safe)) {
            begin(safe, name, 0, 0);
            return;
        }
        if (name != null && !name.isEmpty()) this.name = name;
    }

    void disconnect() {
        connected = false;
        parked = false;
        caps = 0;
        left = right = box = charge = mode = -1;
        spatial = null;
        equalizer = null;
        spatialRevision = equalizerRevision = 0;
        wearStatus = WEAR_UNKNOWN;
        firmware = "";
        limitation = "";
        features.clear();
        featureValues.clear();
        featureRevisions.clear();
        featureTexts.clear();
    }

    void featureSpec(FeatureDescriptor spec) {
        if (spec != null) features.put(spec.id, spec);
    }

    FeatureDescriptor featureSpec(FeatureId id) {
        return id == null ? null : features.get(id);
    }

    void featureValue(FeatureId id, int value) {
        if (id == null) return;
        featureValues.put(id, value);
        featureRevisions.put(id, featureRevision(id) + 1);
        if (id == FeatureId.WEAR_STATUS) {
            wearStatus = (value >= WEAR_UNKNOWN && value <= WEAR_BOTH) ? value : WEAR_UNKNOWN;
        } else if (id == FeatureId.SPATIAL_AUDIO) {
            spatial = value != 0;
            spatialRevision++;
        } else if (id == FeatureId.EQUALIZER) {
            equalizer = value;
            equalizerRevision++;
        } else if (id == FeatureId.NOISE_MODE && value >= -1 && value <= Controls.MODE_TRANS) {
            mode = value;
        }
    }

    void clearFeatureValue(FeatureId id) {
        if (id == null) return;
        featureValues.remove(id);
        featureRevisions.put(id, featureRevision(id) + 1);
    }

    Integer featureValue(FeatureId id) {
        return id == null ? null : featureValues.get(id);
    }

    int featureRevision(FeatureId id) {
        if (id == null) return 0;
        Integer rev = featureRevisions.get(id);
        return rev == null ? 0 : rev;
    }

    void featureText(FeatureId id, String text) {
        if (id == null) return;
        String safe = text == null ? "" : text;
        featureTexts.put(id, safe);
        if (id == FeatureId.FIRMWARE_INFO) firmware = safe;
    }

    String featureText(FeatureId id) {
        if (id == null) return "";
        String value = featureTexts.get(id);
        return value == null ? "" : value;
    }

    boolean isAnyBudInEar() {
        return wearStatus == WEAR_LEFT || wearStatus == WEAR_RIGHT || wearStatus == WEAR_BOTH;
    }

    boolean isTargetInEar(int findTarget) {
        if (wearStatus <= WEAR_NONE || findTarget <= 0) return false;
        return (wearStatus & (findTarget & WEAR_BOTH)) != 0;
    }

    boolean isFeatureWritable(FeatureId id) {
        if (!connected || id == null) return false;
        FeatureDescriptor spec = features.get(id);
        if (spec == null || spec.kind == FeatureDescriptor.Kind.READ_ONLY) return false;
        if (spec.kind == FeatureDescriptor.Kind.ACTION_TARGET || id == FeatureId.FIND_MY_BUDS) return true;
        if (spec.kind == FeatureDescriptor.Kind.CHOICE && spec.options.length > 0) {
            if (id == FeatureId.ANC_LEVEL || id == FeatureId.TRANS_MODE) {
                return (caps & Controls.CAP_NOISE) != 0 && spec.allowsNoiseMode(mode);
            }
            return true;
        }
        return featureValues.containsKey(id);
    }

    static boolean known(int value) { return value >= 0 && value <= 100; }
    static int lowComponent(int[] levels, int charge) {
        int lowest = -1;
        for (int i = 0; i < levels.length; i++) {
            if (!known(levels[i]) || levels[i] > 20 || charge >= 0 && (charge & (1 << i)) != 0) continue;
            if (lowest < 0 || levels[i] < levels[lowest]) lowest = i;
        }
        return lowest;
    }
}
