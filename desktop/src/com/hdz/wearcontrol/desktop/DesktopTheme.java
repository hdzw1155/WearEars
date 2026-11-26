package com.hdz.wearcontrol.desktop;

import java.awt.Color;
import java.awt.Font;

enum DesktopTheme {
    SUPREME("supreme", "至上主义", "Suprematist",
            0xF4F3EE, 0xFFFFFF, 0x111317, 0x111317, 0x4A505C, 0x666C78,
            0x0D4FB8, 0x0D4FB8, 0xDF1B22, 0xEAE8DF, 0xF6C100, 0xF4F3EE, 2),
    MINIMAL("minimal", "新极简", "New Minimal",
            0x000000, 0x18150E, 0x44351D, 0xFFFFFF, 0xD8C79A, 0x9E906B,
            0xF5B841, 0xF5B841, 0xF0524F, 0x241F14, 0xF5A524, 0x111317, 19),
    CLASSIC("classic", "新经典", "New Classic",
            0x000000, 0x121824, 0x263652, 0xFFFFFF, 0xB8CEEE, 0x7E96BA,
            0x4C8DFF, 0x3A80F5, 0xFA5151, 0x1B2436, 0xFF9F0A, 0xFFFFFF, 19),
    LIQUID("liquid", "新液位", "New Liquid",
            0x000000, 0x111A16, 0x254436, 0xFFFFFF, 0xB8D8CA, 0x7EA393,
            0x5EDDA6, 0x22996A, 0xF0524F, 0x182620, 0xFFB547, 0xFFFFFF, 19);

    static final Color INK = new Color(0x111317);
    static final Color CHROME_YELLOW = new Color(0xF6C100);

    final String key;
    final String zh;
    final String en;
    final Color page, card, border, text, muted, faint, accent, pill, danger, track, warning, onPill;
    final int radius;

    DesktopTheme(String key, String zh, String en, int page, int card, int border, int text, int muted,
                 int faint, int accent, int pill, int danger, int track, int warning, int onPill, int radius) {
        this.key = key;
        this.zh = zh;
        this.en = en;
        this.page = new Color(page);
        this.card = new Color(card);
        this.border = new Color(border);
        this.text = new Color(text);
        this.muted = new Color(muted);
        this.faint = new Color(faint);
        this.accent = new Color(accent);
        this.pill = new Color(pill);
        this.danger = new Color(danger);
        this.track = new Color(track);
        this.warning = new Color(warning);
        this.onPill = new Color(onPill);
        this.radius = radius;
    }

    static DesktopTheme forKey(String key) {
        for (DesktopTheme theme : values()) if (theme.key.equals(key)) return theme;
        return MINIMAL;
    }

    static Font font(int style, int size) {
        return new Font("Microsoft YaHei UI", style, size);
    }

    static Color alpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    static Color mix(Color a, Color b, float weight) {
        float base = 1f - weight;
        return new Color(Math.round(a.getRed() * base + b.getRed() * weight),
                Math.round(a.getGreen() * base + b.getGreen() * weight),
                Math.round(a.getBlue() * base + b.getBlue() * weight));
    }
}
