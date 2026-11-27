package com.hdz.wearcontrol;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Pure Java 11 application-layer LAN firewall for WearEars Local Mesh.
 * Zero Android dependencies so Android and Windows desktop share the exact same enforcement logic.
 */
public final class LocalMeshFirewall {
    public static final int MAX_AUTH_FAILS = 3;
    public static final long AUTH_LOCKOUT_MS = 300_000L;
    public static final int MAX_CONNS_PER_10S = 6;
    public static final int MAX_CMDS_PER_10S = 20;
    public static final long RATE_WINDOW_MS = 10_000L;
    public static final int MAX_AUDIT_EVENTS = 20;

    public static final String REASON_ALLOW = "ALLOW";
    public static final String REASON_NON_PRIVATE_IP = "NON_PRIVATE_IP";
    public static final String REASON_IP_BANNED = "IP_BANNED";
    public static final String REASON_CROSS_SUBNET = "CROSS_SUBNET";
    public static final String REASON_NOT_IN_ALLOWLIST = "NOT_IN_ALLOWLIST";
    public static final String REASON_CONN_RATE_LIMIT = "CONN_RATE_LIMIT";
    public static final String REASON_AUTH_FAIL = "AUTH_FAIL";
    public static final String REASON_AUTH_LOCKOUT = "AUTH_LOCKOUT";
    public static final String REASON_FIND_BUDS_HARD_BLOCK = "FIND_BUDS_HARD_BLOCK";
    public static final String REASON_DISALLOWED_FEATURE = "DISALLOWED_FEATURE";
    public static final String REASON_READ_ONLY_MODE = "READ_ONLY_MODE";
    public static final String REASON_CMD_RATE_LIMIT = "CMD_RATE_LIMIT";
    public static final String REASON_INVALID_FRAME = "INVALID_FRAME";

    private static final int MAX_TRACKED_IPS = 128;

    public static final class Decision {
        public final boolean allowed;
        public final String reasonCode;

        public Decision(boolean allowed, String reasonCode) {
            this.allowed = allowed;
            this.reasonCode = reasonCode == null ? "" : reasonCode;
        }

        public static Decision allow() {
            return new Decision(true, REASON_ALLOW);
        }

        public static Decision deny(String reasonCode) {
            return new Decision(false, reasonCode);
        }

        @Override
        public String toString() {
            return (allowed ? "ALLOW" : "DENY") + "(" + reasonCode + ")";
        }
    }

    public static final class AuditEvent {
        public final long timeMs;
        public final String ip;
        public final String reason;

        public AuditEvent(long timeMs, String ip, String reason) {
            this.timeMs = timeMs;
            this.ip = ip == null ? "" : ip;
            this.reason = reason == null ? "" : reason;
        }

        @Override
        public String toString() {
            return timeMs + "|" + ip + "|" + reason;
        }
    }

    private final LongSupplier clockMs;

    private boolean enabled = true;
    private boolean sameSubnetOnly = true;
    private boolean allowlistOnly = false;
    private boolean readOnlyMode = false;
    private final LinkedHashSet<String> allowlist = new LinkedHashSet<>();

    private final Map<String, Integer> authFailures = new HashMap<>();
    private final Map<String, Long> bannedUntil = new HashMap<>();
    private final Map<String, Deque<Long>> connTimes = new HashMap<>();
    private final Map<String, Deque<Long>> cmdTimes = new HashMap<>();
    private final Deque<AuditEvent> auditRing = new ArrayDeque<>(MAX_AUDIT_EVENTS);

    private int blockedTotal = 0;

    public LocalMeshFirewall() {
        this(System::currentTimeMillis);
    }

    public LocalMeshFirewall(LongSupplier clockMs) {
        this.clockMs = clockMs != null ? clockMs : System::currentTimeMillis;
    }

    public LocalMeshFirewall(boolean enabled, boolean sameSubnetOnly, boolean allowlistOnly,
                             boolean readOnlyMode, Collection<String> initialAllowlist,
                             LongSupplier clockMs) {
        this(clockMs);
        this.enabled = enabled;
        this.sameSubnetOnly = sameSubnetOnly;
        this.allowlistOnly = allowlistOnly;
        this.readOnlyMode = readOnlyMode;
        if (initialAllowlist != null) {
            for (String ip : initialAllowlist) {
                addAllowedIp(ip);
            }
        }
    }

    // ---- Configuration ----

    public synchronized boolean isEnabled() {
        return enabled;
    }

    public synchronized void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public synchronized boolean isSameSubnetOnly() {
        return sameSubnetOnly;
    }

    public synchronized void setSameSubnetOnly(boolean sameSubnetOnly) {
        this.sameSubnetOnly = sameSubnetOnly;
    }

    public synchronized boolean isAllowlistOnly() {
        return allowlistOnly;
    }

    public synchronized void setAllowlistOnly(boolean allowlistOnly) {
        this.allowlistOnly = allowlistOnly;
    }

    public synchronized boolean isReadOnlyMode() {
        return readOnlyMode;
    }

    public synchronized void setReadOnlyMode(boolean readOnlyMode) {
        this.readOnlyMode = readOnlyMode;
    }

    public synchronized void configure(boolean enabled, boolean sameSubnetOnly,
                                       boolean allowlistOnly, boolean readOnlyMode) {
        this.enabled = enabled;
        this.sameSubnetOnly = sameSubnetOnly;
        this.allowlistOnly = allowlistOnly;
        this.readOnlyMode = readOnlyMode;
    }

    public synchronized boolean addAllowedIp(String ip) {
        String clean = normalizeIp(ip);
        if (!LocalMeshProtocol.isPrivateLanAddress(clean)) {
            return false;
        }
        return allowlist.add(clean);
    }

    public synchronized boolean removeAllowedIp(String ip) {
        String clean = normalizeIp(ip);
        if (clean.isEmpty()) return false;
        return allowlist.remove(clean);
    }

    public synchronized void clearAllowlist() {
        allowlist.clear();
    }

    public synchronized void setAllowlist(Collection<String> ips) {
        allowlist.clear();
        if (ips == null) return;
        for (String ip : ips) {
            addAllowedIp(ip);
        }
    }

    public synchronized void setAllowlistFromCsv(String csv) {
        allowlist.clear();
        if (csv == null || csv.trim().isEmpty()) return;
        String[] parts = csv.split("[,;\\s]+");
        for (String part : parts) {
            addAllowedIp(part);
        }
    }

    public synchronized String allowlistToCsv() {
        if (allowlist.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String ip : allowlist) {
            if (sb.length() > 0) sb.append(',');
            sb.append(ip);
        }
        return sb.toString();
    }

    public synchronized Set<String> allowlistSnapshot() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(allowlist));
    }

    // ---- Policy enforcement ----

    /**
     * Checks an incoming TCP connection in order:
     * 1. Private LAN address invariant (always enforced)
     * 2. Active auth lockout ban (always enforced)
     * 3. When enabled: same /24 subnet check (allowing 127.0.0.1 / ::1 loopback)
     * 4. When enabled: allowlist-only check
     * 5. When enabled: connection rate limit (MAX_CONNS_PER_10S per 10s window)
     */
    public synchronized Decision checkIncomingConnection(String remoteIp, String localIp) {
        long now = clockMs.getAsLong();
        String ip = normalizeIp(remoteIp);
        if (!LocalMeshProtocol.isPrivateLanAddress(ip)) {
            return denyAndRecord(now, ip.isEmpty() ? "unknown" : ip, REASON_NON_PRIVATE_IP);
        }
        if (isBannedLocked(ip, now)) {
            return denyAndRecord(now, ip, REASON_IP_BANNED);
        }
        if (enabled) {
            if (sameSubnetOnly && !isSameSubnet24(ip, normalizeIp(localIp))) {
                return denyAndRecord(now, ip, REASON_CROSS_SUBNET);
            }
            if (allowlistOnly && !allowlist.contains(ip)) {
                return denyAndRecord(now, ip, REASON_NOT_IN_ALLOWLIST);
            }
            if (!consumeRateSlotLocked(connTimes, ip, now, MAX_CONNS_PER_10S)) {
                return denyAndRecord(now, ip, REASON_CONN_RATE_LIMIT);
            }
        }
        return Decision.allow();
    }

    /**
     * Records a failed PIN handshake from remoteIp.
     * After MAX_AUTH_FAILS consecutive failures, locks out the IP for AUTH_LOCKOUT_MS (5 minutes).
     *
     * @return true if this failure triggered a lockout ban, false otherwise
     */
    public synchronized boolean recordAuthFailure(String remoteIp) {
        long now = clockMs.getAsLong();
        String ip = normalizeIp(remoteIp);
        if (ip.isEmpty()) ip = "unknown";
        pruneExpiredBansLocked(now);
        if (authFailures.size() >= MAX_TRACKED_IPS && !authFailures.containsKey(ip)) {
            authFailures.clear();
        }
        int fails = authFailures.getOrDefault(ip, 0) + 1;
        if (fails >= MAX_AUTH_FAILS) {
            authFailures.remove(ip);
            bannedUntil.put(ip, now + AUTH_LOCKOUT_MS);
            appendAuditLocked(now, ip, REASON_AUTH_LOCKOUT);
            return true;
        }
        authFailures.put(ip, fails);
        appendAuditLocked(now, ip, REASON_AUTH_FAIL);
        return false;
    }

    /** Resets consecutive authentication failure counter for remoteIp upon successful handshake. */
    public synchronized void recordAuthSuccess(String remoteIp) {
        String ip = normalizeIp(remoteIp);
        if (!ip.isEmpty()) {
            authFailures.remove(ip);
        }
    }

    /**
     * Checks an authenticated inbound command frame:
     * - Always hard-blocks FIND_MY_BUDS (and disallowed remote features), even if enabled == false.
     * - When enabled: rejects banned IPs, allows only CMD_REFRESH / PING / PONG in readOnlyMode,
     *   and enforces MAX_CMDS_PER_10S per 10s window.
     */
    public synchronized Decision checkCommand(String remoteIp, LocalMeshProtocol.Frame frame) {
        long now = clockMs.getAsLong();
        String ip = normalizeIp(remoteIp);
        String logIp = ip.isEmpty() ? "unknown" : ip;
        if (frame == null || frame.type == null) {
            return denyAndRecord(now, logIp, REASON_INVALID_FRAME);
        }

        // Unconditional hearing-safety hard redline: FIND_MY_BUDS is NEVER allowed over LAN.
        if (frame.type == LocalMeshProtocol.FrameType.CMD_FEATURE) {
            if (frame.featureId == FeatureId.FIND_MY_BUDS) {
                return denyAndRecord(now, logIp, REASON_FIND_BUDS_HARD_BLOCK);
            }
            if (!LocalMeshProtocol.isAllowedRemoteFeature(frame.featureId)) {
                return denyAndRecord(now, logIp, REASON_DISALLOWED_FEATURE);
            }
        }

        if (enabled) {
            if (!ip.isEmpty() && isBannedLocked(ip, now)) {
                return denyAndRecord(now, logIp, REASON_IP_BANNED);
            }
            if (readOnlyMode && frame.type != LocalMeshProtocol.FrameType.CMD_REFRESH
                    && frame.type != LocalMeshProtocol.FrameType.PING
                    && frame.type != LocalMeshProtocol.FrameType.PONG) {
                return denyAndRecord(now, logIp, REASON_READ_ONLY_MODE);
            }
            if (!consumeRateSlotLocked(cmdTimes, logIp, now, MAX_CMDS_PER_10S)) {
                return denyAndRecord(now, logIp, REASON_CMD_RATE_LIMIT);
            }
        }
        return Decision.allow();
    }

    // ---- Audit & Ban Inspection ----

    public synchronized int blockedCount() {
        return blockedTotal;
    }

    public synchronized int bannedIpCount() {
        pruneExpiredBansLocked(clockMs.getAsLong());
        return bannedUntil.size();
    }

    public synchronized boolean isBanned(String remoteIp) {
        String ip = normalizeIp(remoteIp);
        if (ip.isEmpty()) return false;
        return isBannedLocked(ip, clockMs.getAsLong());
    }

    public synchronized void clearBans() {
        bannedUntil.clear();
        authFailures.clear();
    }

    public synchronized List<AuditEvent> recentEvents() {
        return Collections.unmodifiableList(new ArrayList<>(auditRing));
    }

    // ---- Static network helpers ----

    /**
     * Checks whether remoteIp is on the same IPv4 /24 subnet as localIp, or is loopback (127.0.0.1 / ::1).
     */
    public static boolean isSameSubnet24(String remoteIp, String localIp) {
        String remote = normalizeIp(remoteIp);
        if ("127.0.0.1".equals(remote) || "::1".equals(remote)) {
            return true;
        }
        int[] r = parseIpv4Octets(remote);
        int[] l = parseIpv4Octets(normalizeIp(localIp));
        if (r == null || l == null) {
            return false;
        }
        return r[0] == l[0] && r[1] == l[1] && r[2] == l[2];
    }

    // ---- Internal helpers ----

    private Decision denyAndRecord(long now, String ip, String reason) {
        blockedTotal++;
        appendAuditLocked(now, ip, reason);
        return Decision.deny(reason);
    }

    private void appendAuditLocked(long now, String ip, String reason) {
        while (auditRing.size() >= MAX_AUDIT_EVENTS) {
            auditRing.pollFirst();
        }
        auditRing.addLast(new AuditEvent(now, ip, reason));
    }

    private boolean isBannedLocked(String ip, long now) {
        Long until = bannedUntil.get(ip);
        if (until == null) return false;
        if (now >= until.longValue()) {
            bannedUntil.remove(ip);
            return false;
        }
        return true;
    }

    private void pruneExpiredBansLocked(long now) {
        Iterator<Map.Entry<String, Long>> it = bannedUntil.entrySet().iterator();
        while (it.hasNext()) {
            if (now >= it.next().getValue().longValue()) {
                it.remove();
            }
        }
    }

    private boolean consumeRateSlotLocked(Map<String, Deque<Long>> table, String ip, long now, int maxCount) {
        if (table.size() >= MAX_TRACKED_IPS && !table.containsKey(ip)) {
            pruneEmptyRateBucketsLocked(table, now);
            if (table.size() >= MAX_TRACKED_IPS) {
                table.clear();
            }
        }
        Deque<Long> q = table.get(ip);
        if (q == null) {
            q = new ArrayDeque<>(maxCount + 1);
            table.put(ip, q);
        }
        while (!q.isEmpty() && now - q.peekFirst().longValue() >= RATE_WINDOW_MS) {
            q.pollFirst();
        }
        if (q.size() >= maxCount) {
            return false;
        }
        q.addLast(now);
        return true;
    }

    private void pruneEmptyRateBucketsLocked(Map<String, Deque<Long>> table, long now) {
        Iterator<Map.Entry<String, Deque<Long>>> it = table.entrySet().iterator();
        while (it.hasNext()) {
            Deque<Long> q = it.next().getValue();
            while (!q.isEmpty() && now - q.peekFirst().longValue() >= RATE_WINDOW_MS) {
                q.pollFirst();
            }
            if (q.isEmpty()) {
                it.remove();
            }
        }
    }

    private static String normalizeIp(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("/")) {
            s = s.substring(1);
        }
        return s;
    }

    private static int[] parseIpv4Octets(String ip) {
        if (ip == null || ip.isEmpty() || ip.indexOf(':') >= 0) return null;
        int[] octets = new int[4];
        int part = 0;
        int start = 0;
        int len = ip.length();
        for (int i = 0; i <= len; i++) {
            if (i == len || ip.charAt(i) == '.') {
                if (part >= 4 || i == start || i - start > 3) return null;
                if (i - start > 1 && ip.charAt(start) == '0') return null;
                int val = 0;
                for (int j = start; j < i; j++) {
                    char c = ip.charAt(j);
                    if (c < '0' || c > '9') return null;
                    val = val * 10 + (c - '0');
                }
                if (val > 255) return null;
                octets[part++] = val;
                start = i + 1;
            }
        }
        return part == 4 ? octets : null;
    }
}
