package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.ChatMessageEntity
import com.example.data.model.ChatThreadSummary
import com.example.data.model.PeerDevice
import com.example.data.model.TransportMode
import com.example.ui.LocalizedStrings
import com.example.ui.components.AvatarBadge
import com.example.ui.components.TransportBadge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MessagesScreen(
  strings: LocalizedStrings,
  onlinePeers: List<PeerDevice>,
  threads: List<ChatThreadSummary>,
  allMessages: List<ChatMessageEntity>,
  selectedPeer: PeerDevice?,
  stablePeerKey: (PeerDevice) -> String,
  onSelectPeer: (PeerDevice) -> Unit,
  onClearSelectedPeer: () -> Unit,
  onSendMessage: (PeerDevice, String) -> Unit,
  onCallPeer: (PeerDevice) -> Unit,
  onClearThread: (PeerDevice) -> Unit,
  onNavigateBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  BackHandler {
    if (selectedPeer != null) onClearSelectedPeer() else onNavigateBack()
  }

  BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    val isTwoPane = maxWidth >= 720.dp

    if (isTwoPane) {
      Row(modifier = Modifier.fillMaxSize()) {
        ThreadListPane(
          strings = strings,
          onlinePeers = onlinePeers,
          threads = threads,
          selectedPeer = selectedPeer,
          stablePeerKey = stablePeerKey,
          onSelectPeer = onSelectPeer,
          modifier = Modifier.weight(0.42f).fillMaxHeight(),
        )
        VerticalSeparator()
        Box(modifier = Modifier.weight(0.58f).fillMaxHeight()) {
          if (selectedPeer != null) {
            val peerKey = stablePeerKey(selectedPeer)
            val peerMsgs =
              remember(allMessages, peerKey) { allMessages.filter { it.peerId == peerKey } }
            ChatConversationPane(
              peer = selectedPeer,
              messages = peerMsgs,
              strings = strings,
              showBackButton = false,
              onBack = onClearSelectedPeer,
              onSendMessage = { text -> onSendMessage(selectedPeer, text) },
              onCallPeer = { onCallPeer(selectedPeer) },
              onClearThread = { onClearThread(selectedPeer) },
            )
          } else {
            EmptyChatPlaceholder(strings = strings)
          }
        }
      }
    } else {
      if (selectedPeer != null) {
        val peerKey = stablePeerKey(selectedPeer)
        val peerMsgs =
          remember(allMessages, peerKey) { allMessages.filter { it.peerId == peerKey } }
        ChatConversationPane(
          peer = selectedPeer,
          messages = peerMsgs,
          strings = strings,
          showBackButton = true,
          onBack = onClearSelectedPeer,
          onSendMessage = { text -> onSendMessage(selectedPeer, text) },
          onCallPeer = { onCallPeer(selectedPeer) },
          onClearThread = { onClearThread(selectedPeer) },
          modifier = Modifier.fillMaxSize(),
        )
      } else {
        ThreadListPane(
          strings = strings,
          onlinePeers = onlinePeers,
          threads = threads,
          selectedPeer = null,
          stablePeerKey = stablePeerKey,
          onSelectPeer = onSelectPeer,
          modifier = Modifier.fillMaxSize(),
        )
      }
    }
  }
}

@Composable
private fun VerticalSeparator() {
  Surface(
    modifier = Modifier.width(1.dp).fillMaxHeight(),
    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
  ) {}
}

@Composable
private fun ThreadListPane(
  strings: LocalizedStrings,
  onlinePeers: List<PeerDevice>,
  threads: List<ChatThreadSummary>,
  selectedPeer: PeerDevice?,
  stablePeerKey: (PeerDevice) -> String,
  onSelectPeer: (PeerDevice) -> Unit,
  modifier: Modifier = Modifier,
) {
  val selectedKey = selectedPeer?.let(stablePeerKey)
  val threadKeys = remember(threads) { threads.map { it.peerId }.toSet() }
  val onlineWithoutThread =
    remember(onlinePeers, threadKeys) {
      onlinePeers.filter { stablePeerKey(it) !in threadKeys }
    }

  LazyColumn(
    modifier = modifier,
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    item(key = "msg_header") {
      Text(
        text = strings.messagesTitle,
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.padding(bottom = 4.dp),
      )
    }

    if (onlineWithoutThread.isNotEmpty()) {
      item(key = "online_section_hdr") {
        Text(
          text = strings.onlineNearbySection,
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
        )
      }
      items(items = onlineWithoutThread, key = { "online_${it.id}" }) { peer ->
        val accent =
          if (peer.transportMode == TransportMode.HOTSPOT_WIFI) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.secondary
        val isSelected = selectedKey == stablePeerKey(peer)

        Card(
          onClick = { onSelectPeer(peer) },
          colors =
            CardDefaults.cardColors(
              containerColor =
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.surface
            ),
          border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
          shape = MaterialTheme.shapes.large,
          modifier = Modifier.fillMaxWidth().testTag("chat_online_peer_${peer.id}"),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            AvatarBadge(name = peer.name, accentColor = accent)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                Text(
                  text = peer.address,
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
            Icon(
              imageVector = Icons.Rounded.ChatBubbleOutline,
              contentDescription = strings.messageButton,
              tint = accent,
            )
          }
        }
      }
    }

    if (threads.isNotEmpty()) {
      item(key = "recent_chats_hdr") {
        Text(
          text = strings.recentChatsSection,
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 6.dp),
        )
      }
      items(items = threads, key = { "thread_${it.peerId}" }) { thread ->
        val peer =
          PeerDevice(
            id = thread.peerId,
            name = thread.peerName,
            address = thread.peerAddress,
            tcpPort = 45455,
            udpAudioPort = 45456,
            transportMode = thread.transportMode,
            isPaired = thread.transportMode == TransportMode.BLUETOOTH,
          )
        val accent =
          if (thread.transportMode == TransportMode.HOTSPOT_WIFI) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.secondary
        val isSelected = selectedKey == thread.peerId

        Card(
          onClick = { onSelectPeer(peer) },
          colors =
            CardDefaults.cardColors(
              containerColor =
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.surface
            ),
          border =
            BorderStroke(
              1.dp,
              if (isSelected) accent else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
            ),
          shape = MaterialTheme.shapes.large,
          modifier = Modifier.fillMaxWidth().testTag("chat_thread_${thread.peerId}"),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            AvatarBadge(name = thread.peerName, accentColor = accent)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = thread.peerName,
                  style = MaterialTheme.typography.titleMedium,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.weight(1f),
                )
                Text(
                  text = formatChatTime(thread.lastTimestampMs),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Text(
                text = thread.lastMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              TransportBadge(transportMode = thread.transportMode, strings = strings)
            }
          }
        }
      }
    }

    if (onlineWithoutThread.isEmpty() && threads.isEmpty()) {
      item(key = "empty_chats") { EmptyChatPlaceholder(strings = strings) }
    }

    item { Spacer(Modifier.height(16.dp)) }
  }
}

@Composable
private fun EmptyChatPlaceholder(strings: LocalizedStrings) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    shape = MaterialTheme.shapes.large,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
    modifier = Modifier.fillMaxWidth().padding(16.dp),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(28.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(
        imageVector = Icons.Rounded.ChatBubbleOutline,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(44.dp),
      )
      Text(text = strings.messagesEmptyTitle, style = MaterialTheme.typography.titleMedium)
      Text(
        text = strings.messagesEmptyBody,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
fun ChatConversationPane(
  peer: PeerDevice,
  messages: List<ChatMessageEntity>,
  strings: LocalizedStrings,
  showBackButton: Boolean,
  onBack: () -> Unit,
  onSendMessage: (String) -> Unit,
  onCallPeer: (() -> Unit)? = null,
  onClearThread: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  var draft by rememberSaveable(peer.id) { mutableStateOf("") }
  val listState = rememberLazyListState()

  LaunchedEffect(messages.size) {
    if (messages.isNotEmpty()) {
      listState.animateScrollToItem(messages.size - 1)
    }
  }

  Column(modifier = modifier.fillMaxSize().imePadding()) {
    // Conversation Header
    Surface(
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 2.dp,
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (showBackButton) {
          IconButton(
            onClick = onBack,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("chat_back_button"),
          ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = strings.cancel)
          }
        }
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = peer.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            TransportBadge(transportMode = peer.transportMode, strings = strings)
            Text(
              text = peer.address,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        if (onCallPeer != null) {
          IconButton(
            onClick = onCallPeer,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("chat_call_button"),
          ) {
            Icon(
              Icons.Rounded.Call,
              contentDescription = strings.callButton,
              tint = MaterialTheme.colorScheme.primary,
            )
          }
        }
        if (onClearThread != null && messages.isNotEmpty()) {
          IconButton(
            onClick = onClearThread,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("chat_clear_button"),
          ) {
            Icon(
              Icons.Rounded.DeleteOutline,
              contentDescription = strings.clearChat,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

    // Message Bubbles
    LazyColumn(
      state = listState,
      modifier = Modifier.weight(1f).fillMaxWidth(),
      contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      items(items = messages, key = { it.id }) { msg ->
        val isMe = msg.isFromMe
        Box(
          modifier = Modifier.fillMaxWidth(),
          contentAlignment = if (isMe) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
          Surface(
            color =
              if (isMe) MaterialTheme.colorScheme.primaryContainer
              else MaterialTheme.colorScheme.surfaceVariant,
            contentColor =
              if (isMe) MaterialTheme.colorScheme.onPrimaryContainer
              else MaterialTheme.colorScheme.onSurface,
            shape =
              RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isMe) 18.dp else 4.dp,
                bottomEnd = if (isMe) 4.dp else 18.dp,
              ),
            modifier = Modifier.widthIn(max = 310.dp),
          ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
              Text(text = msg.text, style = MaterialTheme.typography.bodyLarge)
              Spacer(Modifier.height(3.dp))
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.align(Alignment.End),
              ) {
                Text(
                  text = formatChatTime(msg.timestampMs),
                  style = MaterialTheme.typography.labelSmall,
                  color =
                    if (isMe) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isMe) {
                  Icon(
                    imageVector =
                      if (msg.delivered) Icons.Rounded.DoneAll else Icons.Rounded.Check,
                    contentDescription =
                      if (msg.delivered) strings.messageDelivered else strings.messageSending,
                    modifier = Modifier.size(14.dp),
                    tint =
                      if (msg.delivered) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                  )
                }
              }
            }
          }
        }
      }
    }

    // Message Input Bar
    Surface(
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 4.dp,
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedTextField(
          value = draft,
          onValueChange = { draft = it },
          placeholder = { Text(strings.typeMessageHint) },
          maxLines = 3,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
          keyboardActions =
            KeyboardActions(
              onSend = {
                if (draft.trim().isNotEmpty()) {
                  onSendMessage(draft)
                  draft = ""
                }
              }
            ),
          shape = RoundedCornerShape(24.dp),
          modifier = Modifier.weight(1f).testTag("chat_message_input"),
        )
        FilledIconButton(
          onClick = {
            if (draft.trim().isNotEmpty()) {
              onSendMessage(draft)
              draft = ""
            }
          },
          enabled = draft.trim().isNotEmpty(),
          shape = CircleShape,
          colors =
            IconButtonDefaults.filledIconButtonColors(
              containerColor = MaterialTheme.colorScheme.primary,
              contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
          modifier = Modifier.size(50.dp).minimumInteractiveComponentSize().testTag("send_message_button"),
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Rounded.Send,
            contentDescription = strings.sendMessage,
          )
        }
      }
    }
  }
}

private fun formatChatTime(ms: Long): String =
  SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(ms))
