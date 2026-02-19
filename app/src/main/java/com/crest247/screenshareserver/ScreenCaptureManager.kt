package com.crest247.screenshareserver

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureManager(
    private val context: Context,
    private val onLog: (String) -> Unit,
    private val onShutdown: () -> Unit
) {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null

    private var udpSocket: DatagramSocket? = null
    private var clientAddress: InetAddress? = null
    private var clientPort: Int = -1

    private val isRunning = AtomicBoolean(false)
    private val isSenderRunning = AtomicBoolean(false)
    private val TAG = "ScreenCaptureManager"

    private var width = 0
    private var height = 0
    private var density = 0
    
    private var configData: ByteArray? = null
    private var frameIndex: Short = 0
    
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                // Delay slightly to allow system to update display metrics
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    handleRotation()
                }, 500)
            }
        }
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        onLog(msg)
    }

    fun start(resultCode: Int, data: Intent) {
        if (isRunning.get()) return
        isRunning.set(true)

        try {
            updateDimensions()
            log("Initial Screen size: $width x $height")

            val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, data)

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    log("MediaProjection Stopped")
                    stop()
                }
            }, null)

            displayManager.registerDisplayListener(displayListener, null)

            startServer()
            startEncoder()
            startVirtualDisplay()
            
        } catch (e: Exception) {
            log("Manager Start Error: ${e.message}")
        }
    }

    private fun updateDimensions() {
        val metrics = DisplayMetrics()
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = windowManager.defaultDisplay
        display.getRealMetrics(metrics)
        
        var rawWidth = metrics.widthPixels
        var rawHeight = metrics.heightPixels

        // Downscale logic
        val maxDimension = 720
        var scaleFactor = 1.0f
        if (rawWidth > maxDimension || rawHeight > maxDimension) {
            scaleFactor = if (rawWidth > rawHeight) {
                maxDimension.toFloat() / rawWidth
            } else {
                maxDimension.toFloat() / rawHeight
            }
        }
        
        width = ((rawWidth * scaleFactor).toInt() / 16) * 16
        height = ((rawHeight * scaleFactor).toInt() / 16) * 16
        if (width == 0) width = 16
        if (height == 0) height = 16
        density = (metrics.densityDpi * scaleFactor).toInt()
    }

    private fun handleRotation() {
        val oldWidth = width
        val oldHeight = height
        updateDimensions()
        
        if (oldWidth != width || oldHeight != height) {
            log("Rotation detected: $width x $height. Updating stream...")
            
            stopEncoder()
            startEncoder()
            updateVirtualDisplay()
            sendMetaData()
        }
    }

    private fun updateVirtualDisplay() {
        try {
            if (virtualDisplay == null) {
                startVirtualDisplay()
            } else {
                log("Resizing VirtualDisplay to ${width}x${height}")
                virtualDisplay?.resize(width, height, density)
                virtualDisplay?.surface = inputSurface
            }
        } catch (e: Exception) {
            log("VirtualDisplay Update Error: ${e.message}")
        }
    }

    private fun stopEncoder() {
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {}
        mediaCodec = null
        inputSurface = null
        configData = null
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        
        log("Stopping ScreenCaptureManager...")
        try {
            displayManager.unregisterDisplayListener(displayListener)
            virtualDisplay?.release()
            stopEncoder()
            mediaProjection?.stop()
            udpSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            virtualDisplay = null
            mediaProjection = null
            udpSocket = null
            isSenderRunning.set(false)
            onShutdown()
        }
    }

    private fun startServer() {
        Thread {
            try {
                udpSocket = DatagramSocket(8888)
                udpSocket?.reuseAddress = true
                val receiveBuffer = ByteArray(1024)
                val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)

                while (isRunning.get()) {
                    udpSocket?.receive(packet)
                    clientAddress = packet.address
                    clientPort = packet.port

                    sendMetaData()
                    
                    if (!isSenderRunning.getAndSet(true)) {
                        startNetworkSender()
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) log("Server Error: ${e.message}")
            }
        }.start()
    }

    private fun sendMetaData() {
        val header = ByteBuffer.allocate(10)
        header.put(0.toByte()) 
        header.putInt(width)
        header.putInt(height)
        sendUdpData(header.array())
        
        configData?.let {
            val configPacket = ByteArray(it.size + 1)
            configPacket[0] = 2.toByte()
            System.arraycopy(it, 0, configPacket, 1, it.size)
            sendUdpData(configPacket)
        }
    }

    private fun startEncoder() {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 1000000)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = mediaCodec?.createInputSurface()
            mediaCodec?.start()
            
            Thread { recordScreen() }.start()
        } catch (e: Exception) {
            log("Encoder Error: ${e.message}")
        }
    }

    private fun startVirtualDisplay() {
        if (mediaProjection != null && inputSurface != null) {
            virtualDisplay = mediaProjection!!.createVirtualDisplay(
                "ScreenCapture", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, null
            )
        }
    }

    private val frameQueue = java.util.concurrent.ArrayBlockingQueue<ByteArray>(2)

    private fun startNetworkSender() {
        Thread {
            val MTU = 1300
            while (isRunning.get()) {
                try {
                    val data = frameQueue.take()
                    val totalParts = ((data.size + MTU - 1) / MTU)
                    for (i in 0 until totalParts) {
                        val offset = i * MTU
                        val length = minOf(MTU, data.size - offset)
                        val packetData = ByteArray(length + 5)
                        val buffer = ByteBuffer.wrap(packetData)
                        buffer.put(1.toByte())
                        buffer.putShort(frameIndex)
                        buffer.put(i.toByte())
                        buffer.put(totalParts.toByte())
                        System.arraycopy(data, offset, packetData, 5, length)
                        sendUdpData(packetData)
                    }
                    frameIndex++
                } catch (e: Exception) {
                    if (isRunning.get()) log("UDP Send Loop Error: ${e.message}")
                }
            }
        }.start()
    }

    private fun sendUdpData(data: ByteArray) {
        val socket = udpSocket
        val addr = clientAddress
        if (socket != null && addr != null && clientPort != -1) {
            try {
                val packet = DatagramPacket(data, data.size, addr, clientPort)
                socket.send(packet)
            } catch (e: Exception) {}
        }
    }

    private fun recordScreen() {
        val bufferInfo = MediaCodec.BufferInfo()
        val currentCodec = mediaCodec
        while (isRunning.get() && mediaCodec == currentCodec && currentCodec != null) {
            try {
                val outputBufferIndex = currentCodec.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputBufferIndex >= 0) {
                    val outputBuffer = currentCodec.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null) {
                        val data = ByteArray(bufferInfo.size)
                        outputBuffer.get(data)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            configData = data
                        }
                        if (!frameQueue.offer(data)) {
                            frameQueue.poll()
                            frameQueue.offer(data)
                        }
                    }
                    currentCodec.releaseOutputBuffer(outputBufferIndex, false)
                }
            } catch (e: Exception) { break }
        }
    }
}
