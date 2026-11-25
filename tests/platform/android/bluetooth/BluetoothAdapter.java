package android.bluetooth;

import android.content.Context;
import java.util.HashMap;
import java.util.Map;

public class BluetoothAdapter {
    public static final String ACTION_STATE_CHANGED = "android.bluetooth.adapter.action.STATE_CHANGED";
    public static final String EXTRA_STATE = "android.bluetooth.adapter.extra.STATE";
    public static final int STATE_OFF = 10;
    public static final int STATE_TURNING_ON = 11;
    public static final int STATE_ON = 12;
    public static final int STATE_TURNING_OFF = 13;

    public boolean enabled = true;
    public int leAudioSupport = BluetoothStatusCodes.FEATURE_NOT_SUPPORTED;
    public final Map<Integer, BluetoothProfile> proxies = new HashMap<>();
    public final Map<Integer, BluetoothProfile.ServiceListener> pendingListeners = new HashMap<>();
    public int closedCount;

    public boolean isEnabled() { return enabled; }
    public int isLeAudioSupported() { return leAudioSupport; }
    public boolean getProfileProxy(Context context, BluetoothProfile.ServiceListener listener, int profile) {
        if (proxies.containsKey(profile)) {
            BluetoothProfile proxy = proxies.get(profile);
            if (proxy != null) {
                listener.onServiceConnected(profile, proxy);
                return true;
            }
            return false;
        }
        pendingListeners.put(profile, listener);
        return true;
    }
    public void closeProfileProxy(int profile, BluetoothProfile proxy) { closedCount++; }
}
