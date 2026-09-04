package com.tbzmike.trueramusage.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.verticalScroll
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tbzmike.trueramusage.data.AppSwapUsage
import com.tbzmike.trueramusage.data.DisplayMode
import com.tbzmike.trueramusage.data.MemorySnapshot
import com.tbzmike.trueramusage.data.RootState
import com.tbzmike.trueramusage.data.RunningAppUsage
import com.tbzmike.trueramusage.data.SwapDevice
import com.tbzmike.trueramusage.data.ThemeMode
import com.tbzmike.trueramusage.data.ZramClearSafety
import com.tbzmike.trueramusage.data.ZramDevice
import java.util.Locale
import kotlin.math.max

@Composable
fun TrueRamApp(viewModel: MemoryViewModel = viewModel()) {
    TrueRamTheme(viewModel.themeMode) {
        TrueRamScreen(viewModel)
    }
}

@Composable
private fun TrueRamTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }

    val view = LocalView.current
    SideEffect {
        val activity = view.context as? Activity ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(activity.window, view)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }

    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun TrueRamScreen(viewModel: MemoryViewModel) {
    var appToClose by remember { mutableStateOf<AppSwapUsage?>(null) }
    var confirmClearZram by remember { mutableStateOf(false) }
    var confirmCloseAll by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize()) {
        val snapshot = viewModel.snapshot
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("True RAM Usage", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Real memory information in plain language.", color = MaterialTheme.colorScheme.onSurfaceVariant)

            ViewAndAppearanceSection(
                displayMode = viewModel.displayMode,
                themeMode = viewModel.themeMode,
                onDisplayMode = viewModel::setDisplayMode,
                onThemeMode = viewModel::setThemeMode
            )

            RootSection(viewModel.rootState, viewModel.rootRequestInProgress, viewModel::requestRoot)

            if (snapshot == null) {
                InfoCard("Reading memory", viewModel.errorMessage ?: "Reading memory information…")
                Button(onClick = viewModel::refreshNow, modifier = Modifier.fillMaxWidth()) { Text("Refresh") }
                return@Column
            }

            OverviewCharts(snapshot)
            PhysicalRamSection(snapshot, viewModel.displayMode)
            ZramUsageSection(snapshot, viewModel.displayMode)

            if (snapshot.zramDevices.isEmpty()) {
                CollapsibleSection(
                    title = "Kernel ZRAM",
                    summary = if (viewModel.rootState == RootState.GRANTED) "Detailed ZRAM statistics unavailable" else "Root required",
                    initiallyExpanded = false
                ) {
                    Text(
                        if (viewModel.rootState == RootState.GRANTED) {
                            "Root is granted, but this kernel did not expose readable ZRAM statistics."
                        } else {
                            "Grant root access to read detailed kernel ZRAM statistics."
                        }
                    )
                }
            } else {
                snapshot.zramDevices.forEach { ZramDetailsSection(it, viewModel.displayMode) }
            }

            if (viewModel.rootState == RootState.GRANTED && snapshot.activeZramSwapDevices.isNotEmpty()) {
                ZramControlsSection(
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
                    onlyKernelZram = snapshot.onlyKernelZramActive,
                    displayMode = viewModel.displayMode,
                    onRefresh = viewModel::refreshAppsNow,
                    onClose = { appToClose = it },
                    onCloseAll = { confirmCloseAll = true }
                )

                RunningAppsSection(
                    apps = viewModel.runningApps,
                    scanning = viewModel.appsScanInProgress,
                    scanError = viewModel.appsScanError,
                    onlyKernelZram = snapshot.onlyKernelZramActive,
                    displayMode = viewModel.displayMode,
                    onRefresh = viewModel::refreshAppsNow
                )
            }

            if (viewModel.displayMode == DisplayMode.DETAILED) {
                AdvancedSystemDetails(snapshot)
            }

            viewModel.actionMessage?.let { InfoCard("Completed", it) }
            viewModel.actionError?.let { InfoCard("Action warning", it) }
            viewModel.errorMessage?.let { InfoCard("Read warning", it) }

            Button(onClick = viewModel::refreshNow, modifier = Modifier.fillMaxWidth()) {
                Text("Refresh memory readings")
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    appToClose?.let { app ->
        AlertDialog(
            onDismissRequest = { appToClose = null },
            title = { Text("Close ${app.label}?") },
            text = { Text("This force-stops the app so its ${formatBytes(app.attributedSwapBytes)} of private swapped memory can be released. The app can be opened again normally.") },
            confirmButton = {
                Button(onClick = {
                    appToClose = null
                    viewModel.closeAndRelease(app)
                }) { Text("Close & release") }
            },
            dismissButton = { TextButton(onClick = { appToClose = null }) { Text("Cancel") } }
        )
    }

    if (confirmCloseAll) {
        AlertDialog(
            onDismissRequest = { confirmCloseAll = false },
            title = { Text("Close all user apps using ZRAM?") },
            text = { Text("This force-stops every non-system app currently reporting swapped memory. True RAM Usage itself and protected system apps are excluded.") },
            confirmButton = {
                Button(onClick = {
                    confirmCloseAll = false
                    viewModel.closeAllAppsInZram()
                }) { Text("Close all") }
            },
            dismissButton = { TextButton(onClick = { confirmCloseAll = false }) { Text("Cancel") } }
        )
    }

    if (confirmClearZram) {
        AlertDialog(
            onDismissRequest = { confirmClearZram = false },
            title = { Text("Clear kernel ZRAM?") },
            text = { Text("The active ZRAM swap is temporarily disabled so pages return to physical RAM, then the same device is enabled again. Its configured size is not changed.") },
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
private fun ViewAndAppearanceSection(
    displayMode: DisplayMode,
    themeMode: ThemeMode,
    onDisplayMode: (DisplayMode) -> Unit,
    onThemeMode: (ThemeMode) -> Unit
) {
    CollapsibleSection(
        title = "View & appearance",
        summary = "${if (displayMode == DisplayMode.SIMPLE) "Simple" else "Detailed"} • ${themeLabel(themeMode)}",
        initiallyExpanded = false
    ) {
        Text("Display mode", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DisplayMode.entries.forEach { mode ->
                FilterChip(
                    selected = displayMode == mode,
                    onClick = { onDisplayMode(mode) },
                    label = { Text(if (mode == DisplayMode.SIMPLE) "Simple" else "Detailed") }
                )
            }
        }
        Text("Theme", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = themeMode == mode,
                    onClick = { onThemeMode(mode) },
                    label = { Text(themeLabel(mode)) }
                )
            }
        }
        Text(
            "System follows the phone automatically. Light and Dark override the phone setting.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RootSection(state: RootState, inProgress: Boolean, onRequestRoot: () -> Unit) {
    val summary = when (state) {
        RootState.GRANTED -> "Root granted"
        RootState.NOT_REQUESTED -> "Root not requested"
        RootState.DENIED_OR_TIMED_OUT -> "Root not granted"
        RootState.UNAVAILABLE -> "Root unavailable"
    }
    CollapsibleSection("Access", summary, initiallyExpanded = state != RootState.GRANTED) {
        when (state) {
            RootState.GRANTED -> Text("Full kernel ZRAM and running-app memory access is enabled.")
            RootState.DENIED_OR_TIMED_OUT -> Button(onClick = onRequestRoot, enabled = !inProgress) { Text("Retry root access") }
            RootState.UNAVAILABLE -> Text("No compatible root command was found.")
            RootState.NOT_REQUESTED -> Button(onClick = onRequestRoot, enabled = !inProgress) {
                Text(if (inProgress) "Requesting root…" else "Grant root access")
            }
        }
    }
}

@Composable
private fun OverviewCharts(snapshot: MemorySnapshot) {
    val ramFraction = fraction(snapshot.usedRamBytes, snapshot.totalRamBytes)
    val swapFraction = fraction(snapshot.usedSwapBytes, snapshot.totalSwapBytes)
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Memory overview", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DonutChart(
                    title = "RAM",
                    used = snapshot.usedRamBytes,
                    total = snapshot.totalRamBytes,
                    fraction = ramFraction,
                    modifier = Modifier.weight(1f)
                )
                DonutChart(
                    title = if (snapshot.onlyKernelZramActive) "ZRAM" else "Swap",
                    used = snapshot.usedSwapBytes,
                    total = snapshot.totalSwapBytes,
                    fraction = swapFraction,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun DonutChart(title: String, used: Long, total: Long, fraction: Float, modifier: Modifier = Modifier) {
    val active = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(116.dp)) {
            Canvas(modifier = Modifier.size(104.dp)) {
                val stroke = Stroke(width = size.minDimension * 0.12f, cap = StrokeCap.Round)
                drawArc(track, -90f, 360f, false, style = stroke)
                drawArc(active, -90f, 360f * fraction.coerceIn(0f, 1f), false, style = stroke)
            }
            Text(formatPercent(fraction * 100.0), fontWeight = FontWeight.Bold)
        }
        Text(title, fontWeight = FontWeight.SemiBold)
        Text("${formatBytes(used)} / ${formatBytes(total)}", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PhysicalRamSection(snapshot: MemorySnapshot, mode: DisplayMode) {
    CollapsibleSection(
        title = "Physical RAM",
        summary = "${formatBytes(snapshot.usedRamBytes)} of ${formatBytes(snapshot.totalRamBytes)} used",
        initiallyExpanded = mode == DisplayMode.DETAILED
    ) {
        ValueRow("Used", formatPercent(fraction(snapshot.usedRamBytes, snapshot.totalRamBytes) * 100.0))
        ValueRow("Available", formatBytes(snapshot.availableRamBytes))
        if (mode == DisplayMode.DETAILED) {
            Text("Used RAM is calculated as MemTotal minus MemAvailable, so reclaimable cache is handled more realistically than a simple free-RAM reading.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ZramUsageSection(snapshot: MemorySnapshot, mode: DisplayMode) {
    val title = if (snapshot.onlyKernelZramActive) "Kernel ZRAM usage" else "Swap usage"
    CollapsibleSection(
        title = title,
        summary = "${formatBytes(snapshot.usedSwapBytes)} of ${formatBytes(snapshot.totalSwapBytes)} used",
        initiallyExpanded = mode == DisplayMode.DETAILED
    ) {
        ValueRow("Used", formatPercent(fraction(snapshot.usedSwapBytes, snapshot.totalSwapBytes) * 100.0))
        if (snapshot.onlyKernelZramActive) Text("The kernel reports ZRAM as the only active swap device.")
        if (mode == DisplayMode.DETAILED && snapshot.swapDevices.isNotEmpty()) {
            snapshot.swapDevices.forEach { SwapDeviceRows(it) }
        }
    }
}

@Composable
private fun ZramDetailsSection(device: ZramDevice, mode: DisplayMode) {
    CollapsibleSection(
        title = "Kernel ZRAM",
        summary = "${formatBytes(device.originalDataBytes)} stored • ${formatBytes(device.memoryUsedBytes)} physical RAM",
        initiallyExpanded = mode == DisplayMode.DETAILED
    ) {
        ValueRow("Capacity", formatBytes(device.diskSizeBytes))
        ValueRow("Data currently stored", formatBytes(device.originalDataBytes))
        ValueRow("Physical RAM used", formatBytes(device.memoryUsedBytes))
        ValueRow("RAM saved", formatBytes(device.ramSavedBytes))
        device.compressionRatio?.let { ValueRow("Compression", String.format(Locale.US, "%.2f×", it)) }

        if (mode == DisplayMode.DETAILED) {
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

@Composable
private fun ZramControlsSection(
    usedBytes: Long,
    safety: ZramClearSafety?,
    actionInProgress: Boolean,
    onClear: () -> Unit
) {
    CollapsibleSection("ZRAM controls", "${formatBytes(usedBytes)} currently swapped", initiallyExpanded = false) {
        when {
            safety == null -> Text("Checking safety…")
            safety.canClear -> Text("Enough physical RAM is available for a guarded clear.")
            safety.additionalNeededBytes > 0 -> Text("Clear is blocked for safety. About ${formatBytes(safety.additionalNeededBytes)} more available physical RAM is needed first.")
            else -> Text("No active kernel ZRAM swap device was detected.")
        }
        Button(
            onClick = onClear,
            enabled = safety?.canClear == true && !actionInProgress,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (actionInProgress) "Working…" else "Clear kernel ZRAM") }
    }
}

@Composable
private fun AppsInZramSection(
    apps: List<AppSwapUsage>,
    scanning: Boolean,
    scanError: String?,
    actionInProgress: Boolean,
    onlyKernelZram: Boolean,
    displayMode: DisplayMode,
    onRefresh: () -> Unit,
    onClose: (AppSwapUsage) -> Unit,
    onCloseAll: () -> Unit
) {
    val title = if (onlyKernelZram) "Apps using ZRAM" else "Apps using swap"
    val total = apps.sumOf { it.attributedSwapBytes }
    CollapsibleSection(
        title = title,
        summary = when {
            scanning -> "Scanning…"
            scanError != null -> "Scan error"
            apps.isEmpty() -> "No private swapped app pages found"
            else -> "${apps.size} apps • ${formatBytes(total)} private swapped memory"
        },
        initiallyExpanded = false
    ) {
        when {
            scanning -> Text("Reading lightweight per-process memory counters…")
            scanError != null -> InfoCard("App scan failed", scanError)
            apps.isEmpty() -> Text("No installed app currently reports private swapped pages.")
            else -> {
                val closableCount = apps.count { !it.isSystemApp }
                if (closableCount > 0) {
                    Button(onClick = onCloseAll, enabled = !actionInProgress, modifier = Modifier.fillMaxWidth()) {
                        Text("Close all user apps from ${if (onlyKernelZram) "ZRAM" else "swap"}")
                    }
                }
                apps.forEach { AppSwapCard(it, actionInProgress, displayMode, onlyKernelZram, onClose) }
            }
        }
        Button(onClick = onRefresh, enabled = !scanning && !actionInProgress, modifier = Modifier.fillMaxWidth()) {
            Text(if (scanning) "Scanning…" else "Refresh app list")
        }
        if (displayMode == DisplayMode.DETAILED) {
            Text(
                if (onlyKernelZram) {
                    "Per-app values use each process's VmSwap counter. They represent private process pages swapped into the active ZRAM device; shared tmpfs/shmem swap is not assigned to an individual app."
                } else {
                    "Multiple swap types are active, so VmSwap cannot identify which swap device contains each individual process page."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AppSwapCard(
    app: AppSwapUsage,
    actionInProgress: Boolean,
    mode: DisplayMode,
    onlyKernelZram: Boolean,
    onClose: (AppSwapUsage) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(app.label, fontWeight = FontWeight.Bold)
            Text(memoryLocation(app.residentBytes, app.attributedSwapBytes, onlyKernelZram), color = MaterialTheme.colorScheme.primary)
            ValueRow(if (onlyKernelZram) "In ZRAM" else "Swapped", formatBytes(app.attributedSwapBytes))
            if (mode == DisplayMode.DETAILED) {
                ValueRow("Still in physical RAM", formatBytes(app.residentBytes))
                ValueRow("Running for", formatDuration(app.runningSeconds))
                ValueRow("CPU time since start", formatCpuTime(app.cpuTimeSeconds))
                if (app.processCount > 1) ValueRow("Processes", app.processCount.toString())
            }
            if (app.isSystemApp) {
                Text("System app — protected", style = MaterialTheme.typography.bodySmall)
            } else {
                Button(onClick = { onClose(app) }, enabled = !actionInProgress, modifier = Modifier.fillMaxWidth()) {
                    Text("Close app & release swapped memory")
                }
            }
        }
    }
}

@Composable
private fun RunningAppsSection(
    apps: List<RunningAppUsage>,
    scanning: Boolean,
    scanError: String?,
    onlyKernelZram: Boolean,
    displayMode: DisplayMode,
    onRefresh: () -> Unit
) {
    CollapsibleSection(
        title = "Running apps",
        summary = when {
            scanning -> "Scanning…"
            scanError != null -> "Scan error"
            apps.isEmpty() -> "No mapped running apps"
            else -> "${apps.size} mapped Android apps"
        },
        initiallyExpanded = false
    ) {
        when {
            scanning -> Text("Reading running app memory, runtime and CPU counters…")
            scanError != null -> InfoCard("Running-app scan failed", scanError)
            apps.isEmpty() -> Text("No running Android apps could be mapped from the current process table.")
            else -> apps.forEach { RunningAppCard(it, onlyKernelZram, displayMode) }
        }
        Button(onClick = onRefresh, enabled = !scanning, modifier = Modifier.fillMaxWidth()) {
            Text(if (scanning) "Scanning…" else "Refresh running apps")
        }
    }
}

@Composable
private fun RunningAppCard(app: RunningAppUsage, onlyKernelZram: Boolean, mode: DisplayMode) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(app.label, fontWeight = FontWeight.Bold)
            Text(memoryLocation(app.residentBytes, app.swapBytes, onlyKernelZram), color = MaterialTheme.colorScheme.primary)
            if (mode == DisplayMode.SIMPLE) {
                ValueRow("Memory", formatBytes(app.residentBytes + app.swapBytes))
            } else {
                ValueRow("Physical RAM", formatBytes(app.residentBytes))
                ValueRow(if (onlyKernelZram) "ZRAM" else "Swap", formatBytes(app.swapBytes))
                ValueRow("Running for", formatDuration(app.runningSeconds))
                ValueRow("CPU time since start", formatCpuTime(app.cpuTimeSeconds))
                ValueRow("Processes", app.processCount.toString())
                if (app.isSystemApp) Text("System app", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AdvancedSystemDetails(snapshot: MemorySnapshot) {
    CollapsibleSection("Advanced system details", "Kernel VM and pressure counters", initiallyExpanded = false) {
        snapshot.vmStats.swappiness?.let { ValueRow("Swappiness", it.toString()) }
        snapshot.vmStats.swapInPages?.let { ValueRow("Swap-in pages since boot", formatCount(it)) }
        snapshot.vmStats.swapOutPages?.let { ValueRow("Swap-out pages since boot", formatCount(it)) }
        snapshot.pressure?.someAvg10?.let { ValueRow("Memory pressure, some", formatPsi(it)) }
        snapshot.pressure?.fullAvg10?.let { ValueRow("Memory pressure, full", formatPsi(it)) }
        if (snapshot.swapDevices.isNotEmpty()) {
            Text("Active swap devices", fontWeight = FontWeight.SemiBold)
            snapshot.swapDevices.forEach { SwapDeviceRows(it) }
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
private fun CollapsibleSection(
    title: String,
    summary: String,
    initiallyExpanded: Boolean,
    content: @Composable Column.() -> Unit
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Collapse" else "Expand")
                }
            }
            if (expanded) content()
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(body)
        }
    }
}

@Composable
private fun ValueRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
    }
}

private fun memoryLocation(residentBytes: Long, swapBytes: Long, onlyKernelZram: Boolean): String = when {
    residentBytes > 0L && swapBytes > 0L -> if (onlyKernelZram) "Physical RAM + ZRAM" else "Physical RAM + swap"
    swapBytes > 0L -> if (onlyKernelZram) "ZRAM" else "Swap"
    residentBytes > 0L -> "Physical RAM"
    else -> "Running • no resident/swap pages reported"
}

private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

private fun fraction(used: Long, total: Long): Float = if (total > 0L) {
    (used.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
} else 0f

private fun formatBytes(bytes: Long): String {
    val safe = max(0L, bytes)
    val gib = safe / (1024.0 * 1024.0 * 1024.0)
    return if (gib >= 1.0) String.format(Locale.US, "%.2f GiB", gib)
    else String.format(Locale.US, "%.0f MiB", safe / (1024.0 * 1024.0))
}

private fun formatDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    val days = safe / 86_400L
    val hours = (safe % 86_400L) / 3_600L
    val minutes = (safe % 3_600L) / 60L
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}

private fun formatCpuTime(seconds: Double): String {
    val safe = seconds.coerceAtLeast(0.0)
    return when {
        safe >= 3_600.0 -> String.format(Locale.US, "%.1f h", safe / 3_600.0)
        safe >= 60.0 -> String.format(Locale.US, "%.1f min", safe / 60.0)
        else -> String.format(Locale.US, "%.1f s", safe)
    }
}

private fun formatPercent(value: Double): String = String.format(Locale.US, "%.1f%%", value.coerceIn(0.0, 100.0))
private fun formatPsi(value: Double): String = String.format(Locale.US, "%.2f%%", value)
private fun formatCount(value: Long): String = String.format(Locale.US, "%,d", value)
