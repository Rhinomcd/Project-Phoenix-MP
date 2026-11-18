package com.devil.phoenixproject.data.integration.routinefeed

import com.devil.phoenixproject.domain.csv.RoutineCsvCodec
import com.devil.phoenixproject.domain.csv.RoutineCsvExportResult
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.FakeWorkoutRepository
import com.russhwolf.settings.MapSettings
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RoutineFeedSyncTest {
    @Test
    fun withoutConfigMakesNoHttpCalls() = runTest {
        val fixture = Fixture()
        fixture.sync(config = null).syncOnce()
        assertTrue(fixture.requests.isEmpty())
        assertTrue(fixture.settings.keys.isEmpty())
        fixture.client.close()
    }

    @Test
    fun importsNewFileAndPersistsShaAcrossSyncInstances() = runTest {
        val fixture = Fixture()
        fixture.sync().syncOnce()
        val imported = fixture.workouts.getRoutineHeaders("default").single()
        assertEquals("Push", imported.name)
        assertEquals(listOf(8, 8), fixture.workouts.getRoutineById(imported.id)?.exercises?.single()?.setReps)
        assertTrue(fixture.settings.keys.isNotEmpty())

        fixture.requests.clear()
        fixture.sync().syncOnce()
        assertEquals(listOf("/repos/owner/routines/contents/feed"), fixture.requests.map { it.url.encodedPath })
        assertEquals(1, fixture.workouts.getRoutineHeaders("default").size)
        fixture.client.close()
    }

    @Test
    fun changedShaOverwritesSameRoutineInPlace() = runTest {
        val fixture = Fixture()
        fixture.sync().syncOnce()
        val originalId = fixture.workouts.getRoutineHeaders("default").single().id
        fixture.sha = "sha-two"
        fixture.content = csv(reps = listOf(12, 10, 8))

        fixture.sync().syncOnce()

        val stored = fixture.workouts.getRoutineHeaders("default").single()
        assertEquals(originalId, stored.id)
        assertEquals(listOf(12, 10, 8), fixture.workouts.getRoutineById(originalId)?.exercises?.single()?.setReps)
        fixture.client.close()
    }

    @Test
    fun revertedFileReimportsItsEarlierVersion() = runTest {
        val fixture = Fixture()
        fixture.sync().syncOnce()
        val routineId = fixture.workouts.getRoutineHeaders("default").single().id
        fixture.sha = "sha-two"
        fixture.content = csv(reps = listOf(12, 10, 8))
        fixture.sync().syncOnce()

        fixture.sha = "sha-one"
        fixture.content = csv()
        fixture.sync().syncOnce()

        assertEquals(listOf(8, 8), fixture.workouts.getRoutineById(routineId)?.exercises?.single()?.setReps)
        fixture.client.close()
    }

    @Test
    fun invalidCsvIsRecordedAndNotFetchedAgain() = runTest {
        val fixture = Fixture()
        fixture.content = "not Phoenix CSV"
        fixture.sync().syncOnce()
        assertTrue(fixture.workouts.getRoutineHeaders("default").isEmpty())
        assertTrue(fixture.settings.keys.isNotEmpty())
        fixture.requests.clear()

        fixture.sync().syncOnce()

        assertEquals(1, fixture.requests.size)
        assertTrue(fixture.workouts.getRoutineHeaders("default").isEmpty())
        fixture.client.close()
    }

    @Test
    fun networkFailureRecordsNothingAndRetriesNextForeground() = runTest {
        val fixture = Fixture()
        fixture.failFetch = true
        fixture.sync().syncOnce()
        assertTrue(fixture.settings.keys.isEmpty())
        assertTrue(fixture.workouts.getRoutineHeaders("default").isEmpty())
        fixture.failFetch = false

        fixture.sync().syncOnce()

        assertEquals(1, fixture.workouts.getRoutineHeaders("default").size)
        fixture.client.close()
    }

    @Test
    fun unmatchedExerciseIsRecordedAndNotFetchedAgain() = runTest {
        val fixture = Fixture()
        fixture.content = csv().replace("bench-id", "missing-id").replace("Bench Press", "Unknown exercise")
        fixture.sync().syncOnce()
        assertTrue(fixture.workouts.getRoutineHeaders("default").isEmpty())
        assertTrue(fixture.settings.keys.isNotEmpty())
        fixture.requests.clear()

        fixture.sync().syncOnce()

        assertEquals(1, fixture.requests.size)
        fixture.client.close()
    }

    @Test
    fun failureFetchingLaterFileRecordsNoEarlierSha() = runTest {
        val fixture = Fixture()
        fixture.extraEntries = """,{"name":"Z.csv","path":"feed/Z.csv","sha":"later","type":"file"}"""
        fixture.failPath = "/repos/owner/routines/contents/feed/Z.csv"

        fixture.sync().syncOnce()

        assertEquals(3, fixture.requests.size)
        assertTrue(fixture.settings.keys.isEmpty())
        assertTrue(fixture.workouts.getRoutineHeaders("default").isEmpty())
        fixture.client.close()
    }

    @Test
    fun unauthorizedRecordsNothingAndRetriesNextForeground() = runTest {
        val fixture = Fixture()
        fixture.listStatus = HttpStatusCode.Unauthorized
        fixture.sync().syncOnce()
        assertEquals(1, fixture.requests.size)
        assertTrue(fixture.settings.keys.isEmpty())
        assertTrue(fixture.workouts.getRoutineHeaders("default").isEmpty())
        fixture.listStatus = HttpStatusCode.OK

        fixture.sync().syncOnce()

        assertEquals(1, fixture.workouts.getRoutineHeaders("default").size)
        fixture.client.close()
    }

    @Test
    fun recordedShasAreScopedToProfile() = runTest {
        val fixture = Fixture()
        fixture.sync().syncOnce()
        fixture.profiles.setActiveProfileForTest(id = "second")

        fixture.sync().syncOnce()

        assertEquals(1, fixture.workouts.getRoutineHeaders("default").size)
        assertEquals(1, fixture.workouts.getRoutineHeaders("second").size)
        fixture.client.close()
    }

    @Test
    fun switchingProfileMakesNoHttpCalls() = runTest {
        val fixture = Fixture()
        fixture.profiles.emitSwitchingForTest("second")

        fixture.sync().syncOnce()

        assertTrue(fixture.requests.isEmpty())
        fixture.client.close()
    }

    @Test
    fun concurrentForegroundCallDoesNotStartSecondSync() = runTest {
        val fixture = Fixture()
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        fixture.fetchGate = gate
        fixture.fetchStarted = started
        val sync = fixture.sync()
        val first = launch { sync.syncOnce() }
        started.await()

        sync.syncOnce()

        assertEquals(2, fixture.requests.size)
        gate.complete(Unit)
        first.join()
        assertEquals(1, fixture.workouts.getRoutineHeaders("default").size)
        fixture.client.close()
    }

    @Test
    fun sendsGithubHeadersAndIgnoresNonCsvFilesAndDirectories() = runTest {
        val fixture = Fixture()
        fixture.extraEntries = """,{"name":"README.md","path":"feed/README.md","sha":"readme","type":"file"},{"name":"archive.csv","path":"feed/archive.csv","sha":"directory","type":"dir"}"""

        fixture.sync().syncOnce()

        assertEquals(2, fixture.requests.size)
        val listing = fixture.requests.first()
        val download = fixture.requests.last()
        assertEquals("api.github.com", listing.url.host)
        assertEquals("Bearer test-token", listing.headers[HttpHeaders.Authorization])
        assertEquals("application/vnd.github+json", listing.headers[HttpHeaders.Accept])
        assertEquals("2022-11-28", listing.headers["X-GitHub-Api-Version"])
        assertEquals("application/vnd.github.raw", download.headers[HttpHeaders.Accept])
        assertEquals("/repos/owner/routines/contents/feed/Push.CSV", download.url.encodedPath)
        fixture.client.close()
    }

    private class Fixture {
        var fetchGate: CompletableDeferred<Unit>? = null
        var fetchStarted: CompletableDeferred<Unit>? = null
        var failPath: String? = null
        val settings = MapSettings()
        val workouts = FakeWorkoutRepository()
        val profiles = FakeUserProfileRepository().apply { setActiveProfileForTest() }
        private val exercises = FakeExerciseRepository().apply { addExercise(bench) }
        val requests = mutableListOf<HttpRequestData>()
        var sha = "sha-one"
        var content = csv()
        var listStatus = HttpStatusCode.OK
        var failFetch = false
        var extraEntries = ""
        val client = RoutineFeedClient(MockEngine { request ->
            requests += request
            if (request.url.encodedPath == "/repos/owner/routines/contents/feed") {
                respond(
                    """[{"name":"Push.CSV","path":"feed/Push.CSV","sha":"$sha","type":"file"}$extraEntries]""",
                    listStatus,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            } else {
                if (failFetch || request.url.encodedPath == failPath) throw IllegalStateException("connection lost")
                fetchStarted?.complete(Unit)
                fetchGate?.await()
                respond(content, HttpStatusCode.OK)
            }
        })

        fun sync(config: RoutineFeedConfig? = RoutineFeedConfig("owner/routines", "feed", "test-token")) = RoutineFeedSync(
            config = config,
            client = client,
            workoutRepository = workouts,
            exerciseRepository = exercises,
            userProfileRepository = profiles,
            settings = settings,
        )
    }

    private companion object {
        val bench = Exercise(id = "bench-id", name = "Bench Press", muscleGroup = "Chest")

        fun csv(reps: List<Int?> = listOf(8, 8)): String {
            val routine = Routine(
                id = "source-id",
                name = "Push",
                exercises = listOf(
                    RoutineExercise(
                        id = "source-exercise",
                        exercise = bench,
                        orderIndex = 0,
                        setReps = reps,
                        weightPerCableKg = 20f,
                        setWeightsPerCableKg = List(reps.size) { 20f },
                    ),
                ),
            )
            return assertIs<RoutineCsvExportResult.Exported>(RoutineCsvCodec.encode(routine, null, null)).content
        }
    }
}
