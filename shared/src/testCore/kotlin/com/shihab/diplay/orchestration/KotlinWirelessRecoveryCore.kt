@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.shihab.diplay.orchestration

/** Independent pre-migration reference, excluded from production. */
internal object KotlinWirelessRecoveryCore {
    fun budgetAdvance(token: Long, stage: Int): Long {
        var flags = token.toInt() and 7
        val number = (token shr 8).toInt()
        if (stage == -1) return if (number < 4) ((number + 1) shl 8 or flags).toLong() else -1L
        val action = when {
            stage == 0 && flags and 1 == 0 -> { flags = flags or 1; 1 }
            stage == 0 && flags and 4 == 0 -> { flags = flags or 4; 2 }
            stage in 1..2 && flags and 2 == 0 -> { flags = flags or 2; 3 }
            else -> 0
        }
        return (number shl 8 or (action shl 4) or flags).toLong()
    }

    fun budgetQuery(token: Long, field: Int): Int = when (field) {
        0 -> (token shr 8).toInt()
        1 -> (token shr 4).toInt() and 3
        2 -> if (token and 4 != 0L) 1 else 0
        3 -> when (budgetQuery(token, 1)) { 1 -> 400; 3 -> 800; else -> 0 }
        else -> -1
    }

    fun attemptUpdate(token: Long, event: Int, phase: Int = 0): Long = when (event) {
        0 -> token and 1L.inv()
        1 -> if (token and 1 != 0L) token and 17L.inv() or 8L or (if (phase == 0) 16L else 0L) else token
        in 2..5 -> token and 6L.inv() or ((event - 2).toLong() shl 1)
        else -> -1
    }

    fun attemptQuery(token: Long, field: Int): Int = when (field) {
        0 -> (token and 1).toInt()
        1 -> (token shr 1).toInt() and 3
        else -> -1
    }

    fun classify(facts: Int): Int = with(WirelessFailureFacts) {
        val user = facts and (SECURITY or MFI_REJECTED or RADIO_OFF or IDENTIFICATION or MFI_TERMINAL or
            NO_PAIRED or CHOSEN_UNPAIRED or NOT_CHOSEN or UNAVAILABLE) != 0
        if (user) return@with 1
        if (facts and (ANY_ERROR or SECURITY or ARGUMENT or STATE or INTERRUPTED or INTERRUPTED_IO or
                CANCEL or STARTUP or CONNECTION or IDENTIFICATION) != 0) return@with 0
        if (facts and MFI_RETRY != 0) return@with 2
        if (facts and MFI != 0) return@with 0
        if (facts and TOP_IO != 0) 2 else 0
    }

    fun attemptFailure(token: Long, facts: Int): Int = with(WirelessFailureFacts) {
        if (facts and (TOP_ERROR or TOP_BOOTSTRAP) != 0) return@with 0
        val classification = classify(facts)
        if (token and 8 != 0L) {
            return@with if (classification and 1 != 0 ||
                facts and TOP_CANCEL == 0 && classification and 2 == 0) 0 else if (token and 16 != 0L) 4 else 1
        }
        if (classification and 2 != 0) when (attemptQuery(token, 1)) { 1 -> 2; 2 -> 3; else -> 0 } else 0
    }
}
