package com.ecarx.xui.adaptapi.diminteraction;

import android.graphics.Bitmap;
import android.os.Bundle;
import java.util.List;

public interface INaviInteraction {
    int NAVIGATION_STATUS_UNNAVI = 0;
    int NAVIGATION_STATUS_SUCCEED = 1;
    int NAVIGATION_STATUS_START = 2;
    int NAVIGATION_STATUS_END = 3;
    int TYPE_DESTINATION = 3;
    void updateNavigationInfo(INavigationInfo info);
    void updateTurnByTurnArrow(int arrow);
    void updateTurnByTurnArrow(String svg);
    void updateTurnByTurnArrow(Bitmap bitmap);
    void updateDistanceToNextGuidancePoint(int meters);
    void updateDistanceToDestination(int meters);
    void updateETA(int remainingSeconds);
    void updateNextGuidancePointName(String name);
    void updateAddresses(int type, List<IAddress> addresses);
    void updateLaneInfo(ILaneInfo[] lanes);
    void updateDriveDirection(int direction);
    void updateMuteState(int state);
    void notifyTurnByTurnStarted();
    void notifyTurnByTurnStopped();
    void registerNavigationInteractionCallback(INavigationInteractionCallback callback);

    // Observed on the target head unit and in the shipped 753 navigation plugin.
    interface INavigationInteractionCallback {
        void onDoInteractionAction(int action, IAddress address);
        void onSearchAddress(String query);
    }
    interface IAddress {
        String getAddressName();
        String getCityName();
        long getDistance();
        String getFullAddress();
        double getLatitude();
        double getLongitude();
    }
    interface IHighwayExitInfo { }
    interface IRoadCamera { }
    interface IServiceArea { }
    interface ILaneInfo {
        int getLaneNumber();
        int getLaneBackIconId();
        Bitmap getLaneBackIcon();
    }
    // Target-runtime getters; older firmware reads only its original 13 getters.
    interface INavigationInfo {
        int getDayNightMode();
        Bundle getExtensionInfo();
        int getNavigationTurnId();
        long getDistanceToNextGuidancePoint();
        long getDistanceToDestination();
        int getDrivingDirection();
        long getETA();
        ILaneInfo[] getLaneInfo();
        int getMuteState();
        int getNavigationStatus();
        String getNextGuidancePointName();
        String getNavigationTurnSVG();
        IHighwayExitInfo getHighwayExitInfo();
        IRoadCamera getRoadCameraInfo();
        IServiceArea getServiceAreaInfo();
    }
}
