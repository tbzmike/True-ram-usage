package com.tbzmike.trueramusage.data

import android.content.Context
import android.content.pm.ApplicationInfo

class AppSwapRepository(
    context: Context,
    private val rootAccess: RootAccess
) {
    private val packageManager = context.packageManager

    fun readAppsUsingSwap(): List<AppSwapUsage> {
        if (!rootAccess.isGranted()) return emptyList()
        val processes = readProcessSwapUsage()
        if (processes.isEmpty()) return emptyList()

        return processes
            .mapNotNull { process ->
                val packageName = process.processName.substringBefore(':')
                val appInfo = getApplicationInfo(packageName) ?: return@mapNotNull null
                ResolvedProcess(process, packageName, appInfo)
            }
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

    @Suppress("DEPRECATION")
    private fun getApplicationInfo(packageName: String): ApplicationInfo? = runCatching {
        packageManager.getApplicationInfo(packageName, 0)
    }.getOrNull()

    private fun readProcessSwapUsage(): List<ProcessSwapUsage> {
        val script = "for p in /proc/[0-9]*; do " +
            "pid=\${p##*/}; " +
            "[ -r \"\$p/cmdline\" ] || continue; " +
            "name=\$(tr '\\000' ' ' < \"\$p/cmdline\" 2>/dev/null | awk '{print \$1}'); " +
            "[ -n \"\$name\" ] || continue; " +
            "swap=0; swappss=0; rss=0; pss=0; " +
            "if [ -r \"\$p/smaps_rollup\" ]; then " +
            "vals=\$(awk '/^Swap:/ {s=\$2} /^SwapPss:/ {sp=\$2} /^Rss:/ {r=\$2} /^Pss:/ {ps=\$2} END {printf \"%d %d %d %d\", s, sp, r, ps}' \"\$p/smaps_rollup\" 2>/dev/null); " +
            "set -- \$vals; swap=\${1:-0}; swappss=\${2:-0}; rss=\${3:-0}; pss=\${4:-0}; " +
            "else " +
            "swap=\$(awk '/^VmSwap:/ {print \$2}' \"\$p/status\" 2>/dev/null); " +
            "rss=\$(awk '/^VmRSS:/ {print \$2}' \"\$p/status\" 2>/dev/null); " +
            "fi; " +
            "swap=\${swap:-0}; swappss=\${swappss:-0}; rss=\${rss:-0}; pss=\${pss:-0}; " +
            "[ \"\$swap\" -gt 0 ] || continue; " +
            "uid=\$(awk '/^Uid:/ {print \$2}' \"\$p/status\" 2>/dev/null); uid=\${uid:-0}; " +
            "printf '%s\\t%s\\t%s\\t%s\\t%s\\t%s\\t%s\\n' \"\$pid\" \"\$uid\" \"\$swap\" \"\$swappss\" \"\$rss\" \"\$pss\" \"\$name\"; " +
            "done"

        val output = rootAccess.run(script, timeoutSeconds = 20) ?: return emptyList()
        return output.lineSequence().mapNotNull(::parseProcessLine).toList()
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
