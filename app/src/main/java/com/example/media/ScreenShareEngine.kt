package com.example.media

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.example.data.model.TransportMode
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ScreenShareEngine(private val context: Context) {

  companion object {
    private const val TAG = "ScreenShareEngine"
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val projectionManager =
    context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager

  private val _isSharingLocal = MutableStateFlow(false)
  val isSharingLocal: StateFlow<Boolean> = _isSharingLocal.asStateFlow()

  private val _remoteScreenBitmap = MutableStateFlow<Bitmap?>(null)
  val remoteScreenBitmap: StateFlow<Bitmap?> = _remoteScreenBitmap.asStateFlow()

  private var mediaProjection: MediaProjection? = null
  private var virtualDisplay: VirtualDisplay? = null
  private var imageReader: ImageReader? = null
  private var captureThread: HandlerThread? = null
  private var captureHandler: Handler? = null

  @Volatile private var lastSentFrameMs = 0L
  @Volatile private var onStoppedBySystemCallback: (() -> Unit)? = null

  fun createCaptureIntent(): Intent? = projectionManager?.createScreenCaptureIntent()

  @SuppressLint("WrongConstant")
  fun startLocalCapture(
    resultCode: Int,
    data: Intent,
    transportMode: TransportMode,
    onEncodedJpegFrame: (ByteArray) -> Unit,
    onStoppedBySystem: () -> Unit,
  ): Boolean {
    stopLocalCapture(notifyCallback = false)

    val mgr = projectionManager ?: return false
    val projection =
      try {
        mgr.getMediaProjection(resultCode, data)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to obtain MediaProjection", e)
        return false
      }
        ?: return false

    onStoppedBySystemCallback = onStoppedBySystem
    mediaProjection = projection

    val thread = HandlerThread("FJRadioScreenCapture").apply { start() }
    captureThread = thread
    val handler = Handler(thread.looper)
    captureHandler = handler

    val (rawW, rawH, densityDpi) = queryDisplayBounds()
    val maxDim = if (transportMode == TransportMode.HOTSPOT_WIFI) 540 else 320
    val minIntervalMs = if (transportMode == TransportMode.HOTSPOT_WIFI) 200L else 420L
    val jpegQuality = if (transportMode == TransportMode.HOTSPOT_WIFI) 52 else 38

    val (capW, capH) = scaleDimensions(rawW, rawH, maxDim)

    projection.registerCallback(
      object : MediaProjection.Callback() {
        override fun onStop() {
          val cb = onStoppedBySystemCallback
          stopLocalCapture(notifyCallback = false)
          cb?.invoke()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
          if (width <= 0 || height <= 0) return
          val (newW, newH) = scaleDimensions(width, height, maxDim)
          recreateReaderAndResizeDisplay(
            newW,
            newH,
            densityDpi,
            minIntervalMs,
            jpegQuality,
            handler,
            onEncodedJpegFrame,
          )
        }
      },
      handler,
    )

    val reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
    imageReader = reader
    attachReaderListener(reader, capW, capH, minIntervalMs, jpegQuality, handler, onEncodedJpegFrame)

    try {
      virtualDisplay =
        projection.createVirtualDisplay(
          "FJRadioScreenShare",
          capW,
          capH,
          densityDpi,
          DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
          reader.surface,
          null,
          handler,
        )
      _isSharingLocal.value = true
      return true
    } catch (e: Exception) {
      Log.e(TAG, "createVirtualDisplay failed", e)
      stopLocalCapture(notifyCallback = false)
      return false
    }
  }

  @SuppressLint("WrongConstant")
  private fun recreateReaderAndResizeDisplay(
    width: Int,
    height: Int,
    dpi: Int,
    minIntervalMs: Long,
    jpegQuality: Int,
    handler: Handler,
    onEncodedJpegFrame: (ByteArray) -> Unit,
  ) {
    try {
      val oldReader = imageReader
      val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
      imageReader = newReader
      attachReaderListener(
        newReader,
        width,
        height,
        minIntervalMs,
        jpegQuality,
        handler,
        onEncodedJpegFrame,
      )
      virtualDisplay?.resize(width, height, dpi)
      virtualDisplay?.surface = newReader.surface
      oldReader?.close()
    } catch (e: Exception) {
      Log.w(TAG, "Resize virtual display warning: ${e.message}")
    }
  }

  private fun attachReaderListener(
    reader: ImageReader,
    width: Int,
    height: Int,
    minIntervalMs: Long,
    jpegQuality: Int,
    handler: Handler,
    onEncodedJpegFrame: (ByteArray) -> Unit,
  ) {
    reader.setOnImageAvailableListener(
      { imgReader ->
        val image =
          try {
            imgReader.acquireLatestImage()
          } catch (_: Exception) {
            null
          }
            ?: return@setOnImageAvailableListener

        try {
          val now = System.currentTimeMillis()
          if (now - lastSentFrameMs < minIntervalMs) {
            return@setOnImageAvailableListener
          }
          lastSentFrameMs = now

          val plane = image.planes.firstOrNull() ?: return@setOnImageAvailableListener
          val buffer = plane.buffer
          val pixelStride = plane.pixelStride
          val rowStride = plane.rowStride
          val rowPadding = rowStride - pixelStride * width
          val paddedBitmap =
            Bitmap.createBitmap(
              width + rowPadding / pixelStride,
              height,
              Bitmap.Config.ARGB_8888,
            )
          paddedBitmap.copyPixelsFromBuffer(buffer)
          val cropped =
            if (rowPadding > 0) {
              Bitmap.createBitmap(paddedBitmap, 0, 0, width, height).also {
                paddedBitmap.recycle()
              }
            } else {
              paddedBitmap
            }

          val baos = ByteArrayOutputStream()
          cropped.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos)
          cropped.recycle()
          val bytes = baos.toByteArray()
          if (bytes.isNotEmpty()) {
            onEncodedJpegFrame(bytes)
          }
        } catch (e: Exception) {
          Log.w(TAG, "Frame encode warning: ${e.message}")
        } finally {
          image.close()
        }
      },
      handler,
    )
  }

  fun stopLocalCapture(notifyCallback: Boolean = false) {
    val cb = if (notifyCallback) onStoppedBySystemCallback else null
    onStoppedBySystemCallback = null
    _isSharingLocal.value = false

    runCatching { virtualDisplay?.release() }
    virtualDisplay = null

    runCatching { imageReader?.close() }
    imageReader = null

    runCatching { mediaProjection?.stop() }
    mediaProjection = null

    runCatching { captureThread?.quitSafely() }
    captureThread = null
    captureHandler = null

    cb?.invoke()
  }

  fun decodeRemoteFrame(jpegBytes: ByteArray) {
    if (jpegBytes.isEmpty()) return
    scope.launch {
      try {
        val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        if (bmp != null) {
          _remoteScreenBitmap.value = bmp
        }
      } catch (_: Exception) {}
    }
  }

  fun clearRemoteScreen() {
    _remoteScreenBitmap.value = null
  }

  private fun queryDisplayBounds(): Triple<Int, Int, Int> {
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val dpi = context.resources.displayMetrics.densityDpi.coerceAtLeast(160)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val bounds = wm.maximumWindowMetrics.bounds
      Triple(bounds.width().coerceAtLeast(360), bounds.height().coerceAtLeast(640), dpi)
    } else {
      val metrics = DisplayMetrics()
      @Suppress("DEPRECATION") wm.defaultDisplay?.getMetrics(metrics)
      Triple(
        metrics.widthPixels.coerceAtLeast(360),
        metrics.heightPixels.coerceAtLeast(640),
        metrics.densityDpi.coerceAtLeast(160),
      )
    }
  }

  private fun scaleDimensions(w: Int, h: Int, maxDim: Int): Pair<Int, Int> {
    val maxSide = maxOf(w, h).coerceAtLeast(1)
    val scale = if (maxSide > maxDim) maxDim.toFloat() / maxSide else 1f
    val outW = ((w * scale).toInt() and 1.inv()).coerceAtLeast(160)
    val outH = ((h * scale).toInt() and 1.inv()).coerceAtLeast(160)
    return outW to outH
  }
}
