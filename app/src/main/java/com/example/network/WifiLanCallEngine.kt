package com.example.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Base64
import android.util.Log
import com.example.audio.VoiceAudioEngine
import com.example.data.model.CallEndReason
import com.example.data.model.PeerDevice
import com.example.data.model.TransportMode
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WifiLanCallEngine(
  private val context: Context,
  private val audioEngine: VoiceAudioEngine,
  private val callbacks: CallTransportCallbacks,
) {

  companion object {
    private const val TAG = "WifiLanCallEngine"
    const val DISCOVERY_PORT = 45454
    const val DEFAULT_TCP_PORT = 45455
    const val DEFAULT_UDP_AUDIO_PORT = 45456
    private const val NSD_SERVICE_TYPE = "_fjradio._tcp."
    private const val BEACON_PREFIX = "FJRADIO_BEACON"
    private const val PEER_STALE_MS = 7000L
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val wifiManager =
    context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
  private val connectivityManager =
    context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
  private val nsdManager =
    context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

  private val discoveredMap = ConcurrentHashMap<String, PeerDevice>()
  private val _peers = MutableStateFlow<List<PeerDevice>>(emptyList())
  val peers: StateFlow<List<PeerDevice>> = _peers.asStateFlow()

  private val _localIps = MutableStateFlow<List<String>>(emptyList())
  val localIps: StateFlow<List<String>> = _localIps.asStateFlow()

  private val _isScanning = MutableStateFlow(false)
  val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

  @Volatile private var localDeviceId: String = "unknown"
  @Volatile private var localDisplayName: String = "FJ Radio"

  private var tcpServerSocket: ServerSocket? = null
  private var tcpListenPort: Int = DEFAULT_TCP_PORT
  private var udpAudioSocket: DatagramSocket? = null
  private var udpAudioListenPort: Int = DEFAULT_UDP_AUDIO_PORT
  private var discoverySocket: DatagramSocket? = null
  private var multicastLock: WifiManager.MulticastLock? = null

  private var serverJob: Job? = null
  private var discoveryListenerJob: Job? = null
  private var beaconBroadcastJob: Job? = null
  private var audioRecvJob: Job? = null
  private var pingJob: Job? = null

  @Volatile private var activeTcpSocket: Socket? = null
  @Volatile private var activeWriter: PrintWriter? = null
  private val writerLock = Any()
  @Volatile private var activePeer: PeerDevice? = null
  @Volatile private var activePeerAddress: InetAddress? = null
  @Volatile private var activePeerAudioPort: Int = 0
  @Volatile private var audioSequence: Int = 0

  private var nsdRegListener: NsdManager.RegistrationListener? = null
  private var nsdDiscListener: NsdManager.DiscoveryListener? = null

  fun updateLocalIdentity(deviceId: String, displayName: String) {
    localDeviceId = deviceId
    localDisplayName = displayName.replace("|", " ").trim().ifEmpty { "FJ Radio" }
  }

  fun startServersAndDiscovery() {
    refreshNetworkIps()
    startTcpServerIfNeeded()
    startUdpAudioSocketIfNeeded()
    startUdpDiscovery()
    registerAndDiscoverNsd()
  }

  fun triggerActiveScanBurst() {
    refreshNetworkIps()
    scope.launch {
      _isScanning.value = true
      repeat(4) {
        sendBeaconBurst()
        delay(650)
      }
      pruneStalePeers()
      _isScanning.value = false
    }
  }

  fun refreshNetworkIps(): List<String> {
    val ips = mutableListOf<String>()
    try {
      val interfaces = NetworkInterface.getNetworkInterfaces()
      while (interfaces != null && interfaces.hasMoreElements()) {
        val ni = interfaces.nextElement()
        if (!ni.isUp || ni.isLoopback) continue
        val name = ni.name.lowercase()
        val isCellularOrTunnel =
          name.startsWith("rmnet") ||
            name.startsWith("ccmni") ||
            name.startsWith("v4-") ||
            name.startsWith("tun") ||
            name.startsWith("ppp") ||
            name.startsWith("dummy")
        if (isCellularOrTunnel) continue
        for (addr in ni.interfaceAddresses) {
          val inet = addr.address
          if (inet is Inet4Address && !inet.isLoopbackAddress && inet.isSiteLocalAddress) {
            inet.hostAddress?.let { ips.add(it) }
          }
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Error enumerating network interfaces", e)
    }
    _localIps.value = ips.distinct()
    return _localIps.value
  }

  fun isWifiOrHotspotReady(): Boolean {
    val ips = refreshNetworkIps()
    if (ips.isNotEmpty()) return true
    val nw = connectivityManager?.activeNetwork ?: return false
    val caps = connectivityManager.getNetworkCapabilities(nw) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
  }

  private fun getBroadcastTargets(): List<InetAddress> {
    val targets = mutableSetOf<InetAddress>()
    try {
      val interfaces = NetworkInterface.getNetworkInterfaces()
      while (interfaces != null && interfaces.hasMoreElements()) {
        val ni = interfaces.nextElement()
        if (!ni.isUp || ni.isLoopback) continue
        for (addr in ni.interfaceAddresses) {
          val inet = addr.address
          if (inet is Inet4Address && !inet.isLoopbackAddress && inet.isSiteLocalAddress) {
            addr.broadcast?.let { targets.add(it) }
            // Also target .1 gateway directly in case Hotspot AP filters broadcast
            val host = inet.hostAddress
            if (host != null && host.contains('.')) {
              val prefix = host.substringBeforeLast('.')
              runCatching { targets.add(InetAddress.getByName("$prefix.1")) }
            }
          }
        }
      }
    } catch (_: Exception) {}
    runCatching { targets.add(InetAddress.getByName("255.255.255.255")) }
    runCatching { targets.add(InetAddress.getByName("192.168.43.255")) }
    runCatching { targets.add(InetAddress.getByName("192.168.43.1")) }
    runCatching { targets.add(InetAddress.getByName("192.168.137.255")) }
    return targets.toList()
  }

  private fun sendSubnetUnicastSweep() {
    val sock = discoverySocket ?: return
    if (sock.isClosed) return
    val payload =
      "$BEACON_PREFIX|$localDeviceId|$localDisplayName|$tcpListenPort|$udpAudioListenPort"
        .toByteArray(Charsets.UTF_8)
    val localSet = _localIps.value.toSet()
    val prefixes = localSet.mapNotNull { ip -> ip.substringBeforeLast('.', "").takeIf { it.isNotEmpty() } }.distinct()
    for (prefix in prefixes) {
      for (hostId in 1..254) {
        val candidate = "$prefix.$hostId"
        if (candidate in localSet) continue
        try {
          val addr = InetAddress.getByName(candidate)
          sock.send(DatagramPacket(payload, payload.size, addr, DISCOVERY_PORT))
        } catch (_: Exception) {}
      }
    }
  }

  private fun startTcpServerIfNeeded() {
    if (serverJob?.isActive == true) return
    serverJob =
      scope.launch {
        try {
          val server =
            runCatching { ServerSocket(DEFAULT_TCP_PORT) }.getOrElse { ServerSocket(0) }
          tcpServerSocket = server
          tcpListenPort = server.localPort
          while (isActive && !server.isClosed) {
            val client = server.accept()
            launch { handleIncomingTcpConnection(client) }
          }
        } catch (e: Exception) {
          Log.w(TAG, "TCP server loop ended: ${e.message}")
        }
      }
  }

  private fun startUdpAudioSocketIfNeeded() {
    if (audioRecvJob?.isActive == true) return
    audioRecvJob =
      scope.launch {
        try {
          val sock =
            runCatching {
                DatagramSocket(null).apply {
                  reuseAddress = true
                  bind(InetSocketAddress(DEFAULT_UDP_AUDIO_PORT))
                }
              }
              .getOrElse { DatagramSocket(0) }
          udpAudioSocket = sock
          udpAudioListenPort = sock.localPort
          val buffer = ByteArray(1024)
          val packet = DatagramPacket(buffer, buffer.size)
          while (isActive && !sock.isClosed) {
            sock.receive(packet)
            val len = packet.length
            if (len > 12) {
              val payloadLen = len - 12
              val audioBytes = ByteArray(payloadLen)
              System.arraycopy(packet.data, 12, audioBytes, 0, payloadLen)
              audioEngine.writePlaybackFrame(audioBytes, payloadLen)
            }
          }
        } catch (e: Exception) {
          Log.w(TAG, "UDP audio receive ended: ${e.message}")
        }
      }
  }

  private fun startUdpDiscovery() {
    if (discoveryListenerJob?.isActive == true) return
    try {
      multicastLock =
        wifiManager?.createMulticastLock("FJRadioDiscoveryLock")?.apply {
          setReferenceCounted(false)
          acquire()
        }
    } catch (_: Exception) {}

    discoveryListenerJob =
      scope.launch {
        try {
          val sock =
            DatagramSocket(null).apply {
              reuseAddress = true
              broadcast = true
              bind(InetSocketAddress(DISCOVERY_PORT))
            }
          discoverySocket = sock
          val buf = ByteArray(1024)
          val packet = DatagramPacket(buf, buf.size)
          while (isActive && !sock.isClosed) {
            sock.receive(packet)
            val msg = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
            val senderIp = packet.address?.hostAddress ?: continue
            parseBeacon(msg, senderIp)
          }
        } catch (e: Exception) {
          Log.w(TAG, "UDP discovery listener stopped: ${e.message}")
        }
      }

    beaconBroadcastJob?.cancel()
    beaconBroadcastJob =
      scope.launch {
        while (isActive) {
          refreshNetworkIps()
          sendBeaconBurst()
          pruneStalePeers()
          delay(2000)
        }
      }
  }

  private fun sendBeaconBurst() {
    val sock = discoverySocket ?: return
    if (sock.isClosed) return
    val payload =
      "$BEACON_PREFIX|$localDeviceId|$localDisplayName|$tcpListenPort|$udpAudioListenPort"
        .toByteArray(Charsets.UTF_8)
    for (target in getBroadcastTargets()) {
      try {
        val packet = DatagramPacket(payload, payload.size, target, DISCOVERY_PORT)
        sock.send(packet)
      } catch (_: Exception) {}
    }
  }

  private fun parseBeacon(msg: String, senderIp: String) {
    val parts = msg.split("|")
    if (parts.size < 5 || parts[0] != BEACON_PREFIX) return
    val peerId = parts[1]
    if (peerId == localDeviceId || _localIps.value.contains(senderIp)) return
    val peerName = parts[2].ifBlank { senderIp }
    val tcpPort = parts[3].toIntOrNull() ?: DEFAULT_TCP_PORT
    val audioPort = parts[4].toIntOrNull() ?: DEFAULT_UDP_AUDIO_PORT

    val peer =
      PeerDevice(
        id = "wifi_$peerId",
        name = peerName,
        address = senderIp,
        tcpPort = tcpPort,
        udpAudioPort = audioPort,
        transportMode = TransportMode.HOTSPOT_WIFI,
        lastSeenMs = System.currentTimeMillis(),
      )
    discoveredMap[peer.id] = peer
    publishPeers()
  }

  private fun pruneStalePeers() {
    val now = System.currentTimeMillis()
    var changed = false
    val iterator = discoveredMap.entries.iterator()
    while (iterator.hasNext()) {
      val entry = iterator.next()
      if (now - entry.value.lastSeenMs > PEER_STALE_MS) {
        iterator.remove()
        changed = true
      }
    }
    if (changed) publishPeers()
  }

  private fun publishPeers() {
    _peers.value = discoveredMap.values.sortedBy { it.name.lowercase() }
  }

  private fun registerAndDiscoverNsd() {
    val mgr = nsdManager ?: return
    if (nsdRegListener != null) return
    try {
      val serviceInfo =
        NsdServiceInfo().apply {
          serviceName = "FJRadio-$localDeviceId"
          serviceType = NSD_SERVICE_TYPE
          port = tcpListenPort
        }
      val regListener =
        object : NsdManager.RegistrationListener {
          override fun onServiceRegistered(info: NsdServiceInfo) {}
          override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
          override fun onServiceUnregistered(info: NsdServiceInfo) {}
          override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
      nsdRegListener = regListener
      mgr.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, regListener)

      val discListener =
        object : NsdManager.DiscoveryListener {
          override fun onDiscoveryStarted(regType: String) {}
          override fun onServiceFound(service: NsdServiceInfo) {
            if (!service.serviceType.contains("fjradio")) return
            if (service.serviceName.endsWith(localDeviceId)) return
            @Suppress("DEPRECATION")
            mgr.resolveService(
              service,
              object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceResolved(info: NsdServiceInfo) {
                  @Suppress("DEPRECATION")
                  val hostIp = info.host?.hostAddress ?: return
                  if (_localIps.value.contains(hostIp)) return
                  val idSuffix = info.serviceName.removePrefix("FJRadio-")
                  val existing = discoveredMap["wifi_$idSuffix"]
                  if (existing == null) {
                    discoveredMap["wifi_$idSuffix"] =
                      PeerDevice(
                        id = "wifi_$idSuffix",
                        name = info.serviceName,
                        address = hostIp,
                        tcpPort = info.port,
                        udpAudioPort = DEFAULT_UDP_AUDIO_PORT,
                        transportMode = TransportMode.HOTSPOT_WIFI,
                        lastSeenMs = System.currentTimeMillis(),
                      )
                    publishPeers()
                  }
                }
              },
            )
          }

          override fun onServiceLost(service: NsdServiceInfo) {}
          override fun onDiscoveryStopped(serviceType: String) {}
          override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
          override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
      nsdDiscListener = discListener
      mgr.discoverServices(NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discListener)
    } catch (e: Exception) {
      Log.w(TAG, "NSD setup warning: ${e.message}")
    }
  }

  private fun sendControlLine(line: String) {
    synchronized(writerLock) {
      runCatching { activeWriter?.println(line) }
    }
  }

  suspend fun sendChatMessage(peer: PeerDevice, text: String): Boolean =
    withContext(Dispatchers.IO) {
      val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
      val payload = "CHAT|$localDeviceId|$localDisplayName|$encoded"

      // If currently in an active TCP call with this same peer, reuse the live socket
      if (
        activeTcpSocket?.isClosed == false &&
          activePeer?.address == peer.address
      ) {
        sendControlLine(payload)
        return@withContext true
      }

      // Otherwise open a quick reliable TCP socket for standalone offline chat
      var socket: Socket? = null
      return@withContext try {
        socket = Socket()
        socket.connect(
          InetSocketAddress(
            InetAddress.getByName(peer.address),
            peer.tcpPort.takeIf { it > 0 } ?: DEFAULT_TCP_PORT,
          ),
          3500,
        )
        socket.soTimeout = 3500
        val writer = PrintWriter(socket.getOutputStream(), true)
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        writer.println(payload)
        val ack = reader.readLine()
        ack?.startsWith("CHAT_ACK") == true
      } catch (e: Exception) {
        Log.w(TAG, "Offline Wi-Fi chat send failed to ${peer.address}: ${e.message}")
        false
      } finally {
        runCatching { socket?.close() }
      }
    }

  fun sendScreenShareStarted() {
    scope.launch { sendControlLine("SCREEN_START|$localDeviceId") }
  }

  fun sendScreenFrame(jpegBytes: ByteArray) {
    if (activeTcpSocket?.isClosed != false) return
    val encoded = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
    sendControlLine("SCREEN_FRAME|$encoded")
  }

  fun sendScreenShareStopped() {
    scope.launch { sendControlLine("SCREEN_STOP|$localDeviceId") }
  }

  fun initiateCall(peer: PeerDevice) {
    scope.launch {
      try {
        val targetAddr = InetAddress.getByName(peer.address)
        val socket = Socket()
        socket.connect(
          InetSocketAddress(targetAddr, peer.tcpPort.takeIf { it > 0 } ?: DEFAULT_TCP_PORT),
          4500,
        )
        val writer = PrintWriter(socket.getOutputStream(), true)
        activeTcpSocket = socket
        activeWriter = writer
        activePeer = peer
        activePeerAddress = targetAddr
        activePeerAudioPort =
          peer.udpAudioPort.takeIf { it > 0 } ?: DEFAULT_UDP_AUDIO_PORT

        sendControlLine("INVITE|$localDeviceId|$localDisplayName|$udpAudioListenPort")
        listenToControlSocket(socket, isCaller = true, initialPeer = peer)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to initiate Wi-Fi call to ${peer.address}", e)
        cleanupCallState()
        callbacks.onCallEnded(peer, TransportMode.HOTSPOT_WIFI, CallEndReason.UNREACHABLE)
      }
    }
  }

  fun acceptIncomingCall(peer: PeerDevice) {
    scope.launch {
      sendControlLine("ACCEPT|$localDeviceId|$localDisplayName|$udpAudioListenPort")
      startVoiceAndPing(peer)
    }
  }

  fun rejectIncomingCall(peer: PeerDevice) {
    scope.launch {
      sendControlLine("REJECT|$localDeviceId")
      cleanupCallState()
      callbacks.onCallEnded(peer, TransportMode.HOTSPOT_WIFI, CallEndReason.REJECTED)
    }
  }

  fun endActiveCall(reason: CallEndReason = CallEndReason.COMPLETED) {
    val peer = activePeer
    scope.launch {
      sendControlLine("HANGUP|$localDeviceId")
      cleanupCallState()
      if (peer != null) {
        callbacks.onCallEnded(peer, TransportMode.HOTSPOT_WIFI, reason)
      }
    }
  }

  private fun handleIncomingTcpConnection(socket: Socket) {
    scope.launch {
      try {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val firstLine =
          reader.readLine()
            ?: run {
              socket.close()
              return@launch
            }
        val parts = firstLine.split("|", limit = 4)
        val remoteIp = socket.inetAddress.hostAddress ?: "0.0.0.0"

        // Handle standalone offline chat message without starting a call
        if (parts.firstOrNull() == "CHAT" && parts.size >= 4) {
          val senderId = parts[1]
          val senderName = parts[2].ifBlank { remoteIp }
          val decoded =
            runCatching {
              String(Base64.decode(parts[3], Base64.NO_WRAP), Charsets.UTF_8)
            }
              .getOrDefault("")
          val senderPeer =
            PeerDevice(
              id = "wifi_$senderId",
              name = senderName,
              address = remoteIp,
              tcpPort = DEFAULT_TCP_PORT,
              udpAudioPort = DEFAULT_UDP_AUDIO_PORT,
              transportMode = TransportMode.HOTSPOT_WIFI,
            )
          PrintWriter(socket.getOutputStream(), true).println("CHAT_ACK|$localDeviceId")
          socket.close()
          if (decoded.isNotEmpty()) {
            callbacks.onChatMessageReceived(senderPeer, TransportMode.HOTSPOT_WIFI, decoded)
          }
          return@launch
        }

        if (parts.size < 4 || parts[0] != "INVITE") {
          socket.close()
          return@launch
        }
        val callerId = parts[1]
        val callerName = parts[2]
        val callerAudioPort = parts[3].toIntOrNull() ?: DEFAULT_UDP_AUDIO_PORT

        val peer =
          PeerDevice(
            id = "wifi_$callerId",
            name = callerName,
            address = remoteIp,
            tcpPort = DEFAULT_TCP_PORT,
            udpAudioPort = callerAudioPort,
            transportMode = TransportMode.HOTSPOT_WIFI,
          )

        if (activeTcpSocket != null && activeTcpSocket?.isClosed == false) {
          PrintWriter(socket.getOutputStream(), true).println("BUSY|$localDeviceId")
          socket.close()
          return@launch
        }

        val writer = PrintWriter(socket.getOutputStream(), true)
        activeTcpSocket = socket
        activeWriter = writer
        activePeer = peer
        activePeerAddress = socket.inetAddress
        activePeerAudioPort = callerAudioPort

        sendControlLine("RINGING|$localDeviceId|$localDisplayName")
        callbacks.onIncomingCall(peer, TransportMode.HOTSPOT_WIFI)
        listenToControlSocket(socket, isCaller = false, initialPeer = peer, existingReader = reader)
      } catch (e: Exception) {
        Log.w(TAG, "Incoming TCP connection error: ${e.message}")
      }
    }
  }

  private fun listenToControlSocket(
    socket: Socket,
    isCaller: Boolean,
    initialPeer: PeerDevice,
    existingReader: BufferedReader? = null,
  ) {
    var currentPeer = initialPeer
    try {
      val reader = existingReader ?: BufferedReader(InputStreamReader(socket.getInputStream()))
      while (!socket.isClosed) {
        val line = reader.readLine() ?: break
        val parts = line.split("|", limit = 4)
        when (parts.firstOrNull()) {
          "RINGING" -> {
            if (isCaller) {
              val remoteName = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
              if (remoteName != null) {
                currentPeer = currentPeer.copy(name = remoteName)
                activePeer = currentPeer
              }
              callbacks.onPeerRinging(currentPeer, TransportMode.HOTSPOT_WIFI)
            }
          }
          "ACCEPT" -> {
            if (isCaller) {
              val calleeName = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: currentPeer.name
              val calleeAudioPort =
                parts.getOrNull(3)?.toIntOrNull() ?: activePeerAudioPort
              activePeerAudioPort = calleeAudioPort
              currentPeer = currentPeer.copy(name = calleeName, udpAudioPort = calleeAudioPort)
              activePeer = currentPeer
              startVoiceAndPing(currentPeer)
            }
          }
          "CHAT" -> {
            if (parts.size >= 4) {
              val decoded =
                runCatching {
                  String(Base64.decode(parts[3], Base64.NO_WRAP), Charsets.UTF_8)
                }
                  .getOrDefault("")
              if (decoded.isNotEmpty()) {
                callbacks.onChatMessageReceived(currentPeer, TransportMode.HOTSPOT_WIFI, decoded)
              }
            }
          }
          "SCREEN_START" -> callbacks.onRemoteScreenShareStarted(currentPeer)
          "SCREEN_FRAME" -> {
            val b64 = parts.getOrNull(1)
            if (!b64.isNullOrEmpty()) {
              runCatching { Base64.decode(b64, Base64.NO_WRAP) }
                .getOrNull()
                ?.let { callbacks.onRemoteScreenFrameReceived(it) }
            }
          }
          "SCREEN_STOP" -> callbacks.onRemoteScreenShareStopped()
          "REJECT" -> {
            cleanupCallState()
            callbacks.onCallEnded(currentPeer, TransportMode.HOTSPOT_WIFI, CallEndReason.REJECTED)
            return
          }
          "BUSY" -> {
            cleanupCallState()
            callbacks.onCallEnded(currentPeer, TransportMode.HOTSPOT_WIFI, CallEndReason.BUSY)
            return
          }
          "HANGUP" -> {
            cleanupCallState()
            callbacks.onCallEnded(currentPeer, TransportMode.HOTSPOT_WIFI, CallEndReason.COMPLETED)
            return
          }
          "PING" -> {
            val ts = parts.getOrNull(1) ?: "0"
            sendControlLine("PONG|$ts")
          }
          "PONG" -> {
            val sentTs = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            if (sentTs > 0L) {
              val rtt = (System.currentTimeMillis() - sentTs).toInt().coerceIn(1, 999)
              callbacks.onLatencyUpdated(rtt)
            }
          }
        }
      }
      if (activeTcpSocket === socket) {
        cleanupCallState()
        callbacks.onCallEnded(currentPeer, TransportMode.HOTSPOT_WIFI, CallEndReason.COMPLETED)
      }
    } catch (_: Exception) {
      if (activeTcpSocket === socket) {
        cleanupCallState()
        callbacks.onCallEnded(currentPeer, TransportMode.HOTSPOT_WIFI, CallEndReason.COMPLETED)
      }
    }
  }

  private fun startVoiceAndPing(peer: PeerDevice) {
    callbacks.onCallConnected(peer, TransportMode.HOTSPOT_WIFI)
    audioSequence = 0
    audioEngine.startVoiceSession(speakerDefault = false) { muLawFrame ->
      sendUdpAudioPacket(muLawFrame)
    }
    pingJob?.cancel()
    pingJob =
      scope.launch {
        while (isActive && activeTcpSocket?.isClosed == false) {
          sendControlLine("PING|${System.currentTimeMillis()}")
          delay(2500)
        }
      }
  }

  private fun sendUdpAudioPacket(muLawFrame: ByteArray) {
    val sock = udpAudioSocket ?: return
    val addr = activePeerAddress ?: return
    val port = activePeerAudioPort
    if (port <= 0 || sock.isClosed) return

    val packetBytes = ByteArray(12 + muLawFrame.size)
    val bb = ByteBuffer.wrap(packetBytes)
    bb.putInt(audioSequence++)
    bb.putLong(System.currentTimeMillis())
    bb.put(muLawFrame)

    try {
      sock.send(DatagramPacket(packetBytes, packetBytes.size, addr, port))
    } catch (_: Exception) {}
  }

  private fun cleanupCallState() {
    pingJob?.cancel()
    pingJob = null
    audioEngine.stopVoiceSession()
    audioEngine.stopTones()
    runCatching { activeTcpSocket?.close() }
    activeTcpSocket = null
    activeWriter = null
    activePeer = null
    activePeerAddress = null
    activePeerAudioPort = 0
  }

  fun shutdown() {
    cleanupCallState()
    discoveryListenerJob?.cancel()
    beaconBroadcastJob?.cancel()
    serverJob?.cancel()
    audioRecvJob?.cancel()
    runCatching { discoverySocket?.close() }
    runCatching { tcpServerSocket?.close() }
    runCatching { udpAudioSocket?.close() }
    runCatching { multicastLock?.release() }
    nsdRegListener?.let { runCatching { nsdManager?.unregisterService(it) } }
    nsdDiscListener?.let { runCatching { nsdManager?.stopServiceDiscovery(it) } }
  }
}

interface CallTransportCallbacks {
  fun onIncomingCall(peer: PeerDevice, transport: TransportMode)
  fun onPeerRinging(peer: PeerDevice, transport: TransportMode)
  fun onCallConnected(peer: PeerDevice, transport: TransportMode)
  fun onCallEnded(peer: PeerDevice, transport: TransportMode, reason: CallEndReason)
  fun onLatencyUpdated(latencyMs: Int)
  fun onChatMessageReceived(peer: PeerDevice, transport: TransportMode, text: String)
  fun onRemoteScreenShareStarted(peer: PeerDevice)
  fun onRemoteScreenFrameReceived(jpegBytes: ByteArray)
  fun onRemoteScreenShareStopped()
}
