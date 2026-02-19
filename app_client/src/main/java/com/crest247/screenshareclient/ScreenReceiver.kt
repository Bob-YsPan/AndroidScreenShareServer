package com.crest247.screenshareclient

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class ScreenReceiver(
    private val onAspectRatioChanged: (Float) -> Unit,
    private val onConnectionStateChanged: (Boolean) -> Unit,
    private val onMessage: (String) -> Unit = {}
) {

    private var udpSocket: DatagramSocket? = null
    private var mediaCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null

    private val isRunning = AtomicBoolean(false)
    private val TAG = "ScreenReceiver"
    private var receiverThread: Thread? = null

    // Frame Reassembly
    private var currentFrameIndex: Short = -1
    private var frameBuffer: Array<ByteArray?>? = null
    private var receivedPartsCount = 0
    
    // Decoder State
    private var currentWidth = -1
    private var currentHeight = -1
    private var isAudioCodecConfigured = false

    fun start(surface: Surface, host: String, port: Int) {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "ScreenReceiver already running")
            return
        }

        receiverThread = Thread {
            Log.d(TAG, "Receiver Thread Started (UDP): $host:$port")
            
            try {
                udpSocket = DatagramSocket()
                udpSocket?.soTimeout = 2000 // 2 seconds timeout
                val serverAddr = InetAddress.getByName(host)

                // Send Heartbeat to let server know our IP/Port
                val heartbeat = ByteArray(1) { 0xFF.toByte() }
                val heartbeatPacket = DatagramPacket(heartbeat, heartbeat.size, serverAddr, port)
                
                val heartbeatThread = Thread {
                    while (isRunning.get()) {
                        try {
                            udpSocket?.send(heartbeatPacket)
                            Thread.sleep(1000) // Heartbeat every 1s
                        } catch (e: Exception) { break }
                    }
                }
                heartbeatThread.start()

                setupAudioDecoder()
                receiveLoop(surface)

            } catch (e: Exception) {
                Log.e(TAG, "UDP Error: ${e.message}")
                onMessage("Error: ${e.message}")
            } finally {
                cleanup()
            }
        }
        receiverThread?.start()
    }

    private fun receiveLoop(surface: Surface) {
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)
        
        onConnectionStateChanged(true)

        while (isRunning.get()) {
            try {
                udpSocket?.receive(packet)
                val data = packet.data
                val length = packet.length
                if (length < 1) continue

                val type = data[0].toInt()
                when (type) {
                    0 -> { // Meta Data
                        val bb = ByteBuffer.wrap(data, 1, length - 1)
                        val width = bb.getInt()
                        val height = bb.getInt()
                        if (width != currentWidth || height != currentHeight) {
                            Log.d(TAG, "Metadata Change: ${width}x${height}")
                            setupDecoder(surface, width, height)
                            onAspectRatioChanged(width.toFloat() / height.toFloat())
                        }
                    }
                    1 -> { // Frame Data
                        handleFramePart(data, length)
                    }
                    2 -> { // Config Data (SPS/PPS)
                        val config = ByteArray(length - 1)
                        System.arraycopy(data, 1, config, 0, length - 1)
                        submitConfigToDecoder(config)
                    }
                    3 -> { // Audio Data (AAC)
                        if (isAudioCodecConfigured) {
                            val audioData = ByteArray(length - 1)
                            System.arraycopy(data, 1, audioData, 0, length - 1)
                            decodeAudio(audioData)
                        }
                    }
                    4 -> { // Audio Config Data (AAC CSD)
                        val config = ByteArray(length - 1)
                        System.arraycopy(data, 1, config, 0, length - 1)
                        submitAudioConfigToDecoder(config)
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                // Heartbeat still active, just haven't received data
            } catch (e: Exception) {
                if (isRunning.get()) Log.w(TAG, "Receive loop: ${e.message}")
            }
        }
    }

    private fun handleFramePart(data: ByteArray, length: Int) {
        if (length < 5) return
        val bb = ByteBuffer.wrap(data, 1, 4)
        val frameIdx = bb.getShort()
        val partIdx = bb.get().toInt() and 0xFF
        val totalParts = bb.get().toInt() and 0xFF
        val payloadLength = length - 5

        if (frameIdx != currentFrameIndex) {
            currentFrameIndex = frameIdx
            frameBuffer = arrayOfNulls(totalParts)
            receivedPartsCount = 0
        }

        if (frameBuffer != null && partIdx < frameBuffer!!.size && frameBuffer!![partIdx] == null) {
            val payload = ByteArray(payloadLength)
            System.arraycopy(data, 5, payload, 0, payloadLength)
            frameBuffer!![partIdx] = payload
            receivedPartsCount++

            if (receivedPartsCount == totalParts) {
                assembleAndDecode()
            }
        }
    }

    private fun assembleAndDecode() {
        val buffer = frameBuffer ?: return
        var totalSize = 0
        for (part in buffer) {
            totalSize += part?.size ?: 0
        }

        val fullFrame = ByteArray(totalSize)
        var offset = 0
        for (part in buffer) {
            if (part != null) {
                System.arraycopy(part, 0, fullFrame, offset, part.size)
                offset += part.size
            }
        }

        try {
            val codec = mediaCodec ?: return
            val inputBufferIndex = codec.dequeueInputBuffer(0)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                inputBuffer?.clear()
                inputBuffer?.put(fullFrame)
                codec.queueInputBuffer(inputBufferIndex, 0, totalSize, 0, 0)
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            while (outputBufferIndex >= 0) {
                codec.releaseOutputBuffer(outputBufferIndex, true)
                outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decode error: ${e.message}")
        }
    }

    private fun submitConfigToDecoder(config: ByteArray) {
        try {
            val codec = mediaCodec ?: return
            val inputBufferIndex = codec.dequeueInputBuffer(10000)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                inputBuffer?.clear()
                inputBuffer?.put(config)
                codec.queueInputBuffer(inputBufferIndex, 0, config.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                Log.d(TAG, "Decoder Config submitted")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Config submit error: ${e.message}")
        }
    }

    private fun submitAudioConfigToDecoder(config: ByteArray) {
        try {
            val codec = audioCodec ?: return
            val inputBufferIndex = codec.dequeueInputBuffer(10000)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                inputBuffer?.clear()
                inputBuffer?.put(config)
                codec.queueInputBuffer(inputBufferIndex, 0, config.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                isAudioCodecConfigured = true
                Log.d(TAG, "Audio Decoder Config submitted")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio Config submit error: ${e.message}")
        }
    }

    private fun setupDecoder(surface: Surface, width: Int, height: Int) {
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
            
            currentWidth = width
            currentHeight = height
            
            mediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            mediaCodec?.configure(format, surface, null, 0)
            mediaCodec?.start()
            Log.d(TAG, "Decoder Started: ${width}x${height}")
        } catch (e: Exception) {
            Log.e(TAG, "Decoder Setup Failed", e)
        }
    }

    private fun setupAudioDecoder() {
        try {
            val sampleRate = 44100
            val channelCount = 2
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount)
            
            audioCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            audioCodec?.configure(format, null, null, 0)
            audioCodec?.start()

            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(minBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            
            audioTrack?.play()
            isAudioCodecConfigured = false
            Log.d(TAG, "Audio Decoder and Track started")
        } catch (e: Exception) {
            Log.e(TAG, "Audio Setup Failed", e)
        }
    }

    private fun decodeAudio(data: ByteArray) {
        try {
            val codec = audioCodec ?: return
            val inputIndex = codec.dequeueInputBuffer(10000)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex)
                inputBuffer?.clear()
                inputBuffer?.put(data)
                codec.queueInputBuffer(inputIndex, 0, data.size, 0, 0)
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            while (outputIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputIndex)
                if (outputBuffer != null) {
                    val pcm = ByteArray(bufferInfo.size)
                    outputBuffer.get(pcm)
                    audioTrack?.write(pcm, 0, pcm.size)
                }
                codec.releaseOutputBuffer(outputIndex, false)
                outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio Decode Error: ${e.message}")
        }
    }

    private fun cleanup() {
        isRunning.set(false)
        try {
            udpSocket?.close()
            mediaCodec?.stop()
            mediaCodec?.release()
            audioCodec?.stop()
            audioCodec?.release()
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {}
        udpSocket = null
        mediaCodec = null
        audioCodec = null
        audioTrack = null
        currentWidth = -1
        currentHeight = -1
        isAudioCodecConfigured = false
        onConnectionStateChanged(false)
    }

    fun stop() {
        isRunning.set(false)
        receiverThread?.interrupt()
        cleanup()
    }
}
