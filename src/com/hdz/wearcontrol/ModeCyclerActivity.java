package com.hdz.wearcontrol;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * 磁贴按钮的跳板: 秒启动后台服务然后立刻 finish,自己不干活——
 * 之前的写法是透明 Activity 在前台等连接,耳机断开时会把界面冻结最多 8 秒。
 *
 * <p>乐观更新: 点磁贴上的模式段后, 必须在用户按下的那一瞬就把新档位写进磁贴快照并让宿主重绘,
 * 不能等后台连上耳机 2~3 秒后再刷新(那样会出现"按了没反应"或"亮错档")。所以这里先把
 * 期望档位落盘 + push, 再把 prev_mode / optimistic 交给 Service; 连接失败时由 Service 回滚。
 */
public class ModeCyclerActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int wanted = getIntent().getIntExtra("target_mode", -1);
        int prev = TileStore.read(this).mode;
        if (prev < TwsClient.MODE_ANC || prev > TwsClient.MODE_TRANS) {
            prev = TwsClient.MODE_OFF;
        }

        boolean optimistic = wanted >= TwsClient.MODE_ANC
                && wanted <= TwsClient.MODE_TRANS
                && wanted != prev;
        if (optimistic) {
            // 先让磁贴显示"已切到新模式", 不等无线链路的回执
            TileStore.saveMode(this, wanted);
            TileStore.push(this);
        }

        Intent i = new Intent(this, ModeCyclerService.class);
        if (wanted >= 0) i.putExtra("target_mode", wanted);
        i.putExtra("prev_mode", prev);
        i.putExtra("optimistic", optimistic);
        startService(i);
        finish();
    }
}
