package com.hdz.wearcontrol.desktop;

import com.hdz.wearcontrol.Controls;
import com.hdz.wearcontrol.FeatureId;
import com.hdz.wearcontrol.LocalMeshFirewall;
import com.hdz.wearcontrol.LocalMeshProtocol;

import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.text.DefaultCaret;
import javax.swing.text.PlainDocument;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

final class DesktopDeveloperPanel {
    private final DesktopMeshNode mesh;
    private final DesktopSession session;
    private final DesktopPrefs prefs;
    private final DesktopDialogs dialogs;
    private final PlainDocument trace;
    private final Consumer<String> meshTrace;
    private final Runnable onRemoteStateChange;
    private final DefaultListModel<DesktopMeshNode.DiscoveredPeer> peers = new DefaultListModel<>();
    private final DefaultListModel<String> allowlist = new DefaultListModel<>();

    private final JTextField ip = new JTextField();
    private final JTextField port = new JTextField(Integer.toString(LocalMeshProtocol.DEFAULT_TCP_PORT));
    private final JPasswordField pin = new JPasswordField();
    private final JTextField allowIp = new JTextField();
    private DesktopUi ui;
    private DesktopUi.Button off, host, client, scan, connect, disconnect, resetPin;
    private DesktopUi.Note hostStatus, clientStatus, firewallStats;
    private DesktopUi.Wrap remoteActions;
    private JTextArea audit, traceView;
    private JList<DesktopMeshNode.DiscoveredPeer> peerView;
    private JList<String> allowlistView;
    private JCheckBox firewallOn, subnetOnly, allowOnly, readOnly;
    private int requestedMode;
    private int meshRevision;
    private int scanRevision;
    private boolean scanning, connecting;
    private String remoteHost = "";
    private boolean connectionFailed;
    private LocalMeshProtocol.StateSnapshot remote = LocalMeshProtocol.StateSnapshot.disconnected();

    DesktopDeveloperPanel(DesktopMeshNode mesh, DesktopSession session, DesktopPrefs prefs,
                          DesktopDialogs dialogs, PlainDocument trace, Consumer<String> meshTrace,
                          Runnable onRemoteStateChange) {
        this.mesh = mesh;
        this.session = session;
        this.prefs = prefs;
        this.dialogs = dialogs;
        this.trace = trace;
        this.meshTrace = meshTrace;
        this.onRemoteStateChange = onRemoteStateChange;
    }

    JScrollPane view(DesktopUi ui) {
        if (peerView != null) peerView.setModel(new DefaultListModel<>());
        if (allowlistView != null) allowlistView.setModel(new DefaultListModel<>());
        if (traceView != null) traceView.setDocument(new PlainDocument());
        this.ui = ui;
        DesktopUi.Page page = ui.page();
        DesktopUi.Stack modes = ui.card(ui.t("局域网多端协同", "Local Mesh"));
        off = ui.button(ui.t("关闭协同", "Off"), false, this::stop);
        host = ui.button(ui.t("主机共享", "Share as host"), false, this::startHost);
        client = ui.button(ui.t("从机遥控", "Remote client"), false, this::enterClient);
        modes.add(ui.row(off, host, client));
        modes.add(ui.note(ui.t("局域网遥控永久禁止触发查找耳机。",
                "Find My Buds is permanently blocked over the LAN.")));
        page.add(modes);

        DesktopUi.Stack sharing = ui.card(ui.t("主机共享", "Host access"));
        hostStatus = ui.note("");
        resetPin = ui.button(ui.t("重置 4 位配对码", "Reset 4-digit PIN"), false, () -> {
            prefs.setMeshPin(mesh.regeneratePin());
            renderMesh();
        });
        sharing.add(hostStatus);
        sharing.add(resetPin);
        page.add(sharing);

        DesktopUi.Stack remoteCard = ui.card(ui.t("发现主机与远程控制", "Discover hosts and control remotely"));
        scan = ui.button("", false, this::toggleDiscovery);
        remoteCard.add(scan);
        JList<DesktopMeshNode.DiscoveredPeer> peerList = new JList<>(peers);
        peerView = peerList;
        ui.styleList(peerList);
        peerList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                     boolean selected, boolean focus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, "", index, selected, focus);
                label.putClientProperty("html.disable", Boolean.TRUE);
                if (value instanceof DesktopMeshNode.DiscoveredPeer) {
                    DesktopMeshNode.DiscoveredPeer peer = (DesktopMeshNode.DiscoveredPeer) value;
                    label.setText(peer.hostName + " · " + peer.ip + ":" + peer.port
                            + (peer.deviceName.isBlank() ? "" : " · " + peer.deviceName));
                    label.setToolTipText(label.getText());
                }
                return label;
            }
        });
        peerList.addListSelectionListener(e -> {
            DesktopMeshNode.DiscoveredPeer peer = peerList.getSelectedValue();
            if (!e.getValueIsAdjusting() && peer != null) {
                ip.setText(peer.ip);
                port.setText(Integer.toString(peer.port));
            }
        });
        remoteCard.add(ui.box(peerList, 68));
        formField(remoteCard, ui.t("主机 IP", "Host IP"), ip);
        formField(remoteCard, ui.t("端口", "Port"), port);
        formField(remoteCard, ui.t("4 位配对码", "4-digit PIN"), pin);
        connect = ui.button(ui.t("连接主机", "Connect host"), true, this::connectClient);
        disconnect = ui.button(ui.t("断开", "Disconnect"), false, this::enterClient);
        remoteCard.add(ui.row(connect, disconnect));
        clientStatus = ui.note("");
        remoteCard.add(clientStatus);
        remoteActions = ui.row(ui.button(ui.strings.noise(Controls.MODE_ANC), () -> mesh.sendRemoteNoiseMode(Controls.MODE_ANC)),
                ui.button(ui.strings.noise(Controls.MODE_TRANS), () -> mesh.sendRemoteNoiseMode(Controls.MODE_TRANS)),
                ui.button(ui.strings.noise(Controls.MODE_OFF), () -> mesh.sendRemoteNoiseMode(Controls.MODE_OFF)),
                ui.button(ui.t("刷新", "Refresh"), mesh::sendRemoteRefresh));
        remoteCard.add(remoteActions);
        page.add(remoteCard);
        page.add(firewallCard());
        page.add(traceCard());
        renderMesh();
        renderFirewall();
        return ui.scroll(page);
    }

    private void formField(DesktopUi.Stack card, String title, JTextField field) {
        JLabel label = ui.label(title, 12, false, ui.theme.muted);
        label.setLabelFor(field);
        card.add(label);
        card.add(ui.field(field));
    }

    private DesktopUi.Stack firewallCard() {
        DesktopUi.Stack card = ui.card(ui.t("内网防火墙", "LAN firewall"));
        firewallOn = ui.check(ui.t("启用内网防火墙", "Enable LAN firewall"), false);
        subnetOnly = ui.check(ui.t("仅允许同子网 /24", "Only allow the same /24 subnet"), false);
        allowOnly = ui.check(ui.t("仅允许白名单 IP", "Allow only listed IP addresses"), false);
        readOnly = ui.check(ui.t("只读观察模式", "Read-only monitoring"), false);
        for (JCheckBox box : new JCheckBox[] {firewallOn, subnetOnly, allowOnly, readOnly}) {
            card.add(box);
            box.addActionListener(e -> {
                mesh.firewall().configure(firewallOn.isSelected(), subnetOnly.isSelected(),
                        allowOnly.isSelected(), readOnly.isSelected());
                saveFirewall();
            });
        }
        JList<String> addresses = new JList<>(allowlist);
        allowlistView = addresses;
        ui.styleList(addresses);
        card.add(ui.box(addresses, 68));
        formField(card, ui.t("白名单 IP", "Allowlist IP address"), allowIp);
        card.add(ui.row(ui.button(ui.t("添加", "Add"), () -> {
            if (!mesh.firewall().addAllowedIp(allowIp.getText().trim())) {
                dialogs.info(ui.t("白名单", "Allowlist"),
                        ui.t("请输入有效的私有局域网 IP 地址。", "Enter a valid private LAN IP address."),
                        JOptionPane.WARNING_MESSAGE);
                return;
            }
            allowIp.setText("");
            saveFirewall();
        }), ui.button(ui.t("移除所选", "Remove selected"), () -> {
            String selected = addresses.getSelectedValue();
            if (selected != null) {
                mesh.firewall().removeAllowedIp(selected);
                saveFirewall();
            }
        }), ui.button(ui.t("清空白名单", "Clear list"), () -> {
            mesh.firewall().clearAllowlist();
            saveFirewall();
        })));
        firewallStats = ui.note("");
        card.add(firewallStats);
        card.add(ui.button(ui.t("解除全部封禁", "Clear all bans"), () -> {
            mesh.firewall().clearBans();
            renderFirewall();
        }));
        card.add(ui.label(ui.t("审计日志", "Audit log"), 12, true, ui.theme.text));
        audit = ui.logArea();
        card.add(ui.box(audit, 118));
        return card;
    }

    private DesktopUi.Stack traceCard() {
        DesktopUi.Stack card = ui.card(ui.t("协议调试日志", "Protocol trace"));
        JTextArea console = ui.logArea();
        traceView = console;
        console.setDocument(trace);
        ((DefaultCaret) console.getCaret()).setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
        console.setCaretPosition(trace.getLength());
        card.add(ui.box(console, 186));
        card.add(ui.row(ui.button(ui.t("复制日志", "Copy log"), () -> {
            try {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(console.getText()), null);
            } catch (IllegalStateException | SecurityException ex) {
                dialogs.info(ui.t("复制日志", "Copy log"),
                        ui.t("剪贴板不可用，请重试。", "The clipboard is unavailable. Try again."),
                        JOptionPane.WARNING_MESSAGE);
            }
        }), ui.button(ui.t("清空", "Clear"), () -> console.setText(""))));
        return card;
    }

    private void resetConnection(int mode) {
        ++meshRevision;
        stopDiscovery();
        mesh.stopAll();
        requestedMode = mode;
        remote = LocalMeshProtocol.StateSnapshot.disconnected();
        remoteHost = "";
        connectionFailed = false;
        connecting = false;
        renderMesh();
        onRemoteStateChange.run();
    }

    private void stop() { resetConnection(DesktopMeshNode.MODE_OFF); }

    private void startHost() {
        if (requestedMode == DesktopMeshNode.MODE_HOST && mesh.mode() == DesktopMeshNode.MODE_HOST) return;
        resetConnection(DesktopMeshNode.MODE_HOST);
        int revision = meshRevision;
        mesh.pushSnapshot(session.meshSnapshot());
        mesh.startHost(new DesktopMeshNode.HostCommandSink() {
            @Override public void onRemoteNoiseMode(int mode) { session.setNoiseMode(mode); }
            @Override public void onRemoteRefresh() { session.refresh(); }
            @Override public void onRemoteFeature(FeatureId id, int value) {
                if (id != FeatureId.FIND_MY_BUDS) session.writeFeature(id, value);
            }
        }, new DesktopMeshNode.HostListener() {
            @Override public void onHostStarted(String ip, int port, String pin) {
                onEdt(revision, () -> {
                    renderMesh();
                    meshTrace.accept("Host listening on " + ip + ":" + port);
                });
            }
            @Override public void onHostClientCountChanged(int count) { onEdt(revision, DesktopDeveloperPanel.this::renderMesh); }
            @Override public void onHostFirewallChanged() { onEdt(revision, DesktopDeveloperPanel.this::renderFirewall); }
            @Override public void onHostStopped() {
                onEdt(revision, () -> {
                    requestedMode = DesktopMeshNode.MODE_OFF;
                    renderMesh();
                });
            }
            @Override public void onHostError(String reason) {
                onEdt(revision, () -> {
                    connectionFailed = true;
                    renderMesh();
                    meshTrace.accept("Host error: " + reason);
                });
            }
        });
    }

    private void enterClient() {
        resetConnection(DesktopMeshNode.MODE_CLIENT);
        toggleDiscovery();
    }

    private void toggleDiscovery() {
        if (scanning) {
            stopDiscovery();
            return;
        }
        if (requestedMode != DesktopMeshNode.MODE_CLIENT) resetConnection(DesktopMeshNode.MODE_CLIENT);
        scanning = true;
        int revision = ++scanRevision;
        peers.clear();
        renderMesh();
        mesh.startDiscovery(new DesktopMeshNode.DiscoveryListener() {
            @Override public void onPeersUpdated(List<DesktopMeshNode.DiscoveredPeer> updated) {
                SwingUtilities.invokeLater(() -> {
                    if (!scanning || revision != scanRevision) return;
                    peers.clear();
                    updated.forEach(peers::addElement);
                });
            }
            @Override public void onDiscoveryError(String reason) {
                SwingUtilities.invokeLater(() -> {
                    if (!scanning || revision != scanRevision) return;
                    stopDiscovery();
                    meshTrace.accept("Discovery error: " + reason);
                    clientStatus.setText(ui.t("UDP 扫描失败，详情见协议日志。", "UDP discovery failed. See the protocol trace."));
                });
            }
        });
    }

    void stopDiscovery() {
        if (!scanning) return;
        ++scanRevision;
        scanning = false;
        mesh.stopDiscovery();
        renderMesh();
    }

    private void connectClient() {
        String address = ip.getText().trim();
        String code = new String(pin.getPassword()).trim();
        int tcpPort;
        try {
            tcpPort = Integer.parseInt(port.getText().trim());
        } catch (NumberFormatException ex) {
            tcpPort = -1;
        }
        if (!LocalMeshProtocol.isPrivateLanAddress(address) || tcpPort < 1 || tcpPort > 65535
                || !LocalMeshProtocol.isValidPin(code)) {
            dialogs.info(ui.t("连接主机", "Connect host"),
                    ui.t("请输入私有局域网 IP、有效端口（1–65535）和 4 位数字配对码。",
                            "Enter a private LAN IP address, a port from 1 to 65535, and a 4-digit PIN."),
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        resetConnection(DesktopMeshNode.MODE_CLIENT);
        connecting = true;
        int revision = meshRevision;
        renderMesh();
        mesh.connectClient(address, tcpPort, code, new DesktopMeshNode.ClientListener() {
            @Override public void onClientConnected(String hostName) {
                onEdt(revision, () -> {
                    remoteHost = hostName;
                    connecting = false;
                    renderMesh();
                    onRemoteStateChange.run();
                });
            }
            @Override public void onClientState(LocalMeshProtocol.StateSnapshot state) {
                onEdt(revision, () -> {
                    remote = state;
                    renderMesh();
                });
            }
            @Override public void onClientDisconnected(String reason) {
                onEdt(revision, () -> {
                    connecting = false;
                    remote = LocalMeshProtocol.StateSnapshot.disconnected();
                    remoteHost = "";
                    renderMesh();
                    onRemoteStateChange.run();
                    meshTrace.accept("Remote disconnected: " + reason);
                });
            }
            @Override public void onClientError(String reason) {
                onEdt(revision, () -> {
                    connecting = false;
                    connectionFailed = true;
                    renderMesh();
                    meshTrace.accept("Remote error: " + reason);
                });
            }
        });
    }

    private void onEdt(int revision, Runnable action) {
        SwingUtilities.invokeLater(() -> {
            if (revision == meshRevision) action.run();
        });
    }

    private void renderMesh() {
        if (off == null) return;
        off.setSelected(requestedMode == DesktopMeshNode.MODE_OFF);
        host.setSelected(requestedMode == DesktopMeshNode.MODE_HOST);
        client.setSelected(requestedMode == DesktopMeshNode.MODE_CLIENT);
        int listen = mesh.listenPort();
        boolean sharing = requestedMode == DesktopMeshNode.MODE_HOST && listen > 0;
        String address = sharing ? mesh.localLanIp() + ":" + listen + "   ·   PIN " + mesh.pin() + "\n"
                + ui.t("已连接从机 ", "Connected clients ") + mesh.clientCount()
                : requestedMode == DesktopMeshNode.MODE_HOST ? ui.t("正在启动共享…", "Starting host…")
                : ui.t("共享已关闭", "Sharing is off");
        hostStatus.setText(address);
        resetPin.setEnabled(sharing);
        boolean authed = mesh.isClientConnected();
        scan.setText(scanning ? ui.t("停止扫描", "Stop scan") : ui.t("扫描局域网 (UDP 19846)", "Scan LAN (UDP 19846)"));
        connect.setEnabled(!connecting && !authed);
        disconnect.setEnabled(connecting || authed);
        for (Component action : remoteActions.getComponents()) action.setEnabled(authed);
        String status = ui.t("远程控制未连接", "Remote control disconnected");
        if (connecting) status = ui.t("正在连接主机…", "Connecting to host…");
        else if (authed) {
            status = ui.t("主机 ", "Host ") + remoteHost + "\n" + (remote.connected
                    ? remote.deviceName + "\n" + ui.strings.batterySummary(remote.caps, remote.left, remote.right,
                            remote.box, remote.charge) + " · " + ui.strings.noise(remote.mode)
                    : ui.t("主机耳机未连接", "Host earbuds disconnected"));
        } else if (connectionFailed) status = ui.t("连接失败，详情见协议日志。", "Connection failed. See the protocol trace.");
        clientStatus.setText(status);
        hostStatus.getParent().revalidate();
        clientStatus.getParent().revalidate();
    }

    private void saveFirewall() {
        prefs.saveFirewall(mesh.firewall());
        renderFirewall();
    }

    private void renderFirewall() {
        LocalMeshFirewall fw = mesh.firewall();
        firewallOn.setSelected(fw.isEnabled());
        subnetOnly.setSelected(fw.isSameSubnetOnly());
        allowOnly.setSelected(fw.isAllowlistOnly());
        readOnly.setSelected(fw.isReadOnlyMode());
        List<String> snapshot = List.copyOf(fw.allowlistSnapshot());
        if (!snapshot.equals(java.util.Collections.list(allowlist.elements()))) {
            allowlist.clear();
            snapshot.forEach(allowlist::addElement);
        }
        firewallStats.setText(ui.t("已拦截 ", "Blocked ") + fw.blockedCount() + "   ·   "
                + ui.t("封禁 IP ", "Banned IPs ") + fw.bannedIpCount());
        List<LocalMeshFirewall.AuditEvent> events = fw.recentEvents();
        SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss");
        StringBuilder log = new StringBuilder();
        for (int i = events.size() - 1; i >= 0; i--) {
            LocalMeshFirewall.AuditEvent event = events.get(i);
            log.append(time.format(new Date(event.timeMs))).append("  ").append(event.ip).append("  ")
                    .append(auditReason(event.reason)).append('\n');
        }
        audit.setText(log.length() == 0 ? ui.t("暂无拦截记录", "No audit events") : log.toString());
        audit.setCaretPosition(0);
    }

    private String auditReason(String reason) {
        switch (reason) {
            case LocalMeshFirewall.REASON_NON_PRIVATE_IP: return ui.t("非私有 IP", "Non-private IP");
            case LocalMeshFirewall.REASON_IP_BANNED: return ui.t("IP 已封禁", "IP banned");
            case LocalMeshFirewall.REASON_CROSS_SUBNET: return ui.t("跨子网访问", "Cross-subnet access");
            case LocalMeshFirewall.REASON_NOT_IN_ALLOWLIST: return ui.t("IP 不在白名单", "IP outside allowlist");
            case LocalMeshFirewall.REASON_CONN_RATE_LIMIT: return ui.t("连接过于频繁", "Connection rate exceeded");
            case LocalMeshFirewall.REASON_AUTH_FAIL: return ui.t("配对码错误", "Incorrect PIN");
            case LocalMeshFirewall.REASON_AUTH_LOCKOUT: return ui.t("多次认证失败", "Authentication lockout");
            case LocalMeshFirewall.REASON_FIND_BUDS_HARD_BLOCK: return ui.t("远程查找已拦截", "Remote Find My Buds blocked");
            case LocalMeshFirewall.REASON_READ_ONLY_MODE: return ui.t("只读模式", "Read-only mode");
            case LocalMeshFirewall.REASON_CMD_RATE_LIMIT: return ui.t("命令过于频繁", "Command rate exceeded");
            default: return reason;
        }
    }
}
