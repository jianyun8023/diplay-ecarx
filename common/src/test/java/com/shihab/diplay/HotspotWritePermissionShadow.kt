package com.shihab.diplay

import android.content.Context
import android.provider.Settings
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@Implements(Settings.System::class)
class HotspotWritePermissionShadow {
    companion object {
        @Volatile var allowed = false
        @JvmStatic @Implementation fun canWrite(context: Context): Boolean = allowed
    }
}
