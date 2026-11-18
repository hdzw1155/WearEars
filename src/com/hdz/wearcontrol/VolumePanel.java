package com.hdz.wearcontrol;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** 音量独立于耳机协议，拥有自己的控件绑定和系统监听生命周期。 */
final class VolumePanel implements MainPanel {
    private final Activity activity;
    private final AudioManager audio;
    private final VolumeBarView bar;
    private final TextView value;
    private final Switch toggle;
    private final View card;
    private final SuprematistDialView dial;
    private boolean registered, changing;
    private long lastHaptic;
    private final VolumeGuard guard = new VolumeGuard();
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refreshVolume(); }
    };

    public VolumePanel(Activity activity, MainPanel.Actions actions) {
        this.activity = activity;
        audio = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
        bar = activity.findViewById(R.id.volume_bar);
        value = activity.findViewById(R.id.volume_value);
        toggle = activity.findViewById(R.id.switch_volume);
        card = activity.findViewById(R.id.volume_card);
        dial = activity.findViewById(R.id.sup_dial);
        if (dial != null) { dial.enablePanel(0); dial.setVolumeGuard(guard); }
        if (bar != null) {
            bar.setVolumeGuard(guard);
            int uiStyle = AppPrefs.uiStyle(activity);
            bar.setStyle(AppPrefs.family(uiStyle));
            if (AppPrefs.isNeo(uiStyle)) {
                bar.setLockColor(NeoStyle.lockColor(uiStyle));
                bar.setNeoColors(NeoStyle.volumeFill(uiStyle), NeoStyle.volumeTrack(uiStyle));
                bar.setNeo(true);
            }
            bar.setOnLevelChangeListener(new VolumeBarView.OnLevelChangeListener() {
                @Override public void onLevelChanged(int level, int max) { volume(level, max); }
                @Override public void onLockChanged() { refreshVolume(); }
                @Override public void onLockedIncrease() {
                    Toast.makeText(activity, R.string.volume_locked, Toast.LENGTH_SHORT).show();
                }
            });
        }
        if (toggle != null) toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton button, boolean checked) {
                if (!changing) AppPrefs.setShowVolume(VolumePanel.this.activity, checked);
                if (card != null) card.setVisibility(checked ? View.VISIBLE : View.GONE);
            }
        });
    }

    @Override public void render(DeviceState state, int pendingMode) { }
    @Override public void resume() {
        guard.lock();
        boolean show = AppPrefs.showVolume(activity);
        if (card != null) card.setVisibility(show ? View.VISIBLE : View.GONE);
        if (toggle != null) { changing = true; toggle.setChecked(show); changing = false; }
        refreshVolume();
        if (!registered) {
            try {
                activity.registerReceiver(receiver, new IntentFilter("android.media.VOLUME_CHANGED_ACTION"));
                registered = true;
            } catch (RuntimeException ignored) { }
        }
    }

    @Override public void pause() {
        guard.lock();
        if (bar != null) bar.cancelInteraction();
        if (dial != null) dial.cancelVolumeInteraction();
        if (registered) { activity.unregisterReceiver(receiver); registered = false; }
    }

    @Override public void volume(int level, int max) {
        if (audio == null) return;
        boolean wasUnlocked = guard.isUnlocked();
        try {
            int current = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            int safe = guard.limit(current, level, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            if (safe != current) audio.setStreamVolume(AudioManager.STREAM_MUSIC, safe, 0);
        }
        catch (RuntimeException ignored) { refreshVolume(); return; }
        refreshVolume();
        if (wasUnlocked && guard.needsHighConfirmation()) {
            Toast.makeText(activity, R.string.volume_high_guard, Toast.LENGTH_LONG).show();
            haptic(HapticFeedbackConstants.REJECT);
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastHaptic > 80L) {
            lastHaptic = now;
            haptic(HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    @Override public void refreshVolume() {
        if (audio == null) return;
        try {
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int current = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (bar != null) { bar.setLevel(current, max); bar.invalidate(); }
            if (dial != null) { dial.setVolume(current, max); dial.invalidate(); }
            if (value != null) value.setText((max > 0 ? Math.round(current * 100f / max) : 0) + "%");
        } catch (RuntimeException ignored) { }
    }

    private void haptic(int kind) {
        try { activity.getWindow().getDecorView().performHapticFeedback(kind); }
        catch (RuntimeException ignored) { }
    }
}
