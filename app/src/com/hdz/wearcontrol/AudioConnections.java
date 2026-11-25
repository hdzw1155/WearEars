package com.hdz.wearcontrol;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 一次性查询系统音频连接，供自动选设备、标准驱动和磁贴共用，不扫描或连接耳机。 */
final class AudioConnections {
    private static final String TAG = "AudioConnections";
    interface Callback { void onResult(List<BluetoothDevice> devices, boolean complete); }
    static int profile(String action) {
        if ("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED".equals(action)) return BluetoothProfile.A2DP;
        if ("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED".equals(action)) return BluetoothProfile.HEADSET;
        if ("android.bluetooth.action.LE_AUDIO_CONNECTION_STATE_CHANGED".equals(action)) return BluetoothProfile.LE_AUDIO;
        return -1;
    }

    static void query(final Context context, final int disconnectedProfiles, final Callback callback) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() { new Probe(context, disconnectedProfiles, callback).start(); }
        });
    }

    private static String safeAddress(BluetoothDevice device) {
        if (device == null) return null;
        try { return device.getAddress(); }
        catch (SecurityException ignored) { return null; }
    }

    private static final class LeAudioCompat {
        static List<BluetoothDevice> deduplicate(BluetoothProfile proxy, List<BluetoothDevice> connected) {
            if (!(proxy instanceof BluetoothLeAudio)) return new ArrayList<BluetoothDevice>(connected);
            BluetoothLeAudio le = (BluetoothLeAudio) proxy;
            Map<Integer, List<BluetoothDevice>> groups = new LinkedHashMap<Integer, List<BluetoothDevice>>();
            int[] gids = new int[connected.size()];
            for (int i = 0; i < connected.size(); i++) {
                BluetoothDevice d = connected.get(i);
                int gid = d == null ? BluetoothLeAudio.GROUP_ID_INVALID : le.getGroupId(d);
                gids[i] = gid;
                if (gid != BluetoothLeAudio.GROUP_ID_INVALID) {
                    List<BluetoothDevice> members = groups.get(gid);
                    if (members == null) {
                        members = new ArrayList<BluetoothDevice>(2);
                        groups.put(gid, members);
                    }
                    members.add(d);
                }
            }
            List<BluetoothDevice> out = new ArrayList<BluetoothDevice>(connected.size());
            Set<Integer> emitted = new HashSet<Integer>();
            for (int i = 0; i < connected.size(); i++) {
                BluetoothDevice d = connected.get(i);
                if (d == null) continue;
                int gid = gids[i];
                if (gid == BluetoothLeAudio.GROUP_ID_INVALID) {
                    out.add(d);
                } else if (emitted.add(gid)) {
                    out.add(pickRepresentative(le, gid, groups.get(gid)));
                }
            }
            return out;
        }

        private static BluetoothDevice pickRepresentative(BluetoothLeAudio le, int gid, List<BluetoothDevice> members) {
            BluetoothDevice lead = null;
            try { lead = le.getConnectedGroupLeadDevice(gid); }
            catch (RuntimeException ignored) { }
            if (lead != null) {
                String leadAddr = safeAddress(lead);
                for (BluetoothDevice member : members) {
                    if (lead == member || (leadAddr != null && leadAddr.equals(safeAddress(member)))) {
                        return member;
                    }
                }
            }
            return members.get(0);
        }
    }

    private static final class Probe {
        final Context context;
        final int excluded;
        final Callback callback;
        final Handler main = new Handler(Looper.getMainLooper());
        final Map<String, BluetoothDevice> devices = new LinkedHashMap<String, BluetoothDevice>();
        BluetoothAdapter adapter;
        int remaining;
        volatile boolean done;
        boolean incomplete;
        final Runnable timeout = new Runnable() { @Override public void run() { incomplete = true; finish(); } };

        Probe(Context context, int excluded, Callback callback) {
            this.context = context.getApplicationContext(); this.excluded = excluded; this.callback = callback;
        }
        void postToMain(Runnable r) {
            Looper mainLooper = Looper.getMainLooper();
            if (mainLooper != null && Looper.myLooper() == mainLooper) r.run();
            else main.post(r);
        }
        void start() {
            if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) { incomplete = true; finish(); return; }
            try {
                BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
                adapter = manager == null ? null : manager.getAdapter();
                if (adapter == null || !adapter.isEnabled()) { finish(); return; }
                boolean le = false;
                if (Build.VERSION.SDK_INT >= 33) {
                    int support = adapter.isLeAudioSupported();
                    le = support == BluetoothStatusCodes.FEATURE_SUPPORTED;
                    incomplete = !le
                            && support != BluetoothStatusCodes.FEATURE_NOT_SUPPORTED
                            && support != BluetoothStatusCodes.FEATURE_NOT_CONFIGURED;
                }
                remaining = le ? 3 : 2;
                main.postDelayed(timeout, 1500L);
                ask(BluetoothProfile.A2DP); ask(BluetoothProfile.HEADSET);
                if (le) ask(BluetoothProfile.LE_AUDIO);
            } catch (RuntimeException e) {
                Log.w(TAG, "Cannot query Bluetooth audio profiles", e);
                incomplete = true;
                finish();
            }
        }
        void ask(int profile) {
            if ((excluded & (1 << profile)) != 0) { answered(true); return; }
            final class Reply implements BluetoothProfile.ServiceListener {
                boolean received;
                void end(boolean valid) {
                    if (done || received) return;
                    received = true;
                    answered(valid);
                }
                @Override public void onServiceConnected(int id, BluetoothProfile proxy) {
                    List<BluetoothDevice> extracted = null;
                    try {
                        if (!done && proxy != null) {
                            List<BluetoothDevice> connected = proxy.getConnectedDevices();
                            if (connected != null) {
                                extracted = Build.VERSION.SDK_INT >= 33 && id == BluetoothProfile.LE_AUDIO
                                        ? LeAudioCompat.deduplicate(proxy, connected)
                                        : new ArrayList<BluetoothDevice>(connected);
                            }
                        }
                    } catch (RuntimeException e) {
                        Log.w(TAG, "Cannot read connected devices for profile " + id, e);
                        extracted = null;
                    } finally {
                        // 超时后的迟到回调也必须关闭代理。
                        if (proxy != null) {
                            try { adapter.closeProfileProxy(id, proxy); }
                            catch (RuntimeException e) { Log.d(TAG, "Cannot close profile proxy " + id, e); }
                        }
                    }
                    final List<BluetoothDevice> found = extracted;
                    postToMain(new Runnable() {
                        @Override public void run() {
                            if (done || received) return;
                            boolean valid = found != null;
                            if (valid) {
                                for (BluetoothDevice device : found) {
                                    if (device == null) continue;
                                    try {
                                        String address = device.getAddress();
                                        if (address != null && !address.isEmpty()) devices.put(address, device);
                                    } catch (SecurityException e) {
                                        Log.w(TAG, "Cannot read address for profile " + id, e);
                                        valid = false;
                                        break;
                                    }
                                }
                            }
                            end(valid);
                        }
                    });
                }
                @Override public void onServiceDisconnected(int id) {
                    postToMain(new Runnable() {
                        @Override public void run() { end(false); }
                    });
                }
            }
            Reply reply = new Reply();
            try { if (!adapter.getProfileProxy(context, reply, profile)) reply.end(false); }
            catch (RuntimeException e) {
                Log.w(TAG, "Cannot open audio profile " + profile, e);
                reply.end(false);
            }
        }
        void answered(boolean valid) {
            incomplete |= !valid;
            if (--remaining == 0) finish();
        }
        void finish() {
            if (done) return;
            done = true;
            main.removeCallbacks(timeout);
            callback.onResult(new ArrayList<BluetoothDevice>(devices.values()), !incomplete);
        }
    }
    private AudioConnections() { }
}
