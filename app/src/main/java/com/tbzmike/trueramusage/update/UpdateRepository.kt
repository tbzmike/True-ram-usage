package com.tbzmike.trueramusage.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.tbzmike.trueramusage.data.RootAccess
import com.tbzmike.trueramusage.data.RootState
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class UpdateRepository(private val context: Context) {
    private val packageManager = context.packageManager

    fun currentVersionCode(): Long = versionCode(installedPackageInfo())

    fun currentVersionName(): String = installedPackageInfo().versionName.orEmpty()

    fun fetchLatestGreenRelease(): GreenRelease {
        val releaseJson = JSONObject(readText(LATEST_RELEASE_API, githubApi = true))
        check(!releaseJson.optBoolean("draft", false)) { "GitHub returned a draft release." }
        check(!releaseJson.optBoolean("prerelease", false)) { "GitHub returned a prerelease instead of the latest green build." }

        val manifestUrl = assetUrl(releaseJson, UPDATE_MANIFEST_ASSET)
            ?: error("The latest green release does not contain $UPDATE_MANIFEST_ASSET.")
        val manifest = JSONObject(readText(manifestUrl, githubApi = false))
        val apkAssetName = manifest.getString("apkAssetName")
        val apkUrl = assetUrl(releaseJson, apkAssetName)
            ?: error("The latest green release does not contain $apkAssetName.")

        val release = GreenRelease(
            tagName = releaseJson.getString("tag_name"),
            releaseName = releaseJson.optString("name", releaseJson.getString("tag_name")),
            releaseHtmlUrl = releaseJson.getString("html_url"),
            versionCode = manifest.getLong("versionCode"),
            versionName = manifest.getString("versionName"),
            runNumber = manifest.getLong("runNumber"),
            commitSha = manifest.getString("commitSha"),
            apkAssetName = apkAssetName,
            apkDownloadUrl = apkUrl,
            apkSha256 = normalizeDigest(manifest.getString("apkSha256")),
            certificateSha256 = normalizeDigest(manifest.getString("certificateSha256"))
        )

        check(release.versionCode > 0L) { "The green release has an invalid versionCode." }
        check(release.versionName.isNotBlank()) { "The green release has an empty versionName." }
        check(release.apkSha256.length == 64) { "The green release APK SHA-256 is invalid." }
        check(release.certificateSha256.length == 64) { "The green release signing certificate SHA-256 is invalid." }
        return release
    }

    fun downloadAndVerify(release: GreenRelease): File {
        val updatesDir = File(context.filesDir, "updates").apply { mkdirs() }
        val partial = File(updatesDir, "true-ram-usage-${release.versionCode}.apk.part")
        val target = File(updatesDir, "true-ram-usage-${release.versionCode}.apk")
        partial.delete()

        val connection = openConnection(release.apkDownloadUrl, githubApi = false)
        try {
            ensureSuccessful(connection)
            connection.inputStream.use { input ->
                partial.outputStream().buffered().use { output ->
                    input.copyTo(output)
                }
            }
        } finally {
            connection.disconnect()
        }

        val actualSha = sha256(partial)
        if (actualSha != release.apkSha256) {
            partial.delete()
            error("Downloaded APK SHA-256 did not match the green release manifest.")
        }

        verifyApk(
            apk = partial,
            expectedVersionCode = release.versionCode,
            expectedCertificateSha256 = release.certificateSha256
        )

        target.delete()
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
        return target
    }

    fun verifyStaged(update: StagedUpdate): File {
        val apk = File(update.apkPath)
        check(apk.isFile) { "The staged update APK is no longer available." }
        check(sha256(apk) == normalizeDigest(update.apkSha256)) { "The staged update APK SHA-256 no longer matches." }
        verifyApk(apk, update.versionCode, update.certificateSha256)
        return apk
    }

    fun installWithRoot(apk: File): RootInstallResult {
        val rootAccess = RootAccess()
        if (rootAccess.request() != RootState.GRANTED) {
            return RootInstallResult(false, "Root was not available for unattended installation.")
        }

        val tmpPath = "/data/local/tmp/true-ram-usage-update.apk"
        val source = shellQuote(apk.absolutePath)
        val destination = shellQuote(tmpPath)
        val command = "cp $source $destination && chmod 0644 $destination && pm install -r $destination; " +
            "code=\$?; rm -f $destination; exit \$code"
        val result = rootAccess.runResult(command, timeoutSeconds = 180)
            ?: return RootInstallResult(false, "The root installer command could not be started.")
        if (result.timedOut) return RootInstallResult(false, "The root package installation timed out.")

        val success = result.success && result.output.lineSequence().any { it.trim().equals("Success", ignoreCase = true) }
        return if (success) {
            RootInstallResult(true, "The verified green build was installed successfully.")
        } else {
            RootInstallResult(false, result.output.ifBlank { "Android rejected the root package update." })
        }
    }

    fun launchSystemInstaller(apk: File): SystemInstallLaunchResult {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                val settingsIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(settingsIntent)
                return SystemInstallLaunchResult.INSTALL_PERMISSION_REQUIRED
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apk
            )
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME_TYPE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(intent)
            SystemInstallLaunchResult.INSTALLER_LAUNCHED
        }.getOrDefault(SystemInstallLaunchResult.FAILED)
    }

    fun openReleasePage(url: String): Boolean = runCatching {
        if (url.isBlank()) return false
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)

    fun deleteStagedFile(update: StagedUpdate?) {
        update?.apkPath?.let { runCatching { File(it).delete() } }
    }

    private fun verifyApk(apk: File, expectedVersionCode: Long, expectedCertificateSha256: String) {
        val archive = archivePackageInfo(apk)
            ?: error("Android could not parse the downloaded APK.")
        check(archive.packageName == context.packageName) {
            "The downloaded APK package name does not match True RAM Usage."
        }
        check(versionCode(archive) == expectedVersionCode) {
            "The downloaded APK versionCode does not match the green release manifest."
        }

        val expectedCert = normalizeDigest(expectedCertificateSha256)
        val installedSigners = signerDigests(installedPackageInfo())
        val archiveSigners = signerDigests(archive)
        check(expectedCert in installedSigners) {
            "The release manifest signing certificate does not match the installed app."
        }
        check(expectedCert in archiveSigners) {
            "The downloaded APK is not signed with the installed True RAM Usage certificate."
        }
    }

    private fun installedPackageInfo(): PackageInfo = packageInfoFor(context.packageName)
        ?: error("The installed True RAM Usage package could not be read.")

    @Suppress("DEPRECATION")
    private fun packageInfoFor(packageName: String): PackageInfo? = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        packageManager.getPackageInfo(packageName, flags)
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun archivePackageInfo(apk: File): PackageInfo? = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else {
            info.signatures?.toList().orEmpty()
        }
        return signatures.map { signature ->
            val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    private fun assetUrl(releaseJson: JSONObject, assetName: String): String? {
        val assets = releaseJson.getJSONArray("assets")
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            if (asset.optString("name") == assetName) return asset.optString("browser_download_url").takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun readText(url: String, githubApi: Boolean): String {
        val connection = openConnection(url, githubApi)
        try {
            ensureSuccessful(connection)
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String, githubApi: Boolean): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "True-RAM-Usage-Updater")
            if (githubApi) {
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            } else {
                setRequestProperty("Accept", "application/octet-stream")
            }
        }

    private fun ensureSuccessful(connection: HttpURLConnection) {
        val code = connection.responseCode
        check(code in 200..299) { "Update server returned HTTP $code." }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun normalizeDigest(value: String): String = value.lowercase().replace(":", "").trim()

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    companion object {
        private const val LATEST_RELEASE_API = "https://api.github.com/repos/tbzmike/True-ram-usage/releases/latest"
        private const val UPDATE_MANIFEST_ASSET = "update.json"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
