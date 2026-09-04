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
    val pressure: MemoryPressure?
)

data class SwapDevice(
    val path: String,
    val type: String,
    val sizeBytes: Long,
    val usedBytes: Long,
    val priority: Int?
)

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
