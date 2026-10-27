package com.hdz.wearcontrol;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * 磁贴按钮的跳板: 秒启动后台服务然后立刻 finish,自己不干活——
 * 之前的写法是透明 Activity 在前台等连接,耳机断开时会把界面冻结最多 8 秒。
 */
public class ModeCyclerActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent i = new Intent(this, ModeCyclerService.class);
        int wanted = getIntent().getIntExtra("target_mode", -1);
        if (wanted >= 0) i.putExtra("target_mode", wanted);
        startService(i);
        finish();
    }
}
