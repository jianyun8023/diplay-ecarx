package com.ecarx.sdk.policy;

import android.content.Context;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Test boundary for the reflective policy API, disabled for unrelated tests. */
public final class PolicyAPI {
    private static final PolicyAPI INSTANCE = new PolicyAPI();
    public static final AudioPolicy audio = new AudioPolicy();
    public static PolicyAPI get(Context context) { return INSTANCE; }
    public IAudioPolicy getAudioPolicy() { return audio; }

    public static final class AudioPolicy implements IAudioPolicy {
        public boolean needed, reject, abort, declineRelease;
        public int requests, releases, requestType;
        public IMicResourceCallback held;
        public final List<String> events = new CopyOnWriteArrayList<>();

        public void reset() {
            needed = reject = abort = declineRelease = false;
            requests = releases = requestType = 0;
            held = null;
            events.clear();
        }
        public boolean isNeedrequestMic() { return needed; }
        public boolean requestMic(int type, IMicResourceCallback callback) {
            events.add("requestMic");
            requests++;
            requestType = type;
            if (reject) return false;
            held = callback;
            if (abort) callback.onAbort(7); else callback.onResume();
            return true;
        }
        public boolean releaseMic(IMicResourceCallback callback) {
            if (held != callback) throw new AssertionError("Released a different callback");
            events.add("releaseMic");
            releases++;
            held = null;
            return !declineRelease;
        }
    }
}
