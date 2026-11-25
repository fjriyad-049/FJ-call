package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SettingsInputAntenna
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.NetworkStatus
import com.example.data.model.PeerDevice
import com.example.data.model.PermissionStatus
import com.example.data.model.TransportFilter
import com.example.data.model.TransportMode
import com.example.ui.LocalizedStrings
import com.example.ui.components.AvatarBadge
import com.example.ui.components.RadarPulseRing
import com.example.ui.components.TransportBadge

@Composable
fun RadarHomeScreen(
  strings: LocalizedStrings,
  networkStatus: NetworkStatus,
  permissionStatus: PermissionStatus,
  peers: List<PeerDevice>,
  selectedFilter: TransportFilter,
  onSelectFilter: (TransportFilter) -> Unit,
  onScanClick: () -> Unit,
  onCallPeer: (PeerDevice) -> Unit,
  onMessagePeer: (PeerDevice) -> Unit,
  onDialDirectIp: (String) -> Unit,
  onGrantPermissions: () -> Unit,
  onOpenHotspotSettings: () -> Unit,
  onOpenWifiSettings: () -> Unit,
  onMakeBtDiscoverable: () -> Unit,
  onOpenBtSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var directIpText by rememberSaveable { mutableStateOf("") }
  val isScanning = networkStatus.isScanningWifi || networkStatus.isScanningBluetooth

  BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    val isWide = maxWidth >= 700.dp

    LazyColumn(
      modifier = Modifier.fillMaxSize().widthIn(max = 880.dp).align(Alignment.TopCenter),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      // Permission warning banner if core offline call permissions are missing
      if (!permissionStatus.allCoreGranted) {
        item(key = "perm_banner") {
          Card(
            colors =
              CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f)
              ),
            shape = MaterialTheme.shapes.medium,
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(14.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
              )
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = strings.permissionsBannerTitle,
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                  text = strings.permissionsBannerBody,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f),
                )
              }
              Button(
                onClick = onGrantPermissions,
                modifier = Modifier.minimumInteractiveComponentSize().testTag("grant_permissions_button"),
              ) {
                Text(strings.grantPermissions)
              }
            }
          }
        }
      }

      // Live Transport Status Cards (Hotspot/Wi-Fi + Bluetooth)
      item(key = "transport_status") {
        if (isWide) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            HotspotStatusCard(
              strings = strings,
              networkStatus = networkStatus,
              onOpenHotspot = onOpenHotspotSettings,
              onOpenWifi = onOpenWifiSettings,
              modifier = Modifier.weight(1f),
            )
            BluetoothStatusCard(
              strings = strings,
              networkStatus = networkStatus,
              onMakeDiscoverable = onMakeBtDiscoverable,
              onOpenBtSettings = onOpenBtSettings,
              modifier = Modifier.weight(1f),
            )
          }
        } else {
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HotspotStatusCard(
              strings = strings,
              networkStatus = networkStatus,
              onOpenHotspot = onOpenHotspotSettings,
              onOpenWifi = onOpenWifiSettings,
              modifier = Modifier.fillMaxWidth(),
            )
            BluetoothStatusCard(
              strings = strings,
              networkStatus = networkStatus,
              onMakeDiscoverable = onMakeBtDiscoverable,
              onOpenBtSettings = onOpenBtSettings,
              modifier = Modifier.fillMaxWidth(),
            )
          }
        }
      }

      // Radar Scan Hero + Filter Bar
      item(key = "radar_controls") {
        Card(
          colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
          shape = MaterialTheme.shapes.large,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                RadarPulseRing(
                  isScanning = isScanning,
                  primaryColor = MaterialTheme.colorScheme.primary,
                  secondaryColor = MaterialTheme.colorScheme.secondary,
                  modifier = Modifier.size(54.dp),
                ) {
                  Icon(
                    imageVector = Icons.Rounded.Radar,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                  )
                }
                Column {
                  Text(
                    text = strings.nearbyDevicesTitle,
                    style = MaterialTheme.typography.titleLarge,
                  )
                  Text(
                    text = "${peers.size} • ${if (isScanning) strings.scanning else strings.offlineBadge}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }

              FilledTonalButton(
                onClick = onScanClick,
                modifier = Modifier.minimumInteractiveComponentSize().testTag("scan_devices_button"),
              ) {
                if (isScanning) {
                  CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                  )
                } else {
                  Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                  )
                }
                Spacer(Modifier.width(6.dp))
                Text(if (isScanning) strings.scanning else strings.scanButton)
              }
            }

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              TransportFilterChip(
                selected = selectedFilter == TransportFilter.ALL,
                label = strings.filterAll,
                onClick = { onSelectFilter(TransportFilter.ALL) },
                testTag = "filter_all",
              )
              TransportFilterChip(
                selected = selectedFilter == TransportFilter.HOTSPOT_WIFI,
                label = strings.filterHotspot,
                onClick = { onSelectFilter(TransportFilter.HOTSPOT_WIFI) },
                testTag = "filter_hotspot",
              )
              TransportFilterChip(
                selected = selectedFilter == TransportFilter.BLUETOOTH,
                label = strings.filterBluetooth,
                onClick = { onSelectFilter(TransportFilter.BLUETOOTH) },
                testTag = "filter_bluetooth",
              )
            }
          }
        }
      }

      // Discovered Peers List or Friendly Empty State
      if (peers.isEmpty()) {
        item(key = "empty_peers") {
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.large,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
          ) {
            Column(
              modifier = Modifier.fillMaxWidth().padding(24.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.SettingsInputAntenna,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp),
              )
              Text(
                text = strings.emptyDevicesTitle,
                style = MaterialTheme.typography.titleMedium,
              )
              Text(
                text = strings.emptyDevicesBody,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      } else {
        items(items = peers, key = { it.id }) { peer ->
          PeerDeviceCard(
            peer = peer,
            strings = strings,
            onCall = { onCallPeer(peer) },
            onMessage = { onMessagePeer(peer) },
          )
        }
      }

      // Direct IP Fallback Card for Hotspot Host Calling
      item(key = "direct_ip_card") {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          shape = MaterialTheme.shapes.large,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.Link,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
              )
              Text(
                text = strings.directIpTitle,
                style = MaterialTheme.typography.titleMedium,
              )
            }
            Text(
              text = strings.directIpHint,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              OutlinedTextField(
                value = directIpText,
                onValueChange = { directIpText = it },
                placeholder = { Text(strings.directIpPlaceholder) },
                singleLine = true,
                keyboardOptions =
                  KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                  ),
                keyboardActions =
                  KeyboardActions(
                    onDone = { if (directIpText.isNotBlank()) onDialDirectIp(directIpText) }
                  ),
                modifier = Modifier.weight(1f).testTag("direct_ip_input"),
              )
              Button(
                onClick = { onDialDirectIp(directIpText) },
                enabled = directIpText.trim().isNotEmpty(),
                modifier = Modifier.height(54.dp).testTag("dial_ip_button"),
              ) {
                Icon(Icons.Rounded.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(strings.dialIpButton)
              }
            }
            AssistChip(
              onClick = { directIpText = "192.168.43.1" },
              label = { Text(strings.hotspotHostPreset) },
              modifier = Modifier.testTag("hotspot_host_preset_chip"),
            )
          }
        }
      }

      item { Spacer(Modifier.height(16.dp)) }
    }
  }
}

@Composable
private fun HotspotStatusCard(
  strings: LocalizedStrings,
  networkStatus: NetworkStatus,
  onOpenHotspot: () -> Unit,
  onOpenWifi: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val active = networkStatus.hotspotOrLanActive
  val accent = MaterialTheme.colorScheme.primary
  Surface(
    modifier = modifier,
    shape = MaterialTheme.shapes.medium,
    color =
      if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
      else MaterialTheme.colorScheme.surface,
    border =
      BorderStroke(
        1.dp,
        if (active) accent.copy(alpha = 0.6f)
        else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
      ),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          imageVector = Icons.Rounded.WifiTethering,
          contentDescription = null,
          tint = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = strings.hotspotStatusTitle,
          style = MaterialTheme.typography.titleMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Text(
        text =
          if (networkStatus.primaryIp != null)
            "${strings.connectedIpPrefix} ${networkStatus.primaryIp}"
          else strings.noWifiHint,
        style = MaterialTheme.typography.bodyMedium,
        color =
          if (active) MaterialTheme.colorScheme.onSurface
          else MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
          onClick = onOpenHotspot,
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
          modifier = Modifier.minimumInteractiveComponentSize().testTag("open_hotspot_settings"),
        ) {
          Icon(Icons.Rounded.WifiTethering, null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text(strings.openHotspotSettings, style = MaterialTheme.typography.labelLarge)
        }
        OutlinedButton(
          onClick = onOpenWifi,
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
          modifier = Modifier.minimumInteractiveComponentSize().testTag("open_wifi_settings"),
        ) {
          Icon(Icons.Rounded.Wifi, null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text(strings.openWifiSettings, style = MaterialTheme.typography.labelLarge)
        }
      }
    }
  }
}

@Composable
private fun BluetoothStatusCard(
  strings: LocalizedStrings,
  networkStatus: NetworkStatus,
  onMakeDiscoverable: () -> Unit,
  onOpenBtSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val enabled = networkStatus.bluetoothEnabled
  val accent = MaterialTheme.colorScheme.secondary
  Surface(
    modifier = modifier,
    shape = MaterialTheme.shapes.medium,
    color =
      if (enabled) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
      else MaterialTheme.colorScheme.surface,
    border =
      BorderStroke(
        1.dp,
        if (enabled) accent.copy(alpha = 0.6f)
        else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
      ),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          imageVector =
            when {
              !networkStatus.bluetoothSupported -> Icons.Rounded.BluetoothDisabled
              enabled -> Icons.Rounded.BluetoothConnected
              else -> Icons.Rounded.Bluetooth
            },
          contentDescription = null,
          tint = if (enabled) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = strings.bluetoothStatusTitle,
          style = MaterialTheme.typography.titleMedium,
        )
      }
      Text(
        text =
          when {
            !networkStatus.bluetoothSupported -> strings.btUnsupported
            enabled ->
              listOfNotNull(strings.btReady, networkStatus.bluetoothName).joinToString(" • ")
            else -> strings.btOff
          },
        style = MaterialTheme.typography.bodyMedium,
        color =
          if (enabled) MaterialTheme.colorScheme.onSurface
          else MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
          onClick = onMakeDiscoverable,
          enabled = networkStatus.bluetoothSupported,
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
          modifier = Modifier.minimumInteractiveComponentSize().testTag("bt_discoverable_button"),
        ) {
          Icon(Icons.Rounded.Visibility, null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text(strings.makeDiscoverable, style = MaterialTheme.typography.labelLarge)
        }
        AnimatedVisibility(visible = !enabled && networkStatus.bluetoothSupported) {
          OutlinedButton(
            onClick = onOpenBtSettings,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier.minimumInteractiveComponentSize().testTag("open_bt_settings"),
          ) {
            Text(strings.openBtSettings, style = MaterialTheme.typography.labelLarge)
          }
        }
      }
    }
  }
}

@Composable
private fun TransportFilterChip(
  selected: Boolean,
  label: String,
  onClick: () -> Unit,
  testTag: String,
) {
  FilterChip(
    selected = selected,
    onClick = onClick,
    label = { Text(label, style = MaterialTheme.typography.labelLarge) },
    colors =
      FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
      ),
    modifier = Modifier.minimumInteractiveComponentSize().testTag(testTag),
  )
}

@Composable
private fun PeerDeviceCard(
  peer: PeerDevice,
  strings: LocalizedStrings,
  onCall: () -> Unit,
  onMessage: () -> Unit,
) {
  val isHotspot = peer.transportMode == TransportMode.HOTSPOT_WIFI
  val accent =
    if (isHotspot) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary

  Card(
    onClick = onCall,
    shape = MaterialTheme.shapes.large,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
    modifier = Modifier.fillMaxWidth().testTag("peer_card_${peer.id}"),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      AvatarBadge(name = peer.name, accentColor = accent)

      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(
          text = peer.name,
          style = MaterialTheme.typography.titleMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          TransportBadge(transportMode = peer.transportMode, strings = strings)
          if (peer.isPaired) {
            Surface(
              shape = RoundedCornerShape(50),
              color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
              Text(
                text = strings.pairedTag,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
              )
            }
          }
        }
        Text(
          text = peer.address,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        FilledTonalIconButton(
          onClick = onMessage,
          shape = CircleShape,
          modifier = Modifier.minimumInteractiveComponentSize().testTag("message_peer_button"),
        ) {
          Icon(
            imageVector = Icons.Rounded.ChatBubbleOutline,
            contentDescription = strings.messageButton,
          )
        }

        Button(
          onClick = onCall,
          shape = CircleShape,
          colors =
            ButtonDefaults.buttonColors(
              containerColor = accent,
              contentColor =
                if (isHotspot) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSecondary,
            ),
          contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
          modifier = Modifier.minimumInteractiveComponentSize().testTag("call_peer_button"),
        ) {
          Icon(
            imageVector = Icons.Rounded.Call,
            contentDescription = strings.callButton,
            modifier = Modifier.size(18.dp),
          )
          Spacer(Modifier.width(5.dp))
          Text(strings.callButton, style = MaterialTheme.typography.labelLarge)
        }
      }
    }
  }
}
