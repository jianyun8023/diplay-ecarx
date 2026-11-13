@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.shilapi.xcertplay.testcore

import com.shilapi.xcertplay.airplay.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * SDK tests keep live PairingStore callbacks in one sandbox. Ordinary JUnit and device tests call
 * the production JNI bindings directly; these two references are never native acceptance.
 */
@Implements(className = "com.shilapi.xcertplay.airplay.NativePairVerify", isInAndroidSdk = false,
    callNativeMethodsByDefault = true)
class ShadowNativePairVerify {
    companion object {
        private class Owner(val identity: AirPlayIdentity) {
            var core: KotlinPairVerify? = null
        }
        private val next = AtomicLong()
        private val owners = ConcurrentHashMap<Long, Owner>()

        @JvmStatic @Implementation fun create(privateSeed: ByteArray, accessoryId: ByteArray): Long {
            val id = next.incrementAndGet()
            // Pair-verify uses the signing seed and identifier, not the identity's public-key field.
            owners[id] = Owner(AirPlayIdentity(privateSeed.copyOf(), ByteArray(32), accessoryId.toString(Charsets.UTF_8)))
            return id
        }

        @JvmStatic @Implementation fun handle(handle: Long, body: ByteArray, pairings: PairingStore): ByteArray {
            val owner = owners.getValue(handle)
            val core = owner.core ?: KotlinPairVerify(owner.identity, pairings).also { owner.core = it }
            return core.handle(body)
        }

        @JvmStatic @Implementation fun shared(handle: Long): ByteArray? = owners.getValue(handle).core?.shared
        @JvmStatic @Implementation fun keys(handle: Long): PairVerify.ControlKeys? = owners.getValue(handle).core?.controlKeys
        @JvmStatic @Implementation fun controllerId(handle: Long): ByteArray? =
            owners.getValue(handle).core?.verifiedControllerId?.toByteArray(Charsets.UTF_8)
        @JvmStatic @Implementation fun verified(handle: Long): Boolean = owners.getValue(handle).core?.isVerified ?: false
        @JvmStatic @Implementation fun destroy(handle: Long) { owners.remove(handle) }
    }
}

@Implements(className = "com.shilapi.xcertplay.airplay.NativePairSetup", isInAndroidSdk = false,
    callNativeMethodsByDefault = true)
class ShadowNativePairSetup {
    companion object {
        private class Owner(val identity: AirPlayIdentity) {
            var core: KotlinPairSetup? = null
        }
        private val next = AtomicLong()
        private val owners = ConcurrentHashMap<Long, Owner>()

        @JvmStatic @Implementation fun create(seed: ByteArray, publicKey: ByteArray, identifier: ByteArray): Long {
            val id = next.incrementAndGet()
            owners[id] = Owner(AirPlayIdentity(seed.copyOf(), publicKey.copyOf(), identifier.toString(Charsets.UTF_8)))
            return id
        }

        @JvmStatic @Implementation fun handle(handle: Long, body: ByteArray, pairings: PairingStore): ByteArray {
            val owner = owners.getValue(handle)
            val core = owner.core ?: KotlinPairSetup(owner.identity, pairings).also { owner.core = it }
            return core.handle(body)
        }

        @JvmStatic @Implementation fun complete(handle: Long): Boolean = owners.getValue(handle).core?.complete ?: false
        @JvmStatic @Implementation fun destroy(handle: Long) { owners.remove(handle) }
    }
}
