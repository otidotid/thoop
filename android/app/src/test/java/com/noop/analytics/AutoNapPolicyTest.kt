package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoNapPolicyTest {
    @Test fun toggleOffIsInert() { NapVerdict.entries.forEach { assertEquals(AutoNapAction.IGNORE, AutoNapPolicy.action(false, it)) } }
    @Test fun napIsAccepted() = assertEquals(AutoNapAction.ACCEPT, AutoNapPolicy.action(true, NapVerdict.NAP))
    @Test fun inconclusiveIsRejected() = assertEquals(AutoNapAction.REJECT, AutoNapPolicy.action(true, NapVerdict.INCONCLUSIVE))
    @Test fun noneIsIgnored() = assertEquals(AutoNapAction.IGNORE, AutoNapPolicy.action(true, NapVerdict.NONE))
}
