package com.spectrefi.app

import android.app.Application
import android.os.Environment
import android.os.Process
import com.spectrefi.app.service.DatabaseDownloadService
import com.spectrefi.app.ui.dbsetup.DatabaseDownloadManager
import com.spectrefi.app.util.FileLogger
import com.spectrefi.app.util.GlobalExceptionHandler
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class WifiApplication : Application() {

    companion object {
        /** Outlives every screen; use it for work that must finish after a ViewModel dies. */
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Volatile
        var isRootAvailable: Boolean? = null
            private set

        suspend fun checkRootAccess(): Boolean {
            isRootAvailable?.let { return it }
            return try {
                Shell.getShell().isRoot.also { isRootAvailable = it }
            } catch (_: Exception) {
                false.also { isRootAvailable = false }
            }
        }

        fun resetRootCache() {
            isRootAvailable = null
        }
    }

    override fun onCreate() {
        super.onCreate()

        com.spectrefi.app.util.PerformanceManager.initialize(this)

        createStorageDir()

        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setInitializers(com.spectrefi.app.shell.ShellInitializer::class.java)
                .setTimeout(15)
        )

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionHandler(defaultHandler))
        setupNotificationWorker()
        resumeBackgroundDbDownloads()

        com.spectrefi.app.util.Log.i("WifiApplication", "Application started")
    }

    private fun resumeBackgroundDbDownloads() {
        try {
            val manager = DatabaseDownloadManager.getOrCreate(this)
            manager.restoreFromDisk()
            if (manager.hasNetworkWork()) {
                DatabaseDownloadService.start(this)
            }
        } catch (e: Exception) {
            com.spectrefi.app.util.Log.e(
                "WifiApplication",
                "Failed to resume background db downloads",
                e
            )
        }
    }

    private fun createStorageDir() {
        try {
            val base = Environment.getExternalStorageDirectory()
            val dirs = listOf(
                File(base, "SpectreFi/captured"),
                File(base, "SpectreFi/handshakes-storage"),
                File(base, "SpectreFi/logs")
            )
            for (dir in dirs) {
                if (!dir.exists()) {
                    dir.mkdirs()
                    com.spectrefi.app.util.Log.i(
                        "WifiApplication",
                        "Created storage dir: ${dir.absolutePath}"
                    )
                }
            }
        } catch (e: Exception) {
            com.spectrefi.app.util.Log.e(
                "WifiApplication",
                "Failed to create storage dir",
                e
            )
        }
    }

    override fun onTerminate() {
        com.spectrefi.app.util.Log.i("WifiApplication", "Application terminating")
        if (FileLogger.isLoggingEnabled()) {
            FileLogger.stop()
        }
        super.onTerminate()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        com.spectrefi.app.util.Log.w("WifiApplication", "Low memory warning")
        if (FileLogger.isLoggingEnabled()) {
            FileLogger.logMemoryInfo()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.spectrefi.app.util.Log.w(
            "WifiApplication",
            "Memory trim requested, level: $level"
        )
        if (FileLogger.isLoggingEnabled()) {
            FileLogger.logMemoryInfo()
        }
    }

    suspend fun fixDataDirOwnershipIfNeeded() {
        try {
            val uid = Process.myUid()
            val dataDir = filesDir.absolutePath
            val parentDir = filesDir.parentFile?.absolutePath ?: return

            val result = Shell.cmd(
                "stat -c '%u' '$dataDir'",
                "stat -c '%u' '$parentDir'"
            ).exec()

            val fileUid = result.out.getOrNull(0)?.trim()
            val parentUid = result.out.getOrNull(1)?.trim()

            if (fileUid != null && fileUid != uid.toString()) {
                com.spectrefi.app.util.Log.w(
                    "WifiApplication",
                    "Fixing files dir ownership: $fileUid → $uid"
                )
                Shell.cmd("chown -R $uid:$uid '$dataDir' 2>/dev/null || true").exec()
            }
            if (parentUid != null && parentUid != uid.toString()) {
                com.spectrefi.app.util.Log.w(
                    "WifiApplication",
                    "Fixing data dir ownership: $parentUid → $uid"
                )
                Shell.cmd("chown $uid:$uid '$parentDir' 2>/dev/null || true").exec()
            }
        } catch (e: Exception) {
            com.spectrefi.app.util.Log.e(
                "WifiApplication",
                "Failed to fix data dir ownership", e
            )
        }
    }

    private fun setupNotificationWorker() {
        val workRequest =
            androidx.work.PeriodicWorkRequestBuilder<com.spectrefi.app.workers.NotificationWorker>(
                12, java.util.concurrent.TimeUnit.HOURS,
                2, java.util.concurrent.TimeUnit.HOURS
            ).build()

        androidx.work.WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork(
                "notification_check",
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
    }
}
