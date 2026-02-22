package com.crest247.screenshareserver

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

class DiscoveryServer(private val ipAddress: String) {
    private val TAG = "DiscoveryServer"
    private var socket: DatagramSocket? = null
    private val isRunning = AtomicBoolean(false)

    fun start() {
        if (isRunning.getAndSet(true)) return
        Thread {
            try {
                // Listen on port 8889 for client discovery requests
                socket = DatagramSocket(8889)
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)

                Log.d(TAG, "DiscoveryServer listening on port 8889")
                while (isRunning.get()) {
                    try {
                        socket?.receive(packet)
                        val message = String(packet.data, 0, packet.length)
                        Log.d(TAG, "Received message: $message from ${packet.address.hostAddress}")

                        if (message == "DISCOVER_SCREEN_SHARE_SERVER") {
                            val responseMessage = "SCREEN_SHARE_SERVER_IP:$ipAddress".toByteArray()
                            val responsePacket = DatagramPacket(
                                responseMessage,
                                responseMessage.size,
                                packet.address, // Send back to client's IP
                                8890 // Client will listen on 8890
                            )
                            socket?.send(responsePacket)
                            Log.d(TAG, "Sent response to ${packet.address.hostAddress}")
                        }
                    } catch (e: Exception) {
                        if (isRunning.get()) Log.e(TAG, "Error in discovery loop: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "DiscoveryServer error: ${e.message}")
            } finally {
                socket?.close()
                Log.d(TAG, "DiscoveryServer stopped")
            }
        }.start()
    }

    fun stop() {
        isRunning.set(false)
        socket?.close()
    }
}
