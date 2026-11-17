package com.devil.phoenixproject.data.integration.routinefeed

import com.devil.phoenixproject.domain.csv.RoutineCsvFormat
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.URLProtocol
import io.ktor.http.appendPathSegments
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RoutineFeedEntry(val name: String, val path: String, val sha: String, val type: String = "file")

internal enum class RoutineFeedFailureKind { RETRY_LATER, CONFIGURATION, TOO_LARGE }
internal class RoutineFeedFailure(val kind: RoutineFeedFailureKind) : Exception(kind.name)

class RoutineFeedClient(httpClientEngine: HttpClientEngine? = null) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http: HttpClient

    init {
        val configure: HttpClientConfig<*>.() -> Unit = {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 20_000
                requestTimeoutMillis = 30_000
            }
        }
        http = if (httpClientEngine == null) HttpClient(configure) else HttpClient(httpClientEngine, configure)
    }

    suspend fun list(config: RoutineFeedConfig): List<RoutineFeedEntry> =
        json.decodeFromString<List<RoutineFeedEntry>>(read(config, config.path, "application/vnd.github+json"))
            .filter { it.type == "file" && it.name.endsWith(".csv", ignoreCase = true) }

    suspend fun fetch(config: RoutineFeedConfig, entry: RoutineFeedEntry): String =
        read(config, entry.path, "application/vnd.github.raw")

    private suspend fun read(config: RoutineFeedConfig, path: String, accept: String): String {
        try {
            return http.prepareGet {
                url {
                    protocol = URLProtocol.HTTPS
                    host = "api.github.com"
                    appendPathSegments(listOf("repos") + config.repo.split('/') + "contents" + path.trim('/').split('/'))
                }
                header("Authorization", "Bearer ${config.token}")
                header("Accept", accept)
                header("X-GitHub-Api-Version", "2022-11-28")
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val kind = when (response.status.value) {
                        401, 403, 404 -> RoutineFeedFailureKind.CONFIGURATION
                        else -> RoutineFeedFailureKind.RETRY_LATER
                    }
                    throw RoutineFeedFailure(kind)
                }
                val channel = response.bodyAsChannel()
                val bytes = ByteArray(RoutineCsvFormat.MAX_BYTES + 1)
                var size = 0
                while (size < bytes.size) {
                    val count = channel.readAvailable(bytes, size, bytes.size - size)
                    if (count == -1) break
                    size += count
                }
                if (size > RoutineCsvFormat.MAX_BYTES) throw RoutineFeedFailure(RoutineFeedFailureKind.TOO_LARGE)
                bytes.decodeToString(endIndex = size)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RoutineFeedFailure) {
            throw e
        } catch (_: Exception) {
            throw RoutineFeedFailure(RoutineFeedFailureKind.RETRY_LATER)
        }
    }

    fun close() = http.close()
}
