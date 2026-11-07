package com.hdz.wearcontrol;

/** 滑块位置与长度的弹性；不依赖刷新率，停稳后不再请求帧。 */
final class ScrollIndicatorMotion {
    private static final long BOUNCE_MS = 420L;
    private float position, velocity, stretch = 1f;
    private long lastFrame = -1L, lastPulse = -1000L;

    void reset(float value) {
        position = clamp(value);
        velocity = 0f;
        stretch = 1f;
        lastFrame = -1L;
        lastPulse = -1000L;
    }

    void pulse(long now) {
        lastPulse = now;
        if (lastFrame < 0L) lastFrame = now;
    }

    boolean advance(float target, long now) {
        float goal = clamp(target);
        float elapsed = lastFrame < 0L ? 0f : Math.max(0L, Math.min(64L, now - lastFrame));
        lastFrame = now;
        // 小步积分保持 30/60/120Hz 的弹簧形态一致，不把低帧率误当成大力甩动。
        int steps = Math.max(1, (int) Math.ceil(elapsed / 8f));
        float dt = elapsed / steps / 1000f;
        for (int i = 0; i < steps; i++) {
            float acceleration = (goal - position) * 484f - velocity * 23f;
            velocity += acceleration * dt;
            position = Math.max(-0.08f, Math.min(1.08f, position + velocity * dt));
        }
        boolean moving = Math.abs(goal - position) > 0.0008f || Math.abs(velocity) > 0.005f;
        if (!moving) { position = goal; velocity = 0f; }
        float length = 1f + Math.min(0.45f, Math.abs(velocity) * 0.08f + Math.abs(goal - position));
        long age = now - lastPulse;
        boolean bouncing = age >= 0L && age < BOUNCE_MS;
        if (bouncing) {
            float t = age / (float) BOUNCE_MS;
            length += 0.26f * (float) Math.sin(t * Math.PI * 2.2f) * (1f - t) * (1f - t);
        }
        stretch = Math.max(0.65f, Math.min(1.65f, length));
        return moving || bouncing;
    }

    float position() { return clamp(position); }
    float stretch() { return stretch; }

    private static float clamp(float value) { return Math.max(0f, Math.min(1f, value)); }
}
