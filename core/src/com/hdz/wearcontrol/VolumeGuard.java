package com.hdz.wearcontrol;

/** 所有 UI 共用：锁定时可调小，增大须解锁；70% 按实际档位向下取整。 */
final class VolumeGuard {
    private boolean unlocked, highAllowed, highConfirmation;

    boolean isUnlocked() { return unlocked; }
    boolean needsHighConfirmation() { return highConfirmation; }

    void lock() { unlocked = false; highAllowed = false; highConfirmation = false; }
    void unlock() {
        if (highConfirmation) highAllowed = true;
        highConfirmation = false;
        unlocked = true;
    }

    void observe(int current, int max) {
        if (max <= 0) return;
        current = Math.max(0, Math.min(max, current));
        int boundary = (int) (max * 7L / 10L);
        if (current < boundary) {
            highAllowed = false;
            highConfirmation = false;
        }
    }

    int limit(int current, int requested, int max) {
        if (max <= 0) return 0;
        current = Math.max(0, Math.min(max, current));
        requested = Math.max(0, Math.min(max, requested));
        int boundary = (int) (max * 7L / 10L);
        if (requested < current && requested < boundary) {
            // 回到低音量后重新建立保护，不能沿用上一次的高音量授权。
            highAllowed = false;
            highConfirmation = false;
        }
        if (!unlocked) return Math.min(current, requested);
        if (!highAllowed && requested > current && requested >= boundary) {
            unlocked = false;
            highConfirmation = true;
            return Math.max(current, boundary);
        }
        return requested;
    }
}
