package com.crest247.screenshareserver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.crest247.screenshareserver.ui.theme.ScreenShareServerTheme

class MainActivity : ComponentActivity() {

    private var isSharing by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val notificationGranted = permissions[Manifest.permission.POST_NOTIFICATIONS] ?: true
        
        if (!recordAudioGranted) {
            Toast.makeText(this, "Audio record permission required for system audio", Toast.LENGTH_SHORT).show()
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            Toast.makeText(this, "Permission Granted. Starting Service...", Toast.LENGTH_SHORT).show()
            startScreenCaptureService(result.resultCode, result.data!!)
            isSharing = true
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestPermissions()

        setContent {
            ScreenShareServerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ScreenShareScreen(
                        isSharing = isSharing,
                        onStartClick = { startScreenSharing() },
                        onStopClick = { stopScreenSharing() },
                        onStopped = { isSharing = false },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun startScreenSharing() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions()
            return
        }
        val mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startScreenCaptureService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = "START"
            putExtra("RESULT_CODE", resultCode)
            putExtra("DATA", data)
        }
        startForegroundService(serviceIntent)
    }

    private fun stopScreenSharing() {
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = "STOP"
        }
        startService(serviceIntent)
        isSharing = false
    }
}

@Composable
fun ScreenShareScreen(
    isSharing: Boolean,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onStopped: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ipAddress = remember { getIpAddress() }
    val context = androidx.compose.ui.platform.LocalContext.current

    androidx.compose.runtime.DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
                if (intent?.action == "com.crest247.screenshareserver.STOPPED") {
                    onStopped()
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction("com.crest247.screenshareserver.STOPPED")
        }
        context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        Text(
            text = "Server IP: $ipAddress",
            style = androidx.compose.material3.MaterialTheme.typography.titleLarge
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))

        Text(text = if (isSharing) "Screen Sharing is Active" else "Screen Sharing is Stopped")

        Button(
            onClick = { if (isSharing) onStopClick() else onStartClick() },
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text(text = if (isSharing) "Stop Sharing" else "Start Sharing")
        }
    }
}

private fun getIpAddress(): String {
    try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                    return address.hostAddress ?: "Unknown"
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return "Unavailable"
}
