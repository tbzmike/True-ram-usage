package com.tbzmike.trueramusage.data

import java.io.File

class MemoryRepository(
    private val rootAccess: RootAccess
) {
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
            zramDevices = readZramDevices(),
            vmStats = readVmStats(),
            pressure = readPressure()
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

    private fun readSwapDevices(): List<SwapDevice> {
        val text = readText(File("/proc/swaps")) ?: return emptyList()
        return text.lineSequence()
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
            .toList()
    }

    private fun readZramDevices(): List<ZramDevice> {
        val names = readZramNames()
        return names.mapNotNull(::readZramDevice)
    }

    private fun readZramNames(): List<String> {
        val direct = runCatching {
            File("/sys/block").listFiles()
                ?.map { it.name }
                ?.filter { it.matches(Regex("zram\\d+")) }
                ?.sorted()
                .orEmpty()
        }.getOrDefault(emptyList())
        if (direct.isNotEmpty()) return direct
        if (!rootAccess.isGranted()) return emptyList()
        val dollar = '$'
        return rootAccess.run("for d in /sys/block/zram*; do [ -e \"${dollar}d\" ] && basename \"${dollar}d\"; done")
            ?.lineSequence()
            ?.map(String::trim)
            ?.filter { it.matches(Regex("zram\\d+")) }
            ?.sorted()
            ?.toList()
            .orEmpty()
    }

    private fun readZramDevice(name: String): ZramDevice? {
        if (!name.matches(Regex("zram\\d+"))) return null
        val base = File("/sys/block/$name")
        val mmText = readText(File(base, "mm_stat"))
        val diskSizeText = readText(File(base, "disksize"))
        val algorithmText = readText(File(base, "comp_algorithm"))

        val rootedBundle = if ((mmText == null || diskSizeText == null) && rootAccess.isGranted()) {
            rootAccess.run(
                "printf 'DISKSIZE='; cat /sys/block/$name/disksize 2>/dev/null; " +
                    "printf 'MMSTAT='; cat /sys/block/$name/mm_stat 2>/dev/null; " +
                    "printf 'ALGO='; cat /sys/block/$name/comp_algorithm 2>/dev/null"
            )
        } else null

        val rootedValues = rootedBundle
            ?.lineSequence()
            ?.mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1).trim()
            }
            ?.toMap()
            .orEmpty()

        val mmStat = parseLongList(mmText ?: rootedValues["MMSTAT"])
        if (mmStat.size < 3) return null

        return ZramDevice(
            name = name,
            diskSizeBytes = (diskSizeText ?: rootedValues["DISKSIZE"])?.trim()?.toLongOrNull() ?: 0L,
            originalDataBytes = mmStat.getOrElse(0) { 0L },
            compressedDataBytes = mmStat.getOrElse(1) { 0L },
            memoryUsedBytes = mmStat.getOrElse(2) { 0L },
            memoryLimitBytes = mmStat.getOrElse(3) { 0L },
            peakMemoryUsedBytes = mmStat.getOrElse(4) { 0L },
            samePages = mmStat.getOrElse(5) { 0L },
            compactedPages = mmStat.getOrElse(6) { 0L },
            hugePages = mmStat.getOrElse(7) { 0L },
            compressionAlgorithm = readActiveAlgorithm(algorithmText ?: rootedValues["ALGO"])
        )
    }

    private fun readVmStats(): VmStats {
        val swappiness = readText(File("/proc/sys/vm/swappiness"))
            ?.trim()
            ?.toIntOrNull()
            ?: rootAccess.run("cat /proc/sys/vm/swappiness 2>/dev/null")?.trim()?.toIntOrNull()

        val vmText = readText(File("/proc/vmstat"))
            ?: rootAccess.run("cat /proc/vmstat 2>/dev/null")
        val vm = vmText
            ?.lineSequence()
            ?.mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size == 2) parts[0] to parts[1].toLongOrNull() else null
            }
            ?.filter { it.second != null }
            ?.associate { it.first to it.second!! }
            .orEmpty()

        return VmStats(
            swappiness = swappiness,
            swapInPages = vm["pswpin"],
            swapOutPages = vm["pswpout"]
        )
    }

    private fun readPressure(): MemoryPressure? {
        val text = readText(File("/proc/pressure/memory"))
            ?: rootAccess.run("cat /proc/pressure/memory 2>/dev/null")
            ?: return null
        fun avg10(prefix: String): Double? = text.lineSequence()
            .firstOrNull { it.startsWith(prefix) }
            ?.split(Regex("\\s+"))
            ?.firstOrNull { it.startsWith("avg10=") }
            ?.substringAfter('=')
            ?.toDoubleOrNull()
        return MemoryPressure(
            someAvg10 = avg10("some "),
            fullAvg10 = avg10("full ")
        )
    }

    private fun readText(file: File): String? = runCatching {
        file.readText().trim()
    }.getOrNull()

    private fun parseLongList(text: String?): List<Long> = text
        ?.trim()
        ?.split(Regex("\\s+"))
        ?.mapNotNull(String::toLongOrNull)
        .orEmpty()

    private fun readActiveAlgorithm(text: String?): String? {
        val clean = text?.trim().orEmpty()
        if (clean.isEmpty()) return null
        return Regex("\\[([^]]+)]").find(clean)?.groupValues?.getOrNull(1)
            ?: clean.split(Regex("\\s+")).firstOrNull()
    }
}
