package com.ecarx.xui.adaptapi.input;

import android.content.Context;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Stateful OEM boundary for tests of the production reflection adapter. */
public final class Input {
    public static final Input INSTANCE = new Input();
    public static Context createdWith;
    public static Object service = INSTANCE;
    public static boolean unavailable;
    public static RuntimeException createFailure;
    public static RuntimeException requestFailure;
    public static RuntimeException releaseFailure;
    public static int[] refused;
    public static final List<Registration> registrations = new ArrayList<>();
    public static final List<Registration> releases = new ArrayList<>();

    public static Object create(Context context) {
        createdWith = context;
        if (createFailure != null) throw createFailure;
        return unavailable ? null : service;
    }

    public int[] requestKeysInterception(int[] keys, IKeyCallback callback) {
        if (requestFailure != null) throw requestFailure;
        registrations.add(new Registration(keys.clone(), callback,
                refused == null ? new int[0] : refused.clone()));
        return refused == null ? null : refused.clone();
    }

    public void releaseKeysInterception(int[] keys, IKeyCallback callback) {
        for (Registration registration : registrations) {
            if (registration.callback == callback && Arrays.equals(registration.keys, keys)) {
                releases.add(registration);
                if (releaseFailure != null) throw releaseFailure;
                registration.active = false;
                return;
            }
        }
        throw new IllegalArgumentException("release did not match the requested keys and callback");
    }

    /** Normal service delivery stops when released; tests can retain callback to model in-flight IPC. */
    public static List<Boolean> press(int keyCode) {
        List<Boolean> result = new ArrayList<>();
        for (Registration registration : new ArrayList<>(registrations)) {
            if (registration.accepts(keyCode)) result.add(registration.callback.onKeyPressed(keyCode));
        }
        return result;
    }

    public static List<Boolean> release(int keyCode) {
        List<Boolean> result = new ArrayList<>();
        for (Registration registration : new ArrayList<>(registrations)) {
            if (registration.accepts(keyCode)) result.add(registration.callback.onKeyReleased(keyCode));
        }
        return result;
    }

    public static void reset() {
        createdWith = null;
        service = INSTANCE;
        unavailable = false;
        createFailure = null;
        requestFailure = null;
        releaseFailure = null;
        refused = null;
        registrations.clear();
        releases.clear();
    }

    public static final class Registration {
        public final int[] keys;
        public final IKeyCallback callback;
        public final int[] refused;
        public boolean active = true;

        Registration(int[] keys, IKeyCallback callback, int[] refused) {
            this.keys = keys;
            this.callback = callback;
            this.refused = refused;
        }

        boolean accepts(int keyCode) {
            return active && Arrays.stream(keys).anyMatch(key -> key == keyCode)
                    && Arrays.stream(refused).noneMatch(key -> key == keyCode);
        }
    }
}
