package com.ecarx.xui.adaptapi.diminteraction;

import android.content.Context;

public final class DimInteraction {
    private static final DimInteraction INSTANCE = new DimInteraction();
    public static boolean nullCreate, nullMedia, nullNavi, nullPhone;
    public static boolean requireMainLooper;
    public static boolean failPresentation;
    public static final MediaFixture media = new MediaFixture();
    public static final NaviFixture navi = new NaviFixture();
    public static final PhoneFixture phone = new PhoneFixture();
    public static int presentationOption = -1;
    public IInteractionCallback callback;

    // Registration ABI only: callback member descriptors have not been recovered.
    public interface IInteractionCallback { }

    public static DimInteraction instance() { return INSTANCE; }
    public static DimInteraction create(Context context) {
        if (requireMainLooper && (android.os.Looper.myLooper() != android.os.Looper.getMainLooper())) {
            throw new IllegalStateException("DIM requires the main Looper");
        }
        FixtureLog.capture("create");
        return nullCreate ? null : INSTANCE;
    }
    public static void reset() {
        nullCreate = nullMedia = nullNavi = nullPhone = false;
        requireMainLooper = false;
        failPresentation = false;
        presentationOption = -1;
        INSTANCE.callback = null;
        navi.callback = null;
        navi.ongoingNavigationActive = false;
        navi.ongoingTransitions.clear();
        FixtureLog.reset();
    }
    public IMediaInteraction getMediaInteraction() {
        FixtureLog.capture("getMediaInteraction");
        return nullMedia ? null : media;
    }
    public INaviInteraction getNaviInteraction() {
        FixtureLog.capture("getNaviInteraction");
        return nullNavi ? null : navi;
    }
    public IPhoneCallInteraction getPhoneCallInteraction() {
        FixtureLog.capture("getPhoneCallInteraction");
        return nullPhone ? null : phone;
    }
    public int getShowPresentationOption() {
        FixtureLog.capture("getShowPresentationOption");
        return presentationOption;
    }
    public void setPresentationToDimSwitch(int screenId, String packageName, String className, boolean show) {
        FixtureLog.capture("setPresentationToDimSwitch", "screenId", screenId,
            "packageName", packageName, "className", className, "show", show);
        if (failPresentation) throw new IllegalStateException("DIM projection unavailable");
    }
    public void registerInteractionCallback(IInteractionCallback value) {
        FixtureLog.capture("registerInteractionCallback");
        callback = value;
    }
}
