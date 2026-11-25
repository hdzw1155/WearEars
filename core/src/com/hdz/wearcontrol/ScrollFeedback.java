package com.hdz.wearcontrol;

/** 按实际位移决定微震和边界反馈；不积压脉冲，时间由调用方提供，便于离线验证。 */
final class ScrollFeedback {
    static final int NONE = 0, TICK = 1, LIMIT = 2;
    private static final long TICK_INTERVAL = 28L;
    private static final long LIMIT_HOLD = 120L;
    private final float tickDistance;
    private float distance;
    private int edge, direction;
    private long lastTick = -1000L, lastLimit = -1000L;

    ScrollFeedback(float tickDistance) { this.tickDistance = Math.max(1f, tickDistance); }

    int scrolled(int y, int oldY, int max, long now) {
        if (max <= 0 || y == oldY) return NONE;
        if (y <= 0 && y < oldY) return limit(-1, now);
        if (y >= max && y > oldY) return limit(1, now);
        edge = 0;
        int nextDirection = y > oldY ? 1 : -1;
        if (direction != nextDirection) distance = 0f;
        direction = nextDirection;
        distance += Math.abs(y - oldY);
        if (distance < tickDistance) return NONE;
        // 合并事件只出一次反馈，丢掉已错过的颗粒，避免马达排队或同帧互相覆盖。
        distance %= tickDistance;
        if (now - lastTick < TICK_INTERVAL || now - lastLimit < LIMIT_HOLD) return NONE;
        lastTick = now;
        return TICK;
    }

    int limit(int side, long now) {
        distance = 0f;
        direction = 0;
        if (edge == side || now - lastLimit < LIMIT_HOLD) return NONE;
        edge = side;
        lastLimit = lastTick = now;
        return LIMIT;
    }

    boolean atLimit() { return edge != 0; }

    void beginGesture() {
        distance = 0f;
        direction = edge = 0;
    }

    void reset() {
        beginGesture();
        lastTick = lastLimit = -1000L;
    }
}
