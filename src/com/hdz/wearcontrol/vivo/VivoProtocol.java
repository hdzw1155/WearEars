package com.hdz.wearcontrol.vivo;

/** GAIA 已知响应的有效值校验；错误状态不能当作真实电量或功能状态发布。 */
public final class VivoProtocol {
    private VivoProtocol() { }

    public static int[] battery(byte[] payload) {
        if (payload == null || payload.length < 5 || payload[0] != 0) return null;
        int[] result = new int[4];
        for (int i = 0; i < 3; i++) {
            int value = payload[i + 1] & 255;
            if (value > 100 && value != 255) return null;
            result[i] = value;
        }
        int flags = payload[4] & 255;
        result[3] = flags <= 7 ? flags : -1;
        return result;
    }

    /** null=无效应答；-1=应答有效，但当前不属于界面的三个标准挡位。 */
    public static Integer noise(byte[] payload) {
        if (payload == null || payload.length < 2 || payload[0] != 0) return null;
        int mode = payload[1] & 255;
        return mode <= 2 ? mode : -1;
    }

    public static Boolean spatial(byte[] payload) {
        if (payload == null || payload.length == 0) return null;
        if (payload.length > 1 && payload[0] != 0) return null;
        int state = payload[payload.length - 1] & 255;
        return state <= 1 ? Boolean.valueOf(state == 1) : null;
    }

    public static Integer deepX(byte[] payload) {
        if (payload == null || payload.length < 2 || payload[0] != 0) return null;
        int mode = payload[1] & 255;
        return mode <= 3 || mode == 5 ? Integer.valueOf(mode) : null;
    }
}
