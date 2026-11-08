package com.ecarx.xui.adaptapi.device.daynigntmode;

/** Independent test contract for the day/night adapter; no vendor implementation. */
public interface IDayNightMode {
    int getDayNight();
    int getDayNightMode();
    boolean registerDayNightModeCallBack(IDayNightChangeCallBack callback);
    boolean unregisterDayNigntModeCallBack(IDayNightChangeCallBack callback);

    interface IDayNightChangeCallBack {
        void onDayNightChanged(int value);
        void onDayNightModeChange(int value);
    }
}
