package com.tbzmike.trueramusage.data

import java.io.File

class MemoryRepository {
    fun readSnapshot(): MemorySnapshot {
        val memInfo = readMemInfo()
        val totalRam = memInfo["MemTotal"] ?: 0L
        val availableRam = memInfo["MemAvailable"]
            ?: ((memInfo["MemFree"] ?: 0L) + (memInfo["Buffers"] ?: 0L) + (memInfo["Cached"] ?: 0L))
        val usedRam = (totalRam - availableRam).coerceAtLeast(0L)
        val totalSwap = memInfo["SwapTotal"] ?: 0L
        val freeSwap = memInfo["SwapFree"] ?: 0L
        val usedSwap = (totalSwap - freeSwap).coerceAtLeast(0L)

        return MemorySnapshot(
            totalRamBytes = totalRam,
            availableRamBytes = availableRam,
            usedRamBytes = usedRam,
            totalSwapBytes = totalSwap,
            usedSwapBytes = usedSwap,
            swapDevices = readSwapDevices(),
            zramDevices = readZramDevices()
        )
    }

    private fun readMemInfo(): Map<String, Long> = runCatching {
        File("/proc/meminfo").useLines { lines ->
            lines.mapNotNull { line ->
                val key = line.substringBefore(':').trim()
                val valueKb = line.substringAfter(':', "")
                    .trim()
                    .substringBefore(' ')
                    .toLongOrNull()
                if (valueKb == null) null else key to valueKb * 1024L
            }.toMap()
        }
    }.getOrDefault(emptyMap())

    private fun readSwapDevices(): List<SwapDevice> = runCatching {
        File("/proc/swaps").readLines()
            .drop(1)
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 5) return@mapNotNull null
                SwapDevice(
                    path = parts[0],
                    type = parts[1],
                    sizeBytes = (parts[2].toLongOrNull() ?: return@mapNotNull null) * 1024L,
                    usedBytes = (parts[3].toLongOrNull() ?: return@mapNotNull null) * 1024L,
                    priority = parts[4].toIntOrNull()
                )
            }
    }.getOrDefault(emptyList())

    private fun readZramDevices(): List<ZramDevice> {
        val sysBlock = File("/sys/block")
        val candidates = runCatching {
            sysBlock.listFiles()
                ?.filter { it.name.startsWith("zram") }
                ?.sortedBy { it.name }
                .orEmpty()
        }.getOrDefault(emptyList())

        return candidates.mapNotNull { dir -> readZramDevice(dir) }
    }

    private fun readZramDevice(dir: File): ZramDevice? = runCatching {
        val mmStat = readLongList(File(dir, "mm_stat"))
        ZramDevice(
            name = dir.name,
            diskSizeBytes = readLong(File(dir, "disksize")),
            originalDataBytes = mmStat.getOrElse(0) { 0L },
            compressedDataBytes = mmStat.getOrElse(1) { 0L },
            memoryUsedBytes = mmStat.getOrElse(2) { 0L },
            memoryLimitBytes = mmStat.getOrElse(3) { 0L },
            peakMemoryUsedBytes = mmStat.getOrElse(4) { 0L },
            samePages = mmStat.getOrElse(5) { 0L },
            compactedPages = mmStat.getOrElse(7) { 0L },
            compressionAlgorithm = readActiveAlgorithm(File(dir, "comp_algorithm"))
        )
    }.getOrNull()

    private fun readLong(file: File): Long = runCatching {
        file.readText().trim().toLong()
    }.getOrDefault(0L)

    private fun readLongList(file: File): List<Long> = runCatching {
        file.readText().trim().split(Regex("\\s+")).mapNotNull(String::toLongOrNull)
    }.getOrDefault(emptyList())

    private fun readActiveAlgorithm(file: File): String? = runCatching {
        val text = file.readText().trim()
        Regex("\\[([^]]+)]").find(text)?.groupValues?.getOrNull(1)
            ?: text.split(Regex("\\s+")).firstOrNull()
    }.getOrNull()
}
