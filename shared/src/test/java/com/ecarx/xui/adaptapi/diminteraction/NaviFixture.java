package com.ecarx.xui.adaptapi.diminteraction;

import android.graphics.Bitmap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class NaviFixture implements INaviInteraction {
    public INavigationInteractionCallback callback;
    public boolean ongoingNavigationActive;
    public final List<Boolean> ongoingTransitions = new ArrayList<>();
    public void updateNavigationInfo(INavigationInfo i) {
        FixtureLog.capture("updateNavigationInfo",
            "getNavigationTurnId", i.getNavigationTurnId(),
            "getDistanceToNextGuidancePoint", i.getDistanceToNextGuidancePoint(),
            "getDistanceToDestination", i.getDistanceToDestination(),
            "getDrivingDirection", i.getDrivingDirection(), "getETA", i.getETA(),
            "getLaneInfo", i.getLaneInfo(), "getMuteState", i.getMuteState(),
            "getNavigationStatus", i.getNavigationStatus(),
            "getNextGuidancePointName", i.getNextGuidancePointName(),
            "getNavigationTurnSVG", i.getNavigationTurnSVG(),
            "getHighwayExitInfo", i.getHighwayExitInfo(),
            "getRoadCameraInfo", i.getRoadCameraInfo(), "getServiceAreaInfo", i.getServiceAreaInfo());
        // Recovered NaviInteraction only sends ongoing navigation when active changes (mute stays 0 here).
        boolean active = i.getNavigationStatus() == 1;
        if (active != ongoingNavigationActive) {
            ongoingNavigationActive = active;
            ongoingTransitions.add(active);
        }
    }
    public void updateTurnByTurnArrow(int arrow) { FixtureLog.capture("updateTurnByTurnArrow(int)", "arrow", arrow); }
    public void updateTurnByTurnArrow(String svg) { FixtureLog.capture("updateTurnByTurnArrow(String)", "svg", svg); }
    public void updateTurnByTurnArrow(Bitmap bitmap) { FixtureLog.capture("updateTurnByTurnArrow(Bitmap)", "bitmap", bitmap); }
    public void updateDistanceToNextGuidancePoint(int meters) { FixtureLog.capture("updateDistanceToNextGuidancePoint", "meters", meters); }
    public void updateDistanceToDestination(int meters) { FixtureLog.capture("updateDistanceToDestination", "meters", meters); }
    public void updateETA(int epochSeconds) { FixtureLog.capture("updateETA", "epochSeconds", epochSeconds); }
    public void updateNextGuidancePointName(String name) { FixtureLog.capture("updateNextGuidancePointName", "name", name); }
    public void updateLaneInfo(ILaneInfo[] lanes) {
        List<Map<String, Object>> values = null;
        if (lanes != null) {
            values = new ArrayList<>();
            for (ILaneInfo lane : lanes) {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("number", lane.getLaneNumber());
                value.put("iconId", lane.getLaneBackIconId());
                value.put("bitmap", lane.getLaneBackIcon());
                values.add(value);
            }
        }
        FixtureLog.capture("updateLaneInfo", "lanes", values, "count", lanes == null ? null : lanes.length,
            "componentType", ILaneInfo.class.getName());
    }
    public void updateDriveDirection(int direction) { FixtureLog.capture("updateDriveDirection", "direction", direction); }
    public void updateMuteState(int state) { FixtureLog.capture("updateMuteState", "state", state); }
    public void notifyTurnByTurnStarted() { FixtureLog.capture("notifyTurnByTurnStarted"); }
    public void notifyTurnByTurnStopped() { FixtureLog.capture("notifyTurnByTurnStopped"); }
    public void registerNavigationInteractionCallback(INavigationInteractionCallback value) {
        FixtureLog.capture("registerNavigationInteractionCallback"); callback = value;
    }
}
