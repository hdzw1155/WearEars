package com.hdz.wearcontrol.desktop;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.util.function.Supplier;

enum DesktopIcons {
    REFRESH, PIN, SETTINGS, CLOSE, BACK, DOWN, ANC, TRANSPARENCY, OFF, BOLT;

    Icon icon(int size, Supplier<Color> color) {
        return new Icon() {
            @Override public int getIconWidth() { return size; }
            @Override public int getIconHeight() { return size; }
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                draw((Graphics2D) g, x, y, size, color.get());
            }
        };
    }

    void draw(Graphics2D graphics, float x, float y, float size, Color color) {
        Graphics2D g = DesktopUi.graphics(graphics);
        try {
            g.translate(x, y);
            g.scale(size / 24f, size / 24f);
            g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(color);
            switch (this) {
                case REFRESH:
                    g.draw(new Arc2D.Float(4, 4, 16, 16, 15, 300, Arc2D.OPEN));
                    g.draw(path(20, 4, 20, 10, 14, 10));
                    break;
                case PIN:
                    g.rotate(Math.PI / 5, 12, 12);
                    g.draw(path(7, 3, 17, 3, 15, 6, 15, 11, 18, 14, 6, 14, 9, 11, 9, 6, 7, 3));
                    g.drawLine(12, 14, 12, 21);
                    break;
                case SETTINGS:
                    Path2D gear = new Path2D.Float();
                    for (int i = 0; i < 32; i++) {
                        double theta = (i - .5) * Math.PI / 16;
                        double radius = i % 4 < 2 ? 9.5 : 7.5;
                        double xx = 12 + radius * Math.cos(theta);
                        double yy = 12 + radius * Math.sin(theta);
                        if (i == 0) gear.moveTo(xx, yy); else gear.lineTo(xx, yy);
                    }
                    gear.closePath();
                    g.draw(gear);
                    g.drawOval(9, 9, 6, 6);
                    break;
                case CLOSE:
                    g.drawLine(6, 6, 18, 18);
                    g.drawLine(18, 6, 6, 18);
                    break;
                case BACK:
                    g.draw(path(10, 5, 3, 12, 10, 19));
                    g.drawLine(3, 12, 20, 12);
                    break;
                case DOWN:
                    g.draw(path(5, 9, 12, 16, 19, 9));
                    break;
                case ANC:
                    Path2D shield = path(12, 2, 20, 5, 19, 13, 17, 17, 12, 21,
                            7, 17, 5, 13, 4, 5);
                    shield.closePath();
                    g.draw(shield);
                    g.drawLine(9, 11, 15, 11);
                    break;
                case TRANSPARENCY:
                    g.draw(new Arc2D.Float(6, 5, 12, 13, 118, 327, Arc2D.OPEN));
                    g.draw(new Arc2D.Float(9, 8, 6, 8, 30, 253, Arc2D.OPEN));
                    break;
                case OFF:
                    g.draw(new Arc2D.Float(4, 4, 16, 16, 123, 294, Arc2D.OPEN));
                    g.drawLine(12, 2, 12, 11);
                    break;
                case BOLT:
                    Path2D bolt = path(13, 2.5f, 5.5f, 13.5f, 10.5f, 13.5f,
                            10, 21.5f, 19, 10, 13.5f, 10);
                    bolt.closePath();
                    g.fill(bolt);
                    break;
                default:
                    throw new AssertionError(this);
            }
        } finally {
            g.dispose();
        }
    }

    private static Path2D path(float... xy) {
        Path2D path = new Path2D.Float();
        path.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
        return path;
    }
}
