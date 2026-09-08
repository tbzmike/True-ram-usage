package com.tbzmike.trueramusage.update

data class GreenRelease(
    val tagName: String,
    val releaseName: String,
    val releaseHtmlUrl: String,
    val versionCode: Long,
    val versionName: String,
    val runNumber: Long,
    val commitSha: String,
    val apkAssetName: String,
    val apkDownloadUrl: String,
    val apkSha256: String,
    val certificateSha256: String
)

data class StagedUpdate(
    val versionCode: Long,
    val versionName: String,
    val apkPath: String,
    val apkSha256: String,
    val certificateSha256: String,
    val releaseHtmlUrl: String
)

data class RootInstallResult(
    val success: Boolean,
    val message: String
)

enum class SystemInstallLaunchResult {
    INSTALLER_LAUNCHED,
    INSTALL_PERMISSION_REQUIRED,
    FAILED
}
