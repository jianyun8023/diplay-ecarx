# Entry point launched by app_process from the installed application classpath.
-keep class com.shihab.diplay.mfi.LocalMfiProbe {
    public static void main(java.lang.String[]);
}

# linux_i2c_jni.c looks up this exception and constructor by name.
-keep class com.shihab.diplay.transport.LinuxI2cNativeException {
    public <init>(int, java.lang.String);
}

# libdiplay_media calls these from its receiver threads by name (see NativeMedia).
-keepclassmembers class * extends com.shihab.diplay.airplay.NativeStreamCallbacks {
    void onPacket(long, int, int, long, long);
    void onAudioStarted(int);
    void onAudioWire(byte[], byte[], long, int);
    void onAudioClock(int);
    void onAudioQueueEvent(int, long, long);
    void onConfig(byte[]);
    void onReceiving();
    void onLog(int, java.lang.String);
    void onReport(java.lang.String);
    void onEnded(java.lang.String);
}
-keepclassmembers class * extends com.shihab.diplay.airplay.NativeVideoCallbacks {
    void onEvent(int, java.lang.String);
}

# The wired deadline passes opaque values through these JNI entry points.
-keep class com.shihab.diplay.transport.NativeControlDeadline {
    native <methods>;
}

# Opaque recovery tokens use these primitive JNI entry points.
-keep class com.shihab.diplay.orchestration.NativeWirelessRecovery {
    native <methods>;
}

# The route JNI boundary owns a handle and constructs the existing maneuver DTO.
-keep class com.shihab.diplay.hud.NativeRouteGuidance {
    native <methods>;
}
-keep class com.shihab.diplay.hud.AppleManeuver {
    public <init>(int, int, int, java.lang.String, java.lang.Long, java.lang.Long, java.lang.Long, java.lang.Long);
}

# Pair-verify calls the live platform store and returns its existing key DTO.
-keep class com.shihab.diplay.airplay.NativePairVerify {
    native <methods>;
    public static byte[] lookup(com.shihab.diplay.airplay.PairingStore, byte[]);
}
-keepnames class com.shihab.diplay.airplay.PairingStore
-keep class com.shihab.diplay.airplay.PairVerify$ControlKeys {
    public <init>(byte[], byte[]);
}

# The control cipher JNI returns the existing plaintext/rest DTO.
-keep class com.shihab.diplay.airplay.ControlCipher$Decrypted {
    public <init>(byte[], byte[]);
}
# The RTSP JNI codec constructs the existing request/result DTOs directly.
-keep class com.shihab.diplay.airplay.RtspMessage$Request {
    public <init>(java.lang.String, java.lang.String, java.lang.String, java.util.Map, byte[]);
}
-keep class com.shihab.diplay.airplay.RtspMessage$Parsed {
    public <init>(java.util.List, byte[]);
}

# Input/vehicle conversion and platform-output accounting use primitive JNI entry points.
-keep class com.shihab.diplay.airplay.NativeTouchSlots {
    native <methods>;
}
-keep class com.shihab.diplay.transport.NativeVehicleSpeed {
    native <methods>;
}
-keep class com.shihab.diplay.media.NativeAudioScheduler {
    native <methods>;
}

# HUD/cluster pure conversion tables (adapters stay Kotlin).
-keep class com.shihab.diplay.hud.NativeClusterArrow {
    native <methods>;
}
-keep class com.shihab.diplay.hud.NativeLaneIcon {
    native <methods>;
}
-keep class com.shihab.diplay.hud.NativeDayNight {
    native <methods>;
}

# IdentificationInformation vehicle components pure TLV encoder.
-keep class com.shihab.diplay.transport.NativeVehicleIdentification {
    native <methods>;
}

# iAP2 body parameter framing and the DTO its native parser constructs.
-keep class com.shihab.diplay.iap2.wire.NativeIap2Csm {
    native <methods>;
}
-keep class com.shihab.diplay.iap2.wire.Iap2Frame {
    public <init>(int, byte[]);
}
-keep class com.shihab.diplay.iap2.wire.NativeIap2Body {
    native <methods>;
}
-keep class com.shihab.diplay.iap2.wire.Iap2Parameter {
    public <init>(int, byte[]);
}
-keep class com.shihab.diplay.iap2.wire.Iap2ProtocolException {
    public <init>(java.lang.String, java.lang.Throwable);
}

-keep class com.shihab.diplay.transport.NativeIap2Link { native <methods>; }

-keep class com.shihab.diplay.iap2.wire.NativeIap2Wire { native <methods>; }

# PairSetup keeps the live platform save callback and the store's binary class name.
-keep class com.shihab.diplay.airplay.NativePairSetup {
    native <methods>;
    public static void save(com.shihab.diplay.airplay.PairingStore, byte[], byte[]);
}

# Native stateless crypto/DER bridge. Byte-array results need no obfuscated DTO constructors.
-keep class com.shihab.diplay.airplay.NativeCrypto { native <methods>; }

-keep class com.shihab.diplay.airplay.NativeNtpTiming { native <methods>; }
-keep class com.shihab.diplay.airplay.NativeAirPlayNegotiation { native <methods>; }

-keep class com.shihab.diplay.media.NativeVideoPolicy { native <methods>; }
-keep class com.shihab.diplay.media.NativeVideoTiming { native <methods>; }
-keep class com.shihab.diplay.media.NativeVideoFlow { native <methods>; }
-keep class com.shihab.diplay.airplay.NativeScreenCodec { native <methods>; }
-keep class com.shihab.diplay.airplay.NativeMicrophonePacketizer { native <methods>; }

-keep class com.shihab.diplay.orchestration.NativeCarPlayController { native <methods>; }
-keep class com.shihab.diplay.transport.NativeIap2Handshake { native <methods>; }
-keep class com.shihab.diplay.orchestration.NativeStartupWatchdog { native <methods>; }

# USBMUX bridges return only bootstrap arrays/strings; protocol exception has a String-only ctor.
-keep class com.shihab.diplay.transport.NativeUsbMux { native <methods>; }
-keep class com.shihab.diplay.transport.NativeNcm { native <methods>; }
-keep class com.shihab.diplay.transport.IphoneUsbException$Protocol {
    public <init>(java.lang.String);
}
-keep class com.shihab.diplay.transport.NativeLockdown { native <methods>; }
-keepclassmembers class com.shihab.diplay.transport.LockdownXmlTokens {
    public java.lang.Object[] next(int);
    public long[] integer(java.lang.String);
    public java.lang.String data(java.lang.String);
}
-keepclassmembers class com.shihab.diplay.transport.LockdownXmlInput {
    public java.lang.Object[] next();
}
-keepclassmembers class com.shihab.diplay.transport.LockdownIo {
    public long now();
    public void verify();
    public byte[] recv(int, long);
}
-keep class com.shihab.diplay.transport.NativeIap2FileTransfer { native <methods>; }
-keep class com.shihab.diplay.airplay.NativeDataStream { native <methods>; }
-keep class com.shihab.diplay.airplay.NativeMfiSap { native <methods>; }
-keep class com.shihab.diplay.airplay.NativeIapStreamQueue { native <methods>; }
-keep class com.shihab.diplay.media.NativeAac { native <methods>; }
-keepclassmembers class com.shihab.diplay.airplay.MfiSapPlatform {
    public int kind();
    public byte[] certificate();
    public int major();
    public byte[] sign(byte[]);
    public byte[][] baa();
}

-keep class com.shihab.diplay.transport.NativeIap2ReportSubscription {
    native <methods>;
}
-keep class com.shihab.diplay.transport.NativeNmeaLocation {
    native <methods>;
}

-keep class com.shihab.diplay.media.NativePlaybackCore { *; }
-keep class com.shihab.diplay.media.CarPlayNowPlaying {
    public <init>(java.lang.String, java.lang.String, java.lang.String, java.lang.Integer, java.lang.String, java.lang.Long, java.lang.Long, boolean);
}

# Shared call PCM/clock ownership and CarPlay-to-platform route decisions.
-keep class com.shihab.diplay.media.NativeEchoReference { native <methods>; }
-keep class com.shihab.diplay.media.NativeAudioRouting { native <methods>; }
-keep class com.shihab.diplay.network.NativeP2pStartup { native <methods>; }

-keep class com.shihab.diplay.network.NativeHotspotAddress { native <methods>; }

# AirPlay capability declarations and event/control bodies use this JNI boundary.
-keep class com.shihab.diplay.airplay.NativeAirPlayProtocol { native <methods>; }

-keep class com.shihab.diplay.network.NativeHotspotRecovery { native <methods>; }

-keep class com.shihab.diplay.iap2.message.NativeLaneGuidance { native <methods>; }
