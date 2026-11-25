package com.hdz.wearcontrol;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Build;
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
    private final View card;
    private final SuprematistDialView dial;
    private boolean registered;
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
        card = activity.findViewById(R.id.volume_card);
        dial = activity.findViewById(R.id.sup_dial);
        if (dial != null) {
            dial.setVolumeGuard(guard);
            dial.setVolumeEnabled(AppPrefs.showVolume(activity));
        }
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
                @Override public void onHighGuardConfirmNeeded() {
                    Toast.makeText(activity, R.string.volume_high_guard, Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    @Override public void render(DeviceState state, int pendingMode) { }
    @Override public void resume() {
        guard.lock();
        boolean show = AppPrefs.showVolume(activity);
        if (card != null) card.setVisibility(show ? View.VISIBLE : View.GONE);
        if (dial != null) dial.setVolumeEnabled(show);
        refreshVolume();
        if (!registered) {
            try {
                IntentFilter filter = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
                if (Build.VERSION.SDK_INT >= 33) {
                    activity.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                    activity.registerReceiver(receiver, filter);
                }
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
        if (audio == null || !AppPrefs.hasDisclaimer(activity)) return;
        boolean wasUnlocked = guard.isUnlocked();
        boolean hitHighGuard;
        try {
            int current = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            int safe = guard.limit(current, level, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            if (safe != current) audio.setStreamVolume(AudioManager.STREAM_MUSIC, safe, 0);
            hitHighGuard = wasUnlocked && guard.needsHighConfirmation();
        }
        catch (RuntimeException ignored) { refreshVolume(); return; }
        refreshVolume();
        if (hitHighGuard) {
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
            guard.observe(current, max);
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
