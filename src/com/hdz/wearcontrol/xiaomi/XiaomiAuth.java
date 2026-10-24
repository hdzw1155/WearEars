package com.hdz.wearcontrol.xiaomi;

/**
 * 小米 MMA 认证算法 E21 的 Java 移植。
 * 源: libxm_bluetooth.so (arm64) 逆向的 function_E21 / JNI getEncryptedAuthCheckData,
 * 算法与数据表(SBOX/T1/T2/linkKey)逐字节复刻自 TWS-Pods-PC 项目的公开逆向成果,
 * 该复刻经 36 组 Unicorn oracle 真值逐字节验证。
 * 本类零 Android 依赖,可在 PC 上独立测试。
 */
public final class XiaomiAuth {

    private XiaomiAuth() {
    }

    // .so 内嵌数据表 (file offset: SBOX=0x970 T1=0xa70 T2=0xb70 linkKey=0x26b0)
    private static final String SBOX_HEX =
            "64ac285ac9b337c50a10b7a3bab197463d05dc666ef69af80d589567c6aaabec"
                    + "a0689b96d4ebbf434936e96a89d8c38a946399bc7bbec122bb5c71d51f92575d"
                    + "8f44411d51e64017fbfd193234b8612aca236fda39f7a2017fd631e7de8004dd"
                    + "2c5982afa8e00fcda1123e30d11cd03a33722e4f9002130675ce87c2efb2ad7d"
                    + "3815e1529f7a6c2f27c4e281a9cf8dc0d7dfff6076148c5e5509e408c74220fc"
                    + "d25091d94c629ee8b9a6f91a00210bfa359c4e4b6948cb0ec8a45bea8407b418"
                    + "f4ae6bdba7cc3f8b4a0c3c25e5544d4583ed11f0b05393f27426b59d6d7cf32d"
                    + "f156247e471b86bd708e1e3b731603b6ac285ac9b337c50a10b7a3bab1974688";
    private static final String T1_HEX =
            "012de293be4515ae780387a4b838cf3f08670994eb26a86bbd18341bbbbf72f7"
                    + "4035489c512f3b55e3c09fd8d3f38db1ffa73edc8677d7a611fbf4ba92916483"
                    + "f133efda2cb5b22b88d199cb8c841d14819771ca5fa38b573c82c4525c1ce8a0"
                    + "04b4854af61354b6df0c1a8edee039fc209b244ea9989eabf260d06ceafac7d9"
                    + "00d41f6e43bcec5389fe7a5d49c932c2f99af86d16db599644e9cde646428f0a"
                    + "c1ccb965b0d2c6ac1e4162292e0e7450025ac3257b8a2a5bf0060d476f709d7e"
                    + "10ce1227d54c4fd679306836757de4ed806a9037a25e76aac57f3dafa5e51961"
                    + "fd4d7cb70beead4b22f5e7732321c805e166ddb3586963560fa1319517073a28";
    private static final String T2_HEX =
            "8000b00960efb9fd10129fe469baadf8c038c2654f0694fc19de6a1b5d4ea882"
                    + "70ede8ec72b315c3ffabb6474401ac25c9fa8e411a21cbd30d6efe2658da320f"
                    + "20a99d8498059cbb228c63e7c5e173c6af245b876627f757f496b1b75c8bd554"
                    + "79dfaaf63ea3f111caf5d1177b9383bcbd521eebaeccd63508c88ab4e2cdbfd9"
                    + "d050593f4d62340a4888b5564c2e6b9ed23d3c0313fb9751754a917123be762a"
                    + "5ff9d4550bdc37311674d777a7e607dba42f46f3614567e30ca23b1c8518041d"
                    + "29a08fb25ad8a67eee8d534ba19ac10e7a49a52c81c4c7362b7f439533f26c68"
                    + "6df00228cedd9bea5e997c1486cfe542b840782d3ae9641f92907d396fe08930";
    // 默认 linkKey: 第三方客户端从不调 setLinkKey, 恒为此值
    private static final String LINKKEY_HEX = "06775f87918dd423005df1d8cf0c142b";

    public static final byte[] SBOX = hex(SBOX_HEX);
    public static final byte[] T1 = hex(T1_HEX);
    public static final byte[] T2 = hex(T2_HEX);
    public static final byte[] LINKKEY_DEFAULT = hex(LINKKEY_HEX);

    // 已知测试向量(ground_truth 首组): 全零输入
    public static final String VEC_IN = "00000000000000000000000000000000";
    public static final String VEC_OUT = "d7aa553b54d77bb0c9db70fcc959a405";

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static int rol8(int x, int n) {
        x &= 0xFF;
        n &= 7;
        return ((x << n) | (x >> (8 - n))) & 0xFF;
    }

    /** 字节 j 的 ADD/XOR 选择位: (1<<j) & 0x9999 是否置位 */
    private static boolean sel(int j) {
        return ((1 << j) & 0x9999) != 0;
    }

    /**
     * sub_1630 密钥扩展: 16B 输入(挑战末字节已 ^6) → 17 个 16B 子密钥。
     */
    private static byte[][] keySchedule(byte[] inp16) {
        byte[][] rk = new byte[17][16];
        System.arraycopy(inp16, 0, rk[0], 0, 16);
        int[] cur = new int[16];
        for (int i = 0; i < 16; i++) cur[i] = inp16[i] & 0xFF;
        int[] buf = new int[17];
        for (int r = 0; r < 16; r++) {
            int parity = 0;
            for (int b : cur) parity ^= b;
            for (int i = 0; i < 16; i++) buf[i] = rol8(cur[i], 3);
            buf[16] = rol8(parity, 3);
            for (int j = 0; j < 16; j++) {
                int sboxIdx = 15 + 16 * r - j;      // 恒在 [0,255]
                int bufIdx = (r + 1 + j) % 17;
                rk[r + 1][j] = (byte) ((SBOX[sboxIdx] + buf[bufIdx]) & 0xFF);
            }
            for (int i = 0; i < 16; i++) cur[i] = rol8(cur[i], 3);
        }
        return rk;
    }

    /** sub_176c 的 MIX 线性层(忠实寄存器流, 成对 2:1 折叠, mod 256) */
    private static int[] mix(int[] s) {
        int w16 = s[0], w17 = s[1], w3 = s[2], w4 = s[3], w5 = s[4], w7 = (w17 + 2 * w16) & 0xFF, w6 = s[5];
        w16 = (w17 + w16) & 0xFF;
        int w19 = s[6];
        int w20 = (w4 + 2 * w3) & 0xFF;
        w17 = s[7];
        w3 = (w4 + w3) & 0xFF;
        int w21 = s[8];
        int w22 = (w6 + 2 * w5) & 0xFF;
        w4 = s[9];
        w5 = (w6 + w5) & 0xFF;
        int w23 = s[10];
        int w24 = (w17 + 2 * w19) & 0xFF;
        w6 = s[11];
        w17 = (w17 + w19) & 0xFF;
        int w25 = s[12];
        int w26 = (w4 + 2 * w21) & 0xFF;
        w19 = s[13];
        w4 = (w4 + w21) & 0xFF;
        int w27 = s[14];
        int w28 = (w6 + 2 * w23) & 0xFF;
        w21 = s[15];
        w6 = (w6 + w23) & 0xFF;
        w23 = (w19 + 2 * w25) & 0xFF;
        w19 = (w19 + w25) & 0xFF;
        w25 = (w21 + 2 * w27) & 0xFF;
        w21 = (w21 + w27) & 0xFF;
        w27 = (w6 + 2 * w26) & 0xFF;
        w6 = (w6 + w26) & 0xFF;
        w26 = (w21 + 2 * w23) & 0xFF;
        w21 = (w21 + w23) & 0xFF;
        w23 = (w16 + 2 * w20) & 0xFF;
        w16 = (w20 + w16) & 0xFF;
        w20 = (w5 + 2 * w24) & 0xFF;
        w5 = (w24 + w5) & 0xFF;
        w24 = (w4 + 2 * w28) & 0xFF;
        w4 = (w28 + w4) & 0xFF;
        w28 = (w19 + 2 * w25) & 0xFF;
        w19 = (w25 + w19) & 0xFF;
        w25 = (w17 + 2 * w7) & 0xFF;
        w17 = (w17 + w7) & 0xFF;
        w7 = (w3 + 2 * w22) & 0xFF;
        w3 = (w22 + w3) & 0xFF;
        w22 = (w19 + 2 * w24) & 0xFF;
        w19 = (w19 + w24) & 0xFF;
        w24 = (w3 + 2 * w25) & 0xFF;
        w3 = (w25 + w3) & 0xFF;
        w25 = (w16 + 2 * w20) & 0xFF;
        w16 = (w20 + w16) & 0xFF;
        w20 = (w4 + 2 * w28) & 0xFF;
        w4 = (w28 + w4) & 0xFF;
        w28 = (w17 + 2 * w7) & 0xFF;
        w17 = (w17 + w7) & 0xFF;
        int w30 = (w20 + w17) & 0xFF;
        w17 = (w17 + 2 * w20) & 0xFF;
        w7 = (w5 + 2 * w27) & 0xFF;
        w5 = (w27 + w5) & 0xFF;
        w27 = (w21 + 2 * w23) & 0xFF;
        w21 = (w21 + w23) & 0xFF;
        int sp0 = w17;
        w17 = (w19 + w24) & 0xFF;
        int w23b = (w26 + w6) & 0xFF;
        w19 = (w19 + 2 * w24) & 0xFF;
        int w20b = (w21 + w7) & 0xFF;
        w7 = (w21 + 2 * w7) & 0xFF;
        int sp5 = w17;
        w17 = (w23b + 2 * w25) & 0xFF;
        int sp4 = w19;
        w19 = (w4 + w28) & 0xFF;
        w4 = (w4 + 2 * w28) & 0xFF;
        int sp2 = w7;
        int sp6 = w17;
        w17 = (w27 + w5) & 0xFF;
        w7 = (w23b + w25) & 0xFF;
        w5 = (w5 + 2 * w27) & 0xFF;
        int sp8 = w4;
        w4 = (w22 + w16) & 0xFF;
        w16 = (w16 + 2 * w22) & 0xFF;
        int sp11 = w17;
        w17 = (w6 + 2 * w26) & 0xFF;
        int sp1 = w30;
        int sp13 = w4;
        w4 = (w17 + w3) & 0xFF;
        int sp12 = w16;
        w16 = (w3 + 2 * w17) & 0xFF;
        int sp3 = w20b;
        int sp7 = w7;
        int sp9 = w19;
        int sp10 = w5;
        int sp15 = w4;
        int sp14 = w16;
        return new int[]{sp0, sp1, sp2, sp3, sp4, sp5, sp6, sp7,
                sp8, sp9, sp10, sp11, sp12, sp13, sp14, sp15};
    }

    /** 代换层: T1 作用于字节 {0,3,4,7,8,11,12,15}, 其余过 T2 */
    private static int[] subst(int[] x) {
        int[] out = new int[16];
        for (int i = 0; i < 16; i++) {
            switch (i) {
                case 0: case 3: case 4: case 7:
                case 8: case 11: case 12: case 15:
                    out[i] = T1[x[i]] & 0xFF;
                    break;
                default:
                    out[i] = T2[x[i]] & 0xFF;
            }
        }
        return out;
    }

    /** key-mix-1 / 终混: sel 位 → XOR, 非 sel 位 → ADD */
    private static int[] kmCommon(int[] x, byte[] key) {
        int[] out = x.clone();
        for (int j = 0; j < 16; j++) {
            if (sel(j)) out[j] ^= (key[j] & 0xFF);
            else out[j] = (out[j] + (key[j] & 0xFF)) & 0xFF;
        }
        return out;
    }

    /** round==2 专用: 用原始状态备份做相同选择 */
    private static int[] kmSpecial(int[] x, int[] backup) {
        int[] out = x.clone();
        for (int j = 0; j < 16; j++) {
            if (sel(j)) out[j] ^= backup[j];
            else out[j] = (out[j] + backup[j]) & 0xFF;
        }
        return out;
    }

    /** key-mix-2: sel 位 → ADD, 非 sel 位 → XOR (与 key-mix-1 互补) */
    private static int[] km2(int[] x, byte[] key) {
        int[] out = x.clone();
        for (int j = 0; j < 16; j++) {
            if (sel(j)) out[j] = (out[j] + (key[j] & 0xFF)) & 0xFF;
            else out[j] ^= (key[j] & 0xFF);
        }
        return out;
    }

    /** sub_176c 轮函数: 8 轮 (km1[round2 special] → subst → km2 → mix) + rk[16] 终混 */
    private static byte[] roundFunc(int[] state, byte[][] rk) {
        int[] x = state.clone();
        int[] backup = x.clone();
        for (int r = 0; r < 8; r++) {
            if (r == 2) x = kmSpecial(x, backup);
            x = kmCommon(x, rk[2 * r]);
            x = subst(x);
            x = km2(x, rk[2 * r + 1]);
            x = mix(x);
        }
        x = kmCommon(x, rk[16]);
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) out[i] = (byte) x[i];
        return out;
    }

    /**
     * function_E21 = getEncryptedAuthCheckData。
     * inp16: 16B 挑战; 输出 16B 应答码 realCode。
     */
    public static byte[] e21(byte[] inp16) {
        byte[] ctx = LINKKEY_DEFAULT;
        // OUT 初始化 = CTX[0:6] ++ CTX[0:6] ++ CTX[0:4]
        int[] out = new int[16];
        for (int i = 0; i < 6; i++) out[i] = ctx[i] & 0xFF;
        for (int i = 0; i < 6; i++) out[6 + i] = ctx[i] & 0xFF;
        for (int i = 0; i < 4; i++) out[12 + i] = ctx[i] & 0xFF;
        // 密钥扩展输入: 挑战末字节 ^6
        byte[] ksIn = inp16.clone();
        ksIn[15] ^= 6;
        byte[][] rk = keySchedule(ksIn);
        return roundFunc(out, rk);
    }

    public static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
