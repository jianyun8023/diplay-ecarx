package com.ecarx.xui.adaptapi.diminteraction;

import android.net.Uri;

public interface IPhoneCallInteraction {
    void updateCallInfo(ICallInfo info);
    void updateConnectedMobileDeviceState(String name, int state, int feature);
    interface ICallInfo {
        int getCallId();
        int getActiveCallId();
        int getCallCount();
        int getCallStatus();
        String getContactName();
        String getContactNumber();
        Uri getAvatar();
    }
}
