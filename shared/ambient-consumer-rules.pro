# Entry point launched by app_process from the installed application classpath.
-keep class com.shilapi.xcertplay.mfi.LocalMfiProbe {
    public static void main(java.lang.String[]);
}

# linux_i2c_jni.c looks up this exception and constructor by name.
-keep class com.shilapi.xcertplay.transport.LinuxI2cNativeException {
    public <init>(int, java.lang.String);
}

# libdiplay_media calls these from its receiver threads by name (see NativeMedia).
-keepclassmembers class * extends com.shilapi.xcertplay.airplay.NativeStreamCallbacks {
    void onPacket(long, int, int, long, long);
    void onConfig(byte[]);
    void onReceiving();
    void onLog(int, java.lang.String);
    void onReport(java.lang.String);
    void onEnded(java.lang.String);
}
-keepclassmembers class * extends com.shilapi.xcertplay.airplay.NativeVideoCallbacks {
    void onEvent(int, java.lang.String);
}
