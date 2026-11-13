package com.shilapi.xcertplay.airplay

/** Rust owns the complete /info declaration; Kotlin supplies immutable platform facts. */
object AirPlayInfoPlist {
    const val MAIN_UUID = "b7e6c5a0-1111-4000-8000-000000000001"
    const val ALT_UUID = "b7e6c5a0-2222-4000-8000-000000000002"
    const val DOCK_EDGE_BOTTOM = 1
    const val DOCK_EDGE_DRIVER_SIDE = 2

    fun features(config: AirPlayConfig): Long = NativeAirPlayProtocol.features(config.disableAudioOutput)

    fun build(config: AirPlayConfig): Map<String, Any?> {
        fun insets(value: AirPlayInsets?): List<Int>? = value?.let { listOf(it.top, it.bottom, it.left, it.right) }
        fun display(value: AirPlayDisplayConfig): Map<String, Any?> = linkedMapOf(
            "widthPixels" to value.widthPixels,
            "heightPixels" to value.heightPixels,
            "widthPhysicalMm" to value.widthPhysicalMm,
            "heightPhysicalMm" to value.heightPhysicalMm,
            "fps" to value.fps,
            "primaryInputDevice" to value.primaryInputDevice,
            "safeAreaDrawOutside" to value.safeAreaDrawOutside,
            "initialUrl" to value.initialUrl,
            "features" to value.features,
            "initialViewArea" to value.initialViewArea,
            "mainDisplayCornerMasks" to value.mainDisplayCornerMasks,
            "mainDisplayDisableSafeArea" to value.mainDisplayDisableSafeArea,
            "viewArea" to insets(value.viewArea), "safeArea" to insets(value.safeArea),
            "viewAreas" to value.viewAreas?.map { listOf(it.width, it.height, it.originX, it.originY, it.dockEdge) },
        )
        return NativeAirPlayProtocol.info(linkedMapOf(
            "deviceName" to config.deviceName,
            "deviceId" to config.deviceId,
            "btMac" to config.btMac,
            "sourceVersion" to config.sourceVersion,
            "rightHandDrive" to config.rightHandDrive,
            "entertainmentSampleRate" to config.entertainmentSampleRate,
            "hevc" to config.hevc,
            "disableAudioOutput" to config.disableAudioOutput,
            "microphone" to config.microphone,
            "microphoneOpus" to config.microphoneOpus,
            "manufacturer" to config.manufacturer,
            "model" to config.model,
            "oemLabel" to config.oemLabel,
            "videoInCar" to config.videoInCar,
            "mainBufferedAudio" to config.mainBufferedAudio,
            "speechWithout8And16Khz" to config.speechWithout8And16Khz,
            "callAtCallRate" to config.callAtCallRate,
            "main" to display(config.main), "cluster" to config.cluster?.let(::display),
            "videoAllowed" to VideoInCar.allowed,
            "icons" to config.icons.map { mapOf("widthPixels" to it.widthPixels, "heightPixels" to it.heightPixels, "data" to it.data) },
            "hidDevices" to listOf(
                AirPlayHid.touchHidDevice(config.main.widthPixels, config.main.heightPixels, MAIN_UUID),
                AirPlayHid.knobHidDevice(MAIN_UUID), AirPlayHid.mediaHidDevice(MAIN_UUID), AirPlayHid.telephonyHidDevice(MAIN_UUID),
            ),
        ))
    }

    fun viewAreaCommand(index: Int, areaCount: Int): Map<String, Any?> = NativeAirPlayProtocol.viewArea(index, areaCount)
}
