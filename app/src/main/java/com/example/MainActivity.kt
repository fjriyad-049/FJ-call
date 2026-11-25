package com.example

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Help
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.AppLanguage
import com.example.data.model.CallState
import com.example.ui.components.EditNameDialog
import com.example.ui.screens.CallOverlayScreen
import com.example.ui.screens.GuideSetupScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.MessagesScreen
import com.example.ui.screens.RadarHomeScreen
import com.example.ui.stringsFor
import com.example.ui.theme.NearCallTheme
import com.example.ui.viewmodel.CallViewModel

private enum class MainTab {
  RADAR,
  MESSAGES,
  HISTORY,
  GUIDE,
}

class MainActivity : ComponentActivity() {

  private val callViewModel: CallViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val settings by callViewModel.settings.collectAsStateWithLifecycle()
      val isDark = settings.darkThemeOverride ?: true

      NearCallTheme(darkTheme = isDark) {
        FJRadioRootApp(
          viewModel = callViewModel,
          isDark = isDark,
          onToggleTheme = { callViewModel.toggleTheme(isDark) },
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    callViewModel.refreshPermissions()
    callViewModel.refreshBluetoothOnly()
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FJRadioRootApp(
  viewModel: CallViewModel,
  isDark: Boolean,
  onToggleTheme: () -> Unit,
) {
  val context = LocalContext.current
  val settings by viewModel.settings.collectAsStateWithLifecycle()
  val peers by viewModel.peers.collectAsStateWithLifecycle()
  val onlinePeers by viewModel.allOnlinePeers.collectAsStateWithLifecycle()
  val filter by viewModel.transportFilter.collectAsStateWithLifecycle()
  val networkStatus by viewModel.networkStatus.collectAsStateWithLifecycle()
  val permissionStatus by viewModel.permissionStatus.collectAsStateWithLifecycle()
  val callState by viewModel.callState.collectAsStateWithLifecycle()
  val callHistory by viewModel.callHistory.collectAsStateWithLifecycle()
  val chatThreads by viewModel.chatThreads.collectAsStateWithLifecycle()
  val allChatMessages by viewModel.allChatMessages.collectAsStateWithLifecycle()
  val selectedChatPeer by viewModel.selectedChatPeer.collectAsStateWithLifecycle()
  val unreadCount by viewModel.unreadMessageCount.collectAsStateWithLifecycle()
  val remoteScreenBitmap by viewModel.remoteScreenBitmap.collectAsStateWithLifecycle()

  val strings = remember(settings.language) { stringsFor(settings.language) }
  var currentTab by rememberSaveable { mutableStateOf(MainTab.RADAR) }
  var showEditNameDialog by remember { mutableStateOf(false) }

  val permissionLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
      viewModel.refreshPermissions()
      viewModel.startEngines()
    }

  val discoverableLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.StartActivityForResult()
    ) {
      viewModel.refreshBluetoothOnly()
    }

  val screenShareLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
      val data = result.data
      if (result.resultCode == Activity.RESULT_OK && data != null) {
        viewModel.startScreenShare(result.resultCode, data)
      }
    }

  LaunchedEffect(Unit) {
    if (!permissionStatus.allCoreGranted) {
      permissionLauncher.launch(viewModel.requiredRuntimePermissions())
    }
  }

  LaunchedEffect(currentTab) {
    if (currentTab == MainTab.MESSAGES) {
      viewModel.clearUnreadMessages()
    }
  }

  val launchPermissions: () -> Unit = {
    permissionLauncher.launch(viewModel.requiredRuntimePermissions())
  }

  val launchScreenShareConsent: () -> Unit = {
    viewModel.createScreenShareIntent()?.let { intent ->
      runCatching { screenShareLauncher.launch(intent) }
    }
  }

  val launchDiscoverable: () -> Unit = {
    val intent =
      Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
        putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
      }
    runCatching { discoverableLauncher.launch(intent) }
      .onFailure {
        runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
      }
  }

  val openHotspotSettings: () -> Unit = {
    val tetherIntent =
      Intent().apply {
        setClassName("com.android.settings", "com.android.settings.TetherSettings")
      }
    runCatching { context.startActivity(tetherIntent) }
      .onFailure {
        runCatching { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
      }
  }

  val openWifiSettings: () -> Unit = {
    runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
  }

  val openBtSettings: () -> Unit = {
    runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
  }

  Box(modifier = Modifier.fillMaxSize()) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
      val useNavRail = maxWidth >= 680.dp

      Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
          TopAppBar(
            colors =
              TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
              ),
            title = {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Text(
                  text = strings.appName,
                  style = MaterialTheme.typography.titleLarge,
                  fontWeight = FontWeight.Bold,
                )
                Surface(
                  shape = RoundedCornerShape(50),
                  color = MaterialTheme.colorScheme.primaryContainer,
                  contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                  Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                  ) {
                    Icon(
                      imageVector = Icons.Rounded.WifiOff,
                      contentDescription = null,
                      modifier = Modifier.size(12.dp),
                    )
                    Text(
                      text = strings.offlineBadge,
                      style = MaterialTheme.typography.labelSmall,
                      fontWeight = FontWeight.SemiBold,
                    )
                  }
                }
              }
            },
            actions = {
              Surface(
                onClick = { showEditNameDialog = true },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                modifier = Modifier.minimumInteractiveComponentSize().testTag("edit_name_chip"),
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                  Text(
                    text = settings.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                  Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = strings.editNameTitle,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                  )
                }
              }

              Spacer(Modifier.width(6.dp))

              Surface(
                onClick = { viewModel.toggleLanguage() },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier =
                  Modifier.minimumInteractiveComponentSize().testTag("language_toggle_button"),
              ) {
                Text(
                  text = if (settings.language == AppLanguage.BENGALI) "EN" else "বাং",
                  style = MaterialTheme.typography.labelLarge,
                  modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
              }

              IconButton(
                onClick = onToggleTheme,
                modifier = Modifier.minimumInteractiveComponentSize().testTag("theme_toggle_button"),
              ) {
                Icon(
                  imageVector = if (isDark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                  contentDescription = "Toggle theme",
                )
              }
            },
          )
        },
        bottomBar = {
          if (!useNavRail) {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
              NavigationBarItem(
                selected = currentTab == MainTab.RADAR,
                onClick = { currentTab = MainTab.RADAR },
                icon = {
                  Icon(
                    imageVector =
                      if (currentTab == MainTab.RADAR) Icons.Rounded.Radar
                      else Icons.Outlined.Radar,
                    contentDescription = strings.navRadar,
                  )
                },
                label = { Text(strings.navRadar) },
                modifier = Modifier.testTag("nav_radar"),
              )
              NavigationBarItem(
                selected = currentTab == MainTab.MESSAGES,
                onClick = { currentTab = MainTab.MESSAGES },
                icon = {
                  BadgedBox(
                    badge = {
                      if (unreadCount > 0) {
                        Badge { Text("$unreadCount") }
                      }
                    }
                  ) {
                    Icon(
                      imageVector =
                        if (currentTab == MainTab.MESSAGES) Icons.Rounded.ChatBubble
                        else Icons.Outlined.ChatBubbleOutline,
                      contentDescription = strings.navMessages,
                    )
                  }
                },
                label = { Text(strings.navMessages) },
                modifier = Modifier.testTag("nav_messages"),
              )
              NavigationBarItem(
                selected = currentTab == MainTab.HISTORY,
                onClick = { currentTab = MainTab.HISTORY },
                icon = {
                  Icon(
                    imageVector =
                      if (currentTab == MainTab.HISTORY) Icons.Rounded.History
                      else Icons.Outlined.History,
                    contentDescription = strings.navHistory,
                  )
                },
                label = { Text(strings.navHistory) },
                modifier = Modifier.testTag("nav_history"),
              )
              NavigationBarItem(
                selected = currentTab == MainTab.GUIDE,
                onClick = { currentTab = MainTab.GUIDE },
                icon = {
                  Icon(
                    imageVector =
                      if (currentTab == MainTab.GUIDE) Icons.Rounded.Help
                      else Icons.Outlined.HelpOutline,
                    contentDescription = strings.navGuide,
                  )
                },
                label = { Text(strings.navGuide) },
                modifier = Modifier.testTag("nav_guide"),
              )
            }
          }
        },
      ) { innerPadding ->
        Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
          if (useNavRail) {
            NavigationRail(
              containerColor = MaterialTheme.colorScheme.surface,
              modifier = Modifier.fillMaxHeight().statusBarsPadding(),
            ) {
              NavigationRailItem(
                selected = currentTab == MainTab.RADAR,
                onClick = { currentTab = MainTab.RADAR },
                icon = {
                  Icon(
                    imageVector =
                      if (currentTab == MainTab.RADAR) Icons.Rounded.Radar
                      else Icons.Outlined.Radar,
                    contentDescription = strings.navRadar,
                  )
                },
                label = { Text(strings.navRadar) },
                modifier = Modifier.testTag("rail_radar"),
              )
              NavigationRailItem(
                selected = currentTab == MainTab.MESSAGES,
                onClick = { currentTab = MainTab.MESSAGES },
                icon = {
                  BadgedBox(
                    badge = {
                      if (unreadCount > 0) {
                        Badge { Text("$unreadCount") }
                      }
                    }
                  ) {
                    Icon(
                      imageVector =
                        if (currentTab == MainTab.MESSAGES) Icons.Rounded.ChatBubble
                        else Icons.Outlined.ChatBubbleOutline,
                      contentDescription = strings.navMessages,
                    )
                  }
                },
                label = { Text(strings.navMessages) },
                modifier = Modifier.testTag("rail_messages"),
              )
              NavigationRailItem(
                selected = currentTab == MainTab.HISTORY,
                onClick = { currentTab = MainTab.HISTORY },
                icon = {
                  Icon(
                    imageVector =
                      if (currentTab == MainTab.HISTORY) Icons.Rounded.History
                      else Icons.Outlined.History,
                    contentDescription = strings.navHistory,
                  )
                },
                label = { Text(strings.navHistory) },
                modifier = Modifier.testTag("rail_history"),
              )
              NavigationRailItem(
                selected = currentTab == MainTab.GUIDE,
                onClick = { currentTab = MainTab.GUIDE },
                icon = {
                  Icon(
                    imageVector =
                      if (currentTab == MainTab.GUIDE) Icons.Rounded.Help
                      else Icons.Outlined.HelpOutline,
                    contentDescription = strings.navGuide,
                  )
                },
                label = { Text(strings.navGuide) },
                modifier = Modifier.testTag("rail_guide"),
              )
            }
          }

          Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            when (currentTab) {
              MainTab.RADAR ->
                RadarHomeScreen(
                  strings = strings,
                  networkStatus = networkStatus,
                  permissionStatus = permissionStatus,
                  peers = peers,
                  selectedFilter = filter,
                  onSelectFilter = viewModel::setTransportFilter,
                  onScanClick = viewModel::scanNearbyDevices,
                  onCallPeer = viewModel::callPeer,
                  onMessagePeer = { peer ->
                    viewModel.openChatWithPeer(peer)
                    currentTab = MainTab.MESSAGES
                  },
                  onDialDirectIp = viewModel::dialDirectIp,
                  onGrantPermissions = launchPermissions,
                  onOpenHotspotSettings = openHotspotSettings,
                  onOpenWifiSettings = openWifiSettings,
                  onMakeBtDiscoverable = launchDiscoverable,
                  onOpenBtSettings = openBtSettings,
                )
              MainTab.MESSAGES ->
                MessagesScreen(
                  strings = strings,
                  onlinePeers = onlinePeers,
                  threads = chatThreads,
                  allMessages = allChatMessages,
                  selectedPeer = selectedChatPeer,
                  stablePeerKey = viewModel::stablePeerKey,
                  onSelectPeer = viewModel::openChatWithPeer,
                  onClearSelectedPeer = viewModel::closeSelectedChat,
                  onSendMessage = viewModel::sendChatMessage,
                  onCallPeer = viewModel::callPeer,
                  onClearThread = viewModel::clearChatThread,
                  onNavigateBack = { currentTab = MainTab.RADAR },
                )
              MainTab.HISTORY ->
                HistoryScreen(
                  strings = strings,
                  logs = callHistory,
                  onRedial = { peer ->
                    currentTab = MainTab.RADAR
                    viewModel.callPeer(peer)
                  },
                  onClearHistory = viewModel::clearHistory,
                  onNavigateBack = { currentTab = MainTab.RADAR },
                )
              MainTab.GUIDE ->
                GuideSetupScreen(
                  strings = strings,
                  permissionStatus = permissionStatus,
                  onGrantPermissions = launchPermissions,
                  onNavigateBack = { currentTab = MainTab.RADAR },
                )
            }
          }
        }
      }
    }

    if (showEditNameDialog) {
      EditNameDialog(
        currentName = settings.displayName,
        strings = strings,
        onDismiss = { showEditNameDialog = false },
        onSave = viewModel::updateDisplayName,
      )
    }

    AnimatedVisibility(
      visible = callState !is CallState.Idle,
      enter = fadeIn(),
      exit = fadeOut(),
    ) {
      val activePeerKey =
        (callState as? CallState.Active)?.peer?.let { viewModel.stablePeerKey(it) }
      val inCallMessages =
        remember(allChatMessages, activePeerKey) {
          if (activePeerKey == null) emptyList()
          else allChatMessages.filter { it.peerId == activePeerKey }
        }

      CallOverlayScreen(
        callState = callState,
        strings = strings,
        remoteScreenBitmap = remoteScreenBitmap,
        chatMessages = inCallMessages,
        onAnswer = viewModel::answerCall,
        onDecline = viewModel::declineCall,
        onEndCall = viewModel::endCall,
        onToggleMute = viewModel::toggleMute,
        onToggleSpeaker = viewModel::toggleSpeaker,
        onRequestStartScreenShare = launchScreenShareConsent,
        onStopScreenShare = viewModel::stopScreenShare,
        onSendInCallMessage = viewModel::sendChatMessage,
        onDismissEnded = viewModel::dismissEndedBanner,
      )
    }
  }
}
