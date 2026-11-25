package com.hdz.wearcontrol;

/** A failed version lookup and a changed version both close every action gate. */
final class Consent {
    static boolean current(long installed, long accepted) { return installed > 0 && installed == accepted; }
    private Consent() { }
}
