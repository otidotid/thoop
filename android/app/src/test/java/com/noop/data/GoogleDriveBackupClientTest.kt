package com.noop.data

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

/** Executes real request construction/response handling; interceptors prevent any external traffic. */
class GoogleDriveBackupClientTest {
    private fun client(code: Int, body: String): GoogleDriveBackupClient = GoogleDriveBackupClient(
        OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("Bearer fixture-token", chain.request().header("Authorization"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build(),
    )

    private fun errorBody(reason: String) =
        """{"error":{"code":403,"errors":[{"domain":"usageLimits","reason":"$reason"}]}}"""

    private suspend fun failure(code: Int, body: String): IOException {
        val failure = runCatching { client(code, body).list("fixture-token", "fixture-owner") }.exceptionOrNull()
        assertTrue("Expected an HTTP failure, got $failure", failure is IOException)
        return failure as IOException
    }

    @Test fun http401RequiresReauthenticationEvenWithoutAJsonErrorBody() = runBlocking {
        for (body in listOf("", "not-json", errorBody("rateLimitExceeded"))) {
            val failure = failure(401, body)
            assertTrue(failure is GoogleDriveAuthenticationException)
            assertEquals(401, (failure as GoogleDriveAuthenticationException).statusCode)
        }
    }

    @Test fun onlyStructuredAuthenticationOrScopeReasonsTreat403AsARejectedGrant() = runBlocking {
        for (reason in listOf("authError", "invalidCredentials", "insufficientPermissions")) {
            val failure = failure(403, errorBody(reason))
            assertTrue(reason, failure is GoogleDriveAuthenticationException)
            assertEquals(403, (failure as GoogleDriveAuthenticationException).statusCode)
        }
    }

    @Test fun googleRpcScopeRejectionAlsoRequiresReauthentication() = runBlocking {
        val body = """{"error":{"code":403,"status":"PERMISSION_DENIED","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"ACCESS_TOKEN_SCOPE_INSUFFICIENT"}]}}"""
        assertTrue(failure(403, body) is GoogleDriveAuthenticationException)
    }

    @Test fun rateLimit403ResponsesRemainRetryableWithTheirExistingCredentials() = runBlocking {
        for (reason in listOf("rateLimitExceeded", "userRateLimitExceeded", "sharingRateLimitExceeded")) {
            val failure = failure(403, errorBody(reason))
            assertTrue(reason, failure is GoogleDriveRetryableException)
            assertEquals(403, (failure as GoogleDriveRetryableException).statusCode)
            assertFalse(failure.message.orEmpty().contains("Reconnect"))
        }
    }

    @Test fun storageQuotaExplainsHowToRecoverWithoutRevokingTheGrant() = runBlocking {
        val failure = failure(403, errorBody("storageQuotaExceeded"))
        assertTrue(failure is GoogleDriveQuotaException)
        assertTrue(failure.message.orEmpty().contains("Free space"))
        assertTrue(failure.message.orEmpty().contains("still authorized"))
        assertFalse(failure.message.orEmpty().contains("Reconnect"))
    }

    @Test fun dailyProjectQuotaAsksForQuotaRecoveryInsteadOfOAuth() = runBlocking {
        val failure = failure(403, errorBody("dailyLimitExceeded"))
        assertTrue(failure is GoogleDriveQuotaException)
        assertTrue(failure.message.orEmpty().contains("quota resets"))
        assertFalse(failure.message.orEmpty().contains("Reconnect"))
    }

    @Test fun temporaryHttpFailuresAreRetryableWithoutReauthentication() = runBlocking {
        for (status in listOf(408, 429, 500, 502, 503, 504)) {
            val failure = failure(status, "not-json")
            assertTrue("HTTP $status", failure is GoogleDriveRetryableException)
            assertEquals(status, (failure as GoogleDriveRetryableException).statusCode)
        }
    }

    @Test fun filePermissionsUnknown403AndMalformedBodiesCannotRevokeTheWholeDriveGrant() = runBlocking {
        for (body in listOf(errorBody("insufficientFilePermissions"), errorBody("domainPolicy"),
            errorBody("newUnknownReason"), "not-json", "")) {
            assertTrue(failure(403, body) is GoogleDriveRequestException)
        }
    }

    @Test fun authenticationWordsInAnErrorMessageDoNotOverrideItsActualRateLimitReason() = runBlocking {
        val body = """{"error":{"code":403,"message":"authError token=private-value","errors":[{"reason":"rateLimitExceeded"}]}}"""
        val failure = failure(403, body)
        assertTrue(failure is GoogleDriveRetryableException)
        assertFalse(failure.message.orEmpty().contains("private-value"))
        assertTrue(failure(403, """{"error":{"message":"insufficientPermissions"}}""") is GoogleDriveRequestException)
    }

    @Test fun otherRejectedRequestsDoNotEnterTheAuthenticationOrTemporaryRetryPaths() = runBlocking {
        for (status in listOf(400, 404, 409)) {
            val failure = failure(status, errorBody("authError"))
            assertTrue(failure is GoogleDriveRequestException)
            assertEquals(status, (failure as GoogleDriveRequestException).statusCode)
        }
    }

    @Test fun aLaterRetryCanSucceedUsingTheSameAccessToken() = runBlocking {
        var requests = 0
        val client = GoogleDriveBackupClient(OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("Bearer fixture-token", chain.request().header("Authorization"))
            val status = if (requests++ == 0) 403 else 200
            val body = if (status == 403) errorBody("rateLimitExceeded") else """{"files":[]}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build())
        assertTrue(runCatching { client.list("fixture-token", "fixture-owner") }.exceptionOrNull() is GoogleDriveRetryableException)
        assertEquals(emptyList<GoogleDriveBackupClient.RemoteBackup>(), client.list("fixture-token", "fixture-owner"))
        assertEquals(2, requests)
    }

    @Test fun rejectedUploadPreservesTheOriginalBackupBytes() = runBlocking {
        val original = Files.createTempFile("thoop-original-backup-", ".noopbak").toFile()
        try {
            val bytes = byteArrayOf(1, 9, 3, 7)
            original.writeBytes(bytes)
            val failure = runCatching {
                client(403, errorBody("storageQuotaExceeded")).upload("fixture-token", original, "fixture-owner")
            }.exceptionOrNull()
            assertTrue(failure is GoogleDriveQuotaException)
            assertArrayEquals(bytes, original.readBytes())
        } finally { original.delete() }
    }

    @Test fun rejectedDownloadCannotTruncateAnExistingLocalBackup() = runBlocking {
        val original = Files.createTempFile("thoop-existing-backup-", ".noopbak").toFile()
        try {
            val bytes = byteArrayOf(2, 8, 4, 6)
            original.writeBytes(bytes)
            val failure = runCatching { client(401, "").download("fixture-token", "fixture-backup", original) }.exceptionOrNull()
            assertTrue(failure is GoogleDriveAuthenticationException)
            assertArrayEquals(bytes, original.readBytes())
        } finally { original.delete() }
    }
}
