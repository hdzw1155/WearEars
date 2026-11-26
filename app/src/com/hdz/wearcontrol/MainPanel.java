package com.hdz.wearcontrol;

/** 页面功能模块只接收会话数据，通过 Actions 发出用户意图。 */
interface MainPanel {
    interface Actions {
        void selectNoise(int mode);
        void selectSpatial(boolean enabled);
        void selectEqualizer(int preset);
        void selectFeature(FeatureId id, int value);
        void stopFindBuds();
        int pendingFeature(FeatureId id);
        default boolean isFindBudsRinging() { return false; }
    }
    void render(DeviceState state, int pendingMode);
    default void resume() { }
    default void pause() { }
    default void volume(int level, int max) { }
    default void refreshVolume() { }
}
