package com.younes.app.data

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.younes.app.BuildConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException

data class UpdateInfo(
    val version: String,
    val apkUrl: String
)

private data class GitHubAsset(
    val name: String = "",
    val browser_download_url: String = ""
)

private data class GitHubRelease(
    val tag_name: String = "",
    val assets: List<GitHubAsset> = emptyList()
)

object AppUpdateManager {
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/zakaria0n/cours/releases/latest"
    private const val APK_NAME = "younes-update.apk"

    private val client = OkHttpClient()
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun checkForUpdate(callback: (Result<UpdateInfo?>) -> Unit) {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Younes-Android-TV/${BuildConfig.VERSION_NAME}")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { callback(Result.failure(e)) }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        mainHandler.post {
                            callback(Result.failure(IOException("GitHub HTTP ${it.code}")))
                        }
                        return
                    }

                    val release = gson.fromJson(it.body?.charStream(), GitHubRelease::class.java)
                    val remoteVersion = release.tag_name.removePrefix("v")
                    val apk = release.assets.firstOrNull { asset ->
                        asset.name.endsWith(".apk", ignoreCase = true)
                    }
                    val update = if (
                        apk != null && isNewer(remoteVersion, BuildConfig.VERSION_NAME)
                    ) {
                        UpdateInfo(remoteVersion, apk.browser_download_url)
                    } else {
                        null
                    }
                    mainHandler.post { callback(Result.success(update)) }
                }
            }
        })
    }

    fun canInstallPackages(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            activity.packageManager.canRequestPackageInstalls()
        ) {
            return true
        }

        activity.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}")
            )
        )
        return false
    }

    fun downloadAndInstall(
        activity: Activity,
        update: UpdateInfo,
        onFinished: (Boolean) -> Unit
    ) {
        val target = File(
            activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            APK_NAME
        )
        if (target.exists()) target.delete()

        val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(update.apkUrl))
            .setTitle("Younes ${update.version}")
            .setDescription("Mise à jour de l'application")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            .setDestinationInExternalFilesDir(
                activity,
                Environment.DIRECTORY_DOWNLOADS,
                APK_NAME
            )

        val downloadId = manager.enqueue(request)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) {
                    return
                }
                runCatching { activity.unregisterReceiver(this) }

                val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId))
                val success = cursor.use {
                    it.moveToFirst() &&
                        it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) ==
                        DownloadManager.STATUS_SUCCESSFUL
                }
                if (success && target.exists()) {
                    installApk(activity, target)
                }
                onFinished(success)
            }
        }

        ContextCompat.registerReceiver(
            activity,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun installApk(activity: Activity, file: File) {
        val uri = FileProvider.getUriForFile(
            activity,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file
        )
        activity.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }

    private fun isNewer(remote: String, local: String): Boolean {
        val remoteParts = remote.split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
        val localParts = local.split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
        repeat(maxOf(remoteParts.size, localParts.size)) { index ->
            val remotePart = remoteParts.getOrElse(index) { 0 }
            val localPart = localParts.getOrElse(index) { 0 }
            if (remotePart != localPart) return remotePart > localPart
        }
        return false
    }
}
