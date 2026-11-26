package com.hdz.wearcontrol.desktop;

import com.hdz.wearcontrol.Controls;
import com.hdz.wearcontrol.DesktopCoreBridge.StateSnapshotView;

import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.event.HierarchyEvent;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;

final class DesktopBatteryPanel extends JPanel {
    private static final Font NUMBER = DesktopTheme.font(Font.BOLD, 30);
    private static final Font LABEL = DesktopTheme.font(Font.PLAIN, 12);
    private static final Color WARM_NUMBER = new Color(0xFFF8E8);
    private static final Color CLASSIC_NUMBER = new Color(0xF2F7FF);
    private final DesktopTheme theme;
    private final DesktopStrings strings;
    private float phase;
    private final Timer waves = new Timer(33, e -> {
        phase = (phase + .18f) % (float) (2 * Math.PI);
        repaint();
    });
    private final int[] levels = {-1, -1, -1};
    private int charge = -1;
    private boolean single;

    DesktopBatteryPanel(DesktopUi ui) {
        theme = ui.theme;
        strings = ui.strings;
        setOpaque(false);
        setState(0, -1, -1, -1, -1);
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) updateAnimation();
        });
    }

    void setState(StateSnapshotView state) {
        setState(state.caps, state.left, state.right, state.box, state.charge);
    }

    void setState(int caps, int left, int right, int box, int flags) {
        boolean nextSingle = (caps & Controls.CAP_BATTERY_SINGLE) != 0;
        boolean topologyChanged = single != nextSingle;
        single = nextSingle;
        levels[0] = left;
        levels[1] = right;
        levels[2] = box;
        charge = flags;
        getAccessibleContext().setAccessibleName(strings.text("电量", "Battery"));
        String summary = strings.batterySummary(caps, left, right, box, flags);
        getAccessibleContext().setAccessibleDescription(summary);
        setToolTipText(summary);
        if (topologyChanged) revalidate();
        updateAnimation();
        repaint();
    }

    private void updateAnimation() {
        boolean shouldAnimate = false;
        if (isShowing() && theme == DesktopTheme.LIQUID) {
            for (int i = 0; i < count(); i++) shouldAnimate |= levels[i] > 0 && levels[i] < 100;
        }
        if (shouldAnimate) waves.start(); else waves.stop();
    }

    boolean isAnimating() { return waves.isRunning(); }

    @Override public void removeNotify() {
        waves.stop();
        super.removeNotify();
    }

    @Override public Dimension getPreferredSize() {
        int height;
        switch (theme) {
            case SUPREME: height = single ? 52 : 124; break;
            case MINIMAL: height = single ? 86 : 157; break;
            case CLASSIC: height = 117; break;
            case LIQUID: height = 153; break;
            default: throw new AssertionError(theme);
        }
        return new Dimension(0, height);
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (getWidth() < 12 || getHeight() < 16) return;
        Graphics2D g = DesktopUi.graphics(graphics);
        try {
            float w = getWidth(), h = getHeight();
            if (theme == DesktopTheme.MINIMAL) {
                paintBento(g, w, h);
                return;
            }
            surface(g, 0, 0, w, h);
            for (int i = 0; i < count(); i++) {
                switch (theme) {
                    case SUPREME: paintSegments(g, i, w, h); break;
                    case CLASSIC: paintRing(g, i, w / count(), h); break;
                    case LIQUID: paintLiquid(g, i, w / count(), h); break;
                    default: throw new AssertionError(theme);
                }
            }
        } finally {
            g.dispose();
        }
    }

    private void paintBento(Graphics2D g, float width, float height) {
        if (single) {
            paintBentoCell(g, 0, 0, 0, width, height, false, false);
            return;
        }
        float gap = 7f;
        float topH = (height - gap) * .58f;
        float column = (width - gap) / 2f;
        paintBentoCell(g, 0, 0, 0, column, topH, false, false);
        paintBentoCell(g, 1, column + gap, 0, column, topH, true, false);
        paintBentoCell(g, 2, 0, topH + gap, width, height - topH - gap, false, true);
    }

    private void paintBentoCell(Graphics2D g, int i, float x, float y, float w, float h,
                                boolean right, boolean wide) {
        surface(g, x, y, w, h);
        float pad = Math.min(13, w / 9);
        float usable = w - 2 * pad;
        float barTop = y + h - 15;
        float labelWidth = wide ? usable * .47f : usable;
        float valueTop = wide ? y + 6 : y + 29;
        String label = strings.batteryLabel(single, i, false);
        DesktopUi.drawText(g, label, LABEL, theme.muted, x + pad, y + 7, labelWidth,
                wide ? barTop - y - 7 : 20, right ? 1 : -1);
        float numberW = wide ? usable * .43f : usable - (charging(i) ? 19 : 0);
        float numberX = wide ? x + w - pad - numberW : x + pad + (right && charging(i) ? 19 : 0);
        DesktopUi.drawText(g, percent(i), wide ? NUMBER.deriveFont(25f) : NUMBER,
                known(i) ? WARM_NUMBER : theme.faint, numberX, valueTop, numberW, barTop - valueTop - 2,
                right || wide ? 1 : -1);
        if (charging(i)) {
            float boltX = wide ? numberX - 15 : right ? x + pad : x + w - pad - 15;
            DesktopIcons.BOLT.draw(g, boltX, (valueTop + barTop - 16) / 2f, 16, theme.warning);
        }
        g.setColor(new Color(0x2A2416));
        g.fill(DesktopUi.rounded(x + pad, barTop, usable, 5, 2.5f));
        float fillW = usable * fraction(i);
        if (fillW > 0) {
            Color tone = batteryColor(i);
            g.setPaint(new GradientPaint(x + pad, 0, tone, x + pad + fillW, 0,
                    DesktopTheme.mix(tone, Color.WHITE, .25f)));
            g.fill(DesktopUi.rounded(x + pad, barTop, fillW, 5, 2.5f));
        }
    }

    private void paintSegments(Graphics2D g, int i, float width, float height) {
        float pad = 13, row = (height - 16) / count(), y = 8 + row * i + 4;
        float cellHeight = row - 8;
        float badgeW = single ? 67 : 39;
        float numberW = Math.min(66, width * .23f);
        float left = pad + (single || i == 1 ? 0 : i == 0 ? 7 : 16);
        Color accent = i == 0 ? theme.danger : i == 1 ? theme.accent : DesktopTheme.CHROME_YELLOW;
        if (known(i) && levels[i] <= 20 && !charging(i)) accent = theme.danger;
        g.setColor(accent);
        g.fill(DesktopUi.slanted(left, y, badgeW, cellHeight, 8));
        String label = single ? strings.batteryLabel(true, 0, true)
                : i == 0 ? strings.text("左", "L") : i == 1 ? strings.text("右", "R") : strings.text("盒", "Box");
        DesktopUi.drawText(g, label, LABEL.deriveFont(Font.BOLD),
                accent.equals(DesktopTheme.CHROME_YELLOW) ? theme.text : Color.WHITE,
                left + 6, y, badgeW - 12, cellHeight, 0);
        float slash = left + badgeW + 1;
        g.setColor(theme.text);
        g.drawLine(Math.round(slash), Math.round(y + cellHeight - 2),
                Math.round(slash + 8), Math.round(y + 2));
        float bar = slash + 12;
        float barWidth = width - pad - numberW - bar - (i == 2 ? 9 : 0);
        if (barWidth > 8) {
            float skew = Math.min(8, barWidth * .11f);
            float cell = (barWidth - skew) / 7;
            Shape frame = DesktopUi.slanted(bar, y, barWidth, cellHeight, skew);
            g.setColor(known(i) ? theme.card : theme.track);
            g.fill(frame);
            float filled = fraction(i) * 7;
            g.setColor(accent);
            for (int segment = 0; segment < 7 && segment < filled; segment++) {
                float amount = Math.min(1, filled - segment);
                g.fill(DesktopUi.slanted(bar + segment * cell, y, cell * amount + skew, cellHeight, skew));
            }
            g.setColor(known(i) ? theme.text : theme.faint);
            g.draw(frame);
            for (int segment = 1; segment < 7; segment++) {
                float edge = bar + segment * cell;
                g.drawLine(Math.round(edge), Math.round(y + cellHeight),
                        Math.round(edge + skew), Math.round(y));
            }
        }
        if (charging(i)) DesktopIcons.BOLT.draw(g, width - pad - numberW, y + (cellHeight - 14) / 2, 14,
                theme.danger);
        DesktopUi.drawText(g, percent(i), NUMBER.deriveFont(20f), known(i) ? theme.text : theme.faint,
                width - pad - numberW + (charging(i) ? 15 : 4), y,
                numberW - (charging(i) ? 15 : 4), cellHeight, 1);
    }

    private void paintRing(Graphics2D g, int i, float cell, float height) {
        float w = Math.min(single ? 114f : 86f, cell - 15), h = 63f;
        if (w < 8) return;
        float x = (i + .5f) * cell - w / 2, y = 14;
        float radius = Math.min(w / 2, 24);
        Shape outline = DesktopUi.rounded(x, y, w, h, radius);
        g.setPaint(new GradientPaint(0, y, new Color(0x1F2B44), 0, y + h, new Color(0x131A29)));
        g.fill(outline);
        g.setColor(new Color(0x14FFFFFF, true));
        g.fill(DesktopUi.rounded(x + w * .25f, y + 7, w * .5f, 2, 1));
        g.setColor(new Color(0x1E293D));
        g.setStroke(new BasicStroke(4, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(outline);
        if (fraction(i) > 0) {
            Color tone = batteryColor(i);
            Path2D ring = roundedLoop(x, y, w, h, radius);
            Path2D progress = traceFraction(ring, fraction(i));
            g.setColor(DesktopTheme.alpha(tone, 40));
            g.setStroke(new BasicStroke(8, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(progress);
            g.setPaint(new GradientPaint(x, 0, tone, x + w, 0, DesktopTheme.mix(tone, Color.WHITE, .32f)));
            g.setStroke(new BasicStroke(4, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(progress);
            if (fraction(i) < .985f) {
                java.awt.geom.Point2D end = progress.getCurrentPoint();
                g.setColor(CLASSIC_NUMBER);
                g.fill(new java.awt.geom.Ellipse2D.Double(end.getX() - 1.4, end.getY() - 1.4, 2.8, 2.8));
            }
        }
        DesktopUi.drawText(g, percent(i), NUMBER.deriveFont(23f), known(i) ? CLASSIC_NUMBER : theme.faint,
                x + 8, y + 11, w - 16, h - 22, 0);
        if (charging(i)) {
            float bx = x + w - 19, by = y + 1;
            g.setColor(theme.card);
            g.fill(DesktopUi.rounded(bx - 2, by - 2, 22, 22, 11));
            g.setColor(theme.warning);
            g.fill(DesktopUi.rounded(bx, by, 18, 18, 9));
            DesktopIcons.BOLT.draw(g, bx + 1, by + 1, 16, theme.card);
        }
        capsuleLabel(g, i, cell, y + h + 5, height);
    }

    private void paintLiquid(Graphics2D g, int i, float cell, float height) {
        float w = Math.min(single ? 86f : 68f, cell * .69f);
        float h = height - 44, x = (i + .5f) * cell - w / 2, y = 11;
        float bottom = y + h;
        Shape capsule = DesktopUi.rounded(x, y, w, h, w * .46f);
        g.setColor(theme.track);
        g.fill(capsule);
        float fraction = fraction(i);
        if (fraction > 0) {
            Color high = charging(i) ? new Color(0xFFC46B) : levels[i] <= 20 ? new Color(0xFF8A87) : theme.accent;
            Color low = charging(i) ? new Color(0xC77A0A) : levels[i] <= 20 ? new Color(0xB3302E) : theme.pill;
            float level = bottom - h * fraction;
            float amp = fraction < 1 ? 3 * Math.min(1, Math.min(fraction, 1 - fraction) * 8f + .15f) : 0;
            Graphics2D clipped = (Graphics2D) g.create();
            clipped.clip(capsule);
            clipped.setColor(DesktopTheme.mix(low, theme.track, .42f));
            clipped.fill(wave(x, w, bottom, level - 1.4f, amp, phase + (float) Math.PI));
            clipped.setPaint(new GradientPaint(0, level, high, 0, bottom, low));
            clipped.fill(wave(x, w, bottom, level, amp, phase));
            clipped.dispose();
        }
        g.setColor(DesktopTheme.alpha(theme.muted, 37));
        g.fill(DesktopUi.rounded(x + w * .16f, y + h * .17f, 3, h * .31f, 1.5f));
        g.setColor(DesktopTheme.alpha(theme.accent, 76));
        g.setStroke(new BasicStroke(1));
        g.draw(capsule);
        if (known(i)) {
            DesktopUi.drawText(g, percent(i), NUMBER.deriveFont(23f), new Color(0x80000000, true),
                    x + 4, y + 1, w - 8, h, 0);
        }
        DesktopUi.drawText(g, percent(i), NUMBER.deriveFont(23f), known(i) ? theme.text : theme.faint,
                x + 4, y, w - 8, h, 0);
        if (charging(i)) DesktopIcons.BOLT.draw(g, x + w / 2 - 8, y + 9, 16, Color.WHITE);
        capsuleLabel(g, i, cell, bottom + 3, height);
    }

    private void capsuleLabel(Graphics2D g, int i, float cell, float y, float height) {
        DesktopUi.drawText(g, strings.batteryLabel(single, i, true), LABEL, theme.muted,
                i * cell + 4, y, cell - 8, height - y - 4, 0);
    }

    private void surface(Graphics2D g, float x, float y, float w, float h) {
        Shape shape = DesktopUi.rounded(x + .5f, y + .5f, w - 1, h - 1, theme.radius);
        g.setColor(theme.card);
        g.fill(shape);
        g.setColor(theme.border);
        g.setStroke(new BasicStroke(1));
        g.draw(shape);
    }

    private static Path2D wave(float x, float width, float bottom, float level, float amp, float phase) {
        Path2D p = new Path2D.Float();
        p.moveTo(x, bottom);
        for (int step = 0; step <= 32; step++) {
            float fraction = step / 32f;
            p.lineTo(x + width * fraction, level + amp * Math.sin(2 * Math.PI * fraction / 1.15 + phase));
        }
        p.lineTo(x + width, bottom);
        p.closePath();
        return p;
    }

    private static Path2D roundedLoop(float x, float y, float w, float h, float r) {
        Path2D p = new Path2D.Float();
        p.moveTo(x + w / 2, y);
        p.lineTo(x + w - r, y);
        p.quadTo(x + w, y, x + w, y + r);
        p.lineTo(x + w, y + h - r);
        p.quadTo(x + w, y + h, x + w - r, y + h);
        p.lineTo(x + r, y + h);
        p.quadTo(x, y + h, x, y + h - r);
        p.lineTo(x, y + r);
        p.quadTo(x, y, x + r, y);
        p.lineTo(x + w / 2, y);
        return p;
    }

    private static Path2D traceFraction(Path2D ring, float fraction) {
        if (fraction == 1) return ring;
        double[] xy = new double[2];
        double total = 0, x = 0, y = 0;
        for (PathIterator it = ring.getPathIterator(null, .4); !it.isDone(); it.next()) {
            if (it.currentSegment(xy) == PathIterator.SEG_LINETO) total += Math.hypot(xy[0] - x, xy[1] - y);
            x = xy[0];
            y = xy[1];
        }
        Path2D result = new Path2D.Float();
        double remaining = total * fraction;
        for (PathIterator it = ring.getPathIterator(null, .4); !it.isDone(); it.next()) {
            if (it.currentSegment(xy) == PathIterator.SEG_MOVETO) {
                result.moveTo(xy[0], xy[1]);
            } else {
                double length = Math.hypot(xy[0] - x, xy[1] - y);
                if (length > remaining) {
                    double ratio = remaining / length;
                    result.lineTo(x + (xy[0] - x) * ratio, y + (xy[1] - y) * ratio);
                    break;
                }
                result.lineTo(xy[0], xy[1]);
                remaining -= length;
            }
            x = xy[0];
            y = xy[1];
        }
        return result;
    }

    private int count() { return single ? 1 : 3; }
    private boolean known(int i) { return DesktopStrings.validBattery(levels[i]); }
    private float fraction(int i) { return known(i) ? levels[i] / 100f : 0; }
    private boolean charging(int i) { return DesktopStrings.charging(charge, i); }
    private String percent(int i) { return DesktopStrings.percent(levels[i]); }
    private Color batteryColor(int i) {
        return charging(i) ? theme.warning : levels[i] <= 20 ? theme.danger : theme.accent;
    }
}
