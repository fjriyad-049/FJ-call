package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallMade
import androidx.compose.material.icons.rounded.CallMissed
import androidx.compose.material.icons.rounded.CallReceived
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.CallLogEntity
import com.example.data.model.CallDirection
import com.example.data.model.PeerDevice
import com.example.data.model.TransportMode
import com.example.ui.LocalizedStrings
import com.example.ui.components.TransportBadge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
  strings: LocalizedStrings,
  logs: List<CallLogEntity>,
  onRedial: (PeerDevice) -> Unit,
  onClearHistory: () -> Unit,
  onNavigateBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  BackHandler(onBack = onNavigateBack)

  Box(modifier = modifier.fillMaxSize()) {
    LazyColumn(
      modifier = Modifier.fillMaxSize().widthIn(max = 880.dp).align(Alignment.TopCenter),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item(key = "history_header") {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = strings.historyTitle,
            style = MaterialTheme.typography.headlineMedium,
          )
          if (logs.isNotEmpty()) {
            TextButton(
              onClick = onClearHistory,
              modifier = Modifier.minimumInteractiveComponentSize().testTag("clear_history_button"),
            ) {
              Icon(Icons.Rounded.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.size(4.dp))
              Text(strings.clearHistory)
            }
          }
        }
      }

      if (logs.isEmpty()) {
        item(key = "history_empty") {
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.large,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
          ) {
            Column(
              modifier = Modifier.fillMaxWidth().padding(28.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp),
              )
              Text(
                text = strings.historyEmptyTitle,
                style = MaterialTheme.typography.titleMedium,
              )
              Text(
                text = strings.historyEmptyBody,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      } else {
        items(items = logs, key = { it.id }) { log ->
          CallHistoryCard(
            log = log,
            strings = strings,
            onRedial = {
              onRedial(
                PeerDevice(
                  id = log.peerId,
                  name = log.peerName,
                  address = log.peerAddress,
                  tcpPort = 45455,
                  udpAudioPort = 45456,
                  transportMode = log.transportEnum,
                  isPaired = log.transportEnum == TransportMode.BLUETOOTH,
                )
              )
            },
          )
        }
      }

      item { Spacer(Modifier.height(16.dp)) }
    }
  }
}

@Composable
private fun CallHistoryCard(
  log: CallLogEntity,
  strings: LocalizedStrings,
  onRedial: () -> Unit,
) {
  val dirIcon =
    when (log.directionEnum) {
      CallDirection.OUTGOING -> Icons.Rounded.CallMade
      CallDirection.INCOMING -> Icons.Rounded.CallReceived
      CallDirection.MISSED -> Icons.Rounded.CallMissed
    }
  val dirTint =
    when (log.directionEnum) {
      CallDirection.OUTGOING -> MaterialTheme.colorScheme.primary
      CallDirection.INCOMING -> MaterialTheme.colorScheme.tertiary
      CallDirection.MISSED -> MaterialTheme.colorScheme.error
    }
  val dirLabel =
    when (log.directionEnum) {
      CallDirection.OUTGOING -> strings.outgoingTag
      CallDirection.INCOMING -> strings.incomingTag
      CallDirection.MISSED -> strings.missedTag
    }

  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    shape = MaterialTheme.shapes.large,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
    modifier = Modifier.fillMaxWidth().testTag("history_item_${log.id}"),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Icon(
        imageVector = dirIcon,
        contentDescription = dirLabel,
        tint = dirTint,
        modifier = Modifier.size(26.dp),
      )

      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(
          text = log.peerName,
          style = MaterialTheme.typography.titleMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          TransportBadge(transportMode = log.transportEnum, strings = strings)
          Text(
            text = "$dirLabel • ${formatDuration(log.durationSeconds)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Text(
          text = "${log.peerAddress} • ${formatTimestamp(log.timestampMs)}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      FilledTonalIconButton(
        onClick = onRedial,
        shape = CircleShape,
        modifier = Modifier.minimumInteractiveComponentSize().testTag("redial_button_${log.id}"),
      ) {
        Icon(Icons.Rounded.Call, contentDescription = strings.callButton)
      }
    }
  }
}

private fun formatDuration(seconds: Long): String {
  val m = seconds / 60
  val s = seconds % 60
  return String.format(Locale.US, "%02d:%02d", m, s)
}

private fun formatTimestamp(ms: Long): String =
  SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(ms))
