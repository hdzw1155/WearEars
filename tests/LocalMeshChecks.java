package com.hdz.wearcontrol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/** Pure JVM regression checks for LocalMeshProtocol. */
public final class LocalMeshChecks {
    private static int checks;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }

    private static void roundTripAuthAndHeartbeat() {
        String helloWire = LocalMeshProtocol.encodeHello("0429", "XR Glass");
        check("HELLO|0429|XR Glass".equals(helloWire), "HELLO wire format");
        LocalMeshProtocol.Frame hello = LocalMeshProtocol.parseFrame(helloWire);
        check(hello != null && hello.type == LocalMeshProtocol.FrameType.HELLO
                && "0429".equals(hello.pin) && "XR Glass".equals(hello.name), "HELLO round-trip");

        // Tolerates single trailing CRLF/LF on parse
        LocalMeshProtocol.Frame helloCrLf = LocalMeshProtocol.parseFrame(helloWire + "\r\n");
        check(helloCrLf != null && helloCrLf.type == LocalMeshProtocol.FrameType.HELLO
                && "0429".equals(helloCrLf.pin), "HELLO CRLF parse");

        // Rejects embedded newline
        check(LocalMeshProtocol.parseFrame("HELLO|0429|XR\nGlass") == null, "reject embedded LF");
        check(LocalMeshProtocol.parseFrame("HELLO|0429|XR\rGlass") == null, "reject embedded CR");
        check(LocalMeshProtocol.parseFrame("HELLO|123|BadPin") == null, "reject 3-digit PIN in HELLO");

        String authOkWire = LocalMeshProtocol.encodeAuthOk("Pixel Watch");
        LocalMeshProtocol.Frame authOk = LocalMeshProtocol.parseFrame(authOkWire);
        check(authOk != null && authOk.type == LocalMeshProtocol.FrameType.AUTH_OK
                && "Pixel Watch".equals(authOk.name), "AUTH_OK round-trip");

        LocalMeshProtocol.Frame authErr = LocalMeshProtocol.parseFrame(LocalMeshProtocol.encodeAuthErr());
        check(authErr != null && authErr.type == LocalMeshProtocol.FrameType.AUTH_ERR, "AUTH_ERR round-trip");

        LocalMeshProtocol.Frame ping = LocalMeshProtocol.parseFrame(LocalMeshProtocol.encodePing());
        LocalMeshProtocol.Frame pong = LocalMeshProtocol.parseFrame(LocalMeshProtocol.encodePong());
        check(ping != null && ping.type == LocalMeshProtocol.FrameType.PING, "PING round-trip");
        check(pong != null && pong.type == LocalMeshProtocol.FrameType.PONG, "PONG round-trip");
    }

    private static void roundTripStateSnapshot() {
        DeviceState ds = new DeviceState();
        ds.begin("AA:BB:CC:DD:EE:FF", "OPPO|Enco\nFree3\r\tPro", Controls.CAP_NOISE | Controls.CAP_BATTERY | Controls.CAP_SPATIAL, 0);
        ds.connected = true;
        ds.parked = false;
        ds.battery(85, 90, 60, 4);
        ds.mode = Controls.MODE_ANC;
        ds.featureValue(FeatureId.ANC_LEVEL, 2);
        ds.featureValue(FeatureId.WIND_REDUCTION, 1);
        ds.featureValue(FeatureId.EQUALIZER, 3);
        ds.featureValue(FeatureId.SPATIAL_AUDIO, 1);

        LocalMeshProtocol.StateSnapshot snap = LocalMeshProtocol.StateSnapshot.fromDeviceState(ds, true);
        String wire = LocalMeshProtocol.encodeState(snap);
        check(wire.equals("STATE|1|0|7|0|85|90|60|4|2|1|3|1|OPPOEncoFree3Pro"), "STATE exact format: " + wire);

        LocalMeshProtocol.Frame parsed = LocalMeshProtocol.parseFrame(wire);
        check(parsed != null && parsed.type == LocalMeshProtocol.FrameType.STATE && parsed.state != null, "STATE parse");
        LocalMeshProtocol.StateSnapshot got = parsed.state;
        check(got.connected && !got.parked && got.caps == 7 && got.mode == 0, "STATE flags/caps/mode");
        check(got.left == 85 && got.right == 90 && got.box == 60 && got.charge == 4, "STATE battery");
        check(got.ancLevel == 2 && got.windVal == 1 && got.eqVal == 3 && got.spatialVal == 1, "STATE features");
        check("OPPOEncoFree3Pro".equals(got.deviceName), "STATE sanitized name");

        // Disconnected empty name round-trip (must preserve trailing empty field)
        String emptyWire = LocalMeshProtocol.encodeState(LocalMeshProtocol.StateSnapshot.disconnected());
        LocalMeshProtocol.Frame parsedEmpty = LocalMeshProtocol.parseFrame(emptyWire);
        check(parsedEmpty != null && parsedEmpty.state != null && !parsedEmpty.state.connected
                && "".equals(parsedEmpty.state.deviceName), "STATE empty trailing deviceName");

        // Parked snapshot retains connected=1, parked=1
        ds.enterParked();
        LocalMeshProtocol.StateSnapshot parkedSnap = LocalMeshProtocol.StateSnapshot.fromDeviceState(ds, false);
        check(parkedSnap.connected && parkedSnap.parked, "parked snapshot flags");

        // Reject invalid mode / bit values in STATE
        check(LocalMeshProtocol.parseFrame("STATE|2|0|7|0|85|90|60|4|2|1|3|1|Dev") == null, "reject invalid connected bit");
        check(LocalMeshProtocol.parseFrame("STATE|1|0|7|3|85|90|60|4|2|1|3|1|Dev") == null, "reject out-of-range mode");
    }

    private static void roundTripCommandsAndSafetyRedline() {
        for (int m = 0; m <= 2; m++) {
            String wire = LocalMeshProtocol.encodeCmdNoise(m);
            LocalMeshProtocol.Frame f = LocalMeshProtocol.parseFrame(wire);
            check(f != null && f.type == LocalMeshProtocol.FrameType.CMD_NOISE && f.noiseMode == m, "CMD|NOISE|" + m);
        }
        boolean invalidModeThrew = false;
        try {
            LocalMeshProtocol.encodeCmdNoise(3);
        } catch (IllegalArgumentException expected) {
            invalidModeThrew = true;
        }
        check(invalidModeThrew, "encodeCmdNoise rejects invalid mode");
        check(LocalMeshProtocol.parseFrame("CMD|NOISE|-1") == null, "parse rejects CMD|NOISE|-1");
        check(LocalMeshProtocol.parseFrame("CMD|NOISE|3") == null, "parse rejects CMD|NOISE|3");

        LocalMeshProtocol.Frame refresh = LocalMeshProtocol.parseFrame(LocalMeshProtocol.encodeCmdRefresh());
        check(refresh != null && refresh.type == LocalMeshProtocol.FrameType.CMD_REFRESH, "CMD|REFRESH round-trip");

        FeatureId[] allowed = {
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
        };
        for (int i = 0; i < allowed.length; i++) {
            FeatureId id = allowed[i];
            check(LocalMeshProtocol.isAllowedRemoteFeature(id), "allowlisted feature " + id);
            String wire = LocalMeshProtocol.encodeCmdFeature(id, i - 2);
            LocalMeshProtocol.Frame f = LocalMeshProtocol.parseFrame(wire);
            check(f != null && f.type == LocalMeshProtocol.FrameType.CMD_FEATURE
                    && f.featureId == id && f.featureValue == i - 2, "CMD|FEATURE round-trip " + id);
        }

        // Hearing safety hard redline: FIND_MY_BUDS + non-allowlisted features blocked on BOTH encode and decode
        FeatureId[] blocked = {
                FeatureId.FIND_MY_BUDS,
                FeatureId.WEAR_DETECTION,
                FeatureId.WEAR_STATUS,
                FeatureId.FIRMWARE_INFO,
                null
        };
        for (FeatureId id : blocked) {
            check(!LocalMeshProtocol.isAllowedRemoteFeature(id), "blocked feature " + id);
            boolean threw = false;
            try {
                LocalMeshProtocol.encodeCmdFeature(id, 1);
            } catch (IllegalArgumentException expected) {
                threw = true;
            }
            check(threw, "encodeCmdFeature must throw for " + id);
            if (id != null) {
                check(LocalMeshProtocol.parseFrame("CMD|FEATURE|" + id.name() + "|1") == null,
                        "parseFrame must drop CMD|FEATURE|" + id.name());
                check(LocalMeshProtocol.parseFrame("CMD|FEATURE|" + id.name() + "|0") == null,
                        "parseFrame must drop CMD|FEATURE|" + id.name() + "|0");
            }
        }
        check(LocalMeshProtocol.parseFrame("CMD|FEATURE|FIND_MY_BUDS|3") == null, "FIND_MY_BUDS|3 hard-dropped");
        check(LocalMeshProtocol.parseFrame("CMD|FEATURE|UNKNOWN_FEAT|1") == null, "unknown feature dropped");
    }

    private static void sanitizeAndOversizeGuards() {
        String dirty = "  Head|set\r\n\t\u0000\u0007\u001F\u007F Name  ";
        String clean = LocalMeshProtocol.cleanSanitize(dirty);
        check("Headset Name".equals(clean), "cleanSanitize strips pipe/CRLF/controls: '" + clean + "'");
        check(!clean.contains("|") && !clean.contains("\n") && !clean.contains("\r"), "no delimiters remain");

        StringBuilder longStr = new StringBuilder();
        for (int i = 0; i < 120; i++) longStr.append('A');
        String capped = LocalMeshProtocol.cleanSanitize(longStr.toString());
        check(capped.length() == LocalMeshProtocol.MAX_NAME_LEN, "cleanSanitize caps at 64 chars");

        // Exact 1024-byte frame vs 1025-byte frame
        StringBuilder pad = new StringBuilder("AUTH_OK|");
        while (pad.length() < LocalMeshProtocol.MAX_LINE_BYTES) pad.append('x');
        check(pad.toString().getBytes(StandardCharsets.UTF_8).length == 1024, "exact 1024B test frame");
        LocalMeshProtocol.Frame ok1024 = LocalMeshProtocol.parseFrame(pad.toString());
        check(ok1024 != null && ok1024.type == LocalMeshProtocol.FrameType.AUTH_OK
                && ok1024.name.length() == LocalMeshProtocol.MAX_NAME_LEN, "1024B frame accepted and name capped");

        pad.append('x');
        check(pad.toString().getBytes(StandardCharsets.UTF_8).length == 1025, "1025B test frame");
        check(LocalMeshProtocol.parseFrame(pad.toString()) == null, "1025B frame rejected");

        // Multi-byte UTF-8 > 1024 bytes rejected even if char count < 1024
        StringBuilder cjk = new StringBuilder("AUTH_OK|");
        for (int i = 0; i < 400; i++) cjk.append('耳');
        check(cjk.length() < 1024 && cjk.toString().getBytes(StandardCharsets.UTF_8).length > 1024, "CJK byte len > 1024");
        check(LocalMeshProtocol.parseFrame(cjk.toString()) == null, "UTF-8 byte length > 1024 rejected");

        // Stream bounded reader
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            LocalMeshProtocol.writeFrame(out, "PING");
            LocalMeshProtocol.writeFrame(out, "PONG");
            ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
            check("PING".equals(LocalMeshProtocol.readLineBounded(in)), "readLineBounded first frame");
            check("PONG".equals(LocalMeshProtocol.readLineBounded(in)), "readLineBounded second frame");
            check(LocalMeshProtocol.readLineBounded(in) == null, "readLineBounded EOF");
        } catch (IOException e) {
            throw new AssertionError("Unexpected IO error", e);
        }

        boolean oversizeStreamThrew = false;
        try {
            byte[] flood = new byte[2048];
            for (int i = 0; i < flood.length; i++) flood[i] = 'Z';
            LocalMeshProtocol.readLineBounded(new ByteArrayInputStream(flood));
        } catch (IOException expected) {
            oversizeStreamThrew = true;
        }
        check(oversizeStreamThrew, "readLineBounded rejects >1024B stream flood");
    }

    private static void lanAddressAndPinChecks() {
        String[] allowedIps = {
                "127.0.0.1", "127.255.255.254",
                "10.0.0.1", "10.255.255.254",
                "172.16.0.1", "172.24.10.20", "172.31.255.254",
                "192.168.0.1", "192.168.123.45",
                "169.254.1.2",
                "::1",
                "fe80::1", "fe80::1%wlan0", "fe80:0:0:0:1:2:3:4",
                "fc00::1", "fd12:3456:789a::1"
        };
        for (String ip : allowedIps) {
            check(LocalMeshProtocol.isPrivateLanAddress(ip), "must allow private LAN IP: " + ip);
        }

        String[] rejectedIps = {
                null, "", "   ",
                "8.8.8.8", "1.1.1.1", "114.114.114.114",
                "172.15.255.255", "172.32.0.1",
                "192.169.0.1", "100.64.0.1",
                "0.0.0.0", "255.255.255.255",
                "192.168.1.256", "192.168.1", "192.168.01.1",
                "2001:4860:4860::8888", "::ffff:8.8.8.8", "::ffff:192.168.1.1",
                "fe80::gggg", "fe80:::1", "localhost", "evil.example.com",
                "192.168.1.1\n", " 192.168.1.1"
        };
        for (String ip : rejectedIps) {
            check(!LocalMeshProtocol.isPrivateLanAddress(ip), "must reject non-LAN IP: " + ip);
        }

        check(LocalMeshProtocol.isValidPin("0000"), "PIN 0000 valid");
        check(LocalMeshProtocol.isValidPin("9876"), "PIN 9876 valid");
        check(!LocalMeshProtocol.isValidPin(null), "null PIN invalid");
        check(!LocalMeshProtocol.isValidPin(""), "empty PIN invalid");
        check(!LocalMeshProtocol.isValidPin("123"), "3-digit PIN invalid");
        check(!LocalMeshProtocol.isValidPin("12345"), "5-digit PIN invalid");
        check(!LocalMeshProtocol.isValidPin("12a4"), "alpha PIN invalid");
        check(!LocalMeshProtocol.isValidPin("12 4"), "space PIN invalid");
        check(!LocalMeshProtocol.isValidPin("\u0661\u0662\u0663\u0664"), "non-ASCII digit PIN invalid");

        check(LocalMeshProtocol.constantTimeEquals("1234", "1234"), "ct equal");
        check(LocalMeshProtocol.constantTimeEquals("", ""), "ct empty equal");
        check(!LocalMeshProtocol.constantTimeEquals("1234", "1235"), "ct diff last");
        check(!LocalMeshProtocol.constantTimeEquals("1234", "9234"), "ct diff first");
        check(!LocalMeshProtocol.constantTimeEquals("1234", "123"), "ct diff len");
        check(!LocalMeshProtocol.constantTimeEquals("1234", null), "ct rhs null");
        check(!LocalMeshProtocol.constantTimeEquals(null, "1234"), "ct lhs null");
        check(!LocalMeshProtocol.constantTimeEquals(null, null), "ct both null");
    }

    private static void beaconChecks() {
        String wire = LocalMeshProtocol.encodeBeacon(19845, "Phone|Host\n1", "Enco|X3", true);
        check("WEAREARS_BEACON|1|19845|PhoneHost1|EncoX3|1".equals(wire), "beacon exact wire: " + wire);
        check(wire.split("\\|", -1).length == 6, "beacon has exactly 6 fields (no PIN field)");

        LocalMeshProtocol.Beacon parsed = LocalMeshProtocol.parseBeacon(wire);
        check(parsed != null && parsed.version == 1 && parsed.tcpPort == 19845
                && "PhoneHost1".equals(parsed.hostName)
                && "EncoX3".equals(parsed.deviceName)
                && parsed.connected, "beacon round-trip");

        // Reject invalid beacons
        check(LocalMeshProtocol.parseBeacon("WEAREARS_BEACON|2|19845|Host|Dev|1") == null, "reject unknown version");
        check(LocalMeshProtocol.parseBeacon("WEAREARS_BEACON|1|0|Host|Dev|1") == null, "reject port 0");
        check(LocalMeshProtocol.parseBeacon("WEAREARS_BEACON|1|65536|Host|Dev|1") == null, "reject port > 65535");
        check(LocalMeshProtocol.parseBeacon("WEAREARS_BEACON|1|19845|Host|Dev|2") == null, "reject connected=2");
        check(LocalMeshProtocol.parseBeacon("WEAREARS_BEACON|1|19845|Host|Dev|1|9999") == null, "reject extra field");

        boolean badPortThrew = false;
        try {
            LocalMeshProtocol.encodeBeacon(0, "Host", "Dev", false);
        } catch (IllegalArgumentException expected) {
            badPortThrew = true;
        }
        check(badPortThrew, "encodeBeacon rejects port 0");
    }

    private static void malformedFuzzZeroCrash() {
        Random rnd = new Random(0x5EED2026L);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789|-:\r\n\0\t \u4E2D\uD83C\uDFA7";
        for (int i = 0; i < 300; i++) {
            int len = rnd.nextInt(1200);
            StringBuilder sb = new StringBuilder(len);
            for (int j = 0; j < len; j++) {
                sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
            }
            String sample = sb.toString();
            LocalMeshProtocol.parseFrame(sample);
            LocalMeshProtocol.parseBeacon(sample);
            LocalMeshProtocol.isPrivateLanAddress(sample);
            LocalMeshProtocol.isValidPin(sample);
            String cleaned = LocalMeshProtocol.cleanSanitize(sample);
            check(cleaned.length() <= LocalMeshProtocol.MAX_NAME_LEN
                    && !cleaned.contains("|") && !cleaned.contains("\n") && !cleaned.contains("\r"),
                    "fuzz cleanSanitize invariant");
        }

        // Targeted edge cases
        String[] edgeInputs = {
                null, "", "|", "|||", "STATE|", "CMD|", "CMD|FEATURE|NOISE_MODE|99999999999999999999",
                "CMD|NOISE|99999999999", "STATE|1|0|0|0|0|0|0|0|0|0|0|0|", "HELLO||", "AUTH_OK|"
        };
        for (String s : edgeInputs) {
            LocalMeshProtocol.parseFrame(s);
            LocalMeshProtocol.parseBeacon(s);
        }
        checks++;
    }

    public static void main(String[] args) {
        roundTripAuthAndHeartbeat();
        roundTripStateSnapshot();
        roundTripCommandsAndSafetyRedline();
        sanitizeAndOversizeGuards();
        lanAddressAndPinChecks();
        beaconChecks();
        malformedFuzzZeroCrash();
        System.out.println("PASS " + checks + " local mesh checks");
    }
}
