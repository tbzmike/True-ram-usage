package com.tbzmike.trueramusage.data

import kotlin.math.max

data class MemoryActionResult(
    val success: Boolean,
    val message: String
)

data class ZramClearSafety(
    val canClear: Boolean,
    val activeZramDevices: Int,
    val zramSwapUsedBytes: Long,
    val requiredAvailableBytes: Long,
    val additionalNeededBytes: Long
)

data class AllSwapClearSafety(
    val canClear: Boolean,
    val activeSwapDevices: Int,
    val swapUsedBytes: Long,
    val requiredAvailableBytes: Long,
    val additionalNeededBytes: Long
)

data class AggressiveKernelReclaimResult(
    val commandCompleted: Boolean,
    val backgroundKillSucceeded: Boolean,
    val cacheDropSucceeded: Boolean,
    val compactionSupported: Boolean,
    val compactionSucceeded: Boolean,
    val message: String
)

data class AllSwapClearResult(
    val success: Boolean,
    val allSwapDisabledAtOnce: Boolean,
    val devicesCleared: Int,
    val failedDevicePaths: List<String>,
    val swappinessRestored: Boolean,
    val message: String
)

class MemoryActions(
    private val rootAccess: RootAccess
) {
    fun getClearSafety(snapshot: MemorySnapshot): ZramClearSafety {
        val devices = snapshot.activeZramSwapDevices
        val zramUsed = devices.sumOf { it.usedBytes }
        val reserve = safetyReserve(snapshot.totalRamBytes)
        val required = zramUsed + reserve
        val additionalNeeded = (required - snapshot.availableRamBytes).coerceAtLeast(0L)
        return ZramClearSafety(
            canClear = rootAccess.isGranted() && devices.isNotEmpty() && additionalNeeded == 0L,
            activeZramDevices = devices.size,
            zramSwapUsedBytes = zramUsed,
            requiredAvailableBytes = required,
            additionalNeededBytes = additionalNeeded
        )
    }

    fun getAllSwapClearSafety(snapshot: MemorySnapshot): AllSwapClearSafety {
        val devices = snapshot.swapDevices
        val swapUsed = devices.sumOf { it.usedBytes }
        val reserve = safetyReserve(snapshot.totalRamBytes)
        val required = swapUsed + reserve
        val additionalNeeded = (required - snapshot.availableRamBytes).coerceAtLeast(0L)
        return AllSwapClearSafety(
            canClear = rootAccess.isGranted() && devices.isNotEmpty() && additionalNeeded == 0L,
            activeSwapDevices = devices.size,
            swapUsedBytes = swapUsed,
            requiredAvailableBytes = required,
            additionalNeededBytes = additionalNeeded
        )
    }

    fun closeApp(packageName: String, isSystemApp: Boolean, ownPackageName: String): MemoryActionResult {
        if (!rootAccess.isGranted()) {
            return MemoryActionResult(false, "Root access is required to close the app and release its RAM/ZRAM pages.")
        }
        if (packageName == ownPackageName) {
            return MemoryActionResult(false, "True RAM Usage cannot force-stop itself while it is measuring memory.")
        }
        if (isSystemApp) {
            return MemoryActionResult(false, "System apps are protected from this action.")
        }
        if (!isSafePackageName(packageName)) {
            return MemoryActionResult(false, "The package name could not be validated safely.")
        }

        val result = rootAccess.runResult(
            "am force-stop --user current ${shellQuote(packageName)}",
            timeoutSeconds = 15
        ) ?: return MemoryActionResult(false, "The root command could not be started.")

        return if (result.success) {
            MemoryActionResult(true, "App closed. Android is releasing its physical-RAM and swapped pages.")
        } else {
            MemoryActionResult(false, "Android did not allow the app to be force-stopped.")
        }
    }

    fun closeAllAppsInZram(apps: List<AppSwapUsage>, ownPackageName: String): MemoryActionResult {
        if (!rootAccess.isGranted()) {
            return MemoryActionResult(false, "Root access is required to close apps using ZRAM.")
        }

        val targets = apps
            .filterNot { it.isSystemApp }
            .map { it.packageName }
            .filter { it != ownPackageName }
            .distinct()

        return closeTargets(
            targets = targets,
            emptyMessage = "No closable user apps with swapped pages were found.",
            successLabel = "user apps that had memory in ZRAM/swap"
        )
    }

    fun closeAllUserApps(apps: List<RunningAppUsage>, ownPackageName: String): MemoryActionResult {
        if (!rootAccess.isGranted()) {
            return MemoryActionResult(false, "Root access is required to close running user apps.")
        }

        val targets = apps
            .filterNot { it.isSystemApp }
            .map { it.packageName }
            .filter { it != ownPackageName }
            .distinct()

        return closeTargets(
            targets = targets,
            emptyMessage = "No closable running user apps were found.",
            successLabel = "running user apps"
        )
    }

    fun reclaimFileCaches(): MemoryActionResult {
        if (!rootAccess.isGranted()) {
            return MemoryActionResult(false, "Root access is required to reclaim kernel/file caches.")
        }

        val result = rootAccess.runResult(
            "sync; if (echo 3 > /proc/sys/vm/drop_caches) 2>/dev/null; then echo OK; else echo DROP_CACHES_FAILED; exit 1; fi",
            timeoutSeconds = 30
        ) ?: return MemoryActionResult(false, "The cache-reclaim command could not be started.")

        return when {
            result.timedOut -> MemoryActionResult(false, "Cache reclaim timed out before the kernel confirmed completion.")
            result.success && result.output.contains("OK") -> MemoryActionResult(
                true,
                "Clean page cache and reclaimable filesystem metadata were requested for release. Anonymous app memory and required kernel memory were not discarded."
            )
            else -> MemoryActionResult(
                false,
                "This kernel or SELinux policy did not allow /proc/sys/vm/drop_caches to be written. No memory-tuning values were changed."
            )
        }
    }

    fun aggressiveKernelReclaim(): AggressiveKernelReclaimResult {
        if (!rootAccess.isGranted()) {
            return AggressiveKernelReclaimResult(
                commandCompleted = false,
                backgroundKillSucceeded = false,
                cacheDropSucceeded = false,
                compactionSupported = false,
                compactionSucceeded = false,
                message = "Root access is required for aggressive RAM reclaim."
            )
        }

        val dollar = '$'
        val command = buildString {
            append("kill_ok=0; cache_ok=0; compact_state=UNAVAILABLE; ")
            append("if am kill-all >/dev/null 2>&1; then kill_ok=1; fi; ")
            append("sync; ")
            append("if (echo 3 > /proc/sys/vm/drop_caches) 2>/dev/null; then cache_ok=1; fi; ")
            append("if [ -e /proc/sys/vm/compact_memory ]; then compact_state=FAILED; ")
            append("if (echo 1 > /proc/sys/vm/compact_memory) 2>/dev/null; then compact_state=OK; fi; fi; ")
            append("printf 'KILL=%s CACHE=%s COMPACT=%s\\n' \"${dollar}kill_ok\" \"${dollar}cache_ok\" \"${dollar}compact_state\"")
        }

        val result = rootAccess.runResult(command, timeoutSeconds = 60)
            ?: return AggressiveKernelReclaimResult(
                commandCompleted = false,
                backgroundKillSucceeded = false,
                cacheDropSucceeded = false,
                compactionSupported = false,
                compactionSucceeded = false,
                message = "The aggressive kernel reclaim command could not be started."
            )

        if (result.timedOut) {
            return AggressiveKernelReclaimResult(
                commandCompleted = false,
                backgroundKillSucceeded = false,
                cacheDropSucceeded = false,
                compactionSupported = false,
                compactionSucceeded = false,
                message = "Aggressive kernel reclaim timed out. Memory readings must be refreshed before trying again."
            )
        }

        val killSucceeded = Regex("KILL=(\\d)").find(result.output)?.groupValues?.getOrNull(1) == "1"
        val cacheSucceeded = Regex("CACHE=(\\d)").find(result.output)?.groupValues?.getOrNull(1) == "1"
        val compactState = Regex("COMPACT=([A-Z]+)").find(result.output)?.groupValues?.getOrNull(1) ?: "UNAVAILABLE"
        val compactSupported = compactState != "UNAVAILABLE"
        val compactSucceeded = compactState == "OK"

        val parts = buildList {
            add(if (killSucceeded) "background processes killed" else "background-process kill was not confirmed")
            add(if (cacheSucceeded) "clean caches dropped" else "drop_caches was not permitted")
            add(
                when {
                    !compactSupported -> "kernel compaction interface unavailable"
                    compactSucceeded -> "memory compaction requested"
                    else -> "memory compaction was not permitted"
                }
            )
        }

        return AggressiveKernelReclaimResult(
            commandCompleted = result.success,
            backgroundKillSucceeded = killSucceeded,
            cacheDropSucceeded = cacheSucceeded,
            compactionSupported = compactSupported,
            compactionSucceeded = compactSucceeded,
            message = parts.joinToString("; ").replaceFirstChar { it.uppercase() } + "."
        )
    }

    fun clearKernelZram(snapshot: MemorySnapshot): MemoryActionResult {
        if (!rootAccess.isGranted()) {
            return MemoryActionResult(false, "Root access is required to clear kernel ZRAM.")
        }
        val safety = getClearSafety(snapshot)
        if (safety.activeZramDevices == 0) {
            return MemoryActionResult(false, "No active kernel ZRAM swap device was found.")
        }
        if (!safety.canClear) {
            return MemoryActionResult(
                false,
                "The safety estimate does not show enough available physical RAM to cycle ZRAM. Close apps or reclaim caches first."
            )
        }

        for (device in snapshot.activeZramSwapDevices) {
            if (!isSafeZramPath(device.path)) {
                return MemoryActionResult(false, "The active ZRAM device path could not be validated safely.")
            }
            val priority = device.priority ?: -1
            val path = shellQuote(device.path)
            val command = "if swapoff $path; then " +
                "if swapon -p $priority $path 2>/dev/null || swapon $path 2>/dev/null; then " +
                "echo OK; else echo REENABLE_FAILED; exit 2; fi; " +
                "else echo SWAPOFF_FAILED; exit 1; fi"
            val result = rootAccess.runResult(command, timeoutSeconds = 90)
                ?: return MemoryActionResult(false, "The root command could not be started.")
            if (!result.success) {
                val message = when {
                    result.timedOut -> "Clearing ZRAM timed out. Check that the ZRAM swap device is still active."
                    result.output.contains("REENABLE_FAILED") -> "ZRAM was disabled but could not be re-enabled automatically. Reboot the phone before continuing."
                    else -> "The kernel refused to clear ZRAM. No reset or resize operation was attempted."
                }
                return MemoryActionResult(false, message)
            }
        }

        return MemoryActionResult(
            true,
            "Kernel ZRAM was cycled successfully. Android may begin swapping inactive pages into it again immediately."
        )
    }

    fun clearAllSwap(snapshot: MemorySnapshot): AllSwapClearResult {
        if (!rootAccess.isGranted()) {
            return AllSwapClearResult(false, false, 0, emptyList(), true, "Root access is required to clear all swap/ZRAM.")
        }

        val safety = getAllSwapClearSafety(snapshot)
        if (safety.activeSwapDevices == 0) {
            return AllSwapClearResult(true, false, 0, emptyList(), true, "No active swap or ZRAM device needed clearing.")
        }
        if (!safety.canClear) {
            return AllSwapClearResult(
                false,
                false,
                0,
                emptyList(),
                true,
                "All-swap clear was blocked because the current MemAvailable estimate is ${safety.additionalNeededBytes} bytes below the required safety headroom."
            )
        }

        val devices = snapshot.swapDevices.sortedBy { it.usedBytes }
        if (devices.any { !isSafeSwapPath(it.path) }) {
            return AllSwapClearResult(false, false, 0, emptyList(), true, "One or more active swap paths could not be validated safely.")
        }

        val originalSwappiness = snapshot.vmStats.swappiness
        val swappinessTemporarilyLowered = originalSwappiness != null && rootAccess.runResult(
            "echo 0 > /proc/sys/vm/swappiness",
            timeoutSeconds = 10
        )?.success == true

        val disabled = mutableListOf<SwapDevice>()
        var disableFailure: SwapDevice? = null
        for (device in devices) {
            val result = rootAccess.runResult("swapoff ${shellQuote(device.path)}", timeoutSeconds = 180)
            if (result?.success == true) {
                disabled += device
            } else {
                disableFailure = device
                break
            }
        }

        if (disableFailure != null) {
            val failedReenable = reenableSwapDevices(disabled)
            val restored = restoreSwappiness(originalSwappiness, swappinessTemporarilyLowered)
            return AllSwapClearResult(
                success = false,
                allSwapDisabledAtOnce = false,
                devicesCleared = disabled.size,
                failedDevicePaths = listOf(disableFailure.path) + failedReenable,
                swappinessRestored = restored,
                message = if (failedReenable.isEmpty()) {
                    "The kernel refused or timed out while disabling ${disableFailure.path}; already-disabled swap devices were re-enabled."
                } else {
                    "Swapoff failed and one or more previously disabled swap devices could not be re-enabled. A reboot is recommended before further memory actions."
                }
            )
        }

        val failedReenable = reenableSwapDevices(devices)
        val restored = restoreSwappiness(originalSwappiness, swappinessTemporarilyLowered)
        if (failedReenable.isNotEmpty()) {
            return AllSwapClearResult(
                success = false,
                allSwapDisabledAtOnce = true,
                devicesCleared = devices.size,
                failedDevicePaths = failedReenable,
                swappinessRestored = restored,
                message = "All swap was disabled, but ${failedReenable.size} swap device(s) could not be re-enabled. Reboot is recommended before continuing."
            )
        }
        if (!restored) {
            return AllSwapClearResult(
                success = false,
                allSwapDisabledAtOnce = true,
                devicesCleared = devices.size,
                failedDevicePaths = emptyList(),
                swappinessRestored = false,
                message = "All swap was cycled, but the original swappiness value could not be restored. Reboot or restore swappiness manually before continuing."
            )
        }

        return AllSwapClearResult(
            success = true,
            allSwapDisabledAtOnce = true,
            devicesCleared = devices.size,
            failedDevicePaths = emptyList(),
            swappinessRestored = true,
            message = "All ${devices.size} active swap/ZRAM device(s) were disabled together and re-enabled with their recorded priorities. Final readings will verify how much swap remained after Android resumed."
        )
    }

    private fun reenableSwapDevices(devices: List<SwapDevice>): List<String> {
        val failures = mutableListOf<String>()
        devices.sortedByDescending { it.priority ?: Int.MIN_VALUE }.forEach { device ->
            val path = shellQuote(device.path)
            val priority = device.priority
            val command = if (priority != null) {
                "swapon -p $priority $path 2>/dev/null || swapon $path 2>/dev/null"
            } else {
                "swapon $path 2>/dev/null"
            }
            if (rootAccess.runResult(command, timeoutSeconds = 30)?.success != true) {
                failures += device.path
            }
        }
        return failures
    }

    private fun restoreSwappiness(originalSwappiness: Int?, wasLowered: Boolean): Boolean {
        if (!wasLowered || originalSwappiness == null) return true
        return rootAccess.runResult(
            "echo $originalSwappiness > /proc/sys/vm/swappiness",
            timeoutSeconds = 10
        )?.success == true
    }

    private fun closeTargets(
        targets: List<String>,
        emptyMessage: String,
        successLabel: String
    ): MemoryActionResult {
        if (targets.isEmpty()) return MemoryActionResult(false, emptyMessage)
        if (targets.any { !isSafePackageName(it) }) {
            return MemoryActionResult(false, "One or more package names could not be validated safely.")
        }

        val dollar = '$'
        val command = buildString {
            append("failed=0; closed=0; ")
            targets.forEach { packageName ->
                append("if am force-stop --user current ")
                append(shellQuote(packageName))
                append(" >/dev/null 2>&1; then closed=${dollar}((closed+1)); else failed=${dollar}((failed+1)); fi; ")
            }
            append("printf 'CLOSED=%s FAILED=%s\\n' \"${dollar}closed\" \"${dollar}failed\"")
        }

        val result = rootAccess.runResult(command, timeoutSeconds = 60)
            ?: return MemoryActionResult(false, "The bulk close command could not be started.")
        if (result.timedOut) {
            return MemoryActionResult(false, "Closing apps timed out before Android finished processing them.")
        }

        val closed = Regex("CLOSED=(\\d+)").find(result.output)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val failed = Regex("FAILED=(\\d+)").find(result.output)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

        return when {
            closed > 0 && failed == 0 -> MemoryActionResult(true, "Closed $closed $successLabel.")
            closed > 0 -> MemoryActionResult(true, "Closed $closed $successLabel; $failed could not be force-stopped.")
            else -> MemoryActionResult(false, "Android did not close any of the selected apps.")
        }
    }

    private fun safetyReserve(totalRamBytes: Long): Long =
        max(512L * 1024L * 1024L, totalRamBytes / 10L)

    private fun isSafePackageName(packageName: String): Boolean =
        packageName.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))

    private fun isSafeZramPath(path: String): Boolean =
        path.startsWith("/dev/") &&
            path.substringAfterLast('/').matches(Regex("zram\\d+")) &&
            path.none { it.isWhitespace() }

    private fun isSafeSwapPath(path: String): Boolean =
        path.startsWith('/') &&
            path.length in 2..4095 &&
            path.none { it == '\n' || it == '\r' || it == '\u0000' }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
