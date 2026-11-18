package com.hdz.wearcontrol;

import android.content.Context;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** 执行真实 View 手势和控制器；Android 系统边界由 test-interactions.py 提供可控替身。 */
public final class InteractionChecks {
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    private static void touch(android.view.View bar, int action, float x, float y) {
        bar.onTouchEvent(new MotionEvent(action, x, y));
    }
    private static void tapLock(VolumeBarView bar) { touch(bar, 0, 430, 36); touch(bar, 1, 430, 36); }
    private static void drag(VolumeBarView bar, float to) {
        touch(bar, 0, 12, 36); touch(bar, 2, to, 36); touch(bar, 1, to, 36);
    }
    private static void volume() {
        for (int pass = 0; pass < 6; pass++) {
            final int style = pass % 3;
            final boolean neo = pass >= 3;
            // 新系列轨道右侧让出锁钮, 轨道末端更靠左; 取轨道内一点验证"从锁区起拖不算点锁"
            final float inTrack = neo ? 300 : 380;
            final VolumeGuard guard = new VolumeGuard();
            final VolumeBarView bar = new VolumeBarView(new Context());
            final int[] current = {0};
            final int[] hints = {0};
            bar.setVolumeGuard(guard); bar.setStyle(style); bar.setNeo(neo); bar.setLevel(0, 15);
            bar.setOnLevelChangeListener(new VolumeBarView.OnLevelChangeListener() {
                @Override public void onLockedIncrease() { hints[0]++; }
                @Override public void onLevelChanged(int target, int max) {
                    current[0] = guard.limit(current[0], target, 15);
                    bar.setLevel(current[0], 15);
                }
            });
            drag(bar, 170); check(current[0] == 0, "default lock must still protect 0");
            check(hints[0] == 1, "one explanation per blocked increase, never one per MOVE");
            tapLock(bar); check(guard.isUnlocked() && current[0] == 0, "lock button unlocks without changing zero");
            drag(bar, 170); check(current[0] > 0 && current[0] < 10, "real drag must raise volume from zero");
            drag(bar, 448); check(current[0] == 10 && !guard.isUnlocked(), "real gesture stops at 70 percent");
            tapLock(bar); check(current[0] == 10, "threshold confirmation does not jump");
            touch(bar, 0, 365, 36); touch(bar, 2, 448, 36); touch(bar, 1, 448, 36);
            check(current[0] == 15 && guard.isUnlocked(), "drag across lock icon reaches maximum without relocking");
            touch(bar, 0, 438, 36); touch(bar, 2, inTrack, 36); touch(bar, 1, inTrack, 36);
            check(current[0] < 15 && guard.isUnlocked(), "drag starting at right edge is not a lock tap");
            tapLock(bar); drag(bar, 40); check(current[0] < 10 && !guard.isUnlocked(), "locked drag still lowers");
            touch(bar, 0, 100, 36); Handler.advance(500); int before = current[0];
            touch(bar, 2, 180, 36); touch(bar, 1, 180, 36);
            check(current[0] == before && guard.isUnlocked(), "long press gesture only confirms");
            drag(bar, 180); check(current[0] > before, "drag after long press increases");
            guard.lock(); touch(bar, 0, 100, 36); touch(bar, 2, 100, 70); Handler.advance(500);
            check(!guard.isUnlocked(), "vertical scroll cancels hold");
            touch(bar, 3, 100, 70);
            touch(bar, 0, 430, 36); touch(bar, 2, 300, 36); touch(bar, 1, 300, 36);
            check(!guard.isUnlocked(), "drag across lock is not a confirmation");
            touch(bar, 0, 430, 36); touch(bar, 1, 250, 36);
            check(!guard.isUnlocked(), "release far from lock is not a confirmation even without a MOVE sample");
            current[0] = 0; bar.setLevel(0, 15); tapLock(bar);
            touch(bar, 0, 12, 36); touch(bar, 1, 145, 36);
            check(current[0] > 0 && current[0] < 10, "fast swipe from zero includes the release coordinate");
            touch(bar, 0, 12, 36); touch(bar, 2, 30, 36);
            int penultimate = current[0]; touch(bar, 1, 145, 36);
            check(current[0] > penultimate, "release after the last MOVE sets the final level");
            int beforeScroll = current[0];
            touch(bar, 0, 220, 20); touch(bar, 2, 222, 65); touch(bar, 2, 222, 95); touch(bar, 1, 222, 95);
            check(current[0] == beforeScroll, "vertical scrolling through an unlocked slider cannot change volume");
            touch(bar, 0, 12, 36); touch(bar, 3, 250, 36); touch(bar, 2, 270, 36);
            check(current[0] == beforeScroll, "cancelled gesture cannot write at release or on an orphan MOVE");
            touch(bar, 0, 12, 36); touch(bar, 5, 12, 36); touch(bar, 1, 290, 36);
            check(current[0] == beforeScroll, "second finger cancels without jumping to a different pointer");
            Handler.reset();
        }
    }

    private static void volumePanel() {
        android.app.Activity watch = new android.app.Activity();
        VolumeBarView bar = new VolumeBarView(watch);
        watch.views.put(R.id.volume_bar, bar);
        VolumePanel panel = new VolumePanel(watch, null);
        panel.resume(); tapLock(bar); touch(bar, 0, 12, 36); touch(bar, 1, 145, 36);
        check(watch.music.current > 0 && watch.music.current == bar.getLevel(), "actual panel writes the music stream from zero");
        int accepted = watch.music.current;
        watch.music.fixed = true; touch(bar, 0, 145, 36); touch(bar, 1, 240, 36);
        check(watch.music.current == accepted && bar.getLevel() == accepted, "do not display a level the system refused");
        watch.music.fixed = false; panel.pause(); panel.resume();
        touch(bar, 0, 145, 36); touch(bar, 1, 240, 36);
        check(watch.music.current == accepted, "resuming the panel relocks increases");
        panel.pause(); Handler.reset();
    }

    private static void dial() {
        android.app.Activity watch = new android.app.Activity();
        SuprematistDialView dial = new SuprematistDialView(watch);
        watch.views.put(R.id.sup_dial, dial);
        final VolumePanel panel = new VolumePanel(watch, null);
        final int[] hints = {0};
        dial.setOnHeroActionListener(new SuprematistDialView.OnHeroActionListener() {
            public void onRefreshRequested(){} public void onReconnectRequested(){} public void onNoiseModeSelected(int m){}
            public void onVolumeGuardHint(boolean unlocked){if (!unlocked) hints[0]++;}
            public void onVolumeChanged(int level, int max){panel.volume(level,max);}
        });
        panel.resume(); dial.layout(0,0,450,450); dial.onDraw(new android.graphics.Canvas());
        touch(dial,0,146,340); touch(dial,1,210,340);
        check(watch.music.current==0 && hints[0]==1,"Suprematist explains a locked increase without writing");
        touch(dial,0,110,340); touch(dial,1,110,340);
        check(watch.music.current==0,"Suprematist badge only confirms");
        touch(dial,0,146,340); touch(dial,1,210,340);
        check(watch.music.current==6,"Suprematist fast swipe raises music from zero");
        touch(dial,0,210,340); touch(dial,2,220,340); touch(dial,1,238,340);
        check(watch.music.current==8,"Suprematist final sample is not lost");
        touch(dial,0,238,340); touch(dial,2,310,340); touch(dial,1,310,340);
        check(watch.music.current==10,"Suprematist gesture stops at the safe system step");
        touch(dial,0,110,340); touch(dial,1,110,340); touch(dial,0,260,340); touch(dial,1,318,340);
        check(watch.music.current==15,"Suprematist requires a separate confirmation above the boundary");
        touch(dial,0,240,340); touch(dial,5,240,340); touch(dial,1,146,340);
        check(watch.music.current==15,"Suprematist cancels multi-touch");
        panel.pause(); panel.resume(); touch(dial,0,305,340); touch(dial,1,180,340);
        check(watch.music.current==3,"Suprematist permits a locked decrease on release");
        panel.pause(); Handler.reset();
    }

    private static void spatialSwitch() {
        Handler.reset();
        Activity watch = new Activity();
        View card = new View(watch, null);
        Switch toggle = new Switch();
        TextView hint = new TextView();
        watch.views.put(R.id.spatial_card, card);
        watch.views.put(R.id.switch_spatial, toggle);
        watch.views.put(R.id.spatial_hint, hint);
        final java.util.List<Boolean> requests = new java.util.ArrayList<Boolean>();
        EffectsPanel panel = new EffectsPanel(watch, new MainPanel.Actions() {
            @Override public void selectSpatial(boolean enabled) { requests.add(enabled); }
            @Override public void selectNoise(int mode) { throw new AssertionError("spatial sent ANC"); }
            @Override public void selectEqualizer(int preset) { throw new AssertionError("spatial sent EQ"); }
        });
        DeviceState state = new DeviceState();
        state.begin("A", "Air3 Pro", TwsClient.CAP_BATTERY, TwsClient.CAP_SPATIAL);
        state.connected = true;
        panel.render(state, -1);
        check(card.getVisibility() == View.VISIBLE && !toggle.isEnabled()
                && watch.getString(R.string.spatial_state_unknown).contentEquals(toggle.getStateDescription()),
                "no report means unknown and cannot write");
        state.capabilities(TwsClient.CAP_SPATIAL, TwsClient.CAP_SPATIAL);
        state.spatial = false;
        state.spatialRevision++;
        panel.render(state, -1);
        check(toggle.isEnabled() && !toggle.isChecked()
                && watch.getString(R.string.spatial_scenes_on_phone).contentEquals(hint.getText()),
                "confirmed off is interactive and scenes have a phone hint");
        toggle.performClick();
        check(AlertDialog.last.isShowing() && requests.isEmpty() && !toggle.isChecked(),
                "first enable waits for the safety dialog");
        AlertDialog.last.confirm();
        check(requests.equals(java.util.Arrays.asList(true)) && !toggle.isEnabled() && !toggle.isChecked()
                && watch.getString(R.string.spatial_waiting).contentEquals(toggle.getStateDescription()),
                "pending enable keeps the confirmed switch and describes waiting");
        toggle.performClick();
        state.spatialRevision++;
        panel.render(state, -1);
        check(requests.size() == 1 && !toggle.isEnabled(), "repeat taps and unmatched reports cannot finish a request");
        Toast.lastMessage = 0;
        Handler.advance(7000);
        check(toggle.isEnabled() && !toggle.isChecked() && Toast.lastMessage == R.string.spatial_unconfirmed,
                "timeout restores confirmed controls and gives specific feedback");
        toggle.performClick();
        state.spatial = true;
        state.spatialRevision++;
        panel.render(state, -1);
        check(requests.equals(java.util.Arrays.asList(true, true)) && toggle.isEnabled() && toggle.isChecked()
                && toggle.getStateDescription() == null, "matching report confirms the requested state");
        Toast.lastMessage = 0;
        Handler.advance(7000);
        check(Toast.lastMessage == 0, "confirmation cancels the old timeout");
        toggle.performClick();
        check(requests.equals(java.util.Arrays.asList(true, true, false)) && toggle.isChecked(),
                "pending disable also retains the confirmed value");
        state.disconnect();
        panel.render(state, -1);
        Handler.advance(7000);
        check(!toggle.isEnabled() && watch.getString(R.string.spatial_state_unknown).contentEquals(hint.getText())
                && Toast.lastMessage == 0, "disconnect clears waiting without a stale timeout");
        state.begin("B", "Other buds", TwsClient.CAP_BATTERY, TwsClient.CAP_BATTERY);
        state.connected = true;
        panel.render(state, -1);
        check(card.getVisibility() == View.GONE && !toggle.isEnabled(), "another brand cannot inherit spatial controls");
        panel.pause();
        Handler.reset();
    }

    private static final class Observer implements TwsClient.Listener, HeadsetController.UiListener {
        boolean active; int successes, failures;
        public void onConnected(String n){} public void onDisconnected(String r){} public void onError(String e){}
        public void onBattery(int l,int r,int b,int c){} public void onNoiseMode(int m){}
        public void onSpatial(Boolean s){} public void onDeepX(Integer m){}
        public void onConnectionState(int s,int h){}
        public void onOperationChanged(int t,boolean a,boolean success) {
            active=a; if (!a) { if(success)successes++;else failures++; }
        }
    }
    private static void waitQueries(FakeDriver d) throws Exception {
        for(int i=0;i<100&&d.queries<2;i++){Thread.sleep(5);Handler.drain();}
        check(d.queries >= 2, "queries must reach the new driver");
    }
    private static void controller() throws Exception {
        HeadsetController c = HeadsetController.get(new Context());
        Observer o = new Observer(); c.addListener(o);
        FakeDriver timedOut = new FakeDriver(); timedOut.autoConnect=false;
        DriverRegistry.next.add(timedOut); c.connect(null); Handler.drain();
        TwsClient.Listener late = timedOut.listeners.get(0);
        Handler.advance(12000); check(!c.isConnected(), "initial timeout drops session");
        FakeDriver recovered = new FakeDriver(); DriverRegistry.next.add(recovered);
        c.perform(-1, null); Handler.drain(); waitQueries(recovered);
        late.onConnected("old"); late.onBattery(99,99,99,7); Handler.drain();
        check(c.state().left == -1, "late timed-out session cannot populate new UI");
        recovered.battery(); Handler.drain();
        check(o.active && c.state().left == 50, "battery does not end refresh before ANC");
        recovered.listeners.get(0).onNoiseMode(-1); Handler.drain();
        check(o.active && c.state().mode == -1, "an unknown ANC report cannot complete refresh");
        int partialCount = DriverRegistry.created;
        Handler.advance(12000);
        check(o.active && recovered.physical && c.state().left == 50 && partialCount == DriverRegistry.created,
                "one missing report cannot discard a live battery session");
        recovered.noise(); Handler.drain();
        check(!o.active && o.successes==1 && c.state().capability(TwsClient.CAP_NOISE)==DeviceState.SUPPORTED,
                "manual refresh after timeout restores battery and ANC");
        int count = DriverRegistry.created;
        c.perform(-1,null); Handler.drain();
        FakeDriver rebuilt = new FakeDriver(); DriverRegistry.next.add(rebuilt);
        Handler.advance(12000); Handler.drain(); waitQueries(rebuilt);
        check(DriverRegistry.created==count+1 && !recovered.physical, "silent connected socket rebuilt once");
        rebuilt.noise(); rebuilt.battery(); Handler.drain();
        check(o.successes==2 && !o.active, "recovery accepts reverse response order");
        c.perform(-1,null); Handler.drain();
        FakeDriver silent = new FakeDriver(); DriverRegistry.next.add(silent);
        Handler.advance(12000); Handler.drain(); waitQueries(silent); Handler.advance(12000);
        check(!o.active && o.failures==1 && DriverRegistry.next.isEmpty(), "recovery has a finite budget");
        c.perform(-1,null); Handler.drain(); silent.battery(); Handler.drain();
        Handler.advance(12000); Handler.advance(12000);
        check(!o.active && o.failures==2 && silent.physical && c.state().left==50,
                "partial timeout retains confirmed values without reconnecting");
        c.deviceChanged(); TileStore.cachedCaps=TwsClient.CAP_NOISE;
        FakeDriver standard=new StandardClient(); DriverRegistry.next.add(standard);
        c.perform(-1,null); Handler.drain(); waitQueries(standard); standard.battery(); Handler.drain();
        check(!o.active && o.successes==3 && (c.state().potentialCaps & TwsClient.CAP_NOISE)!=0,
                "historical ANC preserves layout but cannot block a battery-only refresh");
        c.removeListener(o); c.disconnect(); Handler.reset();
    }
    public static void main(String[] args) {
        try {volume();volumePanel();dial();spatialSwitch();controller();System.out.println("PASS: "+checks+" interaction/session checks");System.exit(0);}
        catch(Throwable e){e.printStackTrace();System.exit(1);}
    }
}
