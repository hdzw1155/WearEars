package com.hdz.wearcontrol;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import java.util.*;

public final class StandardRefreshChecks {
    private static int checks;
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }

    private static final class RecordingListener implements Controls.Listener {
        final List<String> connectedNames = new ArrayList<>();
        final List<String> disconnectedReasons = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        public void onConnected(String deviceName) { connectedNames.add(deviceName); }
        public void onDisconnected(String reason) { disconnectedReasons.add(reason); }
        public void onError(String msg) { errors.add(msg); }
        public void onBattery(int left, int right, int box, int chargeFlags) { }
        public void onNoiseMode(int mode) { }
        public void onSpatial(Boolean state) { }
        public void onDeepX(Integer mode) { }
    }

    private static void aclConnectedWithoutConnectPermission() {
        BluetoothDevice peer = new BluetoothDevice("00:11:22:33:44:55", "Known Buds");
        peer.throwOnGetName = true;
        List<BluetoothDevice> found = Collections.singletonList(peer);
        List<BluetoothDevice> absent = Collections.emptyList();
        List<AudioConnections.Callback> queries = new ArrayList<>();
        List<Integer> excludedMasks = new ArrayList<>();
        Context ctx = new Context();
        RecordingListener listener = new RecordingListener();
        TwsClient client = new StandardClient((c, excluded, reply) -> {
            excludedMasks.add(excluded);
            queries.add(reply);
        });
        client.addListener(listener);
        client.init(ctx);
        client.connect(peer);
        check(ctx.receivers.size() == 1 && queries.size() == 1,
                "connect survives revoked BLUETOOTH_CONNECT on getName");
        queries.get(0).onResult(absent, true);
        check(!client.isConnected(), "disconnected before ACL broadcast");

        BluetoothDevice aclDevice = new BluetoothDevice("00:11:22:33:44:55", "Secret Name");
        aclDevice.throwOnGetName = true;
        Intent aclConnected = new Intent(BluetoothDevice.ACTION_ACL_CONNECTED)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, aclDevice);
        ctx.receivers.get(0).onReceive(ctx, aclConnected);
        check(queries.size() == 2 && excludedMasks.get(1) == 0,
                "ACTION_ACL_CONNECTED triggers link check when d.getName() throws SecurityException");
        queries.get(1).onResult(found, true);
        check(client.isConnected() && listener.connectedNames.equals(Collections.singletonList("耳机")),
                "connects with fallback name when BLUETOOTH_CONNECT is revoked");
        client.disconnect();

        BluetoothDevice namedPeer = new BluetoothDevice("AA:BB:CC:DD:EE:FF", "Known Buds");
        List<AudioConnections.Callback> queries2 = new ArrayList<>();
        Context ctx2 = new Context();
        RecordingListener listener2 = new RecordingListener();
        TwsClient client2 = new StandardClient((c, excluded, reply) -> queries2.add(reply));
        client2.addListener(listener2);
        client2.init(ctx2);
        client2.connect(namedPeer);
        queries2.get(0).onResult(absent, true);
        namedPeer.throwOnGetName = true;
        ctx2.receivers.get(0).onReceive(ctx2, new Intent(BluetoothDevice.ACTION_ACL_CONNECTED)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, namedPeer));
        check(queries2.size() == 2, "ACL connected succeeds after permission revoked mid-session");
        queries2.get(1).onResult(Collections.singletonList(namedPeer), true);
        check(client2.isConnected() && listener2.connectedNames.equals(Collections.singletonList("Known Buds")),
                "retains known device name when d.getName() throws SecurityException on ACL connect");
        client2.disconnect();
    }

    private static void refreshAndAclDisconnect() {
        BluetoothDevice peer = new BluetoothDevice("11:22:33:44:55:66", "Dual Buds");
        List<BluetoothDevice> found = Collections.singletonList(peer);
        List<BluetoothDevice> absent = Collections.emptyList();
        List<AudioConnections.Callback> queries = new ArrayList<>();
        List<Integer> excludedMasks = new ArrayList<>();
        List<Boolean> results = new ArrayList<>();
        Context ctx = new Context();
        RecordingListener listener = new RecordingListener();
        TwsClient client = new StandardClient((c, excluded, reply) -> {
            excludedMasks.add(excluded);
            queries.add(reply);
        });
        client.addListener(listener);
        client.init(ctx);
        client.connect(peer);
        queries.get(0).onResult(found, true);
        check(client.isConnected() && excludedMasks.get(0) == 0, "connected before profile disconnect");

        Intent a2dpDisconnecting = new Intent("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
                .putExtra(BluetoothDevice.EXTRA_DEVICE, peer)
                .putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTING);
        ctx.receivers.get(0).onReceive(ctx, a2dpDisconnecting);
        check(queries.size() == 2 && excludedMasks.get(1) == (1 << BluetoothProfile.A2DP),
                "A2DP STATE_DISCONNECTING excludes A2DP during broadcast lookup");
        queries.get(1).onResult(found, true);
        check(client.isConnected(), "other active audio profile keeps link connected");

        check(client.refreshPublicProfiles(results::add), "explicit refresh starts");
        check(queries.size() == 3 && excludedMasks.get(2) == 0,
                "refreshPublicProfiles clears disconnectedProfiles bitmask");
        queries.get(2).onResult(found, true);
        check(results.equals(Collections.singletonList(true)) && client.isConnected(),
                "explicit refresh succeeds after clearing disconnectedProfiles");

        Intent aclDisconnected = new Intent(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, peer);
        ctx.receivers.get(0).onReceive(ctx, aclDisconnected);
        check(client.isConnected() && queries.size() == 4 && listener.disconnectedReasons.isEmpty(),
                "ACTION_ACL_DISCONNECTED verifies audio profiles before disconnecting");
        queries.get(3).onResult(found, true);
        check(client.isConnected() && listener.disconnectedReasons.isEmpty(),
                "remaining active audio profile prevents false disconnect on single ACL drop");

        ctx.receivers.get(0).onReceive(ctx, aclDisconnected);
        queries.get(4).onResult(absent, true);
        check(!client.isConnected() && listener.disconnectedReasons.size() == 1,
                "confirmed absence after ACTION_ACL_DISCONNECTED disconnects");
        client.disconnect();
    }

    public static void main(String[] args) {
        BluetoothDevice peer = new BluetoothDevice();
        List<BluetoothDevice> found = Collections.singletonList(peer);
        List<BluetoothDevice> absent = Collections.emptyList();
        List<AudioConnections.Callback> queries = new ArrayList<>();
        List<Boolean> results = new ArrayList<>();
        TwsClient client = new StandardClient((ctx, excluded, reply) -> queries.add(reply));
        client.init(new Context());
        client.connect(peer);
        queries.get(0).onResult(found, true);
        check(client.isConnected(), "standard driver connects on public audio profile evidence");

        check(client.refreshPublicProfiles(results::add), "standard driver opts in to public profile refresh");
        queries.get(1).onResult(found, true);
        check(results.equals(Collections.singletonList(true)) && client.isConnected(),
                "explicit refresh succeeds without a battery broadcast");
        client.refreshPublicProfiles(results::add);
        client.refreshPublicProfiles(results::add);
        queries.get(2).onResult(absent, true);
        check(results.size() == 1 && client.isConnected(), "older lookup cannot disconnect the selected device");
        queries.get(3).onResult(absent, false);
        check(results.equals(Arrays.asList(true, false)) && client.isConnected(),
                "incomplete public profile evidence fails refresh without a false disconnect");
        client.refreshPublicProfiles(results::add);
        queries.get(4).onResult(found, false);
        check(results.equals(Arrays.asList(true, false, true)), "matching device in a partial reply is affirmative");
        client.refreshPublicProfiles(results::add);
        queries.get(5).onResult(absent, true);
        check(!client.isConnected() && results.equals(Arrays.asList(true, false, true, false)),
                "complete absence clears the connection and fails the refresh");

        client.connect(peer);
        queries.get(6).onResult(found, true);
        client.refreshPublicProfiles(results::add);
        client.disconnect();
        client.connect(peer);
        queries.get(8).onResult(found, true);
        queries.get(7).onResult(absent, true);
        check(client.isConnected() && results.size() == 4, "late profile lookup from an earlier session is discarded");

        aclConnectedWithoutConnectPermission();
        refreshAndAclDisconnect();
        System.out.println("PASS " + checks + " standard Bluetooth refresh checks");
    }
}
