package com.devil.phoenixproject.data.integration.routinefeed

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.domain.csv.RoutineCsvCodec
import com.devil.phoenixproject.domain.csv.RoutineCsvImportMode
import com.devil.phoenixproject.domain.csv.RoutineCsvImportPlanner
import com.devil.phoenixproject.domain.csv.RoutineCsvParseResult
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RoutineFeedSync(
    private val config: RoutineFeedConfig?,
    private val client: RoutineFeedClient,
    private val workoutRepository: WorkoutRepository,
    private val exerciseRepository: ExerciseRepository,
    private val userProfileRepository: UserProfileRepository,
    private val settings: Settings,
) {
    private val mutex = Mutex()
    private val planner = RoutineCsvImportPlanner()

    suspend fun syncOnce() {
        val config = config ?: return
        val profileId = activeProfileId() ?: return
        if (!mutex.tryLock()) return
        try {
            val recorded = Json.decodeFromString<Map<String, Map<String, String>>>(settings.getString(SETTINGS_KEY, "{}"))
            val shaByPath = recorded[profileId].orEmpty().toMutableMap()
            val pending = client.list(config).filter { shaByPath[it.path] != it.sha }.sortedBy { it.name }
            val fetched = pending.map { entry ->
                val content = try {
                    client.fetch(config, entry)
                } catch (e: RoutineFeedFailure) {
                    if (e.kind != RoutineFeedFailureKind.TOO_LARGE) throw e
                    Logger.w { "Routine feed ${entry.name}: CSV exceeds the import size limit" }
                    null
                }
                entry to content
            }
            for ((entry, content) in fetched) {
                if (activeProfileId() != profileId) return
                if (content != null) {
                    when (val parsed = RoutineCsvCodec.parse(content)) {
                        is RoutineCsvParseResult.Invalid -> Logger.w { "Routine feed ${entry.name}: ${parsed.issues}" }
                        is RoutineCsvParseResult.Parsed -> {
                            val plan = planner.plan(
                                drafts = parsed.routines,
                                mode = RoutineCsvImportMode.OVERWRITE_MATCHING,
                                profileId = profileId,
                                existingRoutines = workoutRepository.getRoutineHeaders(profileId),
                                existingGroups = workoutRepository.getRoutineGroupsSnapshot(profileId),
                                exerciseLibrary = exerciseRepository.getAllExercises().first(),
                                nowMs = currentTimeMillis(),
                            )
                            if (activeProfileId() != profileId) return
                            if (plan.canCommit) {
                                workoutRepository.commitRoutineCsvImport(
                                    profileId = profileId,
                                    newGroups = plan.newGroups,
                                    routines = plan.writes,
                                    overwriteRoutineIds = plan.overwriteRoutineIds,
                                )
                            } else {
                                Logger.w { "Routine feed ${entry.name}: ${plan.issues}" }
                            }
                        }
                    }
                }
                shaByPath[entry.path] = entry.sha
                settings.putString(SETTINGS_KEY, Json.encodeToString(recorded + (profileId to shaByPath.toMap())))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RoutineFeedFailure) {
            Logger.w { "Routine feed stopped: ${e.kind}" }
        } catch (e: Exception) {
            Logger.w(e) { "Routine feed stopped before completion" }
        } finally {
            mutex.unlock()
        }
    }

    private fun activeProfileId(): String? =
        (userProfileRepository.activeProfileContext.value as? ActiveProfileContext.Ready)?.profile?.id

    companion object {
        const val SETTINGS_KEY = "routine_feed_imported_sha_by_path"
    }
}
