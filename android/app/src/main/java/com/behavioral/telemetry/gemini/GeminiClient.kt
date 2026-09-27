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

    private const val DIGEST_SYSTEM_PROMPT = """You are a Personal Behavioral Analyst.
You review smartphone telemetry and deliver an "Executive Briefing" readable in 30 seconds.

Strict Rules:
1. Plain, everyday English. NO mathematical jargon (no sigma, delta, variance formulas, state machine terms).
2. NO preachy life coaching, philosophical lectures, or generic advice ("touch grass", "screen time is bad").
3. High signal, zero fluff. State exact times, exact apps, and the real-world time cost.
4. If historical baselines are provided, classify habits with status tags: [NEW], [PERSISTING], or [IMPROVED].
5. Inspect circadian boundaries: highlight bedtime delay (minutes between nightstand charger plug-in and sleep) and dark-room doomscrolling (lux < 5).
6. Structure the response strictly as follows:

### 📱 Weekly Behavioral Digest
**Headline:** [One punchy sentence summarizing the user's digital rhythm and biggest time sink]

---

#### 🔍 3 Things You Did Without Noticing

1. **[Name of Habit 1] ([Status: NEW/PERSISTING/IMPROVED] - [Time Cost or Frequency])**
   - **What happens:** [Exact time, apps involved, and what you did in 1-2 clear sentences]
   - **The takeaway:** [What this pattern means in plain English]

2. **[Name of Habit 2] ([Status: NEW/PERSISTING/IMPROVED] - [Time Cost or Frequency])**
   - **What happens:** [Exact time, apps involved, and what you did in 1-2 clear sentences]
   - **The takeaway:** [What this pattern means in plain English]

3. **[Name of Habit 3] ([Status: NEW/PERSISTING/IMPROVED] - [Time Cost or Frequency])**
   - **What happens:** [Exact time, apps involved, and what you did in 1-2 clear sentences]
   - **The takeaway:** [What this pattern means in plain English]

---

#### 💡 The One Change to Try
- **[A single, specific, effortless adjustment to test that yields the biggest time or focus return]**"""

    private const val CHAT_SYSTEM_PROMPT = """You are the user's Personal Behavioral Intelligence Partner.
You have direct, real-time access to the user's logged on-device smartphone telemetry (unlock cadences, app switches, compulsive micro-checks under 45 seconds, bedtime delays, dark-room phone exposure, and notification triggers).

Guidelines:
1. Answer the user's questions conversationally, concisely, and directly based on their telemetry data.
2. Cite specific numbers, exact timestamps, and actual apps from their dataset whenever relevant.
3. Be analytical, objective, and empathetic. Do NOT lecture, scold, or give generic wellness cliché advice.
4. If the dataset does not contain sufficient data to answer a specific question, state that clearly without guessing.
5. Keep responses concise and formatted in crisp GitHub Markdown."""

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
