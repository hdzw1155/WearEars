package com.hdz.wearcontrol;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

/** 磁贴跳板：即时乐观高亮由共享控制器处理，保持原有磁贴外观。 */
public class ModeCyclerActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean permitted = Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        if (!permitted || getSharedPreferences(TileStore.PREFS, MODE_PRIVATE).getInt("disclaimer_ack_vc", 0) == 0) {
            startActivity(new Intent(this, MainActivity.class));
        } else {
            try {
                startService(new Intent(this, ModeCyclerService.class)
                        .putExtra("target_mode", getIntent().getIntExtra("target_mode", -1))
                        .putExtra("refresh_only", getIntent().getBooleanExtra("refresh_only", false)));
            } catch (IllegalStateException e) {
                TileStore.pending(this, -1);
                startActivity(new Intent(this, MainActivity.class));
            }
        }
        finish();
    }
}
