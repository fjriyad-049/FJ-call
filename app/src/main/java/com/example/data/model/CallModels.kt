package com.example.data.model

enum class TransportMode {
  HOTSPOT_WIFI,
  BLUETOOTH,
}

enum class TransportFilter {
  ALL,
  HOTSPOT_WIFI,
  BLUETOOTH,
}

enum class AppLanguage {
  BENGALI,
  ENGLISH,
}

enum class CallDirection {
  OUTGOING,
  INCOMING,
  MISSED,
}

data class PeerDevice(
  val id: String,
  val name: String,
  val address: String,
  val tcpPort: Int = 0,
  val udpAudioPort: Int = 0,
  val transportMode: TransportMode,
  val isPaired: Boolean = false,
  val isDirectManual: Boolean = false,
  val lastSeenMs: Long = System.currentTimeMillis(),
)

data class ChatThreadSummary(
  val peerId: String,
  val peerName: String,
  val peerAddress: String,
  val transportMode: TransportMode,
  val lastMessage: String,
  val lastTimestampMs: Long,
  val isOnlineNow: Boolean,
)

sealed interface CallState {
  data object Idle : CallState

  data class OutgoingRinging(
    val peer: PeerDevice,
    val transport: TransportMode,
    val isPeerRinging: Boolean = false,
  ) : CallState

  data class IncomingRinging(
    val peer: PeerDevice,
    val transport: TransportMode,
  ) : CallState

  data class Active(
    val peer: PeerDevice,
    val transport: TransportMode,
    val durationSeconds: Long = 0L,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val latencyMs: Int? = null,
    val localAudioLevel: Float = 0f,
    val remoteAudioLevel: Float = 0f,
    val isSharingMyScreen: Boolean = false,
    val isRemoteSharingScreen: Boolean = false,
  ) : CallState

  data class Ended(
    val peer: PeerDevice,
    val transport: TransportMode,
    val reason: CallEndReason,
    val durationSeconds: Long = 0L,
  ) : CallState
}

enum class CallEndReason {
  COMPLETED,
  REJECTED,
  BUSY,
  UNREACHABLE,
  CANCELLED,
}

data class NetworkStatus(
  val localIpAddresses: List<String> = emptyList(),
  val primaryIp: String? = null,
  val wifiEnabled: Boolean = false,
  val hotspotOrLanActive: Boolean = false,
  val wifiNetworkLabel: String? = null,
  val bluetoothSupported: Boolean = true,
  val bluetoothEnabled: Boolean = false,
  val bluetoothName: String? = null,
  val isBluetoothDiscoverable: Boolean = false,
  val isScanningWifi: Boolean = false,
  val isScanningBluetooth: Boolean = false,
)

data class PermissionStatus(
  val microphoneGranted: Boolean = false,
  val bluetoothGranted: Boolean = false,
  val nearbyWifiOrLocationGranted: Boolean = false,
  val notificationsGranted: Boolean = false,
) {
  val allCoreGranted: Boolean
    get() = microphoneGranted && bluetoothGranted && nearbyWifiOrLocationGranted
}
