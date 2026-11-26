package com.hdz.wearcontrol.desktop;

import com.hdz.wearcontrol.Controls;
import com.hdz.wearcontrol.DesktopCoreBridge.BrandModuleEntry;
import com.hdz.wearcontrol.DesktopCoreBridge.StateSnapshotView;
import com.hdz.wearcontrol.LocalMeshProtocol;
import com.hdz.wearcontrol.desktop.DesktopSession.ConnectionPhase;
import com.hdz.wearcontrol.desktop.WindowsBluetoothBridge.BtDevice;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.text.BadLocationException;
import javax.swing.text.PlainDocument;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class WearEarsDesktopApp {
    private static final int WIDTH = 410;
    private static final int HEIGHT = 680;
    private static final int TRACE_LIMIT = 180_000;
    private static final DateTimeFormatter TRACE_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private enum View { MAIN, SETTINGS, DEV, SPATIAL_LAB }
    private enum Scan { STARTUP, LIST, RECONNECT }

    private final DesktopInstanceGuard instance;
    private final DesktopPrefs prefs;
    private final DesktopDriverRegistry registry = new DesktopDriverRegistry();
    private final WindowsBluetoothBridge bridge = new WindowsBluetoothBridge();
    private final DesktopSession session = new DesktopSession(registry, bridge);
    private final DesktopMeshNode mesh = new DesktopMeshNode();
    private final WindowsStartupManager startup = new WindowsStartupManager();
    private final PlainDocument trace = new PlainDocument();
    private final JFrame frame = new JFrame("WearEars");
    private final DesktopDialogs dialogs = new DesktopDialogs(frame);
    private final DesktopFeatureDeck features;
    private final DesktopDeveloperPanel developer;
    private final DesktopSpatialLab lab = new DesktopSpatialLab(dialogs);
    private final DesktopTrayManager tray;
    private final CardLayout cards = new CardLayout();
    private final JPanel pages = new JPanel(cards);
    private final JPanel header = new JPanel(new BorderLayout(8, 0));
    private final JPanel root = new JPanel(new BorderLayout());
    private final Map<View, JScrollPane> scrolls = new EnumMap<>(View.class);
    private final Timer blurHide = new Timer(170, e -> hideIfUnfocused());
    private final Timer reconnect = new Timer(800, e -> retryAutoConnection());

    private DesktopUi ui;
    private View view = View.MAIN;
    private boolean closing, consentComplete, wakePending, scanned, scanning, scanFailed, connectFailed, populatingDevices;
    private List<BtDevice> devices = List.of();
    private BtDevice selected;
    private String selectedBrand;
    private ConnectionPhase phase = ConnectionPhase.DISCONNECTED;
    private StateSnapshotView state = session.snapshot();
    private ConnectionRequest activeRequest, pendingRetry;

    private JComboBox<DeviceChoice> deviceCombo;
    private JComboBox<BrandChoice> brandCombo;
    private DesktopUi.Button scanButton, connectButton, disconnectButton, pinButton;
    private JCheckBox startupBox, pinSetting, startMinimizedSetting;
    private DesktopUi.Note startupStatus;
    private boolean startupBusy, startupRead, startupRegistered, startupFailed;
    private DesktopBatteryPanel battery;
    private DesktopNoiseSegmentedBar noise;
    private JLabel phaseText;
    private StatusDot statusDot;
    private DesktopUi.Note deviceName, metadata;
    private DesktopUi.Button quickConnect;

    private WearEarsDesktopApp(DesktopInstanceGuard instance, DesktopPrefs prefs) {
        this.instance = instance;
        this.prefs = prefs;
        selectedBrand = registry.findByKey(prefs.lastBrand()) == null ? DesktopDriverRegistry.BRAND_AUTO : prefs.lastBrand();
        features = new DesktopFeatureDeck(prefs, session, dialogs);
        developer = new DesktopDeveloperPanel(mesh, session, prefs, dialogs, trace,
                message -> appendTrace("Mesh", message), this::updateTray);
        tray = new DesktopTrayManager(frame, this::togglePanel, this::refreshStatus, this::quickNoise,
                () -> {
                    showPanel(DesktopTrayManager.pointerLocation());
                    if (consentComplete) showView(View.SETTINGS);
                }, this::shutdown, blurHide::restart, message -> appendTrace("Tray", message));
    }

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        AtomicReference<WearEarsDesktopApp> running = new AtomicReference<>();
        DesktopPrefs prefs = new DesktopPrefs();
        DesktopInstanceGuard instance;
        boolean[] wakeBeforeLaunch = {false}; // Accessed on the EDT.
        try {
            instance = DesktopInstanceGuard.acquire(DesktopPrefs.userDirectory().resolve("desktop.lock"),
                    () -> SwingUtilities.invokeLater(() -> {
                        WearEarsDesktopApp app = running.get();
                        if (app == null) wakeBeforeLaunch[0] = true;
                        else app.wake();
                    }));
        } catch (IOException | InterruptedException | SecurityException ex) {
            System.err.println("[WearEars] Single-instance startup failed: " + ex);
            DesktopUi ui = new DesktopUi(DesktopTheme.forKey(prefs.uiStyle()), prefs.language());
            SwingUtilities.invokeLater(() -> {
                ui.applyDefaults();
                JOptionPane.showMessageDialog(null, ui.note(ui.t(
                        "无法启动 WearEars 或唤醒已运行的实例。请检查通知区或稍后重试。",
                        "WearEars could not start or wake its running instance. Check the notification area or try again.")),
                        "WearEars", JOptionPane.ERROR_MESSAGE);
            });
            return;
        }
        if (instance == null) return;
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
                WearEarsDesktopApp app = new WearEarsDesktopApp(instance, prefs);
                app.wakePending = wakeBeforeLaunch[0];
                running.set(app);
                app.launch();
            } catch (Exception ex) {
                instance.close();
                System.err.println("[WearEars] Desktop startup failed");
                ex.printStackTrace();
                System.exit(1);
            }
        });
    }

    private void launch() {
        prefs.applyToFirewall(mesh.firewall());
        if (LocalMeshProtocol.isValidPin(prefs.meshPin())) mesh.setPin(prefs.meshPin());
        else prefs.setMeshPin(mesh.pin());
        blurHide.setRepeats(false);
        reconnect.setRepeats(false);

        frame.setUndecorated(true);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setIconImage(DesktopTrayManager.image(new Color(0x8394A9)));
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { closePanel(); }
            @Override public void windowIconified(WindowEvent e) {
                if (tray.installed() && !dialogs.active()) hidePanel();
                else lab.stop();
            }
        });
        frame.addWindowFocusListener(new WindowAdapter() {
            @Override public void windowLostFocus(WindowEvent e) {
                if (!owned(e.getOppositeWindow()) && !dialogs.active()) blurHide.restart();
            }
            @Override public void windowGainedFocus(WindowEvent e) { blurHide.stop(); }
        });
        root.add(header, BorderLayout.NORTH);
        root.add(pages, BorderLayout.CENTER);
        frame.setContentPane(root);
        frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "back");
        frame.getRootPane().getActionMap().put("back", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (!comboPopupOpen(pages)) back();
            }
        });

        rebuildAppearance();
        if (tray.install()) frame.setType(Window.Type.UTILITY);
        startMinimizedSetting.setEnabled(tray.installed());
        updateTray();
        bindSession();

        boolean firstRun = !prefs.disclaimerAccepted();
        if (firstRun || !prefs.startMinimized() || !tray.installed() || wakePending) {
            reveal(DesktopTrayManager.pointerLocation());
        }
        if (firstRun) {
            boolean accepted = dialogs.confirm(disclaimerTitle(), disclaimerText(),
                    ui.t("同意并继续", "Agree and continue"), ui.t("退出", "Exit"));
            if (!accepted || closing) {
                shutdown();
                return;
            }
            prefs.setDisclaimerAccepted(true);
        }
        consentComplete = true;
        if (wakePending) reveal(DesktopTrayManager.pointerLocation());
        else if (firstRun && prefs.startMinimized() && tray.installed()) hidePanel();
        wakePending = false;
        scan(Scan.STARTUP);
    }

    private void rebuildAppearance() {
        Map<View, Integer> scrollPositions = new EnumMap<>(View.class);
        scrolls.forEach((name, scroll) -> scrollPositions.put(name, scroll.getVerticalScrollBar().getValue()));
        ui = new DesktopUi(DesktopTheme.forKey(prefs.uiStyle()), prefs.language());
        ui.applyDefaults();
        dialogs.apply(ui);
        root.setBackground(ui.theme.page);
        root.setBorder(BorderFactory.createLineBorder(ui.theme.border));
        pages.setBackground(ui.theme.page);
        pages.removeAll();
        scrolls.clear();
        addPage(View.MAIN, buildMain());
        addPage(View.SETTINGS, buildSettings());
        addPage(View.DEV, developer.view(ui));
        addPage(View.SPATIAL_LAB, lab.view(ui));
        cards.show(pages, view.name());
        updateHeader();
        renderState();
        root.revalidate();
        root.repaint();
        SwingUtilities.invokeLater(() -> scrollPositions.forEach((name, pos) -> {
            JScrollPane scroll = scrolls.get(name);
            if (scroll != null) scroll.getVerticalScrollBar().setValue(pos);
        }));
    }

    private void addPage(View page, JScrollPane scroll) {
        scrolls.put(page, scroll);
        pages.add(scroll, page.name());
    }

    private JScrollPane buildMain() {
        DesktopUi.Page page = ui.page();
        DesktopUi.Stack connection = ui.card("");
        statusDot = new StatusDot();
        phaseText = ui.label("", 12, true, ui.theme.muted);
        connection.add(ui.row(statusDot, phaseText));
        deviceName = ui.note("");
        deviceName.setFont(DesktopTheme.font(Font.BOLD, 20));
        deviceName.setForeground(ui.theme.text);
        metadata = ui.note("");
        connection.add(deviceName);
        connection.add(metadata);
        quickConnect = ui.button("", true, this::primaryConnectionAction);
        DesktopUi.Button choose = ui.button(ui.t("切换设备", "Choose device"), false, () -> showView(View.SETTINGS));
        connection.add(ui.row(quickConnect, choose));
        page.add(connection);

        DesktopUi.Stack batterySection = ui.stack(6);
        batterySection.add(ui.label(ui.t("电量", "Battery"), 14, true, ui.theme.text));
        battery = new DesktopBatteryPanel(ui);
        batterySection.add(battery);
        page.add(batterySection);

        DesktopUi.Stack controls = ui.card(ui.t("降噪控制", "Noise control"));
        noise = new DesktopNoiseSegmentedBar(ui, session::setNoiseMode);
        controls.add(noise);
        page.add(controls);
        page.add(features.view(ui));

        DesktopUi.Stack spatial = ui.card(ui.t("360° 双耳试听实验室", "360° Binaural Lab"));
        spatial.add(ui.button(ui.t("进入试听实验室", "Open binaural lab"), () -> showView(View.SPATIAL_LAB)));
        page.add(spatial);
        return ui.scroll(page);
    }

    private JScrollPane buildSettings() {
        DesktopUi.Page page = ui.page();
        DesktopUi.Stack appearance = ui.card(ui.t("外观风格", "Appearance"));
        DesktopUi.Wrap styles = ui.row();
        for (DesktopTheme theme : DesktopTheme.values()) {
            styles.add(ui.button(ui.t(theme.zh, theme.en), theme == ui.theme, () -> {
                if (theme == ui.theme) return;
                prefs.setUiStyle(theme.key);
                rebuildAppearance();
            }));
        }
        appearance.add(styles);
        page.add(appearance);

        DesktopUi.Stack language = ui.card(ui.t("语言", "Language"));
        language.add(ui.row(ui.button("简体中文", !ui.strings.english, () -> setLanguage("zh")),
                ui.button("English", ui.strings.english, () -> setLanguage("en"))));
        page.add(language);

        DesktopUi.Stack bluetooth = ui.card(ui.t("蓝牙设备与协议品牌", "Bluetooth device and protocol"));
        JLabel deviceLabel = ui.label(ui.t("已配对蓝牙设备", "Paired Bluetooth device"), 12, false, ui.theme.muted);
        deviceCombo = ui.combo(new JComboBox<>());
        deviceLabel.setLabelFor(deviceCombo);
        bluetooth.add(deviceLabel);
        bluetooth.add(deviceCombo);
        scanButton = ui.button(ui.t("刷新列表", "Refresh list"), false, () -> scan(Scan.LIST));
        bluetooth.add(ui.row(scanButton));
        JLabel brandLabel = ui.label(ui.t("协议品牌", "Protocol brand"), 12, false, ui.theme.muted);
        brandCombo = ui.combo(new JComboBox<>());
        brandLabel.setLabelFor(brandCombo);
        bluetooth.add(brandLabel);
        bluetooth.add(brandCombo);
        brandCombo.addItem(new BrandChoice(DesktopDriverRegistry.BRAND_AUTO, ui.t("自动识别（9 大品牌）", "Auto-detect (9 brands)")));
        for (BrandModuleEntry entry : registry.allBrands()) {
            brandCombo.addItem(new BrandChoice(entry.key, ui.strings.brand(entry.key, entry.displayName)));
        }
        selectBrand(selectedBrand);
        brandCombo.addActionListener(e -> {
            BrandChoice choice = (BrandChoice) brandCombo.getSelectedItem();
            if (choice != null) selectedBrand = choice.key;
        });
        repopulateDevices();
        deviceCombo.addActionListener(e -> {
            if (populatingDevices) return;
            DeviceChoice choice = (DeviceChoice) deviceCombo.getSelectedItem();
            selected = choice == null ? null : choice.device;
            cancelAutoRetry();
            connectFailed = false;
            renderState();
        });
        connectButton = ui.button(ui.t("连接所选", "Connect selected"), true, this::connectSelected);
        disconnectButton = ui.button(ui.t("断开连接", "Disconnect"), false, this::disconnect);
        bluetooth.add(ui.row(connectButton, disconnectButton));
        page.add(bluetooth);

        DesktopUi.Stack behavior = ui.card(ui.t("托盘与启动", "Tray and startup"));
        JCheckBox auto = ui.check(ui.t("启动时自动连接在线耳机", "Connect to online earbuds at startup"), prefs.autoConnect());
        auto.addActionListener(e -> {
            prefs.setAutoConnect(auto.isSelected());
            if (!auto.isSelected()) cancelAutoRetry();
        });
        startMinimizedSetting = ui.check(ui.t("启动时最小化到托盘", "Start minimized to the tray"), prefs.startMinimized());
        startMinimizedSetting.setEnabled(tray.installed());
        startMinimizedSetting.addActionListener(e -> prefs.setStartMinimized(startMinimizedSetting.isSelected()));
        JCheckBox autoHide = ui.check(ui.t("失焦时自动收起浮窗", "Hide panel when it loses focus"), prefs.autoHideOnBlur());
        autoHide.addActionListener(e -> prefs.setAutoHideOnBlur(autoHide.isSelected()));
        pinSetting = ui.check(ui.t("固定面板", "Pin the panel"), prefs.pinned());
        pinSetting.addActionListener(e -> {
            prefs.setPinned(pinSetting.isSelected());
            updatePin();
        });
        startupBox = ui.check(ui.t("开机自动启动", "Start with Windows"), false);
        startupBox.addActionListener(e -> updateStartup(startupBox.isSelected()));
        startupStatus = ui.note("");
        behavior.add(auto);
        behavior.add(startMinimizedSetting);
        behavior.add(autoHide);
        behavior.add(pinSetting);
        behavior.add(startupBox);
        behavior.add(startupStatus);
        renderStartup();
        page.add(behavior);

        DesktopUi.Stack dev = ui.card("");
        dev.add(ui.button(ui.t("开发者选项  ›", "Developer options  ›"), () -> showView(View.DEV)));
        dev.add(ui.note(ui.t("局域网多端协同 · 内网防火墙 · 协议调试日志",
                "Local Mesh · LAN firewall · Protocol trace")));
        page.add(dev);

        DesktopUi.Stack about = ui.card(ui.t("关于 WearEars", "About WearEars"));
        about.add(ui.note("v0.5.0-exp-C (Windows)"));
        DesktopUi.Button exit = ui.button(ui.t("完全退出 WearEars", "Exit WearEars"), false, this::shutdown);
        exit.markDanger();
        about.add(ui.row(ui.button(ui.t("查看免责声明", "Read disclaimer"),
                () -> dialogs.info(disclaimerTitle(), disclaimerText(), JOptionPane.INFORMATION_MESSAGE)), exit));
        page.add(about);
        return ui.scroll(page);
    }

    private void setLanguage(String language) {
        if (prefs.language().equals(language)) return;
        prefs.setLanguage(language);
        rebuildAppearance();
    }

    private void updateHeader() {
        header.removeAll();
        header.setOpaque(true);
        header.setBackground(ui.theme.page);
        header.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, ui.theme.border),
                BorderFactory.createEmptyBorder(9, 12, 9, 10)));
        if (view != View.MAIN) header.add(ui.icon(DesktopIcons.BACK, ui.t("返回", "Back"), this::back), BorderLayout.WEST);
        DesktopTheme theme = ui.theme;
        JComponent title = new JPanel() {
            @Override protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                Graphics2D g = DesktopUi.graphics(graphics);
                try {
                    boolean supreme = theme == DesktopTheme.SUPREME && view == View.MAIN;
                    if (supreme) {
                        g.setColor(theme.danger);
                        g.fill(DesktopUi.slanted(0, 8, 20, 17, 6));
                    }
                    int start = supreme ? 26 : 0;
                    DesktopUi.drawText(g, heading(), DesktopTheme.font(Font.BOLD | (supreme ? Font.ITALIC : 0),
                            view == View.MAIN ? 25 : 18), theme.text, start, 0, getWidth() - start - 4, getHeight(), -1);
                } finally {
                    g.dispose();
                }
            }
        };
        title.setOpaque(false);
        title.setPreferredSize(new Dimension(120, 34));
        title.getAccessibleContext().setAccessibleName(heading());
        title.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        MouseAdapter mover = new MouseAdapter() {
            private Point offset;
            @Override public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    offset = new Point(e.getXOnScreen() - frame.getX(), e.getYOnScreen() - frame.getY());
                }
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (offset != null) frame.setLocation(e.getXOnScreen() - offset.x, e.getYOnScreen() - offset.y);
            }
            @Override public void mouseReleased(MouseEvent e) { offset = null; }
        };
        title.addMouseListener(mover);
        title.addMouseMotionListener(mover);
        header.add(title, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        actions.setOpaque(false);
        DesktopUi.Button refresh = ui.icon(DesktopIcons.REFRESH, ui.t("刷新 / 重连", "Refresh / reconnect"), this::refreshStatus);
        pinButton = ui.icon(DesktopIcons.PIN, "", () -> {
            prefs.setPinned(!prefs.pinned());
            updatePin();
        });
        DesktopUi.Button settings = ui.icon(DesktopIcons.SETTINGS, ui.t("设置", "Settings"), () -> showView(View.SETTINGS));
        if (view == View.MAIN) {
            actions.add(refresh);
            actions.add(pinButton);
            actions.add(settings);
        }
        actions.add(ui.icon(DesktopIcons.CLOSE, ui.t("收起面板", "Close panel"), this::closePanel));
        header.add(actions, BorderLayout.EAST);
        updatePin();
        header.revalidate();
        header.repaint();
    }

    private String heading() {
        switch (view) {
            case SETTINGS: return ui.t("设置", "Settings");
            case DEV: return ui.t("开发者选项", "Developer options");
            case SPATIAL_LAB: return ui.t("360° 双耳试听", "360° Binaural Lab");
            default: return "WearEars";
        }
    }

    private void updatePin() {
        if (pinButton == null) return;
        pinButton.setSelected(prefs.pinned());
        DesktopUi.nameIcon(pinButton, prefs.pinned() ? ui.t("取消固定", "Unpin panel") : ui.t("固定面板", "Pin panel"));
        if (pinSetting != null) pinSetting.setSelected(prefs.pinned());
    }

    private void showView(View next) {
        if (view != next) {
            if (view == View.SPATIAL_LAB) lab.stop();
            if (view == View.DEV) developer.stopDiscovery();
            view = next;
            cards.show(pages, next.name());
            updateHeader();
        }
        if (next == View.SETTINGS && !startupRead && startup.available()) updateStartup(null);
    }

    private void back() {
        if (view == View.MAIN) closePanel();
        else showView(view == View.DEV ? View.SETTINGS : View.MAIN);
    }

    private void wake() {
        if (!consentComplete) {
            wakePending = true;
            return;
        }
        showPanel(DesktopTrayManager.pointerLocation());
    }

    private void showPanel(Point near) {
        if (closing || dialogs.active()) return;
        reveal(near);
        if (phase == ConnectionPhase.DISCONNECTED) scan(Scan.LIST);
    }

    private void reveal(Point near) {
        blurHide.stop();
        if (!frame.isVisible()) {
            frame.setBounds(DesktopTrayManager.panelBounds(near, WIDTH, HEIGHT));
            frame.setVisible(true);
        }
        frame.setExtendedState(JFrame.NORMAL);
        frame.toFront();
        frame.requestFocus();
    }

    private void togglePanel(Point near) {
        blurHide.stop();
        if (!consentComplete || dialogs.active()) return;
        if (frame.isVisible() && (frame.getExtendedState() & JFrame.ICONIFIED) == 0) hidePanel();
        else showPanel(near);
    }

    private void closePanel() {
        if (dialogs.active() || closing) return;
        if (tray.installed()) hidePanel();
        else shutdown();
    }

    private void hidePanel() {
        blurHide.stop();
        lab.stop();
        developer.stopDiscovery();
        frame.setVisible(false);
    }

    private void hideIfUnfocused() {
        if (!consentComplete || closing || !tray.installed() || !prefs.autoHideOnBlur() || prefs.pinned()
                || !frame.isShowing() || tray.menuOpen() || dialogs.active() || comboPopupOpen(pages)) return;
        if (!frame.isFocused() && !owned(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow())) hidePanel();
    }

    private boolean owned(Window candidate) {
        for (Window window = candidate; window != null; window = window.getOwner()) {
            if (window == frame) return true;
        }
        return false;
    }

    private static boolean comboPopupOpen(Container parent) {
        for (Component child : parent.getComponents()) {
            if (!child.isVisible()) continue;
            if (child instanceof JComboBox && ((JComboBox<?>) child).isPopupVisible()) return true;
            if (child instanceof Container && comboPopupOpen((Container) child)) return true;
        }
        return false;
    }

    private void repopulateDevices() {
        populatingDevices = true;
        DefaultComboBoxModel<DeviceChoice> model = new DefaultComboBoxModel<>();
        DeviceChoice chosen = null;
        for (BtDevice device : devices) {
            DeviceChoice choice = new DeviceChoice(device, ui.t(device.connected ? "在线" : "已配对",
                    device.connected ? "Online" : "Paired"));
            model.addElement(choice);
            if (selected != null && selected.mac.equals(device.mac)) chosen = choice;
        }
        deviceCombo.setModel(model);
        deviceCombo.setSelectedItem(chosen != null ? chosen : model.getSelectedItem());
        DeviceChoice actual = (DeviceChoice) deviceCombo.getSelectedItem();
        selected = actual == null ? null : actual.device;
        populatingDevices = false;
    }

    private void selectBrand(String key) {
        for (int i = 0; i < brandCombo.getItemCount(); i++) {
            if (brandCombo.getItemAt(i).key.equalsIgnoreCase(key)) {
                brandCombo.setSelectedIndex(i);
                selectedBrand = brandCombo.getItemAt(i).key;
                return;
            }
        }
    }

    private void scan(Scan reason) {
        if (closing || scanning || !consentComplete) return;
        scanning = true;
        scanFailed = false;
        renderState();
        Thread worker = new Thread(() -> {
            try {
                List<BtDevice> ranked = registry.rankDevices(bridge.listDevices(), prefs.lastMac());
                SwingUtilities.invokeLater(() -> finishScan(reason, ranked, null));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> finishScan(reason, null, ex));
            }
        }, "WearEars-BtScan");
        worker.setDaemon(true);
        worker.start();
    }

    private void finishScan(Scan reason, List<BtDevice> ranked, Exception failure) {
        if (closing) return;
        scanning = false;
        scanned = true;
        scanFailed = failure != null;
        if (failure != null) {
            appendTrace("BT", "Enumeration failed: " + failure);
        } else {
            devices = ranked;
            repopulateDevices();
            appendTrace("BT", "Enumerated " + devices.size() + " Bluetooth device(s)");
            if (phase == ConnectionPhase.DISCONNECTED && (reason == Scan.RECONNECT
                    || (reason == Scan.STARTUP && prefs.autoConnect()))) {
                BtDevice candidate = registry.findAutoConnectCandidate(devices, prefs.lastMac());
                if (candidate != null) {
                    String brand = DesktopDriverRegistry.matchesMac(candidate, prefs.lastMac())
                            && registry.findByKey(prefs.lastBrand()) != null ? prefs.lastBrand() : DesktopDriverRegistry.BRAND_AUTO;
                    if (reason == Scan.RECONNECT && selected != null && selected.mac.equals(candidate.mac)
                            && registry.resolve(candidate.name, selectedBrand) != null) brand = selectedBrand;
                    if (registry.resolve(candidate.name, brand) != null) {
                        selectBrand(brand);
                        beginConnection(candidate, brand, reason == Scan.STARTUP);
                    }
                }
            }
        }
        renderState();
    }

    private void primaryConnectionAction() {
        if (phase != ConnectionPhase.DISCONNECTED) disconnect();
        else if (selected != null) connectSelected();
        else scan(Scan.RECONNECT);
    }

    private void connectSelected() {
        if (phase != ConnectionPhase.DISCONNECTED || selected == null || !consentComplete) return;
        cancelAutoRetry();
        if (registry.resolve(selected.name, selectedBrand) == null) {
            dialogs.info(ui.t("协议品牌", "Protocol brand"),
                    ui.t("未识别此设备的协议。可在设置中选择适用的品牌后重试。",
                            "This device could not be identified. Choose its protocol brand in Settings and try again."),
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        beginConnection(selected, selectedBrand, false);
    }

    private void beginConnection(BtDevice device, String brand, boolean retry) {
        activeRequest = new ConnectionRequest(device, brand, retry);
        phase = ConnectionPhase.CONNECTING;
        connectFailed = false;
        session.connect(device, brand);
        renderState();
    }

    private void disconnect() {
        cancelAutoRetry();
        activeRequest = null;
        session.disconnect();
    }

    private void cancelAutoRetry() {
        reconnect.stop();
        pendingRetry = null;
        if (activeRequest != null) activeRequest.retry = false;
    }

    private void retryAutoConnection() {
        ConnectionRequest retry = pendingRetry;
        pendingRetry = null;
        if (retry != null && !closing && prefs.autoConnect() && phase == ConnectionPhase.DISCONNECTED) {
            beginConnection(retry.device, retry.brand, false);
        }
    }

    private void refreshStatus() {
        if (!consentComplete || closing) return;
        if (phase == ConnectionPhase.CONNECTED) session.refresh();
        else if (mesh.isClientConnected()) mesh.sendRemoteRefresh();
        else if (phase == ConnectionPhase.DISCONNECTED) scan(Scan.RECONNECT);
    }

    private void quickNoise(int mode) {
        if (localNoiseAvailable()) session.setNoiseMode(mode);
        else if (mesh.isClientConnected()) mesh.sendRemoteNoiseMode(mode);
    }

    private boolean localNoiseAvailable() {
        return phase == ConnectionPhase.CONNECTED && state.connected && (state.caps & Controls.CAP_NOISE) != 0;
    }

    private void bindSession() {
        session.addListener(new DesktopSession.SessionListener() {
            @Override public void onPhaseChanged(ConnectionPhase next, String detail) {
                int connection = session.connectionId();
                StateSnapshotView snapshot = session.snapshot();
                mesh.pushSnapshot(session.meshSnapshot());
                SwingUtilities.invokeLater(() -> {
                    if (closing || connection != session.connectionId()) return;
                    phase = next;
                    state = snapshot;
                    if (next == ConnectionPhase.CONNECTED) {
                        if (activeRequest != null) {
                            prefs.rememberConnection(activeRequest.device.mac, activeRequest.brand);
                            activeRequest.retry = false;
                        }
                    } else if (next == ConnectionPhase.DISCONNECTED) {
                        ConnectionRequest failed = activeRequest;
                        activeRequest = null;
                        connectFailed = failed != null;
                        if (failed != null && failed.retry && prefs.autoConnect()) {
                            pendingRetry = failed;
                            reconnect.restart();
                        }
                    }
                    appendTrace("Session", detail);
                    renderState();
                });
            }
            @Override public void onStateChanged(StateSnapshotView snapshot) {
                int connection = session.connectionId();
                mesh.pushSnapshot(session.meshSnapshot());
                SwingUtilities.invokeLater(() -> {
                    if (closing || connection != session.connectionId()) return;
                    state = snapshot;
                    renderState();
                });
            }
            @Override public void onError(String message) {
                onTrace("Error", message);
            }
            @Override public void onTrace(String tag, String message) {
                SwingUtilities.invokeLater(() -> { if (!closing) appendTrace(tag, message); });
            }
        });
    }

    private void renderState() {
        boolean connected = phase == ConnectionPhase.CONNECTED && state.connected;
        boolean linking = phase == ConnectionPhase.CONNECTING;
        phaseText.setText(connected ? ui.t("已连接", "Connected") : linking ? ui.t("连接中…", "Connecting…")
                : scanning ? ui.t("正在扫描…", "Scanning…") : ui.t("未连接", "Disconnected"));
        statusDot.setTone(connected ? (ui.theme == DesktopTheme.SUPREME ? new Color(0x158044) : ui.theme.accent)
                : linking ? ui.theme.warning : ui.theme.faint);
        String name = (connected || linking) && activeRequest != null ? activeRequest.device.name
                : connected && !state.name.isBlank() ? state.name : selected != null ? selected.name
                : ui.t("蓝牙耳机", "Bluetooth earbuds");
        deviceName.setText(name);
        if (connected) {
            BrandModuleEntry brand = activeRequest == null ? registry.matchByName(state.name)
                    : registry.resolve(activeRequest.device.name, activeRequest.brand);
            String brandName = brand == null ? state.brandName : ui.strings.brand(brand.key, brand.displayName);
            String line = brandName + (state.firmware.isBlank() ? "" : " · " + state.firmware)
                    + " · " + ui.strings.wear(state.wearStatus);
            if (state.verifLevel != Controls.VERIF_REAL) line += ui.strings.experimental();
            metadata.setText(line);
        } else if (linking) {
            metadata.setText(ui.t("正在建立蓝牙控制通道。", "Opening the Bluetooth control channel."));
        } else if (scanFailed) {
            metadata.setText(ui.t("无法读取蓝牙设备。查看协议日志或重试。", "Bluetooth scan failed. Retry or check the protocol trace."));
        } else if (connectFailed) {
            metadata.setText(ui.t("蓝牙控制通道已断开。可重连或切换设备。", "The control channel is disconnected. Reconnect or choose another device."));
        } else if ((scanned || scanning) && registry.findAutoConnectCandidate(devices, prefs.lastMac()) == null) {
            metadata.setText(ui.t("请先在 Windows 蓝牙设置中连接耳机，再点击刷新或重连。",
                    "Connect the earbuds in Windows Bluetooth settings, then refresh or reconnect."));
        } else {
            metadata.setText(ui.t("选择耳机后即可一键连接。", "Choose a device to connect."));
        }
        quickConnect.setText(connected ? ui.t("断开", "Disconnect") : linking ? ui.t("取消连接", "Cancel connection")
                : selected != null ? ui.t("连接", "Connect") : ui.t("查找耳机", "Find Bluetooth devices"));
        quickConnect.setEnabled(!scanning || selected != null || linking || connected);
        scanButton.setEnabled(!scanning);
        connectButton.setEnabled(selected != null && phase == ConnectionPhase.DISCONNECTED);
        disconnectButton.setEnabled(phase != ConnectionPhase.DISCONNECTED);

        battery.setState(state);
        noise.setState(connected ? state.mode : -1, localNoiseAvailable());
        features.update(state);
        updateTray();
        pages.revalidate();
    }

    private void updateTray() {
        if (ui != null) tray.update(ui, phase, state, localNoiseAvailable() || mesh.isClientConnected());
    }

    private void updateStartup(Boolean enabled) {
        if (startupBusy || !startup.available()) return;
        startupBusy = true;
        startupFailed = false;
        renderStartup();
        Thread worker = new Thread(() -> {
            try {
                boolean registered = enabled == null ? startup.registered() : startup.setRegistered(enabled);
                SwingUtilities.invokeLater(() -> finishStartup(registered, null));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> finishStartup(null, ex));
            }
        }, "WearEars-StartupRegistry");
        worker.setDaemon(true);
        worker.start();
    }

    private void finishStartup(Boolean registered, Exception failure) {
        if (closing) return;
        startupRead = true;
        startupBusy = false;
        startupFailed = failure != null;
        if (registered != null) startupRegistered = registered;
        if (failure != null) appendTrace("Startup", failure.toString());
        renderStartup();
    }

    private void renderStartup() {
        startupBox.setEnabled(startup.available() && !startupBusy);
        startupBox.setSelected(startupRegistered);
        startupStatus.setText(!startup.available()
                ? ui.t("从 Windows EXE 或 JAR 启动时可用。", "Available when launched from a Windows EXE or JAR.")
                : startupBusy ? ui.t("正在读取 Windows 启动项…", "Checking the Windows startup entry…")
                : startupFailed ? ui.t("无法读取或保存启动项，详情见协议日志。", "Could not read or save the startup entry. See the protocol trace.") : "");
    }

    private String disclaimerTitle() { return ui.t("听觉安全与免责声明", "Hearing safety and disclaimer"); }

    private String disclaimerText() {
        return ui.t("WearEars 通过蓝牙 RFCOMM 或局域网协同控制兼容耳机。标注 [实验] 的协议功能随型号和固件而异。\n\n"
                        + "查找耳机前请摘下双耳，响铃时音量较高。局域网遥控永久禁止触发响铃。\n\n"
                        + "360° 实验室在本机生成双耳试听音频，默认启用输出限幅。播放前请降低耳机音量。",
                "WearEars controls compatible earbuds over Bluetooth RFCOMM or a local network. Experimental protocol features vary by model and firmware.\n\n"
                        + "Remove both earbuds before using Find My Buds. The ring is loud. Remote ringing over the LAN is permanently blocked.\n\n"
                        + "The 360° lab generates local binaural audio with an output limiter. Lower headphone volume before playback.");
    }

    private void appendTrace(String tag, String message) {
        try {
            String line = "[" + LocalTime.now().format(TRACE_TIME) + "][" + tag + "] " + message + "\n";
            trace.insertString(trace.getLength(), line, null);
            int over = trace.getLength() - TRACE_LIMIT;
            if (over > 0) {
                String end = trace.getText(over, Math.min(512, trace.getLength() - over));
                int nextLine = end.indexOf('\n');
                trace.remove(0, over + (nextLine >= 0 ? nextLine + 1 : 0));
            }
        } catch (BadLocationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void shutdown() {
        if (closing) return;
        closing = true;
        blurHide.stop();
        cancelAutoRetry();
        lab.stop();
        features.close();
        developer.stopDiscovery();
        tray.close();
        frame.dispose();
        Thread cleanup = new Thread(() -> {
            try {
                mesh.stopAll();
                session.shutdown();
                session.awaitShutdown(3, TimeUnit.SECONDS);
                lab.awaitStopped(900);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                instance.close();
                System.exit(0);
            }
        }, "WearEars-Shutdown");
        cleanup.start();
    }

    private static final class ConnectionRequest {
        final BtDevice device;
        final String brand;
        boolean retry;
        ConnectionRequest(BtDevice device, String brand, boolean retry) {
            this.device = device;
            this.brand = brand;
            this.retry = retry;
        }
    }

    private static final class DeviceChoice {
        final BtDevice device;
        final String status;
        DeviceChoice(BtDevice device, String status) { this.device = device; this.status = status; }
        @Override public String toString() { return device.name + "  ·  " + status + "  ·  " + device.mac; }
    }

    private static final class BrandChoice {
        final String key, label;
        BrandChoice(String key, String label) { this.key = key; this.label = label; }
        @Override public String toString() { return label; }
    }

    private static final class StatusDot extends JComponent {
        private Color tone = Color.GRAY;
        @Override public Dimension getPreferredSize() { return new Dimension(10, 17); }
        void setTone(Color color) { tone = color; repaint(); }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = DesktopUi.graphics(graphics);
            g.setColor(tone);
            g.fillOval(0, (getHeight() - 8) / 2, 8, 8);
            g.dispose();
        }
    }
}
