package com.hdz.wearcontrol;

import android.app.Activity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 设备扩展控制、佩戴状态、查找耳机（三层防损伤门禁）、固件与诚实作用域说明卡，
 * 并统一维护至上（Supreme）扇形页的动态显隐。
 */
final class DeviceFeaturesPanel implements MainPanel {
    private static final class ToggleBinding {
        final FeatureId id;
        final View row;
        final Switch toggle;
        final View expBadge;

        ToggleBinding(FeatureId id, View row, Switch toggle, View expBadge) {
            this.id = id;
            this.row = row;
            this.toggle = toggle;
            this.expBadge = expBadge;
        }
    }

    private final Activity activity;
    private final MainPanel.Actions actions;
    private final boolean english;

    private final View extrasCard, extrasTogglesCard;
    private final View wearStatusRow;
    private final TextView wearStatusText;
    private final List<ToggleBinding> toggles = new ArrayList<>();
    private final View btnFindBuds, badgeFindBuds;
    private final View scopeLimitCard;
    private final TextView firmwareInfoText, scopeLimitText;

    private final SuprematistFanDeckView fanDeck;
    private final View supPageAcoustic, supPageSpatial, supPageExtras;
    private final View noiseSubContainer, equalizerCard, spatialCard, localSpatialCard;

    private final Set<FeatureId> sessionConfirmed = EnumSet.noneOf(FeatureId.class);
    private DeviceState state;
    private long lastSession = -1L;
    private boolean wasConnected;
    private boolean updating;

    DeviceFeaturesPanel(Activity activity, MainPanel.Actions actions) {
        this.activity = activity;
        this.actions = actions;
        english = AppPrefs.LANG_EN.equals(AppPrefs.lang(activity));

        extrasCard = activity.findViewById(R.id.extras_card);
        extrasTogglesCard = activity.findViewById(R.id.extras_toggles_card);
        wearStatusRow = activity.findViewById(R.id.wear_status_row);
        wearStatusText = activity.findViewById(R.id.wear_status_text);
        btnFindBuds = activity.findViewById(R.id.btn_find_buds);
        badgeFindBuds = activity.findViewById(R.id.badge_find_buds_exp);
        scopeLimitCard = activity.findViewById(R.id.scope_limit_card);
        firmwareInfoText = activity.findViewById(R.id.firmware_info_text);
        scopeLimitText = activity.findViewById(R.id.scope_limit_text);

        fanDeck = activity.findViewById(R.id.fan_deck);
        supPageAcoustic = activity.findViewById(R.id.sup_page_acoustic);
        supPageSpatial = activity.findViewById(R.id.sup_page_spatial);
        supPageExtras = activity.findViewById(R.id.sup_page_extras);
        noiseSubContainer = activity.findViewById(R.id.noise_sub_container);
        equalizerCard = activity.findViewById(R.id.equalizer_card);
        spatialCard = activity.findViewById(R.id.spatial_card);
        localSpatialCard = activity.findViewById(R.id.local_spatial_card);

        bindToggle(FeatureId.LOW_LATENCY, R.id.row_low_latency,
                R.id.switch_low_latency, R.id.badge_low_latency_exp);
        bindToggle(FeatureId.MULTIPOINT, R.id.row_multipoint,
                R.id.switch_multipoint, R.id.badge_multipoint_exp);
        bindToggle(FeatureId.WEAR_DETECTION, R.id.row_wear_detect,
                R.id.switch_wear_detect, R.id.badge_wear_detect_exp);

        if (btnFindBuds != null) {
            btnFindBuds.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    onFindBudsClicked();
                }
            });
        }
    }

    private void bindToggle(FeatureId id, int rowId, int switchId, int badgeId) {
        View row = activity.findViewById(rowId);
        Switch sw = activity.findViewById(switchId);
        View badge = activity.findViewById(badgeId);
        final ToggleBinding binding = new ToggleBinding(id, row, sw, badge);
        toggles.add(binding);
        if (sw != null) {
            sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(CompoundButton button, boolean checked) {
                    if (updating) return;
                    onToggleChanged(binding, checked);
                }
            });
        }
    }

    private void onToggleChanged(final ToggleBinding b, boolean checked) {
        final DeviceState cur = state;
        if (cur == null || !cur.connected || !cur.isFeatureWritable(b.id)
                || actions.pendingFeature(b.id) >= 0) {
            if (cur != null) render(cur, -1);
            return;
        }
        final FeatureDescriptor spec = cur.featureSpec(b.id);
        final long session = cur.session;
        final int target = checked ? 1 : 0;
        render(cur, -1);
        if (spec == null) return;

        Runnable send = new Runnable() {
            @Override public void run() {
                if (state != null && state.connected && state.session == session
                        && state.isFeatureWritable(b.id)) {
                    actions.selectFeature(b.id, target);
                }
            }
        };
        if (checked && spec.verif == Controls.VERIF_UNTESTED) {
            SafetyGates.confirmExperimentalOnce(activity, spec, sessionConfirmed, session, cur, send);
        } else if (checked && spec.safety == FeatureDescriptor.SAFETY_AUDIO_WARN) {
            SafetyGates.confirmAudioRisk(activity, R.string.exp_first_warn_title,
                    R.string.exp_first_warn_msg, session, cur, send);
        } else {
            send.run();
        }
    }

    private void onFindBudsClicked() {
        DeviceState cur = state;
        if (cur == null || !cur.connected) return;
        FeatureDescriptor spec = cur.featureSpec(FeatureId.FIND_MY_BUDS);
        if (spec == null || !cur.isFeatureWritable(FeatureId.FIND_MY_BUDS)) return;
        SafetyGates.startFindBudsFlow(activity, cur, spec, actions);
    }

    private boolean renderToggle(ToggleBinding b, DeviceState cur) {
        FeatureDescriptor spec = cur.connected ? cur.featureSpec(b.id) : null;
        Integer val = cur.featureValue(b.id);
        boolean visible = cur.connected && spec != null && val != null;
        if (b.row != null) b.row.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (b.expBadge != null) {
            b.expBadge.setVisibility(visible && spec.verif == Controls.VERIF_UNTESTED
                    ? View.VISIBLE : View.GONE);
        }
        if (visible && b.toggle != null) {
            int pending = actions.pendingFeature(b.id);
            boolean checked = pending >= 0 ? pending != 0 : val != 0;
            updating = true;
            b.toggle.setChecked(checked);
            b.toggle.setEnabled(cur.isFeatureWritable(b.id) && pending < 0);
            updating = false;
        }
        return visible;
    }

    private boolean renderWearStatus(DeviceState cur) {
        FeatureDescriptor wearSpec = cur.connected ? cur.featureSpec(FeatureId.WEAR_STATUS) : null;
        Integer wearVal = cur.featureValue(FeatureId.WEAR_STATUS);
        boolean visible = cur.connected && (wearSpec != null || wearVal != null
                || cur.wearStatus != DeviceState.WEAR_UNKNOWN);
        if (wearStatusRow != null) wearStatusRow.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible && wearStatusText != null) {
            int textRes;
            switch (cur.wearStatus) {
                case DeviceState.WEAR_BOTH: textRes = R.string.wear_status_both; break;
                case DeviceState.WEAR_LEFT: textRes = R.string.wear_status_left; break;
                case DeviceState.WEAR_RIGHT: textRes = R.string.wear_status_right; break;
                case DeviceState.WEAR_NONE: textRes = R.string.wear_status_none; break;
                default: textRes = R.string.wear_status_unknown; break;
            }
            wearStatusText.setText(textRes);
        }
        return visible;
    }

    private boolean renderFindBuds(DeviceState cur) {
        FeatureDescriptor spec = cur.connected ? cur.featureSpec(FeatureId.FIND_MY_BUDS) : null;
        boolean visible = cur.connected && spec != null && cur.isFeatureWritable(FeatureId.FIND_MY_BUDS);
        if (btnFindBuds != null) {
            btnFindBuds.setVisibility(visible ? View.VISIBLE : View.GONE);
            btnFindBuds.setEnabled(visible);
            btnFindBuds.setAlpha(cur.isAnyBudInEar() ? 0.55f : 1f);
        }
        if (badgeFindBuds != null) {
            badgeFindBuds.setVisibility(visible && spec.verif == Controls.VERIF_UNTESTED
                    ? View.VISIBLE : View.GONE);
        }
        SafetyGates.syncFindBudsRinging(activity, cur, actions);
        return visible;
    }

    private boolean renderScopeAndFirmware(DeviceState cur) {
        if (!cur.connected) {
            if (firmwareInfoText != null) firmwareInfoText.setVisibility(View.GONE);
            if (scopeLimitText != null) scopeLimitText.setVisibility(View.GONE);
            if (scopeLimitCard != null) scopeLimitCard.setVisibility(View.GONE);
            return false;
        }

        String fw = cur.featureText(FeatureId.FIRMWARE_INFO);
        if (fw.isEmpty()) fw = cur.firmware;
        boolean showFw = !fw.isEmpty() && firmwareInfoText != null;
        if (firmwareInfoText != null) {
            if (showFw) {
                firmwareInfoText.setText(activity.getString(R.string.firmware_label) + ": " + fw);
                firmwareInfoText.setVisibility(View.VISIBLE);
            } else {
                firmwareInfoText.setVisibility(View.GONE);
            }
        }

        String scopeNote = buildScopeNote(cur);
        boolean showScope = !scopeNote.isEmpty() && scopeLimitText != null;
        if (scopeLimitText != null) {
            if (showScope) {
                scopeLimitText.setText(scopeNote);
                scopeLimitText.setVisibility(View.VISIBLE);
            } else {
                scopeLimitText.setVisibility(View.GONE);
            }
        }

        boolean visible = showFw || showScope;
        if (scopeLimitCard != null) scopeLimitCard.setVisibility(visible ? View.VISIBLE : View.GONE);
        return visible;
    }

    private String buildScopeNote(DeviceState cur) {
        if ("standard".equals(cur.limitation)) {
            return activity.getString(R.string.scope_standard_limit);
        }
        Set<String> limits = new LinkedHashSet<>();
        boolean partial = !cur.limitation.isEmpty() || ((cur.potentialCaps & ~cur.caps) != 0);
        for (FeatureDescriptor spec : cur.features.values()) {
            if (spec == null) continue;
            if (spec.verif == Controls.VERIF_UNTESTED || spec.devOnly) partial = true;
            String lim = spec.limit(english);
            if (!lim.isEmpty()) {
                limits.add(lim);
                partial = true;
            }
        }
        if (!partial && limits.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(activity.getString(R.string.scope_partial_limit));
        for (String lim : limits) {
            sb.append('\n').append(lim);
        }
        return sb.toString();
    }

    private static boolean isVisible(View view) {
        return view != null && view.getVisibility() == View.VISIBLE;
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

        boolean togglesVisible = renderWearStatus(state);
        for (ToggleBinding b : toggles) {
            if (renderToggle(b, state)) togglesVisible = true;
        }
        if (renderFindBuds(state)) togglesVisible = true;
        if (extrasTogglesCard != null) {
            extrasTogglesCard.setVisibility(togglesVisible ? View.VISIBLE : View.GONE);
        }
        boolean scopeVisible = renderScopeAndFirmware(state);
        boolean anyVisible = togglesVisible || scopeVisible;

        if (extrasCard != null) extrasCard.setVisibility(anyVisible ? View.VISIBLE : View.GONE);
        if (fanDeck != null) {
            boolean acousticVisible = isVisible(noiseSubContainer)
                    || isVisible(equalizerCard);
            boolean spatialVisible = isVisible(spatialCard)
                    || isVisible(localSpatialCard);
            if (supPageAcoustic != null) fanDeck.setPageEnabled(supPageAcoustic, acousticVisible);
            if (supPageSpatial != null) fanDeck.setPageEnabled(supPageSpatial, spatialVisible);
            if (supPageExtras != null) fanDeck.setPageEnabled(supPageExtras, anyVisible);
        }
    }

    @Override public void pause() {
        SafetyGates.dismissAll(activity);
    }
}
