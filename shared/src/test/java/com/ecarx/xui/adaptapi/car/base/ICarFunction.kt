package com.ecarx.xui.adaptapi.car.base

import com.ecarx.xui.adaptapi.FunctionStatus

interface ICarFunction {
    interface IFunctionValueWatcher {
        fun onFunctionValueChanged(function: Int, zone: Int, value: Int)
        fun onCustomizeFunctionValueChanged(function: Int, zone: Int, value: Float)
        fun onSupportedFunctionStatusChanged(function: Int, zone: Int, status: FunctionStatus)
    }
    fun isFunctionSupported(function: Int, zone: Int): FunctionStatus
    fun getFunctionValue(function: Int, zone: Int): Int
    fun getCustomizeFunctionValue(function: Int, zone: Int): Float
    fun getSupportedFunctionValue(function: Int): IntArray
    fun setFunctionValue(function: Int, value: Int): Boolean
    fun setFunctionValue(function: Int, zone: Int, value: Int): Boolean
    fun setCustomizeFunctionValue(function: Int, zone: Int, value: Float): Boolean
    fun registerFunctionValueWatcher(functions: IntArray, watcher: IFunctionValueWatcher): Boolean
    fun unregisterFunctionValueWatcher(watcher: IFunctionValueWatcher): Boolean
}
