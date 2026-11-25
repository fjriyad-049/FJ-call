package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CompletableDeferred

class CallForegroundService : Service() {

  companion object {
    const val CHANNEL_ID = "fjradio_active_call"
    const val NOTIFICATION_ID = 4501
    const val ACTION_START = "com.example.service.ACTION_START"
    const val ACTION_UPGRADE_SCREEN_SHARE = "com.example.service.ACTION_UPGRADE_SCREEN_SHARE"
    const val ACTION_DOWNGRADE_TO_CALL_ONLY = "com.example.service.ACTION_DOWNGRADE_CALL_ONLY"
    const val ACTION_STOP = "com.example.service.ACTION_STOP"
    const val ACTION_HANGUP_FROM_NOTIFICATION = "com.example.service.ACTION_HANGUP"
    const val EXTRA_PEER_NAME = "extra_peer_name"
    const val EXTRA_TRANSPORT_LABEL = "extra_transport_label"

    @Volatile var onNotificationHangup: (() -> Unit)? = null
    @Volatile private var pendingMediaProjectionReady: CompletableDeferred<Boolean>? = null

    fun start(context: Context, peerName: String, transportLabel: String) {
      val hasMic =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
          PackageManager.PERMISSION_GRANTED
      if (!hasMic) return
      val intent =
        Intent(context, CallForegroundService::class.java).apply {
          action = ACTION_START
          putExtra(EXTRA_PEER_NAME, peerName)
          putExtra(EXTRA_TRANSPORT_LABEL, transportLabel)
        }
      startServiceCompat(context, intent)
    }

    suspend fun ensureMediaProjectionForeground(
      context: Context,
      peerName: String,
      transportLabel: String,
    ): Boolean {
      val deferred = CompletableDeferred<Boolean>()
      pendingMediaProjectionReady = deferred
      val intent =
        Intent(context, CallForegroundService::class.java).apply {
          action = ACTION_UPGRADE_SCREEN_SHARE
          putExtra(EXTRA_PEER_NAME, peerName)
          putExtra(EXTRA_TRANSPORT_LABEL, transportLabel)
        }
      startServiceCompat(context, intent)
      return runCatching { deferred.await() }.getOrDefault(false)
    }

    fun downgradeFromScreenShare(context: Context, peerName: String, transportLabel: String) {
      val intent =
        Intent(context, CallForegroundService::class.java).apply {
          action = ACTION_DOWNGRADE_TO_CALL_ONLY
          putExtra(EXTRA_PEER_NAME, peerName)
          putExtra(EXTRA_TRANSPORT_LABEL, transportLabel)
        }
      runCatching { context.startService(intent) }
    }

    fun stop(context: Context) {
      val intent =
        Intent(context, CallForegroundService::class.java).apply { action = ACTION_STOP }
      runCatching { context.startService(intent) }
    }

    private fun startServiceCompat(context: Context, intent: Intent) {
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          context.startForegroundService(intent)
        } else {
          context.startService(intent)
        }
      } catch (_: Exception) {
        pendingMediaProjectionReady?.complete(false)
        pendingMediaProjectionReady = null
      }
    }
  }

  private var wakeLock: PowerManager.WakeLock? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_HANGUP_FROM_NOTIFICATION -> {
        onNotificationHangup?.invoke()
        stopSelfCleanly()
      }
      ACTION_STOP -> {
        stopSelfCleanly()
      }
      ACTION_START,
      ACTION_DOWNGRADE_TO_CALL_ONLY -> {
        val peerName = intent.getStringExtra(EXTRA_PEER_NAME) ?: "FJ Radio"
        val transportLabel = intent.getStringExtra(EXTRA_TRANSPORT_LABEL) ?: "Offline Voice"
        ensureChannel()
        val notification = buildNotification(peerName, transportLabel)
        acquireWakeLock()
        try {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
              NOTIFICATION_ID,
              notification,
              ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
          } else {
            startForeground(NOTIFICATION_ID, notification)
          }
        } catch (_: Exception) {
          stopSelf()
        }
      }
      ACTION_UPGRADE_SCREEN_SHARE -> {
        val peerName = intent.getStringExtra(EXTRA_PEER_NAME) ?: "FJ Radio"
        val transportLabel =
          intent.getStringExtra(EXTRA_TRANSPORT_LABEL) ?: "Offline Call + Screen Share"
        ensureChannel()
        val notification = buildNotification(peerName, "$transportLabel • Screen Share")
        acquireWakeLock()
        val success =
          try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
              val hasMic =
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                  PackageManager.PERMISSION_GRANTED
              val fgType =
                if (hasMic) {
                  ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                  ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
              startForeground(NOTIFICATION_ID, notification, fgType)
            } else {
              startForeground(NOTIFICATION_ID, notification)
            }
            true
          } catch (_: Exception) {
            false
          }
        pendingMediaProjectionReady?.complete(success)
        pendingMediaProjectionReady = null
      }
    }
    return START_NOT_STICKY
  }

  private fun ensureChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val manager = getSystemService(NotificationManager::class.java)
      val channel =
        NotificationChannel(
            CHANNEL_ID,
            "FJ Radio Offline Call",
            NotificationManager.IMPORTANCE_LOW,
          )
          .apply { description = "Keeps offline Bluetooth and Hotspot calls and screen share active" }
      manager.createNotificationChannel(channel)
    }
  }

  private fun buildNotification(peerName: String, transportLabel: String): Notification {
    val openAppIntent =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val hangupIntent =
      PendingIntent.getService(
        this,
        1,
        Intent(this, CallForegroundService::class.java).apply {
          action = ACTION_HANGUP_FROM_NOTIFICATION
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_launcher_foreground)
      .setContentTitle(peerName)
      .setContentText(transportLabel)
      .setOngoing(true)
      .setCategory(NotificationCompat.CATEGORY_CALL)
      .setContentIntent(openAppIntent)
      .addAction(0, "End Call", hangupIntent)
      .build()
  }

  private fun acquireWakeLock() {
    if (wakeLock?.isHeld == true) return
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock =
      pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FJRadio::CallWakeLock").apply {
        acquire(60 * 60 * 1000L)
      }
  }

  private fun stopSelfCleanly() {
    pendingMediaProjectionReady?.complete(false)
    pendingMediaProjectionReady = null
    runCatching {
      if (wakeLock?.isHeld == true) wakeLock?.release()
    }
    wakeLock = null
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  override fun onDestroy() {
    pendingMediaProjectionReady?.complete(false)
    pendingMediaProjectionReady = null
    runCatching {
      if (wakeLock?.isHeld == true) wakeLock?.release()
    }
    super.onDestroy()
  }
}
