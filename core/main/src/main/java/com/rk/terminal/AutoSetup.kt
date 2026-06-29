package com.rk.terminal

import android.app.Activity
import android.os.Build
import com.rk.XedConstants
import com.rk.exec.TerminalCommand
import com.rk.exec.isTerminalInstalled
import com.rk.exec.launchTerminal
import com.rk.file.child
import com.rk.file.localBinDir
import com.rk.utils.application
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** A rough, human-readable estimate shown in the Auto Setup consent dialog. */
data class SetupEstimate(val storageText: String, val dataText: String, val etaText: String?)

/**
 * Entry point + helpers for the first-launch Auto Setup flow.
 *
 * [launch] kicks off the sandbox + apt setup in the terminal (progress is mirrored into
 * [AutoSetupState] and shown by [AutoSetupOverlay]). [estimate] computes the storage/data/time
 * figures for the consent dialog, and [issueUrl] builds a prefilled GitHub issue for failures.
 */
object AutoSetup {

    // Rough byte sizes used for estimates when exact figures aren't available.
    private const val MB = 1024.0 * 1024.0
    private const val ROOTFS_DOWNLOAD_FALLBACK = 55L * 1024 * 1024 // ~55 MB compressed rootfs
    private const val ROOTFS_EXTRACTED = 520L * 1024 * 1024 // ~520 MB extracted
    private const val APT_DOWNLOAD = 170L * 1024 * 1024 // ~170 MB apt upgrade + tools
    private const val APT_INSTALLED = 260L * 1024 * 1024 // ~260 MB installed on disk
    private const val APT_FIXED_SECONDS = 90 // non-download time (extract/configure)

    private fun rootfsUrl(): String {
        val abi = Build.SUPPORTED_ABIS
        return when {
            abi.contains("x86_64") -> XedConstants.ROOTFS_X64
            abi.contains("arm64-v8a") -> XedConstants.ROOTFS_ARM64
            else -> XedConstants.ROOTFS_ARM
        }
    }

    /** Launch the terminal and run the auto-setup script. Safe to call from the main thread. */
    fun launch(activity: Activity) {
        setupAssetFile("auto_setup")
        AutoSetupState.begin(AutoSetupState.SESSION_ID)
        launchTerminal(
            activity = activity,
            terminalCommand =
                TerminalCommand(
                    sandbox = true,
                    exe = "/bin/bash",
                    args = arrayOf(localBinDir().child("auto_setup").absolutePath),
                    id = AutoSetupState.SESSION_ID,
                    terminatePreviousSession = true,
                ),
        )
    }

    private fun humanSize(bytes: Long): String {
        val mb = bytes / MB
        return if (mb >= 1024) String.format(Locale.US, "%.1f GB", mb / 1024) else "${mb.toInt()} MB"
    }

    /**
     * Build the storage/data/time estimate. Performs a short network probe (content length + a
     * quick speed sample) on an IO dispatcher; always returns something usable even offline.
     */
    suspend fun estimate(): SetupEstimate =
        withContext(Dispatchers.IO) {
            val sandboxInstalled = runCatching { isTerminalInstalled() }.getOrDefault(false)

            var rootfsDownload = ROOTFS_DOWNLOAD_FALLBACK
            var speedBytesPerSec: Double? = null

            if (!sandboxInstalled) {
                runCatching {
                    val client =
                        OkHttpClient.Builder()
                            .connectTimeout(8, TimeUnit.SECONDS)
                            .readTimeout(8, TimeUnit.SECONDS)
                            .build()

                    // Exact compressed download size.
                    client.newCall(Request.Builder().url(rootfsUrl()).head().build()).execute().use { resp ->
                        resp.header("Content-Length")?.toLongOrNull()?.let { if (it > 0) rootfsDownload = it }
                    }

                    // Quick speed sample: read ~1.5 MB and time it.
                    val start = System.nanoTime()
                    var read = 0L
                    client.newCall(Request.Builder().url(rootfsUrl()).build()).execute().use { resp ->
                        resp.body.byteStream().use { input ->
                            val buf = ByteArray(16 * 1024)
                            while (read < 1_500_000) {
                                val n = input.read(buf)
                                if (n < 0) break
                                read += n
                            }
                        }
                    }
                    val secs = (System.nanoTime() - start) / 1_000_000_000.0
                    if (read > 0 && secs > 0.05) speedBytesPerSec = read / secs
                }
            }

            val dataBytes = (if (sandboxInstalled) 0L else rootfsDownload) + APT_DOWNLOAD
            val storageBytes = (if (sandboxInstalled) 0L else ROOTFS_EXTRACTED) + APT_INSTALLED

            val etaText =
                speedBytesPerSec?.let { speed ->
                    val downloadSecs = dataBytes / speed
                    val total = (downloadSecs + APT_FIXED_SECONDS).toInt()
                    val mins = total / 60
                    val secs = total % 60
                    if (mins > 0) "~$mins min" else "~$secs sec"
                }

            SetupEstimate(
                storageText = humanSize(storageBytes),
                dataText = humanSize(dataBytes),
                etaText = etaText,
            )
        }

    /** A prefilled GitHub "new issue" URL containing the failure log and device information. */
    fun issueUrl(log: String): String {
        val device = buildDeviceInfo()
        // Keep the log within a sane URL length; GitHub/browsers cap around 8 KB.
        val trimmedLog = if (log.length > 5000) "...(truncated)...\n" + log.takeLast(5000) else log
        val body =
            buildString {
                append("**Auto Setup failed**\n\n")
                append("```log\n")
                append(trimmedLog)
                append("\n```\n\n")
                append("### Device info\n")
                append(device)
            }
        val title = URLEncoder.encode("Auto Setup failed", StandardCharsets.UTF_8.toString())
        val encodedBody = URLEncoder.encode(body, StandardCharsets.UTF_8.toString())
        return "${XedConstants.GITHUB_REPO}/issues/new?title=$title&body=$encodedBody"
    }

    private fun buildDeviceInfo(): String {
        val versionName =
            runCatching {
                    val pm = application!!.packageManager
                    pm.getPackageInfo(application!!.packageName, 0).versionName
                }
                .getOrNull() ?: "?"
        return buildString {
            append("- App version: ").append(versionName).append('\n')
            append("- Android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
            append("- Manufacturer: ").append(Build.MANUFACTURER).append('\n')
            append("- Brand: ").append(Build.BRAND).append('\n')
            append("- Model: ").append(Build.MODEL).append('\n')
            append("- ABIs: ").append(Build.SUPPORTED_ABIS.joinToString(", ")).append('\n')
            append("- Locale: ").append(Locale.getDefault().toString()).append('\n')
            append("- Country: ").append(Locale.getDefault().country).append('\n')
            append("- Timezone: ").append(TimeZone.getDefault().id).append('\n')
        }
    }
}
