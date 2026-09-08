package com.tbzmike.trueramusage.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tbzmike.trueramusage.data.AppPreferences
import com.tbzmike.trueramusage.data.AppSwapRepository
import com.tbzmike.trueramusage.data.AppSwapUsage
import com.tbzmike.trueramusage.data.DisplayMode
import com.tbzmike.trueramusage.data.MemoryActions
import com.tbzmike.trueramusage.data.MemoryRepository
import com.tbzmike.trueramusage.data.MemorySnapshot
import com.tbzmike.trueramusage.data.RootAccess
import com.tbzmike.trueramusage.data.RootState
import com.tbzmike.trueramusage.data.RunningAppUsage
import com.tbzmike.trueramusage.data.ThemeMode
import com.tbzmike.trueramusage.data.UnmappedProcessUsage
import com.tbzmike.trueramusage.data.ZramClearSafety
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MemoryViewModel(application: Application) : AndroidViewModel(application) {
    private val rootAccess = RootAccess()
    private val repository = MemoryRepository(rootAccess)
    private val appSwapRepository = AppSwapRepository(application, rootAccess)
    private val memoryActions = MemoryActions(rootAccess)
    private val preferences = AppPreferences(application)
    private val ownPackageName = application.packageName

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

    init {
        viewModelScope.launch {
            while (isActive) {
                refreshMemory()
                delay(2_000)
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
            runningApps = scan.apps
            unmappedProcesses = scan.unmappedProcesses
            appsInZram = scan.apps
                .filter { it.attributedSwapBytes > 0L }
                .map { it.toAppSwapUsage() }
                .sortedByDescending { it.attributedSwapBytes }
        } catch (error: Throwable) {
            appsScanError = error.message ?: "Running-app memory information could not be read on this kernel."
        } finally {
            appsScanInProgress = false
        }
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
