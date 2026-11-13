package com.ecarx.xui.adaptapi.car

import android.content.Context
import com.ecarx.xui.adaptapi.car.sensor.ISensor

interface ICar { fun getSensorManager(): ISensor }
class Car {
    companion object {
        var instance: ICar? = null
        @JvmStatic fun create(context: Context): ICar? = instance
    }
}
