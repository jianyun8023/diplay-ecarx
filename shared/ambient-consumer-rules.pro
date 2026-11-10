# Entry point launched by app_process from the installed application classpath.
-keep class com.shilapi.xcertplay.mfi.LocalMfiProbe {
    public static void main(java.lang.String[]);
}

# linux_i2c_jni.c looks up this exception and constructor by name.
-keep class com.shilapi.xcertplay.transport.LinuxI2cNativeException {
    public <init>(int, java.lang.String);
}
