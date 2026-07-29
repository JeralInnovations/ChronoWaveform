package com.chrono.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.chrono.app.ChronoViewModel
import com.chrono.app.Screen

/** Top-level router. Navigation is a simple state machine held by the ViewModel. */
@Composable
fun ChronoApp(vm: ChronoViewModel) {
    val connState by vm.ble.connState.collectAsState()
    val deviceStatus by vm.ble.status.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshProjectData()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // New day (or first run): name a project folder, or keep the previous one.
    if (vm.projectPrompt) {
        var name by remember { mutableStateOf(vm.defaultProjectName()) }
        val previous = vm.projectName
        AlertDialog(
            onDismissRequest = { },   // require an explicit choice
            title = { Text("Project folder") },
            text = {
                Column {
                    Text(
                        if (previous == null)
                            "Name the project folder for today's tests. Each test is a " +
                                "subfolder named by its label."
                        else
                            "New day. Start a new project folder, or keep logging into " +
                                "\"$previous\".",
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("New project name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.startProject(name) }) { Text("Start new") }
            },
            dismissButton = {
                if (previous != null) {
                    TextButton(onClick = { vm.keepProject() }) { Text("Keep \"$previous\"") }
                }
            },
        )
    }

    when (vm.screen) {
        Screen.CONNECT -> ConnectScreen(vm, connState)
        Screen.BASELINE -> BaselineScreen(vm, connState)
        Screen.SENSOR1 -> SensorSetupScreen(
            vm = vm,
            sensor = 1,
            deviceState = deviceStatus?.state ?: -1,
            connState = connState,
            onContinue = { vm.continueToSensor2() },
        )
        Screen.SENSOR2 -> SensorSetupScreen(
            vm = vm,
            sensor = 2,
            deviceState = deviceStatus?.state ?: -1,
            connState = connState,
            onContinue = { vm.continueToDistance() },
        )
        Screen.DISTANCE -> DistanceScreen(vm)
        Screen.DASHBOARD -> DashboardScreen(vm, connState, deviceStatus)
    }
}
