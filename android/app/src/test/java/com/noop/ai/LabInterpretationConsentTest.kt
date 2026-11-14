package com.noop.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class LabInterpretationConsentTest {
    @Test fun exactEvidenceSharingRequiresItsOwnGrantAndCanBeRevoked() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("lab_interpretation_consent_v1", 0).edit().clear().commit()
        assertFalse(LabInterpretationConsent.read(context))
        LabInterpretationConsent.write(context, true)
        assertTrue(LabInterpretationConsent.read(context))
        LabInterpretationConsent.write(context, false)
        assertFalse(LabInterpretationConsent.read(context))
    }
}
