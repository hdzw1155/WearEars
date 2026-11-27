package com.hdz.wearcontrol.desktop;

import com.hdz.wearcontrol.desktop.DesktopSession.ConnectionPhase;

/** EDT-owned backoff and cancellation state. The application owns the timer and Bluetooth scan. */
final class ReconnectWatchdog {
    private static final int[] DELAYS_MS = {5_000, 10_000, 15_000, 30_000};

    private boolean enabled;
    private boolean userDisconnectedManually;
    private int step;
    private long revision;

    ReconnectWatchdog(boolean enabled) {
        this.enabled = enabled;
    }

    void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        resetBackoff();
    }

    void suppressUntilManualConnect() {
        userDisconnectedManually = true;
        resetBackoff();
    }

    void manualConnect() {
        userDisconnectedManually = false;
        resetBackoff();
    }

    void connected() {
        resetBackoff();
    }

    void probeFailed() {
        if (enabled && !userDisconnectedManually && step < DELAYS_MS.length - 1) step++;
    }

    boolean userDisconnectedManually() { return userDisconnectedManually; }
    long revision() { return revision; }

    int delayMillis(boolean hasTarget, ConnectionPhase phase) {
        return enabled && !userDisconnectedManually && hasTarget && phase == ConnectionPhase.DISCONNECTED
                ? DELAYS_MS[step] : -1;
    }

    private void resetBackoff() {
        step = 0;
        revision++;
    }
}
