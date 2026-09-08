package dev.yellowbytes.ghostbe.demoapp

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.yellowbytes.ghostbe.client.GhostBeInterceptor
import okhttp3.OkHttpClient
import okhttp3.Request

// Requires `adb reverse tcp:3000 tcp:3000` and `adb reverse tcp:44678 tcp:44678`
// so the emulator's own localhost tunnels to demo-backend/ghost-be on the host.
// (The emulator's usual 10.0.2.2 host-loopback alias can time out on some host
// firewall configurations even though ICMP/ping succeeds -- adb reverse sidesteps
// that entirely.)
private const val BACKEND_URL = "http://127.0.0.1:3000/v1/users/42"
private const val GHOST_BE_URL = "http://127.0.0.1:44678"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DemoScreen()
                }
            }
        }
    }
}

@Composable
private fun DemoScreen() {
    var result by remember { mutableStateOf("(no request yet)") }
    val client = remember {
        OkHttpClient.Builder()
            .addInterceptor(GhostBeInterceptor(baseUrl = GHOST_BE_URL))
            .build()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Button(onClick = {
            result = "Loading..."
            Thread {
                val text = try {
                    val request = Request.Builder().url(BACKEND_URL).build()
                    client.newCall(request).execute().use { response ->
                        "HTTP ${response.code}\n${response.body?.string()}"
                    }
                } catch (e: Exception) {
                    "Error: ${e.message}"
                }
                Handler(Looper.getMainLooper()).post { result = text }
            }.start()
        }) {
            Text("Fetch User")
        }

        Text(result)
    }
}
