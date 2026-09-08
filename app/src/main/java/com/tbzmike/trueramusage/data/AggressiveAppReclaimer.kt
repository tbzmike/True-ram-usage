package com.tbzmike.trueramusage.data

data class AggressiveAppKillResult(
    val success: Boolean,
    val currentUserId: Int?,
    val discoveredThirdPartyPackages: Int,
    val targetedPackages: Int,
    val firstPassAccepted: Int,
    val secondPassAccepted: Int,
    val failedForceStopCommands: Int,
    val killAllPassesSucceeded: Int,
    val message: String
)

class AggressiveAppReclaimer(
    private val rootAccess: RootAccess
) {
    fun forceStopAllKillableUserApps(
        scannedApps: List<RunningAppUsage>,
        ownPackageName: String
    ): AggressiveAppKillResult {
        if (!rootAccess.isGranted()) {
            return AggressiveAppKillResult(
                success = false,
                currentUserId = null,
                discoveredThirdPartyPackages = 0,
                targetedPackages = 0,
                firstPassAccepted = 0,
                secondPassAccepted = 0,
                failedForceStopCommands = 0,
                killAllPassesSucceeded = 0,
                message = "Root access is required to aggressively force-stop user apps."
            )
        }

        val discovery = discoverThirdPartyPackages()
        if (!discovery.success || discovery.userId == null) {
            return AggressiveAppKillResult(
                success = false,
                currentUserId = discovery.userId,
                discoveredThirdPartyPackages = discovery.packages.size,
                targetedPackages = 0,
                firstPassAccepted = 0,
                secondPassAccepted = 0,
                failedForceStopCommands = 0,
                killAllPassesSucceeded = 0,
                message = discovery.message
            )
        }

        val scannedTargets = scannedApps
            .asSequence()
            .filterNot { it.isSystemApp }
            .map { it.packageName }
            .filter(::isSafePackageName)
            .toSet()

        val targets = (discovery.packages + scannedTargets)
            .asSequence()
            .filter { it != ownPackageName }
            .filter(::isSafePackageName)
            .distinct()
            .sorted()
            .toList()

        if (targets.isEmpty()) {
            val killPasses = runKillAllPasses()
            return AggressiveAppKillResult(
                success = killPasses > 0,
                currentUserId = discovery.userId,
                discoveredThirdPartyPackages = discovery.packages.size,
                targetedPackages = 0,
                firstPassAccepted = 0,
                secondPassAccepted = 0,
                failedForceStopCommands = 0,
                killAllPassesSucceeded = killPasses,
                message = if (killPasses > 0) {
                    "No third-party package other than True RAM Usage was available to force-stop; Android background kill-all completed $killPasses/2 passes."
                } else {
                    "No third-party package other than True RAM Usage was available to force-stop, and Android did not confirm either background kill-all pass."
                }
            )
        }

        val first = forceStopPass(targets)
        val firstKill = runSingleKillAllPass()
        runCatching { Thread.sleep(700L) }
        val second = forceStopPass(targets)
        val secondKill = runSingleKillAllPass()

        val failed = first.failed + second.failed
        val killPasses = listOf(firstKill, secondKill).count { it }
        val success = first.completed && second.completed && failed == 0 && killPasses > 0
        val message = buildString {
            append("Aggressive app sweep targeted ${targets.size} package(s) for Android user ${discovery.userId}. ")
            append("Force-stop accepted ${first.accepted}/${targets.size} on pass 1 and ${second.accepted}/${targets.size} on pass 2. ")
            append("Android background kill-all succeeded $killPasses/2 passes")
            if (failed > 0) append("; $failed force-stop command(s) were rejected")
            append('.')
        }

        return AggressiveAppKillResult(
            success = success,
            currentUserId = discovery.userId,
            discoveredThirdPartyPackages = discovery.packages.size,
            targetedPackages = targets.size,
            firstPassAccepted = first.accepted,
            secondPassAccepted = second.accepted,
            failedForceStopCommands = failed,
            killAllPassesSucceeded = killPasses,
            message = message
        )
    }

    private fun discoverThirdPartyPackages(): PackageDiscovery {
        val dollar = '$'
        val command = buildString {
            append("user_id=\"${dollar}(am get-current-user 2>/dev/null | tr -d '[:space:]')\"; ")
            append("case \"${dollar}user_id\" in ''|*[!0-9]*) user_id=\"${dollar}(cmd activity get-current-user 2>/dev/null | tr -d '[:space:]')\" ;; esac; ")
            append("case \"${dollar}user_id\" in ''|*[!0-9]*) echo CURRENT_USER_UNAVAILABLE; exit 2 ;; esac; ")
            append("printf 'USER=%s\\n' \"${dollar}user_id\"; ")
            append("pm list packages -3 --user \"${dollar}user_id\" 2>/dev/null")
        }

        val result = rootAccess.runResult(command, timeoutSeconds = 30)
            ?: return PackageDiscovery(false, null, emptySet(), "The installed-app enumeration command could not be started.")
        if (result.timedOut) {
            return PackageDiscovery(false, null, emptySet(), "Enumerating installed third-party apps timed out.")
        }

        val userId = Regex("(?m)^USER=(\\d+)\\s*$")
            .find(result.output)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        if (!result.success || userId == null) {
            return PackageDiscovery(
                false,
                userId,
                emptySet(),
                "Android's current user could not be resolved safely, so the aggressive app sweep was not guessed."
            )
        }

        val packages = result.output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }
            .filter(::isSafePackageName)
            .toSet()

        return PackageDiscovery(true, userId, packages, "Enumerated ${packages.size} third-party package(s).")
    }

    private fun forceStopPass(targets: List<String>): ForceStopPass {
        if (targets.isEmpty()) return ForceStopPass(true, 0, 0)

        val dollar = '$'
        val command = buildString {
            append("accepted=0; failed=0; ")
            targets.forEach { packageName ->
                append("if am force-stop --user current ")
                append(shellQuote(packageName))
                append(" >/dev/null 2>&1; then accepted=${dollar}((accepted+1)); else failed=${dollar}((failed+1)); fi; ")
            }
            append("printf 'ACCEPTED=%s FAILED=%s\\n' \"${dollar}accepted\" \"${dollar}failed\"")
        }

        val result = rootAccess.runResult(command, timeoutSeconds = 120)
            ?: return ForceStopPass(false, 0, targets.size)
        if (result.timedOut) return ForceStopPass(false, 0, targets.size)

        val accepted = Regex("ACCEPTED=(\\d+)")
            .find(result.output)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 0
        val failed = Regex("FAILED=(\\d+)")
            .find(result.output)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: (targets.size - accepted).coerceAtLeast(0)

        return ForceStopPass(result.success, accepted, failed)
    }

    private fun runKillAllPasses(): Int = listOf(
        runSingleKillAllPass(),
        runSingleKillAllPass()
    ).count { it }

    private fun runSingleKillAllPass(): Boolean =
        rootAccess.runResult("am kill-all >/dev/null 2>&1", timeoutSeconds = 30)?.success == true

    private fun isSafePackageName(packageName: String): Boolean =
        packageName.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private data class PackageDiscovery(
        val success: Boolean,
        val userId: Int?,
        val packages: Set<String>,
        val message: String
    )

    private data class ForceStopPass(
        val completed: Boolean,
        val accepted: Int,
        val failed: Int
    )
}
