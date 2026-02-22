package com.crest247.screenshareclient

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

class DiscoveryClient(private val onDiscovered: (String) -> Unit) {
    private val TAG = "DiscoveryClient"
    private var socket: DatagramSocket? = null
    private val isRunning = AtomicBoolean(false)

    fun start() {
        if (isRunning.getAndSet(true)) return
        Thread {
            try {
                socket = DatagramSocket(8890) // Listen on 8890 for server response
                socket?.broadcast = true
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)

                // Start a thread to send discovery broadcast periodically
                val broadcastThread = Thread {
                    try {
                        val broadcastSocket = DatagramSocket()
                        broadcastSocket.broadcast = true
                        val message = "DISCOVER_SCREEN_SHARE_SERVER".toByteArray()
                        val address = InetAddress.getByName("255.255.255.255")
                        val broadcastPacket = DatagramPacket(message, message.size, address, 8889)

                        while (isRunning.get()) {
                            try {
                                broadcastSocket.send(broadcastPacket)
                                Log.d(TAG, "Sent discovery broadcast")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error sending broadcast: ${e.message}")
                            }
                            Thread.sleep(2000)
                        }
                        broadcastSocket.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "Broadcast thread error: ${e.message}")
                    }
                }
                broadcastThread.start()

                Log.d(TAG, "DiscoveryClient listening on port 8890")
                while (isRunning.get()) {
                    try {
                        socket?.receive(packet)
                        val message = String(packet.data, 0, packet.length)
                        Log.d(TAG, "Received message: $message from ${packet.address.hostAddress}")
                        
                        if (message.startsWith("SCREEN_SHARE_SERVER_IP:")) {
                            val ip = message.substringAfter("SCREEN_SHARE_SERVER_IP:")
                            Log.d(TAG, "Discovered server IP: $ip")
                            onDiscovered(ip)
                        }
                    } catch (e: Exception) {
                        if (isRunning.get()) Log.e(TAG, "Error receiving response: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "DiscoveryClient error: ${e.message}")
            } finally {
                socket?.close()
                Log.d(TAG, "DiscoveryClient stopped")
            }
        }.start()
    }

    fun stop() {
        isRunning.set(false)
        socket?.close()
    }
}
