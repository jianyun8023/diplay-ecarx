package com.ecarx.sdk.policy;

public interface IAudioPolicy {
    boolean isNeedrequestMic();
    boolean requestMic(int requestType, IMicResourceCallback callback);
    boolean releaseMic(IMicResourceCallback callback);
}
