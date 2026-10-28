package com.ecarx.xui.adaptapi.diminteraction;

public final class PhoneFixture implements IPhoneCallInteraction {
    public void updateCallInfo(ICallInfo i) {
        FixtureLog.capture("updateCallInfo", "getCallId", i.getCallId(),
            "getActiveCallId", i.getActiveCallId(), "getCallCount", i.getCallCount(),
            "getCallStatus", i.getCallStatus(), "getContactName", i.getContactName(),
            "getContactNumber", i.getContactNumber(), "getAvatar", i.getAvatar());
    }
    public void updateConnectedMobileDeviceState(String name, int state, int feature) {
        FixtureLog.capture("updateConnectedMobileDeviceState", "name", name, "state", state, "feature", feature);
    }
}
