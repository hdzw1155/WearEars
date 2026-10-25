// ============================================================================
// 【仅供参考，不参与编译】磁贴按钮的跳板 Activity，配套同目录 TwsTileService.java。
// 撤下原因与恢复步骤（依赖 jar、manifest 注册段）见 TwsTileService.java 头部。
// ============================================================================

// package com.hdz.wearcontrol;
// 
// import android.bluetooth.BluetoothAdapter;
// import android.bluetooth.BluetoothDevice;
// import android.bluetooth.BluetoothManager;
// import android.content.SharedPreferences;
// import android.os.Bundle;
// import android.os.Handler;
// 
// /**
//  * 无界面切换器: 磁贴上点按钮跳到这里, 按工厂挑品牌客户端连一次耳机切到目标档,
//  * 完成即退出, 全程无 UI。8 秒超时兜底。
//  * 品牌无关——华为/小米协议层未实测, 失败会安静退出(磁贴状态不变)。
//  */
// public class ModeCyclerActivity extends android.app.Activity implements TwsClient.Listener {
// 
//     private static final String[] NAMES = {"降噪开", "降噪关", "通透"};
// 
//     private final Handler h = new Handler();
//     private TwsClient client;
//     private int nextMode;
//     private boolean done = false;
// 
//     /** 主界面的后台自杀让路标志: 跳板活着时为 true,杀进程前看一眼 */
//     static volatile boolean running = false;
// 
//     @Override
//     protected void onCreate(Bundle savedInstanceState) {
//         super.onCreate(savedInstanceState);
//         running = true;
//         SharedPreferences cfg = getSharedPreferences("cfg", MODE_PRIVATE);
//         int last = cfg.getInt("tile_mode", TwsClient.MODE_OFF);
//         // 磁贴传来的指定档位(点谁切谁); 没传就循环下一档
//         int wanted = getIntent().getIntExtra("target_mode", -1);
//         nextMode = wanted >= 0 ? wanted : (last + 1) % 3;
// 
//         BluetoothAdapter adapter =
//                 ((BluetoothManager) getSystemService(BLUETOOTH_SERVICE)).getAdapter();
//         BluetoothDevice target = adapter != null ? MainActivity.findTarget(adapter) : null;
//         if (target == null) {
//             finish();
//             return;
//         }
//         client = ClientFactory.create(this, target);
//         if (client == null) {
//             finish();
//             return;
//         }
//         getSharedPreferences("cfg", MODE_PRIVATE).edit()
//                 .putString("tile_mac", target.getAddress()).apply();
//         client.addListener(this);
//         client.connect(target);
//         h.postDelayed(new Runnable() {
//             @Override public void run() {
//                 finish();
//             }
//         }, 8000);
//     }
// 
//     private void complete() {
//         if (done) return;
//         done = true;
//         finish();
//     }
// 
//     @Override
//     public void onConnected(String deviceName) {
//         getSharedPreferences("cfg", MODE_PRIVATE).edit()
//                 .putLong("tile_last_ok", System.currentTimeMillis()).apply();
//         if (client != null) client.setNoiseMode(nextMode);
//     }
// 
//     @Override
//     public void onNoiseMode(int mode) {
//         // 耳机确认了新模式, 记下来让磁贴下次显示正确
//         if (mode != nextMode) return;
//         getSharedPreferences("cfg", MODE_PRIVATE).edit()
//                 .putInt("tile_mode", mode)
//                 .putString("tile_mode_name",
//                         mode >= 0 && mode < NAMES.length ? NAMES[mode] : "--")
//                 .putLong("tile_last_ok", System.currentTimeMillis())
//                 .apply();
//         // 让系统立刻重画磁贴, 不等刷新周期
//         try {
//             androidx.wear.tiles.TileService.getUpdater(this)
//                     .requestUpdate(TwsTileService.class);
//         } catch (Exception ignored) {
//         }
//         complete();
//     }
// 
//     @Override
//     public void onBattery(int left, int right, int box, int chargeFlags) {
//         getSharedPreferences("cfg", MODE_PRIVATE).edit()
//                 .putInt("tile_batt_l", left)
//                 .putInt("tile_batt_r", right)
//                 .putInt("tile_batt_c", box)
//                 .apply();
//     }
// 
//     @Override
//     public void onError(String msg) {
//         complete();
//     }
// 
//     @Override
//     public void onDisconnected(String reason) {
//         complete();
//     }
// 
//     @Override
//     protected void onPause() {
//         super.onPause();
//         if (client != null) {
//             client.disconnect();
//             client = null;
//         }
//     }
// 
//     @Override
//     protected void onDestroy() {
//         running = false;
//         h.removeCallbacksAndMessages(null);
//         if (client != null) {
//             client.disconnect();
//             client = null;
//         }
//         super.onDestroy();
//     }
// }
