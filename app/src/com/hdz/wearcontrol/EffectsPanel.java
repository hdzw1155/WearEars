package com.hdz.wearcontrol;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import java.util.EnumSet;
import java.util.Set;

/**
 * 耳机硬件空间音频（含声场与头部追踪）、本机单端空间音频（含 360° 实验室入口）及内置音效面板。
 * 支持 VERIF_REAL 主路径音效卡与 Dev Lab 实验音效卡动态芯片池。
 */
final class EffectsPanel implements MainPanel {
    private final Activity activity;
    private final MainPanel.Actions actions;
    private final int uiStyle;
    private final boolean english;

    private final View spatialCard, spatialSceneContainer, headTrackingRow;
    private final View badgeSpatialExp, badgeHeadTrackingExp;
    private final TextView spatialTitle, spatialHint;
    private final Switch spatial, headTrackingSwitch;
    private final SnapChipRow spatialSceneScroller;
    private final LinearLayout spatialSceneRow;

    private final View localSpatialCard, localSpatialSceneWrapper, btnSpatialLab;
    private final TextView localSpatialTitle, localSpatialStatus, localSpatialLimit;
    private final Switch localSpatialSwitch;
    private final SnapChipRow localSpatialSceneScroller;
    private final LinearLayout localSpatialSceneRow;
    private final OptionItem[] localSpatialScenes;
    private final WearEarsSpatialEffect.StatusListener localSpatialStatusListener;

    private final View equalizerCard, badgeEqExp;
    private final TextView equalizerTitle, deepxCurrent;
    private final SnapChipRow deepxScroller;
    private final LinearLayout deepxRow;

    private final Set<FeatureId> sessionConfirmed = EnumSet.noneOf(FeatureId.class);
    private DeviceState state;
    private long lastSession = -1L;
    private boolean wasConnected;
    private boolean updating;
    private int eqSelection = -1;
    private int sceneSelection = -1;
    private int localSceneSelection = -1;

    public EffectsPanel(Activity activity, final MainPanel.Actions actions) {
        this.activity = activity;
        this.actions = actions;
        uiStyle = AppPrefs.uiStyle(activity);
        english = AppPrefs.LANG_EN.equals(AppPrefs.lang(activity));

        spatialCard = activity.findViewById(R.id.spatial_card);
        spatialTitle = activity.findViewById(R.id.spatial_title);
        badgeSpatialExp = activity.findViewById(R.id.badge_spatial_exp);
        spatial = activity.findViewById(R.id.switch_spatial);
        spatialHint = activity.findViewById(R.id.spatial_hint);
        spatialSceneContainer = activity.findViewById(R.id.spatial_scene_container);
        spatialSceneRow = activity.findViewById(R.id.spatial_scene_row);
        spatialSceneScroller = findSnapRow(spatialSceneContainer, spatialSceneRow);
        headTrackingRow = activity.findViewById(R.id.head_tracking_row);
        badgeHeadTrackingExp = activity.findViewById(R.id.badge_head_tracking_exp);
        headTrackingSwitch = activity.findViewById(R.id.switch_head_tracking);

        localSpatialCard = activity.findViewById(R.id.local_spatial_card);
        localSpatialTitle = activity.findViewById(R.id.local_spatial_title);
        localSpatialSwitch = activity.findViewById(R.id.switch_local_spatial);
        localSpatialStatus = activity.findViewById(R.id.local_spatial_status);
        localSpatialLimit = activity.findViewById(R.id.local_spatial_limit);
        localSpatialSceneRow = activity.findViewById(R.id.local_spatial_scene_row);
        localSpatialSceneScroller = findSnapRow(localSpatialCard, localSpatialSceneRow);
        localSpatialSceneWrapper = (localSpatialSceneRow != null
                && localSpatialSceneRow.getParent() instanceof View
                && localSpatialSceneRow.getParent() != localSpatialCard)
                ? (View) localSpatialSceneRow.getParent() : localSpatialSceneRow;
        btnSpatialLab = activity.findViewById(R.id.btn_spatial_lab);
        localSpatialScenes = new OptionItem[] {
                localSceneOption(activity, WearEarsSpatialEffect.SCENE_WIDE, R.string.spatial_scene_wide),
                localSceneOption(activity, WearEarsSpatialEffect.SCENE_CINEMA, R.string.spatial_scene_cinema),
                localSceneOption(activity, WearEarsSpatialEffect.SCENE_CONCERT, R.string.spatial_scene_concert),
                localSceneOption(activity, WearEarsSpatialEffect.SCENE_STUDIO, R.string.spatial_scene_studio)
        };
        localSpatialStatusListener = new WearEarsSpatialEffect.StatusListener() {
            @Override public void onSpatialStatusChanged(WearEarsSpatialEffect.Status status) {
                if (localSpatialStatus != null && status != null) {
                    localSpatialStatus.setText(status.stringRes);
                }
            }
        };

        equalizerCard = activity.findViewById(R.id.equalizer_card);
        equalizerTitle = activity.findViewById(R.id.equalizer_title);
        badgeEqExp = activity.findViewById(R.id.badge_eq_exp);
        deepxCurrent = activity.findViewById(R.id.deepx_current);
        deepxScroller = activity.findViewById(R.id.deepx_scroller);
        deepxRow = activity.findViewById(R.id.deepx_row);

        if (spatial != null) {
            spatial.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(CompoundButton button, boolean checked) {
                    if (updating) return;
                    onSpatialToggled(checked);
                }
            });
        }
        if (headTrackingSwitch != null) {
            headTrackingSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(CompoundButton button, boolean checked) {
                    if (updating) return;
                    onHeadTrackingToggled(checked);
                }
            });
        }
        if (localSpatialSwitch != null) {
            localSpatialSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(CompoundButton button, boolean checked) {
                    if (updating) return;
                    onLocalSpatialToggled(checked);
                }
            });
        }
        if (btnSpatialLab != null) {
            btnSpatialLab.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    activity.startActivity(SpatialLabActivity.intent(activity));
                }
            });
        }
    }

    private static OptionItem localSceneOption(Activity act, int id, int labelRes) {
        String label = act.getString(labelRes);
        return new OptionItem(id, label, label);
    }

    private static SnapChipRow findSnapRow(View container, View row) {
        if (row != null && row.getParent() instanceof SnapChipRow) return (SnapChipRow) row.getParent();
        if (container instanceof SnapChipRow) return (SnapChipRow) container;
        if (container instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) container;
            for (int i = 0; i < vg.getChildCount(); i++) {
                if (vg.getChildAt(i) instanceof SnapChipRow) return (SnapChipRow) vg.getChildAt(i);
            }
        }
        return null;
    }

    private boolean spatialAvailable() {
        if (state == null || !state.connected) return false;
        FeatureDescriptor spec = state.featureSpec(FeatureId.SPATIAL_AUDIO);
        if (spec != null) return state.isFeatureWritable(FeatureId.SPATIAL_AUDIO);
        return state.spatial != null && state.capability(TwsClient.CAP_SPATIAL) == DeviceState.SUPPORTED;
    }

    private void onSpatialToggled(boolean checked) {
        final DeviceState cur = state;
        if (!spatialAvailable() || actions.pendingFeature(FeatureId.SPATIAL_AUDIO) >= 0) {
            if (cur != null) render(cur, -1);
            return;
        }
        if (!checked) {
            actions.selectSpatial(false);
            return;
        }
        final long session = cur.session;
        renderSpatial();
        SafetyGates.confirmAudioRisk(activity, R.string.spatial_dialog_title, R.string.spatial_dialog_msg,
                session, cur, new Runnable() {
                    @Override public void run() {
                        if (state != null && state.session == session && spatialAvailable()) {
                            actions.selectSpatial(true);
                        }
                    }
                });
    }

    private void onHeadTrackingToggled(boolean checked) {
        final DeviceState cur = state;
        if (cur == null || !cur.connected || !cur.isFeatureWritable(FeatureId.HEAD_TRACKING)
                || actions.pendingFeature(FeatureId.HEAD_TRACKING) >= 0) {
            if (cur != null) render(cur, -1);
            return;
        }
        if (!checked) {
            actions.selectFeature(FeatureId.HEAD_TRACKING, 0);
            return;
        }
        final long session = cur.session;
        renderSpatial();
        SafetyGates.confirmAudioRisk(activity, R.string.head_tracking_title, R.string.head_tracking_msg,
                session, cur, new Runnable() {
                    @Override public void run() {
                        if (state != null && state.connected && state.session == session
                                && state.isFeatureWritable(FeatureId.HEAD_TRACKING)) {
                            actions.selectFeature(FeatureId.HEAD_TRACKING, 1);
                        }
                    }
                });
    }

    private void onSpatialSceneSelected(final FeatureDescriptor spec, final OptionItem opt) {
        final DeviceState cur = state;
        if (cur == null || !cur.connected || spec == null || opt == null
                || !cur.isFeatureWritable(FeatureId.SPATIAL_SCENE)
                || actions.pendingFeature(FeatureId.SPATIAL_SCENE) >= 0) {
            return;
        }
        final long session = cur.session;
        Runnable send = new Runnable() {
            @Override public void run() {
                if (state != null && state.connected && state.session == session
                        && state.isFeatureWritable(FeatureId.SPATIAL_SCENE)) {
                    actions.selectFeature(FeatureId.SPATIAL_SCENE, opt.id);
                }
            }
        };
        if (spec.verif == Controls.VERIF_UNTESTED) {
            SafetyGates.confirmExperimentalOnce(activity, spec, sessionConfirmed, session, cur, send);
        } else {
            send.run();
        }
    }

    private void onEqualizerSelected(final FeatureDescriptor eqSpec, final OptionItem opt) {
        final DeviceState cur = state;
        if (cur == null || !cur.connected || opt == null
                || actions.pendingFeature(FeatureId.EQUALIZER) >= 0) {
            return;
        }
        boolean writable = eqSpec != null ? cur.isFeatureWritable(FeatureId.EQUALIZER)
                : (cur.caps & (TwsClient.CAP_EQUALIZER | TwsClient.CAP_DEEPX)) != 0;
        if (!writable) return;

        final long session = cur.session;
        Runnable send = new Runnable() {
            @Override public void run() {
                if (state != null && state.connected && state.session == session) {
                    actions.selectEqualizer(opt.id);
                }
            }
        };
        if (eqSpec != null && eqSpec.verif == Controls.VERIF_UNTESTED) {
            SafetyGates.confirmExperimentalOnce(activity, eqSpec, sessionConfirmed, session, cur, send);
        } else {
            send.run();
        }
    }

    private void renderSpatial() {
        if (state == null) return;
        FeatureDescriptor spatialSpec = state.featureSpec(FeatureId.SPATIAL_AUDIO);
        boolean hasHwSpatial = state.connected
                && (spatialSpec != null || (state.potentialCaps & TwsClient.CAP_SPATIAL) != 0);
        if (spatialCard != null) spatialCard.setVisibility(hasHwSpatial ? View.VISIBLE : View.GONE);
        if (spatialTitle != null) spatialTitle.setText(R.string.earbud_spatial_title);
        if (badgeSpatialExp != null) {
            boolean exp = spatialSpec == null || spatialSpec.verif == Controls.VERIF_UNTESTED;
            badgeSpatialExp.setVisibility(hasHwSpatial && exp ? View.VISIBLE : View.GONE);
        }

        boolean available = spatialAvailable();
        int pendingSpatial = actions.pendingFeature(FeatureId.SPATIAL_AUDIO);
        boolean spatialOn = available && (pendingSpatial >= 0 ? pendingSpatial != 0
                : (Boolean.TRUE.equals(state.spatial)
                        || Integer.valueOf(1).equals(state.featureValue(FeatureId.SPATIAL_AUDIO))
                        || Integer.valueOf(2).equals(state.featureValue(FeatureId.SPATIAL_AUDIO))));

        if (spatial != null) {
            updating = true;
            spatial.setChecked(spatialOn);
            spatial.setEnabled(available && pendingSpatial < 0);
            spatial.setStateDescription(available && pendingSpatial < 0
                    ? null : activity.getString(pendingSpatial >= 0
                            ? R.string.spatial_waiting : R.string.spatial_state_unknown));
            updating = false;
        }
        if (spatialHint != null) {
            if (pendingSpatial >= 0) {
                spatialHint.setText(R.string.spatial_waiting);
            } else if (!available) {
                spatialHint.setText(R.string.spatial_state_unknown);
            } else {
                String limit = spatialSpec != null ? spatialSpec.limit(english) : "";
                if (!limit.isEmpty()) spatialHint.setText(limit);
                else spatialHint.setText(R.string.earbud_spatial_hint);
            }
        }

        Integer spatialVal = state.featureValue(FeatureId.SPATIAL_AUDIO);
        boolean parentSpatialOn = spatialSpec == null
                || (spatialVal != null && spatialVal.intValue() != 0);
        final FeatureDescriptor sceneSpec = state.featureSpec(FeatureId.SPATIAL_SCENE);
        Integer sceneVal = state.featureValue(FeatureId.SPATIAL_SCENE);
        boolean showScene = state.connected && sceneSpec != null && sceneSpec.options.length > 0
                && sceneVal != null && parentSpatialOn && pendingSpatial != 0;
        if (spatialSceneContainer != null) {
            spatialSceneContainer.setVisibility(showScene ? View.VISIBLE : View.GONE);
        }
        if (showScene && spatialSceneRow != null) {
            int pendingScene = actions.pendingFeature(FeatureId.SPATIAL_SCENE);
            int activeScene = pendingScene >= 0 ? pendingScene : sceneVal;
            sceneSelection = MainPanels.renderOptionChips(activity, spatialSceneRow, spatialSceneScroller,
                    sceneSpec.options, activeScene, state.isFeatureWritable(FeatureId.SPATIAL_SCENE),
                    pendingScene >= 0, uiStyle, english, true, sceneSelection,
                    new MainPanels.OptionSelectListener() {
                        @Override public void onOptionSelected(OptionItem option) {
                            onSpatialSceneSelected(sceneSpec, option);
                        }
                    });
        } else {
            sceneSelection = -1;
        }

        FeatureDescriptor headSpec = state.featureSpec(FeatureId.HEAD_TRACKING);
        Integer headVal = state.featureValue(FeatureId.HEAD_TRACKING);
        boolean showHead = state.connected && headSpec != null && headVal != null
                && parentSpatialOn && pendingSpatial != 0;
        if (headTrackingRow != null) headTrackingRow.setVisibility(showHead ? View.VISIBLE : View.GONE);
        if (badgeHeadTrackingExp != null) {
            badgeHeadTrackingExp.setVisibility(showHead && headSpec.verif == Controls.VERIF_UNTESTED
                    ? View.VISIBLE : View.GONE);
        }
        if (showHead && headTrackingSwitch != null) {
            int pendingHead = actions.pendingFeature(FeatureId.HEAD_TRACKING);
            boolean headChecked = pendingHead >= 0 ? pendingHead != 0 : headVal != 0;
            updating = true;
            headTrackingSwitch.setChecked(headChecked);
            headTrackingSwitch.setEnabled(state.isFeatureWritable(FeatureId.HEAD_TRACKING) && pendingHead < 0);
            updating = false;
        }
    }

    private void renderEqualizer() {
        final FeatureDescriptor eqSpec = state.featureSpec(FeatureId.EQUALIZER);
        OptionItem[] options = (eqSpec != null && eqSpec.options.length > 0)
                ? eqSpec.options : OptionItem.fromPresets(state.presets);
        boolean hasEq = state.connected && options.length > 0
                && (eqSpec != null
                || (state.potentialCaps & (TwsClient.CAP_EQUALIZER | TwsClient.CAP_DEEPX)) != 0);

        if (equalizerCard != null) equalizerCard.setVisibility(hasEq ? View.VISIBLE : View.GONE);
        int titleRes = (state.potentialCaps & TwsClient.CAP_DEEPX) != 0
                ? R.string.deepx_label : R.string.equalizer_label;
        if (equalizerTitle != null) equalizerTitle.setText(titleRes);
        if (badgeEqExp != null) {
            boolean exp = eqSpec == null || eqSpec.verif == Controls.VERIF_UNTESTED;
            badgeEqExp.setVisibility(hasEq && exp ? View.VISIBLE : View.GONE);
        }

        if (!hasEq) {
            eqSelection = -1;
            return;
        }

        boolean eqEnabled = state.connected && (eqSpec != null
                ? state.isFeatureWritable(FeatureId.EQUALIZER)
                : (state.caps & (TwsClient.CAP_EQUALIZER | TwsClient.CAP_DEEPX)) != 0);
        int pendingEq = actions.pendingFeature(FeatureId.EQUALIZER);
        Integer confirmedEq = state.equalizer != null ? state.equalizer : state.featureValue(FeatureId.EQUALIZER);
        int activeEq = pendingEq >= 0 ? pendingEq
                : (confirmedEq != null ? confirmedEq : Integer.MIN_VALUE);

        boolean compact = uiStyle == AppPrefs.STYLE_SUPREME;
        eqSelection = MainPanels.renderOptionChips(activity, deepxRow, deepxScroller, options,
                activeEq, eqEnabled, pendingEq >= 0, uiStyle, english, compact, eqSelection,
                new MainPanels.OptionSelectListener() {
                    @Override public void onOptionSelected(OptionItem option) {
                        onEqualizerSelected(eqSpec, option);
                    }
                });

        String name = "--";
        if (confirmedEq != null) {
            for (OptionItem opt : options) {
                if (opt.id == confirmedEq) {
                    name = opt.label(english);
                    break;
                }
            }
        }
        if (deepxCurrent != null) {
            deepxCurrent.setText(pendingEq >= 0 ? activity.getString(R.string.sub_deepx_pending) : name);
        }
    }

    private boolean isHardwareSpatialOn(DeviceState cur) {
        return cur != null && cur.connected
                && (Boolean.TRUE.equals(cur.spatial)
                        || Integer.valueOf(1).equals(cur.featureValue(FeatureId.SPATIAL_AUDIO)));
    }

    private void onLocalSpatialToggled(boolean checked) {
        AppPrefs.setLocalSpatialEnabled(activity, checked);
        boolean hwSpatialOn = isHardwareSpatialOn(state);
        if (checked && hwSpatialOn && spatialAvailable()
                && actions.pendingFeature(FeatureId.SPATIAL_AUDIO) < 0) {
            // Layer 3 防叠加: 开启本机单端空间音频时主动关闭耳机端硬件空间音频
            actions.selectSpatial(false);
        }
        WearEarsSpatialEffect.get(activity).syncFromUserAction(activity, hwSpatialOn);
        renderLocalSpatial();
    }

    private void renderLocalSpatial() {
        boolean visible = AppPrefs.hasDisclaimer(activity);
        if (localSpatialCard != null) {
            localSpatialCard.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        if (!visible) {
            localSceneSelection = -1;
            return;
        }
        String title = DeviceForm.localSpatialTitle(activity);
        if (localSpatialTitle != null) localSpatialTitle.setText(title);
        if (localSpatialLimit != null) {
            localSpatialLimit.setText(DeviceForm.localSpatialLimitation(activity));
        }
        boolean enabled = AppPrefs.localSpatialEnabled(activity);
        if (localSpatialSwitch != null) {
            updating = true;
            localSpatialSwitch.setChecked(enabled);
            localSpatialSwitch.setContentDescription(title);
            updating = false;
        }
        if (localSpatialStatus != null) {
            localSpatialStatus.setText(WearEarsSpatialEffect.get(activity).statusStringRes());
        }
        if (localSpatialSceneWrapper != null) {
            localSpatialSceneWrapper.setVisibility(enabled ? View.VISIBLE : View.GONE);
        }
        if (enabled && localSpatialSceneRow != null) {
            int activeScene = AppPrefs.localSpatialScene(activity);
            boolean compact = uiStyle == AppPrefs.STYLE_SUPREME;
            localSceneSelection = MainPanels.renderOptionChips(activity, localSpatialSceneRow,
                    localSpatialSceneScroller, localSpatialScenes, activeScene, true, false,
                    uiStyle, english, compact, localSceneSelection,
                    new MainPanels.OptionSelectListener() {
                        @Override public void onOptionSelected(OptionItem option) {
                            if (option == null) return;
                            AppPrefs.setLocalSpatialScene(activity, option.id);
                            WearEarsSpatialEffect.get(activity).syncFromUserAction(
                                    activity, isHardwareSpatialOn(state));
                            renderLocalSpatial();
                        }
                    });
        } else {
            localSceneSelection = -1;
        }
    }

    @Override public void render(DeviceState state, int pendingMode) {
        this.state = state;
        if (!state.connected || state.session != lastSession) {
            sessionConfirmed.clear();
            lastSession = state.session;
        }
        if (wasConnected && !state.connected) {
            SafetyGates.dismissAll(activity);
        }
        wasConnected = state.connected;
        renderSpatial();
        renderLocalSpatial();
        renderEqualizer();
    }

    @Override public void resume() {
        WearEarsSpatialEffect.get(activity).addListener(localSpatialStatusListener);
        renderLocalSpatial();
    }

    @Override public void pause() {
        WearEarsSpatialEffect.get(activity).removeListener(localSpatialStatusListener);
        SafetyGates.dismissAll(activity);
    }
}
