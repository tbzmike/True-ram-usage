package com.tbzmike.trueramusage.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tbzmike.trueramusage.data.RootState
import java.util.Locale

@Composable
fun AggressiveReclaimOverlay(vm: MemoryViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    var open by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }

    MaterialTheme(colorScheme = colors) {
        Button(
            onClick = { open = true },
            enabled = !vm.actionInProgress,
            modifier = modifier
        ) {
            Text(if (vm.aggressiveReclaimStage != null) "Reclaiming…" else "Aggressive reclaim")
        }

        if (open) {
            AlertDialog(
                onDismissRequest = { if (!vm.actionInProgress) open = false },
                title = { Text("Aggressive boot-like reclaim") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "This is intended for unusually overused RAM when you would otherwise reboot. It cannot make RAM literally identical to a reboot because Android and the kernel keep required services and memory resident.",
                            fontWeight = FontWeight.SemiBold
                        )
                        Text("The operation performs a fresh process scan, force-stops current non-system user apps, kills remaining background processes, syncs storage, drops clean page/file caches, requests kernel memory compaction, then tries to disable all active swap/ZRAM at the same time and re-enable every device with its recorded priority.")
                        Text("Before swapoff, True RAM Usage re-reads MemAvailable and current swap usage. If there is not enough physical headroom for every swapped page plus the existing safety reserve, the swap-clear phase is blocked instead of risking an OOM or forced reboot.")
                        Text("Apps may reopen more slowly, background notifications/services from force-stopped user apps may remain stopped until those apps are launched again, and cache rebuilding can cause temporary I/O/CPU activity.", style = MaterialTheme.typography.bodySmall)

                        when {
                            vm.rootState != RootState.GRANTED -> Text("Root access must be granted from the main screen before this operation can run.", color = MaterialTheme.colorScheme.error)
                            vm.aggressiveReclaimStage != null -> {
                                CircularProgressIndicator()
                                Text(vm.aggressiveReclaimStage ?: "Working…", color = MaterialTheme.colorScheme.primary)
                            }
                            else -> Button(
                                onClick = { confirm = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Reclaim physical RAM + clear all swap/ZRAM")
                            }
                        }

                        vm.aggressiveReclaimReport?.let { report ->
                            Text("Verified result", fontWeight = FontWeight.Bold)
                            ReclaimValue("Used RAM before", formatReclaimBytes(report.beforeUsedRamBytes))
                            ReclaimValue("Used RAM after", formatReclaimBytes(report.afterUsedRamBytes))
                            ReclaimValue("Available RAM before", formatReclaimBytes(report.beforeAvailableRamBytes))
                            ReclaimValue("Available RAM after", formatReclaimBytes(report.afterAvailableRamBytes))
                            ReclaimValue("Available RAM gained", formatReclaimBytes(report.gainedAvailableRamBytes))
                            ReclaimValue("Swap/ZRAM before", formatReclaimBytes(report.beforeSwapUsedBytes))
                            ReclaimValue("Swap/ZRAM after", formatReclaimBytes(report.afterSwapUsedBytes))
                            ReclaimValue("Swap passes", report.swapPasses.toString())
                            ReclaimValue("Swap verified empty", if (report.swapIsEmpty) "Yes" else "No")
                            ReclaimValue("All swap disabled together", if (report.allSwapWasDisabledAtOnce) "Yes" else "No / not needed")
                            ReclaimValue("Swappiness restored", if (report.swappinessRestored) "Yes" else "No — reboot or manual restore advised")
                            if (report.swapClearBlockedBytes > 0L) {
                                ReclaimValue("Additional headroom required", formatReclaimBytes(report.swapClearBlockedBytes))
                            }
                            Text(report.userAppCloseMessage, style = MaterialTheme.typography.bodySmall)
                            Text(report.kernelReclaimMessage, style = MaterialTheme.typography.bodySmall)
                            Text(report.swapMessage, style = MaterialTheme.typography.bodySmall)
                        }

                        vm.actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { if (!vm.actionInProgress) open = false }) {
                        Text("Close")
                    }
                }
            )
        }

        if (confirm) {
            AlertDialog(
                onDismissRequest = { confirm = false },
                title = { Text("Run aggressive reclaim now?") },
                text = {
                    Text("This will force-stop current non-system user apps and may temporarily disable every active swap/ZRAM device. True RAM Usage itself and mapped system apps are protected from the force-stop list. The swap phase runs only if the fresh safety check has enough physical RAM headroom.")
                },
                confirmButton = {
                    Button(onClick = {
                        confirm = false
                        open = true
                        vm.aggressiveBootLikeReclaim()
                    }) {
                        Text("Run aggressive reclaim")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirm = false }) { Text("Cancel") }
                }
            )
        }
    }
}

@Composable
private fun ReclaimValue(label: String, value: String) {
    Text("$label: $value", style = MaterialTheme.typography.bodySmall)
}

private fun formatReclaimBytes(bytes: Long): String {
    val unit = 1024.0
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / unit
    if (kb < unit) return String.format(Locale.US, "%.1f KiB", kb)
    val mb = kb / unit
    if (mb < unit) return String.format(Locale.US, "%.1f MiB", mb)
    return String.format(Locale.US, "%.2f GiB", mb / unit)
}
