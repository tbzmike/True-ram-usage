package com.tbzmike.trueramusage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tbzmike.trueramusage.data.AppSwapUsage
import com.tbzmike.trueramusage.data.MemorySnapshot
import com.tbzmike.trueramusage.data.RootState
import com.tbzmike.trueramusage.data.SwapDevice
import com.tbzmike.trueramusage.data.ZramClearSafety
import com.tbzmike.trueramusage.data.ZramDevice
import java.util.Locale
import kotlin.math.max

@Composable
fun TrueRamApp(viewModel: MemoryViewModel = viewModel()) {
    var appToClose by remember { mutableStateOf<AppSwapUsage?>(null) }
    var confirmClearZram by remember { mutableStateOf(false) }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val snapshot = viewModel.snapshot
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("True RAM Usage", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("RAM and kernel ZRAM information in plain language.")

                RootCard(viewModel.rootState, viewModel.rootRequestInProgress, viewModel::requestRoot)

                if (snapshot == null) {
                    Text(viewModel.errorMessage ?: "Reading memory information…")
                    Button(onClick = viewModel::refreshNow) { Text("Refresh") }
                    return@Column
                }

                PhysicalRamCard(snapshot.usedRamBytes, snapshot.totalRamBytes, snapshot.availableRamBytes)

                SwapSummaryCard(
                    used = snapshot.usedSwapBytes,
                    total = snapshot.totalSwapBytes,
                    onlyKernelZram = snapshot.swapDevices.isNotEmpty() && snapshot.swapDevices.all { it.isZram }
                )

                if (snapshot.zramDevices.isEmpty()) {
                    InfoCard(
                        "Kernel ZRAM",
                        if (viewModel.rootState == RootState.GRANTED) {
                            "Root is granted, but this kernel did not expose readable ZRAM statistics."
                        } else {
                            "Grant root access to read detailed kernel ZRAM statistics."
                        }
                    )
                } else {
                    snapshot.zramDevices.forEach { ZramCard(it) }
                }

                if (viewModel.rootState == RootState.GRANTED && snapshot.activeZramSwapDevices.isNotEmpty()) {
                    ZramControlsCard(
                        usedBytes = snapshot.kernelZramSwapUsedBytes,
                        safety = viewModel.zramClearSafety,
                        actionInProgress = viewModel.actionInProgress,
                        onClear = { confirmClearZram = true }
                    )
                }

                if (viewModel.rootState == RootState.GRANTED) {
                    AppsInZramSection(
                        apps = viewModel.appsInZram,
                        scanning = viewModel.appsScanInProgress,
                        scanError = viewModel.appsScanError,
                        actionInProgress = viewModel.actionInProgress,
                        onlyKernelZram = snapshot.swapDevices.isNotEmpty() && snapshot.swapDevices.all { it.isZram },
                        onRefresh = viewModel::refreshAppsNow,
                        onClose = { appToClose = it }
                    )
                }

                AdvancedSystemDetails(snapshot)

                viewModel.actionMessage?.let { InfoCard("Completed", it) }
                viewModel.actionError?.let { InfoCard("Action warning", it) }
                viewModel.errorMessage?.let { InfoCard("Read warning", it) }

                Button(onClick = viewModel::refreshNow, modifier = Modifier.fillMaxWidth()) {
                    Text("Refresh memory readings")
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    appToClose?.let { app ->
        AlertDialog(
            onDismissRequest = { appToClose = null },
            title = { Text("Close ${app.label}?") },
            text = { Text("This force-stops the app so its ${formatBytes(app.attributedSwapBytes)} of attributed swapped pages can be released from ZRAM. The app can be opened again normally.") },
            confirmButton = {
                Button(onClick = {
                    appToClose = null
                    viewModel.closeAndRelease(app)
                }) { Text("Close & release") }
            },
            dismissButton = { TextButton(onClick = { appToClose = null }) { Text("Cancel") } }
        )
    }

    if (confirmClearZram) {
        AlertDialog(
            onDismissRequest = { confirmClearZram = false },
            title = { Text("Clear kernel ZRAM?") },
            text = { Text("The current ZRAM swap will be temporarily disabled so its pages return to physical RAM, then the same device will be enabled again. The 6 GiB size is not changed.") },
            confirmButton = {
                Button(onClick = {
                    confirmClearZram = false
                    viewModel.clearKernelZram()
                }) { Text("Clear ZRAM") }
            },
            dismissButton = { TextButton(onClick = { confirmClearZram = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun RootCard(state: RootState, inProgress: Boolean, onRequestRoot: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (state) {
                RootState.GRANTED -> Text("Root access granted", fontWeight = FontWeight.Bold)
                RootState.DENIED_OR_TIMED_OUT -> {
                    Text("Root access not granted", fontWeight = FontWeight.Bold)
                    Button(onClick = onRequestRoot, enabled = !inProgress) { Text("Retry root access") }
                }
                RootState.UNAVAILABLE -> Text("No compatible root command found.", fontWeight = FontWeight.Bold)
                RootState.NOT_REQUESTED -> {
                    Text("Root required for full ZRAM details", fontWeight = FontWeight.Bold)
                    Button(onClick = onRequestRoot, enabled = !inProgress) {
                        Text(if (inProgress) "Requesting root…" else "Grant root access")
                    }
                }
            }
        }
    }
}

@Composable
private fun PhysicalRamCard(used: Long, total: Long, available: Long) {
    val percent = if (total > 0) used.toDouble() / total * 100.0 else 0.0
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Physical RAM", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${formatBytes(used)} of ${formatBytes(total)} used", style = MaterialTheme.typography.headlineSmall)
            ValueRow("Used", formatPercent(percent))
            ValueRow("Available", formatBytes(available))
        }
    }
}

@Composable
private fun SwapSummaryCard(used: Long, total: Long, onlyKernelZram: Boolean) {
    val percent = if (total > 0) used.toDouble() / total * 100.0 else 0.0
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (onlyKernelZram) "Kernel ZRAM usage" else "Swap usage", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${formatBytes(used)} of ${formatBytes(total)} used", style = MaterialTheme.typography.headlineSmall)
            ValueRow("Used", formatPercent(percent))
            if (onlyKernelZram) Text("The kernel reports ZRAM as the active swap.")
        }
    }
}

@Composable
private fun ZramCard(device: ZramDevice) {
    var expanded by remember(device.name) { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Kernel ZRAM", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            ValueRow("Capacity", formatBytes(device.diskSizeBytes))
            ValueRow("Data currently stored", formatBytes(device.originalDataBytes))
            ValueRow("Physical RAM used", formatBytes(device.memoryUsedBytes))
            ValueRow("RAM saved", formatBytes(device.ramSavedBytes))
            device.compressionRatio?.let { ValueRow("Compression", String.format(Locale.US, "%.2f×", it)) }

            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide advanced details" else "Show advanced details")
            }

            if (expanded) {
                ValueRow("Compressed payload", formatBytes(device.compressedDataBytes))
                device.effectiveRamRatio?.let { ValueRow("Effective RAM ratio", String.format(Locale.US, "%.2f×", it)) }
                device.compressionAlgorithm?.let { ValueRow("Compression method", it) }
                if (device.peakMemoryUsedBytes > 0) ValueRow("Peak physical RAM used", formatBytes(device.peakMemoryUsedBytes))
                if (device.memoryLimitBytes > 0) ValueRow("Physical RAM limit", formatBytes(device.memoryLimitBytes))
                if (device.samePages > 0) ValueRow("Identical pages optimized", formatCount(device.samePages))
                if (device.compactedPages > 0) ValueRow("Pages freed by compaction", formatCount(device.compactedPages))
                if (device.hugePages > 0) ValueRow("Poorly compressible huge pages", formatCount(device.hugePages))
            }
        }
    }
}

@Composable
private fun ZramControlsCard(
    usedBytes: Long,
    safety: ZramClearSafety?,
    actionInProgress: Boolean,
    onClear: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("ZRAM controls", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            ValueRow("Currently swapped", formatBytes(usedBytes))
            when {
                safety == null -> Text("Checking safety…")
                safety.canClear -> Text("Enough physical RAM is available for a guarded clear.")
                safety.additionalNeededBytes > 0 -> Text("Clear is blocked for safety. About ${formatBytes(safety.additionalNeededBytes)} more physical RAM is needed first.")
                else -> Text("No active kernel ZRAM swap device was detected.")
            }
            Button(
                onClick = onClear,
                enabled = safety?.canClear == true && !actionInProgress,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (actionInProgress) "Working…" else "Clear kernel ZRAM") }
        }
    }
}

@Composable
private fun AppsInZramSection(
    apps: List<AppSwapUsage>,
    scanning: Boolean,
    scanError: String?,
    actionInProgress: Boolean,
    onlyKernelZram: Boolean,
    onRefresh: () -> Unit,
    onClose: (AppSwapUsage) -> Unit
) {
    Text(
        if (onlyKernelZram) "Apps currently in kernel ZRAM" else "Apps currently using swap",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )

    when {
        scanning -> InfoCard("Scanning apps", "Reading lightweight per-process swap counters…")
        scanError != null -> InfoCard("App scan failed", scanError)
        apps.isEmpty() -> InfoCard("No app pages found", "No installed app currently has readable private swapped pages.")
        else -> {
            val attributed = apps.sumOf { it.attributedSwapBytes }
            Text("${formatBytes(attributed)} attributed to installed apps")
            apps.forEach { app -> AppSwapCard(app, actionInProgress, onClose) }
        }
    }

    Button(
        onClick = onRefresh,
        enabled = !scanning && !actionInProgress,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (scanning) "Scanning…" else "Refresh app list")
    }

    CollapsibleInfoCard(
        title = "How app ZRAM usage is measured",
        body = if (onlyKernelZram) {
            "Only ZRAM is active as swap. The app reads each process's VmSwap counter from /proc/<pid>/status. This is fast and directly reports that process's private anonymous memory currently swapped into ZRAM. Shared tmpfs/shmem swap is not counted in an individual app figure."
        } else {
            "More than one swap type is active, so a process's VmSwap value cannot be attributed specifically to ZRAM."
        }
    )
}

@Composable
private fun AppSwapCard(app: AppSwapUsage, actionInProgress: Boolean, onClose: (AppSwapUsage) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(app.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            ValueRow("In ZRAM / swap", formatBytes(app.attributedSwapBytes))
            ValueRow("Still in RAM", formatBytes(app.residentBytes))
            if (app.processCount > 1) ValueRow("Processes", app.processCount.toString())
            if (app.isSystemApp) {
                Text("System app — protected", style = MaterialTheme.typography.bodySmall)
            } else {
                Button(onClick = { onClose(app) }, enabled = !actionInProgress, modifier = Modifier.fillMaxWidth()) {
                    Text("Close app & release ZRAM")
                }
            }
        }
    }
}

@Composable
private fun AdvancedSystemDetails(snapshot: MemorySnapshot) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Advanced system details", fontWeight = FontWeight.Bold)
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide details" else "Show details")
            }
            if (expanded) {
                snapshot.vmStats.swappiness?.let { ValueRow("Swappiness", it.toString()) }
                snapshot.pressure?.someAvg10?.let { ValueRow("Memory pressure, some", formatPsi(it)) }
                snapshot.pressure?.fullAvg10?.let { ValueRow("Memory pressure, full", formatPsi(it)) }
                if (snapshot.swapDevices.isNotEmpty()) {
                    Text("Active swap devices", fontWeight = FontWeight.SemiBold)
                    snapshot.swapDevices.forEach { SwapDeviceRows(it) }
                }
            }
        }
    }
}

@Composable
private fun SwapDeviceRows(device: SwapDevice) {
    Text(if (device.isZram) "Kernel ZRAM" else device.path.substringAfterLast('/'), fontWeight = FontWeight.SemiBold)
    ValueRow("Used", "${formatBytes(device.usedBytes)} / ${formatBytes(device.sizeBytes)}")
    device.priority?.let { ValueRow("Priority", it.toString()) }
}

@Composable
private fun CollapsibleInfoCard(title: String, body: String) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide explanation" else "Show explanation")
            }
            if (expanded) Text(body)
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(body)
        }
    }
}

@Composable
private fun ValueRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatBytes(bytes: Long): String {
    val safe = max(0L, bytes)
    val gib = safe / (1024.0 * 1024.0 * 1024.0)
    return if (gib >= 1.0) {
        String.format(Locale.US, "%.2f GiB", gib)
    } else {
        String.format(Locale.US, "%.0f MiB", safe / (1024.0 * 1024.0))
    }
}

private fun formatPercent(value: Double): String = String.format(Locale.US, "%.1f%%", value.coerceIn(0.0, 100.0))
private fun formatPsi(value: Double): String = String.format(Locale.US, "%.2f%%", value)
private fun formatCount(value: Long): String = String.format(Locale.US, "%,d", value)
