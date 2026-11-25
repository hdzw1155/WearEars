package com.hdz.wearcontrol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Sony MDR V1/V2 的帧、能力探测与扩展协议格式，未知子类型只读不写，绝不发 COMMAND_2 解绑指令。 */
final class SonyProtocol {
    static final int FUNC_EQ_PRESET = 0x51;
    static final int FUNC_EQ_EBB = 0x52;
    static final int FUNC_EQ_NONCUSTOM = 0x53;
    static final int FUNC_EQ_ERR = 0x54;
    static final int FUNC_EQ_10BAND = 0x58;
    static final int FUNC_EQ_10BAND_ALT = 0x59;
    static final int FUNC_ASM_15 = 0x68;
    static final int FUNC_ASM_17 = 0x6B;
    static final int FUNC_ASM_19 = 0x6D;
    static final int FUNC_UPSCALING_DSEE = 0xE2;
    static final int FUNC_PAUSE_ON_REMOVE = 0xF1;
    static final int FUNC_SPEAK_TO_CHAT_1 = 0xF2;
    static final int FUNC_SPEAK_TO_CHAT_2 = 0xFC;

    static final int TRANS_CHOICE_VOICE = 120;

    static final SoundPreset[] EQ_PRESETS = {
            new SoundPreset(0x00, "关闭", "Off"),
            new SoundPreset(0x10, "欢快", "Bright"),
            new SoundPreset(0x11, "激昂", "Excited"),
            new SoundPreset(0x12, "醇美", "Mellow"),
            new SoundPreset(0x13, "放松", "Relaxed"),
            new SoundPreset(0x14, "人声", "Vocal"),
            new SoundPreset(0x15, "高音增强", "Treble+"),
            new SoundPreset(0x16, "重低音", "Bass+"),
            new SoundPreset(0x17, "语音", "Speech"),
            new SoundPreset(0xA0, "手动", "Manual"),
            new SoundPreset(0xA1, "自定义 1", "Custom 1"),
            new SoundPreset(0xA2, "自定义 2", "Custom 2")
    };
    private static String normalizeName(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    static boolean matches(String name) {
        String n = normalizeName(name);
        return n.matches("(?:le)?(?:sony)?(wh1000xm[3456]|wf1000xm[3456]|whch720n|wfc700n|wfls900n).*");
    }

    /** 真机实测确认的 Sony 型号；其余候选型号继续标注实验性未验证。 */
    static boolean isVerifiedReal(String name) {
        return normalizeName(name).matches("(?:le)?(?:sony)?wf1000xm5.*");
    }

    static byte[] encode(int type, int sequence, byte[] payload) {
        if (payload == null || payload.length > 1024 || sequence < 0 || sequence > 1) throw new IllegalArgumentException("MDR frame");
        byte[] body = new byte[payload.length + 7];
        body[0] = (byte) type; body[1] = (byte) sequence;
        body[4] = (byte) (payload.length >> 8); body[5] = (byte) payload.length;
        System.arraycopy(payload, 0, body, 6, payload.length);
        int sum = 0;
        for (int i = 0; i < body.length - 1; i++) sum += body[i] & 255;
        body[body.length - 1] = (byte) sum;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x3E);
        for (byte value : body) {
            int b = value & 255;
            if (b >= 0x3C && b <= 0x3E) { output.write(0x3D); output.write(b & 0xEF); }
            else output.write(b);
        }
        output.write(0x3C);
        return output.toByteArray();
    }

    static final class Frame {
        final int type, sequence;
        final byte[] payload;
        Frame(int type, int sequence, byte[] payload) {
            this.type = type; this.sequence = sequence; this.payload = payload;
        }
    }

    static final class Decoder {
        private final byte[] buffer = new byte[1031];
        private int size;
        private boolean inside, escaped;
        List<Frame> feed(byte[] bytes) {
            List<Frame> result = new ArrayList<Frame>();
            if (bytes == null) return result;
            for (byte value : bytes) {
                int b = value & 255;
                if (b == 0x3E) { inside = true; escaped = false; size = 0; continue; }
                if (!inside) continue;
                if (b == 0x3C) {
                    if (!escaped && size >= 7 && buffer[2] == 0 && buffer[3] == 0) {
                        int length = ((buffer[4] & 255) << 8) | (buffer[5] & 255);
                        int check = 0;
                        for (int i = 0; i < size - 1; i++) check += buffer[i] & 255;
                        int type = buffer[0] & 255, seq = buffer[1] & 255;
                        if (length == size - 7 && seq <= 1 && (check & 255) == (buffer[size - 1] & 255)
                                && (type == 1 || type == 12 || type == 14) && (type != 1 || length == 0)) {
                            result.add(new Frame(type, seq, Arrays.copyOfRange(buffer, 6, size - 1)));
                        }
                    }
                    inside = false;
                    continue;
                }
                if (escaped) {
                    if (b < 0x2C || b > 0x2E) { inside = false; continue; }
                    b |= 0x10; escaped = false;
                } else if (b == 0x3D) { escaped = true; continue; }
                if (size == buffer.length) { inside = false; continue; }
                buffer[size++] = (byte) b;
            }
            return result;
        }
    }

    static int generation(byte[] payload) {
        if (payload == null || payload.length < 2 || payload[0] != 1 || payload[1] != 0) return 0;
        return payload.length == 4 ? 1 : payload.length == 8 ? 2 : 0;
    }

    /** 已验证的字段：左/右/盒、充电位；单电池也放在左槽，由能力位决定显示方式。 */
    static int[] battery(int generation, byte[] p) {
        if (p == null || p.length < 2) return null;
        int command = p[0] & 255;
        if (generation == 1 ? command != 0x11 && command != 0x13 : command != 0x23 && command != 0x25) return null;
        int type = p[1] & 255;
        if (type > 2 || p.length != (type == 1 ? 6 : 4)) return null;
        int[] result = {255, 255, 255, 0};
        for (int i = 0; i < (type == 1 ? 2 : 1); i++) {
            int level = p[2 + i * 2] & 255, charging = p[3 + i * 2] & 255;
            if (level == 255) continue;
            if (level > 100 || charging > (generation == 1 ? 1 : 3)) return null;
            int slot = type == 2 ? 2 : i;
            result[slot] = level;
            if (charging == 1) result[3] |= 1 << slot;
        }
        return result;
    }

    static int mode(int generation, byte[] p) {
        if (p == null || p.length < 2 || (p[0] != 0x67 && p[0] != 0x69)) return -1;
        if (generation == 1) {
            if (p.length != 8 || p[1] != 2 || (p[2] & 255) > 1 || (p[3] & 255) > 2
                    || (p[5] & 255) > 1 || (p[6] & 255) > 1 || (p[7] & 255) > 20) return -1;
            if ((p[4] & 255) > (p[3] == 2 ? 2 : 1)) return -1;
            if (p[2] == 0) return Controls.MODE_OFF;
            if (p[4] != 0) return Controls.MODE_ANC;
            return p[7] != 0 ? Controls.MODE_TRANS : Controls.MODE_OFF;
        }
        int type = p[1] & 255;
        int length = type == 0x17 ? 7 : type == 0x15 ? 8 : type == 0x19 ? 9 : 0;
        if (length == 0 || p.length != length || (p[2] & 255) > 1 || (p[3] & 255) > 1
                || (p[4] & 255) > 1) return -1;
        int focus = type == 0x15 ? 6 : 5;
        if ((p[focus] & 255) > 1 || (p[focus + 1] & 255) > 20) return -1;
        if (type == 0x15) {
            int nc = p[5] & 255;
            if (nc > 2 && nc != 3 && nc != 5) return -1;
        }
        if (type == 0x19 && ((p[7] & 255) > 1 || (p[8] & 255) > 2)) return -1;
        return p[3] == 0 ? Controls.MODE_OFF : p[4] == 0 ? Controls.MODE_ANC : Controls.MODE_TRANS;
    }

    /** 保留已回报的通透等级、语音及附加字段，只改三态所需字段。 */
    static byte[] noiseCommand(int generation, byte[] confirmed, int target) {
        if (confirmed == null || mode(generation, confirmed) < 0 || target < 0 || target > 2) return null;
        byte[] p = confirmed.clone();
        p[0] = 0x68;
        if (generation == 1) {
            p[2] = (byte) (target == Controls.MODE_OFF ? 0 : 1);
            p[4] = (byte) (target == Controls.MODE_ANC ? (p[3] == 2 ? 2 : 1) : 0);
            p[7] = (byte) (target == Controls.MODE_TRANS ? (p[5] == 0 ? 1 : Math.max(1, p[7] & 255)) : 0);
        } else {
            p[2] = 1;
            p[3] = (byte) (target == Controls.MODE_OFF ? 0 : 1);
            p[4] = (byte) (target == Controls.MODE_TRANS ? 1 : 0);
        }
        return p;
    }

    static byte[] capabilityQuery() {
        return new byte[]{0x06, 0x00};
    }

    /** 解析 07 00 <cnt> [<funcId> <prio>]... 能力列表。 */
    static int[] capabilities(byte[] p) {
        if (p == null || p.length < 3 || p[0] != 0x07 || p[1] != 0x00) return null;
        int count = p[2] & 255;
        if (p.length < 3 + count * 2) return null;
        int[] funcs = new int[count];
        for (int i = 0; i < count; i++) funcs[i] = p[3 + i * 2] & 255;
        return funcs;
    }

    static int eqInquiryForCapabilities(int generation, boolean[] supported) {
        if (supported == null) return -1;
        if (generation == 1) {
            return (supported[FUNC_EQ_PRESET] || supported[FUNC_EQ_EBB] || supported[FUNC_EQ_NONCUSTOM]) ? 0x01 : -1;
        }
        if (supported[FUNC_EQ_PRESET]) return 0x00;
        if (supported[FUNC_EQ_NONCUSTOM]) return 0x02;
        if (supported[FUNC_EQ_ERR]) return 0x04;
        if (supported[FUNC_EQ_10BAND] || supported[FUNC_EQ_10BAND_ALT]) return 0x31;
        if (supported[FUNC_EQ_EBB]) return 0x01;
        return -1;
    }

    static int ambientLevel(int generation, byte[] p) {
        if (mode(generation, p) < 0) return -1;
        if (generation == 1) return p[7] & 255;
        int focus = (p[1] & 255) == 0x15 ? 6 : 5;
        return p[focus + 1] & 255;
    }

    static boolean focusOnVoice(int generation, byte[] p) {
        if (mode(generation, p) < 0) return false;
        if (generation == 1) return (p[6] & 255) == 1;
        int focus = (p[1] & 255) == 0x15 ? 6 : 5;
        return (p[focus] & 255) == 1;
    }

    static int transChoice(int generation, byte[] p) {
        int lvl = ambientLevel(generation, p);
        if (lvl < 0) return -1;
        if (focusOnVoice(generation, p)) return TRANS_CHOICE_VOICE;
        if (lvl <= 7) return 5;
        if (lvl <= 12) return 10;
        if (lvl <= 17) return 15;
        return 20;
    }

    static byte[] ambientCommand(int generation, byte[] confirmed, int level, boolean voice) {
        if (confirmed == null || mode(generation, confirmed) < 0 || level < 1 || level > 20) return null;
        byte[] p = confirmed.clone();
        p[0] = 0x68;
        if (generation == 1) {
            p[2] = 1;
            p[4] = 0;
            p[6] = (byte) (voice ? 1 : 0);
            p[7] = (byte) level;
        } else {
            int focus = (p[1] & 255) == 0x15 ? 6 : 5;
            p[2] = 1;
            p[3] = 1;
            p[4] = 1;
            p[focus] = (byte) (voice ? 1 : 0);
            p[focus + 1] = (byte) level;
        }
        return p;
    }

    static byte[] transChoiceCommand(int generation, byte[] confirmed, int choice) {
        if (confirmed == null || mode(generation, confirmed) < 0) return null;
        if (choice == TRANS_CHOICE_VOICE) {
            int cur = ambientLevel(generation, confirmed);
            int level = (cur >= 5 && cur <= 20) ? cur : 20;
            return ambientCommand(generation, confirmed, level, true);
        }
        if (choice == 5 || choice == 10 || choice == 15 || choice == 20) {
            return ambientCommand(generation, confirmed, choice, false);
        }
        return null;
    }

    static boolean supportsWind(int generation, byte[] p) {
        if (mode(generation, p) < 0) return false;
        if (generation == 1) return p.length == 8 && (p[1] & 255) == 0x02 && (p[3] & 255) == 0x02;
        return p.length == 8 && (p[1] & 255) == 0x15;
    }

    static boolean isWindActive(int generation, byte[] p) {
        if (!supportsWind(generation, p) || mode(generation, p) != Controls.MODE_ANC) return false;
        if (generation == 1) return (p[4] & 255) == 1;
        int nc = p[5] & 255;
        return nc == 3 || nc == 5;
    }

    static int ancLevelValue(int generation, byte[] p) {
        if (!supportsWind(generation, p)) return -1;
        boolean wind = isWindActive(generation, p);
        if (generation == 1) return wind ? 1 : 2;
        return wind ? 3 : 2;
    }

    static byte[] windCommand(int generation, byte[] confirmed, boolean wind) {
        if (!supportsWind(generation, confirmed)) return null;
        byte[] p = confirmed.clone();
        p[0] = 0x68;
        if (generation == 1) {
            p[2] = 1;
            p[4] = (byte) (wind ? 1 : 2);
            p[7] = 0;
        } else {
            p[2] = 1;
            p[3] = 1;
            p[4] = 0;
            p[5] = (byte) (wind ? 3 : 2);
        }
        return p;
    }

    static byte[] dseeQuery(int generation) {
        return generation == 1 ? new byte[]{(byte) 0xE6, 0x02} : new byte[]{(byte) 0xE6, 0x01};
    }

    static byte[] dseeCommand(int generation, boolean on) {
        if (generation == 1) return new byte[]{(byte) 0xE8, 0x02, 0x00, (byte) (on ? 1 : 0)};
        return new byte[]{(byte) 0xE8, 0x01, (byte) (on ? 1 : 0)};
    }

    static Boolean dsee(int generation, byte[] p) {
        if (p == null || p.length < 3) return null;
        int cmd = p[0] & 255;
        if (cmd != 0xE7 && cmd != 0xE9) return null;
        if (generation == 1) {
            if (p.length >= 4 && p[1] == 0x02 && p[2] == 0x00 && (p[3] == 0 || p[3] == 1)) {
                return p[3] != 0;
            }
            return null;
        }
        if (p[1] == 0x01 && (p[2] == 0 || p[2] == 1)) return p[2] != 0;
        return null;
    }

    static boolean isKnownEqPreset(int preset) {
        for (SoundPreset sp : EQ_PRESETS) if (sp.id == preset) return true;
        return false;
    }

    static byte[] eqQuery(int inquiryType) {
        return new byte[]{0x56, (byte) inquiryType};
    }

    static byte[] eqCommand(int inquiryType, int preset) {
        if (!isKnownEqPreset(preset)) return null;
        return new byte[]{0x58, (byte) inquiryType, (byte) preset, 0x00};
    }

    /** 返回 {inquiryType, presetId} 或 null。 */
    static int[] eqState(byte[] p) {
        if (p == null || p.length < 3) return null;
        int cmd = p[0] & 255;
        if (cmd != 0x57 && cmd != 0x59) return null;
        int inq = p[1] & 255;
        int preset = p[2] & 255;
        if (!isKnownEqPreset(preset)) return null;
        return new int[]{inq, preset};
    }

    static byte[] speakToChatQuery(int generation) {
        return generation == 1 ? new byte[]{(byte) 0xF6, 0x05} : new byte[]{(byte) 0xF6, 0x0C};
    }

    /** V2 Speak-to-Chat 极性反转: 0=ON, 1=OFF; V1 正常: 1=ON, 0=OFF。 */
    static byte[] speakToChatCommand(int generation, boolean on) {
        if (generation == 1) return new byte[]{(byte) 0xF8, 0x05, 0x01, (byte) (on ? 1 : 0)};
        return new byte[]{(byte) 0xF8, 0x0C, (byte) (on ? 0 : 1), 0x01};
    }

    static Boolean speakToChat(int generation, byte[] p) {
        if (p == null || p.length < 3) return null;
        int cmd = p[0] & 255;
        if (cmd != 0xF7 && cmd != 0xF9) return null;
        if (generation == 1) {
            if (p.length >= 4 && p[1] == 0x05 && p[2] == 0x01 && (p[3] == 0 || p[3] == 1)) {
                return p[3] == 1;
            }
            return null;
        }
        if (p[1] == 0x0C && (p[2] == 0 || p[2] == 1)) return p[2] == 0;
        return null;
    }

    static byte[] wearDetectQuery(int generation) {
        return generation == 1 ? new byte[]{(byte) 0xF6, 0x03} : new byte[]{(byte) 0xF6, 0x01};
    }

    /** V2 Pause-on-Remove 极性反转: 0=ON, 1=OFF; V1 正常: 1=ON, 0=OFF。 */
    static byte[] wearDetectCommand(int generation, boolean on) {
        if (generation == 1) return new byte[]{(byte) 0xF8, 0x03, 0x00, (byte) (on ? 1 : 0)};
        return new byte[]{(byte) 0xF8, 0x01, (byte) (on ? 0 : 1)};
    }

    static Boolean wearDetect(int generation, byte[] p) {
        if (p == null || p.length < 3) return null;
        int cmd = p[0] & 255;
        if (cmd != 0xF7 && cmd != 0xF9) return null;
        if (generation == 1) {
            if (p.length >= 4 && p[1] == 0x03 && p[2] == 0x00 && (p[3] == 0 || p[3] == 1)) {
                return p[3] == 1;
            }
            return null;
        }
        if (p[1] == 0x01 && (p[2] == 0 || p[2] == 1)) return p[2] == 0;
        return null;
    }

    static byte[] codecQuery(int generation) {
        return generation == 1 ? new byte[]{0x18, 0x00} : new byte[]{0x12, 0x02};
    }

    static String codec(int generation, byte[] p) {
        if (p == null || p.length < 3) return null;
        int cmd = p[0] & 255;
        int inq = p[1] & 255;
        boolean match = generation == 1
                ? ((cmd == 0x19 || cmd == 0x1B) && inq == 0x00)
                : ((cmd == 0x13 || cmd == 0x15) && inq == 0x02);
        if (!match) return null;
        switch (p[2] & 255) {
            case 0x01: return "SBC";
            case 0x02: return "AAC";
            case 0x10: return "LDAC";
            case 0x20: return "aptX";
            case 0x21: return "aptX HD";
            case 0x30: return "LC3";
            default: return null;
        }
    }

    static String firmware(byte[] p) {
        if (p == null || p.length < 4 || p[0] != 0x05 || p[1] != 0x02) return null;
        int len = p[2] & 255;
        if (len == 0 || p.length < 3 + len) return null;
        String text = new String(p, 3, len, StandardCharsets.US_ASCII).replace("\0", "").trim();
        return text.isEmpty() ? null : text;
    }
}
