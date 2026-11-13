package com.ecarx.xui.adaptapi.car.sensor

import com.ecarx.xui.adaptapi.FunctionStatus

interface ISensor {
    interface ISensorListener {
        fun onSensorValueChanged(sensor: Int, value: Float)
        fun onSensorSupportChanged(sensor: Int, status: FunctionStatus)
    }
    fun isSensorSupported(sensor: Int): FunctionStatus
    fun getSensorLatestValue(sensor: Int): Float
    fun registerListener(listener: ISensorListener, sensor: Int, rate: Int): Boolean
    fun unregisterListener(listener: ISensorListener)
}
