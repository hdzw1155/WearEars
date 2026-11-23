package com.hdz.wearcontrol;

import android.bluetooth.*;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/** Setup, subscription and one outgoing characteristic operation share the session actor. */
final class GattLink extends BluetoothGattCallback {
    interface Events {
        void opened();
        void receive(byte[] data);
        void failed(String message);
    }
    private enum Phase { CONNECTING, DISCOVERING, SUBSCRIBING, SETTLING, OPEN, CLOSED }
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final UUID serviceId, notifyId, writeId;
    private final Handler actor;
    private final Events events;
    private final long settle;
    private final GattPump pump;
    private final Semaphore inbox = new Semaphore(8);
    private final java.util.concurrent.atomic.AtomicBoolean overflowing = new java.util.concurrent.atomic.AtomicBoolean();
    private final ArrayDeque<byte[]> early = new ArrayDeque<>();
    private final Runnable deadline = () -> fail("GATT setup timed out");
    private Phase phase = Phase.CONNECTING;
    private volatile BluetoothGatt gatt;
    private volatile BluetoothGattCharacteristic inbound;
    private BluetoothGattCharacteristic outbound;
    private BluetoothGattDescriptor subscription;
    private int writeType;

    GattLink(Handler actor, Events events, UUID service, UUID notify, UUID write, long settle, int gap, boolean tail) {
        this.actor = actor; this.events = events;
        serviceId = service; notifyId = notify; writeId = write; this.settle = settle;
        pump = new GattPump(new GattPump.Port() {
            public boolean start(byte[] chunk) {
                try {
                    if (Build.VERSION.SDK_INT >= 33)
                        return gatt.writeCharacteristic(outbound, chunk, writeType) == BluetoothStatusCodes.SUCCESS;
                    outbound.setWriteType(writeType);
                    return outbound.setValue(chunk) && gatt.writeCharacteristic(outbound);
                } catch (RuntimeException failure) { return false; }
            }
            public void later(Runnable r, long ms) { actor.postDelayed(r, ms); }
            public void cancel(Runnable r) { actor.removeCallbacks(r); }
            public void fail(String reason) { GattLink.this.fail(reason); }
        }, gap, tail);
    }
    void open(Context app, BluetoothDevice device, long timeout) {
        gatt = device.connectGatt(app, false, this, BluetoothDevice.TRANSPORT_LE);
        if (gatt == null) { fail("GATT unavailable"); return; }
        actor.postDelayed(deadline, timeout);
    }
    private void dispatch(BluetoothGatt source, Runnable work) {
        actor.post(() -> runFor(source, work));
    }
    private void runFor(BluetoothGatt source, Runnable work) {
        if (phase == Phase.CLOSED || source != gatt) return;
        try { work.run(); } catch (RuntimeException e) { fail(e.toString()); }
    }
    @Override public void onConnectionStateChange(BluetoothGatt source, int status, int state) {
        dispatch(source, () -> {
            if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                fail("GATT disconnected (" + status + ")"); return;
            }
            if (phase == Phase.CONNECTING && state == BluetoothProfile.STATE_CONNECTED) {
                phase = Phase.DISCOVERING;
                if (!source.discoverServices()) fail("GATT discovery rejected");
            }
        });
    }
    @Override public void onServicesDiscovered(BluetoothGatt source, int status) {
        dispatch(source, () -> {
            if (phase != Phase.DISCOVERING) return;
            if (status == BluetoothGatt.GATT_SUCCESS) {
                inbound = find(source, notifyId);
                outbound = find(source, writeId);
            }
            subscription = inbound == null ? null : inbound.getDescriptor(CCCD);
            if (subscription == null || outbound == null) {
                fail("GATT characteristics or CCCD unavailable"); return;
            }
            int inboundFlags = inbound.getProperties();
            int flags = outbound.getProperties();
            if ((inboundFlags & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) == 0
                    || (flags & (BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE | BluetoothGattCharacteristic.PROPERTY_WRITE)) == 0) {
                fail("GATT characteristics lack subscription or write support"); return;
            }
            if (!source.setCharacteristicNotification(inbound, true)) {
                fail("GATT notification setup rejected"); return;
            }
            writeType = (flags & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                    ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
            phase = Phase.SUBSCRIBING;
            byte[] value = (inboundFlags & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                    ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
            boolean started = Build.VERSION.SDK_INT >= 33
                    ? source.writeDescriptor(subscription, value) == BluetoothStatusCodes.SUCCESS
                    : subscription.setValue(value) && source.writeDescriptor(subscription);
            if (!started) fail("GATT subscription rejected");
        });
    }
    private BluetoothGattCharacteristic find(BluetoothGatt source, UUID id) {
        for (BluetoothGattService s : source.getServices()) {
            if (s != null && (serviceId == null || serviceId.equals(s.getUuid()))) {
                BluetoothGattCharacteristic found = s.getCharacteristic(id);
                if (found != null) return found;
            }
        }
        return null;
    }
    @Override public void onDescriptorWrite(BluetoothGatt source, BluetoothGattDescriptor descriptor, int status) {
        dispatch(source, () -> {
            if (phase != Phase.SUBSCRIBING || descriptor != subscription) return;
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("GATT subscription failed"); return; }
            phase = Phase.SETTLING;
            actor.postDelayed(() -> runFor(source, () -> {
                if (phase != Phase.SETTLING) return;
                actor.removeCallbacks(deadline);
                phase = Phase.OPEN;
                pump.open();
                events.opened();
                while (phase == Phase.OPEN && !early.isEmpty()) events.receive(early.remove());
            }), settle);
        });
    }
    @Override public void onCharacteristicChanged(BluetoothGatt source, BluetoothGattCharacteristic c) {
        notification(source, c, c.getValue());
    }
    @Override public void onCharacteristicChanged(BluetoothGatt source, BluetoothGattCharacteristic c, byte[] value) {
        notification(source, c, value);
    }
    private void notification(BluetoothGatt source, BluetoothGattCharacteristic c, byte[] value) {
        // Binder callbacks arrive before the actor identity check. Only this subscription may
        // reserve an inbox slot, including while an older connection is still draining.
        if (source == null || source != gatt || c == null || c != inbound || value == null || value.length == 0) return;
        if (value.length > 512 || !inbox.tryAcquire()) {
            if (overflowing.compareAndSet(false, true)) dispatch(source, () -> fail("GATT receive backlog exceeded"));
            return;
        }
        byte[] bytes = value.clone();
        dispatch(source, () -> {
            inbox.release();
            if (c != inbound) return;
            if (phase == Phase.OPEN) events.receive(bytes);
            else if (phase == Phase.SUBSCRIBING || phase == Phase.SETTLING) {
                if (early.size() >= 8) fail("GATT setup backlog exceeded");
                else early.add(bytes);
            }
        });
    }
    @Override public void onCharacteristicWrite(BluetoothGatt source, BluetoothGattCharacteristic c, int status) {
        dispatch(source, () -> { if (c == outbound && phase == Phase.OPEN) pump.acknowledged(status == BluetoothGatt.GATT_SUCCESS); });
    }
    void send(byte[] data, Runnable flushed) { pump.send(data, flushed); }
    private void fail(String reason) { if (phase != Phase.CLOSED) { close(); events.failed(reason); } }
    void close() {
        if (phase == Phase.CLOSED) return;
        phase = Phase.CLOSED;
        actor.removeCallbacks(deadline);
        pump.close(); early.clear();
        BluetoothGatt old = gatt; gatt = null;
        if (old != null) {
            try { old.disconnect(); } catch (RuntimeException ignored) { }
            try { old.close(); } catch (RuntimeException ignored) { }
        }
    }
}
