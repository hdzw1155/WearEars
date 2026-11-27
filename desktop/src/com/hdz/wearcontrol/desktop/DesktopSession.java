package com.hdz.wearcontrol.desktop;

import com.hdz.wearcontrol.Controls;
import com.hdz.wearcontrol.DesktopCoreBridge;
import com.hdz.wearcontrol.DesktopCoreBridge.BrandModuleEntry;
import com.hdz.wearcontrol.DesktopCoreBridge.Link;
import com.hdz.wearcontrol.DesktopCoreBridge.LinkEvents;
import com.hdz.wearcontrol.DesktopCoreBridge.Sockets;
import com.hdz.wearcontrol.DesktopCoreBridge.StateHolder;
import com.hdz.wearcontrol.DesktopCoreBridge.StateSnapshotView;
import com.hdz.wearcontrol.FeatureDescriptor;
import com.hdz.wearcontrol.FeatureId;
import com.hdz.wearcontrol.LocalMeshProtocol;
import com.hdz.wearcontrol.WireDriver;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Single-threaded desktop protocol session implementing WireDriver.Port and bridging
 * core/'s RfcommLink + all 9 brand WireDrivers on Windows.
 */
public final class DesktopSession {
    public enum ConnectionPhase {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }

    public interface SessionListener {
        default void onPhaseChanged(ConnectionPhase phase, String detail) { }
        default void onStateChanged(StateSnapshotView state) { }
        default void onPhaseChanged(int connection, ConnectionPhase phase, String detail) {
            onPhaseChanged(phase, detail);
        }
        default void onStateChanged(int connection, StateSnapshotView state) {
            onStateChanged(state);
        }
        default void onError(String message) { }
        default void onTrace(String tag, String message) { }
    }

    private static final long POLL_INTERVAL_MS = 30_000L;
    private static final long INITIAL_REFRESH_MS = 1_200L;
    private static final long CONNECT_TIMEOUT_MS = 25_000L;

    private final DesktopDriverRegistry registry;
    private final WindowsBluetoothBridge bluetoothBridge;
    private final ScheduledThreadPoolExecutor actor;
    private final StateHolder stateHolder = new StateHolder();
    private final CopyOnWriteArrayList<SessionListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger generation = new AtomicInteger(0);
    private final Map<Runnable, ScheduledFuture<?>> scheduledTasks = new IdentityHashMap<>();

    private volatile ConnectionPhase phase = ConnectionPhase.DISCONNECTED;
    private volatile boolean shutdown;

    // Actor-thread owned state
    private WireDriver activeDriver;
    private Link activeLink;
    private ScheduledFuture<?> pollTask;
    private int activeGen;
    private final Runnable connectTimeout = () -> {
        if (phase == ConnectionPhase.CONNECTING) {
            emitError("RFCOMM handshake timed out");
            teardownOnActor(activeGen, "RFCOMM handshake timed out");
        }
    };

    public DesktopSession(DesktopDriverRegistry registry, WindowsBluetoothBridge bluetoothBridge) {
        this.registry = registry;
        this.bluetoothBridge = bluetoothBridge;
        this.actor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "WearEars-DesktopActor");
            t.setDaemon(true);
            return t;
        });
        actor.setRemoveOnCancelPolicy(true);
        actor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    public void addListener(SessionListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(SessionListener listener) {
        listeners.remove(listener);
    }

    public ConnectionPhase phase() {
        return phase;
    }

    public StateSnapshotView snapshot() {
        return stateHolder.view();
    }

    public LocalMeshProtocol.StateSnapshot meshSnapshot() {
        return stateHolder.toMeshSnapshot(phase == ConnectionPhase.CONNECTED);
    }

    public boolean isFeatureWritable(FeatureId id) {
        return stateHolder.isFeatureWritable(id);
    }

    int connectionId() {
        return generation.get();
    }

    public void connect(WindowsBluetoothBridge.BtDevice device, String forcedBrandKey) {
        if (device == null) {
            emitError("请先选择一个蓝牙耳机设备");
            return;
        }
        connectWithSockets(device.mac, device.name, forcedBrandKey, bluetoothBridge.createSockets(device.mac));
    }

    /** Connect entry point allowing injectable Sockets for unit tests. */
    public void connectWithSockets(String mac, String deviceName, String forcedBrandKey, Sockets sockets) {
        if (shutdown) return;
        final BrandModuleEntry brand = registry.resolve(deviceName, forcedBrandKey);
        if (brand == null) {
            emitError("未自动识别到「" + deviceName + "」对应品牌协议，请在左侧下拉框手动指定品牌后重试");
            return;
        }
        synchronized (this) {
            if (shutdown) return;
            final int gen = generation.incrementAndGet();
            postActor(() -> startSessionOnActor(gen, mac, deviceName, brand, sockets));
        }
    }

    public synchronized void disconnect() {
        if (shutdown) return;
        final int gen = generation.incrementAndGet();
        postActor(() -> teardownOnActor(gen, "已主动断开连接"));
    }

    public void refresh() {
        int gen = generation.get();
        postActor(() -> {
            if (isCurrent(gen) && activeDriver.isConnected()) {
                activeDriver.queryBattery();
                activeDriver.queryNoise();
                if ((activeDriver.caps() & Controls.CAP_SPATIAL) != 0) activeDriver.querySpatial();
                if ((activeDriver.caps() & Controls.CAP_EQUALIZER) != 0) activeDriver.queryEqualizer();
                if ((activeDriver.caps() & Controls.CAP_DEEPX) != 0) activeDriver.queryDeepX();
            }
        });
    }

    public void setNoiseMode(int mode) {
        if (mode < Controls.MODE_ANC || mode > Controls.MODE_TRANS) return;
        int gen = generation.get();
        postActor(() -> {
            if (gen != generation.get() || shutdown) return;
            if (!isCurrent(gen) || !activeDriver.isConnected()) {
                emitError("耳机未连接，无法切换降噪模式");
                return;
            }
            changeNoiseOnActor(mode);
        });
    }

    void applyNoiseHotkey(WindowsHotkeyManager.Action action, int connection) {
        postActor(() -> {
            if (!isCurrent(connection) || phase != ConnectionPhase.CONNECTED || !activeDriver.isConnected()
                    || (activeDriver.caps() & Controls.CAP_NOISE) == 0) return;
            int mode = action.noiseMode(stateHolder.view().mode);
            if (mode >= 0) changeNoiseOnActor(mode);
        });
    }

    private void changeNoiseOnActor(int mode) {
        emitTrace("Session", "setNoiseMode(" + mode + ")");
        activeDriver.changeNoise(mode, () -> { });
    }

    public void setEqualizer(int presetId) {
        postActor(() -> {
            if (activeDriver == null || !activeDriver.isConnected()) return;
            emitTrace("Session", "setEqualizer(" + presetId + ")");
            if ((activeDriver.caps() & Controls.CAP_DEEPX) != 0) {
                activeDriver.setDeepX(presetId);
            } else {
                activeDriver.setEqualizer(presetId);
            }
        });
    }

    public void setSpatial(boolean enabled) {
        postActor(() -> {
            if (activeDriver == null || !activeDriver.isConnected()) return;
            emitTrace("Session", "setSpatial(" + enabled + ")");
            activeDriver.setSpatial(enabled);
        });
    }

    public void writeFeature(FeatureId id, int value) {
        if (id == null) return;
        if (id == FeatureId.FIND_MY_BUDS && value != 0) return;
        postActor(() -> {
            if (activeDriver == null || !activeDriver.isConnected()) {
                emitError("耳机未连接，无法执行该功能");
                return;
            }
            emitTrace("Session", "writeFeature(" + id.name() + ", " + value + ")");
            if (id == FeatureId.NOISE_MODE) {
                activeDriver.changeNoise(value, () -> { });
            } else {
                activeDriver.writeFeature(id, value, () -> { });
            }
        });
    }

    /** Called only after the local confirmation dialog. Recheck on the protocol thread. */
    void ringFindBuds(int target, int confirmedConnection) {
        if (target <= 0) return;
        postActor(() -> {
            if (!canRingFindBuds(stateHolder.view(), target, confirmedConnection, generation.get())
                    || activeGen != confirmedConnection || activeDriver == null || !activeDriver.isConnected()) {
                emitTrace("Safety", "Find My Buds rejected: connection, wear status or target changed");
                return;
            }
            activeDriver.writeFeature(FeatureId.FIND_MY_BUDS, target, () -> { });
        });
    }

    static boolean canRingFindBuds(StateSnapshotView state, int target, int confirmedConnection, int currentConnection) {
        FeatureDescriptor spec = state.features.get(FeatureId.FIND_MY_BUDS);
        return confirmedConnection == currentConnection && state.connected && !state.isAnyBudInEar()
                && target > 0 && spec != null && spec.isValidValue(target);
    }

    public void queryFeature(FeatureId id) {
        if (id == null) return;
        postActor(() -> {
            if (activeDriver != null && activeDriver.isConnected()) {
                activeDriver.queryFeature(id);
            }
        });
    }

    public synchronized void shutdown() {
        if (shutdown) return;
        shutdown = true;
        int gen = generation.incrementAndGet();
        actor.execute(() -> {
            try {
                teardownOnActor(gen, "应用关闭");
            } finally {
                actor.shutdown();
            }
        });
    }

    boolean awaitShutdown(long timeout, TimeUnit unit) throws InterruptedException {
        return actor.awaitTermination(timeout, unit);
    }

    // ---- Actor-thread implementation ----

    private void startSessionOnActor(int gen, String mac, String deviceName, BrandModuleEntry brand, Sockets sockets) {
        if (gen != generation.get() || shutdown) return;
        teardownSilentOnActor();

        activeGen = gen;
        WireDriver driver = brand.module.create();
        activeDriver = driver;

        stateHolder.begin(mac, deviceName, brand.displayName, driver.caps(), driver.potentialCaps(), driver.verifLevel());
        for (FeatureDescriptor spec : driver.featureDescriptors()) {
            if (spec != null) stateHolder.featureSpec(spec);
        }
        phase = ConnectionPhase.CONNECTING;
        emitPhase(ConnectionPhase.CONNECTING, "正在通过 " + brand.displayName + " RFCOMM 连接 " + deviceName + "...");
        emitState();

        WireDriver.Port port = new SessionPort(gen);
        // OPPO can report just one component per notification. Its omitted slots carry 255.
        Controls.Listener driverListener = new SessionDriverListener(gen, driver, !"oppo".equals(brand.key));
        driver.bind(deviceName, port, driverListener);
        scheduleOnActor(gen, connectTimeout, CONNECT_TIMEOUT_MS);

        UUID[] services = DesktopCoreBridge.driverServices(driver, null);
        int[] channels = DesktopCoreBridge.driverChannels(driver);
        emitTrace("Session", "Dialing " + mac + " (" + brand.key + ") services=" + services.length + " channels=" + channels.length);

        Link link = new Link(sockets, new LinkEvents() {
            @Override
            public boolean post(Runnable work) {
                if (shutdown || gen != generation.get()) return false;
                return postActor(() -> {
                    if (isCurrent(gen)) work.run();
                });
            }

            @Override
            public void after(Runnable work, long delay) {
                scheduleOnActor(gen, work, delay);
            }

            @Override
            public void cancel(Runnable work) {
                cancelScheduled(work);
            }

            @Override
            public void opened() {
                if (gen != activeGen || activeDriver != driver) return;
                emitTrace("RFCOMM", "Channel opened, starting protocol handshake");
                DesktopCoreBridge.driverOnOpen(driver);
            }

            @Override
            public void receive(byte[] bytes) {
                if (gen != activeGen || activeDriver != driver) return;
                DesktopCoreBridge.driverReceived(driver, bytes);
            }

            @Override
            public void unavailable() {
                if (gen != activeGen || activeDriver != driver) return;
                emitTrace("RFCOMM", "RFCOMM services/channels unavailable, triggering driver fallback");
                DesktopCoreBridge.driverOpenFallback(driver);
            }

            @Override
            public void lost(String reason) {
                if (gen != activeGen) return;
                teardownOnActor(gen, "蓝牙连接已断开" + (reason == null || reason.isEmpty() ? "" : " (" + reason + ")"));
            }
        });
        activeLink = link;
        link.start(services, channels);
    }

    private void teardownOnActor(int gen, String reason) {
        if (gen != generation.get()) return;
        teardownSilentOnActor();
        activeGen = gen;
        phase = ConnectionPhase.DISCONNECTED;
        stateHolder.disconnect();
        emitPhase(ConnectionPhase.DISCONNECTED, reason);
        emitState();
    }

    private void teardownSilentOnActor() {
        cancelAllScheduled();
        if (pollTask != null) {
            pollTask.cancel(false);
            pollTask = null;
        }
        Link link = activeLink;
        activeLink = null;
        if (link != null) {
            try {
                link.close();
            } catch (Exception ignored) {
            }
        }
        WireDriver driver = activeDriver;
        activeDriver = null;
        if (driver != null) {
            try {
                DesktopCoreBridge.driverOnClose(driver);
            } catch (Exception ignored) {
            }
        }
    }

    private void schedulePollOnActor(int gen) {
        if (pollTask != null) {
            pollTask.cancel(false);
        }
        pollTask = actor.scheduleAtFixedRate(() -> {
            if (!isCurrent(gen) || !activeDriver.isConnected()) return;
            activeDriver.queryBattery();
            activeDriver.queryNoise();
        }, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private boolean postActor(Runnable task) {
        if (shutdown) return false;
        try {
            actor.execute(task);
            return true;
        } catch (RuntimeException rejected) {
            return false;
        }
    }

    private boolean isCurrent(int gen) {
        return gen == activeGen && gen == generation.get() && activeDriver != null && !shutdown;
    }

    private void scheduleOnActor(int gen, Runnable work, long delay) {
        if (work == null || shutdown) return;
        postActor(() -> {
            if (!isCurrent(gen)) return;
            cancelScheduledInternal(work);
            long safeDelay = Math.max(0L, delay);
            ScheduledFuture<?> future = actor.schedule(() -> {
                scheduledTasks.remove(work);
                if (isCurrent(gen)) {
                    work.run();
                }
            }, safeDelay, TimeUnit.MILLISECONDS);
            scheduledTasks.put(work, future);
        });
    }

    private void cancelScheduled(Runnable work) {
        if (work == null) return;
        postActor(() -> cancelScheduledInternal(work));
    }

    private void cancelScheduledInternal(Runnable work) {
        ScheduledFuture<?> f = scheduledTasks.remove(work);
        if (f != null) f.cancel(false);
    }

    private void cancelAllScheduled() {
        for (ScheduledFuture<?> f : scheduledTasks.values()) {
            f.cancel(false);
        }
        scheduledTasks.clear();
    }

    private final class SessionPort implements WireDriver.Port {
        private final int gen;

        SessionPort(int gen) {
            this.gen = gen;
        }

        @Override
        public void send(byte[] bytes, Runnable flushed) {
            if (!isCurrent(gen) || activeLink == null) return;
            activeLink.send(bytes, flushed);
        }

        @Override
        public void after(Runnable work, long delay) {
            scheduleOnActor(gen, work, delay);
        }

        @Override
        public void cancel(Runnable work) {
            cancelScheduled(work);
        }

        @Override
        public void useBle(UUID service, UUID notify, UUID write, long delay, long timeout) {
            if (!isCurrent(gen)) return;
            fail("此耳机回退至 BLE GATT 通道，Windows 桌面端当前仅支持经典蓝牙 RFCOMM/SPP 直连（可用手机作局域网主机协同控制）");
        }

        @Override
        public void fail(String message) {
            postActor(() -> {
                if (!isCurrent(gen)) return;
                String msg = (message == null || message.trim().isEmpty()) ? "控制通道连接失败" : message.trim();
                emitError(msg);
                teardownOnActor(gen, msg);
            });
        }

        @Override
        public long millis() {
            return System.currentTimeMillis();
        }

        @Override
        public void trace(String tag, String message) {
            emitTrace(tag, message);
        }
    }

    private final class SessionDriverListener implements Controls.Listener {
        private final int gen;
        private final WireDriver driver;
        private final boolean fullBatteryReport;
        private boolean initialRefreshScheduled;

        SessionDriverListener(int gen, WireDriver driver, boolean fullBatteryReport) {
            this.gen = gen;
            this.driver = driver;
            this.fullBatteryReport = fullBatteryReport;
        }

        private boolean stale() {
            return !isCurrent(gen) || activeDriver != driver;
        }

        @Override
        public void onConnected(String deviceName) {
            if (stale()) return;
            stateHolder.setConnected(true, deviceName, driver.caps(), driver.potentialCaps(), driver.equalizerPresets());
            for (FeatureDescriptor spec : driver.featureDescriptors()) {
                if (spec != null) stateHolder.featureSpec(spec);
            }
            phase = ConnectionPhase.CONNECTED;
            cancelScheduledInternal(connectTimeout);
            schedulePollOnActor(gen);
            if (!initialRefreshScheduled) {
                initialRefreshScheduled = true;
                scheduleOnActor(gen, () -> {
                    StateSnapshotView state = stateHolder.view();
                    if (!stale() && driver.isConnected()
                            && ((state.left < 0 && state.right < 0 && state.box < 0) || state.mode < 0)) {
                        emitTrace("Session", "Requesting initial battery and noise state again");
                        driver.queryBattery();
                        driver.queryNoise();
                    }
                }, INITIAL_REFRESH_MS);
            }
            emitPhase(ConnectionPhase.CONNECTED, "已连接 " + stateHolder.view().name);
            emitState();
        }

        @Override
        public void onDisconnected(String reason) {
            if (stale()) return;
            teardownOnActor(gen, reason == null ? "连接已断开" : reason);
        }

        @Override
        public void onError(String msg) {
            if (stale()) return;
            emitError(msg);
        }

        @Override
        public void onBattery(int left, int right, int box, int chargeFlags) {
            if (stale()) return;
            stateHolder.updateCapabilities(driver.caps(), driver.potentialCaps(), driver.equalizerPresets());
            stateHolder.battery(left, right, box, chargeFlags, fullBatteryReport);
            emitState();
        }

        @Override
        public void onNoiseMode(int mode) {
            if (stale()) return;
            stateHolder.updateCapabilities(driver.caps(), driver.potentialCaps(), driver.equalizerPresets());
            stateHolder.mode(mode);
            emitState();
        }

        @Override
        public void onSpatial(Boolean state) {
            if (stale()) return;
            stateHolder.updateCapabilities(driver.caps(), driver.potentialCaps(), driver.equalizerPresets());
            stateHolder.spatial(state);
            emitState();
        }

        @Override
        public void onDeepX(Integer mode) {
            if (stale()) return;
            stateHolder.updateCapabilities(driver.caps(), driver.potentialCaps(), driver.equalizerPresets());
            stateHolder.equalizer(mode);
            emitState();
        }

        @Override
        public void onEqualizer(Integer mode) {
            if (stale()) return;
            stateHolder.updateCapabilities(driver.caps(), driver.potentialCaps(), driver.equalizerPresets());
            stateHolder.equalizer(mode);
            emitState();
        }

        @Override
        public void onFeatureSpec(FeatureDescriptor spec) {
            if (stale() || spec == null) return;
            stateHolder.updateCapabilities(driver.caps(), driver.potentialCaps(), driver.equalizerPresets());
            stateHolder.featureSpec(spec);
            emitState();
        }

        @Override
        public void onFeatureValue(FeatureId id, int value) {
            if (stale() || id == null) return;
            stateHolder.updateCapabilities(driver.caps(), driver.potentialCaps(), driver.equalizerPresets());
            stateHolder.featureValue(id, value);
            emitState();
        }

        @Override
        public void onFeatureText(FeatureId id, String text) {
            if (stale() || id == null) return;
            stateHolder.featureText(id, text);
            emitState();
        }
    }

    private void emitPhase(ConnectionPhase p, String detail) {
        int connection = activeGen;
        if (connection != generation.get()) return;
        for (SessionListener l : listeners) {
            l.onPhaseChanged(connection, p, detail);
        }
    }

    private void emitState() {
        int connection = activeGen;
        if (connection != generation.get()) return;
        StateSnapshotView snap = stateHolder.view();
        for (SessionListener l : listeners) {
            l.onStateChanged(connection, snap);
        }
    }

    private void emitError(String message) {
        for (SessionListener l : listeners) {
            l.onError(message);
        }
    }

    private void emitTrace(String tag, String message) {
        for (SessionListener l : listeners) {
            l.onTrace(tag == null ? "Core" : tag, message == null ? "" : message);
        }
    }
}
