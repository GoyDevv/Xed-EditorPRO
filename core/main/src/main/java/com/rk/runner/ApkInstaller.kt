package com.rk.runner

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Installs an APK produced by an Android build. Locates the freshly built debug APK under the
 * project's real path, copies it somewhere the app's FileProvider can serve (external files dir,
 * covered by file_paths' external-path), and fires the system package installer.
 *
 * The user still confirms the install in the system dialog (and grants "install unknown apps" the
 * first time) — this is the on-device equivalent of Android Studio's install-and-run.
 */
object ApkInstaller {

    /** Newest debug APK under the standard Gradle output locations, or null. */
    fun findApk(projectRealPath: String): File? {
        val dirs =
            listOf(
                File(projectRealPath, "app/build/outputs/apk/debug"),
                File(projectRealPath, "build/outputs/apk/debug"),
            )
        return dirs
            .flatMap { dir -> dir.listFiles { f -> f.isFile && f.extension.equals("apk", true) }?.toList() ?: emptyList() }
            .maxByOrNull { it.lastModified() }
    }

    /** Best-effort: find and launch the installer for the project's debug APK. Never throws. */
    fun install(context: Context, projectRealPath: String): Boolean {
        return runCatching {
                val apk = findApk(projectRealPath) ?: return false
                val dest = File(context.getExternalFilesDir(null), "run-install.apk")
                apk.copyTo(dest, overwrite = true)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dest)
                val intent =
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                context.startActivity(intent)
                true
            }
            .getOrElse {
                it.printStackTrace()
                false
            }
    }
}
