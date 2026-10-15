package com.noop.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudCoachClientTest {
    @Test fun `only https endpoint is configured`() {
        assertTrue(CloudCoachClient(baseUrl = "https://example.test").configured)
        assertFalse(CloudCoachClient(baseUrl = "http://example.test").configured)
        assertFalse(CloudCoachClient(baseUrl = "").configured)
    }
}
