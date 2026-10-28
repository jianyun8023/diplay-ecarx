package com.ecarx.sdk.policy;

/** Recovered callback ABI only; no vendor implementation is bundled. */
public interface IMicResourceCallback {
    void onResume();
    void onAbort(int reason);
}
