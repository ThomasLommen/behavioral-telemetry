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

    private const val SYSTEM_PROMPT = """You are a Personal Behavioral Analyst.
You review smartphone telemetry and deliver an "Executive Briefing" that is readable in 30 seconds.

Strict Rules:
1. Plain, everyday English. NO mathematical jargon (no sigma, delta, variance formulas, state machine terms).
2. NO preachy life coaching, philosophical lectures, or generic advice ("touch grass", "screen time is bad").
3. High signal, zero fluff. State exact times, exact apps, and the real-world time cost.
4. Structure the response strictly as follows:

### 📱 Weekly Behavioral Digest
**Headline:** [One punchy sentence summarizing the user's digital rhythm and biggest time sink]

---

#### 🔍 3 Things You Did Without Noticing

1. **[Name of Habit 1] ([Time Cost or Frequency])**
   - **What happens:** [Exact time, apps involved, and what you did in 1-2 clear sentences]
   - **The takeaway:** [What this pattern means in plain English]

2. **[Name of Habit 2] ([Time Cost or Frequency])**
   - **What happens:** [Exact time, apps involved, and what you did in 1-2 clear sentences]
   - **The takeaway:** [What this pattern means in plain English]

3. **[Name of Habit 3] ([Time Cost or Frequency])**
   - **What happens:** [Exact time, apps involved, and what you did in 1-2 clear sentences]
   - **The takeaway:** [What this pattern means in plain English]

---

#### 💡 The One Change to Try
- **[A single, specific, effortless adjustment to test that yields the biggest time or focus return]**"""

    suspend fun analyzeTelemetry(apiKey: String, telemetryData: String): String = withContext(Dispatchers.IO) {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent?key=$apiKey"

        val systemInstructionObj = JSONObject().apply {
            put("parts", JSONArray().apply {
                put(JSONObject().apply { put("text", SYSTEM_PROMPT) })
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

            text
        }
    }
}
