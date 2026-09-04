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

class MemoryActions(
    private val rootAccess: RootAccess
) {
    fun getClearSafety(snapshot: MemorySnapshot): ZramClearSafety {
        val devices = snapshot.activeZramSwapDevices
        val zramUsed = devices.sumOf { it.usedBytes }
        val reserve = max(512L * 1024L * 1024L, snapshot.totalRamBytes / 10L)
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

    fun closeApp(packageName: String, isSystemApp: Boolean): MemoryActionResult {
        if (!rootAccess.isGranted()) {
            return MemoryActionResult(false, "Root access is required to close the app and release its ZRAM pages.")
        }
        if (isSystemApp) {
            return MemoryActionResult(false, "System apps are protected from this action.")
        }
        if (!packageName.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))) {
            return MemoryActionResult(false, "The package name could not be validated safely.")
        }

        val result = rootAccess.runResult(
            "am force-stop --user current ${shellQuote(packageName)}",
            timeoutSeconds = 15
        ) ?: return MemoryActionResult(false, "The root command could not be started.")

        return if (result.success) {
            MemoryActionResult(true, "App closed. Its swapped pages are being released from kernel ZRAM.")
        } else {
            MemoryActionResult(false, "Android did not allow the app to be force-stopped.")
        }
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
                "Not enough available physical RAM to clear ZRAM safely. Free more RAM first."
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

    private fun isSafeZramPath(path: String): Boolean =
        path.startsWith("/dev/") &&
            path.substringAfterLast('/').matches(Regex("zram\\d+")) &&
            path.none { it.isWhitespace() }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
