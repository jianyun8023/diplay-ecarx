package com.shihab.diplay.network

import java.lang.reflect.InvocationTargetException

/** Android 9 Wi-Fi scan control; called under the app's CONNECTIVITY_INTERNAL permission. */
internal class WifiAutojoinSwitch(private val wifi: Any) {
    fun set(enabled: Boolean): WifiScanSwitchResult = try {
        wifi.javaClass.getMethod("enableWifiConnectivityManager", Boolean::class.javaPrimitiveType).invoke(wifi, enabled)
        WifiScanSwitchResult.DONE
    } catch (failure: InvocationTargetException) {
        if (failure.targetException is SecurityException) WifiScanSwitchResult.DENIED else WifiScanSwitchResult.UNKNOWN
    } catch (_: NoSuchMethodException) {
        WifiScanSwitchResult.UNSUPPORTED
    } catch (_: Exception) {
        WifiScanSwitchResult.UNKNOWN
    }
}
