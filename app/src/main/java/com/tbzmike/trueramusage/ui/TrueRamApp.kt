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
                Text("Live memory information translated into plain language.")

                if (snapshot == null) {
                    Text(viewModel.errorMessage ?: "Reading memory information…")
                    Button(onClick = viewModel::refreshNow) { Text("Refresh") }
                    return@Column
                }

                MemoryCard(
                    title = "Physical RAM",
                    used = snapshot.usedRamBytes,
                    total = snapshot.totalRamBytes,
                    description = "Memory currently occupied by Android, apps, caches and the kernel."
                )

                MemoryCard(
                    title = "Swap / ZRAM",
                    used = snapshot.usedSwapBytes,
                    total = snapshot.totalSwapBytes,
                    description = "Memory pages moved out of normal RAM into active swap space."
                )

                if (snapshot.zramDevices.isEmpty()) {
                    InfoCard("ZRAM", "No readable ZRAM device was detected on this device.")
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
private fun MemoryCard(title: String, used: Long, total: Long, description: String) {
    val percent = if (total > 0) (used.toDouble() / total * 100.0).coerceIn(0.0, 100.0) else 0.0
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${formatBytes(used)} of ${formatBytes(total)} used", style = MaterialTheme.typography.headlineSmall)
            Text("${formatPercent(percent)} used")
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ZramCard(device: ZramDevice) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ZRAM • ${device.name}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            ValueRow("Capacity", formatBytes(device.diskSizeBytes))
            ValueRow("Data stored", formatBytes(device.originalDataBytes))
            ValueRow("Physical RAM used", formatBytes(device.memoryUsedBytes))
            ValueRow("RAM saved by compression", formatBytes(device.savedBytes))
            ValueRow("Compressed data size", formatBytes(device.compressedDataBytes))
            device.compressionRatio?.let { ValueRow("Compression efficiency", String.format(Locale.US, "%.2f×", it)) }
            device.compressionAlgorithm?.let { ValueRow("Compression method", it) }
            if (device.peakMemoryUsedBytes > 0) ValueRow("Peak physical RAM used", formatBytes(device.peakMemoryUsedBytes))
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
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(body)
        }
    }
}

@Composable
private fun ValueRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatBytes(bytes: Long): String {
    val safe = max(0L, bytes)
    val gib = safe / (1024.0 * 1024.0 * 1024.0)
    return if (gib >= 1.0) {
        String.format(Locale.US, "%.2f GB", gib)
    } else {
        String.format(Locale.US, "%.0f MB", safe / (1024.0 * 1024.0))
    }
}

private fun formatPercent(value: Double): String = String.format(Locale.US, "%.0f%%", value)
