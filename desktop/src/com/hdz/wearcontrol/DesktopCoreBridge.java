package com.hdz.wearcontrol;

import com.hdz.wearcontrol.huawei.HuaweiSppClient;
import com.hdz.wearcontrol.oppo.OppoOpoClient;
import com.hdz.wearcontrol.vivo.VivoGaiaClient;
import com.hdz.wearcontrol.xiaomi.XiaomiMmaClient;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Package-level facade inside desktop/src/ allowing com.hdz.wearcontrol.desktop to reuse
 * core/'s package-private RfcommLink, DeviceState, WireDriver hooks, and all 9 DriverModules
 * without modifying any file in core/ or drivers/.
 */
public final class DesktopCoreBridge {
    private DesktopCoreBridge() {
    }

    public static final int WEAR_UNKNOWN = DeviceState.WEAR_UNKNOWN;
    public static final int WEAR_NONE = DeviceState.WEAR_NONE;
    public static final int WEAR_LEFT = DeviceState.WEAR_LEFT;
    public static final int WEAR_RIGHT = DeviceState.WEAR_RIGHT;
    public static final int WEAR_BOTH = DeviceState.WEAR_BOTH;

    public interface Socket extends Closeable {
        void connect() throws IOException;
        InputStream input() throws IOException;
        OutputStream output() throws IOException;
    }

    public interface Sockets {
        Socket create(UUID service, boolean insecure, int channel) throws Exception;
    }

    public interface LinkEvents {
        boolean post(Runnable work);
        void after(Runnable work, long delay);
        void cancel(Runnable work);
        void opened();
        void receive(byte[] bytes);
        void unavailable();
        void lost(String reason);
    }

    public static final class Link implements AutoCloseable {
        private final RfcommLink delegate;

        public Link(Sockets sockets, LinkEvents events) {
            RfcommLink.Sockets rfSockets = (service, insecure, channel) -> {
                Socket s = sockets.create(service, insecure, channel);
                return new RfcommLink.Socket() {
                    @Override public void connect() throws IOException { s.connect(); }
                    @Override public InputStream input() throws IOException { return s.input(); }
                    @Override public OutputStream output() throws IOException { return s.output(); }
                    @Override public void close() throws IOException { s.close(); }
                };
            };
            RfcommLink.Events rfEvents = new RfcommLink.Events() {
                @Override public boolean post(Runnable work) { return events.post(work); }
                @Override public void after(Runnable work, long delay) { events.after(work, delay); }
                @Override public void cancel(Runnable work) { events.cancel(work); }
                @Override public void opened() { events.opened(); }
                @Override public void receive(byte[] bytes) { events.receive(bytes); }
                @Override public void unavailable() { events.unavailable(); }
                @Override public void lost(String reason) { events.lost(reason); }
            };
            this.delegate = new RfcommLink(rfSockets, rfEvents);
        }

        public void start(UUID[] services, int[] channels) {
            delegate.start(services == null ? new UUID[0] : services,
                    channels == null ? new int[0] : channels);
        }

        public void send(byte[] data, Runnable flushed) {
            delegate.send(data, flushed);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    public static final class BrandModuleEntry {
        public final String key;
        public final String displayName;
        public final DriverModule module;

        public BrandModuleEntry(String key, String displayName, DriverModule module) {
            this.key = key;
            this.displayName = displayName;
            this.module = module;
        }
    }

    /** Assembles all 9 brand DriverModules directly from drivers/. */
    public static BrandModuleEntry[] allBrandModules() {
        return new BrandModuleEntry[] {
                new BrandModuleEntry("vivo", "vivo / iQOO", new VivoGaiaClient.Module()),
                new BrandModuleEntry("huawei", "华为 / 荣耀 (Huawei)", new HuaweiSppClient.Module()),
                new BrandModuleEntry("xiaomi", "小米 / Redmi (Xiaomi)", new XiaomiMmaClient.Module()),
                new BrandModuleEntry("oppo", "OPPO / 一加 / realme", new OppoOpoClient.Module()),
                new BrandModuleEntry("edifier", "漫步者 (Edifier)", new EdifierClient.Module()),
                new BrandModuleEntry("rose", "弱水时砂 (Rose Technics)", new RoseLinkClient.Module()),
                new BrandModuleEntry("nicehck", "原道 (NiceHCK)", new NiceHckClient.Module()),
                new BrandModuleEntry("sony", "索尼 (Sony)", new SonyClient.Module()),
                new BrandModuleEntry("technics", "松下 (Technics)", new TechnicsClient.Module())
        };
    }

    // ---- WireDriver package-private / protected lifecycle hooks ----

    public static UUID[] driverServices(WireDriver driver, UUID[] advertised) {
        UUID[] res = driver.services(advertised);
        return res == null ? new UUID[0] : res;
    }

    public static int[] driverChannels(WireDriver driver) {
        int[] res = driver.channels();
        return res == null ? new int[0] : res;
    }

    public static void driverOnOpen(WireDriver driver) {
        driver.onOpen();
    }

    public static void driverOnClose(WireDriver driver) {
        driver.onClose();
    }

    public static void driverReceived(WireDriver driver, byte[] bytes) {
        driver.received(bytes);
    }

    public static void driverOpenFallback(WireDriver driver) {
        driver.openFallback();
    }

    public static String driverConnectionFailure(WireDriver driver) {
        return driver.connectionFailure();
    }

    // ---- DeviceState public facade ----

    public static final class StateSnapshotView {
        public final String address;
        public final String name;
        public final String brandName;
        public final boolean connected;
        public final int caps;
        public final int potentialCaps;
        public final int verifLevel;
        public final int left;
        public final int right;
        public final int box;
        public final int charge;
        public final int mode;
        public final Boolean spatial;
        public final Integer equalizer;
        public final int wearStatus;
        public final String firmware;
        public final SoundPreset[] presets;
        public final Map<FeatureId, FeatureDescriptor> features;
        public final Map<FeatureId, Integer> featureValues;
        public final Map<FeatureId, String> featureTexts;
        private final Map<FeatureId, Integer> featureRevisions;

        StateSnapshotView(DeviceState s, String brandName, int verifLevel) {
            this.address = s.address;
            this.name = s.name;
            this.brandName = brandName == null ? "" : brandName;
            this.connected = s.connected;
            this.caps = s.caps;
            this.potentialCaps = s.potentialCaps;
            this.verifLevel = verifLevel;
            this.left = s.left;
            this.right = s.right;
            this.box = s.box;
            this.charge = s.charge;
            this.mode = s.mode;
            this.spatial = s.spatial;
            this.equalizer = s.equalizer;
            this.wearStatus = s.wearStatus;
            this.firmware = s.firmware;
            this.presets = s.presets == null ? new SoundPreset[0] : s.presets.clone();
            this.features = Collections.unmodifiableMap(new LinkedHashMap<>(s.features));
            this.featureValues = Collections.unmodifiableMap(new EnumMap<>(s.featureValues));
            this.featureTexts = Collections.unmodifiableMap(new EnumMap<>(s.featureTexts));
            this.featureRevisions = new EnumMap<>(s.featureRevisions);
        }

        public int featureRevision(FeatureId id) {
            return featureRevisions.getOrDefault(id, 0);
        }

        public boolean isAnyBudInEar() {
            return wearStatus == WEAR_LEFT || wearStatus == WEAR_RIGHT || wearStatus == WEAR_BOTH;
        }
    }

    public static final class StateHolder {
        private final DeviceState state = new DeviceState();
        private String brandName = "";
        private int verifLevel = Controls.VERIF_UNTESTED;

        public synchronized void begin(String address, String name, String brand, int caps, int potentialCaps, int verif) {
            this.brandName = brand == null ? "" : brand;
            this.verifLevel = verif;
            state.begin(address, name, caps, potentialCaps);
        }

        public synchronized void setConnected(boolean connected, String name, int caps, int potentialCaps, SoundPreset[] presets) {
            state.connected = connected;
            if (name != null && !name.isEmpty()) state.name = name;
            state.capabilities(caps, potentialCaps);
            if (presets != null) state.presets = presets.clone();
        }

        public synchronized void updateCapabilities(int caps, int potentialCaps, SoundPreset[] presets) {
            state.capabilities(caps, potentialCaps);
            if (presets != null) state.presets = presets.clone();
        }

        public synchronized void battery(int left, int right, int box, int charge) {
            battery(left, right, box, charge, false);
        }

        public synchronized void battery(int left, int right, int box, int charge, boolean fullReport) {
            if (!state.connected) return;
            if (fullReport) {
                if (!DeviceState.known(left)) state.left = -1;
                if (!DeviceState.known(right)) state.right = -1;
                if (!DeviceState.known(box)) state.box = -1;
            } else if (state.charge >= 0 && charge >= 0 && charge <= 7) {
                int observed = (DeviceState.known(left) ? 1 : 0)
                        | (DeviceState.known(right) ? 2 : 0)
                        | (DeviceState.known(box) ? 4 : 0);
                charge = (state.charge & ~observed) | (charge & observed);
            }
            state.battery(left, right, box, charge);
            if ((state.caps & Controls.CAP_BATTERY_SINGLE) != 0 && charge >= 0 && charge <= 7) {
                state.charge = charge & 1;
            }
        }

        public synchronized void mode(int mode) {
            if (state.connected && mode >= -1 && mode <= Controls.MODE_TRANS) {
                state.mode = mode;
            }
        }

        public synchronized void spatial(Boolean spatial) {
            if (state.connected) {
                state.spatial = spatial;
                if (spatial != null) state.featureValue(FeatureId.SPATIAL_AUDIO, spatial ? 1 : 0);
            }
        }

        public synchronized void equalizer(Integer eq) {
            if (state.connected) {
                state.equalizer = eq;
                if (eq != null) state.featureValue(FeatureId.EQUALIZER, eq);
            }
        }

        public synchronized void featureSpec(FeatureDescriptor spec) {
            state.featureSpec(spec);
        }

        public synchronized void featureValue(FeatureId id, int value) {
            state.featureValue(id, value);
        }

        public synchronized void featureText(FeatureId id, String text) {
            state.featureText(id, text);
        }

        public synchronized void disconnect() {
            state.disconnect();
        }

        public synchronized boolean isFeatureWritable(FeatureId id) {
            return state.isFeatureWritable(id);
        }

        public synchronized StateSnapshotView view() {
            return new StateSnapshotView(state, brandName, verifLevel);
        }

        public synchronized LocalMeshProtocol.StateSnapshot toMeshSnapshot(boolean live) {
            return LocalMeshProtocol.StateSnapshot.fromDeviceState(state, live);
        }
    }
}
