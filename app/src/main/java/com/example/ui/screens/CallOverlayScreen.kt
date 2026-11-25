package com.example.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.StopScreenShare
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.local.ChatMessageEntity
import com.example.data.model.CallState
import com.example.data.model.PeerDevice
import com.example.data.model.TransportMode
import com.example.ui.LocalizedStrings
import com.example.ui.components.RadarPulseRing
import com.example.ui.components.TransportBadge
import com.example.ui.components.VoiceWaveformVisualizer
import com.example.ui.theme.CoralDecline
import com.example.ui.theme.EmeraldAccept
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallOverlayScreen(
  callState: CallState,
  strings: LocalizedStrings,
  remoteScreenBitmap: Bitmap?,
  chatMessages: List<ChatMessageEntity>,
  onAnswer: () -> Unit,
  onDecline: () -> Unit,
  onEndCall: () -> Unit,
  onToggleMute: () -> Unit,
  onToggleSpeaker: () -> Unit,
  onRequestStartScreenShare: () -> Unit,
  onStopScreenShare: () -> Unit,
  onSendInCallMessage: (PeerDevice, String) -> Unit,
  onDismissEnded: () -> Unit,
) {
  if (callState is CallState.Idle) return

  var showInCallChat by rememberSaveable { mutableStateOf(false) }
  var isScreenFullscreen by rememberSaveable { mutableStateOf(false) }

  BackHandler {
    when {
      isScreenFullscreen -> isScreenFullscreen = false
      showInCallChat -> showInCallChat = false
      callState is CallState.Ended -> onDismissEnded()
      callState is CallState.IncomingRinging -> onDecline()
      else -> onEndCall()
    }
  }

  val transport =
    when (callState) {
      is CallState.OutgoingRinging -> callState.transport
      is CallState.IncomingRinging -> callState.transport
      is CallState.Active -> callState.transport
      is CallState.Ended -> callState.transport
      CallState.Idle -> TransportMode.HOTSPOT_WIFI
    }

  val peer =
    when (callState) {
      is CallState.OutgoingRinging -> callState.peer
      is CallState.IncomingRinging -> callState.peer
      is CallState.Active -> callState.peer
      is CallState.Ended -> callState.peer
      CallState.Idle -> return
    }

  val accent =
    if (transport == TransportMode.HOTSPOT_WIFI) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.secondary

  Surface(
    modifier = Modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
  ) {
    Box(
      modifier =
        Modifier.fillMaxSize()
          .background(
            Brush.verticalGradient(
              listOf(
                accent.copy(alpha = 0.22f),
                MaterialTheme.colorScheme.background,
                MaterialTheme.colorScheme.surface,
              )
            )
          )
          .statusBarsPadding()
          .navigationBarsPadding()
          .padding(16.dp)
    ) {
      if (isScreenFullscreen && remoteScreenBitmap != null) {
        // Fullscreen Remote Screen Viewer
        Box(
          modifier =
            Modifier.fillMaxSize()
              .clip(RoundedCornerShape(16.dp))
              .background(Color.Black)
              .clickable { isScreenFullscreen = false },
          contentAlignment = Alignment.Center,
        ) {
          Image(
            bitmap = remoteScreenBitmap.asImageBitmap(),
            contentDescription = strings.viewingRemoteScreen,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
          )
          Surface(
            color = Color.Black.copy(alpha = 0.65f),
            shape = RoundedCornerShape(50),
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
          ) {
            IconButton(
              onClick = { isScreenFullscreen = false },
              modifier = Modifier.minimumInteractiveComponentSize().testTag("exit_fullscreen_screen"),
            ) {
              Icon(
                Icons.Rounded.FullscreenExit,
                contentDescription = "Minimize",
                tint = Color.White,
              )
            }
          }
        }
      } else {
        Column(
          modifier = Modifier.fillMaxSize().widthIn(max = 580.dp).align(Alignment.Center),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.SpaceBetween,
        ) {
          // Top Transport + Status Header
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              TransportBadge(transportMode = transport, strings = strings)
              if (callState is CallState.Active && callState.latencyMs != null) {
                Surface(
                  shape = RoundedCornerShape(50),
                  color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                  Text(
                    text = "${callState.latencyMs} ${strings.latencyMsSuffix}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                  )
                }
              }
            }
            Text(
              text =
                when (callState) {
                  is CallState.OutgoingRinging ->
                    if (callState.isPeerRinging) strings.peerRinging else strings.outgoingCalling
                  is CallState.IncomingRinging -> strings.incomingCall
                  is CallState.Active -> strings.activeCall
                  is CallState.Ended -> strings.endReasonLabel(callState.reason)
                  CallState.Idle -> ""
                },
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center,
            )

            // Local Screen Share Active Indicator Pill
            if (callState is CallState.Active && callState.isSharingMyScreen) {
              Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = RoundedCornerShape(50),
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  Icon(
                    Icons.Rounded.ScreenShare,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                  )
                  Text(
                    text = strings.sharingYourScreen,
                    style = MaterialTheme.typography.labelLarge,
                  )
                }
              }
            }
          }

          // Center Content: Remote Screen Viewer OR Avatar Halo
          if (callState is CallState.Active && remoteScreenBitmap != null) {
            Column(
              modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 10.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color.Black,
                border = BorderStroke(1.5.dp, accent.copy(alpha = 0.7f)),
                modifier = Modifier.weight(1f).fillMaxWidth(),
              ) {
                Box(modifier = Modifier.fillMaxSize()) {
                  Image(
                    bitmap = remoteScreenBitmap.asImageBitmap(),
                    contentDescription = strings.viewingRemoteScreen,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().testTag("remote_screen_image"),
                  )
                  Surface(
                    color = Color.Black.copy(alpha = 0.6f),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                  ) {
                    Text(
                      text = "${peer.name} • ${strings.viewingRemoteScreen}",
                      style = MaterialTheme.typography.labelSmall,
                      modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                  }
                  Surface(
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = CircleShape,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                  ) {
                    IconButton(
                      onClick = { isScreenFullscreen = true },
                      modifier = Modifier.minimumInteractiveComponentSize().testTag("fullscreen_screen_button"),
                    ) {
                      Icon(
                        Icons.Rounded.Fullscreen,
                        contentDescription = "Fullscreen",
                        tint = Color.White,
                      )
                    }
                  }
                }
              }

              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                Text(
                  text = formatCallTimer(callState.durationSeconds),
                  style = MaterialTheme.typography.titleLarge,
                  color = accent,
                  fontWeight = FontWeight.Bold,
                  modifier = Modifier.testTag("active_call_timer"),
                )
              }
            }
          } else {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
              RadarPulseRing(
                isScanning = callState !is CallState.Ended,
                primaryColor = accent,
                secondaryColor = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(168.dp),
              ) {
                Surface(
                  shape = CircleShape,
                  color = accent.copy(alpha = 0.20f),
                  modifier = Modifier.size(92.dp),
                ) {
                  Box(contentAlignment = Alignment.Center) {
                    Icon(
                      imageVector = Icons.Rounded.Person,
                      contentDescription = peer.name,
                      tint = accent,
                      modifier = Modifier.size(46.dp),
                    )
                  }
                }
              }

              Text(
                text = peer.name,
                style = MaterialTheme.typography.displaySmall,
                textAlign = TextAlign.Center,
              )
              Text(
                text = peer.address,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )

              when (callState) {
                is CallState.Active -> {
                  Text(
                    text = formatCallTimer(callState.durationSeconds),
                    style = MaterialTheme.typography.headlineMedium,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("active_call_timer"),
                  )
                  VoiceWaveformVisualizer(
                    localLevel = callState.localAudioLevel,
                    remoteLevel = callState.remoteAudioLevel,
                    primaryColor = accent,
                    secondaryColor = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 4.dp),
                  )
                }
                is CallState.Ended -> {
                  if (callState.durationSeconds > 0L) {
                    Text(
                      text = formatCallTimer(callState.durationSeconds),
                      style = MaterialTheme.typography.titleLarge,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
                else -> Spacer(Modifier.height(36.dp))
              }
            }
          }

          // Bottom Call Action Controls
          when (callState) {
            is CallState.IncomingRinging -> {
              Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                CallActionCircle(
                  icon = Icons.Rounded.CallEnd,
                  label = strings.declineCall,
                  containerColor = CoralDecline,
                  contentColor = Color.White,
                  onClick = onDecline,
                  testTag = "decline_call_button",
                )
                CallActionCircle(
                  icon = Icons.Rounded.Call,
                  label = strings.answerCall,
                  containerColor = EmeraldAccept,
                  contentColor = Color(0xFF061A11),
                  onClick = onAnswer,
                  testTag = "answer_call_button",
                )
              }
            }
            is CallState.Active -> {
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceEvenly,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  CallActionCircle(
                    icon = if (callState.isMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                    label = if (callState.isMuted) strings.unmute else strings.mute,
                    containerColor =
                      if (callState.isMuted) MaterialTheme.colorScheme.primaryContainer
                      else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor =
                      if (callState.isMuted) MaterialTheme.colorScheme.onPrimaryContainer
                      else MaterialTheme.colorScheme.onSurface,
                    sizeDp = 56,
                    onClick = onToggleMute,
                    testTag = "mute_call_button",
                  )
                  CallActionCircle(
                    icon =
                      if (callState.isSpeakerOn) Icons.Rounded.VolumeUp
                      else Icons.Rounded.VolumeOff,
                    label = if (callState.isSpeakerOn) strings.speaker else strings.earpiece,
                    containerColor =
                      if (callState.isSpeakerOn) MaterialTheme.colorScheme.primaryContainer
                      else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor =
                      if (callState.isSpeakerOn) MaterialTheme.colorScheme.onPrimaryContainer
                      else MaterialTheme.colorScheme.onSurface,
                    sizeDp = 56,
                    onClick = onToggleSpeaker,
                    testTag = "speaker_call_button",
                  )
                  CallActionCircle(
                    icon =
                      if (callState.isSharingMyScreen) Icons.Rounded.StopScreenShare
                      else Icons.Rounded.ScreenShare,
                    label =
                      if (callState.isSharingMyScreen) strings.stopSharing
                      else strings.shareScreen,
                    containerColor =
                      if (callState.isSharingMyScreen) MaterialTheme.colorScheme.errorContainer
                      else MaterialTheme.colorScheme.secondaryContainer,
                    contentColor =
                      if (callState.isSharingMyScreen) MaterialTheme.colorScheme.onErrorContainer
                      else MaterialTheme.colorScheme.onSecondaryContainer,
                    sizeDp = 56,
                    onClick = {
                      if (callState.isSharingMyScreen) onStopScreenShare()
                      else onRequestStartScreenShare()
                    },
                    testTag = "share_screen_button",
                  )
                  CallActionCircle(
                    icon = Icons.Rounded.ChatBubbleOutline,
                    label = strings.inCallChat,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    sizeDp = 56,
                    onClick = { showInCallChat = true },
                    testTag = "in_call_chat_button",
                  )
                }

                CallActionCircle(
                  icon = Icons.Rounded.CallEnd,
                  label = strings.endCall,
                  containerColor = CoralDecline,
                  contentColor = Color.White,
                  sizeDp = 68,
                  onClick = onEndCall,
                  testTag = "end_call_button",
                )
              }
            }
            is CallState.OutgoingRinging -> {
              Box(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                contentAlignment = Alignment.Center,
              ) {
                CallActionCircle(
                  icon = Icons.Rounded.CallEnd,
                  label = strings.endCall,
                  containerColor = CoralDecline,
                  contentColor = Color.White,
                  sizeDp = 68,
                  onClick = onEndCall,
                  testTag = "cancel_outgoing_call_button",
                )
              }
            }
            is CallState.Ended -> {
              Spacer(Modifier.height(68.dp))
            }
            CallState.Idle -> {}
          }
        }
      }
    }

    if (showInCallChat && callState is CallState.Active) {
      val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
      ModalBottomSheet(
        onDismissRequest = { showInCallChat = false },
        sheetState = sheetState,
      ) {
        Box(modifier = Modifier.fillMaxWidth().height(420.dp)) {
          ChatConversationPane(
            peer = peer,
            messages = chatMessages,
            strings = strings,
            showBackButton = false,
            onBack = { showInCallChat = false },
            onSendMessage = { text -> onSendInCallMessage(peer, text) },
          )
        }
      }
    }
  }
}

@Composable
private fun CallActionCircle(
  icon: ImageVector,
  label: String,
  containerColor: Color,
  contentColor: Color,
  sizeDp: Int = 64,
  onClick: () -> Unit,
  testTag: String,
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    FilledIconButton(
      onClick = onClick,
      shape = CircleShape,
      colors =
        IconButtonDefaults.filledIconButtonColors(
          containerColor = containerColor,
          contentColor = contentColor,
        ),
      modifier = Modifier.size(sizeDp.dp).minimumInteractiveComponentSize().testTag(testTag),
    ) {
      Icon(
        imageVector = icon,
        contentDescription = label,
        modifier = Modifier.size((sizeDp * 0.44f).dp),
      )
    }
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 1,
    )
  }
}

private fun formatCallTimer(seconds: Long): String {
  val hrs = seconds / 3600
  val mins = (seconds % 3600) / 60
  val secs = seconds % 60
  return if (hrs > 0) String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)
  else String.format(Locale.US, "%02d:%02d", mins, secs)
}
