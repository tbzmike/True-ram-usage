package com.tbzmike.trueramusage.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

    MaterialTheme(colorScheme = colors) {
        Button(
            onClick = {
                open = true
                if (vm.rootState == RootState.GRANTED && !vm.actionInProgress) {
                    vm.aggressiveBootLikeReclaim()
                }
            },
            enabled = !vm.actionInProgress,
            modifier = modifier
        ) {
            Text(if (vm.aggressiveReclaimStage != null) "Reclaiming…" else "Aggressive reclaim")
        }

        if (open) {
            AlertDialog(
                onDismissRequest = { if (!vm.actionInProgress) open = false },
                title = { Text(if (vm.actionInProgress) "Aggressive reclaim running" else "Aggressive reclaim") },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 620.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "This mode intentionally force-stops every installed third-party app for the current Android user except True RAM Usage itself, runs repeated Android background kill passes, reclaims clean caches, compacts memory, then tries to empty all active swap/ZRAM when the fresh headroom check allows it.",
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Essential Android/kernel processes are not blindly SIGKILLed. Android's own kill-all path is used for remaining processes it considers background-killable so the phone can stay operational while the reclaim completes and verifies its result.",
                            style = MaterialTheme.typography.bodySmall
                        )

                        when {
                            vm.rootState != RootState.GRANTED -> {
                                Text(
                                    "Root access is required. Grant root in Settings, then press Aggressive reclaim again.",
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            vm.aggressiveReclaimStage != null -> {
                                CircularProgressIndicator()
                                Text(vm.aggressiveReclaimStage ?: "Working…", color = MaterialTheme.colorScheme.primary)
                            }
                            vm.aggressiveReclaimReport == null && vm.actionError == null -> {
                                Text("The reclaim action starts immediately when this button is pressed with root granted.")
                            }
                        }

                        vm.aggressiveReclaimReport?.let { report ->
                            Text("Verified result", fontWeight = FontWeight.Bold)
                            ReclaimValue("User packages targeted", report.userAppsTargeted.toString())
                            ReclaimValue("Rejected force-stop commands", report.failedForceStopCommands.toString())
                            ReclaimValue("Background kill-all passes confirmed", report.killAllPassesSucceeded.toString())
                            ReclaimValue("Restart cleanup sweep", if (report.appSweepRepeated) "Yes" else "No")
                            ReclaimValue(
                                "Mapped non-system apps still running",
                                report.remainingMappedUserApps?.toString() ?: "Verification unavailable"
                            )
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

                        vm.actionMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                        vm.actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { open = false },
                        enabled = !vm.actionInProgress
                    ) {
                        Text(if (vm.actionInProgress) "Working…" else "Close")
                    }
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
