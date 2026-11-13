package com.shihab.diplay

import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService

/** Android 9 platform authorization when the OEM consent Activity is absent. */
internal object VpnPreConsent {
    fun grant(context: Context): Boolean {
        if (context.checkSelfPermission("android.permission.CONTROL_VPN") != PackageManager.PERMISSION_GRANTED) return false
        return runCatching {
            VpnService::class.java.getMethod("prepareAndAuthorize", Context::class.java).invoke(null, context)
            VpnService.prepare(context) == null
        }.getOrDefault(false)
    }
}
