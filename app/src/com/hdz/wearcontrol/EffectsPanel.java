package com.hdz.wearcontrol;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** 空间音频与内置音效面板。选择目标与已确认状态分开，超时恢复已确认值。 */
final class EffectsPanel implements MainPanel {
    private final Activity activity;
    private final Actions actions;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final View spatialCard, equalizerCard;
    private final Switch spatial;
    private final TextView current, title;
    private final TextView spatialHint;
    private final SnapChipRow scroller;
    private final TextView[] choices;
    private final boolean supreme, english;
    /** 新系列风格值; 旧风格为 -1 */
    private final int neoStyle;
    private boolean updating;
    private AlertDialog spatialDialog;
    private DeviceState state;
    private Boolean pendingSpatial;
    private Integer pendingEqualizer;
    private int spatialRevision, equalizerRevision;
    private int spatialHintText;
    private int selection = -1;
    private final Runnable spatialTimeout = new Runnable() {
        @Override public void run() {
            pendingSpatial = null;
            renderSpatial();
            Toast.makeText(activity, R.string.spatial_unconfirmed, Toast.LENGTH_SHORT).show();
        }
    };
    private final Runnable equalizerTimeout = new Runnable() {
        @Override public void run() { pendingEqualizer = null; unconfirmed(); }
    };

    public EffectsPanel(Activity activity, Actions actions) {
        this.activity = activity;
        this.actions = actions;
        supreme = AppPrefs.uiStyle(activity) == AppPrefs.STYLE_SUPREME;
        int uiStyle = AppPrefs.uiStyle(activity);
        neoStyle = AppPrefs.isNeo(uiStyle) ? uiStyle : -1;
        english = AppPrefs.LANG_EN.equals(AppPrefs.lang(activity));
        spatialCard = activity.findViewById(R.id.spatial_card);
        equalizerCard = activity.findViewById(R.id.equalizer_card);
        spatial = activity.findViewById(R.id.switch_spatial);
        current = activity.findViewById(R.id.deepx_current);
        title = activity.findViewById(R.id.equalizer_title);
        spatialHint = activity.findViewById(R.id.spatial_hint);
        scroller = activity.findViewById(R.id.deepx_scroller);
        choices = new TextView[]{activity.findViewById(R.id.deepx_0), activity.findViewById(R.id.deepx_1),
                activity.findViewById(R.id.deepx_2), activity.findViewById(R.id.deepx_3), activity.findViewById(R.id.deepx_4)};
        for (int i = 0; i < choices.length; i++) {
            final int index = i;
            if (choices[i] != null) choices[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    if (state == null || !state.connected || index >= state.presets.length) return;
                    pendingEqualizer = state.presets[index].id;
                    equalizerRevision = state.equalizerRevision;
                    ui.removeCallbacks(equalizerTimeout);
                    ui.postDelayed(equalizerTimeout, 7000L);
                    actions.selectEqualizer(pendingEqualizer);
                    render(state, -1);
                }
            });
        }
        if (spatial != null) spatial.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton button, final boolean checked) {
                if (updating || !spatialAvailable() || pendingSpatial != null) return;
                if (checked) {
                    final long session = state.session;
                    renderSpatial();
                    if (spatialDialog != null && spatialDialog.isShowing()) return;
                    spatialDialog = new AlertDialog.Builder(EffectsPanel.this.activity).setTitle(R.string.spatial_dialog_title)
                            .setMessage(R.string.spatial_dialog_msg)
                            .setPositiveButton(R.string.dialog_ok, new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface dialog, int which) {
                                    if (state != null && session == state.session && AppPrefs.hasDisclaimer(activity)) requestSpatial(true);
                                }
                            }).setNegativeButton(R.string.dialog_cancel, null).show();
                } else requestSpatial(checked);
            }
        });
    }

    private boolean spatialAvailable() {
        return state != null && state.spatial != null
                && state.capability(TwsClient.CAP_SPATIAL) == DeviceState.SUPPORTED;
    }

    private void requestSpatial(boolean enabled) {
        if (!spatialAvailable() || pendingSpatial != null || state.spatial == enabled) return;
        pendingSpatial = enabled;
        spatialRevision = state.spatialRevision;
        ui.postDelayed(spatialTimeout, 7000L);
        renderSpatial();
        actions.selectSpatial(enabled);
    }

    private void renderSpatial() {
        boolean available = spatialAvailable();
        if (pendingSpatial != null && (!available
                || state.spatialRevision > spatialRevision && pendingSpatial.equals(state.spatial))) {
            pendingSpatial = null;
            ui.removeCallbacks(spatialTimeout);
        }
        int hint = pendingSpatial != null ? R.string.spatial_waiting
                : available ? R.string.spatial_scenes_on_phone : R.string.spatial_state_unknown;
        if (spatialCard != null) spatialCard.setVisibility((state.potentialCaps & TwsClient.CAP_SPATIAL) != 0 ? View.VISIBLE : View.GONE);
        if (spatial != null) {
            updating = true;
            spatial.setChecked(available && state.spatial);
            spatial.setEnabled(available && pendingSpatial == null);
            spatial.setStateDescription(available && pendingSpatial == null ? null : activity.getString(hint));
            updating = false;
        }
        if (spatialHint != null && hint != spatialHintText) {
            spatialHint.setText(hint);
            spatialHintText = hint;
        }
    }

    @Override public void render(DeviceState state, int pendingMode) {
        this.state = state;
        if (!state.connected) pause();
        renderSpatial();
        if (pendingEqualizer != null && state.equalizerRevision > equalizerRevision && pendingEqualizer.equals(state.equalizer)) {
            pendingEqualizer = null; ui.removeCallbacks(equalizerTimeout);
        }
        if (equalizerCard != null) equalizerCard.setVisibility((state.potentialCaps & (TwsClient.CAP_EQUALIZER | TwsClient.CAP_DEEPX)) != 0 ? View.VISIBLE : View.GONE);
        boolean eqEnabled = state.connected && (state.caps & (TwsClient.CAP_EQUALIZER | TwsClient.CAP_DEEPX)) != 0;
        if (title != null) title.setText((state.potentialCaps & TwsClient.CAP_DEEPX) != 0
                ? R.string.deepx_label : R.string.equalizer_label);
        String name = "--";
        int selectedIndex = -1;
        for (int i = 0; i < choices.length; i++) {
            TextView choice = choices[i];
            if (choice == null) continue;
            boolean available = i < state.presets.length;
            choice.setVisibility(available ? View.VISIBLE : View.GONE);
            if (!available) continue;
            SoundPreset preset = state.presets[i];
            String label = english ? preset.english : preset.chinese;
            boolean selected = state.equalizer != null && state.equalizer == preset.id;
            choice.setText(label);
            choice.setEnabled(eqEnabled && pendingEqualizer == null);
            choice.setAlpha(eqEnabled ? 1f : 0.45f);
            if (neoStyle >= 0) {
                choice.setBackgroundResource(NeoStyle.chipBg(neoStyle, selected));
                choice.setTextColor(NeoStyle.chipText(neoStyle, selected));
            } else {
                choice.setBackgroundResource(supreme ? (selected ? R.drawable.bg_sup_chip_sel : R.drawable.bg_sup_chip)
                        : (selected ? R.drawable.bg_chip_sel : R.drawable.bg_chip));
                choice.setTextColor(selected ? Color.WHITE : supreme ? 0xFF5E646E : 0x99F2F2F5);
            }
            if (selected) { name = label; selectedIndex = i; }
        }
        if (scroller != null && selectedIndex != selection) scroller.select(selectedIndex, true);
        selection = selectedIndex;
        if (current != null) current.setText(pendingEqualizer != null ? activity.getString(R.string.sub_deepx_pending) : name);
    }

    @Override public void pause() {
        if (spatialDialog != null) { spatialDialog.dismiss(); spatialDialog = null; }
        ui.removeCallbacksAndMessages(null);
        pendingSpatial = null;
        pendingEqualizer = null;
    }

    private void unconfirmed() {
        Toast.makeText(activity, R.string.sub_unconfirmed, Toast.LENGTH_SHORT).show();
        if (state != null) render(state, -1);
    }
}
