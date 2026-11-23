package com.hdz.wearcontrol;

import android.bluetooth.*;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import java.util.*;

public final class PlatformChecks {
    private static int checks;
    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        checks++;
    }
    private static final UUID SERVICE = UUID.fromString("000000fe-0000-1000-8000-00805f9b34fb");
    private static final UUID NOTIFY = UUID.fromString("000000f2-0000-1000-8000-00805f9b34fb");
    private static final UUID WRITE = UUID.fromString("000000f1-0000-1000-8000-00805f9b34fb");

    private static final class Rig implements GattLink.Events {
        final Handler actor = new Handler();
        final BluetoothDevice device = new BluetoothDevice();
        final BluetoothGatt gatt = device.stack;
        final BluetoothGattCharacteristic inbound, outbound;
        final GattLink link;
        final List<Integer> notifications = new ArrayList<>();
        int opens, failures;
        Rig(boolean anyService, boolean indicate) {
            this(anyService, indicate ? 32 : 16, 4);
        }
        Rig(boolean anyService, int inboundFlags, int outboundFlags) {
            inbound = new BluetoothGattCharacteristic(NOTIFY, inboundFlags);
            outbound = new BluetoothGattCharacteristic(WRITE, outboundFlags);
            BluetoothGattService unrelated = new BluetoothGattService(WRITE);
            if (!anyService) unrelated.characteristics.put(NOTIFY, new BluetoothGattCharacteristic(NOTIFY, 16));
            gatt.services.add(unrelated);
            BluetoothGattService target = new BluetoothGattService(SERVICE);
            target.characteristics.put(NOTIFY, inbound);
            target.characteristics.put(WRITE, outbound);
            gatt.services.add(target);
            link = new GattLink(actor, this, anyService ? null : SERVICE, NOTIFY, WRITE, 250, 30, true);
            link.open(new Context(), device, 5000);
        }
        void discover() {
            link.onConnectionStateChange(gatt, 0, 2);
            actor.advance(0);
            check(gatt.discoveries == 1, "discover one service set");
            link.onServicesDiscovered(gatt, 0);
            actor.advance(0);
        }
        void subscribe() { link.onDescriptorWrite(gatt, inbound.cccd, 0); actor.advance(0); }
        void ready() { discover(); subscribe(); actor.advance(250); }
        public void opened() { opens++; }
        public void receive(byte[] value) { notifications.add((int)value[0]); }
        public void failed(String message) { failures++; }
        void bytes(int v) {
            byte[] data = {(byte)v};
            if (Build.VERSION.SDK_INT >= 33) link.onCharacteristicChanged(gatt, inbound, data);
            else { inbound.setValue(data); link.onCharacteristicChanged(gatt, inbound); }
            data[0] = 99;
        }
        void ack(int status) { link.onCharacteristicWrite(gatt, outbound, status); actor.advance(0); }
    }

    private static void platform(int sdk) {
        Build.VERSION.SDK_INT = sdk;
        Rig r = new Rig(sdk < 33, sdk >= 33);
        r.discover();
        check(r.gatt.subscribed && r.gatt.descriptorStarts == 1 && r.gatt.descriptorValue[0] == (sdk < 33 ? 1 : 2),
                "subscribe using advertised properties");
        r.link.onDescriptorWrite(r.gatt, new BluetoothGattDescriptor(), 0);
        r.link.onDescriptorWrite(new BluetoothGatt(), r.inbound.cccd, 0);
        r.actor.advance(300);
        check(r.opens == 0 && r.gatt.writes.isEmpty(), "foreign descriptors and links grant no write access");
        r.bytes(3); r.actor.advance(0); r.subscribe(); r.bytes(5); r.actor.advance(249);
        check(r.opens == 0 && r.notifications.isEmpty(), "CCCD callback starts readiness delay");
        r.actor.advance(1);
        check(r.opens == 1 && r.notifications.equals(Arrays.asList(3,5)), "replay copied early fragments in order");
        r.link.onDescriptorWrite(r.gatt, r.inbound.cccd, 0); r.actor.advance(250);
        check(r.opens == 1, "duplicate subscription callback does not reopen protocol");

        int[] completed = {0};
        r.link.send(new byte[41], () -> completed[0]++);
        r.link.send(new byte[1], () -> completed[0]++);
        r.actor.advance(100);
        check(r.gatt.writes.size() == 1 && r.gatt.writes.get(0).length == 20, "one platform operation in flight");
        r.link.onCharacteristicWrite(new BluetoothGatt(), r.outbound, 0);
        r.link.onCharacteristicWrite(r.gatt, new BluetoothGattCharacteristic(WRITE, 4), 0);
        r.actor.advance(100);
        check(r.gatt.writes.size() == 1, "foreign completion cannot advance writer");
        r.ack(0); r.actor.advance(29);
        check(r.gatt.writes.size() == 1, "gap measured from acknowledgement");
        r.actor.advance(1); r.ack(0); r.actor.advance(30);
        check(r.gatt.writes.size() == 3 && r.gatt.writes.get(2).length == 1, "20/20/1 fragments");
        r.ack(0); r.actor.advance(29);
        check(completed[0] == 0 && r.gatt.writes.size() == 3, "last fragment also paced");
        r.actor.advance(1); r.ack(0); r.actor.advance(30);
        check(completed[0] == 2 && r.gatt.writes.size() == 4, "two frames flushed in order");
        check((sdk < 33 ? r.gatt.legacyWrites : r.gatt.modernWrites) == 4, "correct Android write overload");

        r.link.send(new byte[10], () -> completed[0]++);
        r.ack(1);
        check(r.gatt.closed && r.failures == 1 && completed[0] == 2, "failed callback closes session without flush");
        r.bytes(9); r.ack(0); r.actor.advance(6000);
        check(r.notifications.size() == 2 && r.failures == 1, "closed session ignores stale events");
        Rig next = new Rig(false, false);
        next.ready();
        // Repeated notifications with the right UUID from another GATT must not consume this mailbox's slots.
        for (int i = 0; i < 12; i++) next.link.onCharacteristicChanged(r.gatt, r.inbound, new byte[]{44});
        next.actor.advance(0); next.bytes(7); next.actor.advance(0);
        check(next.notifications.equals(Collections.singletonList(7)) && next.failures == 0,
                "foreign notifications do not exhaust active session inbox");
        next.gatt.rejectWrite = true;
        next.link.send(new byte[2], () -> { throw new AssertionError("rejected write flushed"); });
        check(next.failures == 1 && next.gatt.closed, "synchronously rejected write fails session");
    }

    private static void setupFailures() {
        for (Rig unavailable : Arrays.asList(new Rig(false, 2, 4), new Rig(false, 16, 2))) {
            unavailable.discover();
            check(!unavailable.gatt.subscribed && unavailable.gatt.descriptorStarts == 0
                    && unavailable.opens == 0 && unavailable.failures == 1,
                    "matching UUIDs still require subscription and write properties");
        }
        Rig bad = new Rig(false, false);
        bad.discover();
        bad.link.onDescriptorWrite(bad.gatt, bad.inbound.cccd, 3);
        bad.actor.advance(6000);
        check(bad.opens == 0 && bad.failures == 1 && bad.gatt.writes.isEmpty(), "failed CCCD aborts handshake");
        Rig timeout = new Rig(false,false);
        timeout.discover(); timeout.actor.advance(5000); timeout.subscribe(); timeout.actor.advance(1000);
        check(timeout.opens == 0 && timeout.failures == 1, "late descriptor cannot recover timed-out session");
        Rig closed = new Rig(false,false);
        closed.discover(); closed.subscribe(); closed.link.close(); closed.actor.advance(5000);
        check(closed.opens == 0 && closed.failures == 0, "active cancellation during settle suppresses open");
        Rig earlyWrite = new Rig(false,false);
        earlyWrite.discover(); earlyWrite.subscribe();
        earlyWrite.link.send(new byte[]{8}, null);
        check(earlyWrite.failures == 1 && earlyWrite.gatt.writes.isEmpty(), "premature business write fails closed");
        Rig full = new Rig(false,false); full.ready();
        for (int i = 0; i < 9; i++) full.bytes(1);
        full.actor.advance(0);
        check(full.failures == 1 && full.notifications.size() <= 8, "real producer overflow has a bounded queue");
    }

    private static void consent() {
        final String address = "00:00:00:00:00:01";
        Context c = new Context();
        MemorySettings p = new MemorySettings();
        c.preferences = p;
        p.edit().putString("tile_address", address).apply();
        check(!AppPrefs.hasDisclaimer(c) && TileLedger.issue(c) == 0, "fresh installation blocks renders");
        AppPrefs.acceptDisclaimer(c);
        long token = TileLedger.issue(c);
        check(token > 0 && AppPrefs.hasDisclaimer(c), "current-version main acceptance enables issuance");
        String a = TileCommand.id(token,0,address), b = TileCommand.id(token,1,address), d = TileCommand.id(token,2,address);
        TileLedger.Click first = TileLedger.consume(c,a);
        check(first != null && TileLedger.current(c,first) && TileLedger.consume(c,a) == null, "action accepted once");
        TileLedger.Click second = TileLedger.consume(c,b);
        check(second != null && !TileLedger.current(c,first) && TileLedger.current(c,second), "new action supersedes waiting result");
        long undelivered = TileLedger.issue(c);
        c.packages.version++;
        check(!AppPrefs.hasDisclaimer(c) && TileLedger.issue(c) == 0 && !TileLedger.current(c,second), "upgrade invalidates previous consent");
        check(TileLedger.consume(c,d) == null, "locked action is consumed without dispatch");
        AppPrefs.acceptDisclaimer(c);
        Context reopened = new Context(); reopened.packages.version = c.packages.version; reopened.preferences = p;
        check(TileLedger.consume(reopened,d) == null && TileLedger.consume(reopened,TileCommand.id(undelivered,-1,address)) == null,
                "persisted consumption and acceptance floor defeat delivered and undelivered old IDs");
        long fresh = TileLedger.issue(c);
        String freshId = TileCommand.id(fresh,-1,address);
        p.edit().putString("device_address","00:00:00:00:00:02").apply();
        check(TileLedger.consume(c,freshId) == null, "stale target rejected");
        p.edit().remove("device_address").apply();
        check(TileLedger.consume(c,freshId) == null, "discarded target cannot replay after reselect");
        TileLedger.Click valid = TileLedger.consume(c,TileCommand.id(fresh,0,address));
        check(valid != null && TileLedger.current(c, valid), "new version remains operable");
        TileLedger.invalidate(c);
        check(!TileLedger.current(c,valid), "foreground action invalidates delayed tile selection");
        c.packages.version = -1;
        check(!AppPrefs.hasDisclaimer(c) && TileLedger.issue(c) == 0, "version lookup failure blocks");
        c.packages.version = 18; p.rejectDiskWrite = true;
        AppPrefs.acceptDisclaimer(c);
        check(!AppPrefs.hasDisclaimer(c), "failed acceptance commit stays blocked in memory");
        p.rejectDiskWrite = false; AppPrefs.acceptDisclaimer(c); long finalToken = TileLedger.issue(c);
        p.rejectDiskWrite = true;
        check(TileLedger.consume(c,TileCommand.id(finalToken,0,address)) == null, "disk failure cannot dispatch a command");
    }

    public static void main(String[] args) {
        platform(30); platform(34); setupFailures(); consent();
        System.out.println("PASS " + checks + " Android GATT and persisted consent checks");
    }
}
