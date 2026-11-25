package com.hdz.wearcontrol;

/** 驱动声明可选择的内置音效。ID 保持品牌协议原值。 */
public final class SoundPreset {
    public final int id;
    public final String chinese, english;
    public SoundPreset(int id, String chinese, String english) {
        this.id = id;
        this.chinese = chinese;
        this.english = english;
    }
    public static SoundPreset[] vivo() {
        return new SoundPreset[]{new SoundPreset(0, "标准", "Standard"),
                new SoundPreset(1, "人声", "Vocals"), new SoundPreset(2, "低音", "Bass"),
                new SoundPreset(3, "高音", "Treble"), new SoundPreset(5, "听书", "Audiobook")};
    }
}
