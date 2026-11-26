package com.hdz.wearcontrol.desktop;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import java.awt.Dimension;

final class DesktopDialogs {
    private final JFrame frame;
    private DesktopUi ui;
    private int depth;

    DesktopDialogs(JFrame frame) {
        this.frame = frame;
    }

    void apply(DesktopUi ui) { this.ui = ui; }
    boolean active() { return depth > 0; }

    boolean confirm(String title, String message, String yes, String no) {
        return show(title, message, JOptionPane.WARNING_MESSAGE, new Object[] {yes, no}, no) == 0;
    }

    void info(String title, String message, int type) {
        String ok = ui.t("确定", "OK");
        show(title, message, type, new Object[] {ok}, ok);
    }

    private int show(String title, String message, int type, Object[] options, String initial) {
        DesktopUi.Note body = ui.note(message);
        int width = 330;
        body.setPreferredSize(new Dimension(width, body.heightFor(width)));
        ++depth;
        try {
            return JOptionPane.showOptionDialog(frame, body, title, JOptionPane.DEFAULT_OPTION,
                    type, null, options, initial);
        } finally {
            --depth;
        }
    }
}
