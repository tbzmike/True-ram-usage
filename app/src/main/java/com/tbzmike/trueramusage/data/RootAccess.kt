package com.tbzmike.trueramusage.data

import java.util.concurrent.Executors
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

        // Read stdout concurrently. Waiting for the process before draining stdout can
        // deadlock when a command produces enough output to fill the OS pipe buffer.
        val executor = Executors.newSingleThreadExecutor()
        val outputFuture = executor.submit<String> {
            process.inputStream.bufferedReader().use { it.readText() }
        }

        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroy()
                if (!process.waitFor(300, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                }
                outputFuture.cancel(true)
                return@runCatching RootCommandResult(
                    exitCode = null,
                    output = "",
                    timedOut = true
                )
            }

            val output = runCatching {
                outputFuture.get(2, TimeUnit.SECONDS)
            }.getOrDefault("")

            RootCommandResult(
                exitCode = process.exitValue(),
                output = output.trim(),
                timedOut = false
            )
        } finally {
            executor.shutdownNow()
        }
    }.getOrNull()
}
