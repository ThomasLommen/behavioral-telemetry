package com.behavioral.telemetry.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.behavioral.telemetry.data.DigestEntity
import com.behavioral.telemetry.data.OnDeviceSynthesizer
import com.behavioral.telemetry.data.TelemetryDatabase
import com.behavioral.telemetry.data.UsageStatsHelper
import com.behavioral.telemetry.export.DataExporter
import com.behavioral.telemetry.gemini.GeminiClient
import com.behavioral.telemetry.service.CollectorService
import com.behavioral.telemetry.workers.WeeklyDigestWorker
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    companion object {
        private const val PREFS_NAME = com.behavioral.telemetry.data.TelemetryConfig.PREFS_NAME
        private const val KEY_GEMINI_API = com.behavioral.telemetry.data.TelemetryConfig.KEY_GEMINI_API
        private const val KEY_LATEST_DIGEST = com.behavioral.telemetry.data.TelemetryConfig.KEY_LATEST_DIGEST
    }

    private var hasUsageAccessState = mutableStateOf(false)
    private var hasNotificationAccessState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startTelemetryService()

        // Schedule automated weekly digest via WorkManager
        WeeklyDigestWorker.scheduleWeeklyDigest(this)

        val db = TelemetryDatabase.getDatabase(this)
        val eventCountFlow = db.telemetryDao().getEventCountFlow()
        val digestsFlow = db.digestDao().getAllDigestsFlow()
        val prefs = getSharedPreferences(com.behavioral.telemetry.data.TelemetryConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val savedApiKey = com.behavioral.telemetry.data.TelemetryConfig.getApiKey(this)
        val cachedDigest = prefs.getString(com.behavioral.telemetry.data.TelemetryConfig.KEY_LATEST_DIGEST, null)

        setContent {
            val eventCount by eventCountFlow.collectAsState(initial = 0)
            val savedDigests by digestsFlow.collectAsState(initial = emptyList())
            var selectedTabIndex by remember { mutableStateOf(0) }
            var apiKey by remember { mutableStateOf(savedApiKey) }
            var isEditingApiKey by remember { mutableStateOf(false) }
            var isAnalyzing by remember { mutableStateOf(false) }
            var isExporting by remember { mutableStateOf(false) }
            var analysisResult by remember { mutableStateOf(cachedDigest) }
            val hasUsageAccess by hasUsageAccessState
            val hasNotificationAccess by hasNotificationAccessState
            val scrollState = rememberScrollState()

            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Top Header
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 20.dp, start = 20.dp, end = 20.dp, bottom = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Behavioral Telemetry",
                                style = MaterialTheme.typography.headlineMedium
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Personal Behavioral Briefing Engine",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }

                        // Navigation Tabs: Live Monitor vs Archive
                        TabRow(
                            selectedTabIndex = selectedTabIndex,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Tab(
                                selected = selectedTabIndex == 0,
                                onClick = { selectedTabIndex = 0 },
                                text = { Text("Live Monitor") }
                            )
                            Tab(
                                selected = selectedTabIndex == 1,
                                onClick = { selectedTabIndex = 1 },
                                text = {
                                    val countSuffix = if (savedDigests.isNotEmpty()) " (${savedDigests.size})" else ""
                                    Text("Archive$countSuffix")
                                }
                            )
                        }

                        if (selectedTabIndex == 0) {
                            // TAB 0: LIVE MONITOR
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(20.dp)
                                    .verticalScroll(scrollState),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
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

                                // Gemini API Key Input (Hidden once saved)
                                if (!isEditingApiKey && apiKey.isNotBlank()) {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Gemini API Key Configured",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            TextButton(onClick = { isEditingApiKey = true }) {
                                                Text("Change")
                                            }
                                        }
                                    }
                                } else {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        OutlinedTextField(
                                            value = apiKey,
                                            onValueChange = {
                                                apiKey = it
                                                com.behavioral.telemetry.data.TelemetryConfig.setApiKey(this@MainActivity, it)
                                            },
                                            label = { Text("Gemini API Key") },
                                            placeholder = { Text("Paste your API key here") },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                            visualTransformation = PasswordVisualTransformation()
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            TextButton(onClick = {
                                                apiKey = com.behavioral.telemetry.data.TelemetryConfig.DEFAULT_GEMINI_API_KEY
                                                com.behavioral.telemetry.data.TelemetryConfig.setApiKey(this@MainActivity, apiKey)
                                                Toast.makeText(this@MainActivity, "Reset to default key", Toast.LENGTH_SHORT).show()
                                            }) {
                                                Text("Reset Default")
                                            }
                                            if (apiKey.isNotBlank()) {
                                                TextButton(onClick = { isEditingApiKey = false }) {
                                                    Text("Done")
                                                }
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // Optional Enhanced Telemetry Permissions Section
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surface
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Text(
                                            text = "Enhanced Telemetry Signals",
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))

                                        // Usage Access Row
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = if (hasUsageAccess) "App Tracking: Enabled" else "App Tracking: Disabled",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (hasUsageAccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                            )
                                            if (!hasUsageAccess) {
                                                TextButton(onClick = {
                                                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                                }) {
                                                    Text("Enable")
                                                }
                                            }
                                        }

                                        // Notification Listener Row
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = if (hasNotificationAccess) "Notification Traps: Enabled" else "Notification Traps: Disabled",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (hasNotificationAccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                            )
                                            if (!hasNotificationAccess) {
                                                TextButton(onClick = {
                                                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                                }) {
                                                    Text("Enable")
                                                }
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // Direct-to-Gemini Analyze Button
                                Button(
                                    onClick = {
                                        if (apiKey.isBlank()) {
                                            Toast.makeText(this@MainActivity, "Please enter your Gemini API key first", Toast.LENGTH_SHORT).show()
                                            return@Button
                                        }
                                        isAnalyzing = true
                                        lifecycleScope.launch {
                                            try {
                                                val summary = OnDeviceSynthesizer.buildTelemetrySummary(this@MainActivity)
                                                val result = GeminiClient.analyzeTelemetry(apiKey.trim(), summary)
                                                analysisResult = result
                                                prefs.edit().putString(KEY_LATEST_DIGEST, result).apply()

                                                // Save to Digest Archive
                                                val now = System.currentTimeMillis()
                                                val sdf = SimpleDateFormat("MMM d, yyyy", Locale.US)
                                                val headline = result.lines()
                                                    .firstOrNull { it.startsWith("**Headline:**") || it.contains("Headline") }
                                                    ?.replace("**Headline:**", "")?.trim()
                                                    ?: "Behavioral Analysis"
                                                db.digestDao().insertDigest(
                                                    DigestEntity(
                                                        timestampUtc = now,
                                                        formattedDate = sdf.format(Date(now)),
                                                        headline = headline,
                                                        fullContent = result,
                                                        isAutomated = false
                                                    )
                                                )
                                                Toast.makeText(this@MainActivity, "Saved to Archive!", Toast.LENGTH_SHORT).show()
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
                                        Text("Synthesizing Executive Digest...")
                                    } else {
                                        Text("Analyze with Gemini Directly")
                                    }
                                }

                                // Display Latest Analysis Result
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
                                                text = "Latest Executive Digest",
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
                        } else {
                            // TAB 1: DIGEST ARCHIVE / HISTORY
                            if (savedDigests.isEmpty()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = "No saved digests yet.",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Run your first analysis from the Live Monitor tab or wait for your Sunday auto-digest.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(savedDigests, key = { it.id }) { digest ->
                                        var isExpanded by remember { mutableStateOf(false) }

                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { isExpanded = !isExpanded },
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                                            )
                                        ) {
                                            Column(modifier = Modifier.padding(16.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = digest.formattedDate,
                                                        style = MaterialTheme.typography.labelLarge,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                    Badge(
                                                        containerColor = if (digest.isAutomated) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer
                                                    ) {
                                                        Text(
                                                            text = if (digest.isAutomated) "Weekly Auto" else "Manual",
                                                            style = MaterialTheme.typography.labelSmall
                                                        )
                                                    }
                                                }

                                                Spacer(modifier = Modifier.height(6.dp))

                                                Text(
                                                    text = digest.headline,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )

                                                if (isExpanded) {
                                                    Spacer(modifier = Modifier.height(12.dp))
                                                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                                                    Spacer(modifier = Modifier.height(12.dp))

                                                    Text(
                                                        text = digest.fullContent,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )

                                                    Spacer(modifier = Modifier.height(8.dp))
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.End
                                                    ) {
                                                        TextButton(
                                                            onClick = {
                                                                lifecycleScope.launch {
                                                                    db.digestDao().deleteDigest(digest.id)
                                                                }
                                                            }
                                                        ) {
                                                            Text("Delete", color = MaterialTheme.colorScheme.error)
                                                        }
                                                    }
                                                } else {
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = "Tap to view full digest...",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasUsageAccessState.value = UsageStatsHelper.hasUsagePermission(this)
        hasNotificationAccessState.value = hasNotificationPermission(this)
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(context.packageName)
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
