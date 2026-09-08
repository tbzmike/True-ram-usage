package com.tbzmike.trueramusage.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class UpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val preferences = UpdatePreferences(applicationContext)
        if (!preferences.automaticUpdatesEnabled) return@withContext Result.success()

        val repository = UpdateRepository(applicationContext)
        try {
            preferences.lastAutomaticCheckMillis = System.currentTimeMillis()
            val latest = repository.fetchLatestGreenRelease()
            val currentVersionCode = repository.currentVersionCode()

            val staged = preferences.stagedUpdate()
            if (staged != null && staged.versionCode <= currentVersionCode) {
                repository.deleteStagedFile(staged)
                preferences.clearStaged()
            }

            if (latest.versionCode <= currentVersionCode) {
                return@withContext Result.success()
            }

            val apk = repository.downloadAndVerify(latest)
            val stagedUpdate = StagedUpdate(
                versionCode = latest.versionCode,
                versionName = latest.versionName,
                apkPath = apk.absolutePath,
                apkSha256 = latest.apkSha256,
                certificateSha256 = latest.certificateSha256,
                releaseHtmlUrl = latest.releaseHtmlUrl
            )
            preferences.stage(stagedUpdate)

            if (preferences.automaticInstallEnabled && preferences.rootPreviouslyGranted) {
                val install = repository.installWithRoot(apk)
                if (install.success) {
                    preferences.clearStaged()
                }
            }
            Result.success()
        } catch (_: IOException) {
            Result.retry()
        } catch (_: Throwable) {
            Result.success()
        }
    }
}
