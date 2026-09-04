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
                Text("Accurate RAM and kernel ZRAM information in plain language.")

                RootCard(
                    state = viewModel.rootState,
                    inProgress = viewModel.rootRequestInProgress,
                    onRequestRoot = viewModel::requestRoot
                )

                if (snapshot == null) {
                    Text(viewModel.errorMessage ?: "Reading memory information…")
                    Button(onClick = viewModel::refreshNow) { Text("Refresh") }
                    return@Column
                }

                DetailedMemoryCard(
                    title = "Physical RAM",
                    used = snapshot.usedRamBytes,
                    total = snapshot.totalRamBytes,
                    available = snapshot.availableRamBytes,
                    description = "Used is calculated from MemTotal minus MemAvailable, so reclaimable caches are not incorrectly treated as permanently occupied RAM."
                )

                SwapSummaryCard(
                    used = snapshot.usedSwapBytes,
                    total = snapshot.totalSwapBytes,
                    deviceCount = snapshot.swapDevices.size,
                    onlyKernelZram = snapshot.swapDevices.isNotEmpty() && snapshot.swapDevices.all { it.isZram }
                )

                snapshot.vmStats.swappiness?.let { swappiness ->
                    InfoCard("Swap preference", "Swappiness is $swappiness. Higher values make the kernel more willing to move inactive pages into ZRAM/swap.")
                }

                snapshot.pressure?.let { pressure ->
                    InfoCard(
                        "Memory pressure",
                        "Over the last 10 seconds, some tasks were stalled ${pressure.someAvg10?.let(::formatPsi) ?: "an unavailable amount"} of the time" +
                            (pressure.fullAvg10?.let { ", and all non-idle tasks were stalled ${formatPsi(it)}." } ?: ".")
                    )
                }

                if (snapshot.zramDevices.isEmpty()) {
                    val text = if (viewModel.rootState == RootState.GRANTED) {
                        "Root is granted, but this kernel did not expose a readable ZRAM statistics device."
                    } else {
                        "Grant root access above so True RAM Usage can read protected kernel ZRAM statistics."
                    }
                    InfoCard("Kernel ZRAM", text)
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

                if (snapshot.swapDevices.isNotEmpty()) {
                    Text("Active memory swap", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    snapshot.swapDevices.forEach { SwapCard(it) }
                }

                if (viewModel.rootState == RootState.GRANTED) {
                    val onlyKernelZram = snapshot.swapDevices.isNotEmpty() && snapshot.swapDevices.all { it.isZram }
                    AppsInZramSection(
                        apps = viewModel.appsInZram,
                        scanning = viewModel.appsScanInProgress,
                        actionInProgress = viewModel.actionInProgress,
                        onlyKernelZram = onlyKernelZram,
                        onRefresh = viewModel::refreshAppsNow,
                        onClose = { appToClose = it }
                    )
                }

                viewModel.actionMessage?.let { InfoCard("Completed", it) }
                viewModel.actionError?.let { InfoCard("Action warning", it) }
                viewModel.errorMessage?.let { InfoCard("Read warning", it) }

                Button(onClick = viewModel::refreshNow, modifier = Modifier.fillMaxWidth()) { Text("Refresh all readings") }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    appToClose?.let { app ->
        AlertDialog(
            onDismissRequest = { appToClose = null },
            title = { Text("Remove ${app.label} from ZRAM?") },
            text = {
                Text("This will force-stop the app. Its processes will end and their ${formatBytes(app.attributedSwapBytes)} of attributed swapped pages should be released from kernel ZRAM. The app can be opened again normally.")
            },
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
            text = {
                Text("True RAM Usage will temporarily swap off the active ZRAM device so its pages return to physical RAM, then immediately re-enable the same device. It will not reset, resize, or recreate your 6 GB kernel ZRAM. Android may start filling ZRAM again straight away.")
            },
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
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Access level", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            when (state) {
                RootState.GRANTED -> {
                    Text("Root access granted", fontWeight = FontWeight.SemiBold)
                    Text("Protected ZRAM statistics, per-app swap attribution and safe memory actions are enabled.")
                }
                RootState.DENIED_OR_TIMED_OUT -> {
                    Text("Root access was not granted")
                    Button(onClick = onRequestRoot, enabled = !inProgress) { Text(if (inProgress) "Requesting…" else "Retry root access") }
                }
                RootState.UNAVAILABLE -> Text("No compatible su command was found on this device.")
                RootState.NOT_REQUESTED -> {
                    Text("Standard access")
                    Text("Basic totals work without root. Root is required for per-app ZRAM usage and ZRAM controls.")
                    Button(onClick = onRequestRoot, enabled = !inProgress) { Text(if (inProgress) "Requesting root…" else "Grant root access") }
                }
            }
        }
    }
}

@Composable
private fun DetailedMemoryCard(title: String, used: Long, total: Long, available: Long, description: String) {
    val percent = if (total > 0) (used.toDouble() / total * 100.0).coerceIn(0.0, 100.0) else 0.0
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${formatBytes(used)} of ${formatBytes(total)} used", style = MaterialTheme.typography.headlineSmall)
            ValueRow("Used", formatPercent(percent))
            ValueRow("Available now", formatBytes(available))
            ValueRow("Kernel-visible total", formatBytes(total))
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SwapSummaryCard(used: Long, total: Long, deviceCount: Int, onlyKernelZram: Boolean) {
    val percent = if (total > 0) (used.toDouble() / total * 100.0).coerceIn(0.0, 100.0) else 0.0
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(if (onlyKernelZram) "Kernel ZRAM usage" else "Swap usage", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${formatBytes(used)} of ${formatBytes(total)} used", style = MaterialTheme.typography.headlineSmall)
            ValueRow("Used", formatPercent(percent))
            ValueRow("Active devices", deviceCount.toString())
            Text(if (onlyKernelZram) "All active swap reported by the kernel is ZRAM; no separate swap file is active." else "The kernel currently has more than one swap type or a non-ZRAM swap device active.")
        }
    }
}

@Composable
private fun ZramCard(device: ZramDevice) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Kernel ZRAM", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            ValueRow("Configured capacity", formatBytes(device.diskSizeBytes))
            ValueRow("Uncompressed data stored", formatBytes(device.originalDataBytes))
            ValueRow("Compressed payload", formatBytes(device.compressedDataBytes))
            ValueRow("Actual physical RAM used", formatBytes(device.memoryUsedBytes))
            ValueRow("Effective RAM saved", formatBytes(device.ramSavedBytes))
            device.compressionRatio?.let { ValueRow("Compression ratio", String.format(Locale.US, "%.2f×", it)) }
            device.effectiveRamRatio?.let { ValueRow("Effective RAM ratio", String.format(Locale.US, "%.2f×", it)) }
            device.compressionAlgorithm?.let { ValueRow("Compression method", it) }
            if (device.peakMemoryUsedBytes > 0) ValueRow("Peak physical RAM used", formatBytes(device.peakMemoryUsedBytes))
            if (device.memoryLimitBytes > 0) ValueRow("Physical RAM limit", formatBytes(device.memoryLimitBytes))
            if (device.samePages > 0) ValueRow("Identical pages optimized", formatCount(device.samePages))
            if (device.compactedPages > 0) ValueRow("Pages freed by compaction", formatCount(device.compactedPages))
            if (device.hugePages > 0) ValueRow("Poorly compressible huge pages", formatCount(device.hugePages))
            Text("Compression ratio measures the compressed payload. Effective RAM ratio includes allocator overhead, so it can be lower.")
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
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Kernel ZRAM controls", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            ValueRow("Currently swapped", formatBytes(usedBytes))
            when {
                safety == null -> Text("Checking whether ZRAM can be cleared safely…")
                safety.canClear -> Text("Enough physical RAM is currently available to perform a guarded clear.")
                safety.additionalNeededBytes > 0 -> Text("Clear is blocked for safety. About ${formatBytes(safety.additionalNeededBytes)} more physical RAM must be available before all current ZRAM pages can be brought back into RAM with a safety reserve.")
                else -> Text("Clear is unavailable because no active kernel ZRAM swap device was detected.")
            }
            Button(
                onClick = onClear,
                enabled = safety?.canClear == true && !actionInProgress,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (actionInProgress) "Memory action running…" else "Clear kernel ZRAM") }
            Text("This does not delete or recreate the 6 GB ZRAM device. It cycles swap off/on only when the pre-check says physical RAM is sufficient.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AppsInZramSection(
    apps: List<AppSwapUsage>,
    scanning: Boolean,
    actionInProgress: Boolean,
    onlyKernelZram: Boolean,
    onRefresh: () -> Unit,
    onClose: (AppSwapUsage) -> Unit
) {
    Text(if (onlyKernelZram) "Apps currently in kernel ZRAM" else "Apps currently using swap", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    InfoCard(
        "How this is measured",
        if (onlyKernelZram) {
            "The kernel reports only ZRAM as active swap. True RAM Usage reads each process's swapped pages and uses SwapPss when available so shared swapped pages are attributed proportionally."
        } else {
            "Per-process swapped pages are shown here, but more than one swap type is active so they cannot all be attributed specifically to ZRAM."
        }
    )
    if (scanning && apps.isEmpty()) Text("Scanning running processes…")
    if (!scanning && apps.isEmpty()) InfoCard("No app pages found", "No installed app currently has readable swapped pages, or this kernel does not expose per-process swap accounting to the root domain.")
    if (apps.isNotEmpty()) {
        val attributed = apps.sumOf { it.attributedSwapBytes }
        InfoCard("App-attributed swapped memory", "${formatBytes(attributed)} is currently attributable to installed apps. Kernel/system pages and pages that cannot be mapped to an installed package are not included in this figure.")
        apps.forEach { app ->
            AppSwapCard(app, actionInProgress, onClose)
        }
    }
    Button(onClick = onRefresh, enabled = !scanning && !actionInProgress, modifier = Modifier.fillMaxWidth()) {
        Text(if (scanning) "Scanning apps…" else "Refresh app list")
    }
}

@Composable
private fun AppSwapCard(app: AppSwapUsage, actionInProgress: Boolean, onClose: (AppSwapUsage) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(app.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            ValueRow("In ZRAM / swap", formatBytes(app.attributedSwapBytes))
            val resident = if (app.pssBytes > 0) app.pssBytes else app.residentBytes
            ValueRow("Still resident in RAM", formatBytes(resident))
            ValueRow("Running processes", app.processCount.toString())
            if (app.isSystemApp) {
                Text("System app — protected from force-stop inside True RAM Usage.", style = MaterialTheme.typography.bodySmall)
            } else {
                Button(onClick = { onClose(app) }, enabled = !actionInProgress, modifier = Modifier.fillMaxWidth()) {
                    Text("Close app & release ZRAM")
                }
            }
        }
    }
}

@Composable
private fun SwapCard(device: SwapDevice) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (device.isZram) "Kernel ZRAM" else "Swap device", fontWeight = FontWeight.Bold)
            ValueRow("Used", "${formatBytes(device.usedBytes)} of ${formatBytes(device.sizeBytes)}")
            ValueRow("Kind", if (device.isZram) "Compressed RAM swap" else device.type)
            device.priority?.let { ValueRow("Kernel priority", it.toString()) }
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
    return if (gib >= 1.0) String.format(Locale.US, "%.2f GiB", gib)
    else String.format(Locale.US, "%.0f MiB", safe / (1024.0 * 1024.0))
}

private fun formatPercent(value: Double): String = String.format(Locale.US, "%.1f%%", value)
private fun formatPsi(value: Double): String = String.format(Locale.US, "%.2f%%", value)
private fun formatCount(value: Long): String = String.format(Locale.US, "%,d", value)
