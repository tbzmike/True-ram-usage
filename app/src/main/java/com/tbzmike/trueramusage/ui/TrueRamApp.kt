package com.tbzmike.trueramusage.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
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
    AppTheme(viewModel.themeMode) { AppScreen(viewModel) }
}

@Composable
private fun AppTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    val view = LocalView.current
    SideEffect {
        val activity = view.context as? Activity ?: return@SideEffect
        WindowCompat.getInsetsController(activity.window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun AppScreen(vm: MemoryViewModel) {
    var appToClose by remember { mutableStateOf<AppSwapUsage?>(null) }
    var confirmCloseAll by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val snapshot = vm.snapshot

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("True RAM Usage", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Real memory information in plain language.", color = MaterialTheme.colorScheme.onSurfaceVariant)

            PreferencesSection(vm)
            AccessSection(vm)

            if (snapshot == null) {
                InfoCard("Reading memory", vm.errorMessage ?: "Reading memory information…")
                Button(onClick = vm::refreshNow, modifier = Modifier.fillMaxWidth()) { Text("Refresh") }
                return@Column
            }

            OverviewCharts(snapshot)
            RamSection(snapshot, vm.displayMode)
            SwapSection(snapshot, vm.displayMode)

            if (snapshot.zramDevices.isEmpty()) {
                Section("Kernel ZRAM", "Detailed statistics unavailable", false) {
                    Text(if (vm.rootState == RootState.GRANTED) "This kernel did not expose readable detailed ZRAM statistics." else "Grant root to read detailed ZRAM statistics.")
                }
            } else {
                snapshot.zramDevices.forEach { ZramSection(it, vm.displayMode) }
            }

            if (vm.rootState == RootState.GRANTED && snapshot.activeZramSwapDevices.isNotEmpty()) {
                ControlsSection(snapshot, vm.zramClearSafety, vm.actionInProgress) { confirmClear = true }
            }

            if (vm.rootState == RootState.GRANTED) {
                AppsInSwapSection(
                    vm.appsInZram,
                    vm.appsScanInProgress,
                    vm.appsScanError,
                    vm.actionInProgress,
                    snapshot.onlyKernelZramActive,
                    vm.displayMode,
                    vm::refreshAppsNow,
                    { appToClose = it },
                    { confirmCloseAll = true }
                )
                RunningAppsSection(
                    vm.runningApps,
                    vm.appsScanInProgress,
                    vm.appsScanError,
                    snapshot.onlyKernelZramActive,
                    vm.displayMode,
                    vm::refreshAppsNow
                )
            }

            if (vm.displayMode == DisplayMode.DETAILED) AdvancedSection(snapshot)

            vm.actionMessage?.let { InfoCard("Completed", it) }
            vm.actionError?.let { InfoCard("Action warning", it) }
            vm.errorMessage?.let { InfoCard("Read warning", it) }

            Button(onClick = vm::refreshNow, modifier = Modifier.fillMaxWidth()) { Text("Refresh memory readings") }
            Spacer(Modifier.height(12.dp))
        }
    }

    appToClose?.let { app ->
        AlertDialog(
            onDismissRequest = { appToClose = null },
            title = { Text("Close ${app.label}?") },
            text = { Text("This force-stops the app so its ${formatBytes(app.attributedSwapBytes)} of private swapped memory can be released.") },
            confirmButton = { Button(onClick = { appToClose = null; vm.closeAndRelease(app) }) { Text("Close & release") } },
            dismissButton = { TextButton(onClick = { appToClose = null }) { Text("Cancel") } }
        )
    }

    if (confirmCloseAll) {
        AlertDialog(
            onDismissRequest = { confirmCloseAll = false },
            title = { Text("Close all user apps using ZRAM?") },
            text = { Text("Every non-system app currently reporting swapped memory will be force-stopped. True RAM Usage and protected system apps are excluded.") },
            confirmButton = { Button(onClick = { confirmCloseAll = false; vm.closeAllAppsInZram() }) { Text("Close all") } },
            dismissButton = { TextButton(onClick = { confirmCloseAll = false }) { Text("Cancel") } }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear kernel ZRAM?") },
            text = { Text("The existing ZRAM swap is temporarily disabled and re-enabled only if the safety check says enough physical RAM is available.") },
            confirmButton = { Button(onClick = { confirmClear = false; vm.clearKernelZram() }) { Text("Clear ZRAM") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun PreferencesSection(vm: MemoryViewModel) {
    Section("View & appearance", "${if (vm.displayMode == DisplayMode.SIMPLE) "Simple" else "Detailed"} • ${themeName(vm.themeMode)}", false) {
        Text("Display mode", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(vm.displayMode == DisplayMode.SIMPLE, { vm.setDisplayMode(DisplayMode.SIMPLE) }, { Text("Simple") })
            FilterChip(vm.displayMode == DisplayMode.DETAILED, { vm.setDisplayMode(DisplayMode.DETAILED) }, { Text("Detailed") })
        }
        Text("Theme", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(vm.themeMode == mode, { vm.setThemeMode(mode) }, { Text(themeName(mode)) })
            }
        }
        Text("System follows the phone automatically; Light and Dark are manual overrides.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AccessSection(vm: MemoryViewModel) {
    val summary = when (vm.rootState) {
        RootState.GRANTED -> "Root granted"
        RootState.NOT_REQUESTED -> "Root not requested"
        RootState.DENIED_OR_TIMED_OUT -> "Root not granted"
        RootState.UNAVAILABLE -> "Root unavailable"
    }
    Section("Access", summary, vm.rootState != RootState.GRANTED) {
        when (vm.rootState) {
            RootState.GRANTED -> Text("Full kernel ZRAM and running-app process access is enabled.")
            RootState.NOT_REQUESTED -> Button(onClick = vm::requestRoot, enabled = !vm.rootRequestInProgress) { Text("Grant root access") }
            RootState.DENIED_OR_TIMED_OUT -> Button(onClick = vm::requestRoot, enabled = !vm.rootRequestInProgress) { Text("Retry root access") }
            RootState.UNAVAILABLE -> Text("No compatible root command was found.")
        }
    }
}

@Composable
private fun OverviewCharts(s: MemorySnapshot) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Memory overview", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Donut("RAM", s.usedRamBytes, s.totalRamBytes, Modifier.weight(1f))
                Donut(if (s.onlyKernelZramActive) "ZRAM" else "Swap", s.usedSwapBytes, s.totalSwapBytes, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Donut(title: String, used: Long, total: Long, modifier: Modifier) {
    val f = fraction(used, total)
    val active = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(100.dp)) {
                val stroke = Stroke(size.minDimension * 0.12f, cap = StrokeCap.Round)
                drawArc(track, -90f, 360f, false, style = stroke)
                drawArc(active, -90f, 360f * f, false, style = stroke)
            }
            Text(formatPercent(f.toDouble() * 100.0), fontWeight = FontWeight.Bold)
        }
        Text(title, fontWeight = FontWeight.SemiBold)
        Text("${formatBytes(used)} / ${formatBytes(total)}", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
}

@Composable
private fun RamSection(s: MemorySnapshot, mode: DisplayMode) {
    Section("Physical RAM", "${formatBytes(s.usedRamBytes)} of ${formatBytes(s.totalRamBytes)} used", mode == DisplayMode.DETAILED) {
        Value("Used", formatPercent(fraction(s.usedRamBytes, s.totalRamBytes).toDouble() * 100.0))
        Value("Available", formatBytes(s.availableRamBytes))
        if (mode == DisplayMode.DETAILED) Text("Used RAM is MemTotal minus MemAvailable, which accounts for reclaimable memory better than a free-RAM figure.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SwapSection(s: MemorySnapshot, mode: DisplayMode) {
    Section(if (s.onlyKernelZramActive) "Kernel ZRAM usage" else "Swap usage", "${formatBytes(s.usedSwapBytes)} of ${formatBytes(s.totalSwapBytes)} used", mode == DisplayMode.DETAILED) {
        Value("Used", formatPercent(fraction(s.usedSwapBytes, s.totalSwapBytes).toDouble() * 100.0))
        if (s.onlyKernelZramActive) Text("ZRAM is the only active swap device.")
        if (mode == DisplayMode.DETAILED) s.swapDevices.forEach { SwapRows(it) }
    }
}

@Composable
private fun ZramSection(z: ZramDevice, mode: DisplayMode) {
    Section("Kernel ZRAM", "${formatBytes(z.originalDataBytes)} stored • ${formatBytes(z.memoryUsedBytes)} physical RAM", mode == DisplayMode.DETAILED) {
        Value("Capacity", formatBytes(z.diskSizeBytes))
        Value("Data currently stored", formatBytes(z.originalDataBytes))
        Value("Physical RAM used", formatBytes(z.memoryUsedBytes))
        Value("RAM saved", formatBytes(z.ramSavedBytes))
        z.compressionRatio?.let { Value("Compression", String.format(Locale.US, "%.2f×", it)) }
        if (mode == DisplayMode.DETAILED) {
            Value("Compressed payload", formatBytes(z.compressedDataBytes))
            z.effectiveRamRatio?.let { Value("Effective RAM ratio", String.format(Locale.US, "%.2f×", it)) }
            z.compressionAlgorithm?.let { Value("Compression method", it) }
            if (z.peakMemoryUsedBytes > 0) Value("Peak physical RAM used", formatBytes(z.peakMemoryUsedBytes))
            if (z.samePages > 0) Value("Identical pages optimized", formatCount(z.samePages))
            if (z.compactedPages > 0) Value("Pages freed by compaction", formatCount(z.compactedPages))
            if (z.hugePages > 0) Value("Poorly compressible huge pages", formatCount(z.hugePages))
        }
    }
}

@Composable
private fun ControlsSection(s: MemorySnapshot, safety: ZramClearSafety?, busy: Boolean, onClear: () -> Unit) {
    Section("ZRAM controls", "${formatBytes(s.kernelZramSwapUsedBytes)} currently swapped", false) {
        when {
            safety == null -> Text("Checking safety…")
            safety.canClear -> Text("Enough physical RAM is available for a guarded clear.")
            safety.additionalNeededBytes > 0 -> Text("Clear is blocked for safety. About ${formatBytes(safety.additionalNeededBytes)} more available RAM is needed.")
            else -> Text("No active ZRAM device was detected.")
        }
        Button(onClick = onClear, enabled = safety?.canClear == true && !busy, modifier = Modifier.fillMaxWidth()) { Text("Clear kernel ZRAM") }
    }
}

@Composable
private fun AppsInSwapSection(
    apps: List<AppSwapUsage>, scanning: Boolean, error: String?, busy: Boolean,
    zramOnly: Boolean, mode: DisplayMode, refresh: () -> Unit,
    close: (AppSwapUsage) -> Unit, closeAll: () -> Unit
) {
    val total = apps.sumOf { it.attributedSwapBytes }
    val summary = when {
        scanning -> "Scanning…"
        error != null -> "Scan error"
        apps.isEmpty() -> "No private swapped app pages found"
        else -> "${apps.size} apps • ${formatBytes(total)} swapped"
    }
    Section(if (zramOnly) "Apps using ZRAM" else "Apps using swap", summary, false) {
        when {
            scanning -> Text("Reading per-process memory counters…")
            error != null -> InfoCard("App scan failed", error)
            apps.isEmpty() -> Text("No installed app currently reports private swapped pages.")
            else -> {
                if (apps.any { !it.isSystemApp }) Button(onClick = closeAll, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Close all user apps from ${if (zramOnly) "ZRAM" else "swap"}") }
                apps.forEach { AppSwapRow(it, zramOnly, mode, busy, close) }
            }
        }
        Button(onClick = refresh, enabled = !scanning && !busy, modifier = Modifier.fillMaxWidth()) { Text(if (scanning) "Scanning…" else "Refresh app list") }
        if (mode == DisplayMode.DETAILED) Text("Per-app swap uses VmSwap, which reports private process pages. Shared tmpfs/shmem swap is not assigned to an individual app.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AppSwapRow(app: AppSwapUsage, zramOnly: Boolean, mode: DisplayMode, busy: Boolean, close: (AppSwapUsage) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(app.label, fontWeight = FontWeight.Bold)
            Text(location(app.residentBytes, app.attributedSwapBytes, zramOnly), color = MaterialTheme.colorScheme.primary)
            Value(if (zramOnly) "In ZRAM" else "Swapped", formatBytes(app.attributedSwapBytes))
            if (mode == DisplayMode.DETAILED) {
                Value("Physical RAM", formatBytes(app.residentBytes))
                Value("Running for", formatDuration(app.runningSeconds))
                Value("CPU time since start", formatCpu(app.cpuTimeSeconds))
                if (app.processCount > 1) Value("Processes", app.processCount.toString())
            }
            if (app.isSystemApp) Text("System app — protected", style = MaterialTheme.typography.bodySmall)
            else Button(onClick = { close(app) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Close app & release swapped memory") }
        }
    }
}

@Composable
private fun RunningAppsSection(apps: List<RunningAppUsage>, scanning: Boolean, error: String?, zramOnly: Boolean, mode: DisplayMode, refresh: () -> Unit) {
    val summary = when {
        scanning -> "Scanning…"
        error != null -> "Scan error"
        apps.isEmpty() -> "No mapped running apps"
        else -> "${apps.size} mapped Android apps"
    }
    Section("Running apps", summary, false) {
        when {
            scanning -> Text("Reading RAM, swap, runtime and CPU counters…")
            error != null -> InfoCard("Running-app scan failed", error)
            apps.isEmpty() -> Text("No running Android apps could be mapped from the current process table.")
            else -> apps.forEach { RunningRow(it, zramOnly, mode) }
        }
        Button(onClick = refresh, enabled = !scanning, modifier = Modifier.fillMaxWidth()) { Text(if (scanning) "Scanning…" else "Refresh running apps") }
    }
}

@Composable
private fun RunningRow(app: RunningAppUsage, zramOnly: Boolean, mode: DisplayMode) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(app.label, fontWeight = FontWeight.Bold)
            Text(location(app.residentBytes, app.swapBytes, zramOnly), color = MaterialTheme.colorScheme.primary)
            if (mode == DisplayMode.SIMPLE) {
                Value("Memory", formatBytes(app.residentBytes + app.swapBytes))
            } else {
                Value("Physical RAM", formatBytes(app.residentBytes))
                Value(if (zramOnly) "ZRAM" else "Swap", formatBytes(app.swapBytes))
                Value("Running for", formatDuration(app.runningSeconds))
                Value("CPU time since start", formatCpu(app.cpuTimeSeconds))
                Value("Processes", app.processCount.toString())
                if (app.isSystemApp) Text("System app", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AdvancedSection(s: MemorySnapshot) {
    Section("Advanced system details", "Kernel VM and pressure counters", false) {
        s.vmStats.swappiness?.let { Value("Swappiness", it.toString()) }
        s.vmStats.swapInPages?.let { Value("Swap-in pages since boot", formatCount(it)) }
        s.vmStats.swapOutPages?.let { Value("Swap-out pages since boot", formatCount(it)) }
        s.pressure?.someAvg10?.let { Value("Memory pressure, some", formatPsi(it)) }
        s.pressure?.fullAvg10?.let { Value("Memory pressure, full", formatPsi(it)) }
    }
}

@Composable
private fun SwapRows(d: SwapDevice) {
    Text(if (d.isZram) "Kernel ZRAM" else d.path.substringAfterLast('/'), fontWeight = FontWeight.SemiBold)
    Value("Used", "${formatBytes(d.usedBytes)} / ${formatBytes(d.sizeBytes)}")
    d.priority?.let { Value("Priority", it.toString()) }
}

@Composable
private fun Section(title: String, summary: String, initiallyExpanded: Boolean, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Collapse" else "Expand") }
            }
            if (expanded) content()
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(body)
        }
    }
}

@Composable
private fun Value(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
    }
}

private fun location(ram: Long, swap: Long, zramOnly: Boolean) = when {
    ram > 0 && swap > 0 -> if (zramOnly) "Physical RAM + ZRAM" else "Physical RAM + swap"
    swap > 0 -> if (zramOnly) "ZRAM" else "Swap"
    ram > 0 -> "Physical RAM"
    else -> "Running • no resident/swap pages reported"
}

private fun themeName(mode: ThemeMode) = when (mode) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

private fun fraction(used: Long, total: Long) = if (total > 0) (used.toDouble() / total).coerceIn(0.0, 1.0).toFloat() else 0f

private fun formatBytes(bytes: Long): String {
    val safe = max(0L, bytes)
    val gib = safe / (1024.0 * 1024.0 * 1024.0)
    return if (gib >= 1.0) String.format(Locale.US, "%.2f GiB", gib) else String.format(Locale.US, "%.0f MiB", safe / (1024.0 * 1024.0))
}

private fun formatDuration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    val d = s / 86400
    val h = (s % 86400) / 3600
    val m = (s % 3600) / 60
    return when { d > 0 -> "${d}d ${h}h"; h > 0 -> "${h}h ${m}m"; else -> "${m}m" }
}

private fun formatCpu(seconds: Double): String {
    val s = seconds.coerceAtLeast(0.0)
    return when { s >= 3600 -> String.format(Locale.US, "%.1f h", s / 3600); s >= 60 -> String.format(Locale.US, "%.1f min", s / 60); else -> String.format(Locale.US, "%.1f s", s) }
}

private fun formatPercent(value: Double) = String.format(Locale.US, "%.1f%%", value.coerceIn(0.0, 100.0))
private fun formatPsi(value: Double) = String.format(Locale.US, "%.2f%%", value)
private fun formatCount(value: Long) = String.format(Locale.US, "%,d", value)
