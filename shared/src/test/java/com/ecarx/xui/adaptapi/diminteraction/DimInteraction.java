package com.ecarx.xui.adaptapi.diminteraction;

import android.content.Context;

public final class DimInteraction {
    private static final DimInteraction INSTANCE = new DimInteraction();
    public static boolean nullCreate, nullMedia, nullNavi, nullPhone;
    public static boolean requireLooper;
    public static boolean failPresentation;
    public static final MediaFixture media = new MediaFixture();
    public static final NaviFixture navi = new NaviFixture();
    public static final PhoneFixture phone = new PhoneFixture();
    public static int presentationOption = -1;
    public IInteractionCallback callback;

    public interface IInteractionCallback { void onShowPresentationOptionChanged(int option); }

    public static DimInteraction instance() { return INSTANCE; }
    public static DimInteraction create(Context context) {
        if (requireLooper && android.os.Looper.myLooper() == null) {
            throw new IllegalStateException("DIM requires a Looper");
        }
        FixtureLog.capture("create", "looper", android.os.Looper.myLooper());
        return nullCreate ? null : INSTANCE;
    }
    public static void reset() {
        android.cevt.dimsupportservice.DimSupportManager.reset();
        nullCreate = nullMedia = nullNavi = nullPhone = false;
        requireLooper = false;
        failPresentation = false;
        presentationOption = -1;
        INSTANCE.callback = null;
        navi.callback = null;
        navi.ongoingNavigationActive = false;
        navi.handlerNavigationActive = false;
        navi.resendOngoing = false;
        navi.navigationMute = false;
        navi.cachedArrowId = -1;
        navi.cachedArrowSvg = "";
        navi.ongoingTransitions.clear();
        navi.navigationEvents.clear();
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
        navi.sendNavigationEvent(show ? "firstFrameReady" : "naviAppOff");
        if (show && navi.ongoingNavigationActive) navi.resendOngoing = true;
    }
    public void registerInteractionCallback(IInteractionCallback value) {
        FixtureLog.capture("registerInteractionCallback");
        callback = value;
    }
}
