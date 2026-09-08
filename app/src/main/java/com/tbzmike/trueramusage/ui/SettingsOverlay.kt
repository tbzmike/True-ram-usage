package com.tbzmike.trueramusage.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tbzmike.trueramusage.data.AppPreferences
import com.tbzmike.trueramusage.data.DisplayMode
import com.tbzmike.trueramusage.data.RootState
import com.tbzmike.trueramusage.data.ThemeMode

@Composable
fun SettingsOverlay(
    memoryVm: MemoryViewModel,
    updateVm: UpdateViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark = when (memoryVm.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
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
            val suffix = when {
                updateVm.stagedUpdate != null -> " • install ready"
                updateVm.updateAvailable -> " • update"
                else -> ""
            }
            Text("Settings$suffix")
        }

        if (open) {
            AlertDialog(
                onDismissRequest = { open = false },
                title = { Text("Settings") },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 650.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SettingsSectionTitle("Appearance")
                        Text("Display detail", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = memoryVm.displayMode == DisplayMode.SIMPLE,
                                onClick = { memoryVm.setDisplayMode(DisplayMode.SIMPLE) },
                                label = { Text("Simple") }
                            )
                            FilterChip(
                                selected = memoryVm.displayMode == DisplayMode.DETAILED,
                                onClick = { memoryVm.setDisplayMode(DisplayMode.DETAILED) },
                                label = { Text("Detailed") }
                            )
                        }

                        Text("Theme", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ThemeMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = memoryVm.themeMode == mode,
                                    onClick = { memoryVm.setThemeMode(mode) },
                                    label = { Text(themeLabel(mode)) }
                                )
                            }
                        }

                        HorizontalDivider()
                        SettingsSectionTitle("Memory monitoring")
                        SettingToggle(
                            title = "Automatic RAM refresh",
                            description = "Refresh /proc memory and ZRAM readings while True RAM Usage is on screen.",
                            checked = memoryVm.automaticMemoryRefreshEnabled,
                            onCheckedChange = memoryVm::setAutomaticMemoryRefresh
                        )

                        Text("Refresh interval", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AppPreferences.SUPPORTED_REFRESH_INTERVALS_MS.forEach { interval ->
                                FilterChip(
                                    selected = memoryVm.memoryRefreshIntervalMs == interval,
                                    onClick = { memoryVm.setMemoryRefreshInterval(interval) },
                                    enabled = memoryVm.automaticMemoryRefreshEnabled,
                                    label = { Text(refreshIntervalLabel(interval)) }
                                )
                            }
                        }
                        Text(
                            "Faster refresh gives more current graphs but makes the monitor itself wake more often. Expensive PSS/SwapPss process scans remain manual.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(onClick = memoryVm::refreshNow, modifier = Modifier.fillMaxWidth()) {
                            Text("Refresh RAM now")
                        }
                        Button(
                            onClick = memoryVm::refreshAppsNow,
                            enabled = memoryVm.rootState == RootState.GRANTED && !memoryVm.appsScanInProgress,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (memoryVm.appsScanInProgress) "Scanning apps…" else "Refresh app/process memory now")
                        }

                        HorizontalDivider()
                        SettingsSectionTitle("Root access")
                        Text(rootStatus(memoryVm.rootState), style = MaterialTheme.typography.bodySmall)
                        if (memoryVm.rootState != RootState.GRANTED) {
                            Button(
                                onClick = memoryVm::requestRoot,
                                enabled = !memoryVm.rootRequestInProgress,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (memoryVm.rootRequestInProgress) "Requesting root…" else "Grant / retry root access")
                            }
                        }

                        HorizontalDivider()
                        SettingsSectionTitle("App updates")
                        Text(
                            "Installed: ${updateVm.currentVersionName} (${updateVm.currentVersionCode})",
                            fontWeight = FontWeight.SemiBold
                        )
                        updateVm.latestRelease?.let { release ->
                            Text("Latest green: ${release.versionName} • CI build #${release.runNumber}")
                        }
                        updateVm.stagedUpdate?.let { staged ->
                            Text(
                                "Verified update ready: ${staged.versionName}",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        SettingToggle(
                            title = "Automatic green-build checks",
                            description = "Check GitHub periodically and download a newer passed main build after signature/hash verification.",
                            checked = updateVm.automaticUpdatesEnabled,
                            onCheckedChange = updateVm::setAutomaticUpdates
                        )
                        SettingToggle(
                            title = "Install verified updates automatically",
                            description = "When automatic checks find a new verified build, install it unattended only when root was previously granted. Turn this off to stage updates for manual installation instead.",
                            checked = updateVm.automaticInstallEnabled,
                            onCheckedChange = updateVm::setAutomaticInstall
                        )

                        updateVm.message?.let {
                            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        Button(
                            onClick = updateVm::checkNow,
                            enabled = !updateVm.checking && !updateVm.downloading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (updateVm.checking) "Checking…" else "Check for updates now")
                        }

                        if (updateVm.stagedUpdate != null) {
                            Button(
                                onClick = updateVm::installStaged,
                                enabled = !updateVm.checking && !updateVm.downloading,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Install verified downloaded update")
                            }
                        } else if (updateVm.updateAvailable) {
                            Button(
                                onClick = updateVm::downloadAndInstallLatest,
                                enabled = !updateVm.checking && !updateVm.downloading,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (updateVm.downloading) "Downloading…" else "Download & install latest green build")
                            }
                        }

                        TextButton(onClick = updateVm::openLatestRelease, modifier = Modifier.fillMaxWidth()) {
                            Text("Open latest GitHub release")
                        }
                        TextButton(onClick = updateVm::openLatestApkDownload, modifier = Modifier.fillMaxWidth()) {
                            Text("Download latest APK in browser")
                        }

                        HorizontalDivider()
                        SettingsSectionTitle("About update safety")
                        Text(
                            "The updater reads GitHub's stable latest-release manifest and APK links first, with the GitHub Releases API only as a fallback. Before installation, True RAM Usage verifies the manifest SHA-256, package name, versionCode, and signing certificate against the currently installed app.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        open = false
                        updateVm.clearMessage()
                    }) {
                        Text("Close")
                    }
                }
            )
        }
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun SettingToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

private fun refreshIntervalLabel(intervalMs: Long): String = when (intervalMs) {
    1_000L -> "1s"
    2_000L -> "2s"
    5_000L -> "5s"
    10_000L -> "10s"
    else -> "${intervalMs / 1_000L}s"
}

private fun rootStatus(state: RootState): String = when (state) {
    RootState.NOT_REQUESTED -> "Root has not been requested in this app session. Root enables PSS/SwapPss, recovery actions and unattended verified updates."
    RootState.GRANTED -> "Root granted. Root-assisted process diagnostics, recovery actions and eligible unattended updates are available."
    RootState.DENIED_OR_TIMED_OUT -> "Root was denied or timed out."
    RootState.UNAVAILABLE -> "No compatible root command was found."
}
