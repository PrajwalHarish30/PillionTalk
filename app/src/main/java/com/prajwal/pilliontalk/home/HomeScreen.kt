package com.prajwal.pilliontalk.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prajwal.pilliontalk.ui.theme.PillionTalkTheme
import com.prajwal.pilliontalk.wifidirect.Role

/** First screen: pick your seat. */
@Composable
fun HomeScreen(onPickRole: (Role) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.weight(1f)) // pushes everything below it towards the bottom (thumb reach)

        Text("Pillion Talk", style = MaterialTheme.typography.displaySmall)
        Text(
            "Talk on the ride. No signal, no data, no calls.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))

        RoleCard(
            emoji = "🏍️",
            title = "I'm riding",
            subtitle = "Start the ride. Your pillion joins your phone.",
            highlighted = true,
            onClick = { onPickRole(Role.RIDER) },
        )
        RoleCard(
            emoji = "🎧",
            title = "I'm the pillion",
            subtitle = "Join your rider's phone.",
            highlighted = false,
            onClick = { onPickRole(Role.PILLION) },
        )

        Spacer(Modifier.weight(1f))

        Text(
            "Both phones need Pillion Talk with Wi-Fi switched on. Mobile data isn't needed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * A big, glove-friendly button for each seat. Surface(onClick = …) gives us the ripple
 * and accessibility semantics of a button, with our own layout inside.
 */
@Composable
private fun RoleCard(
    emoji: String,
    title: String,
    subtitle: String,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    val container = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val content = if (highlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = container,
        contentColor = content,
        border = if (highlighted) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // A soft circle behind the emoji.
            Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.14f), modifier = Modifier.size(64.dp)) {
                Box(contentAlignment = Alignment.Center) { Text(emoji, fontSize = 32.sp) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = content.copy(alpha = 0.8f))
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0E1318, heightDp = 780)
@Composable
private fun HomePreview() {
    PillionTalkTheme { HomeScreen(onPickRole = {}) }
}
