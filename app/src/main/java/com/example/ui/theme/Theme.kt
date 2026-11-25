package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val NearCallDarkColorScheme =
  darkColorScheme(
    primary = SignalCyan,
    onPrimary = ObsidianBg,
    primaryContainer = SignalCyanContainer,
    onPrimaryContainer = SignalCyan,
    secondary = BluetoothCobalt,
    onSecondary = Color.White,
    secondaryContainer = BluetoothCobaltContainer,
    onSecondaryContainer = Color(0xFFD5E3FF),
    tertiary = EmeraldAccept,
    onTertiary = ObsidianBg,
    error = CoralDecline,
    onError = Color.White,
    background = ObsidianBg,
    onBackground = TextPrimaryDark,
    surface = SlateSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = SlateSurfaceElevated,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerHigh = SlateSurfaceHigh,
    outline = SlateOutline,
  )

private val NearCallLightColorScheme =
  lightColorScheme(
    primary = SignalTealLight,
    onPrimary = Color.White,
    primaryContainer = SignalTealContainerLight,
    onPrimaryContainer = Color(0xFF00201A),
    secondary = BluetoothCobaltLight,
    onSecondary = Color.White,
    secondaryContainer = BluetoothCobaltContainerLight,
    onSecondaryContainer = Color(0xFF001947),
    tertiary = EmeraldAccept,
    onTertiary = ObsidianBg,
    error = CoralDecline,
    onError = Color.White,
    background = MistBg,
    onBackground = TextPrimaryLight,
    surface = CloudSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = CloudSurfaceElevated,
    onSurfaceVariant = TextSecondaryLight,
    surfaceContainerHigh = CloudSurfaceElevated,
    outline = CloudOutline,
  )

val NearCallShapes =
  Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
  )

@Composable
fun NearCallTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) NearCallDarkColorScheme else NearCallLightColorScheme

  MaterialTheme(
    colorScheme = colorScheme,
    typography = Typography,
    shapes = NearCallShapes,
    content = content,
  )
}
