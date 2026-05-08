package com.lofipod.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.lofipod.app.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Pulls the release manifest from GitHub, compares it against the installed
 * version, and (when newer) downloads the APK to the app cache and hands the
 * file to the system package installer.
 *
 * The manifest URL is the stable "releases/latest/download/<file>" redirect
 * GitHub maintains — it always points at whichever release is the most
 * recent non-prerelease, so the app doesn't need to know specific tag names.
 *
 * "Newer" is decided by `versionCode`, not `versionName`. The release
 * workflow drives `versionCode` from `github.run_number` so it monotonically
 * increases regardless of how the human version label evolves.
 *
 * Android's only hard rule that affects this flow: APK install requires
 * user confirmation in the system installer dialog. The app cannot skip
 * that. We do everything up to launching the dialog.
 */
class UpdateChecker(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val settings = Settings(context)

    sealed class Result {
        /** Already on the latest version (or even newer locally — dev case). */
        data class UpToDate(val installed: Int, val latest: Int) : Result()

        /** A new release is available; APK has been downloaded to [apkFile]. */
        data class Updated(
            val apkFile: File,
            val versionName: String,
            val versionCode: Int,
            val releaseUrl: String,
        ) : Result()

        /** Network or parse failure; [message] suitable for surfacing. */
        data class Failed(val message: String) : Result()
    }

    /**
     * Single-shot check. Returns [Result.UpToDate] if no newer release,
     * [Result.Updated] if a newer APK was downloaded, [Result.Failed] on
     * any error (network, manifest parse, write).
     *
     * Does NOT install. Caller decides whether to immediately invoke
     * [launchInstaller] or surface a notification first.
     */
    suspend fun checkAndDownload(): Result = withContext(Dispatchers.IO) {
        val installedCode = installedVersionCode()
        try {
            val manifest = fetchManifest() ?: return@withContext Result.Failed(
                "Couldn't reach GitHub releases (no manifest)."
            )
            // Persist last-checked timestamp regardless of outcome so the
            // UI can show "Last checked: ..." even after a no-op run.
            settings.setUpdateLastCheckedAt(System.currentTimeMillis())

            if (manifest.versionCode <= installedCode) {
                return@withContext Result.UpToDate(installedCode, manifest.versionCode)
            }

            val apk = downloadApk(manifest.apkUrl, manifest.versionCode)
                ?: return@withContext Result.Failed("APK download failed.")

            settings.setUpdateAvailableVersionCode(manifest.versionCode)
            settings.setUpdateAvailableVersionName(manifest.versionName)
            Result.Updated(
                apkFile = apk,
                versionName = manifest.versionName,
                versionCode = manifest.versionCode,
                releaseUrl = manifest.releaseUrl,
            )
        } catch (e: Exception) {
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Hand the downloaded APK to the system package installer. Returns
     * `false` if "Install unknown apps" hasn't been granted to LofiPod —
     * caller should redirect the user to the system settings page (see
     * [openInstallUnknownAppsSettings]).
     */
    fun launchInstaller(apk: File): Boolean {
        if (!canRequestInstall()) return false
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            // FLAG_ACTIVITY_NEW_TASK so we can launch from contexts that
            // aren't an Activity (e.g. a worker callback).
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
        return true
    }

    /** True iff the OS will let us request a package install. */
    fun canRequestInstall(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else true

    /**
     * Fire the system Settings page where the user can toggle "Install
     * unknown apps" for LofiPod. They tap once, the toggle persists, and
     * subsequent installer launches succeed without revisiting Settings.
     */
    fun openInstallUnknownAppsSettings() {
        val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    private fun installedVersionCode(): Int {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkg.longVersionCode.toInt()
        } else {
            pkg.versionCode
        }
    }

    private data class Manifest(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val releaseUrl: String,
    )

    private fun fetchManifest(): Manifest? {
        val url = MANIFEST_URL
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val obj = JSONObject(body)
            return Manifest(
                versionCode = obj.getInt("versionCode"),
                versionName = obj.getString("versionName"),
                apkUrl = obj.getString("apkUrl"),
                releaseUrl = obj.optString("releaseUrl", ""),
            )
        }
    }

    /**
     * Download [apkUrl] into the app cache. The path is keyed by
     * [versionCode] so concurrent / re-checked downloads don't clobber a
     * partial file — and so the same file gets reused if the user dismisses
     * and re-opens the install dialog.
     *
     * **Integrity discipline.** Through v0.6.6 this method wrote directly to
     * the final filename and reused any non-empty cached file as-is. A
     * network drop mid-download left a partial APK that subsequent retries
     * happily reused, producing the system installer's classic "There's a
     * problem with the app file" error indefinitely. The fix:
     *
     *   1. Always download to `<file>.tmp`, then atomically rename to the
     *      final filename only after the byte stream completes. A partial
     *      download leaves only the .tmp behind, which is never returned to
     *      the caller.
     *   2. Validate downloaded length against the response's `Content-Length`
     *      header (when present). A short read = corrupt download = retry.
     *   3. Wipe stale .tmp files at every entry so an interrupted prior run
     *      can't leak across sessions.
     *   4. Validate the ZIP/APK header (`PK\x03\x04` magic) before reusing a
     *      cached file. Cheap (4-byte read) and catches the case where the
     *      cache directory was tampered with or the file was zero-padded by
     *      some external process.
     *
     * Trade-offs accepted: cache reuse still saves bandwidth on legitimate
     * re-checks, but correctness wins over bandwidth. A user with a stuck
     * cache can clear app storage as a last resort, but should never need to.
     */
    private fun downloadApk(apkUrl: String, versionCode: Int): File? {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "lofipod-$versionCode.apk")
        val tmp = File(dir, "lofipod-$versionCode.apk.tmp")

        // Drop any leftover .tmp from a prior interrupted run. Silent —
        // the file is by definition corrupt and we have a cleaner path now.
        if (tmp.exists()) tmp.delete()

        // Re-validate the cached file before returning it. Cheap header
        // check rules out the "zero-byte but non-zero length" and "HTML
        // error page got saved with .apk extension" cases.
        if (out.exists() && out.length() > 0L && looksLikeApk(out)) {
            return out
        }
        // Stale or invalid cache — purge and re-download.
        if (out.exists()) out.delete()

        val req = Request.Builder().url(apkUrl).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body ?: return null
            val expected = resp.header("Content-Length")?.toLongOrNull() ?: -1L
            tmp.outputStream().use { os -> body.byteStream().copyTo(os) }
            // Length check: short read = transport interrupted, file is not
            // safe to install.
            val actual = tmp.length()
            if (expected > 0L && actual != expected) {
                tmp.delete()
                return null
            }
            // Final-form validation: open the would-be APK and confirm the
            // header magic. Catches the rare "Content-Length matches but
            // body is HTML" case (e.g., proxy / captive portal returning a
            // 200 with HTML on a redirect).
            if (!looksLikeApk(tmp)) {
                tmp.delete()
                return null
            }
            // Atomic rename. After this the consumer sees a complete,
            // header-validated APK or no file at all — no in-between state.
            if (!tmp.renameTo(out)) {
                // Cross-filesystem rename can fail on some Android storage
                // configurations. Fall back to copy+delete which preserves
                // the all-or-nothing contract from the consumer's view.
                tmp.copyTo(out, overwrite = true)
                tmp.delete()
            }
        }
        return out
    }

    /**
     * 4-byte ZIP/APK local-file-header magic check. APK is a ZIP, and every
     * ZIP starts with `PK\x03\x04`. If the magic doesn't match, the file is
     * either truncated, an HTML error page disguised by Content-Type, or
     * something else entirely — none of which the system installer will
     * accept.
     */
    private fun looksLikeApk(f: File): Boolean {
        if (f.length() < 4L) return false
        return try {
            f.inputStream().use { input ->
                val b = ByteArray(4)
                if (input.read(b) != 4) return false
                b[0] == 0x50.toByte() && b[1] == 0x4B.toByte() &&
                    b[2] == 0x03.toByte() && b[3] == 0x04.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        // Stable redirect URL: "latest/download/<file>" always points at the
        // most-recent non-draft, non-prerelease release. Repo slug is
        // hardcoded — there's no other repo this app targets.
        const val MANIFEST_URL =
            "https://github.com/MannKablam/LofiPod/releases/latest/download/latest.json"
    }
}
