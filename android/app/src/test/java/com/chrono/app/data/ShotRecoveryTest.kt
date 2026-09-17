package com.chrono.app.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ShotRecoveryTest {
    private fun draft() = TestResult("draft-4", 4, 0, 0.25, "Test4", 1234, deviceSerial="logger-a", bootId=42, shotFolder="project/Test4--uuid", tool="saved tool")

    @Test fun persistedSetupCanBeMatchedAfterProcessRestart() {
        val context = RuntimeEnvironment.getApplication()
        assertTrue(ResultStore(context,fileName="shot_drafts.json").save(listOf(draft())))
        val reloaded = ResultStore(context,fileName="shot_drafts.json").load()
        assertEquals("draft-4",matchingDraft(reloaded,emptyList(),"logger-a",42,4)?.uid)
    }
    @Test fun sameLabelOrCounterFromDifferentBootDoesNotAutoAttach() {
        assertNull(matchingDraft(listOf(draft()),emptyList(),"logger-a",43,4))
        assertNull(matchingDraft(listOf(draft()),emptyList(),"logger-b",42,4))
        assertNull(matchingDraft(listOf(draft()),emptyList(),"logger-a",0,4))
        assertNull(matchingDraft(listOf(draft()),emptyList(),"logger-a",42,5))
    }
    @Test fun attachmentPreservesCaptureAndUsesSavedSetup() {
        val reading=TestResult("capture",4,123456,0.0,"Recovered",5678,deviceSerial="logger-a",bootId=42,
            needsShotInfo=true, traceData="trace",traceFormatVersion=1)
        val linked=reading.withShotInfo(draft())
        assertEquals("capture",linked.uid)
        assertEquals(123456L,linked.splitNs)
        assertEquals(5678L,linked.epochMillis)
        assertEquals("trace",linked.traceData)
        assertEquals("Test4",linked.label)
        assertEquals(0.25,linked.distanceM,0.0)
        assertFalse(linked.needsShotInfo)
        assertNull(matchingDraft(listOf(draft()),listOf(linked),"logger-a",42,4))
        val roundtrip=testResultFromJson(testResultToJson(linked))
        assertEquals("draft-4",roundtrip.linkedDraftUid)
    }
    @Test fun unlinkedReadingsRemainUnlinkedAcrossRestart() {
        val reading=draft().copy(needsShotInfo=true, linkedDraftUid="")
        assertTrue(testResultFromJson(testResultToJson(reading)).needsShotInfo)
    }
}
