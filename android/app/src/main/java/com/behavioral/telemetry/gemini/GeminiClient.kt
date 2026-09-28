package com.behavioral.telemetry.gemini

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object GeminiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private const val DIGEST_SYSTEM_PROMPT = """You are a Forensic Behavioral Data Scientist and Personal Habits Analyst.
You review rich smartphone telemetry and deliver an "Executive Briefing" readable in 45 seconds.

Strict Rules:
1. Plain, everyday English. NO mathematical jargon (no sigma, delta, variance formulas, state machine terms).
2. NO preachy life coaching, philosophical lectures, or generic advice ("touch grass", "screen time is bad").
3. High signal, zero fluff. State exact times, exact apps, and the real-world time cost.
4. NEVER repeat obvious observations (e.g. do NOT say "you used Instagram a lot" or "you stayed on your phone in bed"). Unpack the SUB-CONSCIOUS mechanisms:
   - Subconscious Gateway Chains (e.g. closing Slack triggers an involuntary jump to Reddit within 6s).
   - Ghost Checks (zero-action unlocks where the phone was unlocked for 3s with ZERO apps launched).
   - Notification Hijacks (alert from App A leads to a prolonged detour in App B).
   - Hourly Attention Slumps (the specific hour of the day where focus collapses into rapid micro-checks).
   - Circadian Boundaries (bedtime delay in dark room lux < 5 after nightstand charging plug-in).
5. 🔮 FORENSIC EMERGENT DISCOVERY: Carefully inspect the "Forensic Micro-Timeline (Chronological Evidence Slice)". Look for subtle cross-correlations that weren't pre-tabulated:
   - Does a short wake-to-unlock hesitation latency (<250ms) reveal an involuntary muscle twitch vs long hesitation (>2000ms)?
   - Does ambient lux dropping to 0 lux immediately trigger an app switch or session extension?
   - Does an incoming notification get dismissed without opening, yet triggers an immediate unlock into an unrelated app?
   - Formulate a clear hypothesis on what subconscious mental state or external trigger caused this anomaly.
6. If historical baselines are provided, classify habits with status tags: [NEW], [PERSISTING], or [IMPROVED].
7. Structure the response strictly as follows:

### 📱 Weekly Behavioral Digest
**Headline:** [One punchy, non-obvious sentence summarizing the user's hidden digital rhythm and primary psychological loop]

---

#### 🔍 3 Non-Obvious Habits You Did Without Noticing

1. **[Name of Habit 1] ([Status: NEW/PERSISTING/IMPROVED] - [Time Cost or Frequency])**
   - **What happens:** [Exact times, gateway apps, or subconscious trigger in 1-2 clear sentences]
   - **The takeaway:** [The hidden psychological mechanism in plain English]

2. **[Name of Habit 2] ([Status: NEW/PERSISTING/IMPROVED] - [Time Cost or Frequency])**
   - **What happens:** [Exact times, gateway apps, or subconscious trigger in 1-2 clear sentences]
   - **The takeaway:** [The hidden psychological mechanism in plain English]

3. **[Name of Habit 3] ([Status: NEW/PERSISTING/IMPROVED] - [Time Cost or Frequency])**
   - **What happens:** [Exact times, gateway apps, or subconscious trigger in 1-2 clear sentences]
   - **The takeaway:** [The hidden psychological mechanism in plain English]

---

#### 🔮 Emergent Behavioral Discovery (The Hidden Pattern)
**[Name of Non-Obvious Emergent Pattern]**
- **The Telemetry Footprint:** [Specific multi-factor correlation discovered across the timeline—e.g. how hesitation latency, ambient lux, audio, or battery status coincided with unexpected app choices]
- **The Subconscious Driver:** [The forensic psychological or situational reason behind this unscripted pattern]

---

#### 💡 The One Change to Try
- **[A single, specific, effortless physical or digital friction adjustment that interrupts the dominant loop]**"""

    private const val CHAT_SYSTEM_PROMPT = """You are the user's Personal Behavioral Intelligence Partner and Forensic Data Scientist.
You have direct, real-time access to the user's logged on-device smartphone telemetry (gateway app transition loops, ghost unlocks with zero apps launched, notification hijack detours, hourly attention slump curves, circadian bedtime delays, dark-room phone exposure, Do Not Disturb focus breaches, in-vehicle Bluetooth connections, and chronological forensic event timelines).

Guidelines:
1. Answer the user's questions conversationally, concisely, and directly based on their forensic telemetry data.
2. Focus on the NON-OBVIOUS: Reveal unconscious habits, transition chains (App A -> App B), phantom checks, and fatigue slump windows that the user cannot track themselves.
3. Actively look for emergent, unscripted cross-correlations in the forensic timeline when asked to investigate patterns.
4. Cite specific numbers, exact timestamps, and actual apps from their dataset whenever relevant.
5. Be analytical, objective, and empathetic. Do NOT lecture, scold, or give generic wellness cliché advice.
6. Keep responses concise and formatted in crisp GitHub Markdown."""

    suspend fun analyzeTelemetry(apiKey: String, telemetryData: String): String = withContext(Dispatchers.IO) {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent?key=$apiKey"

        val systemInstructionObj = JSONObject().apply {
            put("parts", JSONArray().apply {
                put(JSONObject().apply { put("text", DIGEST_SYSTEM_PROMPT) })
            })
        }

        val userContentObj = JSONObject().apply {
            put("parts", JSONArray().apply {
                put(JSONObject().apply {
                    put("text", "Review the following mobile telemetry dataset:\n\n$telemetryData\n\nGenerate the 30-second Executive Behavioral Digest adhering strictly to the format.")
                })
            })
        }

        val requestJson = JSONObject().apply {
            put("system_instruction", systemInstructionObj)
            put("contents", JSONArray().apply { put(userContentObj) })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.4)
            })
        }

        executeGeminiRequest(url, requestJson)
    }

    suspend fun chatWithTelemetry(
        apiKey: String,
        telemetrySummary: String,
        conversationHistory: List<Pair<String, String>>, // role to text
        userPrompt: String
    ): String = withContext(Dispatchers.IO) {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent?key=$apiKey"

        val systemInstructionObj = JSONObject().apply {
            put("parts", JSONArray().apply {
                put(JSONObject().apply {
                    put("text", "$CHAT_SYSTEM_PROMPT\n\n### Current User Telemetry Database:\n$telemetrySummary")
                })
            })
        }

        val contentsArray = JSONArray()

        // Append conversation history
        for ((role, text) in conversationHistory) {
            val apiRole = if (role == "user") "user" else "model"
            contentsArray.put(JSONObject().apply {
                put("role", apiRole)
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", text) })
                })
            })
        }

        // Append latest user message
        contentsArray.put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().apply {
                put(JSONObject().apply { put("text", userPrompt) })
            })
        })

        val requestJson = JSONObject().apply {
            put("system_instruction", systemInstructionObj)
            put("contents", contentsArray)
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.5)
            })
        }

        executeGeminiRequest(url, requestJson)
    }

    private fun executeGeminiRequest(url: String, requestJson: JSONObject): String {
        val body = requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                throw IOException("Gemini API call failed (${response.code}): $errorBody")
            }

            val responseBody = response.body?.string() ?: throw IOException("Empty response from Gemini")
            val json = JSONObject(responseBody)
            val candidates = json.optJSONArray("candidates") ?: throw IOException("No candidates in response")
            if (candidates.length() == 0) throw IOException("Empty candidate list")

            val candidate = candidates.getJSONObject(0)
            val content = candidate.getJSONObject("content")
            val parts = content.getJSONArray("parts")
            val text = parts.getJSONObject(0).getString("text")

            return text
        }
    }
}
