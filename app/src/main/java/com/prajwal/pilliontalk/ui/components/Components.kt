package com.prajwal.pilliontalk.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * A round avatar with initials (no photos, all neutral grey, per the colour rules).
 * - [speaking] draws the ring in the ink colour, like Discord's green ring.
 * - [waiting] draws a dashed ring: "nobody here yet".
 * - [badge] puts a small icon in the corner (e.g. a crossed-out mic when muted).
 */
@Composable
fun Avatar(
    initials: String,
    size: Dp,
    modifier: Modifier = Modifier,
    speaking: Boolean = false,
    waiting: Boolean = false,
    badge: ImageVector? = null,
) {
    val colors = MaterialTheme.colorScheme
    // Fade the ring in and out instead of blinking.
    val ringAlpha by animateFloatAsState(if (speaking) 1f else 0f, label = "ring")

    Box(modifier.size(size + 12.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size + 12.dp)) {
            val stroke = 2.5.dp.toPx()
            val radius = this.size.minDimension / 2 - stroke / 2
            if (waiting) {
                drawCircle(
                    color = colors.onSurfaceVariant,
                    radius = radius,
                    style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))),
                )
            } else if (ringAlpha > 0f) {
                drawCircle(color = colors.primary.copy(alpha = ringAlpha), radius = radius, style = Stroke(stroke))
            }
        }
        Box(
            Modifier.size(size).background(colors.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                initials,
                color = if (waiting) colors.onSurfaceVariant else colors.onSurface,
                fontSize = (size.value * 0.34f).sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (badge != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = (-2).dp, y = (-2).dp)
                    .size(26.dp)
                    .background(colors.onSurface, CircleShape)
                    .border(3.dp, colors.surface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(badge, contentDescription = null, tint = colors.surface, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/**
 * The 0–200% volume slider. Hand-drawn (instead of Material's Slider) so the boost zone can
 * look different:
 *   0–100%    solid ink: the phone's own volume
 *   100–200%  faded ink: digital boost
 * plus a tick mark at 100% so you can feel where boost starts.
 *
 * pointerInput is how Compose reads raw touches (Flutter: GestureDetector).
 */
@Composable
fun VolumeSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    max: Float = 2f,
) {
    val colors = MaterialTheme.colorScheme
    val currentOnChange by rememberUpdatedState(onValueChange)

    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Volume", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
            val percent = (value * 100).roundToInt()
            Text(
                if (value > 1f) "$percent% · boosted" else "$percent%",
                style = MaterialTheme.typography.bodyMedium,
                color = if (value > 1f) colors.onSurface else colors.onSurfaceVariant,
            )
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .semantics {
                    contentDescription = "Volume"
                    progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..max)
                    setProgress { target -> currentOnChange(target.coerceIn(0f, max)); true }
                }
                .pointerInput(max) {
                    detectTapGestures { offset -> currentOnChange((offset.x / size.width * max).coerceIn(0f, max)) }
                }
                .pointerInput(max) {
                    detectHorizontalDragGestures { change, _ ->
                        currentOnChange((change.position.x / size.width * max).coerceIn(0f, max))
                    }
                },
        ) {
            val trackHeight = 6.dp.toPx()
            val y = size.height / 2 - trackHeight / 2
            val corner = CornerRadius(trackHeight / 2)
            val fraction = (value / max).coerceIn(0f, 1f)
            val boostStart = 1f / max

            // Empty track, then the phone-volume part, then the boost part on top.
            drawRoundRect(colors.surfaceVariant, Offset(0f, y), Size(size.width, trackHeight), corner)
            drawRoundRect(colors.primary.copy(alpha = 0.4f), Offset(0f, y), Size(size.width * fraction, trackHeight), corner)
            drawRoundRect(colors.primary, Offset(0f, y), Size(size.width * minOf(fraction, boostStart), trackHeight), corner)

            // Tick at 100%.
            val tickX = size.width * boostStart
            drawLine(colors.onSurfaceVariant, Offset(tickX, size.height / 2 - 9.dp.toPx()), Offset(tickX, size.height / 2 + 9.dp.toPx()), 2.dp.toPx())

            // Thumb.
            val thumbX = (size.width * fraction).coerceIn(9.dp.toPx(), size.width - 9.dp.toPx())
            drawCircle(colors.primary, radius = 9.dp.toPx(), center = Offset(thumbX, size.height / 2))
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Text("0", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("100%", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            Text("200%", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

/** A small uppercase section label, like "START OR JOIN". */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.8.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
