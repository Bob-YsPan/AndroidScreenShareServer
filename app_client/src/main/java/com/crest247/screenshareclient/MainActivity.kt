package com.crest247.screenshareclient

import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.crest247.screenshareclient.ui.theme.ScreenShareClientTheme

class MainActivity : ComponentActivity() {

    private var screenReceiver: ScreenReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        setContent {
            ScreenShareClientTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ScreenShareApp()
                }
            }
        }
    }

    @Composable
    fun ScreenShareApp() {
        var ipAddress by remember { mutableStateOf("192.168.43.1") }
        var isConnected by remember { mutableStateOf(false) }
        var aspectRatio by remember { mutableFloatStateOf(0.5625f) } 

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            
            if (isConnected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { ctx ->
                            SurfaceView(ctx).apply {
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) {
                                        startReceiver(
                                            surface = holder.surface,
                                            ip = ipAddress,
                                            onRatio = { ratio -> 
                                                // Update state on UI thread
                                                runOnUiThread { aspectRatio = ratio }
                                            },
                                            onConnectionChanged = { connected -> 
                                                runOnUiThread { isConnected = connected } 
                                            }
                                        )
                                    }
                                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                                        stopReceiver()
                                    }
                                })
                            }
                        },
                        modifier = Modifier.aspectRatio(aspectRatio, matchHeightConstraintsFirst = true)
                    )
                }
            }

            if (!isConnected) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.8f))
                        .padding(32.dp)
                ) {
                    Text(text = "Enter Sender IP Address", style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = ipAddress,
                        onValueChange = { ipAddress = it },
                        label = { Text("IP Address") },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { isConnected = true }) {
                        Text("Connect")
                    }
                }
            }
        }
    }

    private fun startReceiver(
        surface: android.view.Surface,
        ip: String,
        onRatio: (Float) -> Unit,
        onConnectionChanged: (Boolean) -> Unit
    ) {
        if (screenReceiver != null) return
        
        screenReceiver = ScreenReceiver(
            onAspectRatioChanged = onRatio,
            onConnectionStateChanged = { connected ->
                onConnectionChanged(connected)
            },
            onMessage = { msg ->
                runOnUiThread {
                    android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        )
        screenReceiver?.start(surface, ip, 8888)
    }

    private fun stopReceiver() {
        screenReceiver?.stop()
        screenReceiver = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopReceiver()
    }
}
