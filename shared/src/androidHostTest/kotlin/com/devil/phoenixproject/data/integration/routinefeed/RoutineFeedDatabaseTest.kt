package com.devil.phoenixproject.data.integration.routinefeed

import com.devil.phoenixproject.data.repository.SqlDelightWorkoutRepository
import com.devil.phoenixproject.domain.csv.RoutineCsvFormat
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import com.russhwolf.settings.MapSettings
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RoutineFeedDatabaseTest {
    @Test
    fun changedShaUpdatesSetsWithoutDuplicatingRoutineInSqlite() = runTest {
        val database = createTestDatabase()
        val exercises = FakeExerciseRepository().apply {
            addExercise(Exercise(id = "bench-id", name = "Bench Press", muscleGroup = "Chest", equipment = "BAR"))
        }
        database.phoenixDatabaseQueries.insertExercise(
            id = "bench-id", name = "Bench Press", displayName = null, description = null, created = 0L,
            muscleGroup = "Chest", muscleGroups = "Chest", muscles = null, equipment = "BAR", movement = null,
            sidedness = null, grip = null, gripWidth = null, minRepRange = null, popularity = 0.0, archived = 0L,
            isFavorite = 0L, isCustom = 0L, timesPerformed = 0L, lastPerformed = null, aliases = null,
            defaultCableConfig = "DOUBLE", one_rep_max_kg = null, mvtOverrideMs = null, isBodyweight = null,
        )
        database.phoenixDatabaseQueries.insertProfile("feed-profile", "Lifter", 0L, 1L, 1L)
        val workouts = SqlDelightWorkoutRepository(database, exercises)
        val profiles = FakeUserProfileRepository().apply { setActiveProfileForTest(id = "feed-profile") }
        var sha = "first"
        var reps = "8|8"
        var weights = "20|20"
        val client = RoutineFeedClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("/contents/feed")) {
                respond(
                    """[{"name":"Push.csv","path":"feed/Push.csv","type":"file","sha":"$sha"}]""",
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            } else {
                respond(
                    listOf(
                        RoutineCsvFormat.VERSION_LINE,
                        RoutineCsvFormat.COLUMNS.joinToString(","),
                        ",Push,,,,bench-id,Bench Press,0,,,,,$reps,$weights,90,OLD_SCHOOL,false,",
                    ).joinToString("\n"),
                )
            }
        })
        try {
            val sync = RoutineFeedSync(
                RoutineFeedConfig("owner/routines", "feed", "test-token"),
                client, workouts, exercises, profiles, MapSettings(),
            )
            sync.syncOnce()
            val originalId = workouts.getRoutineHeaders("feed-profile").single().id
            assertEquals(listOf(8, 8), workouts.getRoutineById(originalId)?.exercises?.single()?.setReps)
            sha = "second"
            reps = "12|10|8"
            weights = "25|25|25"

            sync.syncOnce()

            assertEquals(originalId, workouts.getRoutineHeaders("feed-profile").single().id)
            val stored = workouts.getRoutineById(originalId)!!.exercises.single()
            assertEquals(listOf(12, 10, 8), stored.setReps)
            assertEquals(listOf(25f, 25f, 25f), stored.setWeightsPerCableKg)
        } finally {
            client.close()
        }
    }
}
