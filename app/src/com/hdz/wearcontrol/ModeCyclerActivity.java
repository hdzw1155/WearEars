package com.hdz.wearcontrol;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** 旧磁贴缓存迁移入口。外部 Intent 只能打开控制页，不能直接改变耳机状态。 */
public class ModeCyclerActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TileStore.push(this);
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }
}
