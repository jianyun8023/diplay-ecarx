package com.shilapi.xcertplay.orchestration

/** Rust owns generation/authentication/frame/once policy; Java retains only object/callback leases. */
internal class WirelessConnectionProof<S : Any> {
    private val state = run { ControllerLifecycleCore.requireNative(); NativeCarPlayController.proofCreate() }
    private var session: S? = null
    private var confirmation: (() -> Unit)? = null

    @Synchronized fun begin(generation: Int, confirmation: () -> Unit) {
        NativeCarPlayController.proofEvent(state, 0, generation, false)
        session = null
        this.confirmation = confirmation
    }

    @Synchronized fun activate(generation: Int, session: S) {
        if (NativeCarPlayController.proofEvent(state, 1, generation, this.session === session)) this.session = session
    }

    @Synchronized fun authenticated(generation: Int) {
        if (NativeCarPlayController.proofEvent(state, 2, generation, false)) {
            val callback = confirmation
            confirmation = null
            callback?.invoke()
        }
    }

    @Synchronized fun rendered(generation: Int, session: S) {
        if (NativeCarPlayController.proofEvent(state, 3, generation, this.session === session)) {
            val callback = confirmation
            confirmation = null
            callback?.invoke()
        }
    }

    @Synchronized fun hasActiveSession(generation: Int): Boolean = NativeCarPlayController.proofEvent(state, 6, generation, false)
    @Synchronized fun hasRenderedFrame(generation: Int): Boolean = NativeCarPlayController.proofEvent(state, 7, generation, false)

    @Synchronized fun end(generation: Int, session: S) {
        if (NativeCarPlayController.proofEvent(state, 4, generation, this.session === session)) this.session = null
    }

    @Synchronized fun clear() {
        NativeCarPlayController.proofEvent(state, 5, 0, false)
        session = null
        confirmation = null
    }
}
