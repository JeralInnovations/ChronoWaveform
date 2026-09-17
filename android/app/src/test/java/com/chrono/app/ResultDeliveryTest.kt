package com.chrono.app

import com.chrono.app.ble.RawResult
import com.chrono.app.ble.Proto
import com.chrono.app.ble.ConnState
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import androidx.lifecycle.ViewModelStore
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ResultDeliveryTest {
    private val owner = ViewModelStore()
    @After fun close() { owner.clear() }
    private fun viewModel(): ChronoViewModel {
        val vm = ChronoViewModel(RuntimeEnvironment.getApplication())
        owner.put("test", vm)
        vm.connectSimulated()
        shadowOf(Looper.getMainLooper()).idle()
        return vm
    }

    @Test fun reconnectBatchKeepsEveryDistinctReading() {
        val vm = viewModel()
        vm.ble.results.tryEmit(RawResult(1, 125_000, 1_700_000_000, bootId = 12))
        vm.ble.results.tryEmit(RawResult(2, 130_000, 1_700_000_001, bootId = 12))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, vm.results.size)
        assertEquals(2, vm.results.map { it.shotFolder }.toSet().size)
    }

    @Test fun redeliveryDoesNotCreateASecondRecording() {
        val vm = viewModel()
        val packet = RawResult(7, 125_000, 1_700_000_000, bootId = 12)
        vm.ble.results.tryEmit(packet)
        shadowOf(Looper.getMainLooper()).idle()
        vm.ble.results.tryEmit(packet)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, vm.results.size)
    }

    @Test fun skippingTapTestsDoesNotVerifyOrArmSensors() {
        val vm = viewModel()
        vm.beginTapTest(1)
        shadowOf(Looper.getMainLooper()).idle()
        vm.skipRemainingTapTests()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Screen.DISTANCE, vm.screen)
        assertFalse(vm.sensor1Ready)
        assertFalse(vm.sensor2Ready)
        assertEquals(Proto.ST_IDLE, vm.ble.status.value?.state)
        vm.arm()
        assertEquals(Proto.ST_IDLE, vm.ble.status.value?.state)
    }

    @Test fun explicitOverrideCapturesWithoutTapTestsAndMarksTheReading() {
        val vm = viewModel()
        vm.armWithOverride()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(1, vm.results.size)
        assertTrue(vm.results.single().resultFlags and Proto.RESULT_ARM_OVERRIDE != 0)
        assertFalse(vm.sensor1Ready)
        assertFalse(vm.sensor2Ready)
    }

    @Test fun overrideStillRequiresConnectionIdleLoggerAndFreeSpace() {
        val vm = viewModel()
        val idle = vm.ble.status.value!!
        vm.ble.status.value = idle.copy(pendingCount = 16)
        assertFalse(vm.canRequestArm())
        assertTrue(vm.canReloadPendingShots())
        vm.armWithOverride()
        assertEquals(16, vm.ble.status.value?.pendingCount)
        vm.ble.status.value = idle.copy(state = Proto.ST_RUNNING)
        assertFalse(vm.canRequestArm())
        vm.ble.status.value = idle
        vm.ble.connState.value = ConnState.RECONNECTING
        assertFalse(vm.canRequestArm())
    }

    @Test fun latestCheckAfterAcknowledgementDoesNotDuplicateAndKeepsAssociation() {
        val vm = viewModel()
        vm.pendingLabel = "Test4"
        vm.armWithOverride()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(1,vm.results.size)
        assertEquals("Test4",vm.results.single().label)
        assertFalse(vm.results.single().needsShotInfo)
        assertEquals(0,vm.ble.status.value?.pendingCount)
        vm.ble.checkLatestReading()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1,vm.results.size)
        assertTrue(vm.results.single().linkedDraftUid.isNotBlank())
    }
}
