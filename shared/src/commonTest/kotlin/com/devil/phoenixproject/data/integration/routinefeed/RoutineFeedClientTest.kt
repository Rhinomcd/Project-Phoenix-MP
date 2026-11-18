package com.devil.phoenixproject.data.integration.routinefeed

import com.devil.phoenixproject.domain.csv.RoutineCsvFormat
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class RoutineFeedClientTest {
    private val config = RoutineFeedConfig("owner/routines", "feed", "test-token")
    private val entry = RoutineFeedEntry("Push.csv", "feed/Push.csv", "sha")

    @Test
    fun configRequiresAllThreeValues() {
        assertNull(RoutineFeedConfig.from("", "feed", "token"))
        assertNull(RoutineFeedConfig.from("owner/repo", " ", "token"))
        assertNull(RoutineFeedConfig.from("owner/repo", "feed", ""))
        assertNotNull(RoutineFeedConfig.from("owner/repo", "feed", "token"))
    }

    @Test
    fun refusesBodyOverCsvByteLimit() = runTest {
        val client = RoutineFeedClient(MockEngine { respond("x".repeat(RoutineCsvFormat.MAX_BYTES + 1)) })
        try {
            val failure = assertFailsWith<RoutineFeedFailure> { client.fetch(config, entry) }
            assertEquals(RoutineFeedFailureKind.TOO_LARGE, failure.kind)
        } finally {
            client.close()
        }
    }

    @Test
    fun bodyExactlyAtLimitIsAccepted() = runTest {
        val body = "x".repeat(RoutineCsvFormat.MAX_BYTES)
        val client = RoutineFeedClient(MockEngine { respond(body) })
        try {
            assertEquals(body, client.fetch(config, entry))
        } finally {
            client.close()
        }
    }

    @Test
    fun classifiesConfigurationAndRetryableStatuses() = runTest {
        for ((status, kind) in listOf(
            HttpStatusCode.Unauthorized to RoutineFeedFailureKind.CONFIGURATION,
            HttpStatusCode.Forbidden to RoutineFeedFailureKind.CONFIGURATION,
            HttpStatusCode.NotFound to RoutineFeedFailureKind.CONFIGURATION,
            HttpStatusCode.TooManyRequests to RoutineFeedFailureKind.RETRY_LATER,
            HttpStatusCode.InternalServerError to RoutineFeedFailureKind.RETRY_LATER,
            HttpStatusCode.ServiceUnavailable to RoutineFeedFailureKind.RETRY_LATER,
        )) {
            val client = RoutineFeedClient(MockEngine { respond("", status) })
            try {
                val failure = assertFailsWith<RoutineFeedFailure> { client.fetch(config, entry) }
                assertEquals(kind, failure.kind, status.toString())
            } finally {
                client.close()
            }
        }
    }
}
