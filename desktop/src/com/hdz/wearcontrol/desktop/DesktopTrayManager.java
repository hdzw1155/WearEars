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
    private Color indicator;
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
            TrayIcon candidate = new TrayIcon(image(new Color(0x8394A9)), "WearEars");
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
            return true;
        } catch (AWTException | RuntimeException ex) {
            trace.accept("Could not install notification-area icon: " + ex);
            return false;
        }
    }

    boolean installed() { return icon != null; }
    boolean menuOpen() { return menu != null && menu.isShowing(); }

    void update(DesktopUi ui, DesktopSession.ConnectionPhase phase, StateSnapshotView state,
                boolean canControlNoise) {
        this.ui = ui;
        noiseAvailable = canControlNoise;
        if (icon == null) return;
        String tooltip = ui.strings.tooltip(phase, state);
        if (!tooltip.equals(icon.getToolTip())) icon.setToolTip(tooltip);
        boolean low = low(state.left, state.charge, 0) || low(state.right, state.charge, 1)
                || low(state.box, state.charge, 2);
        Color next = state.connected ? (low ? ui.theme.danger : new Color(0x5EDDA6))
                : phase == DesktopSession.ConnectionPhase.CONNECTING ? ui.theme.warning : new Color(0x8394A9);
        if (!next.equals(indicator)) {
            indicator = next;
            icon.setImage(image(indicator));
        }
    }

    private static boolean low(int percent, int flags, int part) {
        return percent >= 0 && percent <= 20 && !DesktopStrings.charging(flags, part);
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

    private void closeMenu() {
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

    @Override public void close() {
        closeMenu();
        if (icon != null) {
            tray.remove(icon);
            icon = null;
        }
    }
}
