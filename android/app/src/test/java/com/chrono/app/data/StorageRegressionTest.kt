package com.chrono.app.data

import android.app.Application
import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StorageRegressionTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private fun result(id: String, label: String = "Test1") = TestResult(
        uid = id, deviceResultId = 1, splitNs = 125_000, distanceM = 0.1,
        label = label, epochMillis = 1_000, shotFolder = "project/$id",
    )
    private fun json(id: String) = JSONObject().put("uid", id).put("splitNs", 125_000)

    @Test fun identicalLabelsNeverReuseFolders() {
        val session = SessionManager(app)
        session.startProject("project")
        val first = session.logShot("Test1", json("first"))
        session.beginNewTest()
        val second = session.logShot("Test1", json("second"))
        assertNotEquals(first, second)
        assertEquals(setOf("first", "second"), session.loadProjectResults().map { it.uid }.toSet())
    }

    @Test fun lostPreferencesNeverOverwriteOlderRecording() {
        val session = SessionManager(app)
        session.startProject("project")
        val first = session.logShot("Test1", json("first"))
        app.getSharedPreferences("chrono_session", Context.MODE_PRIVATE).edit().clear().commit()
        val upgraded = SessionManager(app)
        upgraded.startProject("project")
        val second = upgraded.logShot("Test1", json("second"))
        assertNotEquals(first, second)
        assertEquals(2, upgraded.loadProjectResults().size)
    }

    @Test fun labelsCanChangeWithoutMovingTheOwningFolder() {
        val session = SessionManager(app)
        session.startProject("project")
        val rel = session.logShot("Test1", json("first"))
        val renamed = session.renameTestFolder(rel, "A better name")
        assertEquals(rel, renamed.first)
        assertEquals("A better name", renamed.second)
        assertTrue(session.updateShot(rel, json("first").put("label", renamed.second)))
        assertEquals("A better name", session.loadProjectResults().single().label)
    }

    @Test fun cannotOverwriteAnotherRecordsCanonicalFile() {
        val session = SessionManager(app)
        session.startProject("project")
        val rel = session.logShot("Test1", json("original"))
        assertFalse(session.updateShot(rel, json("different")))
        assertEquals("original", session.loadProjectResults().single().uid)
    }

    @Test fun partialPublicScanCannotDeleteLocalRecordsOrRevertEdits() {
        val first = result("one", "edited")
        val second = result("two")
        val recovered = recoverResults(listOf(first.copy(label = "stale")), listOf(first, second))
        assertEquals(2, recovered.size)
        assertEquals("edited", recovered.first { it.uid == "one" }.label)
    }

    @Test fun publicOnlyLegacyRecordsImportOnce() {
        val old = result("old")
        val local = result("new")
        val recovered = recoverResults(listOf(old, local.copy(label = "stale")), listOf(local))
        assertEquals(setOf("old", "new"), recovered.map { it.uid }.toSet())
        assertEquals(2, recoverResults(listOf(old), recovered).size)
    }

    @Test fun olderFolderCollisionDoesNotHideEitherReading() {
        val local = result("original")
        val public = result("later").copy(shotFolder = local.shotFolder)
        assertEquals(setOf("original", "later"), recoverResults(listOf(public), listOf(local)).map { it.uid }.toSet())
    }

    @Test fun portableJsonRetainsManualFieldsAndReviewMetadata() {
        val original = result("manual").copy(deviceResultId = -1, manualVelocityMps = 120.5,
            tool = "test tool", target = "test target", targetDistValue = 4.5, targetDistUnit = "m",
            reviewedSplitNs = 2500, reviewedAtMillis = 9999, accepted = false)
        assertEquals(original, testResultFromJson(testResultToJson(original)))
    }

    @Test fun atomicStoreRoundTripsWaveformAndReview() {
        val original = result("one").copy(traceFormatVersion = 1, traceData = "AAABAg==",
            reviewedSplitNs = -12_500, reviewedStartOffsetTicks = 500,
            reviewedStopOffsetTicks = 300, accepted = false)
        val store = ResultStore(app)
        assertTrue(store.save(listOf(original)))
        assertEquals(original, ResultStore(app).load().single())
    }

    @Test fun interruptedReplacementRestoresCommittedLibrary() {
        val store = ResultStore(app)
        assertTrue(store.save(listOf(result("original"))))
        val atomic = AtomicFile(File(app.filesDir, "results.json"))
        val abandoned = atomic.startWrite()
        abandoned.write("[broken".toByteArray())
        abandoned.close() // Simulate process termination before finishWrite.
        assertEquals("original", ResultStore(app).load().single().uid)
    }

    @Test fun corruptLibraryIsNotSilentlyOverwrittenWithEmptyData() {
        val file = File(app.filesDir, "results.json")
        file.writeText("broken original")
        val store = ResultStore(app)
        assertTrue(store.load().isEmpty())
        assertFalse(store.save(emptyList()))
        assertEquals("broken original", file.readText())
    }

    @Test fun simulationNeverChangesRealLibrary() {
        val real = ResultStore(app)
        val sim = ResultStore(app, simulation = true)
        assertTrue(real.save(listOf(result("real"))))
        assertTrue(sim.save(listOf(result("sim"))))
        assertEquals("real", real.load().single().uid)
        assertEquals("sim", sim.load().single().uid)
    }

    @Test fun simulationPhotoUrisWorkOnOlderAndroid() {
        val session = SessionManager(app, simulation = true)
        session.startProject("demo")
        val first = session.newPhotoUri("setup", "Test1")
        val second = session.newPhotoUri("setup", "Test1")
        assertNotNull(first)
        assertNotEquals(first, second)
    }
}
