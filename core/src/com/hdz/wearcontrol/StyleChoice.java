package com.hdz.wearcontrol;

/** Preserve the persisted version-15 IDs and map the three retired styles to their successors. */
final class StyleChoice {
    static final int SUPREME = 3, MINIMAL = 4, CLASSIC = 5, LIQUID = 6, FOLLOW = -1;
    static int resolve(int saved) {
        switch (saved) {
            case 1: case CLASSIC: return CLASSIC;
            case 2: case LIQUID: return LIQUID;
            case 7: case SUPREME: return SUPREME;
            default: return MINIMAL;
        }
    }
    static int tile(int saved) { return saved == FOLLOW ? FOLLOW : resolve(saved); }
    private StyleChoice() { }
}
