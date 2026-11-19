package com.prajwal.pilliontalk

import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.prajwal.pilliontalk.ui.theme.PillionTalkTheme
import com.prajwal.pilliontalk.update.Updater

/**
 * An Activity is one "screen" Android can launch. This is our only one; everything else
 * is Compose. Flutter equivalent: the MainActivity Flutter generates for you, plus runApp().
 */
class MainActivity : ComponentActivity() {

    // onCreate runs when Android creates this screen (on launch, and again after e.g. a rotation).
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draw behind the status and navigation bars. SystemBarStyle.auto picks dark or light
        // bar icons to match the phone's theme, like the app itself.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )

        // The phone's volume buttons should change the call volume (what our voice uses),
        // not the ringtone or media volume.
        volumeControlStream = AudioManager.STREAM_VOICE_CALL

        // setContent { } is Compose's runApp(): everything inside is the UI tree.
        setContent {
            PillionTalkTheme {
                PillionTalkApp()
            }
        }
        handleIntent(intent)
    }

    // Called instead of onCreate when the app is already open and something launches it again,
    // like tapping the "update ready" notification.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Updater.ACTION_INSTALL) {
            (application as PillionTalkApplication).updater.install()
            intent.action = null // don't install again if the screen is recreated (e.g. rotation)
        }
    }
}
