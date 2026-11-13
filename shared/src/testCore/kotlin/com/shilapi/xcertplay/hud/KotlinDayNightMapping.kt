@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.shilapi.xcertplay.hud

internal object KotlinDayNightMapping {
    /**
     * ECARX XUI AdaptAPI `IDayNightMode.getDayNight()` / `onDayNightChanged(int)`:
     * 1 = day, 2 = night, 0/unknown invalid. Contract recovered from the bytecode of the
     * `com.ecarx.xcvehicle` app shipped on this head unit.
     */
    fun fromAdaptapiDayNight(value: Int): Boolean? =
        when (value) {
            1 -> false
            2 -> true
            else -> null
        }

    /**
     * Car AMap standard broadcast `AUTONAVI_STANDARD_BROADCAST_SEND` with `KEY_TYPE` 10019:
     * `EXTRA_STATE` 30 = day, 32 = night. The same feed the OEM projection widget normalizes.
     */
    fun fromAmapState(state: Int): Boolean? =
        when (state) {
            30 -> false
            32 -> true
            else -> null
        }

    /**
     * Vehicle property `0x21400305` (`TwliBriSts`, body twilight sensor): 1 = day, 0 = night.
     * Observed on the target head unit: each change is followed within milliseconds by the
     * AdaptAPI `onDayNightChanged` with the same decision.
     */
    fun fromTwilightProperty(value: Int): Boolean? =
        when (value) {
            1 -> false
            0 -> true
            else -> null
        }

}
