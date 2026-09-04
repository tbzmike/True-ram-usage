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

        return processes
            .mapNotNull { process -> resolveProcess(process) }
            .groupBy { it.packageName }
            .map { (packageName, resolved) ->
                val appInfo = resolved.first().appInfo
                val processList = resolved.map { it.process }
                AppSwapUsage(
                    packageName = packageName,
                    label = runCatching { appInfo.loadLabel(packageManager).toString() }
                        .getOrDefault(packageName),
                    uid = appInfo.uid,
                    attributedSwapBytes = processList.sumOf { it.attributedSwapBytes },
                    rawSwapBytes = processList.sumOf { it.swapBytes },
                    residentBytes = processList.sumOf { it.rssBytes },
                    pssBytes = processList.sumOf { it.pssBytes },
                    processCount = processList.size,
                    isSystemApp = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    processes = processList.sortedByDescending { it.attributedSwapBytes }
                )
            }
            .filter { it.attributedSwapBytes > 0 }
            .sortedByDescending { it.attributedSwapBytes }
    }

    private fun resolveProcess(process: ProcessSwapUsage): ResolvedProcess? {
        val commandPackage = process.processName.substringBefore(':')
        getApplicationInfo(commandPackage)?.let {
            return ResolvedProcess(process, commandPackage, it)
        }

        val packagesForUid = runCatching { packageManager.getPackagesForUid(process.uid) }
            .getOrNull()
            .orEmpty()

        val packageName = packagesForUid.firstOrNull { candidate ->
            process.processName == candidate || process.processName.startsWith("$candidate:")
        } ?: packagesForUid.singleOrNull() ?: return null

        val appInfo = getApplicationInfo(packageName) ?: return null
        return ResolvedProcess(process, packageName, appInfo)
    }

    @Suppress("DEPRECATION")
    private fun getApplicationInfo(packageName: String): ApplicationInfo? = runCatching {
        packageManager.getApplicationInfo(packageName, 0)
    }.getOrNull()

    private fun readProcessSwapUsage(): List<ProcessSwapUsage> {
        // Fast two-stage scan: /proc/<pid>/status is cheap. smaps_rollup is read only
        // for processes whose VmSwap is already greater than zero.
        val script = "for p in /proc/[0-9]*; do " +
            "status=\"\$p/status\"; [ -r \"\$status\" ] || continue; " +
            "swap=\$(awk '/^VmSwap:/ {print \$2; exit}' \"\$status\" 2>/dev/null); " +
            "swap=\${swap:-0}; [ \"\$swap\" -gt 0 ] || continue; " +
            "pid=\${p##*/}; " +
            "uid=\$(awk '/^Uid:/ {print \$2; exit}' \"\$status\" 2>/dev/null); uid=\${uid:-0}; " +
            "rss=\$(awk '/^VmRSS:/ {print \$2; exit}' \"\$status\" 2>/dev/null); rss=\${rss:-0}; " +
            "name=\$(tr '\\000' ' ' < \"\$p/cmdline\" 2>/dev/null | awk '{print \$1}'); " +
            "[ -n \"\$name\" ] || name=\$(awk '/^Name:/ {print \$2; exit}' \"\$status\" 2>/dev/null); " +
            "swappss=0; pss=0; " +
            "if [ -r \"\$p/smaps_rollup\" ]; then " +
            "vals=\$(awk '/^SwapPss:/ {sp=\$2} /^Pss:/ {ps=\$2} END {printf \"%d %d\", sp, ps}' \"\$p/smaps_rollup\" 2>/dev/null); " +
            "set -- \$vals; swappss=\${1:-0}; pss=\${2:-0}; fi; " +
            "printf '%s\\t%s\\t%s\\t%s\\t%s\\t%s\\t%s\\n' \"\$pid\" \"\$uid\" \"\$swap\" \"\$swappss\" \"\$rss\" \"\$pss\" \"\$name\"; " +
            "done"

        val result = rootAccess.runResult(script, timeoutSeconds = 10)
            ?: error("The root process scanner could not be started.")
        if (result.timedOut) error("The per-app swap scan timed out.")
        if (!result.success) error("The kernel process scan failed.")

        return result.output.lineSequence()
            .mapNotNull(::parseProcessLine)
            .toList()
    }

    private fun parseProcessLine(line: String): ProcessSwapUsage? {
        val parts = line.split('\t', limit = 7)
        if (parts.size != 7) return null
        val pid = parts[0].toIntOrNull() ?: return null
        val uid = parts[1].toIntOrNull() ?: return null
        val swapKb = parts[2].toLongOrNull() ?: return null
        val swapPssKb = parts[3].toLongOrNull() ?: 0L
        val rssKb = parts[4].toLongOrNull() ?: 0L
        val pssKb = parts[5].toLongOrNull() ?: 0L
        val name = parts[6].trim()
        if (name.isEmpty()) return null
        return ProcessSwapUsage(
            pid = pid,
            uid = uid,
            processName = name,
            swapBytes = swapKb * 1024L,
            swapPssBytes = swapPssKb * 1024L,
            rssBytes = rssKb * 1024L,
            pssBytes = pssKb * 1024L
        )
    }

    private data class ResolvedProcess(
        val process: ProcessSwapUsage,
        val packageName: String,
        val appInfo: ApplicationInfo
    )
}
