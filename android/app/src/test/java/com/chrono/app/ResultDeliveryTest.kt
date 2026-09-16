package com.chrono.app

import com.chrono.app.ble.RawResult
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
}
