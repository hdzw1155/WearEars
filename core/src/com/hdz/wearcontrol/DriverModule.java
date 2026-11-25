package com.hdz.wearcontrol;

public interface DriverModule {
    boolean matches(String name);
    WireDriver create();
}
