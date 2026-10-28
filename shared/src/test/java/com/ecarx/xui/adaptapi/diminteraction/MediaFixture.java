package com.ecarx.xui.adaptapi.diminteraction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MediaFixture implements IMediaInteraction {
    public void updateCurrentSourceType(int sourceType) {
        FixtureLog.capture("updateCurrentSourceType", "sourceType", sourceType);
    }
    public void updateMediaSourceTypeList(int[] sourceTypes) {
        List<Integer> values = new ArrayList<>();
        for (int value : sourceTypes) values.add(value);
        FixtureLog.capture("updateMediaSourceTypeList", "sourceTypes", values);
    }
    public void updateMediaMuteState(int muted) {
        FixtureLog.capture("updateMediaMuteState", "muted", muted);
    }
    public void updatePlaybackInfo(IPlaybackInfo info) {
        Map<String, Object> p = readMedia(info);
        p.put("getPlaybackStatus", info.getPlaybackStatus());
        p.put("getNextArtwork", info.getNextArtwork());
        p.put("getPreviousArtwork", info.getPreviousArtwork());
        p.put("getCurrentLyricSentence", info.getCurrentLyricSentence());
        p.put("getLoopMode", info.getLoopMode());
        p.put("getPlayingItemPositionInQueue", info.getPlayingItemPositionInQueue());
        FixtureLog.capturePayload("updatePlaybackInfo", p);
    }
    public void updatePlaylist(int sourceType, List<IMedia> items) {
        List<Map<String, Object>> snapshots = new ArrayList<>();
        for (IMedia item : items) snapshots.add(readMedia(item));
        FixtureLog.capture("updatePlaylist", "sourceType", sourceType, "items", snapshots);
    }
    public void updateCurrentProgress(long millis) {
        FixtureLog.capture("updateCurrentProgress", "millis", millis);
    }
    private static Map<String, Object> readMedia(IMedia info) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("getTitle", info.getTitle());
        p.put("getArtist", info.getArtist());
        p.put("getAlbum", info.getAlbum());
        p.put("getDuration", info.getDuration());
        p.put("getSourceType", info.getSourceType());
        p.put("getArtwork", info.getArtwork());
        p.put("getLyric", info.getLyric());
        p.put("getMediaPath", info.getMediaPath());
        p.put("getLyricContent", info.getLyricContent());
        p.put("getRadioFrequency", info.getRadioFrequency());
        p.put("getRadioStationName", info.getRadioStationName());
        p.put("getUUID", info.getUUID());
        p.put("getFavoriteState", info.getFavoriteState());
        p.put("getRadioMode", info.getRadioMode());
        return p;
    }
}
