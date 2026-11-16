package com.prajwal.pilliontalk

import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.prajwal.pilliontalk.ui.theme.PillionTalkTheme

/**
 * An Activity is one "screen" Android can launch. This is our only one; everything else
 * is Compose. Flutter equivalent: the MainActivity Flutter generates for you, plus runApp().
 */
class MainActivity : ComponentActivity() {

    // onCreate runs when Android creates this screen (on launch, and again after e.g. a rotation).
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draw behind the status and navigation bars, with light icons (our background is dark).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
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
    }
}
