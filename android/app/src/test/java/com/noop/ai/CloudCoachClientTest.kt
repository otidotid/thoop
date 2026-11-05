package com.noop.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudCoachClientTest {
    @Test
    fun `only https endpoint is configured`() {
        assertTrue(CloudCoachClient(baseUrl = "https://example.test").configured)
        assertFalse(CloudCoachClient(baseUrl = "http://example.test").configured)
        assertFalse(CloudCoachClient(baseUrl = "").configured)
    }

    @Test
    fun `reply defaults to chat intent`() {
        val reply = CloudCoachClient.Reply(
            answer = "ok",
            conversationId = "c",
        )

        assertEquals("chat", reply.intent)
        assertTrue(reply.references.isEmpty())
    }
}
