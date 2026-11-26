package com.hdz.wearcontrol.desktop;

import com.hdz.wearcontrol.WearEarsBinauralKernel;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import java.awt.Font;

final class DesktopSpatialLab {
    private final BinauralAuditionEngine binaural = new BinauralAuditionEngine();
    private final DesktopDialogs dialogs;
    private DesktopUi ui;
    private SwissDialPanel dial;
    private DesktopUi.Button play;
    private int playbackRevision;

    DesktopSpatialLab(DesktopDialogs dialogs) {
        this.dialogs = dialogs;
    }

    JScrollPane view(DesktopUi ui) {
        this.ui = ui;
        DesktopUi.Page page = ui.page();
        DesktopUi.Stack playback = ui.card(ui.t("本机双耳渲染", "Binaural playback"));
        playback.add(ui.note(ui.t("拖动表盘改变声音方位。播放前请先降低耳机音量。",
                "Drag the dial to move the sound. Lower your headphone volume before playing.")));
        dial = new SwissDialPanel();
        dial.applyTheme(ui);
        dial.setAzimuthDeg(binaural.getAzimuthDeg());
        dial.setOnAngleChanged(binaural::setAzimuthDeg);
        playback.add(dial);
        play = ui.button("", true, this::toggle);
        playback.add(play);
        JCheckBox orbit = ui.check(ui.t("360° 自动环绕", "360° automatic orbit"), binaural.isAutoOrbit());
        orbit.addActionListener(e -> binaural.setAutoOrbit(orbit.isSelected()));
        playback.add(orbit);
        page.add(playback);

        DesktopUi.Stack parameters = ui.card(ui.t("声学参数", "Acoustic parameters"));
        JComboBox<String> scene = ui.combo(new JComboBox<>(new String[] {ui.t("自然宽阔", "Natural wide"),
                ui.t("影院", "Cinema"), ui.t("音乐厅", "Concert hall"), ui.t("监听室", "Studio")}));
        scene.setSelectedIndex(binaural.getScene());
        scene.addActionListener(e -> binaural.setScene(scene.getSelectedIndex()));
        addChoice(parameters, ui.t("空间场景", "Room"), scene);
        JComboBox<String> source = ui.combo(new JComboBox<>(new String[] {ui.t("和弦质感与粉噪", "Chord texture and pink noise"),
                ui.t("脉冲粉噪", "Pulsed pink noise")}));
        source.setSelectedIndex(binaural.getAuditionMode());
        source.addActionListener(e -> binaural.setAuditionMode(source.getSelectedIndex()));
        addChoice(parameters, ui.t("试听声源", "Audio source"), source);
        JSlider head = slider(WearEarsBinauralKernel.HEAD_WIDTH_MIN_PCT, WearEarsBinauralKernel.HEAD_WIDTH_MAX_PCT,
                binaural.getHeadWidthPct());
        addSlider(parameters, head, ui.t("头宽比例", "Head width"), "%", binaural::setHeadWidthPct);
        JSlider pinna = slider(WearEarsBinauralKernel.PINNA_NOTCH_MIN_HZ, WearEarsBinauralKernel.PINNA_NOTCH_MAX_HZ,
                binaural.getPinnaNotchHz());
        addSlider(parameters, pinna, ui.t("耳廓凹口频率", "Pinna notch frequency"), " Hz", binaural::setPinnaNotchHz);
        parameters.add(ui.button(ui.t("恢复默认参数", "Restore defaults"), () -> {
            scene.setSelectedIndex(WearEarsBinauralKernel.SCENE_WIDE);
            source.setSelectedIndex(WearEarsBinauralKernel.AUDITION_CHORD_TEXTURE);
            head.setValue(WearEarsBinauralKernel.HEAD_WIDTH_DEFAULT_PCT);
            pinna.setValue(WearEarsBinauralKernel.PINNA_NOTCH_DEFAULT_HZ);
            binaural.setAzimuthDeg(0);
            dial.setAzimuthDeg(0);
        }));
        page.add(parameters);
        renderPlaying();
        return ui.scroll(page);
    }

    private void addChoice(DesktopUi.Stack card, String title, JComboBox<String> choice) {
        javax.swing.JLabel label = ui.label(title, 12, false, ui.theme.muted);
        label.setLabelFor(choice);
        card.add(label);
        card.add(choice);
    }

    private JSlider slider(int min, int max, int value) {
        JSlider slider = new JSlider(min, max, value);
        slider.setFont(DesktopTheme.font(Font.PLAIN, 12));
        slider.setForeground(ui.theme.accent);
        slider.setOpaque(false);
        return slider;
    }

    private void addSlider(DesktopUi.Stack card, JSlider slider, String title, String unit,
                           java.util.function.IntConsumer setter) {
        javax.swing.JLabel label = ui.label(title + "  " + slider.getValue() + unit, 12, false, ui.theme.muted);
        label.setLabelFor(slider);
        slider.addChangeListener(e -> {
            label.setText(title + "  " + slider.getValue() + unit);
            setter.accept(slider.getValue());
        });
        card.add(label);
        card.add(slider);
    }

    void stop() {
        ++playbackRevision;
        binaural.stop();
        if (play != null) renderPlaying();
    }

    void awaitStopped(long millis) throws InterruptedException {
        binaural.awaitStopped(millis);
    }

    private void toggle() {
        if (binaural.isPlaying()) {
            stop();
            return;
        }
        int revision = ++playbackRevision;
        binaural.start(angle -> SwingUtilities.invokeLater(() -> {
            if (revision == playbackRevision) dial.setAzimuthDeg(angle);
        }), error -> SwingUtilities.invokeLater(() -> {
            if (revision != playbackRevision) return;
            renderPlaying();
            System.err.println("[WearEars] " + error);
            dialogs.info(ui.t("音频输出", "Audio output"),
                    ui.t("音频设备未能开始播放。请检查 Windows 声音输出后重试。",
                            "Playback could not start. Check your Windows output device and try again."),
                    JOptionPane.WARNING_MESSAGE);
        }));
        renderPlaying();
    }

    private void renderPlaying() {
        boolean active = binaural.isPlaying();
        play.setText(active ? ui.t("停止试听", "Stop playback") : ui.t("开始双耳试听", "Play binaural audio"));
        play.setSelected(!active);
        dial.setPlaying(active);
    }
}
