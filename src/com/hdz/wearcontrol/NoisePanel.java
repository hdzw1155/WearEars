package com.hdz.wearcontrol;

import android.app.Activity;
import android.view.View;

/** 能力未知时保留卡片并禁用写入，收到当前会话确认后才启用。 */
final class NoisePanel implements MainPanel {
    private final Activity activity;
    private final View card;
    private final NoiseSegmentedView segmented;
    private final SuprematistDialView dial;

    public NoisePanel(Activity activity, final MainPanel.Actions actions) {
        this.activity = activity;
        card = activity.findViewById(R.id.noise_card);
        segmented = activity.findViewById(R.id.noise_seg);
        dial = activity.findViewById(R.id.sup_dial);
        if (dial != null) dial.enablePanel(TwsClient.CAP_NOISE);
        int uiStyle = AppPrefs.uiStyle(activity);
        if (segmented != null && AppPrefs.isNeo(uiStyle)) NeoStyle.styleNoise(segmented, uiStyle);
        if (segmented != null) segmented.setOnModeChangeListener(new NoiseSegmentedView.OnModeChangeListener() {
            @Override public void onModeSelected(int mode) { actions.selectNoise(mode); }
        });
    }

    @Override public void render(DeviceState state, int pendingMode) {
        int capability = state.capability(TwsClient.CAP_NOISE);
        boolean enabled = capability == DeviceState.SUPPORTED;
        if (card != null) card.setVisibility(capability == DeviceState.UNSUPPORTED ? View.GONE : View.VISIBLE);
        int mode = state.connected ? (pendingMode >= 0 ? pendingMode : state.mode) : -1;
        if (segmented != null) {
            segmented.setEnabled(true);
            segmented.setAlpha(enabled ? 1f : 0.65f);
            segmented.setMode(mode, true);
            segmented.setContentDescription(activity.getString(capability == DeviceState.UNKNOWN
                    ? R.string.noise_waiting : R.string.noise_title));
        }
        if (dial != null) dial.setNoiseState(mode, enabled);
    }
}
