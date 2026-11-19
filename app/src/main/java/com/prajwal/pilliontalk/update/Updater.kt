package com.prajwal.pilliontalk.update

import android.Manifest
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import com.prajwal.pilliontalk.MainActivity
import com.prajwal.pilliontalk.PillionTalkApplication
import com.prajwal.pilliontalk.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Where the updater is, for the banner on the home screen. */
sealed interface UpdateState {
    /** Up to date, offline, or not checked yet. Shows nothing. */
    data object None : UpdateState
    /** Found an update, but it only downloads by itself on Wi-Fi. */
    data class WaitingForWifi(val versionName: String, val notes: String) : UpdateState
    data class Downloading(val versionName: String, val progress: Float) : UpdateState
    data class Ready(val versionName: String, val notes: String) : UpdateState
    /** Waiting for you to switch on "Allow from this source" in Settings. */
    data object NeedsPermission : UpdateState
    data object Installing : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Keeps the app up to date from GitHub, without the Play Store.
 *
 * 1. check(): read the small version.json that GitHub Actions publishes next to the APK, and
 *    compare its versionCode with ours.
 * 2. If it's newer, hand the APK download to Android's DownloadManager: the same system service
 *    a browser uses. Android does the downloading, so it carries on if you leave the app or
 *    Android closes it, and shows progress in the notification shade. It waits for Wi-Fi
 *    unless you tap "Download now".
 * 3. When it's done, DownloadDoneReceiver hears about it and posts "Update ready · Tap to install".
 * 4. install(): give the APK to Android's PackageInstaller, which checks it's signed with the same
 *    key as the installed app and replaces it. Once this app installed itself, Android 12+ can
 *    skip the "Update this app?" prompt.
 *
 * Because the app can be closed between these steps, what's in flight (which version, which
 * download) is saved in SharedPreferences instead of just in memory.
 */
class Updater(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = context.getSharedPreferences("updater", Context.MODE_PRIVATE)
    private val downloads = context.getSystemService(DownloadManager::class.java)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.None)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var lastCheckAt = 0L
    private var progressJob: Job? = null

    /** The version people see, e.g. "0.9.12", for the home screen's footer. */
    val installedVersionName: String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    private val installedVersionCode: Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    // --- What's saved between launches ------------------------------------------------------------

    private var pending: Release?
        get() = if (prefs.contains(KEY_CODE)) {
            Release(prefs.getLong(KEY_CODE, 0), prefs.getString(KEY_NAME, "").orEmpty(), prefs.getString(KEY_NOTES, "").orEmpty())
        } else {
            null
        }
        set(value) = prefs.edit {
            if (value == null) {
                remove(KEY_CODE); remove(KEY_NAME); remove(KEY_NOTES)
            } else {
                putLong(KEY_CODE, value.versionCode); putString(KEY_NAME, value.versionName); putString(KEY_NOTES, value.notes)
            }
        }

    private var downloadId: Long
        get() = prefs.getLong(KEY_DOWNLOAD, -1)
        set(value) = prefs.edit { putLong(KEY_DOWNLOAD, value) }

    private fun apkFile(release: Release) = File(context.getExternalFilesDir(null), "update-${release.versionCode}.apk")

    // --- The steps ----------------------------------------------------------------------------------

    /**
     * Look for a newer build. Called whenever the home screen comes into view; asks GitHub at
     * most every 10 minutes. With no internet it just fails quietly: this app is meant to work offline.
     */
    fun check() {
        if (_state.value is UpdateState.Installing) return
        refresh() // show what's already in flight right away, before GitHub answers
        if (lastCheckAt != 0L && SystemClock.elapsedRealtime() - lastCheckAt < CHECK_EVERY_MS) return
        lastCheckAt = SystemClock.elapsedRealtime()
        scope.launch {
            val latest = withContext(Dispatchers.IO) { runCatching { fetchLatest() }.getOrNull() } ?: return@launch
            if (latest.versionCode <= installedVersionCode) return@launch
            if (pending?.versionCode != latest.versionCode) startDownload(latest, wifiOnly = true)
            refresh()
        }
    }

    /** Called when the app comes back to the front. */
    fun onResume() {
        // You may have just switched on "Allow from this source": carry on installing.
        if (_state.value == UpdateState.NeedsPermission && context.packageManager.canRequestPackageInstalls()) {
            install()
        } else {
            check()
        }
    }

    /** The banner's button. What it does depends on where we are. */
    fun onBannerAction() {
        val release = pending ?: return
        when (_state.value) {
            is UpdateState.Ready, UpdateState.NeedsPermission -> install()
            is UpdateState.Failed -> if (apkFile(release).exists()) install() else startDownload(release, wifiOnly = false)
            is UpdateState.WaitingForWifi -> startDownload(release, wifiOnly = false) // "Download now", on mobile data
            else -> Unit
        }
        refresh()
    }

    private fun startDownload(release: Release, wifiOnly: Boolean) {
        // Clear out any earlier download (a different version, or the same one being restarted).
        if (downloadId != -1L) downloads.remove(downloadId)
        context.getExternalFilesDir(null)?.listFiles { f -> f.name.startsWith("update-") }?.forEach { it.delete() }

        val request = DownloadManager.Request(Uri.parse(APK_URL))
            .setTitle("Pillion Talk ${release.versionName}")
            .setDescription("Downloading update")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(!wifiOnly) // "metered" = mobile data, which can cost money
            // The app's own folder: no storage permission needed, and it's deleted with the app.
            .setDestinationUri(Uri.fromFile(apkFile(release)))
        pending = release
        downloadId = downloads.enqueue(request)
    }

    /**
     * Ask DownloadManager how the download is going, and set the state to match. While it's
     * still going, ask again every half second (only while the app is running; the download
     * itself doesn't need us).
     */
    fun refresh() {
        val release = pending
        if (release == null || release.versionCode <= installedVersionCode) {
            // Nothing pending, or we're already running it (the update installed): tidy up.
            if (release != null) cleanUp()
            if (_state.value !is UpdateState.Failed) _state.value = UpdateState.None
            return
        }
        if (_state.value == UpdateState.Installing || _state.value == UpdateState.NeedsPermission) return

        val progress = queryDownload()
        _state.value = when (progress) {
            is DownloadProgress.Running -> UpdateState.Downloading(release.versionName, progress.fraction)
            DownloadProgress.WaitingForWifi -> UpdateState.WaitingForWifi(release.versionName, release.notes)
            DownloadProgress.Done -> UpdateState.Ready(release.versionName, release.notes)
            DownloadProgress.Failed -> UpdateState.Failed("The download didn't finish")
        }
        if (progress is DownloadProgress.Running && progressJob?.isActive != true) {
            progressJob = scope.launch {
                while (isActive && _state.value is UpdateState.Downloading) {
                    delay(500)
                    refresh()
                }
            }
        }
    }

    private fun queryDownload(): DownloadProgress {
        if (pending?.let { apkFile(it).exists() } == true && downloadId == -1L) return DownloadProgress.Done
        downloads.query(DownloadManager.Query().setFilterById(downloadId)).use { row ->
            if (!row.moveToFirst()) return DownloadProgress.Failed // removed, e.g. cancelled from the shade
            val status = row.getInt(row.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val reason = row.getInt(row.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val done = row.getLong(row.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = row.getLong(row.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return when {
                status == DownloadManager.STATUS_SUCCESSFUL -> DownloadProgress.Done
                status == DownloadManager.STATUS_FAILED -> DownloadProgress.Failed
                status == DownloadManager.STATUS_PAUSED && reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI -> DownloadProgress.WaitingForWifi
                else -> DownloadProgress.Running(if (total > 0) done.toFloat() / total else 0f)
            }
        }
    }

    /** DownloadDoneReceiver calls this. Runs even if the app was closed (Android starts it for us). */
    internal fun onDownloadFinished(id: Long) {
        if (id != downloadId) return
        refresh()
        val release = pending ?: return
        if (_state.value is UpdateState.Ready) notifyReady(release)
    }

    fun install() {
        val release = pending ?: return
        val apk = apkFile(release)
        if (!apk.exists()) return
        if (!context.packageManager.canRequestPackageInstalls()) {
            // Open the "Install unknown apps" switch for this app. onResume() continues from here.
            _state.value = UpdateState.NeedsPermission
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        _state.value = UpdateState.Installing
        scope.launch {
            try {
                withContext(Dispatchers.IO) { startInstall(apk) }
                // From here Android takes over. If it works, this app is closed and replaced.
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(e.message ?: "Android couldn't install the update")
            }
        }
    }

    /** InstallResultReceiver reports here when the install doesn't go through. */
    internal fun onInstallFinished(status: Int, message: String?) {
        if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            // You tapped Cancel on Android's prompt: offer the update again.
            _state.value = UpdateState.None
            refresh()
        } else {
            _state.value = UpdateState.Failed(message ?: "Android couldn't install the update")
        }
    }

    /**
     * Hands the APK to Android's installer. A "session" is like a shopping cart: open it, write
     * the APK into it, then commit. Android answers later through InstallResultReceiver.
     */
    private fun startInstall(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // Android 12+: skip the prompt when allowed (we installed the current version ourselves).
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("PillionTalk.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val callback = PendingIntent.getBroadcast(
                context, sessionId, Intent(context, InstallResultReceiver::class.java),
                // MUTABLE: Android adds the result (status, message) to this intent before sending it.
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
    }

    /** "Update ready · Tap to install". Tapping opens the app, which starts the install. */
    private fun notifyReady(release: Release) {
        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return // the banner in the app still offers it
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Updates", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_INSTALL)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Pillion Talk ${release.versionName} is ready")
                .setContentText("Tap to install. ${release.notes}")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun cleanUp() {
        if (downloadId != -1L) downloads.remove(downloadId)
        context.getExternalFilesDir(null)?.listFiles { f -> f.name.startsWith("update-") }?.forEach { it.delete() }
        downloadId = -1
        pending = null
    }

    private fun fetchLatest(): Release {
        val connection = (URL(VERSION_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = true // GitHub redirects release downloads to its file servers
        }
        if (connection.responseCode !in 200..299) throw IllegalStateException("GitHub said ${connection.responseCode}")
        val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        return Release(json.getLong("versionCode"), json.getString("versionName"), json.optString("notes"))
    }

    private data class Release(val versionCode: Long, val versionName: String, val notes: String)

    private sealed interface DownloadProgress {
        data class Running(val fraction: Float) : DownloadProgress
        data object WaitingForWifi : DownloadProgress
        data object Done : DownloadProgress
        data object Failed : DownloadProgress
    }

    companion object {
        /** The "ready" notification's tap opens MainActivity with this action. */
        const val ACTION_INSTALL = "com.prajwal.pilliontalk.INSTALL_UPDATE"

        private const val RELEASE = "https://github.com/PrajwalHarish30/PillionTalk/releases/download/latest-debug"
        private const val VERSION_URL = "$RELEASE/version.json"
        private const val APK_URL = "$RELEASE/PillionTalk-debug.apk"
        private const val CHECK_EVERY_MS = 10 * 60 * 1000L
        private const val CHANNEL_ID = "updates"
        private const val NOTIFICATION_ID = 2 // the ride notification is 1
        private const val KEY_CODE = "versionCode"
        private const val KEY_NAME = "versionName"
        private const val KEY_NOTES = "notes"
        private const val KEY_DOWNLOAD = "downloadId"
    }
}

/**
 * DownloadManager announces every finished download with a broadcast. Android starts the app
 * to deliver it if needed, which is how "ready" works even after the app was closed.
 * Registered in AndroidManifest.xml.
 */
class DownloadDoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        (context.applicationContext as PillionTalkApplication).updater.onDownloadFinished(id)
    }
}

/**
 * Android's installer reports back here. Registered in AndroidManifest.xml.
 * (A BroadcastReceiver is a callback Android can call even when no screen is open.)
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updater = (context.applicationContext as PillionTalkApplication).updater
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            // Android wants you to confirm: show its "Update this app?" screen.
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the new version replaces this one
            else -> updater.onInstallFinished(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}
