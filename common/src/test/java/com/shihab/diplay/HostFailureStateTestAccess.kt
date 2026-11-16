package com.shihab.diplay

import com.shihab.diplay.orchestration.HostFailureState
import org.robolectric.util.ReflectionHelpers

internal fun CarPlayHostActivity.testFailureState(): HostFailureState =
    ReflectionHelpers.getField(this, "failureState")
