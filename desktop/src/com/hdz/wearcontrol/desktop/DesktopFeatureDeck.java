package com.hdz.wearcontrol.desktop;

import com.hdz.wearcontrol.Controls;
import com.hdz.wearcontrol.DesktopCoreBridge;
import com.hdz.wearcontrol.DesktopCoreBridge.StateSnapshotView;
import com.hdz.wearcontrol.FeatureDescriptor;
import com.hdz.wearcontrol.FeatureId;
import com.hdz.wearcontrol.OptionItem;
import com.hdz.wearcontrol.SoundPreset;

import javax.swing.JCheckBox;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.Timer;
import java.util.Arrays;
import java.util.Objects;

final class DesktopFeatureDeck {
    private static final FeatureId[] ORDER = {FeatureId.ANC_LEVEL, FeatureId.WIND_REDUCTION,
            FeatureId.TRANS_MODE, FeatureId.EQUALIZER, FeatureId.SPATIAL_AUDIO, FeatureId.LOW_LATENCY,
            FeatureId.MULTIPOINT, FeatureId.WEAR_DETECTION, FeatureId.FIND_MY_BUDS};

    private final DesktopPrefs prefs;
    private final DesktopSession session;
    private final DesktopDialogs dialogs;
    private final Timer latencyTimeout;
    private DesktopUi ui;
    private DesktopUi.Stack deck;
    private StateSnapshotView last;
    private boolean latencyPending;
    private int latencyRevision;
    private int latencyConnection;
    private boolean latencyTimedOut;

    DesktopFeatureDeck(DesktopPrefs prefs, DesktopSession session, DesktopDialogs dialogs) {
        this.prefs = prefs;
        this.session = session;
        this.dialogs = dialogs;
        latencyTimeout = new Timer(4500, e -> {
            latencyPending = false;
            latencyTimedOut = true;
            render(session.snapshot());
        });
        latencyTimeout.setRepeats(false);
    }

    JPanel view(DesktopUi ui) {
        this.ui = ui;
        deck = ui.stack(11);
        last = null;
        return deck;
    }

    void update(StateSnapshotView state) {
        boolean latencyChanged = last != null && state.featureRevision(FeatureId.LOW_LATENCY)
                != last.featureRevision(FeatureId.LOW_LATENCY);
        boolean clearPending = latencyPending && (!state.connected || session.connectionId() != latencyConnection
                || state.featureRevision(FeatureId.LOW_LATENCY) != latencyRevision);
        if (clearPending) {
            latencyPending = false;
            latencyTimeout.stop();
        }
        if (latencyChanged || !state.connected) latencyTimedOut = false;
        if (clearPending || latencyChanged || !sameFeatures(state, last)) render(state);
    }

    void close() { latencyTimeout.stop(); }

    private static boolean sameFeatures(StateSnapshotView a, StateSnapshotView b) {
        return b != null && a.connected == b.connected && a.caps == b.caps && a.mode == b.mode
                && a.wearStatus == b.wearStatus && a.address.equals(b.address) && a.features.equals(b.features)
                && a.featureValues.equals(b.featureValues) && a.featureTexts.equals(b.featureTexts)
                && Objects.equals(a.equalizer, b.equalizer) && Objects.equals(a.spatial, b.spatial)
                && Arrays.equals(a.presets, b.presets);
    }

    private void render(StateSnapshotView state) {
        last = state;
        deck.removeAll();
        if (state.connected) {
            for (FeatureId id : ORDER) {
                FeatureDescriptor spec = state.features.get(id);
                if (spec != null) deck.add(featureCard(state, spec));
                else if (id == FeatureId.EQUALIZER) addLegacyEqualizer(state);
                else if (id == FeatureId.SPATIAL_AUDIO) addLegacySpatial(state);
            }
        }
        deck.revalidate();
        deck.repaint();
    }

    private DesktopUi.Stack featureCard(StateSnapshotView state, FeatureDescriptor spec) {
        FeatureId id = spec.id;
        boolean experimental = id == FeatureId.SPATIAL_AUDIO || spec.verif != Controls.VERIF_REAL
                || spec.safety == FeatureDescriptor.SAFETY_AUDIO_WARN;
        DesktopUi.Stack card = ui.card(ui.strings.feature(id) + (experimental ? ui.strings.experimental() : ""));
        Integer value = state.featureValues.get(id);
        String reportedText = state.featureTexts.get(id);
        boolean writable = spec.allowsNoiseMode(state.mode) && session.isFeatureWritable(id);
        if (id == FeatureId.FIND_MY_BUDS) {
            findBudsControls(card, spec, writable);
        } else if (spec.kind == FeatureDescriptor.Kind.CHOICE) {
            DesktopUi.Wrap choices = ui.row();
            for (OptionItem opt : spec.options) {
                DesktopUi.Button option = ui.button(opt.label(ui.strings.english), Objects.equals(value, opt.id),
                        () -> change(spec, opt.id, experimental));
                option.setEnabled(writable);
                choices.add(option);
            }
            card.add(choices);
        } else if (spec.kind == FeatureDescriptor.Kind.TOGGLE) {
            addToggle(card, spec, value, writable, experimental);
        } else if (spec.kind == FeatureDescriptor.Kind.SLIDER) {
            int read = value == null ? spec.minValue : Math.max(spec.minValue, Math.min(spec.maxValue, value));
            JSlider slider = new JSlider(spec.minValue, spec.maxValue, read);
            slider.setOpaque(false);
            slider.setForeground(ui.theme.accent);
            slider.setFont(DesktopTheme.font(java.awt.Font.PLAIN, 12));
            slider.setEnabled(writable);
            card.add(ui.note(value == null ? ui.t("等待设备回报", "Awaiting device status") : Integer.toString(value)));
            slider.addChangeListener(e -> {
                if (!slider.getValueIsAdjusting() && slider.getValue() != read) {
                    if (!change(spec, slider.getValue(), experimental)) slider.setValue(read);
                }
            });
            card.add(slider);
        } else if ((reportedText == null || reportedText.isBlank()) && spec.kind == FeatureDescriptor.Kind.READ_ONLY) {
            card.add(ui.note(value == null ? ui.t("等待设备回报", "Awaiting device status") : Integer.toString(value)));
        }
        if (reportedText != null && !reportedText.isBlank()) card.add(ui.note(reportedText));
        if (!spec.allowsNoiseMode(state.mode)) {
            card.add(ui.note(ui.t("请先切换到支持此功能的降噪模式。", "Select a noise mode that supports this feature.")));
        }
        String limit = spec.limit(ui.strings.english);
        if (!limit.isBlank()) card.add(ui.note(limit));
        return card;
    }

    private void addToggle(DesktopUi.Stack card, FeatureDescriptor spec, Integer value, boolean writable,
                           boolean experimental) {
        boolean selected = value != null && value != 0;
        boolean latency = spec.id == FeatureId.LOW_LATENCY;
        String label = latency && latencyPending ? ui.t("切换中…", "Switching…") : value == null
                ? ui.t("等待设备回报", "Awaiting device status") : selected ? ui.t("已开启", "On") : ui.t("已关闭", "Off");
        JCheckBox control = ui.check(label, selected);
        control.setEnabled(writable && !(latency && latencyPending));
        control.addActionListener(e -> {
            int target = control.isSelected() ? 1 : 0;
            control.setSelected(selected);
            change(spec, target, experimental);
        });
        card.add(control);
        if (latency && latencyTimedOut) {
            card.add(ui.note(ui.t("耳机尚未确认切换，可重试。", "The earbuds did not confirm the change. You can retry.")));
        }
    }

    private boolean change(FeatureDescriptor spec, int value, boolean experimental) {
        int connection = session.connectionId();
        if (!confirmExperimental(experimental) || connection != session.connectionId()
                || !session.isFeatureWritable(spec.id)) return false;
        StateSnapshotView state = session.snapshot();
        if (!spec.allowsNoiseMode(state.mode)) return false;
        if (spec.id == FeatureId.LOW_LATENCY) {
            latencyConnection = connection;
            latencyRevision = state.featureRevision(FeatureId.LOW_LATENCY);
            latencyPending = true;
            latencyTimedOut = false;
            latencyTimeout.restart();
            render(state);
        }
        session.writeFeature(spec.id, value);
        return true;
    }

    private void addLegacyEqualizer(StateSnapshotView state) {
        if ((state.caps & (Controls.CAP_EQUALIZER | Controls.CAP_DEEPX)) == 0 || state.presets.length == 0) return;
        DesktopUi.Stack card = ui.card(ui.strings.feature(FeatureId.EQUALIZER) + ui.strings.experimental());
        DesktopUi.Wrap choices = ui.row();
        for (SoundPreset preset : state.presets) {
            if (preset == null) continue;
            String label = ui.strings.english && !preset.english.isEmpty() ? preset.english : preset.chinese;
            choices.add(ui.button(label, Objects.equals(state.equalizer, preset.id), () -> {
                int connection = session.connectionId();
                if (confirmExperimental(true) && connection == session.connectionId()) session.setEqualizer(preset.id);
            }));
        }
        card.add(choices);
        deck.add(card);
    }

    private void addLegacySpatial(StateSnapshotView state) {
        if ((state.caps & Controls.CAP_SPATIAL) == 0) return;
        DesktopUi.Stack card = ui.card(ui.strings.feature(FeatureId.SPATIAL_AUDIO) + ui.strings.experimental());
        boolean confirmed = Boolean.TRUE.equals(state.spatial);
        JCheckBox spatial = ui.check(ui.t("开启耳机硬件空间音频", "Enable hardware spatial audio"), confirmed);
        spatial.addActionListener(e -> {
            boolean requested = spatial.isSelected();
            spatial.setSelected(confirmed);
            int connection = session.connectionId();
            if (confirmExperimental(true) && connection == session.connectionId()) session.setSpatial(requested);
        });
        card.add(spatial);
        deck.add(card);
    }

    private void findBudsControls(DesktopUi.Stack card, FeatureDescriptor spec, boolean writable) {
        card.add(ui.note(ui.t("摘下双耳再响铃。局域网遥控永久禁止触发查找耳机。",
                "Remove both earbuds before ringing. Find My Buds is permanently blocked over the LAN.")));
        DesktopUi.Wrap targets = ui.row();
        if (spec.options.length == 0) {
            DesktopUi.Button ring = ui.button(ui.t("安全查找响铃", "Ring earbuds"), false, () -> confirmRing(3));
            ring.setEnabled(writable);
            targets.add(ring);
        } else {
            for (OptionItem opt : spec.options) {
                if (opt.id <= 0) continue;
                DesktopUi.Button ring = ui.button(opt.label(ui.strings.english), false, () -> confirmRing(opt.id));
                ring.setEnabled(writable);
                targets.add(ring);
            }
        }
        DesktopUi.Button stop = ui.button(ui.t("立即停止响铃", "Stop ringing now"), false,
                () -> session.writeFeature(FeatureId.FIND_MY_BUDS, 0));
        stop.markDanger();
        targets.add(stop);
        card.add(targets);
    }

    private void confirmRing(int target) {
        int connection = session.connectionId();
        StateSnapshotView live = session.snapshot();
        if (live.isAnyBudInEar()) {
            dialogs.info(ui.t("听觉安全拦截", "Hearing safety"),
                    ui.t("检测到耳机正在佩戴。请摘下双耳后重试。", "An earbud is in an ear. Remove both earbuds and try again."),
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!DesktopSession.canRingFindBuds(live, target, connection, session.connectionId())) return;
        String wear = live.wearStatus == DesktopCoreBridge.WEAR_UNKNOWN
                ? ui.t("耳机未上报佩戴状态。请亲自确认双耳已离开耳道。",
                "Wear status is unknown. Check that both earbuds are out of your ears.")
                : ui.t("耳机上报未佩戴。请再次确认双耳已离开耳道。",
                "The earbuds report they are out of your ears. Check both earbuds again.");
        if (dialogs.confirm(ui.t("听觉安全二次确认", "Confirm hearing safety"),
                wear + "\n\n" + ui.t("查找耳机会发出高音量提示音。", "Find My Buds plays a loud tone."),
                ui.t("确认响铃", "Ring earbuds"), ui.t("取消", "Cancel"))) session.ringFindBuds(target, connection);
    }

    private boolean confirmExperimental(boolean experimental) {
        if (!experimental || prefs.expFeatureConsent()) return true;
        boolean agreed = dialogs.confirm(ui.t("实验功能确认", "Experimental feature"),
                ui.t("不同型号和固件的协议支持会有差异，耳机可能只支持部分档位。",
                        "Protocol support varies by model and firmware. Some settings may be unavailable."),
                ui.t("继续", "Continue"), ui.t("取消", "Cancel"));
        if (agreed) prefs.setExpFeatureConsent(true);
        return agreed;
    }
}
