package com.shilapi.xcertplay

import android.app.Activity
import android.os.Bundle

/** HY's action handoff must not bring either DiPlay screen to the foreground. */
class SiriActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == "$packageName.action.SIRI") CarPlayMediaSession.requestSiriFromAction()
        finish()
    }
}
