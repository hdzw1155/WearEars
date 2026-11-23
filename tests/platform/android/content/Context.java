package android.content;

import android.content.pm.PackageManager;

public class Context {
    public final PackageManager packages = new PackageManager();
    public SharedPreferences preferences;
    public SharedPreferences getSharedPreferences(String name, int mode) {
        if (!name.equals("cfg") || mode != 0) throw new AssertionError("Unexpected preference file");
        return preferences;
    }
    public PackageManager getPackageManager() { return packages; }
    public String getPackageName() { return "com.hdz.wearcontrol"; }
    public Context getApplicationContext() { return this; }
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) { return null; }
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, int flags) { return null; }
    public void unregisterReceiver(BroadcastReceiver receiver) { }
}
