package com.noop.analytics

enum class AutoNapAction { ACCEPT, REJECT, IGNORE }
object AutoNapPolicy {
    fun action(enabled: Boolean, verdict: NapVerdict): AutoNapAction = when {
        !enabled -> AutoNapAction.IGNORE
        verdict == NapVerdict.NAP -> AutoNapAction.ACCEPT
        verdict == NapVerdict.INCONCLUSIVE -> AutoNapAction.REJECT
        else -> AutoNapAction.IGNORE
    }
}
