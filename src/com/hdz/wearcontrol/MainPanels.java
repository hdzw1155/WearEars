package com.hdz.wearcontrol;

import android.app.Activity;
import android.util.Log;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/** 页面只在此处注册功能。模块缺席时隐藏它拥有的旧布局区域。 */
final class MainPanels {
    private final List<MainPanel> panels = new ArrayList<MainPanel>();

    MainPanels(Activity activity, MainPanel.Actions actions) {
        add(activity, actions, "BatteryPanel", new int[]{R.id.battery_header, R.id.card_l,
                R.id.card_r, R.id.card_c, R.id.battery_classic_card, R.id.neo_battery_card});
        add(activity, actions, "NoisePanel", new int[]{R.id.noise_card});
        add(activity, actions, "VolumePanel", new int[]{R.id.volume_card, R.id.switch_volume});
        add(activity, actions, "EffectsPanel", new int[]{R.id.spatial_card, R.id.equalizer_card});
    }

    private void add(Activity activity, MainPanel.Actions actions, String name, int[] roots) {
        try {
            panels.add((MainPanel) Class.forName("com.hdz.wearcontrol." + name)
                    .getDeclaredConstructor(Activity.class, MainPanel.Actions.class).newInstance(activity, actions));
            return;
        } catch (ClassNotFoundException absent) {
            // 可选 UI 模块已从精简构建中移除。
        } catch (ReflectiveOperationException e) {
            Log.w("Panels", "Cannot load " + name, e);
        } catch (LinkageError e) {
            Log.w("Panels", "Incomplete panel " + name, e);
        }
        for (int id : roots) {
            View view = activity.findViewById(id);
            if (view != null) view.setVisibility(View.GONE);
        }
    }

    void render(DeviceState state, int pending) { for (MainPanel panel : panels) panel.render(state, pending); }
    void resume() { for (MainPanel panel : panels) panel.resume(); }
    void pause() { for (MainPanel panel : panels) panel.pause(); }
    void volume(int level, int max) { for (MainPanel panel : panels) panel.volume(level, max); }
    void refreshVolume() { for (MainPanel panel : panels) panel.refreshVolume(); }
}
