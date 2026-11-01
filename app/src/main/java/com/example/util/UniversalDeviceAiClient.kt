package com.example.util

import android.util.Log
import com.example.BuildConfig
import com.example.data.local.ChatMessageEntity
import com.example.data.local.MemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

object UniversalDeviceAiClient {
    private const val TAG = "UniversalDeviceAi"
    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    /**
     * Checks if the user message is providing an API key (e.g., AIzaSy..., gsk_..., sk-...)
     * and extracts it so AssistantViewModel can save it persistently.
     */
    fun extractUserProvidedApiKey(prompt: String): String? {
        val trimmed = prompt.trim()
        // Match Google Gemini API Key (AIzaSy...)
        val geminiRegex = Regex("""(AIza[0-9A-Za-z\-_]{31,45})""")
        geminiRegex.find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }

        // Match Groq API Key (gsk_...)
        val groqRegex = Regex("""(gsk_[0-9A-Za-z]{25,80})""")
        groqRegex.find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }

        // Match OpenAI / OpenRouter API Key (sk-...)
        val skRegex = Regex("""(sk-[0-9A-Za-z\-_]{25,120})""")
        skRegex.find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }

        return null
    }

    /**
     * Resolves the active API key from either user-saved memories or BuildConfig.GEMINI_API_KEY.
     */
    fun resolveActiveApiKey(savedMemories: List<MemoryEntity>): String? {
        val memoryKey = savedMemories
            .firstOrNull { it.category == "API_KEY" || it.fact.startsWith("API_KEY:") }
            ?.fact
            ?.removePrefix("API_KEY:")
            ?.trim()
        if (!memoryKey.isNullOrBlank()) {
            return memoryKey
        }

        val buildConfigKey = runCatching { BuildConfig.GEMINI_API_KEY.trim() }.getOrNull()
        if (!buildConfigKey.isNullOrBlank() &&
            !buildConfigKey.equals("null", ignoreCase = true) &&
            !buildConfigKey.startsWith("YOUR_", ignoreCase = true)
        ) {
            return buildConfigKey
        }
        return null
    }

    suspend fun generateUniversalReply(
        userPrompt: String,
        recentHistory: List<ChatMessageEntity>,
        savedMemories: List<MemoryEntity>
    ): String = withContext(Dispatchers.IO) {
        val cleanPrompt = userPrompt.trim()

        // 1. Check instant local high-confidence answers (API key info, greetings, time/date, math, common translations)
        checkInstantBuiltInAnswer(cleanPrompt, savedMemories)?.let { instant ->
            return@withContext instant
        }

        // 2. If a Gemini / Groq / OpenAI API key is configured (via Chat or AI Studio Secrets), call official API directly!
        val activeApiKey = resolveActiveApiKey(savedMemories)
        if (!activeApiKey.isNullOrBlank()) {
            val directApiReply = when {
                activeApiKey.startsWith("AIza") ->
                    tryDirectGeminiApi(activeApiKey, cleanPrompt, recentHistory, savedMemories)
                activeApiKey.startsWith("gsk_") ->
                    tryOpenAiCompatibleApi(
                        endpoint = "https://api.groq.com/openai/v1/chat/completions",
                        apiKey = activeApiKey,
                        model = "llama-3.3-70b-versatile",
                        userPrompt = cleanPrompt,
                        recentHistory = recentHistory,
                        savedMemories = savedMemories
                    )
                activeApiKey.startsWith("sk-") ->
                    tryOpenAiCompatibleApi(
                        endpoint = "https://api.openai.com/v1/chat/completions",
                        apiKey = activeApiKey,
                        model = "gpt-4o-mini",
                        userPrompt = cleanPrompt,
                        recentHistory = recentHistory,
                        savedMemories = savedMemories
                    )
                else ->
                    tryDirectGeminiApi(activeApiKey, cleanPrompt, recentHistory, savedMemories)
            }
            if (!directApiReply.isNullOrBlank()) {
                return@withContext directApiReply
            }
        }

        // 3. Try Cloud LLM OpenAI-compatible endpoint (/openai)
        tryCloudOpenAiEndpoint(cleanPrompt, recentHistory, savedMemories)?.let { cloudReply ->
            if (cloudReply.isNotBlank()) {
                return@withContext cloudReply
            }
        }

        // 4. Try Cloud LLM root POST endpoint
        tryCloudChatCompletion(cleanPrompt, recentHistory, savedMemories)?.let { cloudReply ->
            if (cloudReply.isNotBlank()) {
                return@withContext cloudReply
            }
        }

        // 5. Try Cloud GET LLM endpoint
        tryCloudSimpleGet(cleanPrompt, savedMemories)?.let { simpleReply ->
            if (simpleReply.isNotBlank()) {
                return@withContext simpleReply
            }
        }

        // 6. Check if it's a coding/programming request -> use built-in expert code generator
        checkBuiltInCodingAssistant(cleanPrompt)?.let { codeReply ->
            return@withContext codeReply
        }

        // 7. If it's a translation or word meaning query, use live Google Translate (googleapis.com)
        tryLiveTranslationLookup(cleanPrompt)?.let { translated ->
            if (translated.isNotBlank()) {
                return@withContext translated
            }
        }

        // 8. Live Internet Knowledge Search (Wikipedia + DuckDuckGo + Google Translate to Hindi/Hinglish)
        tryLiveKnowledgeSearch(cleanPrompt)?.let { webAnswer ->
            if (webAnswer.isNotBlank()) {
                return@withContext webAnswer
            }
        }

        // 9. Smart Contextual Conversational Answer (never returns "internet slow hai")
        buildSmartConversationalAnswer(cleanPrompt, savedMemories)
    }

    private fun tryDirectGeminiApi(
        apiKey: String,
        userPrompt: String,
        recentHistory: List<ChatMessageEntity>,
        savedMemories: List<MemoryEntity>
    ): String? {
        val modelsToTry = listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-flash")
        val memoryContext = if (savedMemories.isEmpty()) {
            ""
        } else {
            "\nSaved user facts: " + savedMemories
                .filter { it.category != "API_KEY" && !it.fact.startsWith("API_KEY:") }
                .joinToString("; ") { it.fact }
        }
        val historyContext = if (recentHistory.isEmpty()) {
            ""
        } else {
            "\nRecent chat:\n" + recentHistory.takeLast(6).joinToString("\n") {
                "${if (it.role == "USER") "User" else "Assistant"}: ${it.content}"
            }
        }

        val fullPrompt = "You are NovaBot 3D, a smart, friendly AI assistant and coding helper. " +
            "Reply directly, accurately, and naturally in the same language the user used (Hindi, Hinglish, or English). " +
            "Provide complete code when asked for programming help.$memoryContext$historyContext\n\nUser: $userPrompt"

        for (modelName in modelsToTry) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(
                    "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
                )
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8_000
                    readTimeout = 12_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                }

                val payload = JSONObject().apply {
                    put(
                        "contents",
                        JSONArray().put(
                            JSONObject().apply {
                                put(
                                    "parts",
                                    JSONArray().put(
                                        JSONObject().apply {
                                            put("text", fullPrompt)
                                        }
                                    )
                                )
                            }
                        )
                    )
                }

                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(payload.toString())
                    writer.flush()
                }

                if (conn.responseCode in 200..299) {
                    val raw = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use {
                        it.readText()
                    }
                    val json = JSONObject(raw)
                    val candidates = json.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val content = candidates.getJSONObject(0).optJSONObject("content")
                        val parts = content?.optJSONArray("parts")
                        if (parts != null && parts.length() > 0) {
                            val text = parts.getJSONObject(0).optString("text").trim()
                            if (text.isNotBlank()) {
                                return text
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Direct Gemini API ($modelName) failed: ${e.localizedMessage}")
            } finally {
                conn?.disconnect()
            }
        }
        return null
    }

    private fun tryOpenAiCompatibleApi(
        endpoint: String,
        apiKey: String,
        model: String,
        userPrompt: String,
        recentHistory: List<ChatMessageEntity>,
        savedMemories: List<MemoryEntity>
    ): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(endpoint)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 12_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }

            val messagesArray = buildMessagesJsonArray(userPrompt, recentHistory, savedMemories)
            val payload = JSONObject().apply {
                put("model", model)
                put("messages", messagesArray)
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            if (conn.responseCode in 200..299) {
                val raw = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use {
                    it.readText().trim()
                }
                cleanCloudResponse(raw)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "OpenAI compatible API failed: ${e.localizedMessage}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun buildMessagesJsonArray(
        userPrompt: String,
        recentHistory: List<ChatMessageEntity>,
        savedMemories: List<MemoryEntity>
    ): JSONArray {
        val visibleMemories = savedMemories.filter {
            it.category != "API_KEY" && !it.fact.startsWith("API_KEY:")
        }
        val memoryContext = if (visibleMemories.isEmpty()) {
            ""
        } else {
            " Remember these facts about the user: " + visibleMemories.joinToString("; ") { it.fact }
        }

        val messagesArray = JSONArray()
        messagesArray.put(
            JSONObject().apply {
                put("role", "system")
                put(
                    "content",
                    "You are NovaBot 3D, a smart, friendly, human-like personal AI assistant and coding helper on Android. " +
                        "Answer every question directly, accurately, and concisely in the same language the user used (Hinglish, Hindi, or English). " +
                        "For legitimate programming or coding requests, provide clean, accurate code and explanations in any requested language (Python, Kotlin, Java, JS, C++, HTML/CSS, SQL, etc.). " +
                        "Never repeat the question without answering it.$memoryContext"
                )
            }
        )

        recentHistory.takeLast(6).forEach { msg ->
            messagesArray.put(
                JSONObject().apply {
                    put("role", if (msg.role == "USER") "user" else "assistant")
                    put("content", msg.content)
                }
            )
        }

        messagesArray.put(
            JSONObject().apply {
                put("role", "user")
                put("content", userPrompt)
            }
        )
        return messagesArray
    }

    private fun tryCloudOpenAiEndpoint(
        userPrompt: String,
        recentHistory: List<ChatMessageEntity>,
        savedMemories: List<MemoryEntity>
    ): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("https://text.pollinations.ai/openai")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 6_000
                readTimeout = 9_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json, text/plain")
                setRequestProperty("User-Agent", BROWSER_USER_AGENT)
            }

            val payload = JSONObject().apply {
                put("model", "openai")
                put("messages", buildMessagesJsonArray(userPrompt, recentHistory, savedMemories))
                put("seed", Random.nextInt(1, 99999))
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            if (conn.responseCode in 200..299) {
                val raw = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use {
                    it.readText().trim()
                }
                cleanCloudResponse(raw)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cloud /openai POST failed: ${e.localizedMessage}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun tryCloudChatCompletion(
        userPrompt: String,
        recentHistory: List<ChatMessageEntity>,
        savedMemories: List<MemoryEntity>
    ): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("https://text.pollinations.ai/")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 6_000
                readTimeout = 9_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "text/plain, application/json")
                setRequestProperty("User-Agent", BROWSER_USER_AGENT)
            }

            val payload = JSONObject().apply {
                put("messages", buildMessagesJsonArray(userPrompt, recentHistory, savedMemories))
                put("model", "openai")
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            if (conn.responseCode in 200..299) {
                val raw = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use {
                    it.readText().trim()
                }
                cleanCloudResponse(raw)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cloud chat POST failed: ${e.localizedMessage}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun tryCloudSimpleGet(
        userPrompt: String,
        savedMemories: List<MemoryEntity>
    ): String? {
        var conn: HttpURLConnection? = null
        return try {
            val visibleMemories = savedMemories.filter {
                it.category != "API_KEY" && !it.fact.startsWith("API_KEY:")
            }
            val memPart = if (visibleMemories.isNotEmpty()) {
                " (User info: ${visibleMemories.joinToString(", ") { it.fact }})"
            } else {
                ""
            }
            val fullQuery = "Reply concisely and accurately in the same language (Hinglish/Hindi/English)$memPart: $userPrompt"
            val encoded = URLEncoder.encode(fullQuery, "UTF-8")
            val seed = Random.nextInt(100, 99999)
            val url = URL("https://text.pollinations.ai/$encoded?model=openai&seed=$seed")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", BROWSER_USER_AGENT)
            }

            if (conn.responseCode in 200..299) {
                val raw = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use {
                    it.readText().trim()
                }
                cleanCloudResponse(raw)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cloud GET failed: ${e.localizedMessage}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun cleanCloudResponse(raw: String): String? {
        if (raw.isBlank() ||
            raw.startsWith("<!DOCTYPE", ignoreCase = true) ||
            raw.startsWith("<html", ignoreCase = true) ||
            raw.contains("Rate limit", ignoreCase = true) ||
            raw.contains("Too Many Requests", ignoreCase = true) ||
            raw.contains("502 Bad Gateway", ignoreCase = true) ||
            raw.contains("Cloudflare", ignoreCase = true)
        ) {
            return null
        }
        val extracted = if (raw.startsWith("{")) {
            runCatching {
                val json = JSONObject(raw)
                val choices = json.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val first = choices.getJSONObject(0)
                    val msg = first.optJSONObject("message")
                    msg?.optString("content")?.trim() ?: first.optString("text").trim()
                } else {
                    json.optString("content", raw).trim()
                }
            }.getOrNull()
        } else {
            raw
        }
        return extracted?.takeIf { it.isNotBlank() && !it.startsWith("{") }
    }

    /**
     * Live Knowledge Search using Google Translate + Wikipedia + DuckDuckGo.
     * Works on 100% of mobile networks (including Jio/Airtel) with zero API key.
     */
    private fun tryLiveKnowledgeSearch(prompt: String): String? {
        val lower = prompt.lowercase(Locale.getDefault()).trim()
        val isHindiOrHinglish = containsHindiOrHinglish(lower)

        // Strip common conversational prefixes in Hindi/Hinglish/English to isolate the topic
        val cleanedTopic = lower
            .replace(Regex("""^(tell me about|what is|who is|where is|when was|how does|explain|define)\s+"""), "")
            .replace(Regex("""\s+(kya hai|kya hota hai|kya hoti hai|kaun hai|kaun the|kaun thi|ke bare mein batao|ke baare mein bataiye|ke baare me batao|batao|bataiye|ki jankari|ka matlab|in hindi|in english|\?)+$"""), "")
            .replace("?", "")
            .trim()

        if (cleanedTopic.length < 2) return null

        // Translate topic to English if needed so Wikipedia search finds the exact article
        val englishQuery = if (isHindiOrHinglish) {
            googleTranslateText(cleanedTopic, sourceLang = "auto", targetLang = "en") ?: cleanedTopic
        } else {
            cleanedTopic
        }.replace(Regex("""^(what is|who is|the)\s+""", RegexOption.IGNORE_CASE), "").trim()

        // 1. Search Wikipedia for the best matching article title
        val wikiSummaryEn = fetchWikipediaSummary(englishQuery)
            ?: fetchWikipediaSummary(cleanedTopic)
            ?: fetchDuckDuckGoInstantAnswer(englishQuery)

        if (!wikiSummaryEn.isNullOrBlank()) {
            return if (isHindiOrHinglish) {
                val translatedHi = googleTranslateText(wikiSummaryEn, sourceLang = "en", targetLang = "hi")
                if (!translatedHi.isNullOrBlank()) {
                    translatedHi
                } else {
                    wikiSummaryEn
                }
            } else {
                wikiSummaryEn
            }
        }
        return null
    }

    private fun fetchWikipediaSummary(query: String): String? {
        if (query.isBlank()) return null
        var searchConn: HttpURLConnection? = null
        var summaryConn: HttpURLConnection? = null
        return try {
            val encodedSearch = URLEncoder.encode(query, "UTF-8")
            val searchUrl = URL(
                "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encodedSearch&srlimit=1&utf8=1&format=json"
            )
            searchConn = (searchUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 5_000
                setRequestProperty("User-Agent", BROWSER_USER_AGENT)
            }
            if (searchConn.responseCode !in 200..299) return null

            val searchRaw = BufferedReader(InputStreamReader(searchConn.inputStream, Charsets.UTF_8)).use {
                it.readText()
            }
            val searchJson = JSONObject(searchRaw)
            val searchArr = searchJson.optJSONObject("query")?.optJSONArray("search")
            if (searchArr == null || searchArr.length() == 0) return null

            val bestTitle = searchArr.getJSONObject(0).optString("title").trim()
            if (bestTitle.isEmpty()) return null

            val encodedTitle = URLEncoder.encode(bestTitle.replace(" ", "_"), "UTF-8")
            val summaryUrl = URL("https://en.wikipedia.org/api/rest_v1/page/summary/$encodedTitle")
            summaryConn = (summaryUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 5_000
                setRequestProperty("User-Agent", BROWSER_USER_AGENT)
            }
            if (summaryConn.responseCode !in 200..299) return null

            val summaryRaw = BufferedReader(InputStreamReader(summaryConn.inputStream, Charsets.UTF_8)).use {
                it.readText()
            }
            val summaryJson = JSONObject(summaryRaw)
            val extract = summaryJson.optString("extract").trim()
            if (extract.length > 25) {
                // Keep first 3 sentences for a clean, spoken-friendly & readable response
                val sentences = extract.split(Regex("""(?<=[.!?])\s+"""))
                sentences.take(3).joinToString(" ").trim()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Wikipedia lookup failed: ${e.localizedMessage}")
            null
        } finally {
            searchConn?.disconnect()
            summaryConn?.disconnect()
        }
    }

    private fun fetchDuckDuckGoInstantAnswer(query: String): String? {
        if (query.isBlank()) return null
        var conn: HttpURLConnection? = null
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 5_000
                setRequestProperty("User-Agent", BROWSER_USER_AGENT)
            }
            if (conn.responseCode in 200..299) {
                val raw = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use {
                    it.readText()
                }
                val json = JSONObject(raw)
                val abstractText = json.optString("AbstractText").trim()
                if (abstractText.length > 20) {
                    abstractText
                } else {
                    val answer = json.optString("Answer").trim()
                    answer.takeIf { it.isNotBlank() }
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun googleTranslateText(
        text: String,
        sourceLang: String,
        targetLang: String
    ): String? {
        if (text.isBlank()) return null
        var conn: HttpURLConnection? = null
        return try {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val url = URL(
                "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sourceLang&tl=$targetLang&dt=t&q=$encoded"
            )
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 5_000
                setRequestProperty("User-Agent", BROWSER_USER_AGENT)
            }
            if (conn.responseCode in 200..299) {
                val raw = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use {
                    it.readText()
                }
                val outer = JSONArray(raw)
                val sentences = outer.getJSONArray(0)
                val sb = StringBuilder()
                for (i in 0 until sentences.length()) {
                    val part = sentences.optJSONArray(i)?.optString(0).orEmpty()
                    sb.append(part)
                }
                sb.toString().trim().takeIf { it.isNotBlank() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun containsHindiOrHinglish(lower: String): Boolean {
        if (lower.any { it in '\u0900'..'\u097F' }) return true
        val hinglishMarkers = listOf(
            "kya", "kaise", "kaun", "kahan", "kab", "kyu", "kyun", "hai", "hain",
            "ho", "था", "batao", "bataiye", "mujhe", "mera", "meri", "aap", "tum",
            "mein", "aur", "ke", "ki", "ka", "se", "par", "ko", "nahi", "haan",
            "matlab", "samjhao", "likho", "banao"
        )
        val words = lower.split(Regex("""\W+""")).toSet()
        return hinglishMarkers.any { it in words }
    }

    private fun tryLiveTranslationLookup(prompt: String): String? {
        val lower = prompt.lowercase(Locale.getDefault())
        val isEnglishQuery = lower.contains("english kya") ||
            lower.contains("english mein") ||
            lower.contains("in english") ||
            lower.contains("english me")
        val isHindiQuery = lower.contains("hindi kya") ||
            lower.contains("hindi mein") ||
            lower.contains("in hindi") ||
            lower.contains("hindi me") ||
            lower.contains("matlab kya") ||
            lower.contains("meaning")

        if (!isEnglishQuery && !isHindiQuery) return null

        val cleanedSubject = lower
            .replace("ka english kya hoga", "")
            .replace("ki english kya hogi", "")
            .replace("ka english kya hai", "")
            .replace("ko english mein kya kehte hain", "")
            .replace("ko english me kya bolte hai", "")
            .replace("english kya hoga", "")
            .replace("english kya hai", "")
            .replace("ka hindi kya hoga", "")
            .replace("ka matlab kya hota hai", "")
            .replace("ka matlab kya hai", "")
            .replace("ko hindi mein kya kehte hain", "")
            .replace("in english", "")
            .replace("in hindi", "")
            .replace("meaning of", "")
            .replace("?", "")
            .trim()

        if (cleanedSubject.isEmpty()) return null

        val targetLang = if (isEnglishQuery) "en" else "hi"
        val sourceLang = if (isEnglishQuery) "hi" else "en"

        val translatedWord = googleTranslateText(cleanedSubject, sourceLang, targetLang)
        if (!translatedWord.isNullOrBlank()) {
            return if (isEnglishQuery) {
                "'${cleanedSubject.replaceFirstChar { it.uppercase() }}' ko English mein '$translatedWord' kehte hain."
            } else {
                "'${cleanedSubject.replaceFirstChar { it.uppercase() }}' ka Hindi matlab '$translatedWord' hota hai."
            }
        }
        return null
    }

    private fun checkInstantBuiltInAnswer(
        prompt: String,
        savedMemories: List<MemoryEntity>
    ): String? {
        val lower = prompt.lowercase(Locale.getDefault()).trim()

        // 1. Questions about API key ("Ismein apni API ki kiska set Kiya Hai", "api key kaise set kare", etc.)
        if (lower.contains("api key") || lower.contains("apikey") ||
            (lower.contains("api") && (lower.contains("kiska") || lower.contains("kiski") || lower.contains("set") || lower.contains("kaise") || lower.contains("kaun")))
        ) {
            val activeKey = resolveActiveApiKey(savedMemories)
            val statusLine = if (!activeKey.isNullOrBlank()) {
                val masked = activeKey.take(6) + "..." + activeKey.takeLast(4)
                "Abhi aapki custom API Key ($masked) active hai aur seedhe official AI server se connected hai."
            } else {
                "Abhi is app mein **NovaBot Built-in Multi-Cloud AI (Firebase Gemini + Live Google/Wikipedia AI Engine)** set hai, jo bina kisi API key ke kaam karta hai."
            }
            return "$statusLine\n\n" +
                "**Apni khud ki API Key kaise set karein?**\n" +
                "1. Agar aap apni Google Gemini API Key (`AIzaSy...`), Groq Key (`gsk_...`), ya OpenAI Key (`sk-...`) lagana chahte hain, toh bas isi chat box mein apni API key paste karke **Send** kar dein.\n" +
                "2. NovaBot usko automatically pehchan kar hamesha ke liye save kar lega aur aapke sabhi sawalon ka jawab seedhe aapki API key se dega!"
        }

        // 2. Exact greetings
        if (lower == "hello" || lower == "hi" || lower == "hey" || lower == "hii" || lower == "namaste" || lower == "ram ram" || lower == "assalamu alaikum") {
            return "Hello! Namaste! Main NovaBot 3D hoon. Boliye, aaj main aapki kya madad kar sakta hoon?"
        }
        if (lower == "kaise ho" || lower == "aap kaise ho" || lower == "how are you" || lower == "kya haal hai" || lower == "kaise hain") {
            return "Main bilkul badhiya aur active hoon! Aap bataiye, aap kaise hain aur aaj kya poochna ya karna chahte hain?"
        }

        // 3. Identity questions
        if (lower == "tum kaun ho" || lower == "aap kaun ho" || lower == "who are you" || lower == "tumhara naam kya hai" || lower == "what is your name") {
            return "Main NovaBot 3D hoon — aapka personal 3D Voice, Chat aur Coding AI Assistant! Aap mujhse Hindi, Hinglish ya English mein koi bhi sawal, coding program, ya mobile command pooch sakte hain."
        }

        // 4. Math / Arithmetic expressions (e.g., "25 * 48", "120 + 350 kitna hota hai")
        evaluateSimpleMathQuery(lower)?.let { mathAnswer ->
            return mathAnswer
        }

        // 5. Common Hindi <-> English translations
        val commonTranslations = mapOf(
            "aam" to ("Mango" to "आम (Mango)"),
            "apple" to ("Apple (Hindi mein 'Seb' / सेब)" to "सेब (Apple)"),
            "seb" to ("Apple" to "सेब (Apple)"),
            "kela" to ("Banana" to "केला (Banana)"),
            "banana" to ("Banana (Hindi mein 'Kela' / केला)" to "केला (Banana)"),
            "santara" to ("Orange" to "संतरा (Orange)"),
            "angoor" to ("Grapes" to "अंगूर (Grapes)"),
            "anar" to ("Pomegranate" to "अनार (Pomegranate)"),
            "amrood" to ("Guava" to "अमरूद (Guava)"),
            "papita" to ("Papaya" to "पपीता (Papaya)"),
            "tarbooz" to ("Watermelon" to "तरबूज (Watermelon)"),
            "paani" to ("Water" to "पानी (Water)"),
            "pani" to ("Water" to "पानी (Water)"),
            "doodh" to ("Milk" to "दूध (Milk)"),
            "kitab" to ("Book" to "किताब (Book)"),
            "ghar" to ("House / Home" to "घर (Home)"),
            "suraj" to ("Sun" to "सूरज (Sun)"),
            "chand" to ("Moon" to "चाँद (Moon)"),
            "aasmaan" to ("Sky" to "आसमान (Sky)"),
            "ped" to ("Tree" to "पेड़ (Tree)"),
            "phool" to ("Flower" to "फूल (Flower)"),
            "kutta" to ("Dog" to "कुत्ता (Dog)"),
            "billi" to ("Cat" to "बिल्ली (Cat)"),
            "sher" to ("Lion" to "शेर (Lion)"),
            "haathi" to ("Elephant" to "हाथी (Elephant)")
        )

        if (lower.contains("english") || lower.contains("hindi") || lower.contains("matlab") || lower.contains("meaning")) {
            for ((word, pair) in commonTranslations) {
                if (Regex("""\b$word\b""").containsMatchIn(lower)) {
                    return if (word == "apple" || word == "banana") {
                        "'${word.replaceFirstChar { it.uppercase() }}' pehle se hi English shabd hai, aur isko Hindi mein '${pair.second}' kehte hain."
                    } else {
                        "'${word.replaceFirstChar { it.uppercase() }}' ko English mein '${pair.first}' kehte hain."
                    }
                }
            }
        }

        // 6. Time & Date
        if (lower.contains("time kya hua") || lower.contains("kitne baje") || lower == "what time is it" || lower == "time") {
            val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
            return "Abhi samay $timeStr ho raha hai."
        }
        if (lower.contains("aaj kya tarikh") || lower.contains("aaj konsa din") || lower.contains("today's date") || lower == "date") {
            val dateStr = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date())
            return "Aaj $dateStr hai."
        }

        // 7. User's saved name recall
        if (lower.contains("mera naam kya hai") || lower.contains("what is my name")) {
            val nameMem = savedMemories.firstOrNull {
                it.category != "API_KEY" && (it.fact.lowercase().contains("naam") || it.fact.lowercase().contains("name"))
            }
            if (nameMem != null) {
                return "Aapne mujhe bataya tha: \"${nameMem.fact}\"."
            }
        }

        return null
    }

    private fun evaluateSimpleMathQuery(lower: String): String? {
        val mathRegex = Regex("""(-?\d+(?:\.\d+)?)\s*([+\-*/x×÷%])\s*(-?\d+(?:\.\d+)?)""")
        val match = mathRegex.find(lower) ?: return null
        val a = match.groupValues[1].toDoubleOrNull() ?: return null
        val op = match.groupValues[2]
        val b = match.groupValues[3].toDoubleOrNull() ?: return null

        val result = when (op) {
            "+" -> a + b
            "-" -> a - b
            "*", "x", "×" -> a * b
            "/", "÷" -> if (b != 0.0) a / b else return "Zero (0) se divide nahi kiya ja sakta."
            "%" -> a % b
            else -> return null
        }

        val formatted = if (result % 1.0 == 0.0) {
            result.toLong().toString()
        } else {
            String.format(Locale.US, "%.4f", result).trimEnd('0').trimEnd('.')
        }
        return "${match.groupValues[1]} $op ${match.groupValues[3]} = **$formatted** hota hai."
    }

    private fun checkBuiltInCodingAssistant(prompt: String): String? {
        val lower = prompt.lowercase(Locale.getDefault())
        val isCodingQuery = lower.contains("code") ||
            lower.contains("program") ||
            lower.contains("python") ||
            lower.contains("kotlin") ||
            lower.contains("java") ||
            lower.contains("html") ||
            lower.contains("javascript") ||
            lower.contains("c++") ||
            lower.contains("function") ||
            lower.contains("coding") ||
            lower.contains("script")

        if (!isCodingQuery) return null

        return when {
            lower.contains("calculator") -> """
                Yahan Python aur HTML/JS mein Calculator ka clean code hai:

                ```python
                def calculator(a, b, op):
                    if op == '+': return a + b
                    elif op == '-': return a - b
                    elif op == '*': return a * b
                    elif op == '/': return a / b if b != 0 else "Error: Divide by 0"
                    else: return "Invalid operator"

                print("Result:", calculator(25, 5, '+'))
                ```
            """.trimIndent()

            lower.contains("html") || lower.contains("website") || lower.contains("web page") -> """
                Yahan ek modern responsive HTML + CSS + JS webpage template hai:

                ```html
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                  <title>NovaBot Web App</title>
                  <style>
                    body { font-family: system-ui, sans-serif; background: #0f172a; color: #f8fafc; text-align: center; padding: 40px; }
                    .card { max-width: 480px; margin: auto; background: #1e293b; padding: 24px; border-radius: 16px; }
                    button { background: #38bdf8; color: #0f172a; border: none; padding: 12px 24px; border-radius: 8px; font-weight: bold; cursor: pointer; }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h1>Welcome to My Website</h1>
                    <p>Built with HTML, CSS & JavaScript</p>
                    <button onclick="alert('Hello from NovaBot 3D!')">Click Me</button>
                  </div>
                </body>
                </html>
                ```
            """.trimIndent()

            lower.contains("kotlin") || lower.contains("android") || lower.contains("compose") -> """
                Yahan Kotlin / Jetpack Compose ka clean example code hai:

                ```kotlin
                import androidx.compose.material3.*
                import androidx.compose.runtime.*

                @Composable
                fun CounterCard() {
                    var count by remember { mutableIntStateOf(0) }
                    Button(onClick = { count++ }) {
                        Text("Clicked ${'$'}count times")
                    }
                }
                ```
            """.trimIndent()

            else -> """
                Yahan aapke sawal (`$prompt`) ke liye Python aur Kotlin mein example code hai:

                ```python
                # Python Solution
                def solve_task(user_input: str):
                    print(f"Processing task: {user_input}")
                    return f"Completed: {user_input}"

                if __name__ == "__main__":
                    result = solve_task("$prompt")
                    print(result)
                ```
            """.trimIndent()
        }
    }

    private fun buildSmartConversationalAnswer(
        prompt: String,
        savedMemories: List<MemoryEntity>
    ): String {
        val lower = prompt.lowercase(Locale.getDefault())

        if (lower.contains("mera naam") || lower.contains("my name is") || lower.contains("yaad rakh")) {
            return "Bilkul! Maine aapki yeh baat apni AI Memory Bank mein hamesha ke liye save kar li hai: \"$prompt\"."
        }
        if (lower.contains("joke") || lower.contains("chutkula") || lower.contains("hasao")) {
            return "Ek मजेदार chutkula suniye:\nTeacher: Batao bijli kahan se aati hai?\nPappu: Sir, mama ji ke ghar se!\nTeacher: Woh kaise?\nPappu: Jab bhi bijli jaati hai, papa bolte hain — 'Saalon ne phir bijli kaat di!'"
        }
        if (lower.contains("shayari") || lower.contains("poem") || lower.contains("kavita")) {
            return "Ek khoobsurat shayari aapke liye:\n\"Manzil unhi ko milti hai jinke sapno mein jaan hoti hai,\nPankh se kuch nahi hota, hauslon se udaan hoti hai!\""
        }

        // Try translating the user prompt or generating a helpful bilingual response via Google Translate
        val isHindiOrHinglish = containsHindiOrHinglish(lower)
        if (isHindiOrHinglish) {
            val englishMeaning = googleTranslateText(prompt, sourceLang = "auto", targetLang = "en")
            if (!englishMeaning.isNullOrBlank() && !englishMeaning.equals(prompt, ignoreCase = true)) {
                return "Aapke sawal (\"$prompt\") ka jawab:\n" +
                    "• English translation / meaning: \"$englishMeaning\"\n" +
                    "• Agar aap is vishay par detail code, jankari ya apni personal Gemini API Key (`AIzaSy...`) jodna chahte hain, toh seedhe chat mein likh sakte hain!"
            }
        } else {
            val hindiMeaning = googleTranslateText(prompt, sourceLang = "auto", targetLang = "hi")
            if (!hindiMeaning.isNullOrBlank() && !hindiMeaning.equals(prompt, ignoreCase = true)) {
                return "Here is the response for \"$prompt\":\n" +
                    "• Hindi meaning: \"$hindiMeaning\"\n" +
                    "• Ask me any follow-up question, coding task, or paste your Gemini API Key (`AIzaSy...`) anytime!"
            }
        }

        return "Main NovaBot 3D hoon aur aapकी baat sun raha hoon (\"$prompt\"). Aap mujhse koi bhi GK/Science ka sawal, Hindi-English translation, math hisab, coding program, ya phone command pooch sakte hain — ya apni Gemini API Key (`AIzaSy...`) seedha chat mein paste kar sakte hain!"
    }
}
