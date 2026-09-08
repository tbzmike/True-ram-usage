package com.tbzmike.trueramusage.update

import android.content.Context

class UpdatePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("true_ram_usage_updates", Context.MODE_PRIVATE)

    var automaticUpdatesEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTOMATIC_UPDATES, true)
        set(value) { prefs.edit().putBoolean(KEY_AUTOMATIC_UPDATES, value).apply() }

    var automaticInstallEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTOMATIC_INSTALL, true)
        set(value) { prefs.edit().putBoolean(KEY_AUTOMATIC_INSTALL, value).apply() }

    var rootPreviouslyGranted: Boolean
        get() = prefs.getBoolean(KEY_ROOT_PREVIOUSLY_GRANTED, false)
        set(value) { prefs.edit().putBoolean(KEY_ROOT_PREVIOUSLY_GRANTED, value).apply() }

    var lastAutomaticCheckMillis: Long
        get() = prefs.getLong(KEY_LAST_AUTOMATIC_CHECK, 0L)
        set(value) { prefs.edit().putLong(KEY_LAST_AUTOMATIC_CHECK, value).apply() }

    fun stagedUpdate(): StagedUpdate? {
        val versionCode = prefs.getLong(KEY_STAGED_VERSION_CODE, 0L)
        val versionName = prefs.getString(KEY_STAGED_VERSION_NAME, null) ?: return null
        val path = prefs.getString(KEY_STAGED_APK_PATH, null) ?: return null
        val sha = prefs.getString(KEY_STAGED_APK_SHA256, null) ?: return null
        val cert = prefs.getString(KEY_STAGED_CERT_SHA256, null) ?: return null
        val releaseUrl = prefs.getString(KEY_STAGED_RELEASE_URL, "").orEmpty()
        if (versionCode <= 0L) return null
        return StagedUpdate(versionCode, versionName, path, sha, cert, releaseUrl)
    }

    fun stage(update: StagedUpdate) {
        prefs.edit()
            .putLong(KEY_STAGED_VERSION_CODE, update.versionCode)
            .putString(KEY_STAGED_VERSION_NAME, update.versionName)
            .putString(KEY_STAGED_APK_PATH, update.apkPath)
            .putString(KEY_STAGED_APK_SHA256, update.apkSha256)
            .putString(KEY_STAGED_CERT_SHA256, update.certificateSha256)
            .putString(KEY_STAGED_RELEASE_URL, update.releaseHtmlUrl)
            .apply()
    }

    fun clearStaged() {
        prefs.edit()
            .remove(KEY_STAGED_VERSION_CODE)
            .remove(KEY_STAGED_VERSION_NAME)
            .remove(KEY_STAGED_APK_PATH)
            .remove(KEY_STAGED_APK_SHA256)
            .remove(KEY_STAGED_CERT_SHA256)
            .remove(KEY_STAGED_RELEASE_URL)
            .apply()
    }

    companion object {
        private const val KEY_AUTOMATIC_UPDATES = "automatic_updates"
        private const val KEY_AUTOMATIC_INSTALL = "automatic_install"
        private const val KEY_ROOT_PREVIOUSLY_GRANTED = "root_previously_granted"
        private const val KEY_LAST_AUTOMATIC_CHECK = "last_automatic_check"
        private const val KEY_STAGED_VERSION_CODE = "staged_version_code"
        private const val KEY_STAGED_VERSION_NAME = "staged_version_name"
        private const val KEY_STAGED_APK_PATH = "staged_apk_path"
        private const val KEY_STAGED_APK_SHA256 = "staged_apk_sha256"
        private const val KEY_STAGED_CERT_SHA256 = "staged_cert_sha256"
        private const val KEY_STAGED_RELEASE_URL = "staged_release_url"
    }
}
