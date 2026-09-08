package com.tbzmike.trueramusage.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tbzmike.trueramusage.update.GreenRelease
import com.tbzmike.trueramusage.update.StagedUpdate
import com.tbzmike.trueramusage.update.SystemInstallLaunchResult
import com.tbzmike.trueramusage.update.UpdatePreferences
import com.tbzmike.trueramusage.update.UpdateRepository
import com.tbzmike.trueramusage.update.UpdateScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = UpdateRepository(application)
    private val preferences = UpdatePreferences(application)

    val currentVersionCode: Long = repository.currentVersionCode()
    val currentVersionName: String = repository.currentVersionName()

    var automaticUpdatesEnabled by mutableStateOf(preferences.automaticUpdatesEnabled)
        private set

    var latestRelease by mutableStateOf<GreenRelease?>(null)
        private set

    var stagedUpdate by mutableStateOf(preferences.stagedUpdate())
        private set

    var checking by mutableStateOf(false)
        private set

    var downloading by mutableStateOf(false)
        private set

    var message by mutableStateOf<String?>(null)
        private set

    val updateAvailable: Boolean
        get() = latestRelease?.versionCode?.let { it > currentVersionCode } == true ||
            stagedUpdate?.versionCode?.let { it > currentVersionCode } == true

    fun onForeground() {
        cleanObsoleteStagedUpdate()
        val sixHours = 6L * 60L * 60L * 1000L
        if (automaticUpdatesEnabled && System.currentTimeMillis() - preferences.lastAutomaticCheckMillis >= sixHours) {
            checkForUpdates(automatic = true)
        }
    }

    fun setAutomaticUpdates(enabled: Boolean) {
        automaticUpdatesEnabled = enabled
        preferences.automaticUpdatesEnabled = enabled
        UpdateScheduler.apply(getApplication(), enabled)
        message = if (enabled) {
            "Automatic green-build checks are enabled. Verified updates can install unattended when root has previously been granted."
        } else {
            "Automatic update checks are disabled. Manual checks remain available."
        }
    }

    fun checkNow() = checkForUpdates(automatic = false)

    fun downloadAndInstallLatest() {
        if (checking || downloading) return
        viewModelScope.launch {
            val release = latestRelease ?: runCatching {
                checking = true
                message = "Checking the latest green GitHub release…"
                withContext(Dispatchers.IO) { repository.fetchLatestGreenRelease() }
            }.onFailure {
                message = it.message ?: "The latest green release could not be checked."
            }.getOrNull().also { checking = false } ?: return@launch

            latestRelease = release
            if (release.versionCode <= currentVersionCode) {
                message = "Version $currentVersionName is already the latest green build."
                return@launch
            }
            downloadAndStage(release, automatic = false)
        }
    }

    fun installStaged() {
        if (checking || downloading) return
        val staged = stagedUpdate ?: return
        viewModelScope.launch {
            message = "Verifying the staged APK before installation…"
            val apk = runCatching {
                withContext(Dispatchers.IO) { repository.verifyStaged(staged) }
            }.onFailure {
                message = it.message ?: "The staged APK failed verification."
            }.getOrNull() ?: return@launch

            val rootResult = withContext(Dispatchers.IO) { repository.installWithRoot(apk) }
            if (rootResult.success) {
                preferences.clearStaged()
                stagedUpdate = null
                message = rootResult.message
                return@launch
            }

            when (repository.launchSystemInstaller(apk)) {
                SystemInstallLaunchResult.INSTALLER_LAUNCHED ->
                    message = "Android's installer was opened for the verified green build."
                SystemInstallLaunchResult.INSTALL_PERMISSION_REQUIRED ->
                    message = "Allow True RAM Usage to install apps, return here, then tap Install downloaded update again."
                SystemInstallLaunchResult.FAILED ->
                    message = "Root installation was unavailable and Android's installer could not be opened."
            }
        }
    }

    fun openLatestRelease() {
        val url = latestRelease?.releaseHtmlUrl ?: stagedUpdate?.releaseHtmlUrl.orEmpty()
        if (!repository.openReleasePage(url)) message = "No GitHub release page is available yet."
    }

    fun clearMessage() {
        message = null
    }

    private fun checkForUpdates(automatic: Boolean) {
        if (checking || downloading) return
        viewModelScope.launch {
            checking = true
            if (!automatic) message = "Checking the latest green GitHub release…"
            val release = runCatching {
                withContext(Dispatchers.IO) { repository.fetchLatestGreenRelease() }
            }.onFailure {
                if (!automatic) message = it.message ?: "The latest green release could not be checked."
            }.getOrNull()
            preferences.lastAutomaticCheckMillis = System.currentTimeMillis()
            checking = false
            if (release == null) return@launch

            latestRelease = release
            if (release.versionCode <= currentVersionCode) {
                if (!automatic) message = "Version $currentVersionName is already the latest green build."
                return@launch
            }

            message = "Green build ${release.versionName} is available."
            if (automatic && automaticUpdatesEnabled) {
                downloadAndStage(release, automatic = true)
            }
        }
    }

    private suspend fun downloadAndStage(release: GreenRelease, automatic: Boolean) {
        downloading = true
        message = "Downloading and verifying green build ${release.versionName}…"
        val apk = runCatching {
            withContext(Dispatchers.IO) { repository.downloadAndVerify(release) }
        }.onFailure {
            message = it.message ?: "The update download or verification failed."
        }.getOrNull()
        downloading = false
        if (apk == null) return

        val staged = StagedUpdate(
            versionCode = release.versionCode,
            versionName = release.versionName,
            apkPath = apk.absolutePath,
            apkSha256 = release.apkSha256,
            certificateSha256 = release.certificateSha256,
            releaseHtmlUrl = release.releaseHtmlUrl
        )
        preferences.stage(staged)
        stagedUpdate = staged

        val shouldTryUnattended = automatic && preferences.rootPreviouslyGranted
        if (shouldTryUnattended) {
            message = "Verified green build ${release.versionName}; installing with previously granted root access…"
            val result = withContext(Dispatchers.IO) { repository.installWithRoot(apk) }
            if (result.success) {
                preferences.clearStaged()
                stagedUpdate = null
                message = result.message
            } else {
                message = "Update ${release.versionName} is verified and staged. Unattended root installation was unavailable; install it manually from Updates."
            }
            return
        }

        if (!automatic) {
            installStaged()
        } else {
            message = "Update ${release.versionName} is verified and staged. Grant root once for unattended future updates, or install it manually from Updates."
        }
    }

    private fun cleanObsoleteStagedUpdate() {
        val staged = stagedUpdate ?: return
        if (staged.versionCode <= currentVersionCode) {
            repository.deleteStagedFile(staged)
            preferences.clearStaged()
            stagedUpdate = null
        }
    }
}
