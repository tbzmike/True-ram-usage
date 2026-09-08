package com.tbzmike.trueramusage.data

data class MemorySnapshot(
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val usedRamBytes: Long,
    val totalSwapBytes: Long,
    val usedSwapBytes: Long,
    val swapDevices: List<SwapDevice>,
    val zramDevices: List<ZramDevice>,
    val vmStats: VmStats,
    val pressure: MemoryPressure?,
    val breakdown: SystemMemoryBreakdown
) {
    val kernelZramSwapUsedBytes: Long
        get() = swapDevices.filter { it.isZram }.sumOf { it.usedBytes }

    val activeZramSwapDevices: List<SwapDevice>
        get() = swapDevices.filter { it.isZram }

    val onlyKernelZramActive: Boolean
        get() = swapDevices.isNotEmpty() && swapDevices.all { it.isZram }

    val zramPhysicalRamBytes: Long
        get() = zramDevices.sumOf { it.memoryUsedBytes }
}

data class SystemMemoryBreakdown(
    val freeBytes: Long,
    val buffersBytes: Long,
    val cachedBytes: Long,
    val swapCachedBytes: Long,
    val anonymousBytes: Long,
    val shmemBytes: Long,
    val slabBytes: Long,
    val reclaimableSlabBytes: Long,
    val unreclaimableSlabBytes: Long,
    val kernelStackBytes: Long,
    val pageTablesBytes: Long,
    val unevictableBytes: Long,
    val mlockedBytes: Long,
    val dirtyBytes: Long,
    val writebackBytes: Long
)

data class SwapDevice(
    val path: String,
    val type: String,
    val sizeBytes: Long,
    val usedBytes: Long,
    val priority: Int?
) {
    val isZram: Boolean
        get() {
            val normalized = path.lowercase()
            val fileName = normalized.substringAfterLast('/')
                .substringBefore('(')
                .trim()
            return fileName.matches(Regex("zram\\d+")) ||
                Regex("(^|/)zram\\d+($|/)").containsMatchIn(normalized)
        }
}

data class ZramDevice(
    val name: String,
    val diskSizeBytes: Long,
    val originalDataBytes: Long,
    val compressedDataBytes: Long,
    val memoryUsedBytes: Long,
    val memoryLimitBytes: Long,
    val peakMemoryUsedBytes: Long,
    val samePages: Long,
    val compactedPages: Long,
    val hugePages: Long,
    val compressionAlgorithm: String?
) {
    val ramSavedBytes: Long
        get() = (originalDataBytes - memoryUsedBytes).coerceAtLeast(0)

    val compressionRatio: Double?
        get() = if (compressedDataBytes > 0) originalDataBytes.toDouble() / compressedDataBytes else null

    val effectiveRamRatio: Double?
        get() = if (memoryUsedBytes > 0) originalDataBytes.toDouble() / memoryUsedBytes else null
}

data class VmStats(
    val swappiness: Int?,
    val swapInPages: Long?,
    val swapOutPages: Long?
)

data class MemoryPressure(
    val someAvg10: Double?,
    val fullAvg10: Double?
)

data class ProcessSwapUsage(
    val pid: Int,
    val uid: Int,
    val processName: String,
    val swapBytes: Long,
    val swapPssBytes: Long,
    val rssBytes: Long,
    val pssBytes: Long,
    val runningSeconds: Long = 0L,
    val cpuTimeSeconds: Double = 0.0,
    val proportionalMetricsAvailable: Boolean = false,
    val mappedFromProcessName: Boolean = false
) {
    val attributedSwapBytes: Long
        get() = if (proportionalMetricsAvailable) swapPssBytes else swapBytes

    val attributedRamBytes: Long
        get() = if (proportionalMetricsAvailable) pssBytes else rssBytes
}

data class AppSwapUsage(
    val packageName: String,
    val label: String,
    val uid: Int,
    val attributedSwapBytes: Long,
    val rawSwapBytes: Long,
    val residentBytes: Long,
    val pssBytes: Long,
    val processCount: Int,
    val isSystemApp: Boolean,
    val processes: List<ProcessSwapUsage>,
    val runningSeconds: Long = 0L,
    val cpuTimeSeconds: Double = 0.0,
    val proportionalMetricsAvailable: Boolean = false,
    val isolatedProcessCount: Int = 0
) {
    val attributedRamBytes: Long
        get() = processes.sumOf { it.attributedRamBytes }
}

data class RunningAppUsage(
    val packageName: String,
    val label: String,
    val uid: Int,
    val residentBytes: Long,
    val pssBytes: Long,
    val swapBytes: Long,
    val swapPssBytes: Long,
    val processCount: Int,
    val isSystemApp: Boolean,
    val runningSeconds: Long,
    val cpuTimeSeconds: Double,
    val proportionalMetricsAvailable: Boolean,
    val isolatedProcessCount: Int,
    val processes: List<ProcessSwapUsage>
) {
    val attributedRamBytes: Long
        get() = processes.sumOf { it.attributedRamBytes }

    val attributedSwapBytes: Long
        get() = processes.sumOf { it.attributedSwapBytes }
}

data class UnmappedProcessUsage(
    val pid: Int,
    val uid: Int,
    val processName: String,
    val residentBytes: Long,
    val pssBytes: Long,
    val swapBytes: Long,
    val swapPssBytes: Long,
    val proportionalMetricsAvailable: Boolean,
    val runningSeconds: Long,
    val cpuTimeSeconds: Double
) {
    val attributedRamBytes: Long
        get() = if (proportionalMetricsAvailable) pssBytes else residentBytes

    val attributedSwapBytes: Long
        get() = if (proportionalMetricsAvailable) swapPssBytes else swapBytes
}

data class ProcessScanResult(
    val apps: List<RunningAppUsage>,
    val unmappedProcesses: List<UnmappedProcessUsage>
)
