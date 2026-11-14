package com.prajwal.pilliontalk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.prajwal.pilliontalk.host.HostScreen
import com.prajwal.pilliontalk.ui.theme.PillionTalkTheme

/**
 * An Activity is one "screen" Android can launch. This is our only one.
 * Flutter equivalent: the MainActivity Flutter generates for you, plus runApp().
 */
class MainActivity : ComponentActivity() {

    // onCreate runs when Android creates this screen (on launch, and again after e.g. a rotation).
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // draw behind the status and navigation bars

        // setContent { } is Compose's runApp(): everything inside is the UI tree.
        setContent {
            PillionTalkTheme {
                // Scaffold works like Flutter's Scaffold; innerPadding keeps content clear of system bars.
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    HostScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}
