package com.behavioral.telemetry.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.behavioral.telemetry.data.OnDeviceSynthesizer
import com.behavioral.telemetry.data.TelemetryDatabase
import com.behavioral.telemetry.export.DataExporter
import com.behavioral.telemetry.gemini.GeminiClient
import com.behavioral.telemetry.service.CollectorService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val PREFS_NAME = "telemetry_prefs"
        private const val KEY_GEMINI_API = "gemini_api_key"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startTelemetryService()

        val db = TelemetryDatabase.getDatabase(this)
        val eventCountFlow = db.telemetryDao().getEventCountFlow()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedApiKey = prefs.getString(KEY_GEMINI_API, "") ?: ""

        setContent {
            val eventCount by eventCountFlow.collectAsState(initial = 0)
            var apiKey by remember { mutableStateOf(savedApiKey) }
            var isAnalyzing by remember { mutableStateOf(false) }
            var isExporting by remember { mutableStateOf(false) }
            var analysisResult by remember { mutableStateOf<String?>(null) }
            val scrollState = rememberScrollState()

            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp)
                            .verticalScroll(scrollState),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Behavioral Telemetry",
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Direct-to-Gemini Socratic Engine",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        // Stats Card
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("Events Logged in SQLite", style = MaterialTheme.typography.labelMedium)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "$eventCount",
                                    style = MaterialTheme.typography.headlineLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Gemini API Key Input
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = {
                                apiKey = it
                                prefs.edit().putString(KEY_GEMINI_API, it).apply()
                            },
                            label = { Text("Gemini API Key") },
                            placeholder = { Text("Paste your API key here") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Direct-to-Gemini Analyze Button
                        Button(
                            onClick = {
                                if (apiKey.isBlank()) {
                                    Toast.makeText(this@MainActivity, "Please enter your Gemini API key first", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                isAnalyzing = true
                                analysisResult = null
                                lifecycleScope.launch {
                                    try {
                                        val summary = OnDeviceSynthesizer.buildTelemetrySummary(this@MainActivity)
                                        val result = GeminiClient.analyzeTelemetry(apiKey.trim(), summary)
                                        analysisResult = result
                                    } catch (e: Exception) {
                                        analysisResult = "Analysis error: ${e.message}"
                                    } finally {
                                        isAnalyzing = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isAnalyzing && eventCount > 0
                        ) {
                            if (isAnalyzing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Consulting Socratic Agent...")
                            } else {
                                Text("Analyze with Gemini Directly")
                            }
                        }

                        // Display Analysis Result
                        if (analysisResult != null) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                                )
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = "Socratic Behavioral Review",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = analysisResult ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Default,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Export to PC button
                        OutlinedButton(
                            onClick = {
                                isExporting = true
                                lifecycleScope.launch {
                                    try {
                                        val file = DataExporter.exportToJsonl(this@MainActivity)
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Exported to Downloads:\n${file.name}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(this@MainActivity, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                    } finally {
                                        isExporting = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isExporting && eventCount > 0
                        ) {
                            Text(if (isExporting) "Exporting..." else "Export JSONL to Downloads")
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Battery optimization button
                        TextButton(
                            onClick = {
                                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                startActivity(intent)
                            }
                        ) {
                            Text("Disable Battery Optimization")
                        }
                    }
                }
            }
        }
    }

    private fun startTelemetryService() {
        val serviceIntent = Intent(this, CollectorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}
