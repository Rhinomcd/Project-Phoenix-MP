package com.devil.phoenixproject.data.integration.routinefeed

data class RoutineFeedConfig(val repo: String, val path: String, val token: String) {
    companion object {
        fun from(repo: String, path: String, token: String): RoutineFeedConfig? =
            if (listOf(repo, path, token).all { it.isNotBlank() }) RoutineFeedConfig(repo, path, token) else null
    }
}

// Koin singleton definitions cannot return null.
data class RoutineFeedConfigProvider(val config: RoutineFeedConfig?)
