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

# The wired deadline passes opaque values through these JNI entry points.
-keep class com.shilapi.xcertplay.transport.NativeControlDeadline {
    native <methods>;
}

# Opaque recovery tokens use these primitive JNI entry points.
-keep class com.shilapi.xcertplay.orchestration.NativeWirelessRecovery {
    native <methods>;
}

# The route JNI boundary owns a handle and constructs the existing maneuver DTO.
-keep class com.shilapi.xcertplay.hud.NativeRouteGuidance {
    native <methods>;
}
-keep class com.shilapi.xcertplay.hud.AppleManeuver {
    public <init>(int, int, int, java.lang.String, java.lang.Long, java.lang.Long, java.lang.Long, java.lang.Long);
}

# Pair-verify calls the live platform store and returns its existing key DTO.
-keep class com.shilapi.xcertplay.airplay.NativePairVerify {
    native <methods>;
    public static byte[] lookup(com.shilapi.xcertplay.airplay.PairingStore, byte[]);
}
-keepnames class com.shilapi.xcertplay.airplay.PairingStore
-keep class com.shilapi.xcertplay.airplay.PairVerify$ControlKeys {
    public <init>(byte[], byte[]);
}

# The control cipher JNI returns the existing plaintext/rest DTO.
-keep class com.shilapi.xcertplay.airplay.ControlCipher$Decrypted {
    public <init>(byte[], byte[]);
}
# The RTSP JNI codec constructs the existing request/result DTOs directly.
-keep class com.shilapi.xcertplay.airplay.RtspMessage$Request {
    public <init>(java.lang.String, java.lang.String, java.lang.String, java.util.Map, byte[]);
}
-keep class com.shilapi.xcertplay.airplay.RtspMessage$Parsed {
    public <init>(java.util.List, byte[]);
}

# Input/vehicle conversion and platform-output accounting use primitive JNI entry points.
-keep class com.shilapi.xcertplay.airplay.NativeTouchSlots {
    native <methods>;
}
-keep class com.shilapi.xcertplay.transport.NativeVehicleSpeed {
    native <methods>;
}
-keep class com.shilapi.xcertplay.media.NativeAudioScheduler {
    native <methods>;
}

# HUD/cluster pure conversion tables (adapters stay Kotlin).
-keep class com.shilapi.xcertplay.hud.NativeClusterArrow {
    native <methods>;
}
-keep class com.shilapi.xcertplay.hud.NativeLaneIcon {
    native <methods>;
}
-keep class com.shilapi.xcertplay.hud.NativeDayNight {
    native <methods>;
}

# IdentificationInformation vehicle components pure TLV encoder.
-keep class com.shilapi.xcertplay.transport.NativeVehicleIdentification {
    native <methods>;
}

# iAP2 body parameter framing and the DTO its native parser constructs.
-keep class com.shilapi.xcertplay.iap2.wire.NativeIap2Body {
    native <methods>;
}
-keep class com.shilapi.xcertplay.iap2.wire.Iap2Parameter {
    public <init>(int, byte[]);
}
