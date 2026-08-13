package com.devil.phoenixproject.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devil.phoenixproject.data.sync.PortalServerEntry
import com.devil.phoenixproject.data.sync.PortalServerRepository
import com.devil.phoenixproject.data.sync.PortalTokenStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class PortalServersUiState(
    val servers: List<PortalServerEntry> = emptyList(),
    val selectedId: String = PortalServerRepository.BUILT_IN_SERVER_ID,
    val isLinked: Boolean = false,
    val restartRequired: Boolean = false,
    val addDialogVisible: Boolean = false,
    val addError: String? = null,
)

private data class PortalServersDialogState(
    val visible: Boolean = false,
    val error: String? = null,
)

class PortalServersViewModel(
    private val servers: PortalServerRepository,
    private val tokenStorage: PortalTokenStorage,
) : ViewModel() {

    private val dialogState = MutableStateFlow(PortalServersDialogState())

    val uiState: StateFlow<PortalServersUiState> = combine(
        servers.servers,
        servers.selectedServerId,
        servers.restartRequired,
        tokenStorage.isAuthenticated,
        dialogState,
    ) { entries, selectedId, restartRequired, isLinked, dialog ->
        PortalServersUiState(
            servers = entries,
            selectedId = selectedId,
            isLinked = isLinked,
            restartRequired = restartRequired,
            addDialogVisible = dialog.visible,
            addError = dialog.error,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        PortalServersUiState(),
    )

    /**
     * Switching backends while linked would leave the session (and any already-synced
     * rows) pointing at the previous server, so the account must be unlinked first.
     */
    fun selectServer(id: String) {
        // Read the source directly: uiState only tracks it while the screen collects.
        if (tokenStorage.isAuthenticated.value) return
        servers.selectServer(id)
    }

    fun addServer(name: String, url: String, anonKey: String) {
        servers.addServer(name, url, anonKey)
            .onSuccess { dialogState.value = PortalServersDialogState() }
            .onFailure { error ->
                dialogState.update { it.copy(error = error.message ?: "Could not add server") }
            }
    }

    fun removeServer(id: String) {
        servers.removeServer(id)
    }

    fun showAddDialog() {
        dialogState.value = PortalServersDialogState(visible = true)
    }

    fun dismissAddDialog() {
        dialogState.value = PortalServersDialogState()
    }
}
