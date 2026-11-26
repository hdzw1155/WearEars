package com.hdz.wearcontrol.desktop;

import com.hdz.wearcontrol.Controls;

import javax.swing.AbstractAction;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.IntConsumer;

final class DesktopNoiseSegmentedBar extends JPanel {
    private static final int[] NEO_ORDER = {Controls.MODE_OFF, Controls.MODE_TRANS, Controls.MODE_ANC};
    private static final int[] SUPREME_ORDER = {Controls.MODE_ANC, Controls.MODE_TRANS, Controls.MODE_OFF};
    private static final Font LABEL = DesktopTheme.font(Font.BOLD, 12);
    private static final double STIFFNESS = 1200.0;
    private static final double DAMPING = Math.sqrt(STIFFNESS);

    private final DesktopTheme theme;
    private final DesktopStrings strings;
    private final IntConsumer onSelect;
    private final Timer spring = new Timer(16, e -> advance());
    private int selected = -1;
    private int focusIndex = -1;
    private double center = -1;
    private double velocity;
    private long lastFrame;
    private int pressX = -1;
    private boolean dragging;

    DesktopNoiseSegmentedBar(DesktopUi ui, IntConsumer onSelect) {
        theme = ui.theme;
        strings = ui.strings;
        this.onSelect = onSelect;
        setOpaque(false);
        setFocusable(true);
        setPreferredSize(new Dimension(0, theme == DesktopTheme.SUPREME ? 55 : 44));
        getAccessibleContext().setAccessibleName(ui.t("降噪模式", "Noise control"));
        setState(-1, false);
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && !isShowing()) resetMotion();
        });
        addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { repaint(); }
            @Override public void focusLost(FocusEvent e) { repaint(); }
        });
        bind(KeyEvent.VK_LEFT, "left", () -> moveFocus(-1));
        bind(KeyEvent.VK_RIGHT, "right", () -> moveFocus(1));
        bind(KeyEvent.VK_HOME, "first", () -> focusAt(0));
        bind(KeyEvent.VK_END, "last", () -> focusAt(2));
        bind(KeyEvent.VK_SPACE, "space", this::activateFocus);
        bind(KeyEvent.VK_ENTER, "enter", this::activateFocus);

        MouseAdapter pointer = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (!isEnabled() || e.getButton() != MouseEvent.BUTTON1) return;
                requestFocusInWindow();
                focusAt(segmentAt(e.getX()));
                pressX = e.getX();
            }

            @Override public void mouseDragged(MouseEvent e) {
                if (!isEnabled() || pressX < 0 || theme == DesktopTheme.SUPREME) return;
                if (!dragging && Math.abs(e.getX() - pressX) < 5) return;
                dragging = true;
                spring.stop();
                velocity = 0;
                center = clamp(3.0 * e.getX() / Math.max(1, getWidth()) - .5);
                focusAt(segmentAt(e.getX()));
            }

            @Override public void mouseReleased(MouseEvent e) {
                if (pressX < 0 || e.getButton() != MouseEvent.BUTTON1) return;
                pressX = -1;
                boolean accept = isEnabled() && (dragging || contains(e.getPoint()));
                dragging = false;
                if (accept) {
                    focusAt(segmentAt(e.getX()));
                    activateFocus();
                }
                settle();
            }
        };
        addMouseListener(pointer);
        addMouseMotionListener(pointer);
    }

    void setState(int mode, boolean enabled) {
        int old = selected;
        selected = enabled ? indexOf(mode) : -1;
        setEnabled(enabled);
        setCursor(Cursor.getPredefinedCursor(enabled ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        getAccessibleContext().setAccessibleDescription(enabled
                ? strings.noise(mode) + " · " + strings.text("方向键选档，回车确认", "Arrow keys to select, Enter to apply")
                : strings.text("当前耳机未提供降噪控制", "Noise control unavailable"));
        if (!enabled) resetMotion();
        else if (old != selected && !dragging) settle();
        repaint();
    }

    private int[] order() {
        return theme == DesktopTheme.SUPREME ? SUPREME_ORDER : NEO_ORDER;
    }

    private int indexOf(int mode) {
        int[] order = order();
        for (int i = 0; i < order.length; i++) if (order[i] == mode) return i;
        return -1;
    }

    private void bind(int key, String name, Runnable action) {
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, 0), name);
        getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { if (isEnabled()) action.run(); }
        });
    }

    private int segmentAt(int x) {
        return (int) clamp(3L * x / Math.max(1, getWidth()));
    }

    private static double clamp(double x) { return Math.max(0, Math.min(2, x)); }

    private void moveFocus(int steps) {
        int index = focusIndex < 0 ? Math.max(0, selected) : focusIndex;
        focusAt((int) clamp(index + steps));
    }

    private void focusAt(int index) {
        focusIndex = index;
        repaint();
    }

    private void activateFocus() {
        if (focusIndex >= 0 && focusIndex != selected) onSelect.accept(order()[focusIndex]);
    }

    private void settle() {
        if (selected < 0 || center < 0 || !isShowing() || theme == DesktopTheme.SUPREME) {
            resetMotion();
        } else if (!spring.isRunning() && center != selected) {
            lastFrame = System.nanoTime();
            spring.start();
        }
        repaint();
    }

    private void advance() {
        if (!isShowing()) {
            resetMotion();
            return;
        }
        long now = System.nanoTime();
        double dt = Math.min(.023, (now - lastFrame) / 1e9);
        lastFrame = now;
        velocity += (-STIFFNESS * (center - selected) - DAMPING * velocity) * dt;
        center += velocity * dt;
        if (Math.abs(center - selected) < .003 && Math.abs(velocity) < .04) resetMotion();
        repaint();
    }

    private void resetMotion() {
        spring.stop();
        velocity = 0;
        center = selected;
        dragging = false;
        pressX = -1;
    }

    boolean isAnimating() { return spring.isRunning(); }

    @Override public void removeNotify() {
        resetMotion();
        super.removeNotify();
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (getWidth() < 15 || getHeight() < 15) return;
        Graphics2D g = DesktopUi.graphics(graphics);
        try {
            float width = getWidth(), height = getHeight(), segment = width / 3f;
            boolean supreme = theme == DesktopTheme.SUPREME;
            if (!supreme) {
                Shape track = DesktopUi.rounded(.5f, .5f, width - 1, height - 1, 22);
                g.setColor(theme.track);
                g.fill(track);
                g.setColor(theme.border);
                g.draw(track);
                if (center >= 0 && (selected >= 0 || dragging)) {
                    g.setColor(theme.pill);
                    g.fill(DesktopUi.rounded((float) clamp(center) * segment + 3, 3, segment - 6, height - 6, 20));
                }
            }
            int[] modes = order();
            for (int i = 0; i < modes.length; i++) {
                float x = i * segment;
                boolean focused = isFocusOwner() && i == (focusIndex < 0 ? Math.max(0, selected) : focusIndex);
                Color color;
                if (supreme) {
                    Shape slab = DesktopUi.slanted(x + 1, 1, segment - 4, height - 2, 11);
                    g.setColor(!isEnabled() ? new Color(0x4A505C) : i == selected ? theme.danger : theme.text);
                    g.fill(slab);
                    if (focused) {
                        g.setColor(theme.accent);
                        g.draw(slab);
                    }
                    color = isEnabled() ? Color.WHITE : new Color(0xCBD0D8);
                } else {
                    boolean overPill = (selected >= 0 || dragging) && Math.abs(clamp(center) - i) < .35;
                    color = !isEnabled() ? theme.faint : overPill ? theme.onPill : theme.muted;
                    if (focused) {
                        g.setColor(overPill ? theme.onPill : theme.accent);
                        g.draw(DesktopUi.rounded(x + 5, 5, segment - 10, height - 10, 18));
                    }
                }
                paintLabel(g, modes[i], color, x, segment, height, supreme);
            }
        } finally {
            g.dispose();
        }
    }

    private void paintLabel(Graphics2D g, int mode, Color color, float x, float width, float height, boolean supreme) {
        DesktopIcons icon = mode == Controls.MODE_ANC ? DesktopIcons.ANC
                : mode == Controls.MODE_TRANS ? DesktopIcons.TRANSPARENCY : DesktopIcons.OFF;
        String text = mode == Controls.MODE_TRANS && supreme && strings.english ? "TRANS" : strings.noise(mode);
        if (supreme) {
            icon.draw(g, x + width / 2 - 8, 7, 17, color);
            DesktopUi.drawText(g, text, LABEL, color, x + 8, 25, width - 18, height - 29, 0);
        } else {
            FontMetrics metrics = DesktopUi.fit(g, text, LABEL, width - 28, height - 8);
            float start = x + (width - (metrics.stringWidth(text) + 21)) / 2;
            icon.draw(g, start, (height - 16) / 2, 16, color);
            g.setColor(color);
            g.drawString(text, start + 21, (height + metrics.getAscent() - metrics.getDescent()) / 2);
        }
    }
}
