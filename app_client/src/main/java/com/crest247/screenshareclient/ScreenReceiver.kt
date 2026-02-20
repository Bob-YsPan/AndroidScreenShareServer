package com.crest247.screenshareclient

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ScreenReceiver(
    private val onAspectRatioChanged: (Float) -> Unit,
    private val onConnectionStateChanged: (Boolean) -> Unit,
    private val onMessage: (String) -> Unit = {}
) {
    private val TAG = "ScreenReceiver"
    private val isRunning = AtomicBoolean(false)

    private var udpSocket: DatagramSocket? = null
    private var receiverThread: Thread? = null
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    private val videoQueue: BlockingQueue<VideoTask> = ArrayBlockingQueue(2)
    private val audioQueue: BlockingQueue<AudioTask> = ArrayBlockingQueue(10)

    private sealed class VideoTask {
        data class Setup(val surface: Surface, val width: Int, val height: Int) : VideoTask()
        data class Config(val data: ByteArray) : VideoTask()
        data class Frame(val data: ByteArray) : VideoTask()
    }

    private sealed class AudioTask {
        data class Config(val data: ByteArray) : AudioTask()
        data class Data(val data: ByteArray) : AudioTask()
    }

    // Reassembly state (only used in receiver thread)
    private var currentFrameIndex: Short = -1
    private var frameBuffer: Array<ByteArray?>? = null
    private var receivedPartsCount = 0

    fun start(surface: Surface, host: String, port: Int) {
        if (isRunning.getAndSet(true)) return

        startVideoThread()
        startAudioThread()

        receiverThread = Thread {
            try {
                udpSocket = DatagramSocket()
                udpSocket?.soTimeout = 2000
                udpSocket?.receiveBufferSize = 1024 * 1024 // 1MB is usually enough and avoids excessive bufferbloat
                
                val serverAddr = InetAddress.getByName(host)
                val heartbeatPacket = DatagramPacket(ByteArray(1) { 0xFF.toByte() }, 1, serverAddr, port)
                
                // Heartbeat thread
                val hbThread = Thread {
                    while (isRunning.get()) {
                        try {
                            udpSocket?.send(heartbeatPacket)
                            Thread.sleep(1000)
                        } catch (e: Exception) { break }
                    }
                }
                hbThread.start()

                receiveLoop(surface)
            } catch (e: Exception) {
                Log.e(TAG, "Receiver error: ${e.message}")
            } finally {
                stop()
            }
        }
        receiverThread?.start()
    }

    private fun receiveLoop(surface: Surface) {
        val buffer = ByteArray(8192)
        val packet = DatagramPacket(buffer, buffer.size)
        onConnectionStateChanged(true)

        while (isRunning.get()) {
            try {
                udpSocket?.receive(packet)
                val data = packet.data
                val length = packet.length
                if (length < 1) continue

                when (data[0].toInt()) {
                    0 -> { // Meta
                        val bb = ByteBuffer.wrap(data, 1, length - 1)
                        val w = bb.getInt()
                        val h = bb.getInt()
                        videoQueue.offer(VideoTask.Setup(surface, w, h))
                        onAspectRatioChanged(w.toFloat() / h.toFloat())
                    }
                    1 -> handleFramePart(data, length)
                    2 -> { // Video Config
                        val config = ByteArray(length - 1)
                        System.arraycopy(data, 1, config, 0, length - 1)
                        videoQueue.offer(VideoTask.Config(config))
                    }
                    3 -> { // Audio Data
                        val audio = ByteArray(length - 1)
                        System.arraycopy(data, 1, audio, 0, length - 1)
                        if (!audioQueue.offer(AudioTask.Data(audio))) {
                            audioQueue.poll() // Drop oldest if full to maintain low latency
                            audioQueue.offer(AudioTask.Data(audio))
                        }
                    }
                    4 -> { // Audio Config
                        val config = ByteArray(length - 1)
                        System.arraycopy(data, 1, config, 0, length - 1)
                        audioQueue.offer(AudioTask.Config(config))
                    }
                }
            } catch (e: Exception) {
                if (e is java.net.SocketTimeoutException) continue
                if (isRunning.get()) Log.w(TAG, "Receive error: ${e.message}")
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
                assembleAndQueue()
            }
        }
    }

    private fun assembleAndQueue() {
        val parts = frameBuffer ?: return
        var totalSize = 0
        for (p in parts) totalSize += p?.size ?: 0
        
        val assembled = ByteArray(totalSize)
        var offset = 0
        for (p in parts) {
            if (p != null) {
                System.arraycopy(p, 0, assembled, offset, p.size)
                offset += p.size
            }
        }

        if (!videoQueue.offer(VideoTask.Frame(assembled))) {
            videoQueue.poll() // Drop oldest frame to catch up and reduce latency
            videoQueue.offer(VideoTask.Frame(assembled))
        }
    }

    private fun startVideoThread() {
        videoThread = Thread {
            var codec: MediaCodec? = null
            var lastWidth = -1
            var lastHeight = -1

            while (isRunning.get()) {
                try {
                    val task = videoQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    when (task) {
                        is VideoTask.Setup -> {
                            if (task.width != lastWidth || task.height != lastHeight) {
                                codec?.stop()
                                codec?.release()
                                codec = createVideoDecoder(task.surface, task.width, task.height)
                                lastWidth = task.width
                                lastHeight = task.height
                            }
                        }
                        is VideoTask.Config -> {
                            codec?.let { submitConfig(it, task.data) }
                        }
                        is VideoTask.Frame -> {
                            codec?.let { decodeVideoFrame(it, task.data) }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Video thread error: ${e.message}")
                }
            }
            codec?.stop()
            codec?.release()
        }
        videoThread?.start()
    }

    private fun createVideoDecoder(surface: Surface, width: Int, height: Int): MediaCodec {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val hardwareName = findHardwareDecoder(mime)
        val codec = if (hardwareName != null) {
            Log.d(TAG, "Using hardware decoder: $hardwareName")
            MediaCodec.createByCodecName(hardwareName)
        } else {
            MediaCodec.createDecoderByType(mime)
        }

        val format = MediaFormat.createVideoFormat(mime, width, height)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        format.setInteger(MediaFormat.KEY_OPERATING_RATE, 240)
        
        codec.configure(format, surface, null, 0)
        codec.start()
        return codec
    }

    private fun decodeVideoFrame(codec: MediaCodec, data: ByteArray) {
        val inputIndex = codec.dequeueInputBuffer(0)
        if (inputIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputIndex)
            inputBuffer?.clear()
            inputBuffer?.put(data)
            codec.queueInputBuffer(inputIndex, 0, data.size, 0, 0)
        }

        val info = MediaCodec.BufferInfo()
        var outputIndex = codec.dequeueOutputBuffer(info, 0)
        while (outputIndex >= 0) {
            codec.releaseOutputBuffer(outputIndex, true)
            outputIndex = codec.dequeueOutputBuffer(info, 0)
        }
    }

    private fun submitConfig(codec: MediaCodec, data: ByteArray) {
        val inputIndex = codec.dequeueInputBuffer(10000)
        if (inputIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputIndex)
            inputBuffer?.clear()
            inputBuffer?.put(data)
            codec.queueInputBuffer(inputIndex, 0, data.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
        }
    }

    private fun startAudioThread() {
        audioThread = Thread {
            var codec: MediaCodec? = null
            var track: AudioTrack? = null
            var isConfigured = false

            while (isRunning.get()) {
                try {
                    val task = audioQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    if (codec == null) {
                        val result = setupAudio()
                        codec = result.first
                        track = result.second
                    }

                    when (task) {
                        is AudioTask.Config -> {
                            codec?.let {
                                submitConfig(it, task.data)
                                isConfigured = true
                            }
                        }
                        is AudioTask.Data -> {
                            if (isConfigured) {
                                codec?.let { c ->
                                    track?.let { t -> decodeAudioFrame(c, t, task.data) }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Audio thread error: ${e.message}")
                }
            }
            codec?.stop()
            codec?.release()
            track?.stop()
            track?.release()
        }
        audioThread?.start()
    }

    private fun setupAudio(): Pair<MediaCodec, AudioTrack> {
        val sampleRate = 44100
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2)
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, 0)
        codec.start()

        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                }
            }
            .build()
        track.play()
        return Pair(codec, track)
    }

    private fun decodeAudioFrame(codec: MediaCodec, track: AudioTrack, data: ByteArray) {
        val inputIndex = codec.dequeueInputBuffer(0)
        if (inputIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputIndex)
            inputBuffer?.clear()
            inputBuffer?.put(data)
            codec.queueInputBuffer(inputIndex, 0, data.size, 0, 0)
        }

        val info = MediaCodec.BufferInfo()
        var outputIndex = codec.dequeueOutputBuffer(info, 0)
        while (outputIndex >= 0) {
            val outBuf = codec.getOutputBuffer(outputIndex)
            if (outBuf != null) {
                val pcm = ByteArray(info.size)
                outBuf.get(pcm)
                track.write(pcm, 0, pcm.size)
            }
            codec.releaseOutputBuffer(outputIndex, false)
            outputIndex = codec.dequeueOutputBuffer(info, 0)
        }
    }

    private fun findHardwareDecoder(mime: String): String? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (info.isEncoder) continue
            for (type in info.supportedTypes) {
                if (type.equals(mime, ignoreCase = true)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        if (info.isHardwareAccelerated) return info.name
                    } else {
                        val name = info.name.lowercase()
                        if (!name.startsWith("omx.google.") && !name.startsWith("c2.android.") && !name.contains("sw")) return info.name
                    }
                }
            }
        }
        return null
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        udpSocket?.close()
        onConnectionStateChanged(false)
    }
}
