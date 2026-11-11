package com.hdz.wearcontrol;

import android.bluetooth.BluetoothDevice;
import android.os.ParcelUuid;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.UUID;

/** Sony MDR：单条在途指令，ACK 只确认收包，模式以 RET/NTFY 为准。 */
final class SonyClient extends RfcommClient {
    private static final UUID V1 = UUID.fromString("96cc203e-5068-46ad-b32d-e316f5e069ba");
    private static final UUID V2 = UUID.fromString("956c7b26-d49a-4ba8-b03f-b17d393cb6e2");
    private SonyProtocol.Decoder decoder;
    private final ArrayDeque<byte[]> commands = new ArrayDeque<byte[]>();
    private byte[] sending, noiseState;
    private int sequence, attempt, transaction, generation, handshake;
    private int lastReceivedSequence = -1;
    private final int[] battery = {255, 255, 255};
    private int charging;
    private volatile boolean singleBattery, noiseConfirmed;

    @Override protected UUID[] services(BluetoothDevice device) {
        ParcelUuid[] advertised = device.getUuids();
        if (advertised != null) for (ParcelUuid service : advertised)
            if (V1.equals(service.getUuid())) return new UUID[]{V1, V2};
        return new UUID[]{V2, V1};
    }
    @Override protected void opened() {
        decoder = new SonyProtocol.Decoder();
        commands.clear();
        sending = null; noiseState = null;
        sequence = attempt = transaction = generation = handshake = charging = 0;
        lastReceivedSequence = -1;
        noiseConfirmed = singleBattery = false;
        Arrays.fill(battery, 255);
        hello();
    }

    private void hello() {
        if (generation != 0) return;
        if (++handshake > 3) { fail("耳机未返回 Sony MDR 协议版本"); return; }
        enqueue(new byte[]{0, 0});
        schedule(new Runnable() { @Override public void run() { hello(); }}, 1500);
    }

    private void enqueue(byte[] command) {
        if (Arrays.equals(sending, command)) return;
        for (byte[] queued : commands) if (Arrays.equals(queued, command)) return;
        if (commands.size() >= 16) return;
        commands.add(command);
        next();
    }
    private void next() {
        if (sending != null || commands.isEmpty()) return;
        sending = commands.remove();
        attempt = 0;
        transmit();
    }
    private void transmit() {
        if (++attempt > 3) { fail("Sony 控制指令未收到确认"); return; }
        write(SonyProtocol.encode(12, sequence, sending));
        final int id = ++transaction;
        schedule(new Runnable() { @Override public void run() {
            if (sending != null && id == transaction) transmit();
        }}, 1000);
    }

    @Override protected void received(byte[] bytes) {
        for (SonyProtocol.Frame frame : decoder.feed(bytes)) {
            if (frame.type == 1) {
                if (sending != null && frame.sequence == 1 - sequence) {
                    sequence = frame.sequence;
                    sending = null;
                    ++transaction;
                    next();
                }
                continue;
            }
            write(SonyProtocol.encode(1, 1 - frame.sequence, new byte[0]));
            if (lastReceivedSequence == frame.sequence) continue; // 重发仍须 ACK，但不重复处理。
            lastReceivedSequence = frame.sequence;
            if (frame.type != 12) continue;
            byte[] p = frame.payload;
            int version = SonyProtocol.generation(p);
            if (version != 0 && generation == 0) {
                generation = version;
                ready();
                queryNoise();
                queryBattery();
            }
            if (generation == 0) continue;
            int[] value = SonyProtocol.battery(generation, p);
            if (value != null) {
                if (p[1] == 0) singleBattery = true;
                else if (p[1] == 1) singleBattery = false;
                for (int i = 0; i < 3; i++) if (value[i] != 255) {
                    battery[i] = value[i];
                    charging = (charging & ~(1 << i)) | (value[3] & (1 << i));
                }
                for (Listener l : listeners) l.onBattery(battery[0], battery[1], battery[2], charging);
            }
            int mode = SonyProtocol.mode(generation, p);
            if (mode >= 0) {
                noiseState = p.clone();
                noiseConfirmed = true;
                for (Listener l : listeners) l.onNoiseMode(mode);
            }
        }
    }

    @Override public void queryBattery() {
        schedule(new Runnable() { @Override public void run() {
            if (generation == 0) return;
            byte command = (byte) (generation == 1 ? 0x10 : 0x22);
            enqueue(new byte[]{command, 0});
            enqueue(new byte[]{command, 1});
            enqueue(new byte[]{command, 2});
        }}, 0);
    }
    @Override public void queryNoise() {
        schedule(new Runnable() { @Override public void run() {
            if (generation == 0) return;
            if (noiseState != null) enqueue(new byte[]{0x66, noiseState[1]});
            else if (generation == 1) enqueue(new byte[]{0x66, 2});
            else {
                // 只读三个已知格式，设备必须返回完整合法状态才开放写入。
                enqueue(new byte[]{0x66, 0x17});
                enqueue(new byte[]{0x66, 0x19});
                enqueue(new byte[]{0x66, 0x15});
            }
        }}, 0);
    }
    @Override public void setNoiseMode(final int mode) {
        schedule(new Runnable() { @Override public void run() {
            byte[] command = SonyProtocol.noiseCommand(generation, noiseState, mode);
            if (command == null) return;
            java.util.Iterator<byte[]> iterator = commands.iterator();
            while (iterator.hasNext()) if (iterator.next()[0] == 0x68) iterator.remove();
            enqueue(command);
            schedule(new Runnable() { @Override public void run() { queryNoise(); }}, 700);
        }}, 0);
    }
    @Override public int caps() {
        return CAP_BATTERY | (singleBattery ? CAP_BATTERY_SINGLE : 0) | (noiseConfirmed ? CAP_NOISE : 0);
    }
    @Override public int verifLevel() {
        return SonyProtocol.isVerifiedReal(deviceName()) ? VERIF_REAL : VERIF_UNTESTED;
    }
    @Override public String brandName() { return "Sony"; }
}
