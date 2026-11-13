package com.shihab.diplay.transport

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

/** Grants only this app access to the attached Apple device whose permission UI is missing. */
internal object IphoneUsbGrant {
    enum class Code { GRANTED, NO_DEVICE, UNSUPPORTED, DENIED, ERROR }
    fun supported(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk == 28

    fun grant(context: Context, device: UsbDevice): Code {
        if (!supported()) return Code.UNSUPPORTED
        if (device.vendorId != IphoneUsbMatcher.APPLE_VENDOR_ID ||
            context.checkSelfPermission("android.permission.MANAGE_USB") != PackageManager.PERMISSION_GRANTED) return Code.DENIED
        val usb = context.getSystemService(UsbManager::class.java) ?: return Code.UNSUPPORTED
        val attached = usb.deviceList[device.deviceName] ?: return Code.NO_DEVICE
        if (attached.deviceId != device.deviceId || attached.vendorId != device.vendorId || attached.productId != device.productId)
            return Code.NO_DEVICE
        return runCatching {
            UsbManager::class.java.getMethod("grantPermission", UsbDevice::class.java, Int::class.javaPrimitiveType)
                .invoke(usb, attached, context.applicationInfo.uid)
            if (usb.hasPermission(attached)) Code.GRANTED else Code.DENIED
        }.getOrDefault(Code.ERROR)
    }
}
