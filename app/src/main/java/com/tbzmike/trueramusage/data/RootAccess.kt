package com.tbzmike.trueramusage.data

import java.util.concurrent.TimeUnit

enum class RootState {
    NOT_REQUESTED,
    GRANTED,
    DENIED_OR_TIMED_OUT,
    UNAVAILABLE
}

class RootAccess {
    @Volatile
    private var granted = false

    fun isGranted(): Boolean = granted

    fun request(): RootState {
        val result = execute("id -u", timeoutSeconds = 30)
        granted = result?.lineSequence()?.firstOrNull()?.trim() == "0"
        return when {
            granted -> RootState.GRANTED
            result == null -> RootState.DENIED_OR_TIMED_OUT
            else -> RootState.DENIED_OR_TIMED_OUT
        }
    }

    fun run(command: String): String? {
        if (!granted) return null
        return execute(command, timeoutSeconds = 5)
    }

    private fun execute(command: String, timeoutSeconds: Long): String? = runCatching {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (process.exitValue() == 0) output else null
    }.getOrNull()
}
