package com.example.network

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.audio.VoiceAudioEngine
import com.example.data.model.CallEndReason
import com.example.data.model.PeerDevice
import com.example.data.model.TransportMode
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BluetoothCallEngine(
  private val context: Context,
  private val audioEngine: VoiceAudioEngine,
  private val callbacks: CallTransportCallbacks,
) {

  companion object {
    private const val TAG = "BluetoothCallEngine"
    private val APP_UUID: UUID = UUID.fromString("8e2b5a10-4c9d-4e7a-9b3f-1a2b3c4d5e6f")
    private const val SERVICE_NAME = "FJRadioVoice"

    private const val FRAME_INVITE: Byte = 0x01
    private const val FRAME_RINGING: Byte = 0x02
    private const val FRAME_ACCEPT: Byte = 0x03
    private const val FRAME_REJECT: Byte = 0x04
    private const val FRAME_HANGUP: Byte = 0x05
    private const val FRAME_AUDIO: Byte = 0x06
    private const val FRAME_PING: Byte = 0x07
    private const val FRAME_PONG: Byte = 0x08
    private const val FRAME_BUSY: Byte = 0x09
    private const val FRAME_CHAT: Byte = 0x0A
    private const val FRAME_CHAT_ACK: Byte = 0x0B
    private const val FRAME_SCREEN_START: Byte = 0x0C
    private const val FRAME_SCREEN_FRAME: Byte = 0x0D
    private const val FRAME_SCREEN_STOP: Byte = 0x0E
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val bluetoothManager =
    context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
  private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

  private val deviceMap = ConcurrentHashMap<String, PeerDevice>()
  private val _peers = MutableStateFlow<List<PeerDevice>>(emptyList())
  val peers: StateFlow<List<PeerDevice>> = _peers.asStateFlow()

  private val _isScanning = MutableStateFlow(false)
  val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

  private val _bluetoothEnabled = MutableStateFlow(false)
  val bluetoothEnabled: StateFlow<Boolean> = _bluetoothEnabled.asStateFlow()

  private val _isDiscoverable = MutableStateFlow(false)
  val isDiscoverable: StateFlow<Boolean> = _isDiscoverable.asStateFlow()

  @Volatile private var localDeviceId: String = "unknown"
  @Volatile private var localDisplayName: String = "FJ Radio"

  private var serverSocket: BluetoothServerSocket? = null
  private var serverJob: Job? = null
  private var receiverRegistered = false

  @Volatile private var activeSocket: BluetoothSocket? = null
  @Volatile private var activePeer: PeerDevice? = null
  private var writerChannel: Channel<Pair<Byte, ByteArray>>? = null
  private var writerJob: Job? = null
  private var pingJob: Job? = null

  private val btReceiver =
    object : BroadcastReceiver() {
      @SuppressLint("MissingPermission")
      override fun onReceive(ctx: Context?, intent: Intent?) {
        when (intent?.action) {
          BluetoothDevice.ACTION_FOUND -> {
            val device: BluetoothDevice? =
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(
                  BluetoothDevice.EXTRA_DEVICE,
                  BluetoothDevice::class.java,
                )
              } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
              }
            device?.let { addOrUpdateDevice(it, isPaired = false) }
          }
          BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
            _isScanning.value = false
          }
          BluetoothAdapter.ACTION_STATE_CHANGED -> {
            refreshStatusAndPairedDevices()
            if (adapter?.isEnabled == true) {
              startRfcommServerIfPermitted()
            }
          }
          BluetoothAdapter.ACTION_SCAN_MODE_CHANGED -> {
            val mode =
              intent.getIntExtra(
                BluetoothAdapter.EXTRA_SCAN_MODE,
                BluetoothAdapter.SCAN_MODE_NONE,
              )
            _isDiscoverable.value = mode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE
          }
        }
      }
    }

  fun updateLocalIdentity(deviceId: String, displayName: String) {
    localDeviceId = deviceId
    localDisplayName = displayName.replace("|", " ").trim().ifEmpty { "FJ Radio" }
  }

  fun isBluetoothSupported(): Boolean = adapter != null

  @SuppressLint("MissingPermission")
  fun getLocalBluetoothName(): String? {
    if (!hasConnectPermission()) return null
    return runCatching { adapter?.name }.getOrNull()
  }

  fun hasConnectPermission(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED
    } else {
      true
    }

  fun hasScanPermission(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
        PackageManager.PERMISSION_GRANTED
    } else {
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
          PackageManager.PERMISSION_GRANTED
    }

  fun start() {
    registerReceiverIfNeeded()
    refreshStatusAndPairedDevices()
    startRfcommServerIfPermitted()
  }

  @SuppressLint("MissingPermission")
  fun refreshStatusAndPairedDevices() {
    val bt = adapter
    _bluetoothEnabled.value = bt?.isEnabled == true
    if (bt == null || !bt.isEnabled || !hasConnectPermission()) {
      return
    }
    try {
      _isDiscoverable.value =
        bt.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE
      val bonded = bt.bondedDevices.orEmpty()
      for (dev in bonded) {
        addOrUpdateDevice(dev, isPaired = true)
      }
    } catch (e: SecurityException) {
      Log.w(TAG, "Missing BT connect permission", e)
    }
  }

  @SuppressLint("MissingPermission")
  fun startScan() {
    refreshStatusAndPairedDevices()
    val bt = adapter ?: return
    if (!bt.isEnabled || !hasScanPermission()) return
    try {
      if (bt.isDiscovering) {
        bt.cancelDiscovery()
      }
      _isScanning.value = bt.startDiscovery()
    } catch (e: SecurityException) {
      Log.w(TAG, "Cannot start BT discovery", e)
      _isScanning.value = false
    }
  }

  private fun registerReceiverIfNeeded() {
    if (receiverRegistered) return
    val filter =
      IntentFilter().apply {
        addAction(BluetoothDevice.ACTION_FOUND)
        addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED)
      }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      context.registerReceiver(btReceiver, filter, Context.RECEIVER_EXPORTED)
    } else {
      context.registerReceiver(btReceiver, filter)
    }
    receiverRegistered = true
  }

  @SuppressLint("MissingPermission")
  private fun addOrUpdateDevice(device: BluetoothDevice, isPaired: Boolean) {
    if (!hasConnectPermission()) return
    val addr = device.address ?: return
    val name = runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: addr
    val key = "bt_$addr"
    val existing = deviceMap[key]
    deviceMap[key] =
      PeerDevice(
        id = key,
        name = name,
        address = addr,
        transportMode = TransportMode.BLUETOOTH,
        isPaired = isPaired || (existing?.isPaired == true),
        lastSeenMs = System.currentTimeMillis(),
      )
    _peers.value =
      deviceMap.values.sortedWith(
        compareByDescending<PeerDevice> { it.isPaired }.thenBy { it.name.lowercase() }
      )
  }

  @SuppressLint("MissingPermission")
  fun startRfcommServerIfPermitted() {
    val bt = adapter ?: return
    if (!bt.isEnabled || !hasConnectPermission()) return
    if (serverJob?.isActive == true) return

    serverJob =
      scope.launch {
        try {
          val srv =
            runCatching { bt.listenUsingRfcommWithServiceRecord(SERVICE_NAME, APP_UUID) }
              .getOrElse {
                bt.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, APP_UUID)
              }
          serverSocket = srv
          while (isActive) {
            val socket = srv.accept() ?: break
            launch { handleIncomingRfcommSocket(socket) }
          }
        } catch (e: Exception) {
          Log.w(TAG, "BT RFCOMM server loop stopped: ${e.message}")
        }
      }
  }

  @SuppressLint("MissingPermission")
  suspend fun sendChatMessage(peer: PeerDevice, text: String): Boolean =
    withContext(Dispatchers.IO) {
      val payload = "$localDeviceId|$localDisplayName|$text".toByteArray(Charsets.UTF_8)

      // If already in an active RFCOMM call with this peer, multiplex directly
      if (activeSocket?.isConnected == true && activePeer?.address == peer.address) {
        sendFrame(FRAME_CHAT, payload)
        return@withContext true
      }

      val bt = adapter ?: return@withContext false
      if (!bt.isEnabled || !hasConnectPermission()) return@withContext false

      var tempSocket: BluetoothSocket? = null
      return@withContext try {
        if (hasScanPermission() && bt.isDiscovering) {
          bt.cancelDiscovery()
        }
        val device = bt.getRemoteDevice(peer.address)
        val sock =
          try {
            device.createRfcommSocketToServiceRecord(APP_UUID).also { it.connect() }
          } catch (_: Exception) {
            device.createInsecureRfcommSocketToServiceRecord(APP_UUID).also { it.connect() }
          }
        tempSocket = sock
        val out = DataOutputStream(sock.outputStream)
        val input = DataInputStream(sock.inputStream)
        out.writeByte(FRAME_CHAT.toInt())
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
        val ackType = input.readByte()
        val ackLen = input.readInt()
        if (ackLen > 0 && ackLen < 4096) {
          input.readFully(ByteArray(ackLen))
        }
        ackType == FRAME_CHAT_ACK
      } catch (e: Exception) {
        Log.w(TAG, "BT offline chat send failed: ${e.message}")
        false
      } finally {
        runCatching { tempSocket?.close() }
      }
    }

  fun sendScreenShareStarted() {
    sendFrame(FRAME_SCREEN_START, ByteArray(0))
  }

  fun sendScreenFrame(jpegBytes: ByteArray) {
    if (activeSocket?.isConnected != true) return
    // Drop excess video frames if RFCOMM queue is backed up so voice stays real-time
    if (jpegBytes.size <= 65_000) {
      sendFrame(FRAME_SCREEN_FRAME, jpegBytes)
    }
  }

  fun sendScreenShareStopped() {
    sendFrame(FRAME_SCREEN_STOP, ByteArray(0))
  }

  @SuppressLint("MissingPermission")
  fun initiateCall(peer: PeerDevice) {
    val bt = adapter ?: return
    if (!bt.isEnabled || !hasConnectPermission()) {
      callbacks.onCallEnded(peer, TransportMode.BLUETOOTH, CallEndReason.UNREACHABLE)
      return
    }
    scope.launch {
      try {
        if (hasScanPermission() && bt.isDiscovering) {
          bt.cancelDiscovery()
          _isScanning.value = false
        }
        val remoteDevice = bt.getRemoteDevice(peer.address)
        val socket =
          try {
            val secure = remoteDevice.createRfcommSocketToServiceRecord(APP_UUID)
            secure.connect()
            secure
          } catch (_: Exception) {
            val insecure = remoteDevice.createInsecureRfcommSocketToServiceRecord(APP_UUID)
            insecure.connect()
            insecure
          }

        setupActiveSocket(socket, peer)
        sendFrame(FRAME_INVITE, "$localDeviceId|$localDisplayName".toByteArray(Charsets.UTF_8))
        listenToRfcommFrames(socket, isCaller = true, initialPeer = peer)
      } catch (e: Exception) {
        Log.e(TAG, "Bluetooth call failed to ${peer.address}", e)
        cleanupCallState()
        callbacks.onCallEnded(peer, TransportMode.BLUETOOTH, CallEndReason.UNREACHABLE)
      }
    }
  }

  fun acceptIncomingCall(peer: PeerDevice) {
    scope.launch {
      sendFrame(FRAME_ACCEPT, "$localDeviceId|$localDisplayName".toByteArray(Charsets.UTF_8))
      startVoiceAndPing(peer)
    }
  }

  fun rejectIncomingCall(peer: PeerDevice) {
    scope.launch {
      sendFrame(FRAME_REJECT, localDeviceId.toByteArray(Charsets.UTF_8))
      delay(80)
      cleanupCallState()
      callbacks.onCallEnded(peer, TransportMode.BLUETOOTH, CallEndReason.REJECTED)
    }
  }

  fun endActiveCall(reason: CallEndReason = CallEndReason.COMPLETED) {
    val peer = activePeer
    scope.launch {
      sendFrame(FRAME_HANGUP, localDeviceId.toByteArray(Charsets.UTF_8))
      delay(80)
      cleanupCallState()
      if (peer != null) {
        callbacks.onCallEnded(peer, TransportMode.BLUETOOTH, reason)
      }
    }
  }

  @SuppressLint("MissingPermission")
  private fun handleIncomingRfcommSocket(socket: BluetoothSocket) {
    val remoteAddr = socket.remoteDevice?.address ?: "00:00:00:00:00:00"
    val remoteBtName =
      if (hasConnectPermission()) {
        runCatching { socket.remoteDevice?.name }.getOrNull()
      } else null
    val initialPeer =
      PeerDevice(
        id = "bt_$remoteAddr",
        name = remoteBtName ?: remoteAddr,
        address = remoteAddr,
        transportMode = TransportMode.BLUETOOTH,
        isPaired = true,
      )

    try {
      val input = DataInputStream(socket.inputStream)
      val firstType = input.readByte()
      val firstLen = input.readInt()
      val firstPayload = ByteArray(firstLen.coerceIn(0, 200_000))
      if (firstPayload.isNotEmpty()) input.readFully(firstPayload)

      // Handle standalone offline Bluetooth chat message without initiating a call
      if (firstType == FRAME_CHAT) {
        val parts = String(firstPayload, Charsets.UTF_8).split("|", limit = 3)
        val senderName = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: initialPeer.name
        val text = parts.getOrNull(2).orEmpty()
        val out = DataOutputStream(socket.outputStream)
        out.writeByte(FRAME_CHAT_ACK.toInt())
        out.writeInt(0)
        out.flush()
        socket.close()
        if (text.isNotEmpty()) {
          callbacks.onChatMessageReceived(
            initialPeer.copy(name = senderName),
            TransportMode.BLUETOOTH,
            text,
          )
        }
        return
      }

      if (activeSocket != null && activeSocket?.isConnected == true) {
        val out = DataOutputStream(socket.outputStream)
        val payload = localDeviceId.toByteArray(Charsets.UTF_8)
        out.writeByte(FRAME_BUSY.toInt())
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
        socket.close()
        return
      }

      setupActiveSocket(socket, initialPeer)
      processFrame(firstType, firstPayload, isCaller = false, currentPeer = initialPeer)?.let {
        updatedPeer ->
        listenToRfcommFrames(socket, isCaller = false, initialPeer = updatedPeer, existingInput = input)
      }
    } catch (e: Exception) {
      Log.w(TAG, "Incoming RFCOMM handler error: ${e.message}")
      runCatching { socket.close() }
    }
  }

  private fun setupActiveSocket(socket: BluetoothSocket, peer: PeerDevice) {
    activeSocket = socket
    activePeer = peer
    val channel = Channel<Pair<Byte, ByteArray>>(capacity = 48)
    writerChannel = channel
    writerJob?.cancel()
    writerJob =
      scope.launch {
        try {
          val out = DataOutputStream(socket.outputStream)
          for ((type, payload) in channel) {
            out.writeByte(type.toInt())
            out.writeInt(payload.size)
            if (payload.isNotEmpty()) out.write(payload)
            out.flush()
          }
        } catch (e: Exception) {
          Log.w(TAG, "BT writer loop ended: ${e.message}")
        }
      }
  }

  private fun sendFrame(type: Byte, payload: ByteArray) {
    writerChannel?.trySend(type to payload)
  }

  private fun listenToRfcommFrames(
    socket: BluetoothSocket,
    isCaller: Boolean,
    initialPeer: PeerDevice,
    existingInput: DataInputStream? = null,
  ) {
    var currentPeer = initialPeer
    try {
      val input = existingInput ?: DataInputStream(socket.inputStream)
      while (socket.isConnected) {
        val type = input.readByte()
        val length = input.readInt()
        if (length < 0 || length > 250_000) break
        val payload = ByteArray(length)
        if (length > 0) input.readFully(payload)

        val nextPeer = processFrame(type, payload, isCaller, currentPeer)
        if (nextPeer == null) {
          return
        }
        currentPeer = nextPeer
      }
    } catch (_: EOFException) {
      if (activeSocket === socket) {
        cleanupCallState()
        callbacks.onCallEnded(currentPeer, TransportMode.BLUETOOTH, CallEndReason.COMPLETED)
      }
    } catch (_: Exception) {
      if (activeSocket === socket) {
        cleanupCallState()
        callbacks.onCallEnded(currentPeer, TransportMode.BLUETOOTH, CallEndReason.COMPLETED)
      }
    }
  }

  private fun processFrame(
    type: Byte,
    payload: ByteArray,
    isCaller: Boolean,
    currentPeer: PeerDevice,
  ): PeerDevice? {
    var updatedPeer = currentPeer
    when (type) {
      FRAME_INVITE -> {
        if (!isCaller) {
          val parts = String(payload, Charsets.UTF_8).split("|")
          val callerName = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: updatedPeer.name
          updatedPeer = updatedPeer.copy(name = callerName)
          activePeer = updatedPeer
          sendFrame(
            FRAME_RINGING,
            "$localDeviceId|$localDisplayName".toByteArray(Charsets.UTF_8),
          )
          callbacks.onIncomingCall(updatedPeer, TransportMode.BLUETOOTH)
        }
      }
      FRAME_RINGING -> {
        if (isCaller) {
          val parts = String(payload, Charsets.UTF_8).split("|")
          val calleeName = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
          if (calleeName != null) {
            updatedPeer = updatedPeer.copy(name = calleeName)
            activePeer = updatedPeer
          }
          callbacks.onPeerRinging(updatedPeer, TransportMode.BLUETOOTH)
        }
      }
      FRAME_ACCEPT -> {
        if (isCaller) {
          val parts = String(payload, Charsets.UTF_8).split("|")
          val calleeName = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: updatedPeer.name
          updatedPeer = updatedPeer.copy(name = calleeName)
          activePeer = updatedPeer
          startVoiceAndPing(updatedPeer)
        }
      }
      FRAME_CHAT -> {
        val parts = String(payload, Charsets.UTF_8).split("|", limit = 3)
        val text = parts.getOrNull(2).orEmpty()
        if (text.isNotEmpty()) {
          callbacks.onChatMessageReceived(updatedPeer, TransportMode.BLUETOOTH, text)
        }
      }
      FRAME_SCREEN_START -> callbacks.onRemoteScreenShareStarted(updatedPeer)
      FRAME_SCREEN_FRAME -> callbacks.onRemoteScreenFrameReceived(payload)
      FRAME_SCREEN_STOP -> callbacks.onRemoteScreenShareStopped()
      FRAME_REJECT -> {
        cleanupCallState()
        callbacks.onCallEnded(updatedPeer, TransportMode.BLUETOOTH, CallEndReason.REJECTED)
        return null
      }
      FRAME_BUSY -> {
        cleanupCallState()
        callbacks.onCallEnded(updatedPeer, TransportMode.BLUETOOTH, CallEndReason.BUSY)
        return null
      }
      FRAME_HANGUP -> {
        cleanupCallState()
        callbacks.onCallEnded(updatedPeer, TransportMode.BLUETOOTH, CallEndReason.COMPLETED)
        return null
      }
      FRAME_AUDIO -> audioEngine.writePlaybackFrame(payload, payload.size)
      FRAME_PING -> sendFrame(FRAME_PONG, payload)
      FRAME_PONG -> {
        if (payload.size == 8) {
          val sent = ByteBuffer.wrap(payload).long
          val rtt = (System.currentTimeMillis() - sent).toInt().coerceIn(1, 999)
          callbacks.onLatencyUpdated(rtt)
        }
      }
    }
    return updatedPeer
  }

  private fun startVoiceAndPing(peer: PeerDevice) {
    callbacks.onCallConnected(peer, TransportMode.BLUETOOTH)
    audioEngine.startVoiceSession(speakerDefault = false) { muLawFrame ->
      sendFrame(FRAME_AUDIO, muLawFrame)
    }
    pingJob?.cancel()
    pingJob =
      scope.launch {
        while (isActive && activeSocket?.isConnected == true) {
          val buf = ByteBuffer.allocate(8).putLong(System.currentTimeMillis()).array()
          sendFrame(FRAME_PING, buf)
          delay(2500)
        }
      }
  }

  private fun cleanupCallState() {
    pingJob?.cancel()
    pingJob = null
    audioEngine.stopVoiceSession()
    audioEngine.stopTones()
    writerJob?.cancel()
    writerJob = null
    writerChannel?.close()
    writerChannel = null
    runCatching { activeSocket?.close() }
    activeSocket = null
    activePeer = null
  }

  fun shutdown() {
    cleanupCallState()
    serverJob?.cancel()
    runCatching { serverSocket?.close() }
    if (receiverRegistered) {
      runCatching { context.unregisterReceiver(btReceiver) }
      receiverRegistered = false
    }
  }
}
