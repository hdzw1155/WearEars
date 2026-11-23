package com.hdz.wearcontrol;

import android.app.Activity;
import java.util.Arrays;
import java.util.List;

final class MainPanels {
    private final List<MainPanel> panels;
    MainPanels(Activity activity, MainPanel.Actions actions) {
        panels = Arrays.asList(new BatteryPanel(activity, actions), new NoisePanel(activity, actions),
                new VolumePanel(activity, actions), new EffectsPanel(activity, actions));
    }
    void render(DeviceState state, int pending) { for (MainPanel panel : panels) panel.render(state, pending); }
    void resume() { for (MainPanel panel : panels) panel.resume(); }
    void pause() { for (MainPanel panel : panels) panel.pause(); }
    void volume(int level, int max) { for (MainPanel panel : panels) panel.volume(level, max); }
    void refreshVolume() { for (MainPanel panel : panels) panel.refreshVolume(); }
}
