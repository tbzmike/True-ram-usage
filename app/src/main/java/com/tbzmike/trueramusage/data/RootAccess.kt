package com.tbzmike.trueramusage.data

import java.util.concurrent.TimeUnit

enum class RootState {
    NOT_REQUESTED,
    GRANTED,
    DENIED_OR_TIMED_OUT,
    UNAVAILABLE
}

data class RootCommandResult(
    val exitCode: Int?,
    val output: String,
    val timedOut: Boolean
) {
    val success: Boolean
        get() = !timedOut && exitCode == 0
}

class RootAccess {
    @Volatile
    private var granted = false

    fun isGranted(): Boolean = granted

    fun request(): RootState {
        val result = execute("id -u", timeoutSeconds = 30)
            ?: return RootState.UNAVAILABLE
        granted = result.success && result.output.lineSequence().firstOrNull()?.trim() == "0"
        return if (granted) RootState.GRANTED else RootState.DENIED_OR_TIMED_OUT
    }

    fun run(command: String, timeoutSeconds: Long = 8): String? {
        if (!granted) return null
        val result = execute(command, timeoutSeconds) ?: return null
        return if (result.success) result.output else null
    }

    fun runResult(command: String, timeoutSeconds: Long = 8): RootCommandResult? {
        if (!granted) return null
        return execute(command, timeoutSeconds)
    }

    private fun execute(command: String, timeoutSeconds: Long): RootCommandResult? = runCatching {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching RootCommandResult(
                exitCode = null,
                output = "",
                timedOut = true
            )
        }
        RootCommandResult(
            exitCode = process.exitValue(),
            output = process.inputStream.bufferedReader().use { it.readText() }.trim(),
            timedOut = false
        )
    }.getOrNull()
}
