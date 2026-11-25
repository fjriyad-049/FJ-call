package com.example.ui.viewmodel

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.VoiceAudioEngine
import com.example.data.local.AppPreferences
import com.example.data.local.CallLogEntity
import com.example.data.local.ChatMessageEntity
import com.example.data.local.NearCallDatabase
import com.example.data.local.UserSettings
import com.example.data.model.AppLanguage
import com.example.data.model.CallDirection
import com.example.data.model.CallEndReason
import com.example.data.model.CallState
import com.example.data.model.ChatThreadSummary
import com.example.data.model.NetworkStatus
import com.example.data.model.PeerDevice
import com.example.data.model.PermissionStatus
import com.example.data.model.TransportFilter
import com.example.data.model.TransportMode
import com.example.media.ScreenShareEngine
import com.example.network.BluetoothCallEngine
import com.example.network.CallTransportCallbacks
import com.example.network.WifiLanCallEngine
import com.example.service.CallForegroundService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class CallViewModel(application: Application) : AndroidViewModel(application) {

  private val appContext = application.applicationContext
  private val preferences = AppPreferences(appContext)
  private val db = NearCallDatabase.getInstance(appContext)
  private val callLogDao = db.callLogDao()
  private val chatMessageDao = db.chatMessageDao()
  private val audioEngine = VoiceAudioEngine(appContext)
  private val screenShareEngine = ScreenShareEngine(appContext)

  private val _callState = MutableStateFlow<CallState>(CallState.Idle)
  val callState: StateFlow<CallState> = _callState.asStateFlow()

  private val _transportFilter = MutableStateFlow(TransportFilter.ALL)
  val transportFilter: StateFlow<TransportFilter> = _transportFilter.asStateFlow()

  private val _permissionStatus = MutableStateFlow(PermissionStatus())
  val permissionStatus: StateFlow<PermissionStatus> = _permissionStatus.asStateFlow()

  private val _selectedChatPeer = MutableStateFlow<PeerDevice?>(null)
  val selectedChatPeer: StateFlow<PeerDevice?> = _selectedChatPeer.asStateFlow()

  private val _unreadMessageCount = MutableStateFlow(0)
  val unreadMessageCount: StateFlow<Int> = _unreadMessageCount.asStateFlow()

  val remoteScreenBitmap: StateFlow<Bitmap?> = screenShareEngine.remoteScreenBitmap

  private var callTimerJob: Job? = null
  private var endedDismissJob: Job? = null
  private var currentCallDirection: CallDirection = CallDirection.OUTGOING

  private val transportCallbacks =
    object : CallTransportCallbacks {
      override fun onIncomingCall(peer: PeerDevice, transport: TransportMode) {
        endedDismissJob?.cancel()
        currentCallDirection = CallDirection.INCOMING
        _callState.value = CallState.IncomingRinging(peer = peer, transport = transport)
        audioEngine.startIncomingRingtone()
      }

      override fun onPeerRinging(peer: PeerDevice, transport: TransportMode) {
        val current = _callState.value
        if (current is CallState.OutgoingRinging) {
          _callState.value = current.copy(peer = peer, isPeerRinging = true)
        }
      }

      override fun onCallConnected(peer: PeerDevice, transport: TransportMode) {
        endedDismissJob?.cancel()
        audioEngine.stopTones()
        screenShareEngine.clearRemoteScreen()
        _callState.value =
          CallState.Active(
            peer = peer,
            transport = transport,
            durationSeconds = 0L,
            isMuted = false,
            isSpeakerOn = false,
          )
        val label =
          if (transport == TransportMode.HOTSPOT_WIFI) "Hotspot / Wi-Fi LAN"
          else "Bluetooth RFCOMM"
        CallForegroundService.start(appContext, peer.name, label)
        startCallDurationTicker()
      }

      override fun onCallEnded(
        peer: PeerDevice,
        transport: TransportMode,
        reason: CallEndReason,
      ) {
        callTimerJob?.cancel()
        callTimerJob = null
        audioEngine.stopTones()
        audioEngine.stopVoiceSession()
        screenShareEngine.stopLocalCapture(notifyCallback = false)
        screenShareEngine.clearRemoteScreen()
        CallForegroundService.stop(appContext)

        val current = _callState.value
        val duration = (current as? CallState.Active)?.durationSeconds ?: 0L

        val logDirection =
          when {
            current is CallState.IncomingRinging -> CallDirection.MISSED
            else -> currentCallDirection
          }

        viewModelScope.launch {
          callLogDao.insert(
            CallLogEntity(
              peerId = peer.id,
              peerName = peer.name,
              peerAddress = peer.address,
              transportMode = transport.name,
              direction = logDirection.name,
              durationSeconds = duration,
            )
          )
        }

        _callState.value =
          CallState.Ended(
            peer = peer,
            transport = transport,
            reason = reason,
            durationSeconds = duration,
          )

        endedDismissJob?.cancel()
        endedDismissJob =
          viewModelScope.launch {
            delay(2200)
            if (_callState.value is CallState.Ended) {
              _callState.value = CallState.Idle
            }
          }
      }

      override fun onLatencyUpdated(latencyMs: Int) {
        val current = _callState.value
        if (current is CallState.Active) {
          _callState.value = current.copy(latencyMs = latencyMs)
        }
      }

      override fun onChatMessageReceived(
        peer: PeerDevice,
        transport: TransportMode,
        text: String,
      ) {
        val stableId = stablePeerKey(peer)
        viewModelScope.launch {
          chatMessageDao.insert(
            ChatMessageEntity(
              peerId = stableId,
              peerName = peer.name,
              peerAddress = peer.address,
              transportMode = transport.name,
              text = text,
              isFromMe = false,
              delivered = true,
            )
          )
          val activeOpenId = _selectedChatPeer.value?.let { stablePeerKey(it) }
          if (activeOpenId != stableId) {
            _unreadMessageCount.value = _unreadMessageCount.value + 1
          }
        }
      }

      override fun onRemoteScreenShareStarted(peer: PeerDevice) {
        val current = _callState.value
        if (current is CallState.Active) {
          _callState.value = current.copy(isRemoteSharingScreen = true)
        }
      }

      override fun onRemoteScreenFrameReceived(jpegBytes: ByteArray) {
        val current = _callState.value
        if (current is CallState.Active) {
          if (!current.isRemoteSharingScreen) {
            _callState.value = current.copy(isRemoteSharingScreen = true)
          }
          screenShareEngine.decodeRemoteFrame(jpegBytes)
        }
      }

      override fun onRemoteScreenShareStopped() {
        screenShareEngine.clearRemoteScreen()
        val current = _callState.value
        if (current is CallState.Active) {
          _callState.value = current.copy(isRemoteSharingScreen = false)
        }
      }
    }

  private val wifiEngine = WifiLanCallEngine(appContext, audioEngine, transportCallbacks)
  private val bluetoothEngine = BluetoothCallEngine(appContext, audioEngine, transportCallbacks)

  val settings: StateFlow<UserSettings> =
    preferences.settingsFlow.stateIn(
      scope = viewModelScope,
      started = SharingStarted.Eagerly,
      initialValue =
        UserSettings(
          deviceId = "init",
          displayName = Build.MODEL ?: "FJ Radio",
          language = AppLanguage.BENGALI,
        ),
    )

  val callHistory: StateFlow<List<CallLogEntity>> =
    callLogDao
      .observeAll()
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList(),
      )

  val allChatMessages: StateFlow<List<ChatMessageEntity>> =
    chatMessageDao
      .observeAllMessages()
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList(),
      )

  val peers: StateFlow<List<PeerDevice>> =
    combine(wifiEngine.peers, bluetoothEngine.peers, _transportFilter) { wifiPeers, btPeers, filter
        ->
        when (filter) {
          TransportFilter.ALL -> wifiPeers + btPeers
          TransportFilter.HOTSPOT_WIFI -> wifiPeers
          TransportFilter.BLUETOOTH -> btPeers
        }
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList(),
      )

  val allOnlinePeers: StateFlow<List<PeerDevice>> =
    combine(wifiEngine.peers, bluetoothEngine.peers) { wifiPeers, btPeers -> wifiPeers + btPeers }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList(),
      )

  val chatThreads: StateFlow<List<ChatThreadSummary>> =
    combine(allChatMessages, allOnlinePeers) { messages, onlinePeers ->
        val onlineMap = onlinePeers.associateBy { stablePeerKey(it) }
        messages
          .groupBy { it.peerId }
          .map { (peerId, threadMsgs) ->
            val latest = threadMsgs.last()
            val onlinePeer = onlineMap[peerId]
            ChatThreadSummary(
              peerId = peerId,
              peerName = onlinePeer?.name ?: latest.peerName,
              peerAddress = onlinePeer?.address ?: latest.peerAddress,
              transportMode = onlinePeer?.transportMode ?: latest.transportEnum,
              lastMessage = latest.text,
              lastTimestampMs = latest.timestampMs,
              isOnlineNow = onlinePeer != null,
            )
          }
          .sortedByDescending { it.lastTimestampMs }
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList(),
      )

  val networkStatus: StateFlow<NetworkStatus> =
    combine(
        wifiEngine.localIps,
        wifiEngine.isScanning,
        bluetoothEngine.bluetoothEnabled,
        bluetoothEngine.isDiscoverable,
        bluetoothEngine.isScanning,
      ) { ips, wifiScan, btEnabled, btDiscoverable, btScan ->
        NetworkStatus(
          localIpAddresses = ips,
          primaryIp = ips.firstOrNull(),
          wifiEnabled = ips.isNotEmpty(),
          hotspotOrLanActive = ips.isNotEmpty(),
          bluetoothSupported = bluetoothEngine.isBluetoothSupported(),
          bluetoothEnabled = btEnabled,
          bluetoothName = bluetoothEngine.getLocalBluetoothName(),
          isBluetoothDiscoverable = btDiscoverable,
          isScanningWifi = wifiScan,
          isScanningBluetooth = btScan,
        )
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = NetworkStatus(),
      )

  init {
    CallForegroundService.onNotificationHangup = { endCall() }

    viewModelScope.launch {
      val id = preferences.ensureDeviceId()
      preferences.settingsFlow.collect { user ->
        wifiEngine.updateLocalIdentity(id, user.displayName)
        bluetoothEngine.updateLocalIdentity(id, user.displayName)
      }
    }

    viewModelScope.launch {
      combine(audioEngine.localLevel, audioEngine.remoteLevel) { local, remote -> local to remote }
        .collect { (local, remote) ->
          val current = _callState.value
          if (current is CallState.Active) {
            _callState.value =
              current.copy(localAudioLevel = local, remoteAudioLevel = remote)
          }
        }
    }

    refreshPermissions()
    startEngines()
  }

  fun stablePeerKey(peer: PeerDevice): String = "${peer.transportMode.name}_${peer.address}"

  fun startEngines() {
    wifiEngine.startServersAndDiscovery()
    bluetoothEngine.start()
  }

  fun refreshPermissions() {
    val mic =
      ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    val bt =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
          PackageManager.PERMISSION_GRANTED &&
          ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED
      } else {
        true
      }

    val wifiOrLoc =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.NEARBY_WIFI_DEVICES) ==
          PackageManager.PERMISSION_GRANTED
      } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        true
      } else {
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
          PackageManager.PERMISSION_GRANTED ||
          ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION,
          ) == PackageManager.PERMISSION_GRANTED
      }

    val notif =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
          PackageManager.PERMISSION_GRANTED
      } else {
        true
      }

    _permissionStatus.value =
      PermissionStatus(
        microphoneGranted = mic,
        bluetoothGranted = bt,
        nearbyWifiOrLocationGranted = wifiOrLoc,
        notificationsGranted = notif,
      )

    if (bt) {
      bluetoothEngine.refreshStatusAndPairedDevices()
      bluetoothEngine.startRfcommServerIfPermitted()
    }
  }

  fun requiredRuntimePermissions(): Array<String> {
    val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      perms += Manifest.permission.BLUETOOTH_CONNECT
      perms += Manifest.permission.BLUETOOTH_SCAN
      perms += Manifest.permission.BLUETOOTH_ADVERTISE
    } else {
      perms += Manifest.permission.ACCESS_FINE_LOCATION
      perms += Manifest.permission.ACCESS_COARSE_LOCATION
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      perms += Manifest.permission.NEARBY_WIFI_DEVICES
      perms += Manifest.permission.POST_NOTIFICATIONS
    }
    return perms.toTypedArray()
  }

  fun setTransportFilter(filter: TransportFilter) {
    _transportFilter.value = filter
  }

  fun scanNearbyDevices() {
    wifiEngine.triggerActiveScanBurst()
    bluetoothEngine.startScan()
  }

  fun refreshBluetoothOnly() {
    bluetoothEngine.refreshStatusAndPairedDevices()
    bluetoothEngine.startRfcommServerIfPermitted()
  }

  fun openChatWithPeer(peer: PeerDevice) {
    _selectedChatPeer.value = peer
    _unreadMessageCount.value = 0
  }

  fun closeSelectedChat() {
    _selectedChatPeer.value = null
  }

  fun clearUnreadMessages() {
    _unreadMessageCount.value = 0
  }

  fun sendChatMessage(peer: PeerDevice, rawText: String) {
    val clean = rawText.trim()
    if (clean.isEmpty()) return
    val stableId = stablePeerKey(peer)
    viewModelScope.launch {
      val rowId =
        chatMessageDao.insert(
          ChatMessageEntity(
            peerId = stableId,
            peerName = peer.name,
            peerAddress = peer.address,
            transportMode = peer.transportMode.name,
            text = clean,
            isFromMe = true,
            delivered = false,
          )
        )
      val delivered =
        when (peer.transportMode) {
          TransportMode.HOTSPOT_WIFI -> wifiEngine.sendChatMessage(peer, clean)
          TransportMode.BLUETOOTH -> bluetoothEngine.sendChatMessage(peer, clean)
        }
      if (delivered) {
        chatMessageDao.updateDelivered(rowId, true)
      }
    }
  }

  fun clearChatThread(peer: PeerDevice) {
    viewModelScope.launch { chatMessageDao.deleteThread(stablePeerKey(peer)) }
  }

  fun createScreenShareIntent(): Intent? = screenShareEngine.createCaptureIntent()

  fun startScreenShare(resultCode: Int, data: Intent) {
    val active = _callState.value as? CallState.Active ?: return
    val transportLabel =
      if (active.transport == TransportMode.HOTSPOT_WIFI) "Hotspot / Wi-Fi LAN"
      else "Bluetooth RFCOMM"

    viewModelScope.launch {
      val fgReady =
        CallForegroundService.ensureMediaProjectionForeground(
          appContext,
          active.peer.name,
          transportLabel,
        )
      if (!fgReady) return@launch

      val started =
        screenShareEngine.startLocalCapture(
          resultCode = resultCode,
          data = data,
          transportMode = active.transport,
          onEncodedJpegFrame = { jpegBytes ->
            when (active.transport) {
              TransportMode.HOTSPOT_WIFI -> wifiEngine.sendScreenFrame(jpegBytes)
              TransportMode.BLUETOOTH -> bluetoothEngine.sendScreenFrame(jpegBytes)
            }
          },
          onStoppedBySystem = {
            when (active.transport) {
              TransportMode.HOTSPOT_WIFI -> wifiEngine.sendScreenShareStopped()
              TransportMode.BLUETOOTH -> bluetoothEngine.sendScreenShareStopped()
            }
            CallForegroundService.downgradeFromScreenShare(
              appContext,
              active.peer.name,
              transportLabel,
            )
            val cur = _callState.value
            if (cur is CallState.Active) {
              _callState.value = cur.copy(isSharingMyScreen = false)
            }
          },
        )

      if (started) {
        when (active.transport) {
          TransportMode.HOTSPOT_WIFI -> wifiEngine.sendScreenShareStarted()
          TransportMode.BLUETOOTH -> bluetoothEngine.sendScreenShareStarted()
        }
        val cur = _callState.value
        if (cur is CallState.Active) {
          _callState.value = cur.copy(isSharingMyScreen = true)
        }
      }
    }
  }

  fun stopScreenShare() {
    val active = _callState.value as? CallState.Active
    screenShareEngine.stopLocalCapture(notifyCallback = false)
    if (active != null) {
      when (active.transport) {
        TransportMode.HOTSPOT_WIFI -> wifiEngine.sendScreenShareStopped()
        TransportMode.BLUETOOTH -> bluetoothEngine.sendScreenShareStopped()
      }
      val transportLabel =
        if (active.transport == TransportMode.HOTSPOT_WIFI) "Hotspot / Wi-Fi LAN"
        else "Bluetooth RFCOMM"
      CallForegroundService.downgradeFromScreenShare(
        appContext,
        active.peer.name,
        transportLabel,
      )
      _callState.value = active.copy(isSharingMyScreen = false)
    }
  }

  fun callPeer(peer: PeerDevice) {
    if (_callState.value !is CallState.Idle && _callState.value !is CallState.Ended) return
    endedDismissJob?.cancel()
    currentCallDirection = CallDirection.OUTGOING
    _callState.value = CallState.OutgoingRinging(peer = peer, transport = peer.transportMode)
    audioEngine.startOutgoingRingback()

    when (peer.transportMode) {
      TransportMode.HOTSPOT_WIFI -> wifiEngine.initiateCall(peer)
      TransportMode.BLUETOOTH -> bluetoothEngine.initiateCall(peer)
    }
  }

  fun dialDirectIp(rawIp: String) {
    val cleanIp = rawIp.trim()
    if (cleanIp.isEmpty()) return
    val manualPeer =
      PeerDevice(
        id = "wifi_manual_$cleanIp",
        name = cleanIp,
        address = cleanIp,
        tcpPort = WifiLanCallEngine.DEFAULT_TCP_PORT,
        udpAudioPort = WifiLanCallEngine.DEFAULT_UDP_AUDIO_PORT,
        transportMode = TransportMode.HOTSPOT_WIFI,
        isDirectManual = true,
      )
    callPeer(manualPeer)
  }

  fun answerCall() {
    val current = _callState.value as? CallState.IncomingRinging ?: return
    audioEngine.stopTones()
    when (current.transport) {
      TransportMode.HOTSPOT_WIFI -> wifiEngine.acceptIncomingCall(current.peer)
      TransportMode.BLUETOOTH -> bluetoothEngine.acceptIncomingCall(current.peer)
    }
  }

  fun declineCall() {
    val current = _callState.value as? CallState.IncomingRinging ?: return
    audioEngine.stopTones()
    when (current.transport) {
      TransportMode.HOTSPOT_WIFI -> wifiEngine.rejectIncomingCall(current.peer)
      TransportMode.BLUETOOTH -> bluetoothEngine.rejectIncomingCall(current.peer)
    }
  }

  fun endCall() {
    val current = _callState.value
    val transport =
      when (current) {
        is CallState.OutgoingRinging -> current.transport
        is CallState.Active -> current.transport
        is CallState.IncomingRinging -> {
          declineCall()
          return
        }
        else -> return
      }
    screenShareEngine.stopLocalCapture(notifyCallback = false)
    screenShareEngine.clearRemoteScreen()

    val reason =
      if (current is CallState.OutgoingRinging) CallEndReason.CANCELLED
      else CallEndReason.COMPLETED

    when (transport) {
      TransportMode.HOTSPOT_WIFI -> wifiEngine.endActiveCall(reason)
      TransportMode.BLUETOOTH -> bluetoothEngine.endActiveCall(reason)
    }
  }

  fun dismissEndedBanner() {
    endedDismissJob?.cancel()
    if (_callState.value is CallState.Ended) {
      _callState.value = CallState.Idle
    }
  }

  fun toggleMute() {
    val current = _callState.value as? CallState.Active ?: return
    val nextMuted = !current.isMuted
    audioEngine.setMuted(nextMuted)
    _callState.value = current.copy(isMuted = nextMuted)
  }

  fun toggleSpeaker() {
    val current = _callState.value as? CallState.Active ?: return
    val nextSpeaker = !current.isSpeakerOn
    audioEngine.setSpeakerEnabled(nextSpeaker)
    _callState.value = current.copy(isSpeakerOn = nextSpeaker)
  }

  fun updateDisplayName(name: String) {
    viewModelScope.launch { preferences.updateDisplayName(name) }
  }

  fun toggleLanguage() {
    viewModelScope.launch {
      val next =
        if (settings.value.language == AppLanguage.BENGALI) AppLanguage.ENGLISH
        else AppLanguage.BENGALI
      preferences.setLanguage(next)
    }
  }

  fun toggleTheme(currentlyDark: Boolean) {
    viewModelScope.launch { preferences.toggleDarkTheme(!currentlyDark) }
  }

  fun clearHistory() {
    viewModelScope.launch { callLogDao.clearAll() }
  }

  private fun startCallDurationTicker() {
    callTimerJob?.cancel()
    callTimerJob =
      viewModelScope.launch {
        var elapsed = 0L
        while (isActive) {
          delay(1000)
          elapsed += 1
          val current = _callState.value
          if (current is CallState.Active) {
            _callState.value = current.copy(durationSeconds = elapsed)
          } else {
            break
          }
        }
      }
  }

  override fun onCleared() {
    CallForegroundService.onNotificationHangup = null
    CallForegroundService.stop(appContext)
    screenShareEngine.stopLocalCapture(notifyCallback = false)
    wifiEngine.shutdown()
    bluetoothEngine.shutdown()
    audioEngine.stopVoiceSession()
    audioEngine.stopTones()
    super.onCleared()
  }
}
