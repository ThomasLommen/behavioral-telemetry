package com.behavioral.telemetry.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.behavioral.telemetry.data.DigestEntity
import com.behavioral.telemetry.data.OnDeviceSynthesizer
import com.behavioral.telemetry.data.TelemetryConfig
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
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private var hasUsageAccessState = mutableStateOf(false)
    private var hasNotificationAccessState = mutableStateOf(false)
    private var hasPostNotificationsState = mutableStateOf(false)
    private val selectedTabIndexState = mutableStateOf(0)

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPostNotificationsState.value = isGranted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startTelemetryService()

        val targetTab = intent?.getIntExtra(WeeklyDigestWorker.EXTRA_TARGET_TAB, 0) ?: 0
        selectedTabIndexState.value = targetTab.coerceIn(0, 2)

        // Schedule automated weekly digest via WorkManager
        WeeklyDigestWorker.scheduleWeeklyDigest(this)

        val db = TelemetryDatabase.getDatabase(this)
        val eventCountFlow = db.telemetryDao().getEventCountFlow()
        val digestsFlow = db.digestDao().getAllDigestsFlow()
        val prefs = getSharedPreferences(TelemetryConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val savedApiKey = TelemetryConfig.getApiKey(this)
        val cachedDigest = prefs.getString(TelemetryConfig.KEY_LATEST_DIGEST, null)

        setContent {
            val eventCount by eventCountFlow.collectAsState(initial = 0)
            val savedDigests by digestsFlow.collectAsState(initial = emptyList())
            var selectedTabIndex by selectedTabIndexState
            var apiKey by remember { mutableStateOf(savedApiKey) }
            var isEditingApiKey by remember { mutableStateOf(false) }
            var isAnalyzing by remember { mutableStateOf(false) }
            var isExporting by remember { mutableStateOf(false) }
            var analysisResult by remember { mutableStateOf(cachedDigest) }
            val hasUsageAccess by hasUsageAccessState
            val hasNotificationAccess by hasNotificationAccessState
            val scrollState = rememberScrollState()

            // Interactive "Ask Gemini" Chat State
            val chatMessages = remember {
                mutableStateListOf(
                    Pair(
                        "model",
                        "Hey! I have direct access to your on-device behavioral telemetry (bedtime charging anchors, dark-room exposure, compulsive micro-checks, and app switching loops). Ask me anything about your digital patterns!"
                    )
                )
            }
            var chatInput by remember { mutableStateOf("") }
            var isChatLoading by remember { mutableStateOf(false) }

            val sendChatQuery: (String) -> Unit = { query ->
                if (query.isNotBlank() && !isChatLoading) {
                    val q = query.trim()
                    chatInput = ""
                    chatMessages.add(Pair("user", q))
                    isChatLoading = true
                    lifecycleScope.launch {
                        try {
                            val summary = OnDeviceSynthesizer.buildTelemetrySummary(this@MainActivity)
                            val history = chatMessages.take(chatMessages.size - 1)
                            val reply = GeminiClient.chatWithTelemetry(apiKey.trim(), summary, history, q)
                            chatMessages.add(Pair("model", reply))
                        } catch (e: Exception) {
                            chatMessages.add(Pair("model", "Analysis failed: ${e.message}"))
                        } finally {
                            isChatLoading = false
                        }
                    }
                }
            }

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

                        // Navigation Tabs: Live Monitor vs Ask Gemini vs Archive
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
                                text = { Text("Ask Gemini") }
                            )
                            Tab(
                                selected = selectedTabIndex == 2,
                                onClick = { selectedTabIndex = 2 },
                                text = {
                                    val countSuffix = if (savedDigests.isNotEmpty()) " (${savedDigests.size})" else ""
                                    Text("Archive$countSuffix")
                                }
                            )
                        }

                        when (selectedTabIndex) {
                            0 -> {
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
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                text = "Capturing screen cycles, charging anchors, dark-room lux, and app switches silently in background.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                                    TelemetryConfig.setApiKey(this@MainActivity, it)
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
                                                    apiKey = TelemetryConfig.DEFAULT_GEMINI_API_KEY
                                                    TelemetryConfig.setApiKey(this@MainActivity, apiKey)
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

                                    // Enhanced Telemetry Permissions Section
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

                                            // Usage Stats Permission Row
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text("App Dwell & Switching", style = MaterialTheme.typography.bodyMedium)
                                                    Text(
                                                        if (hasUsageAccess) "Active (logs foreground app switches)" else "Off (requires manual enable)",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = if (hasUsageAccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                                    )
                                                }
                                                if (!hasUsageAccess) {
                                                    TextButton(onClick = {
                                                        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                                        startActivity(intent)
                                                    }) {
                                                        Text("Enable")
                                                    }
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(8.dp))
                                            HorizontalDivider()
                                            Spacer(modifier = Modifier.height(8.dp))

                                            // Notification Listener Permission Row
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text("Notification Trap Detection", style = MaterialTheme.typography.bodyMedium)
                                                    Text(
                                                        if (hasNotificationAccess) "Active (logs arrival metadata only)" else "Off (requires manual enable)",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = if (hasNotificationAccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                                    )
                                                }
                                                if (!hasNotificationAccess) {
                                                    TextButton(onClick = {
                                                        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                                        startActivity(intent)
                                                    }) {
                                                        Text("Enable")
                                                    }
                                                }
                                            }

                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                val hasPostNotif by hasPostNotificationsState
                                                Spacer(modifier = Modifier.height(8.dp))
                                                HorizontalDivider()
                                                Spacer(modifier = Modifier.height(8.dp))

                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text("Weekly Briefing Alerts", style = MaterialTheme.typography.bodyMedium)
                                                        Text(
                                                            if (hasPostNotif) "Active (delivers Sunday analysis)" else "Off (requires permission)",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = if (hasPostNotif) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                                        )
                                                    }
                                                    if (!hasPostNotif) {
                                                        TextButton(onClick = {
                                                            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                                        }) {
                                                            Text("Enable")
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    // Gemini 3.8 Flash Analysis Button
                                    Button(
                                        onClick = {
                                            if (apiKey.isBlank()) {
                                                Toast.makeText(this@MainActivity, "Please configure your Gemini API key first", Toast.LENGTH_SHORT).show()
                                                return@Button
                                            }
                                            isAnalyzing = true
                                            lifecycleScope.launch {
                                                try {
                                                    val summary = OnDeviceSynthesizer.buildTelemetrySummary(this@MainActivity)
                                                    val result = GeminiClient.analyzeTelemetry(apiKey.trim(), summary)
                                                    analysisResult = result
                                                    prefs.edit().putString(TelemetryConfig.KEY_LATEST_DIGEST, result).apply()

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

                                                    Toast.makeText(this@MainActivity, "Executive Digest Generated & Saved!", Toast.LENGTH_SHORT).show()
                                                } catch (e: Exception) {
                                                    analysisResult = "Analysis failed: ${e.message}"
                                                } finally {
                                                    isAnalyzing = false
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = !isAnalyzing && eventCount > 0
                                    ) {
                                        Text(if (isAnalyzing) "Analyzing via Gemini 3.8 Flash..." else "Synthesize Weekly Digest Now")
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    // Display Analysis Result (Executive Briefing Card)
                                    if (analysisResult != null) {
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                                            )
                                        ) {
                                            Column(modifier = Modifier.padding(16.dp)) {
                                                Text(
                                                    text = "Latest Executive Digest",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Text(
                                                    text = analysisResult ?: "",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontFamily = FontFamily.Default
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(16.dp))
                                    }

                                    // Export Vault ShareSheet button
                                    OutlinedButton(
                                        onClick = {
                                            isExporting = true
                                            lifecycleScope.launch {
                                                try {
                                                    val file = DataExporter.exportTelemetryJson(this@MainActivity)
                                                    val shareIntent = DataExporter.createShareIntent(this@MainActivity, file)
                                                    startActivity(Intent.createChooser(shareIntent, "Export Telemetry Vault"))
                                                } catch (e: Exception) {
                                                    Toast.makeText(this@MainActivity, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                                } finally {
                                                    isExporting = false
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = !isExporting && (eventCount > 0 || savedDigests.isNotEmpty())
                                    ) {
                                        Text(if (isExporting) "Exporting Vault..." else "Export Telemetry Vault (JSON)")
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

                            1 -> {
                                // TAB 1: ASK GEMINI INTERACTIVE CHAT
                                Column(modifier = Modifier.fillMaxSize()) {
                                    // Starter prompt chips
                                    val promptChips = listOf(
                                        "What's my biggest distraction spiral?",
                                        "Am I using my phone in the dark before bed?",
                                        "What percentage of unlocks are compulsive checks?",
                                        "Which apps trigger my longest sessions?"
                                    )

                                    LazyRow(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        items(promptChips) { chipText ->
                                            SuggestionChip(
                                                onClick = { sendChatQuery(chipText) },
                                                label = { Text(chipText, style = MaterialTheme.typography.labelSmall) }
                                            )
                                        }
                                    }

                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                                    val chatListState = rememberLazyListState()
                                    LaunchedEffect(chatMessages.size) {
                                        if (chatMessages.isNotEmpty()) {
                                            chatListState.animateScrollToItem(chatMessages.size - 1)
                                        }
                                    }

                                    LazyColumn(
                                        state = chatListState,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        items(chatMessages) { msg ->
                                            val isUser = msg.first == "user"
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                                            ) {
                                                Card(
                                                    modifier = Modifier.widthIn(max = 300.dp),
                                                    colors = CardDefaults.cardColors(
                                                        containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                                    )
                                                ) {
                                                    Column(modifier = Modifier.padding(12.dp)) {
                                                        Text(
                                                            text = if (isUser) "You" else "Gemini Behavioral Partner",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
                                                        )
                                                        Spacer(modifier = Modifier.height(4.dp))
                                                        Text(
                                                            text = msg.second,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        if (isChatLoading) {
                                            item {
                                                Row(
                                                    modifier = Modifier.padding(vertical = 8.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(16.dp),
                                                        strokeWidth = 2.dp
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = "Analyzing your telemetry data...",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.secondary
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedTextField(
                                            value = chatInput,
                                            onValueChange = { chatInput = it },
                                            placeholder = { Text("Ask about your habits...") },
                                            modifier = Modifier.weight(1f),
                                            maxLines = 3,
                                            enabled = !isChatLoading
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Button(
                                            onClick = { sendChatQuery(chatInput) },
                                            enabled = chatInput.isNotBlank() && !isChatLoading
                                        ) {
                                            Text("Send")
                                        }
                                    }
                                }
                            }

                            2 -> {
                                // TAB 2: DIGEST ARCHIVE & DATA SOVEREIGNTY VAULT
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp)
                                ) {
                                    // Data Sovereignty & Housekeeping Card
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    ) {
                                        Column(modifier = Modifier.padding(14.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "Data Sovereignty & Vault",
                                                    style = MaterialTheme.typography.titleSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                Badge(
                                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                                ) {
                                                    Text(
                                                        text = "${savedDigests.size} Digests | $eventCount Events",
                                                        style = MaterialTheme.typography.labelSmall
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "All telemetry and digests remain strictly on-device. Export or prune raw events anytime.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(modifier = Modifier.height(10.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Button(
                                                    onClick = {
                                                        lifecycleScope.launch {
                                                            try {
                                                                val file = DataExporter.exportTelemetryJson(this@MainActivity)
                                                                val shareIntent = DataExporter.createShareIntent(this@MainActivity, file)
                                                                startActivity(Intent.createChooser(shareIntent, "Export Telemetry Vault"))
                                                            } catch (e: Exception) {
                                                                Toast.makeText(this@MainActivity, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                                            }
                                                        }
                                                    },
                                                    modifier = Modifier.weight(1f),
                                                    enabled = eventCount > 0 || savedDigests.isNotEmpty()
                                                ) {
                                                    Text("Export Vault", style = MaterialTheme.typography.labelMedium)
                                                }

                                                OutlinedButton(
                                                    onClick = {
                                                        lifecycleScope.launch {
                                                            val thirtyDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
                                                            val deleted = db.telemetryDao().deleteOldEvents(thirtyDaysAgo)
                                                            Toast.makeText(this@MainActivity, "Pruned $deleted events older than 30 days. Saved digests intact.", Toast.LENGTH_SHORT).show()
                                                        }
                                                    },
                                                    modifier = Modifier.weight(1f),
                                                    enabled = eventCount > 0
                                                ) {
                                                    Text("Prune (>30d)", style = MaterialTheme.typography.labelMedium)
                                                }
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    if (savedDigests.isEmpty()) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .weight(1f),
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
                                                .fillMaxWidth()
                                                .weight(1f),
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
                                                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val targetTab = intent.getIntExtra(WeeklyDigestWorker.EXTRA_TARGET_TAB, -1)
        if (targetTab in 0..2) {
            selectedTabIndexState.value = targetTab
        }
    }

    override fun onResume() {
        super.onResume()
        hasUsageAccessState.value = UsageStatsHelper.hasUsagePermission(this)
        hasNotificationAccessState.value = hasNotificationPermission(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPostNotificationsState.value = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            hasPostNotificationsState.value = true
        }
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
