package com.hdz.wearcontrol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;

/**
 * Pure Java 11 wire protocol for WearEars Local Mesh (WEAREARS_MESH_V1).
 * Zero Android dependencies, UTF-8 newline-delimited frames, 1024-byte hard cap per line.
 */
public final class LocalMeshProtocol {
    public static final String PROTOCOL_VERSION = "WEAREARS_MESH_V1";
    public static final int PROTOCOL_VER_NUM = 1;
    public static final int DEFAULT_TCP_PORT = 19845;
    public static final int DISCOVERY_UDP_PORT = 19846;
    public static final int MAX_LINE_BYTES = 1024;
    public static final int MAX_NAME_LEN = 64;

    private static final String BEACON_PREFIX = "WEAREARS_BEACON";

    /**
     * Hearing-safety allowlist for remote feature writes.
     * FIND_MY_BUDS is unconditionally excluded on both encode and decode paths.
     */
    private static final EnumSet<FeatureId> ALLOWED_REMOTE_FEATURES = EnumSet.of(
            FeatureId.NOISE_MODE,
            FeatureId.ANC_LEVEL,
            FeatureId.TRANS_MODE,
            FeatureId.WIND_REDUCTION,
            FeatureId.EQUALIZER,
            FeatureId.SPATIAL_AUDIO,
            FeatureId.SPATIAL_SCENE,
            FeatureId.HEAD_TRACKING,
            FeatureId.LOW_LATENCY,
            FeatureId.MULTIPOINT
    );

    private LocalMeshProtocol() {
    }

    public enum FrameType {
        HELLO,
        AUTH_OK,
        AUTH_ERR,
        STATE,
        CMD_NOISE,
        CMD_REFRESH,
        CMD_FEATURE,
        PING,
        PONG
    }

    public static final class StateSnapshot {
        public final boolean connected;
        public final boolean parked;
        public final int caps;
        public final int mode;
        public final int left;
        public final int right;
        public final int box;
        public final int charge;
        public final int ancLevel;
        public final int windVal;
        public final int eqVal;
        public final int spatialVal;
        public final String deviceName;

        public StateSnapshot(boolean connected, boolean parked, int caps, int mode,
                             int left, int right, int box, int charge,
                             int ancLevel, int windVal, int eqVal, int spatialVal,
                             String deviceName) {
            this.connected = connected;
            this.parked = parked;
            this.caps = caps;
            this.mode = (mode >= -1 && mode <= Controls.MODE_TRANS) ? mode : -1;
            this.left = left;
            this.right = right;
            this.box = box;
            this.charge = charge;
            this.ancLevel = ancLevel;
            this.windVal = windVal;
            this.eqVal = eqVal;
            this.spatialVal = spatialVal;
            this.deviceName = cleanSanitize(deviceName);
        }

        public static StateSnapshot disconnected() {
            return new StateSnapshot(false, false, 0, -1, -1, -1, -1, -1, -1, -1, -1, -1, "");
        }

        public static StateSnapshot fromDeviceState(DeviceState s, boolean live) {
            if (s == null) return disconnected();
            boolean conn = s.connected && (live || s.parked);
            boolean pk = conn && (s.parked || !live);
            Integer anc = s.featureValue(FeatureId.ANC_LEVEL);
            Integer wind = s.featureValue(FeatureId.WIND_REDUCTION);
            Integer eq = s.featureValue(FeatureId.EQUALIZER);
            if (eq == null) eq = s.equalizer;
            Integer sp = s.featureValue(FeatureId.SPATIAL_AUDIO);
            int spatialVal = sp != null ? sp.intValue() : (s.spatial == null ? -1 : (s.spatial ? 1 : 0));
            return new StateSnapshot(
                    conn,
                    pk,
                    conn ? s.caps : 0,
                    conn ? s.mode : -1,
                    conn ? s.left : -1,
                    conn ? s.right : -1,
                    conn ? s.box : -1,
                    conn ? s.charge : -1,
                    conn && anc != null ? anc.intValue() : -1,
                    conn && wind != null ? wind.intValue() : -1,
                    conn && eq != null ? eq.intValue() : -1,
                    conn ? spatialVal : -1,
                    s.name
            );
        }
    }

    public static final class Frame {
        public final FrameType type;
        public final String pin;
        public final String name;
        public final StateSnapshot state;
        public final int noiseMode;
        public final FeatureId featureId;
        public final int featureValue;

        private Frame(FrameType type, String pin, String name, StateSnapshot state,
                      int noiseMode, FeatureId featureId, int featureValue) {
            this.type = type;
            this.pin = pin;
            this.name = name;
            this.state = state;
            this.noiseMode = noiseMode;
            this.featureId = featureId;
            this.featureValue = featureValue;
        }

        static Frame hello(String pin, String clientName) {
            return new Frame(FrameType.HELLO, pin, clientName, null, -1, null, 0);
        }

        static Frame authOk(String hostName) {
            return new Frame(FrameType.AUTH_OK, null, hostName, null, -1, null, 0);
        }

        static Frame authErr() {
            return new Frame(FrameType.AUTH_ERR, null, null, null, -1, null, 0);
        }

        static Frame state(StateSnapshot snapshot) {
            return new Frame(FrameType.STATE, null, null, snapshot, -1, null, 0);
        }

        static Frame cmdNoise(int mode) {
            return new Frame(FrameType.CMD_NOISE, null, null, null, mode, null, 0);
        }

        static Frame cmdRefresh() {
            return new Frame(FrameType.CMD_REFRESH, null, null, null, -1, null, 0);
        }

        static Frame cmdFeature(FeatureId id, int val) {
            return new Frame(FrameType.CMD_FEATURE, null, null, null, -1, id, val);
        }

        static Frame simple(FrameType type) {
            return new Frame(type, null, null, null, -1, null, 0);
        }
    }

    public static final class Beacon {
        public final int version;
        public final int tcpPort;
        public final String hostName;
        public final String deviceName;
        public final boolean connected;

        public Beacon(int version, int tcpPort, String hostName, String deviceName, boolean connected) {
            this.version = version;
            this.tcpPort = tcpPort;
            this.hostName = cleanSanitize(hostName);
            this.deviceName = cleanSanitize(deviceName);
            this.connected = connected;
        }
    }

    /**
     * Hearing-safety hard redline: unconditionally rejects FIND_MY_BUDS and non-allowlisted features.
     */
    public static boolean isAllowedRemoteFeature(FeatureId id) {
        return id != null && id != FeatureId.FIND_MY_BUDS && ALLOWED_REMOTE_FEATURES.contains(id);
    }

    /**
     * Strips newlines, pipes, and ISO control characters; trims and caps length to <= 64 chars.
     */
    public static String cleanSanitize(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        StringBuilder out = new StringBuilder(Math.min(raw.length(), MAX_NAME_LEN));
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '|' || c == '\r' || c == '\n' || Character.isISOControl(c)) {
                continue;
            }
            if (out.length() >= MAX_NAME_LEN) {
                break;
            }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < raw.length() && Character.isLowSurrogate(raw.charAt(i + 1))) {
                    if (out.length() + 2 > MAX_NAME_LEN) break;
                    out.append(c).append(raw.charAt(i + 1));
                    i++;
                }
                continue;
            }
            if (Character.isLowSurrogate(c)) {
                continue;
            }
            out.append(c);
        }
        return out.toString().trim();
    }

    /** Validates a 4-digit ASCII numeric PIN ("0000".."9999"). */
    public static boolean isValidPin(String pin) {
        if (pin == null || pin.length() != 4) return false;
        for (int i = 0; i < 4; i++) {
            char c = pin.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** Constant-time UTF-8 comparison to avoid PIN timing side channels. */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] ba = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        int diff = ba.length ^ bb.length;
        int max = Math.max(ba.length, bb.length);
        for (int i = 0; i < max; i++) {
            byte xa = i < ba.length ? ba[i] : 0;
            byte xb = i < bb.length ? bb[i] : 0;
            diff |= (xa ^ xb);
        }
        return diff == 0;
    }

    /**
     * Allows strictly private/local LAN IP literals:
     * IPv4: 127.0.0.0/8, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16
     * IPv6: ::1, fe80::/10 (link-local), fc00::/7 (fc/fd unique local)
     * Rejects public IPs, hostnames, null, empty, and malformed inputs without DNS lookup.
     */
    public static boolean isPrivateLanAddress(String ip) {
        if (ip == null || ip.isEmpty() || ip.length() > 128) return false;
        for (int i = 0; i < ip.length(); i++) {
            char c = ip.charAt(i);
            if (c <= 0x20 || c >= 0x7F) return false;
        }
        if (ip.indexOf(':') >= 0) {
            return isPrivateIpv6(ip);
        }
        return isPrivateIpv4(ip);
    }

    private static boolean isPrivateIpv4(String ip) {
        int[] octets = new int[4];
        int part = 0;
        int start = 0;
        int len = ip.length();
        for (int i = 0; i <= len; i++) {
            if (i == len || ip.charAt(i) == '.') {
                if (part >= 4 || i == start || i - start > 3) return false;
                if (i - start > 1 && ip.charAt(start) == '0') return false;
                int val = 0;
                for (int j = start; j < i; j++) {
                    char c = ip.charAt(j);
                    if (c < '0' || c > '9') return false;
                    val = val * 10 + (c - '0');
                }
                if (val > 255) return false;
                octets[part++] = val;
                start = i + 1;
            }
        }
        if (part != 4) return false;
        int o0 = octets[0];
        int o1 = octets[1];
        if (o0 == 127) return true;
        if (o0 == 10) return true;
        if (o0 == 172 && o1 >= 16 && o1 <= 31) return true;
        if (o0 == 192 && o1 == 168) return true;
        return o0 == 169 && o1 == 254;
    }

    private static boolean isPrivateIpv6(String raw) {
        String ip = raw;
        int zoneIdx = ip.indexOf('%');
        if (zoneIdx >= 0) {
            if (zoneIdx == 0 || zoneIdx == ip.length() - 1) return false;
            ip = ip.substring(0, zoneIdx);
        }
        if (ip.indexOf('.') >= 0) {
            // Reject IPv4-mapped/embedded IPv6 forms (e.g. ::ffff:8.8.8.8).
            return false;
        }
        int doubleColon = ip.indexOf("::");
        if (doubleColon >= 0 && ip.indexOf("::", doubleColon + 2) >= 0) {
            return false;
        }
        int[] words = new int[8];
        if (doubleColon >= 0) {
            String leftPart = ip.substring(0, doubleColon);
            String rightPart = ip.substring(doubleColon + 2);
            int[] left = leftPart.isEmpty() ? new int[0] : parseHexGroups(leftPart);
            int[] right = rightPart.isEmpty() ? new int[0] : parseHexGroups(rightPart);
            if (left == null || right == null) return false;
            if (left.length + right.length >= 8) return false;
            System.arraycopy(left, 0, words, 0, left.length);
            System.arraycopy(right, 0, words, 8 - right.length, right.length);
        } else {
            int[] all = parseHexGroups(ip);
            if (all == null || all.length != 8) return false;
            System.arraycopy(all, 0, words, 0, 8);
        }

        // Loopback ::1
        boolean isLoopback = words[7] == 1;
        for (int i = 0; i < 7 && isLoopback; i++) {
            if (words[i] != 0) isLoopback = false;
        }
        if (isLoopback) return true;

        int first = words[0];
        // Link-local fe80::/10
        if ((first & 0xFFC0) == 0xFE80) return true;
        // Unique Local Address fc00::/7 (fc00:: - fdff::)
        return (first & 0xFE00) == 0xFC00;
    }

    private static int[] parseHexGroups(String segment) {
        String[] parts = segment.split(":", -1);
        if (parts.length == 0 || parts.length > 8) return null;
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 4) return null;
            int val = 0;
            for (int j = 0; j < p.length(); j++) {
                int d = Character.digit(p.charAt(j), 16);
                if (d < 0) return null;
                val = (val << 4) | d;
            }
            out[i] = val;
        }
        return out;
    }

    // ---- Frame encoders ----

    public static String encodeHello(String pin, String clientName) {
        if (!isValidPin(pin)) {
            throw new IllegalArgumentException("Invalid 4-digit PIN");
        }
        return "HELLO|" + pin + "|" + cleanSanitize(clientName);
    }

    public static String encodeAuthOk(String hostName) {
        return "AUTH_OK|" + cleanSanitize(hostName);
    }

    public static String encodeAuthErr() {
        return "AUTH_ERR";
    }

    public static String encodeState(StateSnapshot s) {
        StateSnapshot safe = s == null ? StateSnapshot.disconnected() : s;
        return "STATE|"
                + (safe.connected ? 1 : 0) + "|"
                + (safe.parked ? 1 : 0) + "|"
                + safe.caps + "|"
                + safe.mode + "|"
                + safe.left + "|"
                + safe.right + "|"
                + safe.box + "|"
                + safe.charge + "|"
                + safe.ancLevel + "|"
                + safe.windVal + "|"
                + safe.eqVal + "|"
                + safe.spatialVal + "|"
                + cleanSanitize(safe.deviceName);
    }

    public static String encodeCmdNoise(int mode) {
        if (mode < Controls.MODE_ANC || mode > Controls.MODE_TRANS) {
            throw new IllegalArgumentException("Invalid noise mode: " + mode);
        }
        return "CMD|NOISE|" + mode;
    }

    public static String encodeCmdRefresh() {
        return "CMD|REFRESH";
    }

    /**
     * Encodes a remote feature command. Unconditionally rejects FIND_MY_BUDS and disallowed features.
     */
    public static String encodeCmdFeature(FeatureId id, int val) {
        if (!isAllowedRemoteFeature(id)) {
            throw new IllegalArgumentException("Remote feature not allowed: " + id);
        }
        return "CMD|FEATURE|" + id.name() + "|" + val;
    }

    public static String encodePing() {
        return "PING";
    }

    public static String encodePong() {
        return "PONG";
    }

    /**
     * Encodes a LAN UDP discovery beacon. Never includes any PIN.
     */
    public static String encodeBeacon(int tcpPort, String hostName, String deviceName, boolean connected) {
        if (tcpPort < 1 || tcpPort > 65535) {
            throw new IllegalArgumentException("Invalid TCP port: " + tcpPort);
        }
        return BEACON_PREFIX + "|"
                + PROTOCOL_VER_NUM + "|"
                + tcpPort + "|"
                + cleanSanitize(hostName) + "|"
                + cleanSanitize(deviceName) + "|"
                + (connected ? 1 : 0);
    }

    // ---- Frame decoders ----

    /**
     * Parses a single protocol line. Returns null on any oversized (>1024B), malformed, or disallowed frame.
     */
    public static Frame parseFrame(String rawLine) {
        String line = stripValidLine(rawLine);
        if (line == null || line.isEmpty()) return null;

        if ("PING".equals(line)) return Frame.simple(FrameType.PING);
        if ("PONG".equals(line)) return Frame.simple(FrameType.PONG);
        if ("AUTH_ERR".equals(line)) return Frame.authErr();

        String[] parts = line.split("\\|", -1);
        if (parts.length < 2) return null;

        String head = parts[0];
        if ("HELLO".equals(head)) {
            if (parts.length != 3 || !isValidPin(parts[1])) return null;
            return Frame.hello(parts[1], cleanSanitize(parts[2]));
        }
        if ("AUTH_OK".equals(head)) {
            if (parts.length != 2) return null;
            return Frame.authOk(cleanSanitize(parts[1]));
        }
        if ("STATE".equals(head)) {
            if (parts.length != 14) return null;
            Boolean connected = parseBit(parts[1]);
            Boolean parked = parseBit(parts[2]);
            Integer caps = parseIntStrict(parts[3]);
            Integer mode = parseIntStrict(parts[4]);
            Integer left = parseIntStrict(parts[5]);
            Integer right = parseIntStrict(parts[6]);
            Integer box = parseIntStrict(parts[7]);
            Integer charge = parseIntStrict(parts[8]);
            Integer ancLevel = parseIntStrict(parts[9]);
            Integer windVal = parseIntStrict(parts[10]);
            Integer eqVal = parseIntStrict(parts[11]);
            Integer spatialVal = parseIntStrict(parts[12]);
            if (connected == null || parked == null || caps == null || mode == null
                    || left == null || right == null || box == null || charge == null
                    || ancLevel == null || windVal == null || eqVal == null || spatialVal == null) {
                return null;
            }
            if (mode < -1 || mode > Controls.MODE_TRANS) return null;
            return Frame.state(new StateSnapshot(
                    connected, parked, caps, mode, left, right, box, charge,
                    ancLevel, windVal, eqVal, spatialVal, cleanSanitize(parts[13])
            ));
        }
        if ("CMD".equals(head)) {
            String sub = parts[1];
            if ("NOISE".equals(sub)) {
                if (parts.length != 3) return null;
                Integer mode = parseIntStrict(parts[2]);
                if (mode == null || mode < Controls.MODE_ANC || mode > Controls.MODE_TRANS) return null;
                return Frame.cmdNoise(mode);
            }
            if ("REFRESH".equals(sub)) {
                if (parts.length != 2) return null;
                return Frame.cmdRefresh();
            }
            if ("FEATURE".equals(sub)) {
                if (parts.length != 4) return null;
                FeatureId id;
                try {
                    id = FeatureId.valueOf(parts[2]);
                } catch (IllegalArgumentException unknown) {
                    return null;
                }
                if (!isAllowedRemoteFeature(id)) {
                    return null;
                }
                Integer val = parseIntStrict(parts[3]);
                if (val == null) return null;
                return Frame.cmdFeature(id, val);
            }
        }
        return null;
    }

    /**
     * Parses a UDP discovery beacon. Never parses or expects any PIN.
     */
    public static Beacon parseBeacon(String raw) {
        String line = stripValidLine(raw);
        if (line == null || line.isEmpty()) return null;
        String[] parts = line.split("\\|", -1);
        if (parts.length != 6) return null;
        if (!BEACON_PREFIX.equals(parts[0])) return null;
        Integer ver = parseIntStrict(parts[1]);
        Integer port = parseIntStrict(parts[2]);
        Boolean connected = parseBit(parts[5]);
        if (ver == null || ver != PROTOCOL_VER_NUM || port == null || port < 1 || port > 65535 || connected == null) {
            return null;
        }
        return new Beacon(ver, port, cleanSanitize(parts[3]), cleanSanitize(parts[4]), connected);
    }

    // ---- Stream framing helpers ----

    /**
     * Reads one UTF-8 line terminated by LF or CRLF with a hard 1024-byte payload cap.
     * Returns null on clean EOF before any bytes are read.
     */
    public static String readLineBounded(InputStream in) throws IOException {
        return readLineBounded(in, MAX_LINE_BYTES);
    }

    public static String readLineBounded(InputStream in, int maxBytes) throws IOException {
        if (in == null) throw new IOException("Null input stream");
        int cap = Math.max(1, Math.min(maxBytes, MAX_LINE_BYTES));
        ByteArrayOutputStream buf = new ByteArrayOutputStream(Math.min(cap, 256));
        int b;
        boolean sawAny = false;
        while ((b = in.read()) != -1) {
            sawAny = true;
            if (b == '\n') {
                break;
            }
            if (buf.size() >= cap) {
                if (b == '\r' && in.read() == '\n') break;
                throw new IOException("Frame exceeds " + cap + " bytes");
            }
            buf.write(b);
        }
        if (!sawAny) return null;
        byte[] bytes = buf.toByteArray();
        int len = bytes.length;
        if (len > 0 && bytes[len - 1] == '\r') {
            len--;
        }
        return new String(bytes, 0, len, StandardCharsets.UTF_8);
    }

    /** Writes a single frame followed by '\n', enforcing the 1024-byte limit. */
    public static void writeFrame(OutputStream out, String frame) throws IOException {
        if (out == null || frame == null) throw new IOException("Invalid frame write");
        byte[] payload = frame.getBytes(StandardCharsets.UTF_8);
        if (payload.length > MAX_LINE_BYTES) {
            throw new IOException("Outbound frame exceeds " + MAX_LINE_BYTES + " bytes");
        }
        for (byte b : payload) {
            if (b == '\n' || b == '\r') {
                throw new IOException("Outbound frame contains newline");
            }
        }
        out.write(payload);
        out.write('\n');
        out.flush();
    }

    // ---- Internal helpers ----

    private static String stripValidLine(String raw) {
        if (raw == null) return null;
        int end = raw.length();
        if (end > 0 && raw.charAt(end - 1) == '\n') {
            end--;
            if (end > 0 && raw.charAt(end - 1) == '\r') {
                end--;
            }
        }
        if (end > MAX_LINE_BYTES) return null;
        String payload = end == raw.length() ? raw : raw.substring(0, end);
        if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_LINE_BYTES) return null;
        for (int i = 0; i < payload.length(); i++) {
            char c = payload.charAt(i);
            if (c == '\r' || c == '\n' || c == '\0') return null;
        }
        return payload;
    }

    private static Boolean parseBit(String s) {
        if ("1".equals(s)) return Boolean.TRUE;
        if ("0".equals(s)) return Boolean.FALSE;
        return null;
    }

    private static Integer parseIntStrict(String s) {
        if (s == null || s.isEmpty() || s.length() > 11) return null;
        int start = 0;
        if (s.charAt(0) == '-') {
            if (s.length() == 1) return null;
            start = 1;
        }
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return null;
        }
        try {
            return Integer.valueOf(Integer.parseInt(s));
        } catch (NumberFormatException overflow) {
            return null;
        }
    }
}
