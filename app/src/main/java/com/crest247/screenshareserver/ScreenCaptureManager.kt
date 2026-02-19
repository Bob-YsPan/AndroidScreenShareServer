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
import android.view.Surface
import android.view.WindowManager
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
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

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var outputStream: OutputStream? = null

    private val isRunning = AtomicBoolean(false)
    private val TAG = "ScreenCaptureManager"

    private var width = 0
    private var height = 0
    private var density = 0
    
    private var configData: ByteArray? = null

    private fun log(msg: String) {
        Log.d(TAG, msg)
        onLog(msg)
    }

    fun start(resultCode: Int, data: Intent) {
        if (isRunning.get()) return
        isRunning.set(true)

        try {
            val metrics = DisplayMetrics()
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager.defaultDisplay.getRealMetrics(metrics)
            
            // Align dimensions to 16 to avoid stride issues on older decoders (Android 7)
            // Also cap resolution to improve performance/latency
            var rawWidth = metrics.widthPixels
            var rawHeight = metrics.heightPixels

            // Downscale to max 720p to improve latency on slow networks
            val maxDimension = 720
            var scaleFactor = 1.0f
            if (rawWidth > maxDimension || rawHeight > maxDimension) {
                scaleFactor = if (rawWidth > rawHeight) {
                    maxDimension.toFloat() / rawWidth
                } else {
                    maxDimension.toFloat() / rawHeight
                }
            }
            
            // Align dimensions to 16
            width = ((rawWidth * scaleFactor).toInt() / 16) * 16
            height = ((rawHeight * scaleFactor).toInt() / 16) * 16
            
            if (width == 0) width = 16
            if (height == 0) height = 16
            
            density = (metrics.densityDpi * scaleFactor).toInt() // Scale density too for correct UI scaling? Or keep original?
            // Usually valid density is needed for virtual display, but scaling effective density might be better. 
            // Let's keep original density or scale it? 
            // If we downscale resolution but keep high density, UI might look huge.
            // Let's just use the metrics density for now, VirtualDisplay usually handles it.
            density = metrics.densityDpi 

            val msg = "Screen size: $width x $height (Scaled from $rawWidth x $rawHeight), Density: $density"
            log(msg)
            // Debug Toast
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(context, "Manager Started: $width x $height (1Mbps)", android.widget.Toast.LENGTH_SHORT).show()
            }

            val mpManager =
                context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, data)

            // Register callback to satisfy Android 14+ requirement
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    log("MediaProjection Stopped")
                    stop()
                }
            }, null)

            startServer()
            startEncoder()
            startVirtualDisplay()
        } catch (e: Exception) {
            log("Manager Start Error: ${e.message}")
            e.printStackTrace()
             android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(context, "Start Failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return // Prevent re-entry if already stopping
        
        log("Stopping ScreenCaptureManager...")
        try {
            virtualDisplay?.release()
            mediaProjection?.stop()
            try {
                mediaCodec?.signalEndOfInputStream()
            } catch (ignore: Exception) {}
            mediaCodec?.stop()
            mediaCodec?.release()

            clientSocket?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            virtualDisplay = null
            mediaProjection = null
            mediaCodec = null
            inputSurface = null
            clientSocket = null
            serverSocket = null
            configData = null
            frameQueue.clear()
            
            // Notify Service to stop
            onShutdown()
        }
    }

    private fun startServer() {
        Thread {
            try {
                // Debug Toast for Thread Start
                 android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(context, "Server Thread Running...", android.widget.Toast.LENGTH_SHORT).show()
                }
                
                log("Starting ServerSocket on 8888...")
                // Force bind to 0.0.0.0 to listen on all interfaces
                val bindAddr = java.net.InetAddress.getByName("0.0.0.0")
                serverSocket = ServerSocket(8888, 50, bindAddr)
                
                log("Server Started on Port 8888")
                
                // Notify via Toast (Main Thread) that server is ready
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(context, "SOCKET CREATED 8888", android.widget.Toast.LENGTH_SHORT).show()
                }

                while (isRunning.get()) {
                    log("Waiting for client...")
                    try {
                        val socket = serverSocket?.accept()
                        if (socket != null) {
                            log("Client connected: ${socket.inetAddress}")
                            
                            // Optimize Socket for Latency
                            socket.tcpNoDelay = true
                            socket.setPerformancePreferences(0, 1, 2) // low latency, high bandwidth
                            socket.sendBufferSize = 64 * 1024 // 64KB (Reduce buffering)
                            
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                android.widget.Toast.makeText(context, "Client Connected!", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            
                            clientSocket = socket
                            outputStream = socket.getOutputStream()

                            // Send Header: Width (4), Height (4)
                            val header = ByteBuffer.allocate(8)
                            header.putInt(width)
                            header.putInt(height)
                            outputStream?.write(header.array())
                            
                            // Send Config Data (SPS/PPS) if available
                            val currentConfig = configData
                            if (currentConfig != null) {
                                log("Sending cached config data: ${currentConfig.size} bytes")
                                val configHeader = ByteBuffer.allocate(4)
                                configHeader.putInt(currentConfig.size)
                                outputStream?.write(configHeader.array())
                                outputStream?.write(currentConfig)
                            }
                            
                            
                            outputStream?.flush()

                            // Start the sender thread to consume frames from queue
                            startNetworkSender()

                            // Only handle one client
                            break
                        }
                    } catch (socketEx: Exception) {
                        if (isRunning.get()) {
                            log("Socket Accept Error: ${socketEx.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                log("Server Error: ${e.message}")
                e.printStackTrace()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(context, "Server Thread Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun startEncoder() {
        try {
            val format =
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            format.setInteger(MediaFormat.KEY_BIT_RATE, 1000000) // 1Mbps (Reduced from 6Mbps)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second
            
            // Low Latency Settings (Android 10+)
            // format.setInteger("latency", 0) 
            // format.setInteger(MediaFormat.KEY_PRIORITY, 0) // Real-time priority

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = mediaCodec?.createInputSurface()
            mediaCodec?.start()
            
            // Start recording loop
            Thread {
                recordScreen()
            }.start()

        } catch (e: Exception) {
            log("Encoder Error: ${e.message}")
            Log.e(TAG, "Encoder Error", e)
        }
    }

    private fun startVirtualDisplay() {
        if (mediaProjection != null && inputSurface != null) {
            virtualDisplay = mediaProjection!!.createVirtualDisplay(
                "ScreenCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, null
            )
        }
    }

    private val frameQueue = java.util.concurrent.ArrayBlockingQueue<ByteArray>(1)

    private fun startNetworkSender() {
        Thread {
            log("Network Sender Thread Started")
            while (isRunning.get()) {
                try {
                    val data = frameQueue.take() // Blocks until a frame is available
                    if (outputStream != null) {
                        try {
                            // Protocol: [Length (4)] + [Data (N)]
                            val packetHeader = ByteBuffer.allocate(4)
                            packetHeader.putInt(data.size)
                            outputStream!!.write(packetHeader.array())
                            outputStream!!.write(data)
                        } catch (e: Exception) {
                            Log.e(TAG, "Network Send Error", e)
                            log("Client Disconnected or Network Error")
                            stop() // Stop everything on network fail
                            break
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Sender Loop Error", e)
                }
            }
            log("Network Sender Thread Stopped")
        }.start()
    }

    private fun recordScreen() {
        val bufferInfo = MediaCodec.BufferInfo()
        while (isRunning.get()) {
            if (mediaCodec == null) break

            try {
                val outputBufferIndex = mediaCodec!!.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputBufferIndex >= 0) {
                    val outputBuffer = mediaCodec!!.getOutputBuffer(outputBufferIndex)

                    if (outputBuffer != null) {
                        val length = bufferInfo.size
                        val data = ByteArray(length)
                        outputBuffer.get(data)
                        
                        // Check for Config Data (SPS/PPS)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            Log.d(TAG, "Captured Config Data: $length bytes")
                            configData = data
                            // Config data is crucial, typically sent on connect.
                            // However, if it changes mid-stream, we might want to ensure it sends.
                            // For now, let's treat it as a normal frame too so it gets to the queue.
                            // But prioritized? If queue is full of P-frames, we should prefer Config.
                            // Given the rareness, just queuing it is likely fine.
                        }

                        // FRAME DROPPING LOGIC
                        // If queue is full, remove the oldest item (drop it) and add the new one.
                        if (!frameQueue.offer(data)) {
                            // Queue is full. Drop the previous frame.
                            frameQueue.poll() 
                            // Try adding again.
                            if (!frameQueue.offer(data)) {
                                log("Failed to queue frame even after drop")
                            } else {
                                // log("Dropped frame to reduce latency") // Log might be too noisy
                            }
                        }
                    }

                    mediaCodec!!.releaseOutputBuffer(outputBufferIndex, false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recording Loop Error", e)
                if (isRunning.get()) {
                    // Only log error if we haven't stopped intendedly
                    log("Recording Error: ${e.message}")
                }
            }
        }
    }
}
