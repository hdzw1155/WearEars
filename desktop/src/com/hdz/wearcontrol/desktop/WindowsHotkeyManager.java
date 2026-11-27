package com.hdz.wearcontrol.desktop;

import com.hdz.wearcontrol.Controls;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class WindowsHotkeyManager {
    enum Action {
        TOGGLE_PANEL("Ctrl+Alt+W"), CYCLE_NOISE("Ctrl+Alt+N"), NOISE_ANC("Ctrl+Alt+A"),
        NOISE_TRANS("Ctrl+Alt+T"), NOISE_OFF("Ctrl+Alt+O");

        final String shortcut;

        Action(String shortcut) {
            this.shortcut = shortcut;
        }

        int noiseMode(int currentMode) {
            switch (this) {
                case CYCLE_NOISE: return nextNoiseMode(currentMode);
                case NOISE_ANC: return Controls.MODE_ANC;
                case NOISE_TRANS: return Controls.MODE_TRANS;
                case NOISE_OFF: return Controls.MODE_OFF;
                default: return -1;
            }
        }
    }

    enum Kind { REG, READY, HOTKEY }

    static final class Event {
        final Kind kind;
        final Action action;
        final boolean registered;
        final int available, total;

        Event(Kind kind, Action action, boolean registered, int available, int total) {
            this.kind = kind;
            this.action = action;
            this.registered = registered;
            this.available = available;
            this.total = total;
        }
    }

    enum Phase { DISABLED, STARTING, READY, UNAVAILABLE }

    static final class Status {
        final Phase phase;
        final int available, total;
        final Set<Action> busy;

        Status(Phase phase, int available, int total, Set<Action> busy) {
            this.phase = phase;
            this.available = available;
            this.total = total;
            this.busy = Set.copyOf(busy);
        }

        Status(Phase phase) {
            this(phase, 0, 0, Set.of());
        }
    }

    interface Launcher {
        Process start() throws IOException;
    }

    private static final byte[] QUIT = "QUIT\n".getBytes(StandardCharsets.US_ASCII);
    private final Launcher launcher;
    private final Executor callbacks;
    private final Consumer<Action> onAction;
    private final Runnable onStatusChanged;
    private final ExecutorService lifecycle = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "WearEars-HotkeyLifecycle");
        thread.setDaemon(true);
        return thread;
    });
    private Activation active;
    private Status status = new Status(Phase.DISABLED);
    private boolean closed;

    WindowsHotkeyManager(Path bridgeExe, Executor callbacks, Consumer<Action> onAction, Runnable onStatusChanged) {
        this(() -> new ProcessBuilder(WindowsBluetoothBridge.ensureRunnableBridgeExe(bridgeExe).toString(), "hotkeys")
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start(), callbacks, onAction, onStatusChanged);
    }

    WindowsHotkeyManager(Launcher launcher, Executor callbacks, Consumer<Action> onAction, Runnable onStatusChanged) {
        this.launcher = launcher;
        this.callbacks = callbacks;
        this.onAction = onAction;
        this.onStatusChanged = onStatusChanged;
    }

    void start() {
        synchronized (this) {
            if (closed || active != null) return;
            Activation activation = new Activation();
            active = activation;
            status = new Status(Phase.STARTING);
            lifecycle.execute(() -> launch(activation));
        }
        callbacks.execute(onStatusChanged);
    }

    void stop() {
        synchronized (this) {
            if (!stopLocked()) return;
        }
        callbacks.execute(onStatusChanged);
    }

    void shutdown() {
        boolean changed;
        synchronized (this) {
            if (closed) return;
            closed = true;
            changed = stopLocked();
            lifecycle.shutdown();
        }
        if (changed) callbacks.execute(onStatusChanged);
    }

    synchronized Status status() {
        return status;
    }

    boolean awaitShutdown(long timeout, TimeUnit unit) throws InterruptedException {
        return lifecycle.awaitTermination(timeout, unit);
    }

    private boolean stopLocked() {
        Activation previous = active;
        if (previous == null) return false;
        active = null;
        status = new Status(Phase.DISABLED);
        lifecycle.execute(() -> stopProcess(previous));
        return true;
    }

    private void launch(Activation activation) {
        synchronized (this) {
            if (active != activation) return;
        }
        try {
            Process process = launcher.start();
            activation.process = process;
            synchronized (this) {
                if (active != activation) return; // Its queued stop owns the process.
                activation.reader = new Thread(() -> read(activation, process), "WearEars-HotkeyReader");
                activation.reader.setDaemon(true);
                activation.reader.start();
            }
        } catch (IOException | RuntimeException unavailable) {
            failed(activation);
        }
    }

    private void read(Activation activation, Process process) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Event event = parseLine(line);
                if (event != null) handle(activation, event);
            }
        } catch (IOException ignored) {
        } finally {
            failed(activation);
        }
    }

    private void handle(Activation activation, Event event) {
        synchronized (this) {
            if (active != activation) return;
            if (event.kind == Kind.REG && status.phase == Phase.STARTING) {
                if (event.registered) {
                    activation.registered.add(event.action);
                    activation.busy.remove(event.action);
                } else {
                    activation.busy.add(event.action);
                    activation.registered.remove(event.action);
                }
                return;
            }
            if (event.kind == Kind.READY && status.phase == Phase.STARTING) {
                status = new Status(Phase.READY, event.available, event.total, activation.busy);
            } else if (event.kind != Kind.HOTKEY || status.phase != Phase.READY
                    || !activation.registered.contains(event.action)) {
                return;
            }
        }
        if (event.kind == Kind.READY) callbacks.execute(onStatusChanged);
        else callbacks.execute(() -> {
            synchronized (this) {
                if (active != activation) return;
            }
            onAction.accept(event.action);
        });
    }

    private void failed(Activation activation) {
        synchronized (this) {
            if (active != activation) return;
            status = new Status(Phase.UNAVAILABLE);
            lifecycle.execute(() -> stopProcess(activation));
        }
        callbacks.execute(onStatusChanged);
    }

    // Only the lifecycle executor starts or terminates children, so rapid toggles cannot overlap registrations.
    private static void stopProcess(Activation activation) {
        Process process = activation.process;
        activation.process = null;
        if (process == null) return;
        try (OutputStream input = process.getOutputStream()) {
            input.write(QUIT);
            input.flush();
        } catch (IOException ignored) {
        }
        try {
            if (!process.waitFor(750, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor();
            }
            if (activation.reader != null) activation.reader.join(1_000);
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    static Event parseLine(String line) {
        if (line == null || line.isBlank()) return null;
        String[] fields = line.strip().split("\\|", -1);
        try {
            switch (fields[0]) {
                case "REG":
                    if (fields.length != 3 || !("OK".equals(fields[2]) || "BUSY".equals(fields[2]))) return null;
                    return new Event(Kind.REG, Action.valueOf(fields[1]), "OK".equals(fields[2]), 0, 0);
                case "READY":
                    if (fields.length != 3 || !fields[1].matches("[0-9]+") || !fields[2].matches("[0-9]+")) return null;
                    int available = Integer.parseInt(fields[1]);
                    int total = Integer.parseInt(fields[2]);
                    return total > 0 && available <= total ? new Event(Kind.READY, null, false, available, total) : null;
                case "HOTKEY":
                    return fields.length == 2 ? new Event(Kind.HOTKEY, Action.valueOf(fields[1]), false, 0, 0) : null;
                default:
                    return null;
            }
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    static int nextNoiseMode(int current) {
        switch (current) {
            case Controls.MODE_ANC: return Controls.MODE_TRANS;
            case Controls.MODE_TRANS: return Controls.MODE_OFF;
            default: return Controls.MODE_ANC;
        }
    }

    private static final class Activation {
        final EnumSet<Action> registered = EnumSet.noneOf(Action.class);
        final EnumSet<Action> busy = EnumSet.noneOf(Action.class);
        Process process;
        Thread reader;
    }
}
