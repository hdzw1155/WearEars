package com.hdz.wearcontrol.desktop;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * Swiss International Style 360-degree interactive binaural polar dial.
 * 0 deg = Front (top), +90 deg = Right, -90 deg = Left, +/-180 deg = Back.
 */
final class SwissDialPanel extends JPanel {
    private DesktopTheme theme = DesktopTheme.MINIMAL;
    private DesktopStrings strings = new DesktopStrings("zh");

    private float azimuthDeg = 0.0f;
    private boolean playing = false;
    private Consumer<Float> onAngleChanged;

    SwissDialPanel() {
        setBackground(theme.page);
        setPreferredSize(new Dimension(280, 280));
        setMinimumSize(new Dimension(220, 220));
        MouseAdapter ma = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                updateFromPoint(e.getX(), e.getY());
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                updateFromPoint(e.getX(), e.getY());
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
    }

    void applyTheme(DesktopUi ui) {
        theme = ui.theme;
        strings = ui.strings;
        setBackground(theme.page);
        repaint();
    }

    void setOnAngleChanged(Consumer<Float> listener) {
        this.onAngleChanged = listener;
    }

    void setAzimuthDeg(float deg) {
        this.azimuthDeg = deg;
        repaint();
    }

    void setPlaying(boolean playing) {
        this.playing = playing;
        repaint();
    }

    private void updateFromPoint(int x, int y) {
        int cx = getWidth() / 2;
        int cy = getHeight() / 2;
        double dx = x - cx;
        double dy = cy - y; // up = 0 deg
        if (dx * dx + dy * dy < 16) return;
        double rad = Math.atan2(dx, dy);
        float deg = (float) Math.toDegrees(rad);
        this.azimuthDeg = deg;
        repaint();
        if (onAngleChanged != null) {
            onAngleChanged.accept(deg);
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            int cx = w / 2;
            int cy = h / 2;
            int r = Math.max(8, Math.min(w, h) / 2 - 34);
            Color grid = DesktopTheme.mix(theme.page, theme.accent, .24f);
            Color axis = DesktopTheme.mix(theme.page, theme.accent, .55f);

            // Outer 0px hairline frame
            g2.setColor(theme.border);
            g2.drawRect(0, 0, w - 1, h - 1);

            // Concentric technical rings
            g2.setStroke(new BasicStroke(1.0f));
            for (int ring = 1; ring <= 3; ring++) {
                int rr = (r * ring) / 3;
                g2.setColor(ring == 3 ? axis : grid);
                g2.drawOval(cx - rr, cy - rr, rr * 2, rr * 2);
            }

            // Crosshair axes
            g2.setColor(grid);
            g2.drawLine(cx - r - 8, cy, cx + r + 8, cy);
            g2.drawLine(cx, cy - r - 8, cx, cy + r + 8);

            // Degree tick marks every 30 deg
            for (int deg = 0; deg < 360; deg += 30) {
                double rad = Math.toRadians(deg);
                int r0 = r - (deg % 90 == 0 ? 8 : 4);
                int x1 = cx + (int) Math.round(Math.sin(rad) * r0);
                int y1 = cy - (int) Math.round(Math.cos(rad) * r0);
                int x2 = cx + (int) Math.round(Math.sin(rad) * r);
                int y2 = cy - (int) Math.round(Math.cos(rad) * r);
                g2.setColor(deg % 90 == 0 ? axis : grid);
                g2.drawLine(x1, y1, x2, y2);
            }

            // Cardinal labels
            g2.setFont(DesktopTheme.font(Font.BOLD, 11));
            g2.setColor(theme.muted);
            drawCentered(g2, strings.text("0° 前方", "0° FRONT"), cx, cy - r - 12);
            drawCentered(g2, strings.text("180° 后方", "180° BACK"), cx, cy + r + 18);
            drawCentered(g2, "L", cx - r - 16, cy + 4);
            drawCentered(g2, "R", cx + r + 16, cy + 4);

            // Center listener head marker
            g2.setColor(theme.track);
            g2.fillOval(cx - 14, cy - 14, 28, 28);
            g2.setColor(axis);
            g2.drawOval(cx - 14, cy - 14, 28, 28);
            // Nose indicator pointing up
            g2.setColor(theme.text);
            g2.fillRect(cx - 2, cy - 17, 4, 5);

            // Active source vector + beacon
            double rad = Math.toRadians(azimuthDeg);
            int sx = cx + (int) Math.round(Math.sin(rad) * (r - 12));
            int sy = cy - (int) Math.round(Math.cos(rad) * (r - 12));

            g2.setColor(playing ? theme.accent : theme.pill);
            g2.setStroke(new BasicStroke(2.0f));
            g2.drawLine(cx, cy, sx, sy);

            if (playing) {
                g2.setColor(DesktopTheme.alpha(theme.accent, 55));
                g2.fillOval(sx - 15, sy - 15, 30, 30);
            }
            g2.setColor(playing ? theme.accent : theme.text);
            g2.fillOval(sx - 7, sy - 7, 14, 14);
            g2.setColor(theme.page);
            g2.fillOval(sx - 2, sy - 2, 4, 4);

            // Azimuth numeric readout at bottom-left corner
            int rounded = Math.round(azimuthDeg);
            String label = String.format("%+d°", rounded);
            g2.setFont(DesktopTheme.font(Font.BOLD, 13));
            g2.setColor(playing ? theme.accent : theme.text);
            g2.drawString(label, 12, h - 12);
        } finally {
            g2.dispose();
        }
    }

    private static void drawCentered(Graphics2D g2, String text, int x, int y) {
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(text, x - fm.stringWidth(text) / 2, y);
    }
}
