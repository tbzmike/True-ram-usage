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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tbzmike.trueramusage.data.RootState
import com.tbzmike.trueramusage.data.SwapDevice
import com.tbzmike.trueramusage.data.ZramDevice
import java.util.Locale
import kotlin.math.max

@Composable
fun TrueRamApp(viewModel: MemoryViewModel = viewModel()) {
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
                Text("Accurate memory information translated into plain language.")

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
                    description = "Used is calculated from the kernel's MemTotal minus MemAvailable. MemAvailable is the kernel's estimate of memory that can be given to apps without swapping."
                )

                SwapSummaryCard(
                    used = snapshot.usedSwapBytes,
                    total = snapshot.totalSwapBytes,
                    deviceCount = snapshot.swapDevices.size
                )

                snapshot.vmStats.swappiness?.let { swappiness ->
                    InfoCard(
                        "Swap preference",
                        "Swappiness is $swappiness. Higher values make the kernel more willing to move inactive memory to swap."
                    )
                }

                snapshot.pressure?.let { pressure ->
                    val some = pressure.someAvg10
                    val full = pressure.fullAvg10
                    InfoCard(
                        "Memory pressure",
                        buildString {
                            append("Last 10 seconds: ")
                            append(if (some != null) "some tasks stalled ${formatPsi(some)} of the time" else "partial pressure unavailable")
                            if (full != null) append(", while all non-idle tasks stalled ${formatPsi(full)} of the time.") else append(".")
                        }
                    )
                }

                if (snapshot.zramDevices.isEmpty()) {
                    val text = if (viewModel.rootState == RootState.GRANTED) {
                        "Root is granted, but no readable ZRAM device was found. The kernel may use a different swap implementation or expose ZRAM differently."
                    } else {
                        "Detailed ZRAM statistics are protected on this device. Grant root access above so True RAM Usage can read the kernel's ZRAM statistics directly."
                    }
                    InfoCard("ZRAM details", text)
                } else {
                    snapshot.zramDevices.forEach { ZramCard(it) }
                }

                if (snapshot.swapDevices.isNotEmpty()) {
                    Text("Active swap devices", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    snapshot.swapDevices.forEach { SwapCard(it) }
                }

                viewModel.errorMessage?.let { InfoCard("Read warning", it) }
                Button(onClick = viewModel::refreshNow, modifier = Modifier.fillMaxWidth()) { Text("Refresh now") }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun RootCard(state: RootState, inProgress: Boolean, onRequestRoot: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Access level", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            when (state) {
                RootState.GRANTED -> {
                    Text("Root access granted")
                    Text("Full kernel-level ZRAM and protected memory statistics are enabled.")
                }
                RootState.DENIED_OR_TIMED_OUT -> {
                    Text("Root access was not granted")
                    Text("You can retry. Your root manager should show a permission request.")
                    Button(onClick = onRequestRoot, enabled = !inProgress) { Text(if (inProgress) "Requesting…" else "Retry root access") }
                }
                RootState.UNAVAILABLE -> Text("No compatible root command was found on this device.")
                RootState.NOT_REQUESTED -> {
                    Text("Standard access")
                    Text("Basic RAM and swap totals work now. Root is needed for protected ZRAM details and, later, per-app swap attribution.")
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
private fun SwapSummaryCard(used: Long, total: Long, deviceCount: Int) {
    val percent = if (total > 0) (used.toDouble() / total * 100.0).coerceIn(0.0, 100.0) else 0.0
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Swap", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${formatBytes(used)} of ${formatBytes(total)} used", style = MaterialTheme.typography.headlineSmall)
            ValueRow("Used", formatPercent(percent))
            ValueRow("Active swap devices", deviceCount.toString())
            Text("This is the kernel's active swap total. ZRAM is shown separately below when detailed statistics are readable.")
        }
    }
}

@Composable
private fun ZramCard(device: ZramDevice) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ZRAM • ${device.name}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            ValueRow("Configured capacity", formatBytes(device.diskSizeBytes))
            ValueRow("Uncompressed data stored", formatBytes(device.originalDataBytes))
            ValueRow("Compressed payload", formatBytes(device.compressedDataBytes))
            ValueRow("Actual RAM allocated", formatBytes(device.memoryUsedBytes))
            ValueRow("Effective RAM saved", formatBytes(device.ramSavedBytes))
            device.compressionRatio?.let { ValueRow("Compression ratio", String.format(Locale.US, "%.2f×", it)) }
            device.effectiveRamRatio?.let { ValueRow("Effective RAM ratio", String.format(Locale.US, "%.2f×", it)) }
            device.compressionAlgorithm?.let { ValueRow("Compression algorithm", it) }
            if (device.peakMemoryUsedBytes > 0) ValueRow("Peak RAM allocated", formatBytes(device.peakMemoryUsedBytes))
            if (device.memoryLimitBytes > 0) ValueRow("ZRAM memory limit", formatBytes(device.memoryLimitBytes))
            if (device.samePages > 0) ValueRow("Identical pages optimized", formatCount(device.samePages))
            if (device.compactedPages > 0) ValueRow("Pages freed by compaction", formatCount(device.compactedPages))
            if (device.hugePages > 0) ValueRow("Poorly compressible huge pages", formatCount(device.hugePages))
            Text("Compression ratio uses uncompressed data ÷ compressed payload. Effective RAM ratio includes ZRAM allocator overhead, so the two values can differ.")
        }
    }
}

@Composable
private fun SwapCard(device: SwapDevice) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(device.path.substringAfterLast('/'), fontWeight = FontWeight.Bold)
            ValueRow("Used", "${formatBytes(device.usedBytes)} of ${formatBytes(device.sizeBytes)}")
            ValueRow("Type", device.type)
            device.priority?.let { ValueRow("Priority", it.toString()) }
            Text(device.path, style = MaterialTheme.typography.bodySmall)
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

private fun formatPercent(value: Double): String = String.format(Locale.US, "%.1f%%", value)
private fun formatPsi(value: Double): String = String.format(Locale.US, "%.2f%%", value)
private fun formatCount(value: Long): String = String.format(Locale.US, "%,d", value)
