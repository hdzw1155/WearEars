package com.hdz.wearcontrol;

/** 可选品牌模块。识别与工厂由该品牌提供，核心不引用任何具体协议类。 */
public interface DriverModule {
    boolean matches(String deviceName);
    TwsClient create();
}
