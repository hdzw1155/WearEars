package com.hdz.wearcontrol.desktop;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.UIManager;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.text.View;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

final class DesktopUi {
    final DesktopTheme theme;
    final DesktopStrings strings;

    DesktopUi(DesktopTheme theme, String language) {
        this.theme = theme;
        strings = new DesktopStrings(language);
    }

    String t(String zh, String en) {
        return strings.text(zh, en);
    }

    void applyDefaults() {
        UIManager.put("OptionPane.background", theme.card);
        UIManager.put("OptionPane.messageForeground", theme.text);
        UIManager.put("OptionPane.messageFont", DesktopTheme.font(Font.PLAIN, 13));
        UIManager.put("OptionPane.buttonFont", DesktopTheme.font(Font.BOLD, 12));
        UIManager.put("Panel.background", theme.card);
        UIManager.put("Button.background", theme.track);
        UIManager.put("Button.foreground", theme.text);
        UIManager.put("Button.font", DesktopTheme.font(Font.BOLD, 12));
        UIManager.put("Label.font", DesktopTheme.font(Font.PLAIN, 13));
        UIManager.put("ToolTip.font", DesktopTheme.font(Font.PLAIN, 12));
        UIManager.put("ToolTip.background", theme.card);
        UIManager.put("ToolTip.foreground", theme.text);
        UIManager.put("ToolTip.border", BorderFactory.createLineBorder(theme.border));
        UIManager.put("ComboBox.disabledBackground", theme.track);
        UIManager.put("ComboBox.disabledForeground", theme.faint);
    }

    Stack card(String title) {
        Stack panel = new Stack(theme, 8, 14);
        if (!title.isEmpty()) {
            Note heading = new Note(title, theme.text);
            heading.setFont(DesktopTheme.font(Font.BOLD, 14));
            panel.add(heading);
        }
        return panel;
    }

    Stack stack(int gap) {
        return new Stack(null, gap, 0);
    }

    Page page() {
        Page page = new Page();
        page.setBackground(theme.page);
        return page;
    }

    JLabel label(String text, int size, boolean bold, Color color) {
        JLabel label = new JLabel();
        label.putClientProperty("html.disable", Boolean.TRUE);
        label.setFont(DesktopTheme.font(bold ? Font.BOLD : Font.PLAIN, size));
        label.setForeground(color);
        label.setText(text);
        return label;
    }

    Note note(String text) {
        return new Note(text, theme.muted);
    }

    JTextArea logArea() {
        JTextArea area = new JTextArea();
        area.setFont(DesktopTheme.font(Font.PLAIN, 12));
        area.setForeground(theme.text);
        area.setBackground(theme.track);
        area.setSelectionColor(theme.pill);
        area.setSelectedTextColor(theme.onPill);
        area.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        area.setEditable(false);
        area.setLineWrap(true);
        return area;
    }

    JButton button(String text, Runnable action) {
        return button(text, false, action);
    }

    Button button(String text, boolean primary, Runnable action) {
        Button button = new Button(text, theme);
        button.setSelected(primary);
        button.addActionListener(e -> action.run());
        return button;
    }

    Button icon(DesktopIcons icon, String accessibleName, Runnable action) {
        Button button = button("", false, action);
        button.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        button.setPreferredSize(new Dimension(34, 34));
        button.setIcon(icon.icon(17, button::ink));
        nameIcon(button, accessibleName);
        return button;
    }

    static void nameIcon(JButton button, String name) {
        button.setToolTipText(name);
        button.getAccessibleContext().setAccessibleName(name);
    }

    JCheckBox check(String label, boolean selected) {
        JCheckBox box = new JCheckBox(label, selected);
        box.setOpaque(false);
        box.setFont(DesktopTheme.font(Font.PLAIN, 12));
        box.setForeground(theme.text);
        box.setIconTextGap(8);
        box.setIcon(new Icon() {
            @Override public int getIconWidth() { return 17; }
            @Override public int getIconHeight() { return 17; }
            @Override public void paintIcon(Component component, Graphics g, int x, int y) {
                Graphics2D gg = graphics(g);
                try {
                    boolean on = box.isSelected();
                    gg.setColor(on ? theme.pill : theme.track);
                    gg.fillRoundRect(x, y, 17, 17, 5, 5);
                    gg.setColor(box.isFocusOwner() ? theme.accent : theme.border);
                    gg.drawRoundRect(x, y, 16, 16, 5, 5);
                    if (on) {
                        gg.setColor(theme.onPill);
                        gg.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        gg.drawLine(x + 4, y + 8, x + 7, y + 11);
                        gg.drawLine(x + 7, y + 11, x + 13, y + 5);
                    }
                } finally {
                    gg.dispose();
                }
            }
        });
        box.setDisabledIcon(box.getIcon());
        return box;
    }

    JTextField field(JTextField input) {
        input.setFont(DesktopTheme.font(Font.PLAIN, 13));
        input.setForeground(theme.text);
        input.setBackground(theme.track);
        input.setCaretColor(theme.accent);
        input.setSelectionColor(theme.pill);
        input.setSelectedTextColor(theme.onPill);
        input.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(theme.border),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        return input;
    }

    <T> JComboBox<T> combo(JComboBox<T> combo) {
        combo.setFont(DesktopTheme.font(Font.PLAIN, 13));
        combo.setBackground(theme.track);
        combo.setForeground(theme.text);
        combo.setBorder(BorderFactory.createLineBorder(theme.border));
        combo.setMaximumRowCount(10);
        combo.setUI(new BasicComboBoxUI() {
            @Override protected JButton createArrowButton() {
                Button arrow = new Button("", theme);
                arrow.setIcon(DesktopIcons.DOWN.icon(13, arrow::ink));
                arrow.setPreferredSize(new Dimension(29, 29));
                arrow.setFocusable(false);
                return arrow;
            }
        });
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                      boolean selected, boolean focus) {
                JLabel cell = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
                cell.putClientProperty("html.disable", Boolean.TRUE);
                cell.setText(value == null ? "" : value.toString());
                cell.setBackground(selected && index >= 0 ? theme.pill : theme.track);
                cell.setForeground(selected && index >= 0 ? theme.onPill : theme.text);
                cell.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 6));
                return cell;
            }
        });
        return combo;
    }

    void styleList(JList<?> list) {
        list.setFont(DesktopTheme.font(Font.PLAIN, 12));
        list.setBackground(theme.track);
        list.setForeground(theme.text);
        list.setSelectionBackground(theme.pill);
        list.setSelectionForeground(theme.onPill);
        list.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
    }

    Wrap row(Component... items) {
        Wrap row = new Wrap();
        for (Component item : items) row.add(item);
        return row;
    }

    JScrollPane scroll(JComponent view) {
        JScrollPane scroll = new JScrollPane(view, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(theme.page);
        scroll.getVerticalScrollBar().setUI(new BasicScrollBarUI() {
            @Override protected JButton createDecreaseButton(int orientation) { return hiddenArrow(); }
            @Override protected JButton createIncreaseButton(int orientation) { return hiddenArrow(); }
            @Override protected void configureScrollBarColors() {
                thumbColor = theme.border;
                trackColor = theme.page;
            }
            @Override protected void paintThumb(Graphics g, JComponent c, Rectangle bounds) {
                if (bounds.isEmpty() || !c.isEnabled()) return;
                Graphics2D gg = graphics(g);
                gg.setColor(isThumbRollover() ? theme.accent : thumbColor);
                gg.fillRoundRect(bounds.x + 2, bounds.y + 1, bounds.width - 4, bounds.height - 2, 5, 5);
                gg.dispose();
            }
            private JButton hiddenArrow() {
                JButton button = new JButton();
                button.setPreferredSize(new Dimension(0, 0));
                return button;
            }
        });
        JScrollBar bar = scroll.getVerticalScrollBar();
        bar.setPreferredSize(new Dimension(9, 0));
        bar.setUnitIncrement(21);
        return scroll;
    }

    JScrollPane box(JComponent view, int height) {
        JScrollPane scroll = scroll(view);
        scroll.setPreferredSize(new Dimension(0, height));
        scroll.setBorder(BorderFactory.createLineBorder(theme.border));
        scroll.getViewport().setBackground(theme.track);
        return scroll;
    }

    static Graphics2D graphics(Graphics g) {
        Graphics2D gg = (Graphics2D) g.create();
        gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        gg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return gg;
    }

    static Shape rounded(float x, float y, float width, float height, float radius) {
        return new RoundRectangle2D.Float(x, y, width, height, radius * 2, radius * 2);
    }

    static Shape slanted(float x, float y, float width, float height, float skew) {
        Path2D path = new Path2D.Float();
        path.moveTo(x + skew, y);
        path.lineTo(x + width, y);
        path.lineTo(x + width - skew, y + height);
        path.lineTo(x, y + height);
        path.closePath();
        return path;
    }

    static FontMetrics fit(Graphics2D g, String text, Font font, float width, float height) {
        FontMetrics metrics = g.getFontMetrics(font);
        float size = font.getSize2D();
        while ((metrics.stringWidth(text) > width || metrics.getAscent() + metrics.getDescent() > height)
                && size > 1f) {
            font = font.deriveFont(--size);
            metrics = g.getFontMetrics(font);
        }
        g.setFont(font);
        return metrics;
    }

    static void drawText(Graphics2D g, String text, Font font, Color color, float x, float y,
                         float width, float height, int align) {
        if (width <= 0 || height <= 0) return;
        FontMetrics m = fit(g, text, font, width, height);
        float spare = width - m.stringWidth(text);
        float start = x + (align < 0 ? 0 : align > 0 ? spare : spare / 2f);
        g.setColor(color);
        g.drawString(text, start, y + (height + m.getAscent() - m.getDescent()) / 2f);
    }

    static final class Button extends JButton {
        private final DesktopTheme theme;
        private boolean danger;

        Button(String text, DesktopTheme theme) {
            super(text);
            this.theme = theme;
            setFont(DesktopTheme.font(Font.BOLD, 12));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setRolloverEnabled(true);
            setBorder(BorderFactory.createEmptyBorder(7, 12, 7, 12));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            getModel().addChangeListener(e -> setForeground(ink()));
            setForeground(ink());
        }

        void markDanger() {
            danger = true;
            setForeground(ink());
        }

        Color ink() {
            if (!isEnabled()) return theme.faint;
            if (isSelected()) return theme.onPill;
            return danger ? theme.danger : theme.text;
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = graphics(graphics);
            try {
                Color fill = isEnabled() && isSelected() ? theme.pill : theme.track;
                if (isEnabled() && (getModel().isRollover() || getModel().isPressed())) {
                    fill = DesktopTheme.mix(fill, ink(), getModel().isPressed() ? .19f : .10f);
                }
                Shape shape = rounded(.5f, .5f, getWidth() - 1f, getHeight() - 1f,
                        theme == DesktopTheme.SUPREME ? 2 : 14);
                g.setColor(fill);
                g.fill(shape);
                g.setColor(isFocusOwner() ? theme.accent : theme.border);
                g.draw(shape);
                if (isFocusOwner() && isSelected()) {
                    g.setColor(theme.onPill);
                    g.draw(rounded(2.5f, 2.5f, getWidth() - 5f, getHeight() - 5f, 10));
                }
            } finally {
                g.dispose();
            }
            super.paintComponent(graphics);
        }
    }

    private interface WidthAware {
        int heightFor(int width);
    }

    static final class Note extends JTextArea implements WidthAware {
        Note(String text, Color color) {
            super(text);
            setEditable(false);
            setFocusable(false);
            setHighlighter(null);
            setOpaque(false);
            setBorder(null);
            setFont(DesktopTheme.font(Font.PLAIN, 12));
            setForeground(color);
            setLineWrap(true);
            setWrapStyleWord(true);
        }

        @Override public int heightFor(int width) {
            if (getText().isEmpty()) return 0;
            View view = getUI().getRootView(this);
            view.setSize(Math.max(1, width), Integer.MAX_VALUE);
            return (int) Math.ceil(view.getPreferredSpan(View.Y_AXIS));
        }
    }

    static class Stack extends JPanel implements WidthAware {
        private final DesktopTheme cardTheme;
        private final int gap;

        Stack(DesktopTheme theme, int gap, int padding) {
            this.cardTheme = theme;
            this.gap = gap;
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(padding, padding, padding, padding));
            setLayout(new ResponsiveLayout(false));
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (cardTheme == null) return;
            Graphics2D g = graphics(graphics);
            Shape shape = rounded(.5f, .5f, getWidth() - 1f, getHeight() - 1f, cardTheme.radius);
            g.setColor(cardTheme.card);
            g.fill(shape);
            g.setColor(cardTheme.border);
            g.draw(shape);
            g.dispose();
        }

        @Override public int heightFor(int width) {
            return measureStack(width, this, gap, false);
        }
    }

    static final class Wrap extends JPanel implements WidthAware {
        Wrap() {
            setOpaque(false);
            setLayout(new ResponsiveLayout(true));
        }

        @Override public int heightFor(int width) {
            return measureWrap(width, this, false);
        }
    }

    static final class Page extends Stack implements Scrollable {
        Page() {
            super(null, 11, 14);
            setOpaque(true);
        }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int axis, int direction) { return 21; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int axis, int direction) {
            return Math.max(21, r.height - 35);
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private static final class ResponsiveLayout implements LayoutManager {
        private final boolean wrap;
        ResponsiveLayout(boolean wrap) { this.wrap = wrap; }
        @Override public void addLayoutComponent(String name, Component component) { }
        @Override public void removeLayoutComponent(Component component) { }
        @Override public Dimension minimumLayoutSize(Container parent) { return new Dimension(0, 0); }
        @Override public Dimension preferredLayoutSize(Container parent) {
            int width = parent.getParent() instanceof JViewport ? parent.getParent().getWidth() : parent.getWidth();
            if (width <= 0) width = 380;
            return new Dimension(width, ((WidthAware) parent).heightFor(width));
        }
        @Override public void layoutContainer(Container parent) {
            if (wrap) measureWrap(parent.getWidth(), parent, true);
            else measureStack(parent.getWidth(), parent, ((Stack) parent).gap, true);
        }
    }

    private static int measureStack(int width, Container stack, int gap, boolean layout) {
        Insets insets = stack.getInsets();
        int inner = Math.max(0, width - insets.left - insets.right);
        int y = insets.top;
        boolean hasItem = false;
        for (Component child : stack.getComponents()) {
            if (!child.isVisible()) continue;
            int height = child instanceof WidthAware ? ((WidthAware) child).heightFor(inner)
                    : child.getPreferredSize().height;
            if (height == 0) {
                if (layout) child.setBounds(insets.left, y, inner, 0);
                continue;
            }
            if (hasItem) y += gap;
            if (layout) child.setBounds(insets.left, y, inner, height);
            y += height;
            hasItem = true;
        }
        return y + insets.bottom;
    }

    private static int measureWrap(int width, Container row, boolean layout) {
        int x = 0, y = 0, lineHeight = 0;
        for (Component child : row.getComponents()) {
            if (!child.isVisible()) continue;
            Dimension size = child.getPreferredSize();
            int w = Math.min(Math.max(0, width), size.width);
            if (x > 0 && x + w > width) {
                x = 0;
                y += lineHeight + 6;
                lineHeight = 0;
            }
            if (layout) child.setBounds(x, y, w, size.height);
            x += w + 6;
            lineHeight = Math.max(lineHeight, size.height);
        }
        return y + lineHeight;
    }
}
