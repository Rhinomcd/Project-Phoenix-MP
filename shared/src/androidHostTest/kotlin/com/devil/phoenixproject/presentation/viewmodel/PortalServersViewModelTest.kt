package com.devil.phoenixproject.presentation.viewmodel

import com.devil.phoenixproject.data.sync.GoTrueAuthResponse
import com.devil.phoenixproject.data.sync.GoTrueUser
import com.devil.phoenixproject.data.sync.PortalServerRepository
import com.devil.phoenixproject.data.sync.PortalTokenStorage
import com.devil.phoenixproject.data.sync.SupabaseConfig
import com.devil.phoenixproject.testutil.TestCoroutineRule
import com.russhwolf.settings.MapSettings
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PortalServersViewModelTest {
    @get:Rule
    val coroutineRule = TestCoroutineRule()

    private val bootstrap = SupabaseConfig(url = "https://official.example.com", anonKey = "official-key")

    private lateinit var tokenStorage: PortalTokenStorage
    private lateinit var repository: PortalServerRepository

    private fun createViewModel(): PortalServersViewModel {
        tokenStorage = PortalTokenStorage(MapSettings())
        repository = PortalServerRepository(MapSettings(), bootstrap, tokenStorage)
        return PortalServersViewModel(repository, tokenStorage)
    }

    private fun link() {
        tokenStorage.saveGoTrueAuth(
            GoTrueAuthResponse(
                accessToken = "access",
                tokenType = "bearer",
                expiresIn = 3600,
                expiresAt = 1_740_916_800L,
                refreshToken = "refresh",
                user = GoTrueUser(id = "user-1", email = "user@example.com"),
            ),
        )
    }

    @Test
    fun `uiState starts on the built-in server and is not linked`() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launchCollect(viewModel)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf(PortalServerRepository.BUILT_IN_SERVER_ID), state.servers.map { it.id })
        assertEquals(PortalServerRepository.BUILT_IN_SERVER_ID, state.selectedId)
        assertFalse(state.isLinked)
        assertFalse(state.restartRequired)
        assertFalse(state.addDialogVisible)
        assertNull(state.addError)
    }

    @Test
    fun `addServer success closes the dialog and publishes the new server`() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launchCollect(viewModel)
        advanceUntilIdle()

        viewModel.showAddDialog()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.addDialogVisible)

        viewModel.addServer("Home", "https://portal.home.lan", "home-key")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.addDialogVisible)
        assertNull(state.addError)
        assertEquals(listOf("Phoenix Portal", "Home"), state.servers.map { it.name })
    }

    @Test
    fun `addServer failure keeps the dialog open with an error`() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launchCollect(viewModel)
        advanceUntilIdle()

        viewModel.showAddDialog()
        viewModel.addServer("Home", "ftp://portal.home.lan", "home-key")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.addDialogVisible)
        assertNotNull(state.addError)
        assertEquals(1, state.servers.size)
    }

    @Test
    fun `selectServer flags restartRequired`() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launchCollect(viewModel)
        advanceUntilIdle()
        val added = repository.addServer("Home", "https://portal.home.lan", "home-key").getOrThrow()

        viewModel.selectServer(added.id)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(added.id, state.selectedId)
        assertTrue(state.restartRequired)
    }

    @Test
    fun `selectServer is ignored while the portal account is linked`() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launchCollect(viewModel)
        advanceUntilIdle()
        val added = repository.addServer("Home", "https://portal.home.lan", "home-key").getOrThrow()
        link()
        advanceUntilIdle()

        viewModel.selectServer(added.id)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isLinked)
        assertEquals(PortalServerRepository.BUILT_IN_SERVER_ID, state.selectedId)
        assertFalse(state.restartRequired)
    }

    @Test
    fun `removeServer drops the custom entry`() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launchCollect(viewModel)
        advanceUntilIdle()
        val added = repository.addServer("Home", "https://portal.home.lan", "home-key").getOrThrow()
        advanceUntilIdle()

        viewModel.removeServer(added.id)
        advanceUntilIdle()

        assertEquals(
            listOf(PortalServerRepository.BUILT_IN_SERVER_ID),
            viewModel.uiState.value.servers.map { it.id },
        )
    }
}

/** [PortalServersViewModel.uiState] is WhileSubscribed, so tests need an active collector. */
private fun CoroutineScope.launchCollect(viewModel: PortalServersViewModel) {
    launch { viewModel.uiState.collect {} }
}
