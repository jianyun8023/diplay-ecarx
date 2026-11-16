package com.shihab.diplay.orchestration

/** A negotiated alt stream alone does not mean that the driver can see a map on the dashboard. */
internal object DashboardMapEligibility {
    fun permits(initialUrl: String?, physicalOutputVisible: Boolean, stream: Int, uiShown: Boolean, closed: Boolean): Boolean {
        ControllerLifecycleCore.requireNative()
        return NativeControllerDashboard.permits(initialUrl, physicalOutputVisible, stream, uiShown, closed)
    }
}
