package dev.yellobytes.ghostbe.demoapp

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
import dev.yellobytes.ghostbe.client.GhostBeInterceptor
import okhttp3.OkHttpClient
import okhttp3.Request

// 10.0.2.2 is the Android emulator's alias for the host machine's localhost,
// where demo-backend and ghost-be both run.
private const val BACKEND_URL = "http://10.0.2.2:3000/v1/users/42"
private const val GHOST_BE_URL = "http://10.0.2.2:8787"

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
