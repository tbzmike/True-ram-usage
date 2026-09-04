package com.tbzmike.trueramusage.data

import android.content.Context
import android.content.pm.ApplicationInfo

class AppSwapRepository(
    context: Context,
    private val rootAccess: RootAccess
) {
    private val packageManager = context.packageManager

    fun readAppsUsingSwap(): List<AppSwapUsage> {
        check(rootAccess.isGranted()) { "Root access is required for per-app swap attribution." }

        val processes = readProcessSwapUsage()
        if (processes.isEmpty()) return emptyList()

        val packagesByUid = mutableMapOf<Int, List<String>>()

        return processes
            .mapNotNull { process -> resolveProcess(process, packagesByUid) }
            .groupBy { it.packageName }
            .map { (packageName, resolved) ->
                val appInfo = resolved.first().appInfo
                val processList = resolved.map { it.process }
                AppSwapUsage(
                    packageName = packageName,
                    label = runCatching { appInfo.loadLabel(packageManager).toString() }
                        .getOrDefault(packageName),
                    uid = appInfo.uid,
                    attributedSwapBytes = processList.sumOf { it.swapBytes },
                    rawSwapBytes = processList.sumOf { it.swapBytes },
                    residentBytes = processList.sumOf { it.rssBytes },
                    pssBytes = 0L,
                    processCount = processList.size,
                    isSystemApp = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    processes = processList.sortedByDescending { it.swapBytes }
                )
            }
            .filter { it.attributedSwapBytes > 0L }
            .sortedByDescending { it.attributedSwapBytes }
    }

    private fun resolveProcess(
        process: ProcessSwapUsage,
        packagesByUid: MutableMap<Int, List<String>>
    ): ResolvedProcess? {
        // Android app process cmdlines normally begin with the package name. Prefer
        // that direct mapping, but verify the UID so a process name can never be
        // used to attribute another application's memory.
        val directPackage = process.processName.substringBefore(':')
        if (directPackage.isNotBlank()) {
            val directInfo = getApplicationInfo(directPackage)
            if (directInfo != null && directInfo.uid == process.uid) {
                return ResolvedProcess(process, directPackage, directInfo)
            }
        }

        val packages = packagesByUid.getOrPut(process.uid) {
            runCatching { packageManager.getPackagesForUid(process.uid)?.toList().orEmpty() }
                .getOrDefault(emptyList())
        }
        if (packages.isEmpty()) return null

        val packageName = when {
            packages.size == 1 -> packages.single()
            process.processName.isNotBlank() -> packages.firstOrNull { candidate ->
                process.processName == candidate || process.processName.startsWith("$candidate:")
            }
            else -> null
        } ?: return null

        val appInfo = getApplicationInfo(packageName) ?: return null
        if (appInfo.uid != process.uid) return null
        return ResolvedProcess(process, packageName, appInfo)
    }

    @Suppress("DEPRECATION")
    private fun getApplicationInfo(packageName: String): ApplicationInfo? = runCatching {
        packageManager.getApplicationInfo(packageName, 0)
    }.getOrNull()

    private fun readProcessSwapUsage(): List<ProcessSwapUsage> {
        // One root command reads only the inexpensive status fields for all PIDs.
        // Do not walk smaps/smaps_rollup here: those files can be very expensive on
        // Android processes with many mappings.
        val statusResult = rootAccess.runResult(
            "grep -H -e '^Uid:' -e '^VmRSS:' -e '^VmSwap:' /proc/[0-9]*/status 2>/dev/null || true",
            timeoutSeconds = 8
        ) ?: error("The root process scanner could not be started.")

        if (statusResult.timedOut) {
            error("The per-app swap scan timed out while reading process status files.")
        }
        if (!statusResult.success) {
            error("The kernel process status scan failed.")
        }

        val rawProcesses = parseStatusOutput(statusResult.output)
            .filter { it.swapKb > 0L }

        if (rawProcesses.isEmpty()) return emptyList()

        val names = readProcessNames(rawProcesses.map { it.pid })

        return rawProcesses.map { raw ->
            ProcessSwapUsage(
                pid = raw.pid,
                uid = raw.uid,
                processName = names[raw.pid].orEmpty(),
                swapBytes = raw.swapKb * 1024L,
                swapPssBytes = 0L,
                rssBytes = raw.rssKb * 1024L,
                pssBytes = 0L
            )
        }
    }

    private fun parseStatusOutput(output: String): List<RawProcessStatus> {
        val linePattern = Regex("^/proc/(\\d+)/status:(Uid|VmRSS|VmSwap):\\s*(.*)$")
        val byPid = linkedMapOf<Int, MutableProcessStatus>()

        output.lineSequence().forEach { rawLine ->
            val match = linePattern.matchEntire(rawLine.trim()) ?: return@forEach
            val pid = match.groupValues[1].toIntOrNull() ?: return@forEach
            val field = match.groupValues[2]

            // /proc/<pid>/status uses arbitrary whitespace (normally tabs on Uid:).
            // Reading the first digit run is therefore safer than splitting only on
            // ASCII spaces.
            val firstNumber = Regex("^\\s*(\\d+)")
                .find(match.groupValues[3])
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?: return@forEach

            val current = byPid.getOrPut(pid) { MutableProcessStatus() }
            when (field) {
                "Uid" -> current.uid = firstNumber.toInt()
                "VmRSS" -> current.rssKb = firstNumber
                "VmSwap" -> current.swapKb = firstNumber
            }
        }

        return byPid.mapNotNull { (pid, fields) ->
            val uid = fields.uid ?: return@mapNotNull null
            RawProcessStatus(
                pid = pid,
                uid = uid,
                swapKb = fields.swapKb,
                rssKb = fields.rssKb
            )
        }
    }

    private fun readProcessNames(pids: List<Int>): Map<Int, String> {
        if (pids.isEmpty()) return emptyMap()

        val pidList = pids.distinct().joinToString(" ")
        val dollar = '$'
        val script =
            "for pid in $pidList; do " +
                "[ -r /proc/${dollar}pid/cmdline ] || continue; " +
                "name=\$(tr '\\000' ' ' < /proc/${dollar}pid/cmdline 2>/dev/null | cut -d' ' -f1); " +
                "printf '%s\\t%s\\n' \"${dollar}pid\" \"${dollar}name\"; " +
                "done"

        val result = rootAccess.runResult(script, timeoutSeconds = 4) ?: return emptyMap()
        if (!result.success) return emptyMap()

        return result.output.lineSequence()
            .mapNotNull { line ->
                val parts = line.split('\t', limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val pid = parts[0].toIntOrNull() ?: return@mapNotNull null
                pid to parts[1].trim()
            }
            .toMap()
    }

    private data class MutableProcessStatus(
        var uid: Int? = null,
        var swapKb: Long = 0L,
        var rssKb: Long = 0L
    )

    private data class RawProcessStatus(
        val pid: Int,
        val uid: Int,
        val swapKb: Long,
        val rssKb: Long
    )

    private data class ResolvedProcess(
        val process: ProcessSwapUsage,
        val packageName: String,
        val appInfo: ApplicationInfo
    )
}
