package com.prajwal.pilliontalk.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.IntentCompat
import com.prajwal.pilliontalk.PillionTalkApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    data class Available(val versionName: String, val notes: String) : UpdateState
    /** Waiting for you to switch on "Allow from this source" in Settings. */
    data object NeedsPermission : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data object Installing : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Keeps the app up to date from GitHub, without the Play Store.
 *
 * 1. check(): download the small version.json that GitHub Actions publishes next to the APK
 *    in the "latest-debug" release, and compare its versionCode with ours.
 * 2. install(): download the APK and hand it to Android's PackageInstaller. Android checks it's
 *    signed with the same key as the installed app (the debug key in the repo), then replaces it.
 *
 * Android always protects you here: an app can only install with the "Install unknown apps"
 * switch on, and it shows a confirmation unless this app installed the current version itself.
 * So the very first update asks; on Android 12+ later ones can go through without a prompt.
 *
 * It lives in PillionTalkApplication (like RideController) because InstallResultReceiver,
 * which Android calls back while installing, needs to reach it too.
 */
class Updater(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.None)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var latest: Release? = null
    private var lastCheckAt = 0L

    /** The version people see, e.g. "0.9.12", for the home screen's footer. */
    val installedVersionName: String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    private val installedVersionCode: Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    /**
     * Look for a newer build. Called whenever the home screen comes into view; asks GitHub at
     * most every 10 minutes. With no internet it just fails quietly: this app is meant to work offline.
     */
    fun check() {
        val busy = _state.value is UpdateState.Downloading || _state.value is UpdateState.Installing
        if (busy || SystemClock.elapsedRealtime() - lastCheckAt < CHECK_EVERY_MS && lastCheckAt != 0L) return
        lastCheckAt = SystemClock.elapsedRealtime()
        scope.launch {
            val release = withContext(Dispatchers.IO) { runCatching { fetchLatest() }.getOrNull() } ?: return@launch
            latest = release
            if (release.versionCode > installedVersionCode) {
                _state.value = UpdateState.Available(release.versionName, release.notes)
            } else if (_state.value is UpdateState.Available) {
                _state.value = UpdateState.None
            }
        }
    }

    /** Called when the app comes back to the front: carry on if you just allowed installs in Settings. */
    fun onResume() {
        if (_state.value == UpdateState.NeedsPermission && context.packageManager.canRequestPackageInstalls()) {
            install()
        } else {
            check()
        }
    }

    fun install() {
        val release = latest ?: return
        if (!context.packageManager.canRequestPackageInstalls()) {
            // Open the "Install unknown apps" switch for this app. onResume() continues from here.
            _state.value = UpdateState.NeedsPermission
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        scope.launch {
            try {
                _state.value = UpdateState.Downloading(0f)
                val apk = withContext(Dispatchers.IO) {
                    download(APK_URL, File(context.cacheDir, "update.apk")) { _state.value = UpdateState.Downloading(it) }
                }
                _state.value = UpdateState.Installing
                withContext(Dispatchers.IO) { startInstall(apk) }
                // From here Android takes over. If it works, this app is closed and replaced.
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(e.message ?: "Download failed")
            }
        }
    }

    /** InstallResultReceiver reports here when the install doesn't go through. */
    internal fun onInstallFinished(status: Int, message: String?) {
        _state.value = when (status) {
            // You tapped Cancel on Android's prompt: offer the update again.
            PackageInstaller.STATUS_FAILURE_ABORTED -> latest?.let { UpdateState.Available(it.versionName, it.notes) } ?: UpdateState.None
            else -> UpdateState.Failed(message ?: "Android couldn't install the update")
        }
    }

    private fun fetchLatest(): Release {
        val json = JSONObject(open(VERSION_URL).inputStream.bufferedReader().use { it.readText() })
        return Release(
            versionCode = json.getLong("versionCode"),
            versionName = json.getString("versionName"),
            notes = json.optString("notes"),
        )
    }

    private fun download(url: String, to: File, onProgress: (Float) -> Unit): File {
        val connection = open(url)
        val total = connection.contentLengthLong
        var done = 0L
        var lastReported = -1
        connection.inputStream.use { input ->
            to.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    done += read
                    // Only report whole percents, so the UI isn't flooded with updates.
                    val percent = if (total > 0) (done * 100 / total).toInt() else 0
                    if (percent != lastReported) {
                        lastReported = percent
                        onProgress(percent / 100f)
                    }
                }
            }
        }
        return to
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

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 15_000
            instanceFollowRedirects = true // GitHub redirects release downloads to its file servers
            if (responseCode !in 200..299) throw IllegalStateException("GitHub said $responseCode")
        }

    private data class Release(val versionCode: Long, val versionName: String, val notes: String)

    companion object {
        private const val RELEASE = "https://github.com/PrajwalHarish30/PillionTalk/releases/download/latest-debug"
        private const val VERSION_URL = "$RELEASE/version.json"
        private const val APK_URL = "$RELEASE/PillionTalk-debug.apk"
        private const val CHECK_EVERY_MS = 10 * 60 * 1000L
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
