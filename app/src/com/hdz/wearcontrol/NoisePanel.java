package com.hdz.wearcontrol;

import android.app.Activity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import java.util.EnumSet;
import java.util.Set;

/**
 * 能力未知时保留卡片并禁用写入，收到当前会话确认后才启用。
 * 支持 ANC_LEVEL / TRANS_MODE / WIND_REDUCTION 渐进式展开。
 */
final class NoisePanel implements MainPanel {
    private final Activity activity;
    private final MainPanel.Actions actions;
    private final View card;
    private final NoiseSegmentedView segmented;
    private final SuprematistDialView dial;
    private final View subContainer, subHeader, windRow;
    private final TextView subLabel;
    private final View subExpBadge, windExpBadge;
    private final SnapChipRow subScroller;
    private final LinearLayout subRow;
    private final Switch windSwitch;
    private final int uiStyle;
    private final boolean english;
    private final Set<FeatureId> sessionConfirmed = EnumSet.noneOf(FeatureId.class);

    private DeviceState state;
    private int lastPendingMode = -1;
    private long lastSession = -1L;
    private FeatureId lastSubId;
    private int subSelection = -1;
    private boolean updating;

    public NoisePanel(Activity activity, final MainPanel.Actions actions) {
        this.activity = activity;
        this.actions = actions;
        uiStyle = AppPrefs.uiStyle(activity);
        english = AppPrefs.LANG_EN.equals(AppPrefs.lang(activity));
        card = activity.findViewById(R.id.noise_card);
        segmented = activity.findViewById(R.id.noise_seg);
        dial = activity.findViewById(R.id.sup_dial);
        subContainer = activity.findViewById(R.id.noise_sub_container);
        subHeader = activity.findViewById(R.id.noise_sub_header);
        subLabel = activity.findViewById(R.id.noise_sub_label);
        subExpBadge = activity.findViewById(R.id.badge_noise_sub_exp);
        subScroller = activity.findViewById(R.id.noise_sub_scroller);
        subRow = activity.findViewById(R.id.noise_sub_row);
        windRow = activity.findViewById(R.id.wind_toggle_row);
        windExpBadge = activity.findViewById(R.id.badge_wind_exp);
        windSwitch = activity.findViewById(R.id.switch_wind);

        if (dial != null) dial.enablePanel(TwsClient.CAP_NOISE);
        if (segmented != null && AppPrefs.isNeo(uiStyle)) NeoStyle.styleNoise(segmented, uiStyle);
        if (segmented != null) {
            segmented.setOnModeChangeListener(new NoiseSegmentedView.OnModeChangeListener() {
                @Override public void onModeSelected(int mode) { actions.selectNoise(mode); }
            });
        }
        if (windSwitch != null) {
            windSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(CompoundButton button, boolean checked) {
                    if (updating) return;
                    onWindToggled(checked);
                }
            });
        }
    }

    private void onWindToggled(boolean checked) {
        final DeviceState cur = state;
        if (cur == null || !cur.connected || !cur.isFeatureWritable(FeatureId.WIND_REDUCTION)
                || lastPendingMode >= 0 || actions.pendingFeature(FeatureId.WIND_REDUCTION) >= 0) {
            if (cur != null) render(cur, lastPendingMode);
            return;
        }
        final FeatureDescriptor windSpec = cur.featureSpec(FeatureId.WIND_REDUCTION);
        final long session = cur.session;
        final int target = checked ? 1 : 0;
        render(cur, lastPendingMode);
        if (windSpec == null) return;

        Runnable send = new Runnable() {
            @Override public void run() {
                if (state != null && state.connected && state.session == session
                        && state.isFeatureWritable(FeatureId.WIND_REDUCTION)) {
                    actions.selectFeature(FeatureId.WIND_REDUCTION, target);
                }
            }
        };
        if (checked && windSpec.verif == Controls.VERIF_UNTESTED) {
            SafetyGates.confirmExperimentalOnce(activity, windSpec, sessionConfirmed, session, cur, send);
        } else if (checked && windSpec.safety == FeatureDescriptor.SAFETY_AUDIO_WARN) {
            SafetyGates.confirmAudioRisk(activity, R.string.exp_first_warn_title,
                    R.string.exp_first_warn_msg, session, cur, send);
        } else {
            send.run();
        }
    }

    private void onSubSelected(final FeatureId subId, final FeatureDescriptor spec, final OptionItem opt) {
        final DeviceState cur = state;
        if (cur == null || !cur.connected || spec == null || opt == null
                || !cur.isFeatureWritable(subId) || lastPendingMode >= 0
                || actions.pendingFeature(subId) >= 0) {
            return;
        }
        final long session = cur.session;
        Runnable send = new Runnable() {
            @Override public void run() {
                if (state != null && state.connected && state.session == session
                        && state.isFeatureWritable(subId)) {
                    actions.selectFeature(subId, opt.id);
                }
            }
        };
        if (spec.verif == Controls.VERIF_UNTESTED) {
            SafetyGates.confirmExperimentalOnce(activity, spec, sessionConfirmed, session, cur, send);
        } else if (spec.safety == FeatureDescriptor.SAFETY_AUDIO_WARN) {
            SafetyGates.confirmAudioRisk(activity, R.string.exp_first_warn_title,
                    R.string.exp_first_warn_msg, session, cur, send);
        } else {
            send.run();
        }
    }

    @Override public void render(DeviceState state, int pendingMode) {
        this.state = state;
        this.lastPendingMode = pendingMode;
        if (!state.connected || state.session != lastSession) {
            sessionConfirmed.clear();
            lastSession = state.session;
        }

        int capability = state.capability(TwsClient.CAP_NOISE);
        boolean enabled = state.connected && capability == DeviceState.SUPPORTED;
        if (card != null) card.setVisibility(capability == DeviceState.UNSUPPORTED ? View.GONE : View.VISIBLE);
        int mode = state.connected ? (pendingMode >= 0 ? pendingMode : state.mode) : -1;
        if (segmented != null) {
            segmented.setEnabled(enabled);
            segmented.setAlpha(enabled ? 1f : 0.65f);
            segmented.setMode(mode, true);
            segmented.setContentDescription(activity.getString(capability == DeviceState.UNKNOWN
                    ? R.string.noise_waiting : R.string.noise_title));
        }
        if (dial != null) dial.setNoiseState(mode, state.connected && capability != DeviceState.UNSUPPORTED);

        FeatureId subId = null;
        if (enabled) {
            if (mode == Controls.MODE_ANC) subId = FeatureId.ANC_LEVEL;
            else if (mode == Controls.MODE_TRANS) subId = FeatureId.TRANS_MODE;
        }
        final FeatureDescriptor subSpec = subId != null ? state.featureSpec(subId) : null;
        boolean showSub = subSpec != null && subSpec.options.length > 0 && subSpec.allowsNoiseMode(mode);
        if (showSub) {
            if (subHeader != null) subHeader.setVisibility(View.VISIBLE);
            if (subLabel != null) {
                subLabel.setText(subId == FeatureId.TRANS_MODE
                        ? R.string.trans_sub_label : R.string.noise_sub_label);
                subLabel.setVisibility(View.VISIBLE);
            }
            if (subExpBadge != null) {
                subExpBadge.setVisibility(subSpec.verif == Controls.VERIF_UNTESTED
                        ? View.VISIBLE : View.GONE);
            }
            if (subScroller != null) subScroller.setVisibility(View.VISIBLE);
            if (subRow != null) subRow.setVisibility(View.VISIBLE);
            int pendingSub = actions.pendingFeature(subId);
            Integer confirmedSub = state.featureValue(subId);
            int activeId = pendingSub >= 0 ? pendingSub
                    : (confirmedSub != null ? confirmedSub : Integer.MIN_VALUE);
            boolean writable = state.isFeatureWritable(subId) && pendingMode < 0;
            if (subId != lastSubId) {
                subSelection = -1;
                lastSubId = subId;
            }
            final FeatureId targetSubId = subId;
            subSelection = MainPanels.renderOptionChips(activity, subRow, subScroller, subSpec.options,
                    activeId, writable, pendingSub >= 0, uiStyle, english, true, subSelection,
                    new MainPanels.OptionSelectListener() {
                        @Override public void onOptionSelected(OptionItem option) {
                            onSubSelected(targetSubId, subSpec, option);
                        }
                    });
        } else {
            if (subHeader != null) subHeader.setVisibility(View.GONE);
            if (subLabel != null) subLabel.setVisibility(View.GONE);
            if (subExpBadge != null) subExpBadge.setVisibility(View.GONE);
            if (subScroller != null) subScroller.setVisibility(View.GONE);
            else if (subRow != null) subRow.setVisibility(View.GONE);
            subSelection = -1;
            lastSubId = null;
        }

        FeatureDescriptor windSpec = enabled ? state.featureSpec(FeatureId.WIND_REDUCTION) : null;
        Integer windVal = state.featureValue(FeatureId.WIND_REDUCTION);
        boolean showWind = windSpec != null && windSpec.allowsNoiseMode(mode) && windVal != null;
        if (windRow != null) windRow.setVisibility(showWind ? View.VISIBLE : View.GONE);
        if (windExpBadge != null) {
            windExpBadge.setVisibility(showWind && windSpec.verif == Controls.VERIF_UNTESTED
                    ? View.VISIBLE : View.GONE);
        }
        if (showWind && windSwitch != null) {
            int pendingWind = actions.pendingFeature(FeatureId.WIND_REDUCTION);
            boolean windChecked = pendingWind >= 0 ? pendingWind != 0 : windVal != 0;
            updating = true;
            windSwitch.setChecked(windChecked);
            windSwitch.setEnabled(state.isFeatureWritable(FeatureId.WIND_REDUCTION)
                    && pendingWind < 0 && pendingMode < 0);
            updating = false;
        }

        if (subContainer != null) {
            subContainer.setVisibility((showSub || showWind) ? View.VISIBLE : View.GONE);
        }

        if (dial != null) {
            String subTag = "";
            if (enabled) {
                if (showSub) {
                    Integer confirmedSub = state.featureValue(subId);
                    if (confirmedSub != null) {
                        OptionItem opt = subSpec.findOption(confirmedSub);
                        if (opt != null) subTag = opt.label(english);
                    }
                }
                if (showWind && windVal != 0) {
                    String windText = activity.getString(R.string.wind_reduction_title);
                    subTag = subTag.isEmpty() ? windText : (subTag + " · " + windText);
                }
            }
            dial.setSubTag(subTag);
        }
    }

    @Override public void pause() {
        SafetyGates.dismissAll(activity);
    }
}
