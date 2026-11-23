package com.hdz.wearcontrol;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import java.util.*;

public final class StandardRefreshChecks {
    private static int checks;
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
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
        System.out.println("PASS " + checks + " standard Bluetooth refresh checks");
    }
}
