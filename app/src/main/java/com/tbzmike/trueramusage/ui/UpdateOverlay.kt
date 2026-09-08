package com.tbzmike.trueramusage.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
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

@Composable
fun UpdateOverlay(vm: UpdateViewModel, modifier: Modifier = Modifier) {
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
        ExtendedFloatingActionButton(
            onClick = { open = true },
            modifier = modifier
        ) {
            val staged = vm.stagedUpdate
            Text(if (staged != null) "Install ${staged.versionName}" else if (vm.updateAvailable) "Update available" else "Updates")
        }

        if (open) {
            AlertDialog(
                onDismissRequest = { open = false },
                title = { Text("App updates") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Installed: ${vm.currentVersionName} (${vm.currentVersionCode})", fontWeight = FontWeight.SemiBold)
                        vm.latestRelease?.let { release ->
                            Text("Latest green: ${release.versionName} • CI build #${release.runNumber}")
                        }
                        vm.stagedUpdate?.let { staged ->
                            Text("Verified download ready: ${staged.versionName}", color = MaterialTheme.colorScheme.primary)
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = vm.automaticUpdatesEnabled,
                                onClick = { vm.setAutomaticUpdates(!vm.automaticUpdatesEnabled) },
                                label = { Text(if (vm.automaticUpdatesEnabled) "Automatic updates on" else "Automatic updates off") }
                            )
                        }
                        Text(
                            "Automatic checks use GitHub's latest passed main build. If root was granted before, a verified newer APK can install unattended; otherwise it stays staged for manual installation.",
                            style = MaterialTheme.typography.bodySmall
                        )

                        vm.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }

                        Button(
                            onClick = vm::checkNow,
                            enabled = !vm.checking && !vm.downloading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (vm.checking) "Checking…" else "Check for updates")
                        }

                        if (vm.stagedUpdate != null) {
                            Button(
                                onClick = vm::installStaged,
                                enabled = !vm.checking && !vm.downloading,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Install downloaded update")
                            }
                        } else if (vm.updateAvailable) {
                            Button(
                                onClick = vm::downloadAndInstallLatest,
                                enabled = !vm.checking && !vm.downloading,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (vm.downloading) "Downloading…" else "Download & install latest green build")
                            }
                        }

                        if (vm.latestRelease != null || vm.stagedUpdate?.releaseHtmlUrl?.isNotBlank() == true) {
                            TextButton(onClick = vm::openLatestRelease, modifier = Modifier.fillMaxWidth()) {
                                Text("Open GitHub release page")
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { open = false; vm.clearMessage() }, modifier = Modifier.padding(horizontal = 4.dp)) {
                        Text("Close")
                    }
                }
            )
        }
    }
}
