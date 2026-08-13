package com.devil.phoenixproject.data.sync

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the configurable Portal server list: which entry the app resolves at
 * startup, validation of user-added servers, and the sync-checkpoint hygiene that
 * must run whenever the selection changes.
 */
class PortalServerRepositoryTest {

    private val bootstrap = SupabaseConfig(url = "https://official.example.com", anonKey = "official-key")

    private fun createRepository(
        settings: MapSettings = MapSettings(),
        secureSettings: MapSettings = MapSettings(),
    ) = PortalServerRepository(settings, bootstrap, PortalTokenStorage(secureSettings))

    // ===== Built-in entry =====

    @Test
    fun builtInServerIsAlwaysFirstAndMirrorsBootstrapConfig() {
        val repository = createRepository()

        val builtIn = repository.servers.value.first()
        assertEquals(PortalServerRepository.BUILT_IN_SERVER_ID, builtIn.id)
        assertEquals("Phoenix Portal", builtIn.name)
        assertEquals(bootstrap.url, builtIn.url)
        assertEquals(bootstrap.anonKey, builtIn.anonKey)
        assertTrue(builtIn.isBuiltIn)
        assertEquals(PortalServerRepository.BUILT_IN_SERVER_ID, repository.selectedServerId.value)
    }

    @Test
    fun activeSupabaseConfigDefaultsToBootstrapConfig() {
        assertEquals(bootstrap, createRepository().activeSupabaseConfig())
    }

    // ===== addServer validation =====

    @Test
    fun addServerAppendsCustomEntryAfterBuiltIn() {
        val repository = createRepository()

        val added = repository.addServer("Home", "https://portal.home.lan/", "home-key").getOrThrow()

        assertFalse(added.isBuiltIn)
        // Trailing slash is trimmed so the URL concatenates cleanly into API paths.
        assertEquals("https://portal.home.lan", added.url)
        assertEquals(listOf(PortalServerRepository.BUILT_IN_SERVER_ID, added.id), repository.servers.value.map { it.id })
    }

    @Test
    fun addServerRejectsBlankFieldsAndNonHttpUrls() {
        val repository = createRepository()

        assertTrue(repository.addServer("  ", "https://a.example.com", "key").isFailure)
        assertTrue(repository.addServer("Home", "https://a.example.com", " ").isFailure)
        assertTrue(repository.addServer("Home", "ftp://a.example.com", "key").isFailure)
        assertTrue(repository.addServer("Home", "not a url", "key").isFailure)
        assertTrue(repository.addServer("Home", "https://", "key").isFailure)
        assertEquals(1, repository.servers.value.size)
    }

    @Test
    fun addServerAllowsPlainHttpForLocalBackends() {
        val repository = createRepository()

        val added = repository.addServer("Lab", "http://192.168.1.10:8000", "key").getOrThrow()

        assertEquals("http://192.168.1.10:8000", added.url)
        assertEquals("192.168.1.10:8000", added.host)
    }

    // ===== Persistence =====

    @Test
    fun customServersAndSelectionSurviveRestart() {
        val settings = MapSettings()
        val added = createRepository(settings).addServer("Home", "https://portal.home.lan", "home-key").getOrThrow()
        createRepository(settings).selectServer(added.id)

        val restarted = createRepository(settings)

        assertEquals(listOf(PortalServerRepository.BUILT_IN_SERVER_ID, added.id), restarted.servers.value.map { it.id })
        assertEquals(added.id, restarted.selectedServerId.value)
        assertEquals(
            SupabaseConfig(url = "https://portal.home.lan", anonKey = "home-key"),
            restarted.activeSupabaseConfig(),
        )
        assertFalse(restarted.restartRequired.value, "restartRequired must not be persisted across launches")
    }

    @Test
    fun unknownPersistedSelectionFallsBackToBuiltIn() {
        val settings = MapSettings()
        settings.putString("portal_selected_server_id", "deleted-server")

        val repository = createRepository(settings)

        assertEquals(PortalServerRepository.BUILT_IN_SERVER_ID, repository.selectedServerId.value)
        assertEquals(bootstrap, repository.activeSupabaseConfig())
    }

    // ===== selectServer =====

    @Test
    fun selectServerFlagsRestartRequiredAndClearsSyncCheckpoints() {
        val secureSettings = MapSettings()
        val tokenStorage = PortalTokenStorage(secureSettings)
        val repository = PortalServerRepository(MapSettings(), bootstrap, tokenStorage)
        val added = repository.addServer("Home", "https://portal.home.lan", "home-key").getOrThrow()
        tokenStorage.setLastSyncTimestamp(1_700_000_000_000L)
        tokenStorage.setPhasePRBackfillCheckpoint("profile-a", 42L)
        tokenStorage.setPhasePRBackfillCheckpoint("profile-b", 43L)

        repository.selectServer(added.id)

        assertEquals(added.id, repository.selectedServerId.value)
        assertTrue(repository.restartRequired.value)
        assertEquals(0L, tokenStorage.getLastSyncTimestamp())
        assertEquals(0L, tokenStorage.getPhasePRBackfillCheckpoint("profile-a"))
        assertEquals(0L, tokenStorage.getPhasePRBackfillCheckpoint("profile-b"))
    }

    @Test
    fun selectServerIsANoOpForTheCurrentOrUnknownId() {
        val secureSettings = MapSettings()
        val tokenStorage = PortalTokenStorage(secureSettings)
        val repository = PortalServerRepository(MapSettings(), bootstrap, tokenStorage)
        tokenStorage.setLastSyncTimestamp(1_700_000_000_000L)

        repository.selectServer(PortalServerRepository.BUILT_IN_SERVER_ID)
        repository.selectServer("never-added")

        assertEquals(PortalServerRepository.BUILT_IN_SERVER_ID, repository.selectedServerId.value)
        assertFalse(repository.restartRequired.value)
        assertEquals(
            1_700_000_000_000L,
            tokenStorage.getLastSyncTimestamp(),
            "an unchanged selection must not throw away sync progress",
        )
    }

    // ===== removeServer =====

    @Test
    fun removeSelectedServerFallsBackToBuiltInAndClearsCheckpoints() {
        val secureSettings = MapSettings()
        val tokenStorage = PortalTokenStorage(secureSettings)
        val settings = MapSettings()
        val repository = PortalServerRepository(settings, bootstrap, tokenStorage)
        val added = repository.addServer("Home", "https://portal.home.lan", "home-key").getOrThrow()
        repository.selectServer(added.id)
        tokenStorage.setLastSyncTimestamp(1_700_000_000_000L)

        repository.removeServer(added.id)

        assertEquals(listOf(PortalServerRepository.BUILT_IN_SERVER_ID), repository.servers.value.map { it.id })
        assertEquals(PortalServerRepository.BUILT_IN_SERVER_ID, repository.selectedServerId.value)
        assertEquals(0L, tokenStorage.getLastSyncTimestamp())
        assertFalse(
            settings.getStringOrNull("portal_custom_servers").orEmpty().contains(added.id),
            "removed server must not linger in persisted storage",
        )
    }

    @Test
    fun removeServerIgnoresTheBuiltInEntry() {
        val repository = createRepository()

        repository.removeServer(PortalServerRepository.BUILT_IN_SERVER_ID)

        assertEquals(1, repository.servers.value.size)
    }

    // ===== Corrupt storage =====

    @Test
    fun corruptCustomServerPayloadDegradesToBuiltInOnly() {
        val settings = MapSettings()
        settings.putString("portal_custom_servers", "{not json")

        val repository = createRepository(settings)

        assertEquals(listOf(PortalServerRepository.BUILT_IN_SERVER_ID), repository.servers.value.map { it.id })
    }
}
