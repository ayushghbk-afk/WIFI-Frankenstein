package com.lsd.wififrankenstein.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection

class DownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeDownloads = ConcurrentHashMap<String, Job>()
    private val notificationManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }
    private val notificationProgress = ConcurrentHashMap<String, Int>()
    private val lastNotificationUpdate = ConcurrentHashMap<String, Long>()
    private val lastBroadcastUpdate = ConcurrentHashMap<String, Long>()

    companion object {
        private const val TAG = "DownloadService"
        private const val NOTIFICATION_ID = 1001
        private const val AGGREGATE_NOTIFICATION_ID = 1000
        private const val COMPLETION_NOTIFICATION_BASE_ID = 2000
        private const val CHANNEL_ID = "download_channel"
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 2000L
        private const val PROGRESS_UPDATE_THRESHOLD = 10
        private const val BROADCAST_UPDATE_INTERVAL_MS = 250L

        const val ACTION_START_DOWNLOAD = "start_download"
        const val ACTION_CANCEL_DOWNLOAD = "cancel_download"
        const val ACTION_CANCEL_ALL_DOWNLOADS = "cancel_all_downloads"

        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_DOWNLOAD_URL = "download_url"
        const val EXTRA_SERVER_VERSION = "server_version"
        const val EXTRA_SHA256 = "sha256"

        private const val MAX_REDIRECTS = 5
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val TEMP_SUFFIX = ".part"

        /** Only these extensions may be written into the app's private storage. */
        private val ALLOWED_EXTENSIONS = setOf(
            "dic", "db", "sqlite", "sqlite3", "gz", "xz", "zip", "7z",
            "json", "txt", "csv", "apk"
        )

        private val SAFE_FILE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

        const val BROADCAST_DOWNLOAD_PROGRESS = "download_progress"
        const val BROADCAST_DOWNLOAD_COMPLETE = "download_complete"
        const val BROADCAST_DOWNLOAD_ERROR = "download_error"
        const val BROADCAST_DOWNLOAD_CANCELLED = "download_cancelled"

        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_ERROR_MESSAGE = "error_message"

        fun startDownload(
            context: Context,
            fileName: String,
            downloadUrl: String,
            serverVersion: String,
            sha256: String? = null
        ) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_FILE_NAME, fileName)
                putExtra(EXTRA_DOWNLOAD_URL, downloadUrl)
                putExtra(EXTRA_SERVER_VERSION, serverVersion)
                if (!sha256.isNullOrBlank()) putExtra(EXTRA_SHA256, sha256)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancelDownload(context: Context, fileName: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
                putExtra(EXTRA_FILE_NAME, fileName)
            }
            context.startService(intent)
        }

        fun cancelAllDownloads(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL_ALL_DOWNLOADS
            }
            context.startService(intent)
        }

        @Volatile
        private var instance: DownloadService? = null

        fun hasActiveDownloads(context: Context): Boolean {
            return instance?.activeDownloads?.isNotEmpty() == true
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The service is always started with startForegroundService(), so a foreground
        // notification must be posted immediately - even when notifications are denied.
        ensureForeground()

        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: return START_NOT_STICKY
                val downloadUrl =
                    intent.getStringExtra(EXTRA_DOWNLOAD_URL) ?: return START_NOT_STICKY
                val serverVersion =
                    intent.getStringExtra(EXTRA_SERVER_VERSION) ?: return START_NOT_STICKY
                val sha256 = intent.getStringExtra(EXTRA_SHA256)

                if (!activeDownloads.containsKey(fileName)) {
                    startDownload(fileName, downloadUrl, serverVersion, sha256)
                }
            }

            ACTION_CANCEL_DOWNLOAD -> {
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: return START_NOT_STICKY
                cancelDownload(fileName)
            }

            ACTION_CANCEL_ALL_DOWNLOADS -> {
                cancelAllDownloads()
            }
        }

        return START_STICKY
    }

    private fun startDownload(
        fileName: String,
        downloadUrl: String,
        serverVersion: String,
        sha256: String?
    ) {
        val destination = resolveDestination(fileName)
        if (destination == null) {
            Log.e(TAG, "Refusing unsafe download target: $fileName")
            broadcastError(fileName, getString(R.string.download_error_unknown))
            return
        }

        val job = serviceScope.launch {
            val tempFile = File(destination.parentFile, destination.name + TEMP_SUFFIX)
            try {
                showInitialNotification(fileName)

                val connection = openConnection(downloadUrl)
                val fileSize = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L

                connection.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalBytesRead = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            if (!isActive) break

                            output.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            val progress = if (fileSize > 0) {
                                (totalBytesRead * 100 / fileSize).toInt()
                            } else {
                                -1
                            }

                            updateNotificationOptimized(fileName, progress)
                            broadcastProgressOptimized(fileName, progress)
                        }
                    }
                }

                if (!isActive) return@launch

                if (!sha256.isNullOrBlank()) {
                    val actual = sha256Of(tempFile)
                    if (!actual.equals(sha256.trim(), ignoreCase = true)) {
                        Log.e(TAG, "Checksum mismatch for $fileName")
                        tempFile.delete()
                        broadcastError(fileName, getString(R.string.download_error_checksum))
                        cancelNotification(fileName)
                        return@launch
                    }
                }

                if (destination.exists()) destination.delete()
                if (!tempFile.renameTo(destination)) {
                    tempFile.copyTo(destination, overwrite = true)
                    tempFile.delete()
                }

                updateFileVersion(fileName, serverVersion)
                broadcastComplete(fileName)
                cancelNotification(fileName)
                showCompletionNotification(fileName)

            } catch (e: CancellationException) {
                tempFile.delete()
                throw e
            } catch (e: Exception) {
                tempFile.delete()
                Log.e(TAG, "Download failed for $fileName", e)
                broadcastError(fileName, e.message ?: getString(R.string.download_error_unknown))
                cancelNotification(fileName)
            } finally {
                activeDownloads.remove(fileName)
                notificationProgress.remove(fileName)
                lastNotificationUpdate.remove(fileName)
                lastBroadcastUpdate.remove(fileName)
                if (activeDownloads.isEmpty()) {
                    stopSelf()
                }
            }
        }

        activeDownloads[fileName] = job
    }

    /**
     * Maps an untrusted file name onto a file directly inside the app's private storage.
     * The name arrives from the remote update manifest, so "../" and unexpected extensions
     * are rejected instead of being interpreted as a path.
     */
    private fun resolveDestination(fileName: String): File? {
        val name = fileName.trim()
        if (!SAFE_FILE_NAME.matches(name)) return null
        if (name.contains("..")) return null
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension !in ALLOWED_EXTENSIONS) return null
        val file = File(filesDir, name)
        return if (file.canonicalFile.parentFile == filesDir.canonicalFile) file else null
    }

    /**
     * Follows redirects manually so every hop can be validated: https only, bounded depth,
     * and no trust-all TLS configuration.
     */
    private fun openConnection(downloadUrl: String): HttpURLConnection {
        var url = URL(downloadUrl)
        for (hop in 0..MAX_REDIRECTS) {
            if (!url.protocol.equals("https", ignoreCase = true)) {
                throw IllegalArgumentException("Only https downloads are allowed")
            }
            val connection = url.openConnection() as? HttpsURLConnection
                ?: throw IllegalArgumentException("Unexpected connection type")
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS

            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) throw IllegalArgumentException("Redirect without Location")
                url = URL(url, location)
                continue
            }
            if (code >= 400) {
                connection.disconnect()
                throw IllegalArgumentException("HTTP $code")
            }
            return connection
        }
        throw IllegalArgumentException("Too many redirects")
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } > 0) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private var foregroundNotificationShown = false

    private fun ensureForeground() {
        if (foregroundNotificationShown) return
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_file_download)
            .setContentTitle(getString(R.string.downloading_file))
            .setOngoing(true)
            .build()
        try {
            startForeground(AGGREGATE_NOTIFICATION_ID, notification)
            foregroundNotificationShown = true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
    }

    private fun cancelDownload(fileName: String) {
        activeDownloads[fileName]?.cancel()
        activeDownloads.remove(fileName)
        notificationProgress.remove(fileName)
        lastNotificationUpdate.remove(fileName)
        lastBroadcastUpdate.remove(fileName)
        broadcastCancelled(fileName)
        cancelNotification(fileName)

        if (activeDownloads.isEmpty()) {
            stopSelf()
        }
    }

    private fun cancelAllDownloads() {
        activeDownloads.values.forEach { it.cancel() }
        activeDownloads.clear()
        notificationProgress.clear()
        lastNotificationUpdate.clear()
        lastBroadcastUpdate.clear()
        cancelAllNotifications()
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.download_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.download_notification_channel_description)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun showInitialNotification(fileName: String) {
        if (!hasNotificationPermission()) {
            Log.w(TAG, "No notification permission, skipping notification")
            return
        }

        notificationProgress[fileName] = 0
        lastNotificationUpdate[fileName] = System.currentTimeMillis()
        lastBroadcastUpdate[fileName] = System.currentTimeMillis()
        showNotification(fileName, 0)
    }

    private fun updateNotificationOptimized(fileName: String, progress: Int) {
        if (!hasNotificationPermission()) {
            return
        }

        val lastProgress = notificationProgress[fileName] ?: 0
        val lastUpdateTime = lastNotificationUpdate[fileName] ?: 0
        val currentTime = System.currentTimeMillis()

        val progressDifference = kotlin.math.abs(progress - lastProgress)
        val timeDifference = currentTime - lastUpdateTime

        val shouldUpdate = when {
            progress <= 0 -> true
            progress == 100 -> true
            progressDifference >= PROGRESS_UPDATE_THRESHOLD && timeDifference >= NOTIFICATION_UPDATE_INTERVAL_MS -> true
            timeDifference >= (NOTIFICATION_UPDATE_INTERVAL_MS * 2) -> true
            else -> false
        }

        if (shouldUpdate) {
            notificationProgress[fileName] = progress
            lastNotificationUpdate[fileName] = currentTime
            updateNotification(fileName, progress)
        }
    }

    private fun showNotification(fileName: String, progress: Int) {
        if (!hasNotificationPermission()) {
            return
        }

        val notificationId = getNotificationId(fileName)
        val cancelIntent = Intent(this, DownloadService::class.java).apply {
            action = ACTION_CANCEL_DOWNLOAD
            putExtra(EXTRA_FILE_NAME, fileName)
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            notificationId,
            cancelIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val isIndeterminate = progress <= 0
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_file_download)
            .setContentTitle(getString(R.string.downloading_file))
            .setContentText(fileName)
            .setProgress(
                if (isIndeterminate) 0 else 100,
                if (isIndeterminate) 0 else progress,
                isIndeterminate
            )
            .setOngoing(true)
            .addAction(R.drawable.ic_close, getString(R.string.cancel), cancelPendingIntent)
            .build()

        try {
            if (activeDownloads.size == 1) {
                startForeground(notificationId, notification)
            } else {
                notificationManager.notify(notificationId, notification)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to show notification due to missing permission", e)
        }
    }

    private fun updateNotification(fileName: String, progress: Int) {
        if (!hasNotificationPermission()) {
            return
        }

        val notificationId = getNotificationId(fileName)
        val cancelIntent = Intent(this, DownloadService::class.java).apply {
            action = ACTION_CANCEL_DOWNLOAD
            putExtra(EXTRA_FILE_NAME, fileName)
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            notificationId,
            cancelIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val isIndeterminate = progress <= 0
        val progressText = if (isIndeterminate && progress < 0) {
            getString(R.string.downloading_file)
        } else {
            getString(R.string.downloading_file_progress, fileName, progress.coerceAtLeast(0))
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_file_download)
            .setContentTitle(getString(R.string.downloading_file))
            .setContentText(progressText)
            .setProgress(
                if (isIndeterminate) 0 else 100,
                if (isIndeterminate) 0 else progress,
                isIndeterminate
            )
            .setOngoing(true)
            .addAction(R.drawable.ic_close, getString(R.string.cancel), cancelPendingIntent)
            .build()

        try {
            notificationManager.notify(notificationId, notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to update notification due to missing permission", e)
        }
    }

    private fun showCompletionNotification(fileName: String) {
        if (!hasNotificationPermission()) {
            return
        }

        val completionId = getCompletionNotificationId(fileName)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_check)
            .setContentTitle(getString(R.string.download_complete))
            .setContentText(getString(R.string.file_downloaded_successfully, fileName))
            .setAutoCancel(true)
            .setOngoing(false)
            .build()

        try {
            notificationManager.notify(completionId, notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to show completion notification due to missing permission", e)
        }
    }

    private fun cancelNotification(fileName: String) {
        val notificationId = getNotificationId(fileName)
        notificationManager.cancel(notificationId)
    }

    private fun cancelAllNotifications() {
        activeDownloads.keys.forEach { fileName ->
            cancelNotification(fileName)
        }
        notificationManager.cancelAll()
        stopForeground(true)
    }

    private fun broadcastProgress(fileName: String, progress: Int) {
        val intent = Intent(BROADCAST_DOWNLOAD_PROGRESS).apply {
            putExtra(EXTRA_FILE_NAME, fileName)
            putExtra(EXTRA_PROGRESS, progress)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastProgressOptimized(fileName: String, progress: Int) {
        val lastBroadcastTime = lastBroadcastUpdate[fileName] ?: 0
        val currentTime = System.currentTimeMillis()
        val timeDifference = currentTime - lastBroadcastTime

        val shouldBroadcast = when {
            progress <= 0 || progress == 100 -> true
            timeDifference >= BROADCAST_UPDATE_INTERVAL_MS -> true
            else -> false
        }

        if (shouldBroadcast) {
            lastBroadcastUpdate[fileName] = currentTime
            broadcastProgress(fileName, progress)
        }
    }

    private fun broadcastComplete(fileName: String) {
        val intent = Intent(BROADCAST_DOWNLOAD_COMPLETE).apply {
            putExtra(EXTRA_FILE_NAME, fileName)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastError(fileName: String, errorMessage: String) {
        val intent = Intent(BROADCAST_DOWNLOAD_ERROR).apply {
            putExtra(EXTRA_FILE_NAME, fileName)
            putExtra(EXTRA_ERROR_MESSAGE, errorMessage)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastCancelled(fileName: String) {
        val intent = Intent(BROADCAST_DOWNLOAD_CANCELLED).apply {
            putExtra(EXTRA_FILE_NAME, fileName)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun updateFileVersion(fileName: String, newVersion: String) {
        val versionFileName = "${fileName.substringBeforeLast(".")}_version.json"
        val versionJson = JSONObject().put("version", newVersion)
        openFileOutput(versionFileName, MODE_PRIVATE).use { output ->
            output.write(versionJson.toString().toByteArray())
        }
    }

    fun hasActiveDownloads(): Boolean = activeDownloads.isNotEmpty()

    private fun getNotificationId(fileName: String): Int {
        return NOTIFICATION_ID + fileName.hashCode() % 1000
    }

    private fun getCompletionNotificationId(fileName: String): Int {
        return COMPLETION_NOTIFICATION_BASE_ID + fileName.hashCode() % 1000
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        serviceScope.cancel()
        cancelAllNotifications()
        notificationProgress.clear()
        lastNotificationUpdate.clear()
        lastBroadcastUpdate.clear()
    }
}
