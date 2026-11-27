package com.hdz.wearcontrol;

import com.hdz.wearcontrol.huawei.HuaweiProtocol;
import com.hdz.wearcontrol.oppo.OppoProtocol;
import com.hdz.wearcontrol.xiaomi.XiaomiProtocol;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static com.hdz.wearcontrol.Controls.*;
import static com.hdz.wearcontrol.FeatureId.*;
import static com.hdz.wearcontrol.LocalMeshFirewall.*;

/** Fixed-seed properties using in-memory transports and clocks. */
public final class ProtocolRobustnessChecks {
    private static final AtomicInteger CHECKS = new AtomicInteger();
    private static final byte[] EMPTY = new byte[0];
    private static final int RANDOM_PAYLOAD_CASES = 320;
    private static final FeatureId[] FEATURES = FeatureId.values();
    private static final int[] INT_BOUNDARIES = {Integer.MIN_VALUE, -1, 0, 1, 2, 3, 255, Integer.MAX_VALUE};
    private static final int[] BATTERY_BOUNDARIES = {-1, 0, 1, 99, 100, 255};
    private static final String PEER = "192.168.14.25";
    private static final String HOST = "192.168.14.1";

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        CHECKS.incrementAndGet();
    }

    private static byte[] hex(String value) { return ProtocolChecks.hex(value); }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    private static final class WireFixture {
        final String name;
        final byte[] hello;
        final long readyAt;
        final byte[] battery, noise;
        final int[] levels;
        final int mode;
        final byte[][] lengthHeaders;

        WireFixture(String name, byte[] hello, long readyAt, byte[] battery, byte[] noise,
                    int[] levels, int mode, String shortHeader, String longHeader) {
            this.name = name;
            this.hello = hello;
            this.readyAt = readyAt;
            this.battery = battery;
            this.noise = noise;
            this.levels = levels;
            this.mode = mode;
            this.lengthHeaders = new byte[][]{hex(shortHeader), hex(longHeader)};
        }
    }

    // Known reply formats exercised in WireChecks, ProtocolChecks and Wave1DriverFixChecks.
    private static final List<WireFixture> WIRE_FIXTURES = List.of(
            new WireFixture("vivo TWS Air3 Pro", EMPTY, 0,
                    hex("FF040005001B8207000064FF07"), hex("FF040004001B823000010400"),
                    new int[]{0, 100, 255, 7}, MODE_OFF, "FF040000001B8207", "FF0400FF001B0000"),
            new WireFixture("HUAWEI FreeBuds 6i", EMPTY, 0,
                    HuaweiProtocol.frame(0x01, 0x08, hex("0203505A640303000001")),
                    hex("5A0007002B2A010200010510"),
                    new int[]{80, 90, 100, 4}, MODE_ANC, "5A00000001", "5AFFFF0001"),
            // This documented profile is ready on a valid report, so a random auth challenge is unnecessary.
            new WireFixture("Redmi Buds 5", EMPTY, 0,
                    XiaomiProtocol.response(0x02, 4, 0, 0, hex("04076163FF")),
                    XiaomiProtocol.response(0xF3, 4, 0, 0, hex("04000B0102")),
                    new int[]{97, 99, 255, 0}, MODE_ANC, "FEDCBA04020000", "FEDCBA0402FFFF"),
            new WireFixture("OPPO Enco Free4", OppoProtocol.encode(0x8100, 1, hex("0000")), 2500,
                    OppoProtocol.encode(OppoProtocol.RSP_BATTERY, 2, hex("0002018002FF")),
                    hex("AA0C00000C812205000001011000"),
                    new int[]{0, 255, 255, 1}, MODE_ANC, "AA000000", "AAFFFFFF7F"),
            new WireFixture("EDIFIER FitBuds Turbo", EMPTY, 0,
                    hex("BBECF200060364640003117E"), hex("BBECCC0002100186"),
                    new int[]{100, 100, 255, -1}, MODE_ANC, "BBECF20000", "BBECF2FFFF"),
            new WireFixture("CERAMICS-U", EMPTY, 0,
                    hex("DD00040CE463FF33AA"), hex("DD00020901E9AA"),
                    new int[]{100, 99, 255, 1}, MODE_ANC, "DD0000", "DD00FF"),
            new WireFixture("NiceHCK OriG in", EMPTY, 0,
                    hex("4E0600000500005500"), hex("4E040000010103"),
                    new int[]{255, 85, 255, -1}, MODE_ANC, "4E000000", "4EFFFF00"),
            new WireFixture("WF-1000XM5", SonyProtocol.encode(12, 0, hex("0100030010020000")), 0,
                    SonyProtocol.encode(12, 1, hex("230132013C00")),
                    SonyProtocol.encode(12, 0, hex("671501010003000A")),
                    new int[]{50, 60, 255, 1}, MODE_ANC, "3E0C00000000003C", "3E0C000000FFFF3C"),
            new WireFixture("Technics EAH-AZ80", EMPTY, 0,
                    hex("055D0500D60C000164"), hex("055B06000A0000026432"),
                    new int[]{100, 255, 255, -1}, MODE_TRANS, "055B0000", "055BFFFF"));

    private static final class WireRig implements WireDriver.Port, Controls.Listener {
        final WireFixture fixture;
        final WireDriver driver;
        final CoreChecks.Clock clock = new CoreChecks.Clock();
        final List<Runnable> heldFlushes = new ArrayList<>();
        boolean holdFlushes;
        int sends, failures, events, batteryReports, noiseReports, writeCompletions;
        int[] battery;
        int mode = -1;

        WireRig(DriverModule module, WireFixture fixture) {
            this.fixture = fixture;
            driver = module.create();
            driver.bind(fixture.name, this, this);
        }

        void open() {
            driver.onOpen();
            if (fixture.hello.length != 0) driver.received(fixture.hello);
            clock.tick(fixture.readyAt);
        }

        @Override public void send(byte[] bytes, Runnable flushed) {
            sends++;
            if (flushed != null) {
                if (holdFlushes) heldFlushes.add(flushed);
                else clock.after(flushed, 0);
            }
            if (!holdFlushes && driver instanceof SonyClient && bytes[1] == 12) {
                byte[] ack = SonyProtocol.encode(1, 1 - bytes[2], EMPTY);
                clock.after(() -> driver.received(ack), 0);
            }
        }
        @Override public void after(Runnable task, long delay) { clock.after(task, delay); }
        @Override public void cancel(Runnable task) { clock.cancel(task); }
        @Override public void useBle(UUID service, UUID notify, UUID write, long delay, long timeout) { }
        @Override public void fail(String reason) { failures++; }
        @Override public long millis() { return clock.now; }
        @Override public void trace(String tag, String message) {
            if (message.startsWith("parse frame failed:")) throw new AssertionError(fixture.name + " " + message);
        }
        @Override public void onConnected(String name) { events++; }
        @Override public void onDisconnected(String reason) { events++; }
        @Override public void onError(String message) { events++; }
        @Override public void onBattery(int left, int right, int box, int flags) {
            battery = new int[]{left, right, box, flags};
            for (int level : new int[]{left, right, box}) {
                check(level == -1 || level == 255 || (level >= 0 && level <= 100),
                        fixture.name + " reported invalid battery " + level);
            }
            batteryReports++;
            events++;
        }
        @Override public void onNoiseMode(int value) {
            check(value >= -1 && value <= 2, fixture.name + " reported invalid noise mode " + value);
            mode = value;
            noiseReports++;
            events++;
        }
        @Override public void onSpatial(Boolean value) { events++; }
        @Override public void onDeepX(Integer value) { events++; }
        @Override public void onEqualizer(Integer value) { events++; }
        @Override public void onFeatureSpec(FeatureDescriptor spec) { events++; }
        @Override public void onFeatureValue(FeatureId id, int value) { events++; }
        @Override public void onFeatureText(FeatureId id, String text) { events++; }
    }

    private static WireFixture fixtureFor(DriverModule module) {
        WireFixture match = null;
        for (WireFixture fixture : WIRE_FIXTURES) {
            if (!module.matches(fixture.name)) continue;
            check(match == null, "ambiguous fixture for " + module.getClass().getName());
            match = fixture;
        }
        check(match != null, "missing fixture for " + module.getClass().getName());
        return match;
    }

    private static void wireNullAndEmptyChunksAreNoOps(DriverModule module, WireFixture fixture) {
        // Several clients used data.length before the decoder could reject a null chunk.
        WireRig rig = new WireRig(module, fixture);
        rig.driver.received(null);
        rig.driver.received(EMPTY);
        check(rig.events == 0 && rig.sends == 0, fixture.name + " emitted on empty input before open");
        rig.open();
        int events = rig.events;
        int sends = rig.sends;
        rig.driver.received(null);
        rig.driver.received(EMPTY);
        check(rig.events == events && rig.sends == sends, fixture.name + " emitted on empty input while open");
        rig.driver.onClose();
    }

    private static void receiveInChunks(WireRig rig, byte[] frame, int width) {
        int events = rig.events;
        for (int offset = 0; offset < frame.length; offset += width) {
            int end = Math.min(offset + width, frame.length);
            rig.driver.received(Arrays.copyOfRange(frame, offset, end));
            if (end < frame.length) {
                check(rig.events == events, rig.fixture.name + " published before frame completion at " + end);
            }
        }
    }

    private static void checkReports(WireRig rig) {
        WireFixture fixture = rig.fixture;
        check(rig.batteryReports == 1 && Arrays.equals(rig.battery, fixture.levels),
                fixture.name + " did not recover exactly one battery report: " + Arrays.toString(rig.battery));
        check(rig.noiseReports == 1 && rig.mode == fixture.mode,
                fixture.name + " did not recover exactly one noise report: " + rig.mode);
        check(rig.driver.isConnected(), fixture.name + " did not become ready");
    }

    private static void wireFragmentsAndLengthBounds(DriverModule module, WireFixture fixture) {
        for (int width = 1; width <= 3; width++) {
            WireRig rig = new WireRig(module, fixture);
            rig.open();
            int events = rig.events;
            for (byte[] header : fixture.lengthHeaders) {
                receiveInChunks(rig, header, width);
                // Drain a maximum plausible declared length before the next known-good frame.
                rig.driver.received(new byte[4096]);
            }
            check(rig.events == events, fixture.name + " treated malformed length probes as state");
            receiveInChunks(rig, fixture.battery, width);
            receiveInChunks(rig, fixture.noise, width);
            checkReports(rig);
            rig.driver.onClose();
        }
        WireRig joined = new WireRig(module, fixture);
        joined.open();
        joined.driver.received(concat(fixture.battery, fixture.noise));
        checkReports(joined);
        joined.driver.onClose();
    }

    private static void wireRandomInput(DriverModule module, WireFixture fixture, Random random) {
        WireRig rig = new WireRig(module, fixture);
        rig.open();
        rig.driver.received(concat(fixture.battery, fixture.noise));
        checkReports(rig);
        rig.driver.received(new byte[512]);
        byte[] ones = new byte[512];
        Arrays.fill(ones, (byte) 0xFF);
        rig.driver.received(ones);
        for (int sample = 0; sample < RANDOM_PAYLOAD_CASES; sample++) {
            int length = sample == 0 ? 1 : sample == 1 ? 512 : 1 + random.nextInt(512);
            byte[] bytes = new byte[length];
            random.nextBytes(bytes);
            rig.driver.received(bytes);
            if (sample % 32 == 0) {
                // Reverse two chunks of a known report and drop a byte to model a broken stream.
                int split = 1 + random.nextInt(fixture.noise.length - 2);
                rig.driver.received(Arrays.copyOfRange(fixture.noise, split + 1, fixture.noise.length));
                rig.driver.received(Arrays.copyOf(fixture.noise, split));
            }
            int value = INT_BOUNDARIES[random.nextInt(INT_BOUNDARIES.length)];
            switch (sample % 4) {
                case 0: rig.driver.queryBattery(); break;
                case 1: rig.driver.queryNoise(); break;
                case 2: rig.driver.changeNoise(value, () -> rig.writeCompletions++); break;
                case 3:
                    FeatureId id = FEATURES[random.nextInt(FEATURES.length)];
                    rig.driver.writeFeature(id, value, () -> rig.writeCompletions++);
                    break;
                default: throw new AssertionError("unreachable operation");
            }
            rig.clock.tick(11);
        }
        rig.driver.onClose();
    }

    private static void wireLateFramesAreIgnored(DriverModule module, WireFixture fixture) {
        // A valid report queued before close could publish both battery and feature callbacks after close.
        WireRig rig = new WireRig(module, fixture);
        rig.open();
        rig.driver.received(concat(fixture.battery, fixture.noise));
        checkReports(rig);
        rig.driver.received(Arrays.copyOf(fixture.battery, 2));
        rig.driver.onClose();
        int events = rig.events;
        int sends = rig.sends;
        rig.driver.received(Arrays.copyOfRange(fixture.battery, 2, fixture.battery.length));
        rig.driver.received(concat(fixture.battery, fixture.noise));
        check(rig.events == events && rig.sends == sends && !rig.driver.isConnected(),
                fixture.name + " published or wrote on a late report");
    }

    private static void wireLateTimersAreIgnored(DriverModule module, WireFixture fixture) {
        // An initialization timeout may already be queued when the driver closes.
        WireRig rig = new WireRig(module, fixture);
        rig.driver.onOpen();
        rig.clock.tick(0);
        List<Runnable> queued = new ArrayList<>();
        rig.clock.waiting.values().forEach(queued::addAll);
        rig.driver.onClose();
        check(rig.clock.waiting.values().stream().allMatch(List::isEmpty),
                fixture.name + " left protocol timers registered after close");
        int sends = rig.sends;
        int failures = rig.failures;
        int events = rig.events;
        queued.forEach(Runnable::run);
        rig.clock.tick(30_000);
        check(rig.sends == sends && rig.failures == failures && rig.events == events,
                fixture.name + " emitted from a timer after close");
    }

    private static void wireLateFlushesAreIgnored(DriverModule module, WireFixture fixture) {
        // A transport completion can outlive the session that registered the control write.
        WireRig rig = new WireRig(module, fixture);
        rig.open();
        rig.driver.received(concat(fixture.battery, fixture.noise));
        rig.clock.tick(0);
        rig.holdFlushes = true;
        rig.driver.changeNoise(MODE_TRANS, () -> rig.writeCompletions++);
        rig.driver.writeFeature(NOISE_MODE, MODE_OFF, () -> rig.writeCompletions++);
        rig.clock.tick(0);
        check(!rig.heldFlushes.isEmpty(), fixture.name + " did not queue a transport completion");
        rig.driver.onClose();
        int sends = rig.sends;
        int failures = rig.failures;
        int events = rig.events;
        int completions = rig.writeCompletions;
        rig.heldFlushes.forEach(Runnable::run);
        rig.clock.tick(30_000);
        check(rig.sends == sends && rig.failures == failures && rig.events == events
                        && rig.writeCompletions == completions,
                fixture.name + " completed a stale transport write");
    }

    private static void wireDrivers(Random random) {
        check(InstalledDrivers.ALL.length == 9 && WIRE_FIXTURES.size() == 9, "expected nine drivers");
        Set<String> tested = new HashSet<>();
        for (DriverModule module : InstalledDrivers.ALL) {
            WireFixture fixture = fixtureFor(module);
            check(tested.add(fixture.name), "duplicate driver fixture " + fixture.name);
            wireNullAndEmptyChunksAreNoOps(module, fixture);
            wireFragmentsAndLengthBounds(module, fixture);
            wireRandomInput(module, fixture, random);
            wireLateFramesAreIgnored(module, fixture);
            wireLateTimersAreIgnored(module, fixture);
            wireLateFlushesAreIgnored(module, fixture);
        }
    }

    private static void meshStateRoundTrips(Random random) {
        for (int i = 0; i < 160; i++) {
            String name = "左耳 \"WearEars\" \\声场 " + i + " 🎧";
            boolean connected = random.nextBoolean();
            LocalMeshProtocol.StateSnapshot source = new LocalMeshProtocol.StateSnapshot(
                    connected, connected && random.nextBoolean(), random.nextInt(64), random.nextInt(4) - 1,
                    BATTERY_BOUNDARIES[i % BATTERY_BOUNDARIES.length],
                    BATTERY_BOUNDARIES[random.nextInt(BATTERY_BOUNDARIES.length)],
                    BATTERY_BOUNDARIES[random.nextInt(BATTERY_BOUNDARIES.length)], random.nextInt(9) - 1,
                    random.nextInt(5) - 1, random.nextInt(3) - 1, random.nextInt(16) - 1,
                    random.nextInt(4) - 1, name);
            String wire = LocalMeshProtocol.encodeState(source);
            check(wire.getBytes(StandardCharsets.UTF_8).length <= LocalMeshProtocol.MAX_LINE_BYTES,
                    "STATE exceeded its UTF-8 byte budget at case " + i);
            LocalMeshProtocol.Frame decoded = LocalMeshProtocol.parseFrame(wire);
            check(decoded != null && decoded.type == LocalMeshProtocol.FrameType.STATE && decoded.state != null,
                    "STATE did not round-trip at case " + i);
            LocalMeshProtocol.StateSnapshot actual = decoded.state;
            check(actual.connected == source.connected && actual.parked == source.parked
                            && actual.caps == source.caps && actual.mode == source.mode,
                    "STATE changed flags, capabilities or mode at case " + i);
            check(actual.left == source.left && actual.right == source.right && actual.box == source.box
                            && actual.charge == source.charge,
                    "STATE changed battery at case " + i);
            check(actual.ancLevel == source.ancLevel && actual.windVal == source.windVal
                            && actual.eqVal == source.eqVal && actual.spatialVal == source.spatialVal,
                    "STATE changed features at case " + i);
            check(name.equals(actual.deviceName), "STATE changed the Unicode name at case " + i);
            check(wire.equals(LocalMeshProtocol.encodeState(actual)), "STATE re-encoded differently at case " + i);
        }
    }

    private static void meshRejects(String wire, String label) {
        check(LocalMeshProtocol.parseFrame(wire) == null, "accepted " + label);
    }

    private static void meshMalformedFrames(Random random) {
        String valid = "STATE|1|0|3|0|100|0|-1|7|3|1|2|1|耳机";
        String[] escapes = {"\\q", "\\x10", "\\u12G4", "\\u0"};
        String[] overflow = {"2147483648", "-2147483649", "9223372036854775807", "-9223372036854775808"};
        // WEAREARS_MESH_V1 is pipe-delimited. JSON, including invalid JSON escapes, is foreign input.
        for (int i = 0; i < 120; i++) {
            String json = "{\"type\":\"STATE\",\"deviceName\":\"耳机" + random.nextInt() + "\"}";
            meshRejects(json.substring(0, 1 + random.nextInt(json.length() - 1)), "truncated JSON " + i);
            meshRejects("{\"type\":\"STATE\",,\"deviceName\":" + i + "}", "malformed JSON " + i);
            meshRejects("{\"deviceName\":\"耳机" + escapes[i % escapes.length] + "\"}", "invalid JSON escape " + i);
            meshRejects("AUTH_OK|" + "耳".repeat(342 + i), "oversized UTF-8 AUTH_OK " + i);

            String[] fields = valid.split("\\|", -1);
            int field = 3 + i % 10;
            fields[field] = overflow[i / 10 % overflow.length];
            meshRejects(String.join("|", fields), "STATE integer overflow in field " + field + " case " + i);
            fields[field] = "";
            meshRejects(String.join("|", fields), "STATE missing integer in field " + field + " case " + i);
        }
        for (int i = 0; i <= valid.lastIndexOf('|'); i++) {
            meshRejects(valid.substring(0, i), "STATE cut at character " + i);
        }
        for (String wire : new String[]{null, " ", "HELLO|1234", "CMD|FEATURE|EQUALIZER", "CMD|NOISE|",
                "CMD|NOISE|-2147483648", "CMD|NOISE|2147483647", "CMD|NOISE|+1", "CMD|NOISE|1.0",
                "CMD|FEATURE|EQUALIZER|2147483648", "CMD|FEATURE|EQUALIZER|-2147483649",
                "AUTH_OK|one\ntwo", "AUTH_OK|one\rtwo", "AUTH_OK|one\0two"}) {
            meshRejects(wire, "invalid frame " + wire);
        }
    }

    private static void meshFrameTerminatorDoesNotUsePayloadBudget() throws IOException {
        // The stream writer appends LF after enforcing the payload cap. CRLF has the same payload budget.
        String prefix = "AUTH_OK|";
        int budget = LocalMeshProtocol.MAX_LINE_BYTES - prefix.length();
        String maxFrame = prefix + "耳".repeat(budget / 3) + "x".repeat(budget % 3);
        String oversized = maxFrame + "x";
        check(maxFrame.getBytes(StandardCharsets.UTF_8).length == 1024, "exact UTF-8 limit fixture");
        for (String ending : new String[]{"", "\n", "\r\n"}) {
            LocalMeshProtocol.Frame parsed = LocalMeshProtocol.parseFrame(maxFrame + ending);
            check(parsed != null && parsed.type == LocalMeshProtocol.FrameType.AUTH_OK
                            && parsed.name.equals("耳".repeat(LocalMeshProtocol.MAX_NAME_LEN)),
                    "1024-byte payload rejected with terminator length " + ending.length());
            meshRejects(oversized + ending, "1025-byte payload with terminator length " + ending.length());
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LocalMeshProtocol.writeFrame(output, maxFrame);
        byte[] wire = output.toByteArray();
        check(wire.length == 1025 && wire[wire.length - 1] == '\n', "writer appends LF outside the byte cap");
        check(maxFrame.equals(LocalMeshProtocol.readLineBounded(new ByteArrayInputStream(wire))),
                "writer output at cap is accepted by bounded reader");
        ByteArrayInputStream crlf = new ByteArrayInputStream((maxFrame + "\r\nPING\n").getBytes(StandardCharsets.UTF_8));
        check(maxFrame.equals(LocalMeshProtocol.readLineBounded(crlf))
                        && "PING".equals(LocalMeshProtocol.readLineBounded(crlf)),
                "CRLF at payload cap preserves the following frame");
        try {
            LocalMeshProtocol.readLineBounded(new ByteArrayInputStream((oversized + "\n").getBytes(StandardCharsets.UTF_8)));
            throw new AssertionError("stream accepted 1025 payload bytes");
        } catch (IOException expected) {
            check(expected.getMessage().contains("exceeds 1024 bytes"), "wrong oversized read error");
        }
        try {
            LocalMeshProtocol.writeFrame(output, oversized);
            throw new AssertionError("writer accepted 1025 payload bytes");
        } catch (IOException expected) {
            check(output.size() == wire.length && expected.getMessage().contains("exceeds 1024 bytes"),
                    "oversized write changed the output stream or reported the wrong error");
        }
    }

    private static void decision(LocalMeshFirewall.Decision actual, String reason, String label) {
        check(actual.allowed == REASON_ALLOW.equals(reason) && reason.equals(actual.reasonCode),
                label + " expected " + reason + ", got " + actual);
    }

    private static void firewallAddressAndRateBoundaries() {
        CoreChecks.Clock clock = new CoreChecks.Clock();
        LocalMeshFirewall fw = new LocalMeshFirewall(() -> clock.now);
        fw.setSameSubnetOnly(false);
        for (String ip : new String[]{"10.0.0.0", "10.255.255.255", "172.16.0.0", "172.31.255.255",
                "192.168.0.0", "192.168.255.255", "169.254.0.0", "169.254.255.255", "127.0.0.1",
                "::1", "fc00::1", "fdff::1", "fe80::1%wlan0", "febf::1"}) {
            check(LocalMeshProtocol.isPrivateLanAddress(ip), "private literal rejected " + ip);
            decision(fw.checkIncomingConnection(ip, HOST), REASON_ALLOW, ip);
        }
        for (String ip : new String[]{"9.255.255.255", "11.0.0.0", "172.15.255.255", "172.32.0.0",
                "192.167.255.255", "192.169.0.0", "169.253.255.255", "169.255.0.0", "0.0.0.0", "8.8.8.8",
                "localhost", "10.1.2", "10.01.2.3", "192.168.1.256", "192..168.1", "10.0.0.2:123",
                "fe80:::1", "fe80::1%", "fec0::1", "2001:db8::1", "::ffff:192.168.1.1", "", null}) {
            check(!LocalMeshProtocol.isPrivateLanAddress(ip), "non-private or malformed literal accepted " + ip);
            decision(fw.checkIncomingConnection(ip, HOST), REASON_NON_PRIVATE_IP, ip);
        }
        fw.setSameSubnetOnly(true);
        decision(fw.checkIncomingConnection("192.168.15.25", HOST), REASON_CROSS_SUBNET, "adjacent /24");
        LocalMeshProtocol.Frame read = LocalMeshProtocol.Frame.cmdRefresh();
        for (int i = 0; i < MAX_CONNS_PER_10S; i++) {
            decision(fw.checkIncomingConnection(PEER, HOST), REASON_ALLOW, "connection slot " + i);
        }
        for (int i = 0; i < MAX_CMDS_PER_10S; i++) {
            decision(fw.checkCommand(PEER, read), REASON_ALLOW, "read slot " + i);
        }
        clock.tick(RATE_WINDOW_MS - 1);
        decision(fw.checkIncomingConnection(PEER, HOST), REASON_CONN_RATE_LIMIT, "connection before reset");
        decision(fw.checkCommand(PEER, read), REASON_CMD_RATE_LIMIT, "command before reset");
        clock.tick(1);
        decision(fw.checkIncomingConnection(PEER, HOST), REASON_ALLOW, "connection at reset");
        decision(fw.checkCommand(PEER, read), REASON_ALLOW, "command at reset");
    }

    private static void firewallAuthCooldownAndClear() {
        CoreChecks.Clock clock = new CoreChecks.Clock();
        LocalMeshFirewall fw = new LocalMeshFirewall(() -> clock.now);
        for (int i = 1; i < MAX_AUTH_FAILS; i++) {
            check(!fw.recordAuthFailure(PEER) && !fw.isBanned(PEER), "ban before threshold " + i);
        }
        fw.recordAuthSuccess(PEER);
        for (int i = 1; i < MAX_AUTH_FAILS; i++) {
            check(!fw.recordAuthFailure(PEER), "auth success kept a previous failure " + i);
        }
        check(fw.recordAuthFailure(PEER) && fw.isBanned(PEER) && fw.bannedIpCount() == 1, "threshold starts lockout");
        clock.tick(AUTH_LOCKOUT_MS - 1);
        decision(fw.checkIncomingConnection(PEER, HOST), REASON_IP_BANNED, "1 ms before lockout expiry");
        decision(fw.checkCommand(PEER, LocalMeshProtocol.Frame.cmdRefresh()), REASON_IP_BANNED, "banned peer reading");
        clock.tick(1);
        check(!fw.isBanned(PEER) && fw.bannedIpCount() == 0, "lockout did not expire at boundary");
        decision(fw.checkIncomingConnection(PEER, HOST), REASON_ALLOW, "at lockout expiry");
        for (int i = 1; i <= MAX_AUTH_FAILS; i++) {
            check(fw.recordAuthFailure(PEER) == (i == MAX_AUTH_FAILS), "threshold after expiry " + i);
        }
        String another = "192.168.14.26";
        for (int i = 1; i < MAX_AUTH_FAILS; i++) {
            check(!fw.recordAuthFailure(another), "partial failure count for another peer " + i);
        }
        fw.clearBans();
        check(fw.bannedIpCount() == 0 && !fw.isBanned(PEER), "clearBans kept an active ban");
        check(!fw.recordAuthFailure(another), "clearBans kept pre-threshold failures");
        decision(fw.checkIncomingConnection(PEER, HOST), REASON_ALLOW, "after clearBans");
    }

    private static void firewallReadOnlyAllowsOnlyReads() {
        // Write-only filtering left HELLO and server STATE frames admissible after authentication.
        LocalMeshFirewall fw = new LocalMeshFirewall(() -> 1000L);
        fw.setReadOnlyMode(true);
        for (String wire : new String[]{"CMD|REFRESH", "PING", "PONG"}) {
            decision(fw.checkCommand(PEER, LocalMeshProtocol.parseFrame(wire)), REASON_ALLOW, wire);
        }
        for (String wire : new String[]{"CMD|NOISE|1", "CMD|FEATURE|EQUALIZER|3", "HELLO|1234|Peer",
                "AUTH_OK|Peer", "AUTH_ERR", LocalMeshProtocol.encodeState(LocalMeshProtocol.StateSnapshot.disconnected())}) {
            LocalMeshProtocol.Frame frame = LocalMeshProtocol.parseFrame(wire);
            check(frame != null, "read-only test needs a decoded " + wire);
            decision(fw.checkCommand(PEER, frame), REASON_READ_ONLY_MODE, wire);
        }
        decision(fw.checkCommand(PEER, null), REASON_INVALID_FRAME, "unknown frame");
        fw.setReadOnlyMode(false);
        decision(fw.checkCommand(PEER, LocalMeshProtocol.Frame.cmdNoise(MODE_OFF)), REASON_ALLOW, "writes re-enabled");
    }

    private static void firewallConcurrentAccess() throws Exception {
        int threads = 4;
        int iterations = 1000;
        long now = 1000L;
        LocalMeshFirewall fw = new LocalMeshFirewall(() -> now);
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Void>> workers = new ArrayList<>();
        try {
            for (int worker = 0; worker < threads; worker++) {
                String ip = "192.168.14." + (30 + worker);
                workers.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    for (int i = 1; i <= iterations; i++) {
                        check(fw.addAllowedIp(ip), "allowlist lost removal for " + ip);
                        Set<String> addresses = fw.allowlistSnapshot();
                        check(fw.removeAllowedIp(ip) && addresses.contains(ip), "allowlist snapshot mutated for " + ip);
                        check(fw.recordAuthFailure(ip) == (i % MAX_AUTH_FAILS == 0), "auth counter race for " + ip);
                        decision(fw.checkIncomingConnection("8.8.8.8", HOST), REASON_NON_PRIVATE_IP, "concurrent denial");
                        List<LocalMeshFirewall.AuditEvent> audit = fw.recentEvents();
                        check(!audit.isEmpty() && audit.size() <= MAX_AUDIT_EVENTS, "unbounded audit snapshot");
                        check(audit.stream().allMatch(event -> event.timeMs == now && !event.ip.isEmpty()
                                        && !event.reason.isEmpty()), "invalid event in a concurrent audit snapshot");
                    }
                    return null;
                }));
            }
            for (Future<Void> worker : workers) worker.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        check(fw.blockedCount() == threads * iterations, "concurrent denials lost an increment");
        check(fw.bannedIpCount() == threads && fw.allowlistSnapshot().isEmpty(), "concurrent peer state was lost");
        check(fw.recentEvents().size() == MAX_AUDIT_EVENTS, "audit ring did not settle at its capacity");
    }

    private static float renderedPeak(float[] left, float[] right, int frames, String label) {
        float peak = 0.0f;
        for (int i = 0; i < frames; i++) {
            peak = Math.max(peak, Math.max(Math.abs(left[i]), Math.abs(right[i])));
        }
        check(Float.isFinite(peak), label + " rendered a non-finite sample");
        check(peak <= 1.0f, label + " clipped at " + peak);
        check(Float.isNaN(left[frames]) && Float.isNaN(right[frames]), label + " overwrote the tail");
        return peak;
    }

    private static void dspExtremeParameters(Random random) {
        int frames = 4 * WearEarsBinauralKernel.BLOCK_SIZE + 1;
        float[] inputL = new float[frames], inputR = new float[frames];
        float[] outL = new float[frames + 1], outR = new float[frames + 1];
        for (int i = 0; i < frames; i++) {
            inputL[i] = i < 640 ? 10.0f : 20.0f * random.nextFloat() - 10.0f;
            inputR[i] = i < 640 ? 10.0f : 20.0f * random.nextFloat() - 10.0f;
        }
        inputL[711] = Float.NaN;
        inputL[817] = Float.POSITIVE_INFINITY;
        inputR[1000] = Float.NEGATIVE_INFINITY;
        int[] scenes = {WearEarsBinauralKernel.SCENE_WIDE, WearEarsBinauralKernel.SCENE_CINEMA,
                WearEarsBinauralKernel.SCENE_CONCERT, WearEarsBinauralKernel.SCENE_STUDIO};
        float[] angles = {-720, -630, -540, -450, -360, -270, -180, -179.9f, -90, -0.01f, 0, 90,
                179.9f, 180, 270, 360, 450, 540, 630, 720, 135, Float.NaN, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY};
        int[] extremes = {Integer.MIN_VALUE, Integer.MAX_VALUE};
        WearEarsBinauralKernel mono = new WearEarsBinauralKernel();
        WearEarsBinauralKernel stereo = new WearEarsBinauralKernel();
        for (int scene : scenes) {
            mono.setScene(scene);
            stereo.setScene(scene);
            for (int width : extremes) {
                mono.setHeadWidthPct(width);
                stereo.setHeadWidthPct(width);
                int expectedWidth = width == Integer.MIN_VALUE ? 85 : 115;
                check(mono.getHeadWidthPct() == expectedWidth, "head width clamp " + width);
                for (int pinna : extremes) {
                    mono.setPinnaNotchHz(pinna);
                    stereo.setPinnaNotchHz(pinna);
                    int expectedPinna = pinna == Integer.MIN_VALUE ? 7500 : 9000;
                    check(mono.getPinnaNotchHz() == expectedPinna, "pinna notch clamp " + pinna);
                    for (float angle : angles) {
                        float previous = mono.getAzimuthDeg();
                        mono.setAzimuthDeg(angle);
                        stereo.setAzimuthDeg(angle);
                        float applied = mono.getAzimuthDeg();
                        String label = "scene=" + scene + " azimuth=" + angle + " width=" + width + " pinna=" + pinna;
                        check(Float.isFinite(applied) && applied >= -180.0f && applied < 180.0f,
                                label + " azimuth outside the wrapped domain");
                        if (Float.isFinite(angle)) {
                            float turns = (angle - applied) / 360.0f;
                            check(Math.abs(turns - Math.rint(turns)) < 1.0e-6, label + " wrapped to the wrong direction");
                        } else {
                            check(previous == applied, label + " replaced the last finite azimuth");
                        }
                        Arrays.fill(outL, Float.NaN);
                        Arrays.fill(outR, Float.NaN);
                        mono.processMono(inputL, outL, outR, frames);
                        check(renderedPeak(outL, outR, frames, "mono " + label) > 0.85f, "mono did not reach the limiter");
                        Arrays.fill(outL, Float.NaN);
                        Arrays.fill(outR, Float.NaN);
                        stereo.processStereo(inputL, inputR, outL, outR, frames);
                        check(renderedPeak(outL, outR, frames, "stereo " + label) > 0.85f, "stereo did not reach the limiter");
                    }
                }
            }
        }
    }

    private static void dspFiniteOverdriveCannotPoisonSubsequentAudio() {
        // Even a finite sample can overflow the Hermite and filter intermediates and leave the DSP silent.
        int frames = 4097;
        float[] input = new float[frames], left = new float[frames + 1], right = new float[frames + 1];
        for (int i = 1; i < frames; i++) input[i] = (float) (0.25 * Math.sin(i * 0.2));
        input[0] = Float.MAX_VALUE;
        WearEarsBinauralKernel kernel = new WearEarsBinauralKernel();
        kernel.setAzimuthDeg(89);
        for (boolean stereo : new boolean[]{false, true}) {
            kernel.reset();
            Arrays.fill(left, Float.NaN);
            Arrays.fill(right, Float.NaN);
            if (stereo) kernel.processStereo(input, input, left, right, frames);
            else kernel.processMono(input, left, right, frames);
            renderedPeak(left, right, frames, "finite overdrive stereo=" + stereo);
            float tailLeft = 0.0f, tailRight = 0.0f;
            for (int i = frames - 256; i < frames; i++) {
                tailLeft = Math.max(tailLeft, Math.abs(left[i]));
                tailRight = Math.max(tailRight, Math.abs(right[i]));
            }
            check(tailLeft > 0.02f && tailRight > 0.02f, "overdrive poisoned later audio, stereo=" + stereo
                    + " tail peaks=" + tailLeft + "/" + tailRight);
        }
    }

    private static final FeatureDescriptor LOW_LATENCY_SPEC = FeatureDescriptor.toggle(LOW_LATENCY, VERIF_REAL, false);
    private static final FeatureDescriptor MULTIPOINT_SPEC = FeatureDescriptor.toggle(MULTIPOINT, VERIF_REAL, false);
    private static final FeatureDescriptor FIND_SPEC = FeatureDescriptor.findBuds(VERIF_REAL, false);
    private static final FeatureDescriptor WEAR_SPEC = FeatureDescriptor.readOnly(WEAR_STATUS, VERIF_REAL, false);
    private static final OptionItem ONE = new OptionItem(1, "标准", "Standard");
    private static final FeatureDescriptor ANC_SPEC = FeatureDescriptor.choice(ANC_LEVEL, VERIF_REAL,
            FeatureDescriptor.SAFETY_NONE, 1 << MODE_ANC, false, ONE);
    private static final FeatureDescriptor[] STATE_SPECS = {
            ANC_SPEC, FeatureDescriptor.choice(TRANS_MODE, VERIF_REAL,
                    FeatureDescriptor.SAFETY_NONE, 1 << MODE_TRANS, false, ONE),
            FeatureDescriptor.toggle(WIND_REDUCTION, VERIF_REAL, false),
            FeatureDescriptor.toggle(SPATIAL_AUDIO, VERIF_REAL, false),
            FeatureDescriptor.slider(SPATIAL_SCENE, VERIF_REAL, FeatureDescriptor.SAFETY_NONE, 0, false, 0, 3),
            FeatureDescriptor.choice(EQUALIZER, VERIF_REAL, false, ONE),
            LOW_LATENCY_SPEC, MULTIPOINT_SPEC, WEAR_SPEC, FIND_SPEC,
            FeatureDescriptor.readOnly(FIRMWARE_INFO, VERIF_REAL, false)
    };

    private static final class TxWrite {
        final FeatureTransactions.Request request;
        final BooleanSupplier claim;
        final Runnable flushed;
        boolean claimed;

        TxWrite(FeatureTransactions.Request request, BooleanSupplier claim, Runnable flushed) {
            this.request = request;
            this.claim = claim;
            this.flushed = flushed;
        }
    }

    private static final class TxRig implements FeatureTransactions.Host {
        final DeviceState state = new DeviceState();
        final CoreChecks.Clock clock = new CoreChecks.Clock();
        final FeatureTransactions tx = new FeatureTransactions(this);
        final List<TxWrite> writes = new ArrayList<>();
        final List<FeatureId> reads = new ArrayList<>();
        final EnumMap<FeatureId, Integer> pending = new EnumMap<>(FeatureId.class);
        final EnumMap<FeatureId, Boolean> outcomes = new EnumMap<>(FeatureId.class);

        @Override public boolean live() { return state.connected && !state.parked; }
        @Override public boolean writable(FeatureId id, int value) {
            FeatureDescriptor spec = state.featureSpec(id);
            return state.isFeatureWritable(id) && spec.isValidValue(value)
                    && (id != FIND_MY_BUDS || value == 0 || !state.isAnyBudInEar());
        }
        @Override public boolean featureReady(FeatureId id) {
            boolean waitingForWear = id == FIND_MY_BUDS && tx.pending(id) > 0
                    && state.featureSpec(WEAR_STATUS) != null && state.wearStatus == DeviceState.WEAR_UNKNOWN;
            return live() && !waitingForWear && state.isFeatureConfirmedLive(id);
        }
        @Override public Integer currentValue(FeatureId id) { return state.featureValue(id); }
        @Override public void read(FeatureId id) { reads.add(id); }
        @Override public void write(FeatureId id, int value, BooleanSupplier claim, Runnable flushed) {
            check(live() && featureReady(id) && writable(id, value), "transaction wrote without current evidence for " + id);
            FeatureTransactions.Request request = tx.receipt(id);
            check(request != null && request.value == value, "write did not match pending request for " + id);
            writes.add(new TxWrite(request, claim, flushed));
        }
        @Override public void later(Runnable work, long delay) { clock.after(work, delay); }
        @Override public void cancel(Runnable work) { clock.cancel(work); }
        @Override public void changed(FeatureId id, int value, boolean active, boolean success) {
            if (active) {
                check(!success, "pending transaction cannot be successful yet");
                pending.put(id, value);
                outcomes.remove(id);
            } else {
                check(Objects.equals(pending.remove(id), value), "completion did not match the displayed request");
                outcomes.put(id, success);
            }
        }

        void connect() {
            state.connected = true;
            state.parked = false;
            tx.connected();
        }

        void wear(int status) {
            state.featureValue(WEAR_STATUS, status);
            tx.onWearStatus(state.wearStatus);
            tx.onFeatureReady(FIND_MY_BUDS);
        }
    }

    private static void hearingProtectionAndParkedEvidence() {
        for (int target = 0; target <= 3; target++) {
            meshRejects("CMD|FEATURE|FIND_MY_BUDS|" + target, "remote ring target " + target);
            meshRejects("CMD|FIND_MY_BUDS|" + target, "remote ring command " + target);
        }
        meshRejects("CMD|FEATURE|UNKNOWN_FEATURE|1", "unknown remote feature");
        meshRejects("CMD|UNKNOWN_CONTROL|1", "unknown remote control");

        TxRig rig = new TxRig();
        DeviceState state = rig.state;
        state.begin("A", "Fixture", CAP_BATTERY, CAP_BATTERY);
        rig.connect();
        state.featureSpec(FIND_SPEC);
        state.featureSpec(WEAR_SPEC);
        state.battery(48, 49, 50, 0);
        for (int wear = DeviceState.WEAR_LEFT; wear <= DeviceState.WEAR_BOTH; wear++) {
            rig.wear(wear);
            for (int target = 1; target <= 3; target++) {
                check(state.isTargetInEar(target) == ((wear & target) != 0), "wear mask " + wear + "/" + target);
                check(!rig.tx.begin(FIND_MY_BUDS, target), "ring admitted with wear mask " + wear + "/" + target);
            }
        }
        check(rig.writes.isEmpty() && !rig.tx.isFindBudsRinging(), "in-ear rejection emitted a write");

        rig.wear(DeviceState.WEAR_NONE);
        state.enterParked();
        rig.tx.resetWearStatus();
        check(state.wearStatus == DeviceState.WEAR_UNKNOWN && state.featureValue(WEAR_STATUS) == null
                        && state.liveConfirmedFeatures.isEmpty(), "park retained stale wear evidence");
        // Seed a stale report before resume so this assertion exercises resume's own invalidation.
        state.featureValue(WEAR_STATUS, DeviceState.WEAR_NONE);
        state.featureSpec(FIND_SPEC);
        state.resumeParked("A", "Fixture");
        check(state.wearStatus == DeviceState.WEAR_UNKNOWN && state.featureValue(WEAR_STATUS) == null
                        && state.liveConfirmedFeatures.isEmpty(), "resume retained stale wear evidence");
        check(state.left == 48 && state.right == 49 && state.box == 50 && state.isFeatureWritable(FIND_MY_BUDS),
                "wake lost the parked snapshot");

        check(rig.tx.begin(FIND_MY_BUDS, 3), "parked ring request did not enter the waiting lane");
        rig.connect();
        state.featureSpec(FIND_SPEC);
        rig.tx.onFeatureReady(FIND_MY_BUDS);
        check(rig.writes.isEmpty() && rig.tx.busy(FIND_MY_BUDS), "stale not-worn state unlocked a parked ring");
        rig.wear(DeviceState.WEAR_RIGHT);
        check(rig.writes.isEmpty() && !rig.tx.busy(FIND_MY_BUDS) && !rig.tx.isFindBudsRinging(),
                "fresh in-ear report failed to cancel a parked request");

        rig.wear(DeviceState.WEAR_NONE);
        check(rig.tx.begin(FIND_MY_BUDS, 2) && rig.writes.size() == 1, "fresh not-worn report did not permit ringing");
        TxWrite beforeInsertion = rig.writes.get(0);
        rig.wear(DeviceState.WEAR_RIGHT);
        check(!beforeInsertion.claim.getAsBoolean() && !rig.tx.isFindBudsRinging()
                        && !rig.tx.busy(FIND_MY_BUDS), "insertion before flush left the ring write claimable");

        rig.wear(DeviceState.WEAR_NONE);
        check(rig.tx.begin(FIND_MY_BUDS, 2), "could not start a fresh ring after cancellation");
        TxWrite sent = rig.writes.get(1);
        check(sent.claim.getAsBoolean(), "could not claim the new ring start");
        sent.flushed.run();
        rig.wear(DeviceState.WEAR_RIGHT);
        check(rig.writes.size() == 3 && rig.writes.get(2).request.value == 0
                        && !rig.tx.isFindBudsRinging(), "insertion after flush did not queue a stop command");
    }

    private static void featureTimeoutAndCancellationRollBack() {
        TxRig rig = new TxRig();
        rig.state.begin("A", "Fixture", CAP_NOISE, CAP_NOISE);
        rig.connect();
        rig.state.featureSpec(LOW_LATENCY_SPEC);
        rig.state.featureValue(LOW_LATENCY, 0);
        rig.state.featureSpec(ANC_SPEC);
        rig.state.featureValue(NOISE_MODE, MODE_ANC);

        check(rig.tx.begin(LOW_LATENCY, 1), "start timeout test");
        TxWrite write = rig.writes.get(0);
        check(write.claim.getAsBoolean(), "could not claim the timed write");
        write.flushed.run();
        rig.clock.tick(999);
        check(rig.reads.isEmpty(), "readback ran before 1000 ms");
        rig.clock.tick(1);
        check(rig.reads.equals(List.of(LOW_LATENCY)), "readback did not run at 1000 ms");
        rig.clock.tick(5999);
        check(rig.tx.pending(LOW_LATENCY) == 1 && rig.outcomes.isEmpty(), "write timed out before 7000 ms");
        rig.clock.tick(1);
        check(!rig.tx.busy() && rig.pending.isEmpty() && Boolean.FALSE.equals(rig.outcomes.get(LOW_LATENCY))
                        && rig.state.featureValue(LOW_LATENCY) == 0, "timeout did not restore the confirmed value");
        write.flushed.run();
        rig.tx.report(write.request, LOW_LATENCY, 1, true);
        rig.clock.tick(1000);
        check(rig.reads.size() == 1 && Boolean.FALSE.equals(rig.outcomes.get(LOW_LATENCY)),
                "late flush or receipt revived a timed-out write");

        check(rig.tx.begin(LOW_LATENCY, 0) && rig.writes.size() == 2,
                "countermand after a dispatched timeout must write even when the confirmed value matches");
        TxWrite countermand = rig.writes.get(1);
        check(rig.tx.begin(ANC_LEVEL, 1) && rig.writes.size() == 3, "start independent ANC lane");
        TxWrite anc = rig.writes.get(2);
        rig.tx.onTopModeBegin(MODE_OFF);
        check(!anc.claim.getAsBoolean() && !rig.tx.busy(ANC_LEVEL) && Boolean.FALSE.equals(rig.outcomes.get(ANC_LEVEL))
                        && rig.tx.busy(LOW_LATENCY), "mode switch did not cancel only the dependent lane");
        rig.tx.abortQueued(LOW_LATENCY, countermand.claim);
        countermand.flushed.run();
        rig.clock.tick(7000);
        check(!rig.tx.busy() && rig.pending.isEmpty() && Boolean.FALSE.equals(rig.outcomes.get(LOW_LATENCY))
                        && rig.state.featureValue(LOW_LATENCY) == 0 && rig.reads.size() == 1,
                "aborted queue changed the confirmed value or left timers running");
    }

    private static final int[] CAPABILITIES = {CAP_NOISE, CAP_BATTERY, CAP_BATTERY_SINGLE,
            CAP_SPATIAL, CAP_DEEPX, CAP_EQUALIZER};

    private static boolean expectedWritable(DeviceState state, FeatureId id, boolean installed, boolean hasValue) {
        if (!state.connected || !installed) return false;
        switch (id) {
            case WEAR_STATUS:
            case FIRMWARE_INFO: return false;
            case EQUALIZER:
            case FIND_MY_BUDS: return true;
            case ANC_LEVEL: return (state.caps & CAP_NOISE) != 0 && state.mode == MODE_ANC;
            case TRANS_MODE: return (state.caps & CAP_NOISE) != 0 && state.mode == MODE_TRANS;
            default: return hasValue;
        }
    }

    private static void disconnectedSnapshot(DeviceState state, String label) {
        check(!state.connected && !state.parked && state.left == -1 && state.right == -1 && state.box == -1
                        && state.charge == -1 && state.mode == -1 && !state.hasSyncedData(), label + " retained wire state");
        check(state.spatial == null && state.equalizer == null && state.spatialRevision == 0
                        && state.equalizerRevision == 0 && state.wearStatus == DeviceState.WEAR_UNKNOWN
                        && state.firmware.isEmpty() && state.limitation.isEmpty(), label + " retained derived state");
        check(state.features.isEmpty() && state.liveConfirmedFeatures.isEmpty() && state.featureValues.isEmpty()
                        && state.featureTexts.isEmpty() && state.featureRevisions.isEmpty(), label + " retained feature evidence");
    }

    private static void checkStateMachine(TxRig rig, EnumSet<FeatureId> installed, EnumSet<FeatureId> confirmed,
                                          EnumMap<FeatureId, Integer> values, String label) {
        DeviceState state = rig.state;
        check(state.features.keySet().equals(installed), label + " descriptors changed unexpectedly");
        check(state.liveConfirmedFeatures.equals(confirmed), label + " live confirmation set changed unexpectedly");
        check(state.featureValues.equals(values), label + " confirmed values changed outside of a report");
        check(!state.parked || state.connected, label + " has disconnected parked state");
        check((state.potentialCaps & state.caps) == state.caps, label + " confirmed capability missing from potential caps");
        for (int cap : CAPABILITIES) {
            int expected = state.connected && (state.caps & cap) != 0 ? DeviceState.SUPPORTED
                    : (state.potentialCaps & cap) != 0 ? DeviceState.UNKNOWN : DeviceState.UNSUPPORTED;
            check(state.capability(cap) == expected, label + " capability " + cap);
        }
        for (FeatureId id : FEATURES) {
            boolean expectedLive = confirmed.contains(id);
            if (id == ANC_LEVEL || id == TRANS_MODE) expectedLive &= (state.caps & CAP_NOISE) != 0;
            if (id == SPATIAL_AUDIO) expectedLive |= (state.caps & CAP_SPATIAL) != 0 && state.spatial != null;
            if (id == EQUALIZER) expectedLive |= (state.caps & (CAP_DEEPX | CAP_EQUALIZER)) != 0;
            check(state.isFeatureConfirmedLive(id) == (rig.live() && expectedLive), label + " live confirmation for " + id);
            check(state.isFeatureWritable(id) == expectedWritable(state, id, installed.contains(id), values.containsKey(id)),
                    label + " writable flag for " + id);
            Integer pending = rig.pending.get(id);
            FeatureTransactions.Request receipt = rig.tx.receipt(id);
            check(rig.tx.busy(id) == (pending != null) && rig.tx.pending(id) == (pending == null ? -1 : pending)
                            && (receipt == null ? pending == null : Objects.equals(receipt.value, pending)),
                    label + " pending request disagrees with change notification for " + id);
        }
        check(rig.tx.busy() == !rig.pending.isEmpty(), label + " global busy flag disagrees with lane state");
        check(!state.isFeatureWritable(null) && !state.isFeatureConfirmedLive(null), label + " null feature was admitted");
    }

    private static void randomBattery(DeviceState state, Random random, String label) {
        int[] before = {state.left, state.right, state.box};
        int charge = state.charge;
        int[] levels = {-2, -1, 0, 1, 99, 100, 101, 255};
        int[] report = new int[3];
        int flags = random.nextInt(10) - 1;
        boolean split = (state.caps & CAP_BATTERY_SINGLE) == 0;
        for (int i = 0; i < report.length; i++) {
            report[i] = levels[random.nextInt(levels.length)];
            if (state.connected && (i == 0 || split) && report[i] >= 0 && report[i] <= 100) before[i] = report[i];
        }
        if (state.connected && split && flags >= 0 && flags <= 7) charge = flags;
        state.battery(report[0], report[1], report[2], flags);
        check(state.left == before[0] && state.right == before[1] && state.box == before[2] && state.charge == charge,
                label + " battery fragment changed an unrelated, invalid or disconnected slot");
    }

    private static TxWrite sampleWrite(TxRig rig, Random random, boolean wantsClaim) {
        if (rig.writes.isEmpty()) return null;
        if (random.nextInt(4) != 0) {
            for (int i = rig.writes.size() - 1; i >= 0; i--) {
                TxWrite candidate = rig.writes.get(i);
                if (rig.tx.receipt(candidate.request.id) == candidate.request && candidate.claimed != wantsClaim) {
                    return candidate;
                }
            }
        }
        // Keep old receipts in the sample space to exercise supersession and disconnect.
        return rig.writes.get(random.nextInt(rig.writes.size()));
    }

    private static void randomStateTransitions(Random random) {
        TxRig rig = new TxRig();
        DeviceState state = rig.state;
        EnumSet<FeatureId> installed = EnumSet.noneOf(FeatureId.class);
        EnumSet<FeatureId> confirmed = EnumSet.noneOf(FeatureId.class);
        EnumMap<FeatureId, Integer> values = new EnumMap<>(FeatureId.class);
        int claims = 0, acknowledgments = 0, staleClaims = 0, unmatchedReports = 0, parks = 0, disconnects = 0;
        for (int step = 0; step < 1000; step++) {
            int operation = random.nextInt(18);
            String label = "step " + step + " operation " + operation;
            FeatureDescriptor spec = STATE_SPECS[random.nextInt(STATE_SPECS.length)];
            FeatureId id = spec.id;
            FeatureId lane = random.nextBoolean() ? LOW_LATENCY : MULTIPOINT;
            switch (operation) {
                case 0:
                case 1:
                    rig.tx.linkEnded();
                    installed.clear();
                    confirmed.clear();
                    values.clear();
                    if (operation == 0) {
                        state.disconnect();
                        check(state.caps == 0, label + " disconnect retained confirmed caps");
                        disconnects++;
                    } else {
                        long session = state.session;
                        state.begin(random.nextBoolean() ? "A" : "B", "Fixture", CAP_BATTERY, CAP_NOISE);
                        check(state.session == session + 1, label + " begin did not advance the session");
                    }
                    disconnectedSnapshot(state, label);
                    check(!rig.tx.busy() && !rig.tx.isFindBudsRinging(), label + " link end left a pending operation");
                    break;
                case 2:
                    rig.connect();
                    // Connection evidence supplies initial values for two independent transaction lanes.
                    for (FeatureDescriptor initial : List.of(LOW_LATENCY_SPEC, MULTIPOINT_SPEC)) {
                        state.featureSpec(initial);
                        installed.add(initial.id);
                        confirmed.add(initial.id);
                        if (!values.containsKey(initial.id)) {
                            state.featureValue(initial.id, 0);
                            values.put(initial.id, 0);
                        }
                        rig.tx.onFeatureReady(initial.id);
                    }
                    break;
                case 3:
                case 4:
                    if (operation == 3 && state.connected && !rig.tx.busy()) {
                        state.enterParked();
                        parks++;
                    } else if (operation == 4 && state.parked) {
                        state.resumeParked(state.address, state.name);
                    } else break;
                    rig.tx.resetWearStatus();
                    confirmed.clear();
                    values.remove(WEAR_STATUS);
                    check(state.wearStatus == DeviceState.WEAR_UNKNOWN, label + " wake or park retained wear");
                    break;
                case 5: {
                    int caps = random.nextInt(64), potential = random.nextInt(64);
                    boolean changedTopology = ((state.caps ^ caps) & CAP_BATTERY_SINGLE) != 0;
                    state.capabilities(caps, potential);
                    check(state.caps == caps && state.potentialCaps == (caps | potential), label + " caps merge");
                    if (changedTopology) {
                        check(state.left == -1 && state.right == -1 && state.box == -1 && state.charge == -1,
                                label + " topology change retained incompatible battery data");
                    }
                    break;
                }
                case 6:
                    randomBattery(state, random, label);
                    break;
                case 7:
                    if (!rig.live()) break;
                    state.featureSpec(spec);
                    installed.add(id);
                    confirmed.add(id);
                    rig.tx.onFeatureReady(id);
                    break;
                case 8:
                    if (!rig.live()) break;
                    int value = id == WEAR_STATUS ? random.nextInt(7) - 2 : random.nextInt(2);
                    int revision = state.featureRevision(id);
                    if (id == WEAR_STATUS) rig.wear(value);
                    else state.featureValue(id, value);
                    values.put(id, value);
                    check(state.featureRevision(id) == revision + 1, label + " feature revision did not advance for " + id);
                    rig.tx.onFeatureReady(id);
                    break;
                case 9:
                    if (!rig.live() || id == WEAR_STATUS) break;
                    state.clearFeatureValue(id);
                    values.remove(id);
                    break;
                case 10:
                    if (!rig.live()) break;
                    int mode = random.nextInt(4) - 1;
                    rig.tx.onTopModeBegin(mode);
                    state.featureValue(NOISE_MODE, mode);
                    values.put(NOISE_MODE, mode);
                    check(state.mode == mode, label + " reported noise mode not applied");
                    break;
                case 11:
                case 12: {
                    int target = Objects.equals(values.get(lane), 1) ? 0 : 1;
                    boolean expected = rig.tx.pending(lane) != target && (!rig.live()
                            || expectedWritable(state, lane, installed.contains(lane), values.containsKey(lane)));
                    check(rig.tx.begin(lane, target) == expected, label + " wrong admission for " + lane);
                    if (expected) check(rig.tx.pending(lane) == target, label + " accepted write did not stay pending");
                    break;
                }
                case 13:
                case 14:
                case 17: {
                    boolean wantsClaim = operation != 14;
                    TxWrite write = sampleWrite(rig, random, wantsClaim);
                    if (write == null) break;
                    FeatureId feature = write.request.id;
                    FeatureTransactions.Request active = rig.tx.receipt(feature);
                    boolean current = active == write.request;
                    if (wantsClaim) {
                        boolean accepted = write.claim.getAsBoolean();
                        check(accepted == (current && !write.claimed), label + " stale or duplicate transport claim");
                        if (accepted) {
                            claims++;
                            write.claimed = true;
                            check(!FeatureTransactions.dispatched(write.request), label + " claim was treated as a flush");
                            write.flushed.run();
                            check(FeatureTransactions.dispatched(write.request), label + " flush did not mark request sent");
                        } else {
                            staleClaims++;
                        }
                    }
                    if (operation != 13) {
                        boolean sent = FeatureTransactions.dispatched(write.request);
                        int report = random.nextBoolean() ? write.request.value : 1 - write.request.value;
                        if (current && sent) {
                            state.featureValue(feature, report);
                            values.put(feature, report);
                        }
                        rig.tx.report(write.request, feature, report, sent);
                        if (current && sent && report == write.request.value) {
                            acknowledgments++;
                            check(rig.tx.receipt(feature) == null && Boolean.TRUE.equals(rig.outcomes.get(feature)),
                                    label + " matching acknowledgment did not settle the transaction");
                        } else {
                            unmatchedReports++;
                            check(rig.tx.receipt(feature) == active, label + " unmatched acknowledgment settled the transaction");
                        }
                    }
                    break;
                }
                case 15:
                    rig.clock.tick(random.nextInt(8000));
                    break;
                case 16:
                    if (rig.live()) state.featureText(FIRMWARE_INFO, "v" + step);
                    break;
                default: throw new AssertionError("unreachable operation");
            }
            checkStateMachine(rig, installed, confirmed, values, label);
        }
        check(claims > 0 && acknowledgments > 0 && parks > 0 && disconnects > 0,
                "state sequence missed claims/acknowledgments/parks/disconnects: "
                        + claims + "/" + acknowledgments + "/" + parks + "/" + disconnects);
        check(staleClaims > 0 && unmatchedReports > 0, "state sequence missed late transport events");
    }

    public static void main(String[] args) throws Exception {
        Random random = new Random(0x5745415245415253L);
        wireDrivers(new Random(random.nextLong()));
        hearingProtectionAndParkedEvidence();
        featureTimeoutAndCancellationRollBack();
        meshStateRoundTrips(new Random(random.nextLong()));
        meshMalformedFrames(new Random(random.nextLong()));
        meshFrameTerminatorDoesNotUsePayloadBudget();
        firewallAddressAndRateBoundaries();
        firewallAuthCooldownAndClear();
        firewallReadOnlyAllowsOnlyReads();
        firewallConcurrentAccess();
        dspExtremeParameters(new Random(random.nextLong()));
        dspFiniteOverdriveCannotPoisonSubsequentAudio();
        randomStateTransitions(new Random(random.nextLong()));
        System.out.println("PASS " + CHECKS.get() + " protocol robustness checks");
    }
}
