package com.tbzmike.trueramusage.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tbzmike.trueramusage.data.AggressiveAppReclaimer
import com.tbzmike.trueramusage.data.AppPreferences
import com.tbzmike.trueramusage.data.AppSwapRepository
import com.tbzmike.trueramusage.data.AppSwapUsage
import com.tbzmike.trueramusage.data.DisplayMode
import com.tbzmike.trueramusage.data.MemoryActions
import com.tbzmike.trueramusage.data.MemoryRepository
import com.tbzmike.trueramusage.data.MemorySnapshot
import com.tbzmike.trueramusage.data.ProcessScanResult
import com.tbzmike.trueramusage.data.RootAccess
import com.tbzmike.trueramusage.data.RootState
import com.tbzmike.trueramusage.data.RunningAppUsage
import com.tbzmike.trueramusage.data.ThemeMode
import com.tbzmike.trueramusage.data.UnmappedProcessUsage
import com.tbzmike.trueramusage.data.ZramClearSafety
import com.tbzmike.trueramusage.update.UpdatePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AggressiveReclaimReport(
    val beforeUsedRamBytes: Long,
    val afterUsedRamBytes: Long,
    val beforeAvailableRamBytes: Long,
    val afterAvailableRamBytes: Long,
    val beforeSwapUsedBytes: Long,
    val afterSwapUsedBytes: Long,
    val userAppCloseMessage: String,
    val userAppsTargeted: Int,
    val failedForceStopCommands: Int,
    val killAllPassesSucceeded: Int,
    val appSweepRepeated: Boolean,
    val remainingMappedUserApps: Int?,
    val kernelReclaimMessage: String,
    val swapMessage: String,
    val swapPasses: Int,
    val swapClearBlockedBytes: Long,
    val allSwapWasDisabledAtOnce: Boolean,
    val swappinessRestored: Boolean
) {
    val reclaimedRamBytes: Long
        get() = (beforeUsedRamBytes - afterUsedRamBytes).coerceAtLeast(0L)

    val gainedAvailableRamBytes: Long
        get() = (afterAvailableRamBytes - beforeAvailableRamBytes).coerceAtLeast(0L)

    val clearedSwapBytes: Long
        get() = (beforeSwapUsedBytes - afterSwapUsedBytes).coerceAtLeast(0L)

    val swapIsEmpty: Boolean
        get() = afterSwapUsedBytes == 0L
}

class MemoryViewModel(application: Application) : AndroidViewModel(application) {
    private val rootAccess = RootAccess()
    private val repository = MemoryRepository(rootAccess)
    private val appSwapRepository = AppSwapRepository(application, rootAccess)
    private val memoryActions = MemoryActions(rootAccess)
    private val aggressiveAppReclaimer = AggressiveAppReclaimer(rootAccess)
    private val preferences = AppPreferences(application)
    private val updatePreferences = UpdatePreferences(application)
    private val ownPackageName = application.packageName
    private var monitoringJob: Job? = null
    private var activityStarted = false

    var snapshot by mutableStateOf<MemorySnapshot?>(null)
        private set

    var appsInZram by mutableStateOf<List<AppSwapUsage>>(emptyList())
        private set

    var runningApps by mutableStateOf<List<RunningAppUsage>>(emptyList())
        private set

    var unmappedProcesses by mutableStateOf<List<UnmappedProcessUsage>>(emptyList())
        private set

    var appsScanError by mutableStateOf<String?>(null)
        private set

    var rootState by mutableStateOf(RootState.NOT_REQUESTED)
        private set

    var rootRequestInProgress by mutableStateOf(false)
        private set

    var appsScanInProgress by mutableStateOf(false)
        private set

    var actionInProgress by mutableStateOf(false)
        private set

    var actionMessage by mutableStateOf<String?>(null)
        private set

    var actionError by mutableStateOf<String?>(null)
        private set

    var aggressiveReclaimStage by mutableStateOf<String?>(null)
        private set

    var aggressiveReclaimReport by mutableStateOf<AggressiveReclaimReport?>(null)
        private set

    var zramClearSafety by mutableStateOf<ZramClearSafety?>(null)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    private var displayModeState by mutableStateOf(preferences.displayMode)
    val displayMode: DisplayMode
        get() = displayModeState

    private var themeModeState by mutableStateOf(preferences.themeMode)
    val themeMode: ThemeMode
        get() = themeModeState

    private var automaticMemoryRefreshState by mutableStateOf(preferences.automaticMemoryRefreshEnabled)
    val automaticMemoryRefreshEnabled: Boolean
        get() = automaticMemoryRefreshState

    private var memoryRefreshIntervalState by mutableStateOf(preferences.memoryRefreshIntervalMs)
    val memoryRefreshIntervalMs: Long
        get() = memoryRefreshIntervalState

    fun setMonitoringActive(active: Boolean) {
        activityStarted = active
        restartMonitoringLoop()
    }

    private fun restartMonitoringLoop() {
        monitoringJob?.cancel()
        monitoringJob = null
        if (!activityStarted) return

        if (!automaticMemoryRefreshState) {
            monitoringJob = viewModelScope.launch { refreshMemory() }
            return
        }

        monitoringJob = viewModelScope.launch {
            while (isActive) {
                refreshMemory()
                delay(memoryRefreshIntervalState)
            }
        }
    }

    fun setDisplayMode(mode: DisplayMode) {
        displayModeState = mode
        preferences.displayMode = mode
    }

    fun setThemeMode(mode: ThemeMode) {
        themeModeState = mode
        preferences.themeMode = mode
    }

    fun setAutomaticMemoryRefresh(enabled: Boolean) {
        automaticMemoryRefreshState = enabled
        preferences.automaticMemoryRefreshEnabled = enabled
        restartMonitoringLoop()
    }

    fun setMemoryRefreshInterval(intervalMs: Long) {
        val normalized = AppPreferences.normalizeRefreshInterval(intervalMs)
        memoryRefreshIntervalState = normalized
        preferences.memoryRefreshIntervalMs = normalized
        if (automaticMemoryRefreshState) restartMonitoringLoop()
    }

    fun refreshNow() {
        viewModelScope.launch { refreshMemory() }
    }

    fun refreshAppsNow() {
        if (rootState != RootState.GRANTED || appsScanInProgress) return
        viewModelScope.launch { refreshApps() }
    }

    fun requestRoot() {
        if (rootRequestInProgress) return
        viewModelScope.launch {
            rootRequestInProgress = true
            rootState = withContext(Dispatchers.IO) { rootAccess.request() }
            rootRequestInProgress = false
            if (rootState == RootState.GRANTED) updatePreferences.rootPreviouslyGranted = true
            refreshMemory()
            if (rootState == RootState.GRANTED) refreshApps()
        }
    }

    fun closeAndRelease(app: AppSwapUsage) {
        if (actionInProgress) return
        viewModelScope.launch {
            actionInProgress = true
            clearActionMessage()
            val result = withContext(Dispatchers.IO) {
                memoryActions.closeApp(app.packageName, app.isSystemApp, ownPackageName)
            }
            if (result.success) actionMessage = result.message else actionError = result.message
            delay(700)
            refreshMemory()
            refreshApps()
            actionInProgress = false
        }
    }

    fun closeAllAppsInZram() {
        if (actionInProgress || appsInZram.isEmpty()) return
        viewModelScope.launch {
            actionInProgress = true
            clearActionMessage()
            val result = withContext(Dispatchers.IO) {
                memoryActions.closeAllAppsInZram(appsInZram, ownPackageName)
            }
            if (result.success) actionMessage = result.message else actionError = result.message
            delay(900)
            refreshMemory()
            refreshApps()
            actionInProgress = false
        }
    }

    fun closeAllUserApps() {
        if (actionInProgress || runningApps.isEmpty()) return
        viewModelScope.launch {
            actionInProgress = true
            clearActionMessage()
            val result = withContext(Dispatchers.IO) {
                memoryActions.closeAllUserApps(runningApps, ownPackageName)
            }
            if (result.success) actionMessage = result.message else actionError = result.message
            delay(900)
            refreshMemory()
            refreshApps()
            actionInProgress = false
        }
    }

    fun reclaimFileCaches() {
        if (actionInProgress) return
        viewModelScope.launch {
            actionInProgress = true
            clearActionMessage()
            val result = withContext(Dispatchers.IO) {
                memoryActions.reclaimFileCaches()
            }
            if (result.success) actionMessage = result.message else actionError = result.message
            delay(500)
            refreshMemory()
            actionInProgress = false
        }
    }

    fun clearKernelZram() {
        val current = snapshot ?: return
        if (actionInProgress) return
        viewModelScope.launch {
            actionInProgress = true
            clearActionMessage()
            val result = withContext(Dispatchers.IO) {
                memoryActions.clearKernelZram(current)
            }
            if (result.success) actionMessage = result.message else actionError = result.message
            delay(700)
            refreshMemory()
            refreshApps()
            actionInProgress = false
        }
    }

    fun aggressiveBootLikeReclaim() {
        if (actionInProgress || rootState != RootState.GRANTED) return
        viewModelScope.launch {
            actionInProgress = true
            clearActionMessage()
            aggressiveReclaimReport = null
            try {
                aggressiveReclaimStage = "Capturing before-state memory readings…"
                val before = withContext(Dispatchers.IO) { repository.readSnapshot() }

                aggressiveReclaimStage = "Scanning current processes before the exhaustive app sweep…"
                val freshScan = withContext(Dispatchers.IO) { appSwapRepository.readUsage() }
                applyProcessScan(freshScan)

                aggressiveReclaimStage = "Enumerating and force-stopping every installed third-party app…"
                val firstAppSweep = withContext(Dispatchers.IO) {
                    aggressiveAppReclaimer.forceStopAllKillableUserApps(freshScan.apps, ownPackageName)
                }
                if (firstAppSweep.currentUserId == null) {
                    throw IllegalStateException(firstAppSweep.message)
                }

                delay(700)
                aggressiveReclaimStage = "Killing remaining Android-background processes, dropping clean caches and compacting memory…"
                val kernelResult = withContext(Dispatchers.IO) { memoryActions.aggressiveKernelReclaim() }

                delay(800)
                aggressiveReclaimStage = "Re-reading RAM and swap before the swapoff phase…"
                var current = withContext(Dispatchers.IO) { repository.readSnapshot() }
                var safety = memoryActions.getAllSwapClearSafety(current)
                var swapPasses = 0
                var allSwapWasDisabledAtOnce = false
                var swappinessRestored = true
                var swapMessage = when {
                    current.swapDevices.isEmpty() -> "No active swap/ZRAM device was present after RAM reclaim."
                    !safety.canClear -> "All-swap clear was blocked because ${safety.additionalNeededBytes} more bytes of MemAvailable headroom were required after aggressive RAM reclaim."
                    else -> "All-swap clear had not started."
                }

                while (
                    current.swapDevices.isNotEmpty() &&
                    current.usedSwapBytes > 0L &&
                    safety.canClear &&
                    swapPasses < 2
                ) {
                    aggressiveReclaimStage = "Emptying all active swap/ZRAM together — pass ${swapPasses + 1}…"
                    val clearResult = withContext(Dispatchers.IO) { memoryActions.clearAllSwap(current) }
                    swapPasses += 1
                    allSwapWasDisabledAtOnce = allSwapWasDisabledAtOnce || clearResult.allSwapDisabledAtOnce
                    swappinessRestored = swappinessRestored && clearResult.swappinessRestored
                    swapMessage = clearResult.message
                    if (!clearResult.success) break

                    delay(500)
                    current = withContext(Dispatchers.IO) { repository.readSnapshot() }
                    safety = memoryActions.getAllSwapClearSafety(current)
                    if (current.usedSwapBytes == 0L) break
                }

                aggressiveReclaimStage = "Performing final background/cache reclaim after swap cycling…"
                val finalKernelResult = withContext(Dispatchers.IO) { memoryActions.aggressiveKernelReclaim() }
                delay(700)

                aggressiveReclaimStage = "Checking whether any user apps restarted during reclaim…"
                val preFinalScan = runCatching {
                    withContext(Dispatchers.IO) { appSwapRepository.readUsage() }
                }.getOrNull()
                val restartedUserApps = preFinalScan?.apps.orEmpty().filter {
                    !it.isSystemApp && it.packageName != ownPackageName
                }

                var repeatedSweep = false
                var retryMessage: String? = null
                var retryFailedCommands = 0
                var retryKillPasses = 0
                if (restartedUserApps.isNotEmpty()) {
                    repeatedSweep = true
                    aggressiveReclaimStage = "${restartedUserApps.size} user app(s) restarted; force-stopping the complete user-app set again…"
                    val retrySweep = withContext(Dispatchers.IO) {
                        aggressiveAppReclaimer.forceStopAllKillableUserApps(preFinalScan?.apps.orEmpty(), ownPackageName)
                    }
                    retryMessage = retrySweep.message
                    retryFailedCommands = retrySweep.failedForceStopCommands
                    retryKillPasses = retrySweep.killAllPassesSucceeded
                    withContext(Dispatchers.IO) { memoryActions.aggressiveKernelReclaim() }
                    delay(700)
                }

                aggressiveReclaimStage = "Verifying final physical RAM, swap and surviving mapped user apps…"
                val after = withContext(Dispatchers.IO) { repository.readSnapshot() }
                snapshot = after
                zramClearSafety = memoryActions.getClearSafety(after)
                errorMessage = null

                val afterScan = runCatching {
                    withContext(Dispatchers.IO) { appSwapRepository.readUsage() }
                }.getOrNull()
                if (afterScan != null) applyProcessScan(afterScan)
                val remainingMappedUserApps = afterScan?.apps?.count {
                    !it.isSystemApp && it.packageName != ownPackageName
                }

                val appSweepMessage = buildString {
                    append(firstAppSweep.message)
                    if (retryMessage != null) append(" Restart cleanup: $retryMessage")
                    if (remainingMappedUserApps != null) {
                        append(" Final mapped non-system app processes still visible: $remainingMappedUserApps.")
                    }
                }
                val kernelMessage = "Initial kernel pass: ${kernelResult.message} Final kernel pass: ${finalKernelResult.message}"

                aggressiveReclaimReport = AggressiveReclaimReport(
                    beforeUsedRamBytes = before.usedRamBytes,
                    afterUsedRamBytes = after.usedRamBytes,
                    beforeAvailableRamBytes = before.availableRamBytes,
                    afterAvailableRamBytes = after.availableRamBytes,
                    beforeSwapUsedBytes = before.usedSwapBytes,
                    afterSwapUsedBytes = after.usedSwapBytes,
                    userAppCloseMessage = appSweepMessage,
                    userAppsTargeted = firstAppSweep.targetedPackages,
                    failedForceStopCommands = firstAppSweep.failedForceStopCommands + retryFailedCommands,
                    killAllPassesSucceeded = firstAppSweep.killAllPassesSucceeded + retryKillPasses,
                    appSweepRepeated = repeatedSweep,
                    remainingMappedUserApps = remainingMappedUserApps,
                    kernelReclaimMessage = kernelMessage,
                    swapMessage = swapMessage,
                    swapPasses = swapPasses,
                    swapClearBlockedBytes = if (swapPasses == 0 && current.swapDevices.isNotEmpty() && !safety.canClear) safety.additionalNeededBytes else 0L,
                    allSwapWasDisabledAtOnce = allSwapWasDisabledAtOnce,
                    swappinessRestored = swappinessRestored
                )
                actionMessage = "Aggressive reclaim completed. User-app force-stop, kernel reclaim and final RAM/swap readings were verified."
            } catch (error: Throwable) {
                actionError = error.message ?: "Aggressive reclaim failed before final verification."
                runCatching { refreshMemory() }
            } finally {
                aggressiveReclaimStage = null
                actionInProgress = false
            }
        }
    }

    fun clearAggressiveReclaimReport() {
        aggressiveReclaimReport = null
    }

    fun clearActionMessage() {
        actionMessage = null
        actionError = null
    }

    private suspend fun refreshMemory() {
        runCatching {
            withContext(Dispatchers.IO) { repository.readSnapshot() }
        }.onSuccess {
            snapshot = it
            zramClearSafety = memoryActions.getClearSafety(it)
            errorMessage = null
        }.onFailure {
            errorMessage = it.message ?: "Unable to read memory information"
        }
    }

    private suspend fun refreshApps() {
        if (appsScanInProgress || rootState != RootState.GRANTED) return
        appsScanInProgress = true
        appsScanError = null
        try {
            val scan = withContext(Dispatchers.IO) { appSwapRepository.readUsage() }
            applyProcessScan(scan)
        } catch (error: Throwable) {
            appsScanError = error.message ?: "Running-app memory information could not be read on this kernel."
        } finally {
            appsScanInProgress = false
        }
    }

    private fun applyProcessScan(scan: ProcessScanResult) {
        runningApps = scan.apps
        unmappedProcesses = scan.unmappedProcesses
        appsInZram = scan.apps
            .filter { it.attributedSwapBytes > 0L }
            .map { it.toAppSwapUsage() }
            .sortedByDescending { it.attributedSwapBytes }
        appsScanError = null
    }

    private fun RunningAppUsage.toAppSwapUsage() = AppSwapUsage(
        packageName = packageName,
        label = label,
        uid = uid,
        attributedSwapBytes = attributedSwapBytes,
        rawSwapBytes = swapBytes,
        residentBytes = residentBytes,
        pssBytes = pssBytes,
        processCount = processCount,
        isSystemApp = isSystemApp,
        processes = processes,
        runningSeconds = runningSeconds,
        cpuTimeSeconds = cpuTimeSeconds,
        proportionalMetricsAvailable = proportionalMetricsAvailable,
        isolatedProcessCount = isolatedProcessCount
    )
}
