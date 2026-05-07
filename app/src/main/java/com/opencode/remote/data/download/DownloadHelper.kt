package com.opencode.remote.data.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment

object DownloadHelper {
    fun downloadApk(context: Context, url: String, filename: String): Long {
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle("OConnector Update")
            setDescription("Downloading $filename")
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
            setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setMimeType("application/vnd.android.package-archive")
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(request)
    }
}
