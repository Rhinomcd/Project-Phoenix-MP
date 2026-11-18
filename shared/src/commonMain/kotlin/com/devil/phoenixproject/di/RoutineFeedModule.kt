package com.devil.phoenixproject.di

import com.devil.phoenixproject.data.integration.routinefeed.RoutineFeedClient
import com.devil.phoenixproject.data.integration.routinefeed.RoutineFeedConfigProvider
import com.devil.phoenixproject.data.integration.routinefeed.RoutineFeedSync
import org.koin.dsl.module
import org.koin.dsl.onClose

val routineFeedModule = module {
    single { RoutineFeedClient() } onClose { it?.close() }
    single {
        RoutineFeedSync(
            config = getOrNull<RoutineFeedConfigProvider>()?.config,
            client = get(),
            workoutRepository = get(),
            exerciseRepository = get(),
            userProfileRepository = get(),
            settings = get(),
        )
    }
}
