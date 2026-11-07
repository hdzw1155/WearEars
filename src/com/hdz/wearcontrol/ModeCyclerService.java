package com.hdz.wearcontrol;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** 用户主动点击后的短时服务，与主页共用连接；完成或十二秒超时即结束。 */
public class ModeCyclerService extends Service {
    private HeadsetController controller;
    private HeadsetController.Completion completion;

    @Override public int onStartCommand(Intent intent, int flags, final int startId) {
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY; }
        controller = HeadsetController.get(this);
        int mode = intent.getIntExtra("target_mode", -1);
        if (mode < 0 || mode > 2) {
            int last = TileStore.readOrEmpty(this).mode;
            mode = ((last < 0 ? TwsClient.MODE_OFF : last) + 1) % 3;
        }
        completion = new HeadsetController.Completion() {
            @Override public void onComplete(boolean success) { stopSelfResult(startId); }
        };
        controller.perform(intent.getBooleanExtra("refresh_only", false) ? -1 : mode, completion);
        return START_NOT_STICKY;
    }
    @Override public void onDestroy() {
        if (controller != null) controller.cancelOperation(completion);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
