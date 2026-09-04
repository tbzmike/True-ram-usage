package com.tbzmike.trueramusage.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.system.Os
import android.system.OsConstants
import java.io.File

class AppSwapRepository(
    context: Context,
    private val rootAccess: RootAccess
) {
    private val packageManager = context.packageManager
    private val clockTicksPerSecond: Long = runCatching {
        Os.sysconf(OsConstants._SC_CLK_TCK)
    }.getOrDefault(0L).coerceAtLeast(0L)

    fun readRunningApps(): List<RunningAppUsage> {
        check(rootAccess.isGranted()) { "Root access is required for running-app memory details." }

        val rawStatuses = readAllProcessStatuses()
        if (rawStatuses.isEmpty()) return emptyList()

        val packagesByUid = mutableMapOf<Int, List<String>>()
        val packageInfoCache = mutableMapOf<String, ApplicationInfo?>()

        val ambiguousPids = rawStatuses.filter { raw ->
            val packages = packagesByUid.getOrPut(raw.uid) { packagesForUid(raw.uid) }
            packages.size > 1
        }.map { it.pid }
        val ambiguousNames = readProcessNames(ambiguousPids)

        val resolved = rawStatuses.mapNotNull { raw ->
            val packages = packagesByUid.getOrPut(raw.uid) { packagesForUid(raw.uid) }
            if (packages.isEmpty()) return@mapNotNull null

            val packageName = when {
                packages.size == 1 -> packages.single()
                else -> {
                    val processName = ambiguousNames[raw.pid].orEmpty()
                    packages.firstOrNull { candidate ->
                        processName == candidate || processName.startsWith("$candidate:")
                    }
                }
            } ?: return@mapNotNull null

            val appInfo = packageInfoCache.getOrPut(packageName) { getApplicationInfo(packageName) }
                ?: return@mapNotNull null
            if (appInfo.uid != raw.uid) return@mapNotNull null

            ResolvedRawProcess(raw, packageName, appInfo)
        }

        if (resolved.isEmpty()) return emptyList()

        val statsByPid = readProcessStats(resolved.map { it.raw.pid })
        val uptimeSeconds = readUptimeSeconds()

        val processRows = resolved.map { item ->
            val stats = statsByPid[item.raw.pid]
            val timing = calculateTiming(stats, uptimeSeconds)
            ResolvedProcess(
                packageName = item.packageName,
                appInfo = item.appInfo,
                process = ProcessSwapUsage(
                    pid = item.raw.pid,
                    uid = item.raw.uid,
                    processName = stats?.processName.orEmpty(),
                    swapBytes = item.raw.swapKb * 1024L,
                    swapPssBytes = 0L,
                    rssBytes = item.raw.rssKb * 1024L,
                    pssBytes = 0L,
                    runningSeconds = timing.runningSeconds,
                    cpuTimeSeconds = timing.cpuTimeSeconds
                )
            )
        }

        return processRows
            .groupBy { it.packageName }
            .map { (packageName, rows) ->
                val appInfo = rows.first().appInfo
                val processes = rows.map { it.process }
                RunningAppUsage(
                    packageName = packageName,
                    label = runCatching { appInfo.loadLabel(packageManager).toString() }
                        .getOrDefault(packageName),
                    uid = appInfo.uid,
                    residentBytes = processes.sumOf { it.rssBytes },
                    swapBytes = processes.sumOf { it.swapBytes },
                    processCount = processes.size,
                    isSystemApp = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    runningSeconds = processes.maxOfOrNull { it.runningSeconds } ?: 0L,
                    cpuTimeSeconds = processes.sumOf { it.cpuTimeSeconds },
                    processes = processes.sortedByDescending { it.rssBytes + it.swapBytes }
                )
            }
            .sortedByDescending { it.residentBytes + it.swapBytes }
    }

    fun readAppsUsingSwap(): List<AppSwapUsage> = readRunningApps()
        .filter { it.swapBytes > 0L }
        .map { app ->
            AppSwapUsage(
                packageName = app.packageName,
                label = app.label,
                uid = app.uid,
                attributedSwapBytes = app.swapBytes,
                rawSwapBytes = app.swapBytes,
                residentBytes = app.residentBytes,
                pssBytes = 0L,
                processCount = app.processCount,
                isSystemApp = app.isSystemApp,
                processes = app.processes,
                runningSeconds = app.runningSeconds,
                cpuTimeSeconds = app.cpuTimeSeconds
            )
        }
        .sortedByDescending { it.attributedSwapBytes }

    private fun readAllProcessStatuses(): List<RawProcessStatus> {
        val result = rootAccess.runResult(
            "grep -H -e '^Uid:' -e '^VmRSS:' -e '^VmSwap:' /proc/[0-9]*/status 2>/dev/null || true",
            timeoutSeconds = 8
        ) ?: error("The root process scanner could not be started.")

        if (result.timedOut) error("The running-app scan timed out while reading process status files.")
        if (!result.success) error("The kernel process status scan failed.")
        return parseStatusOutput(result.output)
    }

    private fun parseStatusOutput(output: String): List<RawProcessStatus> {
        val linePattern = Regex("^/proc/(\\d+)/status:(Uid|VmRSS|VmSwap):\\s*(.*)$")
        val byPid = linkedMapOf<Int, MutableProcessStatus>()

        output.lineSequence().forEach { rawLine ->
            val match = linePattern.matchEntire(rawLine.trim()) ?: return@forEach
            val pid = match.groupValues[1].toIntOrNull() ?: return@forEach
            val value = Regex("^\\s*(\\d+)")
                .find(match.groupValues[3])
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?: return@forEach

            val current = byPid.getOrPut(pid) { MutableProcessStatus() }
            when (match.groupValues[2]) {
                "Uid" -> current.uid = value.toInt()
                "VmRSS" -> current.rssKb = value
                "VmSwap" -> current.swapKb = value
            }
        }

        return byPid.mapNotNull { (pid, fields) ->
            val uid = fields.uid ?: return@mapNotNull null
            RawProcessStatus(pid, uid, fields.swapKb, fields.rssKb)
        }
    }

    private fun readProcessNames(pids: List<Int>): Map<Int, String> {
        if (pids.isEmpty()) return emptyMap()
        val pidList = pids.distinct().joinToString(" ")
        val dollar = '$'
        val script = "for pid in $pidList; do " +
            "[ -r /proc/${dollar}pid/cmdline ] || continue; " +
            "name=\$(tr '\\000' ' ' < /proc/${dollar}pid/cmdline 2>/dev/null | cut -d' ' -f1); " +
            "printf '%s\\t%s\\n' \"${dollar}pid\" \"${dollar}name\"; done"

        val result = rootAccess.runResult(script, timeoutSeconds = 4) ?: return emptyMap()
        if (!result.success) return emptyMap()
        return result.output.lineSequence().mapNotNull { line ->
            val parts = line.split('\t', limit = 2)
            val pid = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            pid to parts.getOrNull(1).orEmpty().trim()
        }.toMap()
    }

    private fun readProcessStats(pids: List<Int>): Map<Int, ProcessStat> {
        if (pids.isEmpty()) return emptyMap()
        val pidList = pids.distinct().joinToString(" ")
        val dollar = '$'
        val script = "for pid in $pidList; do " +
            "[ -r /proc/${dollar}pid/stat ] || continue; " +
            "printf '%s\\t' \"${dollar}pid\"; cat /proc/${dollar}pid/stat 2>/dev/null; done"

        val result = rootAccess.runResult(script, timeoutSeconds = 6) ?: return emptyMap()
        if (!result.success) return emptyMap()

        return result.output.lineSequence().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@mapNotNull null
            val pid = line.substring(0, tab).toIntOrNull() ?: return@mapNotNull null
            parseStatLine(pid, line.substring(tab + 1))?.let { pid to it }
        }.toMap()
    }

    private fun parseStatLine(pid: Int, statLine: String): ProcessStat? {
        val open = statLine.indexOf('(')
        val close = statLine.lastIndexOf(')')
        if (open < 0 || close <= open) return null
        val processName = statLine.substring(open + 1, close)
        val rest = statLine.substring(close + 1).trim().split(Regex("\\s+"))
        val userTicks = rest.getOrNull(11)?.toLongOrNull() ?: return null
        val systemTicks = rest.getOrNull(12)?.toLongOrNull() ?: return null
        val startTicks = rest.getOrNull(19)?.toLongOrNull() ?: return null
        return ProcessStat(pid, processName, userTicks + systemTicks, startTicks)
    }

    private fun calculateTiming(stat: ProcessStat?, uptimeSeconds: Double): ProcessTiming {
        if (stat == null || clockTicksPerSecond <= 0L) return ProcessTiming(0L, 0.0)
        val cpuSeconds = stat.cpuTicks.toDouble() / clockTicksPerSecond.toDouble()
        val startSeconds = stat.startTicks.toDouble() / clockTicksPerSecond.toDouble()
        val runningSeconds = (uptimeSeconds - startSeconds).coerceAtLeast(0.0).toLong()
        return ProcessTiming(runningSeconds, cpuSeconds.coerceAtLeast(0.0))
    }

    private fun readUptimeSeconds(): Double = runCatching {
        File("/proc/uptime").readText().trim().substringBefore(' ').toDouble()
    }.getOrDefault(0.0)

    private fun packagesForUid(uid: Int): List<String> = runCatching {
        packageManager.getPackagesForUid(uid)?.toList().orEmpty()
    }.getOrDefault(emptyList())

    @Suppress("DEPRECATION")
    private fun getApplicationInfo(packageName: String): ApplicationInfo? = runCatching {
        packageManager.getApplicationInfo(packageName, 0)
    }.getOrNull()

    private data class MutableProcessStatus(var uid: Int? = null, var swapKb: Long = 0L, var rssKb: Long = 0L)
    private data class RawProcessStatus(val pid: Int, val uid: Int, val swapKb: Long, val rssKb: Long)
    private data class ResolvedRawProcess(val raw: RawProcessStatus, val packageName: String, val appInfo: ApplicationInfo)
    private data class ResolvedProcess(val packageName: String, val appInfo: ApplicationInfo, val process: ProcessSwapUsage)
    private data class ProcessStat(val pid: Int, val processName: String, val cpuTicks: Long, val startTicks: Long)
    private data class ProcessTiming(val runningSeconds: Long, val cpuTimeSeconds: Double)
}
