package com.ecarx.xui.adaptapi.diminteraction;

import android.net.Uri;
import java.util.List;

/** OEM binary names: keep the nested payload interfaces distinct. */
public interface IMediaInteraction {
    void updateCurrentSourceType(int sourceType);
    void updateMediaSourceTypeList(int[] sourceTypes);
    void updateMediaMuteState(int muted);
    void updatePlaybackInfo(IPlaybackInfo info);
    void updatePlaylist(int sourceType, List<IMedia> items);
    void updateCurrentProgress(long millis);

    interface IMedia {
        String getTitle();
        String getArtist();
        String getAlbum();
        long getDuration();
        int getSourceType();
        Uri getArtwork();
        Uri getLyric();
        Uri getMediaPath();
        String getLyricContent();
        String getRadioFrequency();
        String getRadioStationName();
        String getUUID();
        int getFavoriteState();
        int getRadioMode();
    }

    interface IPlaybackInfo extends IMedia {
        int getPlaybackStatus();
        Uri getNextArtwork();
        Uri getPreviousArtwork();
        String getCurrentLyricSentence();
        int getLoopMode();
        int getPlayingItemPositionInQueue();
    }
}
