package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.TransportMode
import com.example.ui.LocalizedStrings
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun TransportBadge(
  transportMode: TransportMode,
  strings: LocalizedStrings,
  modifier: Modifier = Modifier,
) {
  val isHotspot = transportMode == TransportMode.HOTSPOT_WIFI
  val containerColor =
    if (isHotspot) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.secondaryContainer
  val contentColor =
    if (isHotspot) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSecondaryContainer

  Surface(
    modifier = modifier,
    color = containerColor,
    contentColor = contentColor,
    shape = RoundedCornerShape(50),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
      Icon(
        imageVector =
          if (isHotspot) Icons.Rounded.WifiTethering else Icons.Rounded.Bluetooth,
        contentDescription = null,
        modifier = Modifier.size(14.dp),
      )
      Text(
        text = if (isHotspot) strings.hotspotTag else strings.bluetoothTag,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
      )
    }
  }
}

@Composable
fun RadarPulseRing(
  isScanning: Boolean,
  primaryColor: Color,
  secondaryColor: Color,
  modifier: Modifier = Modifier,
  centerContent: @Composable () -> Unit,
) {
  val transition = rememberInfiniteTransition(label = "radar_pulse")
  val pulse1 by
    transition.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = if (isScanning) 1800 else 3200, easing = LinearEasing),
          repeatMode = RepeatMode.Restart,
        ),
      label = "pulse1",
    )
  val pulse2 by
    transition.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec =
        infiniteRepeatable(
          animation =
            tween(
              durationMillis = if (isScanning) 1800 else 3200,
              delayMillis = if (isScanning) 900 else 1600,
              easing = LinearEasing,
            ),
          repeatMode = RepeatMode.Restart,
        ),
      label = "pulse2",
    )

  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    Canvas(modifier = Modifier.matchParentSize()) {
      val center = Offset(size.width / 2f, size.height / 2f)
      val maxRadius = size.minDimension / 2f

      drawCircle(
        brush =
          Brush.radialGradient(
            colors = listOf(primaryColor.copy(alpha = 0.14f), Color.Transparent),
            center = center,
            radius = maxRadius,
          ),
        radius = maxRadius,
        center = center,
      )

      drawCircle(
        color = secondaryColor.copy(alpha = 0.16f),
        radius = maxRadius * 0.68f,
        center = center,
        style = Stroke(width = 1.5.dp.toPx()),
      )

      listOf(pulse1, pulse2).forEach { progress ->
        val r = maxRadius * (0.28f + 0.70f * progress)
        val alpha = (1f - progress) * if (isScanning) 0.55f else 0.25f
        drawCircle(
          color = primaryColor.copy(alpha = alpha),
          radius = r,
          center = center,
          style = Stroke(width = 2.dp.toPx()),
        )
      }
    }
    centerContent()
  }
}

@Composable
fun VoiceWaveformVisualizer(
  localLevel: Float,
  remoteLevel: Float,
  primaryColor: Color,
  secondaryColor: Color,
  modifier: Modifier = Modifier,
) {
  val transition = rememberInfiniteTransition(label = "wave_anim")
  val phase by
    transition.animateFloat(
      initialValue = 0f,
      targetValue = (2 * PI).toFloat(),
      animationSpec =
        infiniteRepeatable(
          animation = tween(1100, easing = FastOutSlowInEasing),
          repeatMode = RepeatMode.Restart,
        ),
      label = "wave_phase",
    )

  val energy = ((localLevel + remoteLevel) * 0.75f).coerceIn(0.08f, 1f)

  Canvas(modifier = modifier.fillMaxWidth().height(56.dp)) {
    val barCount = 24
    val spacing = size.width / (barCount * 1.65f)
    val barWidth = spacing * 0.85f
    val totalWidth = barCount * barWidth + (barCount - 1) * spacing
    val startX = (size.width - totalWidth) / 2f
    val midY = size.height / 2f

    for (i in 0 until barCount) {
      val normDistFromCenter =
        1f - (kotlin.math.abs(i - (barCount - 1) / 2f) / (barCount / 2f))
      val wave = 0.45f + 0.55f * ((sin(phase + i * 0.55f) + 1f) / 2f)
      val barHeight = (size.height * 0.88f * energy * normDistFromCenter * wave).coerceAtLeast(6.dp.toPx())
      val x = startX + i * (barWidth + spacing)
      val color = if (i % 2 == 0) primaryColor else secondaryColor
      drawRoundRect(
        color = color.copy(alpha = 0.85f),
        topLeft = Offset(x, midY - barHeight / 2f),
        size = Size(barWidth, barHeight),
        cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
      )
    }
  }
}

@Composable
fun EditNameDialog(
  currentName: String,
  strings: LocalizedStrings,
  onDismiss: () -> Unit,
  onSave: (String) -> Unit,
) {
  var nameInput by remember(currentName) { mutableStateOf(currentName) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(strings.editNameTitle, style = MaterialTheme.typography.titleLarge) },
    text = {
      OutlinedTextField(
        value = nameInput,
        onValueChange = { if (it.length <= 28) nameInput = it },
        singleLine = true,
        label = { Text(strings.yourNameLabel) },
        modifier = Modifier.fillMaxWidth().testTag("display_name_input"),
      )
    },
    confirmButton = {
      Button(
        onClick = {
          onSave(nameInput)
          onDismiss()
        },
        enabled = nameInput.trim().isNotEmpty(),
        modifier = Modifier.testTag("save_name_button"),
      ) {
        Text(strings.save)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(strings.cancel) }
    },
  )
}

@Composable
fun AvatarBadge(
  name: String,
  accentColor: Color,
  modifier: Modifier = Modifier,
) {
  val initial =
    name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
  Box(
    modifier =
      modifier
        .size(46.dp)
        .clip(CircleShape)
        .background(
          Brush.linearGradient(
            listOf(accentColor.copy(alpha = 0.28f), accentColor.copy(alpha = 0.10f))
          )
        ),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = initial,
      style = MaterialTheme.typography.titleLarge,
      color = accentColor,
      fontWeight = FontWeight.Bold,
    )
  }
}
