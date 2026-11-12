package com.prajwal.pilliontalk

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
                    HelloPillion(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

/**
 * A @Composable function is Compose's version of a Flutter widget.
 * Instead of a build() method returning a widget tree, the function *emits* UI by calling other composables.
 */
@Composable
fun HelloPillion(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Hello, Pillion!", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(16.dp))
        // Build.* tells us which phone we're running on. Handy for telling the two test phones apart.
        Text("${Build.MANUFACTURER} ${Build.MODEL}", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

// @Preview renders this composable inside Android Studio without running it on a phone.
@Preview(showBackground = true)
@Composable
private fun HelloPillionPreview() {
    MaterialTheme { HelloPillion() }
}
