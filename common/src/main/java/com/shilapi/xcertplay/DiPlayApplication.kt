package com.shilapi.xcertplay

import android.app.Application
import com.shilapi.xcertplay.orchestration.CarPlayStartupTimeline

/**
 * Records the first application checkpoint. The startup timeline previously began at
 * `LAUNCHER_CREATE`, leaving process fork and class loading as one unexplained block.
 *
 * This runs after ContentProviders and before any Activity, so the process→Activity gap it splits
 * still contains provider installation. Nothing else belongs here: it must stay allocation-cheap.
 */
class DiPlayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching {
            CarPlayStartupTimeline.record(
                this,
                CarPlayStartupTimeline.Stage.APPLICATION_CREATE,
                AirPlayPersistence.loadWirelessHotspotMode(this),
            )
        }
    }
}
