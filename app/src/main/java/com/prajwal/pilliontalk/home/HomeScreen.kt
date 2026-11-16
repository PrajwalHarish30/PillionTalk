package com.prajwal.pilliontalk.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.TwoWheeler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.prajwal.pilliontalk.profile.ProfileStore
import com.prajwal.pilliontalk.profile.initialsOf
import com.prajwal.pilliontalk.ui.components.Avatar
import com.prajwal.pilliontalk.ui.components.SectionLabel
import com.prajwal.pilliontalk.ui.theme.PillionTalkTheme
import com.prajwal.pilliontalk.wifidirect.Role

/** First screen: your name, and start or join a ride. */
@Composable
fun HomeScreen(
    myName: String,
    onEditName: () -> Unit,
    onPickRole: (Role) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pillion Talk", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            // Your avatar: tap to change your name.
            // clip before clickable, so the press ripple is round like the avatar, not a square.
            Box(Modifier.clip(CircleShape).clickable(onClick = onEditName)) {
                Avatar(initials = initialsOf(myName.ifBlank { "?" }), size = 36.dp)
            }
        }

        Text(
            if (myName.isBlank()) "Hi there. Start a ride, or join one." else "Hi $myName. Start a ride, or join one.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))
        SectionLabel("Start or join")

        RoleRow(
            icon = Icons.Rounded.TwoWheeler,
            title = "Start a ride",
            subtitle = "You're riding. Your pillion joins you.",
            onClick = { onPickRole(Role.RIDER) },
        )
        RoleRow(
            icon = Icons.Rounded.Headphones,
            title = "Join a ride",
            subtitle = "You're the pillion.",
            onClick = { onPickRole(Role.PILLION) },
        )

        Spacer(Modifier.weight(1f))
        Text(
            "Works offline · no SIM or data needed",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** One big tappable row (Surface(onClick) gives the ripple and button semantics). */
@Composable
private fun RoleRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "What should your pillion call you?" Shown on first use, and when you tap your avatar. */
@Composable
fun NameDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What should they call you?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Your name shows up on the other phone during a ride.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(ProfileStore.MAX_NAME_LENGTH) },
                    singleLine = true,
                    placeholder = { Text("Prajwal") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onSave(text) }),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Preview(showBackground = true, heightDp = 760)
@Composable
private fun HomePreview() {
    PillionTalkTheme(dark = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            HomeScreen(myName = "Prajwal", onEditName = {}, onPickRole = {})
        }
    }
}
