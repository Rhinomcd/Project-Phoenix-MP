package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.domain.model.generateUUID
import com.russhwolf.settings.Settings
import com.russhwolf.settings.get
import com.russhwolf.settings.set
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A Portal (Supabase) backend the app can talk to.
 *
 * The built-in entry mirrors the build-time bootstrap config (Android BuildConfig /
 * iOS Info.plist); custom entries are user-supplied self-hosted backends.
 */
@Serializable
data class PortalServerEntry(
    val id: String,
    val name: String,
    val url: String,
    val anonKey: String,
    val isBuiltIn: Boolean,
) {
    /** Host portion of [url], for display as supporting text. */
    val host: String get() = portalServerHost(url)
}

/**
 * Stores the list of Portal servers and which one the app talks to.
 *
 * The selection is read once, when the Koin graph builds [activeSupabaseConfig], so a
 * change only takes effect on the next app launch: [PortalApiClient]/[PortalAuthRepository]
 * are singletons holding an immutable [SupabaseConfig], and swapping them at runtime would
 * strand in-flight sync work against the old backend. Synced rows are NOT namespaced per
 * server either — switching is only supported while unlinked (the UI enforces that), and
 * no data is migrated between servers.
 *
 * @param settings Plain (non-secure) settings — server URLs and anon keys are public values.
 * @param bootstrapConfig The build-time config that backs the built-in entry.
 */
class PortalServerRepository(
    private val settings: Settings,
    bootstrapConfig: SupabaseConfig,
    private val tokenStorage: PortalTokenStorage,
) {
    companion object {
        const val BUILT_IN_SERVER_ID = "official"
        private const val BUILT_IN_SERVER_NAME = "Phoenix Portal"
        private const val KEY_CUSTOM_SERVERS = "portal_custom_servers"
        private const val KEY_SELECTED_SERVER_ID = "portal_selected_server_id"
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val builtInServer = PortalServerEntry(
        id = BUILT_IN_SERVER_ID,
        name = BUILT_IN_SERVER_NAME,
        url = bootstrapConfig.url,
        anonKey = bootstrapConfig.anonKey,
        isBuiltIn = true,
    )

    private val _servers = MutableStateFlow(listOf(builtInServer) + loadCustomServers())
    val servers: StateFlow<List<PortalServerEntry>> = _servers.asStateFlow()

    private val _selectedServerId = MutableStateFlow(
        resolveSelectedId(settings[KEY_SELECTED_SERVER_ID], _servers.value),
    )
    val selectedServerId: StateFlow<String> = _selectedServerId.asStateFlow()

    private val _restartRequired = MutableStateFlow(false)

    /** True once the selection changed in this process; not persisted — a restart clears it. */
    val restartRequired: StateFlow<Boolean> = _restartRequired.asStateFlow()

    /** Resolves the selected entry to a [SupabaseConfig]. Called once, at DI-graph build. */
    fun activeSupabaseConfig(): SupabaseConfig {
        val selected = _servers.value.firstOrNull { it.id == _selectedServerId.value } ?: builtInServer
        return SupabaseConfig(url = selected.url, anonKey = selected.anonKey)
    }

    fun addServer(name: String, url: String, anonKey: String): Result<PortalServerEntry> {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) return Result.failure(IllegalArgumentException("Name is required"))
        val normalizedUrl = normalizePortalServerUrl(url)
            ?: return Result.failure(IllegalArgumentException("Enter an http:// or https:// server URL"))
        val trimmedKey = anonKey.trim()
        if (trimmedKey.isBlank()) return Result.failure(IllegalArgumentException("Anon key is required"))

        val entry = PortalServerEntry(
            id = generateUUID(),
            name = trimmedName,
            url = normalizedUrl,
            anonKey = trimmedKey,
            isBuiltIn = false,
        )
        _servers.value = _servers.value + entry
        persistCustomServers()
        return Result.success(entry)
    }

    /** Removes a custom server. The built-in entry cannot be removed. */
    fun removeServer(id: String) {
        val entry = _servers.value.firstOrNull { it.id == id } ?: return
        if (entry.isBuiltIn) return

        _servers.value = _servers.value - entry
        persistCustomServers()
        if (_selectedServerId.value == id) selectServer(BUILT_IN_SERVER_ID)
    }

    fun selectServer(id: String) {
        if (_servers.value.none { it.id == id }) return
        if (_selectedServerId.value == id) return

        _selectedServerId.value = id
        settings[KEY_SELECTED_SERVER_ID] = id
        // Checkpoints record how far sync got against ONE backend; resuming from them
        // against a different backend would skip rows that were never pulled.
        tokenStorage.clearSyncCheckpoints()
        _restartRequired.value = true
    }

    private fun loadCustomServers(): List<PortalServerEntry> {
        val stored: String = settings[KEY_CUSTOM_SERVERS] ?: return emptyList()
        return try {
            json.decodeFromString<List<PortalServerEntry>>(stored)
                .map { it.copy(isBuiltIn = false) }
        } catch (e: IllegalArgumentException) {
            // Corrupt payload: fall back to "no custom servers" rather than blocking startup.
            emptyList()
        }
    }

    private fun persistCustomServers() {
        settings[KEY_CUSTOM_SERVERS] = json.encodeToString(_servers.value.filterNot { it.isBuiltIn })
    }

    private fun resolveSelectedId(persisted: String?, available: List<PortalServerEntry>): String {
        val known = persisted?.takeIf { id -> available.any { it.id == id } }
        return known ?: BUILT_IN_SERVER_ID
    }
}

/**
 * Returns [url] trimmed and stripped of trailing slashes, or null when it is not an
 * http/https URL with a non-blank host.
 */
fun normalizePortalServerUrl(url: String): String? {
    val trimmed = url.trim().trimEnd('/')
    val scheme = trimmed.substringBefore("://", missingDelimiterValue = "").lowercase()
    if (scheme != "http" && scheme != "https") return null
    val host = trimmed.substringAfter("://").substringBefore('/')
    if (host.isBlank() || host.any { it.isWhitespace() }) return null
    return trimmed
}

/** Host (and port) portion of a portal URL, falling back to the whole string. */
fun portalServerHost(url: String): String = url.substringAfter("://").substringBefore('/').ifBlank { url }
