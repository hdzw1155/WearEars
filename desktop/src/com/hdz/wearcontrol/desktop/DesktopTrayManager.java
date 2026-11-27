package com.hdz.wearcontrol.desktop;

import com.hdz.wearcontrol.Controls;
import com.hdz.wearcontrol.DesktopCoreBridge.StateSnapshotView;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Arc2D;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

final class DesktopTrayManager implements AutoCloseable {
    private static final int PANEL_MARGIN = 12;
    private static final Color DISCONNECTED = new Color(0x8394A9);
    private static final Color CONNECTED = new Color(0x5EDDA6);
    private static final Color BADGE_BLUE = new Color(0x0D4FB8);
    private static final Color BADGE_RED = new Color(0xDF1B22);
    private static final Color CHARGING = new Color(0x8BF9A6);
    private final JFrame frame;
    private final Consumer<Point> togglePanel;
    private final Runnable refresh;
    private final IntConsumer noise;
    private final Runnable settings;
    private final Runnable exit;
    private final Runnable onMenuBlur;
    private final Consumer<String> trace;
    private SystemTray tray;
    private TrayIcon icon;
    private JDialog menu;
    private DesktopUi ui;
    private Color indicator = DISCONNECTED;
    private String iconStyle = DesktopPrefs.TRAY_HEADSET;
    private int iconPercent = -1;
    private boolean iconCharging;
    private boolean noiseAvailable;

    DesktopTrayManager(JFrame frame, Consumer<Point> togglePanel, Runnable refresh, IntConsumer noise,
                       Runnable settings, Runnable exit, Runnable onMenuBlur, Consumer<String> trace) {
        this.frame = frame;
        this.togglePanel = togglePanel;
        this.refresh = refresh;
        this.noise = noise;
        this.settings = settings;
        this.exit = exit;
        this.onMenuBlur = onMenuBlur;
        this.trace = trace;
    }

    boolean install() {
        if (!SystemTray.isSupported()) {
            trace.accept("System tray unavailable");
            return false;
        }
        try {
            tray = SystemTray.getSystemTray();
            TrayIcon candidate = new TrayIcon(image(DISCONNECTED), "WearEars");
            candidate.setImageAutoSize(true);
            candidate.addMouseListener(new MouseAdapter() {
                @Override public void mouseReleased(MouseEvent event) {
                    if (event.getClickCount() > 1) return;
                    Point point = pointerLocation();
                    if (point == null) point = event.getPoint();
                    Point location = point;
                    if (event.getButton() == MouseEvent.BUTTON1) {
                        SwingUtilities.invokeLater(() -> {
                            closeMenu();
                            togglePanel.accept(location);
                        });
                    } else if (event.getButton() == MouseEvent.BUTTON3) {
                        SwingUtilities.invokeLater(() -> showMenu(location));
                    }
                }
            });
            tray.add(candidate);
            icon = candidate;
            iconStyle = DesktopPrefs.TRAY_HEADSET;
            iconPercent = -1;
            iconCharging = false;
            indicator = DISCONNECTED;
            return true;
        } catch (AWTException | RuntimeException ex) {
            trace.accept("Could not install notification-area icon: " + ex);
            return false;
        }
    }

    boolean installed() { return icon != null; }
    boolean menuOpen() { return menu != null && menu.isShowing(); }

    void update(DesktopUi ui, DesktopSession.ConnectionPhase phase, StateSnapshotView state,
                boolean canControlNoise, String style) {
        this.ui = ui;
        noiseAvailable = canControlNoise;
        if (icon == null) return;
        String tooltip = ui.strings.tooltip(phase, state);
        if (!tooltip.equals(icon.getToolTip())) icon.setToolTip(tooltip);
        boolean low = low(state.left, state.charge, 0) || low(state.right, state.charge, 1)
                || low(state.box, state.charge, 2);
        Color next = state.connected ? (low ? ui.theme.danger : CONNECTED)
                : phase == DesktopSession.ConnectionPhase.CONNECTING ? ui.theme.warning : DISCONNECTED;
        boolean batteryStyle = DesktopPrefs.TRAY_BATTERY.equals(style);
        int percent = batteryStyle ? displayBatteryPercent(state) : -1;
        boolean charging = batteryStyle && displayBatteryCharging(state);
        if (style.equals(iconStyle) && percent == iconPercent && charging == iconCharging && next.equals(indicator)) return;
        icon.setImage(batteryStyle ? batteryImage(percent, charging, next) : image(next));
        iconStyle = style;
        iconPercent = percent;
        iconCharging = charging;
        indicator = next;
    }

    private static boolean low(int percent, int flags, int part) {
        return percent >= 0 && percent <= LowBatteryAlertTracker.LOW_THRESHOLD && !DesktopStrings.charging(flags, part);
    }

    void notifyLowBattery(String title, String body) {
        if (icon != null) icon.displayMessage(title, body, TrayIcon.MessageType.WARNING);
    }

    static int displayBatteryPercent(StateSnapshotView state) {
        switch (displayBatteryComponent(state)) {
            case 0: return state.left;
            case 1: return state.right;
            case 2: return state.box;
            default: return -1;
        }
    }

    static boolean displayBatteryCharging(StateSnapshotView state) {
        int part = displayBatteryComponent(state);
        return part >= 0 && DesktopStrings.charging(state.charge, part);
    }

    private static int displayBatteryComponent(StateSnapshotView state) {
        if (!state.connected) return -1;
        boolean left = DesktopStrings.validBattery(state.left);
        if ((state.caps & Controls.CAP_BATTERY_SINGLE) != 0) return left ? 0 : -1;
        boolean right = DesktopStrings.validBattery(state.right);
        if (left && right) {
            if (state.left != state.right) return state.left < state.right ? 0 : 1;
            // At the same level, a discharging earbud takes priority.
            return DesktopStrings.charging(state.charge, 0) && !DesktopStrings.charging(state.charge, 1) ? 1 : 0;
        }
        if (left || right) return left ? 0 : 1;
        return DesktopStrings.validBattery(state.box) ? 2 : -1;
    }

    private void showMenu(Point point) {
        closeMenu();
        if (ui == null || !installed()) return;
        JDialog popup = new JDialog(frame);
        popup.setUndecorated(true);
        popup.setType(Window.Type.POPUP);
        popup.setAlwaysOnTop(true);
        popup.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        DesktopUi.Stack list = ui.stack(4);
        list.setOpaque(true);
        list.setBackground(ui.theme.card);
        list.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(ui.theme.border),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        list.add(menuItem(frame.isVisible() ? ui.t("隐藏面板", "Hide panel") : ui.t("打开面板", "Open panel"),
                true, () -> togglePanel.accept(point)));
        list.add(menuItem(ui.t("刷新电量", "Refresh battery"), true, refresh));
        list.add(menuItem(ui.t("降噪 (ANC)", "Noise cancellation (ANC)"), noiseAvailable, () -> noise.accept(Controls.MODE_ANC)));
        list.add(menuItem(ui.t("通透 (TRANS)", "Transparency (TRANS)"), noiseAvailable, () -> noise.accept(Controls.MODE_TRANS)));
        list.add(menuItem(ui.t("关闭 (OFF)", "Off (OFF)"), noiseAvailable, () -> noise.accept(Controls.MODE_OFF)));
        list.add(menuItem(ui.t("设置…", "Settings…"), true, settings));
        list.add(menuItem(ui.t("退出 WearEars", "Exit WearEars"), true, exit));
        int width = 230;
        list.setPreferredSize(new Dimension(width, list.heightFor(width)));
        popup.setContentPane(list);
        popup.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "dismiss");
        popup.getRootPane().getActionMap().put("dismiss", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { closeMenu(); }
        });
        popup.addWindowFocusListener(new WindowAdapter() {
            @Override public void windowLostFocus(WindowEvent e) {
                if (menu == popup) {
                    closeMenu();
                    onMenuBlur.run();
                }
            }
        });
        menu = popup;
        popup.pack();
        Rectangle work = workArea(point);
        int x = Math.max(work.x, Math.min(point.x - popup.getWidth() / 2, work.x + work.width - popup.getWidth()));
        int y = point.y - popup.getHeight() - 6;
        if (y < work.y) y = point.y + 6;
        y = Math.max(work.y, Math.min(y, work.y + work.height - popup.getHeight()));
        popup.setLocation(x, y);
        popup.setVisible(true);
        popup.toFront();
        popup.requestFocus();
    }

    private DesktopUi.Button menuItem(String title, boolean enabled, Runnable action) {
        DesktopUi.Button item = ui.button(title, false, () -> {
            closeMenu();
            action.run();
        });
        item.setHorizontalAlignment(javax.swing.SwingConstants.LEADING);
        item.setEnabled(enabled);
        return item;
    }

    void closeMenu() {
        JDialog previous = menu;
        menu = null;
        if (previous != null) previous.dispose();
    }

    static Point pointerLocation() {
        PointerInfo pointer = MouseInfo.getPointerInfo();
        return pointer == null ? null : pointer.getLocation();
    }

    static Rectangle panelBounds(Point near, int width, int height) {
        Rectangle work = workArea(near);
        return anchor(work, width, height);
    }

    static Rectangle anchor(Rectangle work, int width, int height) {
        int marginX = Math.min(PANEL_MARGIN, Math.max(0, (work.width - 1) / 2));
        int marginY = Math.min(PANEL_MARGIN, Math.max(0, (work.height - 1) / 2));
        int w = Math.min(width, work.width - 2 * marginX);
        int h = Math.min(height, work.height - 2 * marginY);
        return new Rectangle(work.x + work.width - w - marginX, work.y + work.height - h - marginY, w, h);
    }

    private static Rectangle workArea(Point near) {
        GraphicsEnvironment screens = GraphicsEnvironment.getLocalGraphicsEnvironment();
        Rectangle primary = screens.getMaximumWindowBounds();
        if (near == null) return primary;
        GraphicsDevice main = screens.getDefaultScreenDevice();
        for (GraphicsDevice screen : screens.getScreenDevices()) {
            GraphicsConfiguration config = screen.getDefaultConfiguration();
            Rectangle bounds = config.getBounds();
            if (!bounds.contains(near)) continue;
            if (screen == main && bounds.contains(primary)) return primary;
            Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(config);
            return new Rectangle(bounds.x + insets.left, bounds.y + insets.top,
                    bounds.width - insets.left - insets.right, bounds.height - insets.top - insets.bottom);
        }
        return primary;
    }

    static BufferedImage image(Color dot) {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D base = image.createGraphics();
        Graphics2D g = DesktopUi.graphics(base);
        try {
            g.setColor(new Color(0x0D4FB8));
            g.fillRoundRect(2, 2, 60, 60, 17, 17);
            g.setColor(new Color(0xBEDBFF));
            g.setStroke(new BasicStroke(2));
            g.drawRoundRect(3, 3, 58, 58, 16, 16);
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(5, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Arc2D.Float(15, 14, 34, 34, 0, 180, Arc2D.OPEN));
            g.drawLine(15, 29, 15, 39);
            g.drawLine(49, 29, 49, 39);
            g.fillRoundRect(13, 30, 11, 17, 6, 6);
            g.fillRoundRect(40, 30, 11, 17, 6, 6);
            g.setColor(new Color(0x0A1832));
            g.fillOval(42, 42, 19, 19);
            g.setColor(dot);
            g.fillOval(45, 45, 13, 13);
        } finally {
            g.dispose();
            base.dispose();
        }
        return image;
    }

    static BufferedImage batteryImage(int percent, boolean charging, Color indicator) {
        if (percent < 0) return image(indicator);
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D base = image.createGraphics();
        Graphics2D g = DesktopUi.graphics(base);
        try {
            g.setColor(!charging && percent <= LowBatteryAlertTracker.LOW_THRESHOLD ? BADGE_RED : BADGE_BLUE);
            g.fillRoundRect(2, 2, 60, 60, 15, 15);
            DesktopUi.drawText(g, Integer.toString(percent), DesktopTheme.font(Font.BOLD, 46),
                    Color.WHITE, 5, 4, 54, charging ? 49 : 56, 0);
            if (charging) {
                g.setColor(CHARGING);
                g.fillRoundRect(15, 53, 34, 5, 5, 5);
            }
        } finally {
            g.dispose();
            base.dispose();
        }
        return image;
    }

    @Override public void close() {
        closeMenu();
        if (icon != null) {
            tray.remove(icon);
            icon = null;
        }
    }
}
