package com.ecarx.xui.adaptapi.input;

/** Test-only reflection surface; no OEM implementation is packaged in the app. */
public interface IKeyCallback {
    boolean onKeyPressed(int keyCode);
    boolean onKeyReleased(int keyCode);

    // Synthetic future API methods: verify that an unknown callback cannot become a key press.
    boolean onKeyLongPressed(int keyCode);
    int callbackVersion();
    void onServiceChanged();
}
