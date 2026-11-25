package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.PermissionStatus
import com.example.ui.LocalizedStrings

@Composable
fun GuideSetupScreen(
  strings: LocalizedStrings,
  permissionStatus: PermissionStatus,
  onGrantPermissions: () -> Unit,
  onNavigateBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  BackHandler(onBack = onNavigateBack)

  Box(modifier = modifier.fillMaxSize()) {
    LazyColumn(
      modifier = Modifier.fillMaxSize().widthIn(max = 880.dp).align(Alignment.TopCenter),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      item(key = "guide_header") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            text = strings.guideTitle,
            style = MaterialTheme.typography.headlineMedium,
          )
          Text(
            text = strings.guideSubtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      item(key = "hotspot_guide") {
        GuideStepsCard(
          title = strings.guideHotspotTitle,
          steps = strings.guideHotspotSteps,
          icon = Icons.Rounded.WifiTethering,
          accentColor = MaterialTheme.colorScheme.primary,
        )
      }

      item(key = "bluetooth_guide") {
        GuideStepsCard(
          title = strings.guideBluetoothTitle,
          steps = strings.guideBluetoothSteps,
          icon = Icons.Rounded.Bluetooth,
          accentColor = MaterialTheme.colorScheme.secondary,
        )
      }

      item(key = "perm_checklist") {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          shape = MaterialTheme.shapes.large,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Text(
              text = strings.permissionsChecklistTitle,
              style = MaterialTheme.typography.titleLarge,
            )
            PermissionRow(strings.permMic, permissionStatus.microphoneGranted, strings)
            PermissionRow(strings.permBt, permissionStatus.bluetoothGranted, strings)
            PermissionRow(strings.permWifi, permissionStatus.nearbyWifiOrLocationGranted, strings)
            PermissionRow(strings.permNotif, permissionStatus.notificationsGranted, strings)

            if (!permissionStatus.allCoreGranted || !permissionStatus.notificationsGranted) {
              Button(
                onClick = onGrantPermissions,
                modifier =
                  Modifier.fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("guide_grant_permissions_button"),
              ) {
                Text(strings.grantPermissions)
              }
            }
          }
        }
      }

      item { Spacer(Modifier.height(16.dp)) }
    }
  }
}

@Composable
private fun GuideStepsCard(
  title: String,
  steps: List<String>,
  icon: ImageVector,
  accentColor: Color,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    shape = MaterialTheme.shapes.large,
    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Icon(imageVector = icon, contentDescription = null, tint = accentColor)
        Text(text = title, style = MaterialTheme.typography.titleMedium, color = accentColor)
      }
      steps.forEachIndexed { idx, step ->
        Row(
          verticalAlignment = Alignment.Top,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Box(
            modifier =
              Modifier.size(24.dp).clip(CircleShape).background(accentColor.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = "${idx + 1}",
              style = MaterialTheme.typography.labelLarge,
              color = accentColor,
              fontWeight = FontWeight.Bold,
            )
          }
          Text(
            text = step,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
          )
        }
      }
    }
  }
}

@Composable
private fun PermissionRow(
  label: String,
  granted: Boolean,
  strings: LocalizedStrings,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(
        imageVector =
          if (granted) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
        contentDescription = null,
        tint =
          if (granted) MaterialTheme.colorScheme.tertiary
          else MaterialTheme.colorScheme.error,
        modifier = Modifier.size(20.dp),
      )
      Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
    Text(
      text = if (granted) strings.granted else strings.missing,
      style = MaterialTheme.typography.labelLarge,
      color =
        if (granted) MaterialTheme.colorScheme.tertiary
        else MaterialTheme.colorScheme.error,
    )
  }
}
