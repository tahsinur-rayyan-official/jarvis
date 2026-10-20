package com.jarvis.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Llm {

    data class Result(
        val message: JSONObject?,
        val error: String?,
        val code: Int = 200,
        val body: String = ""
    )

    /** Tries each model in turn; falls back to a no-tools call if the model rejects tools. */
    fun chat(
        baseUrl: String,
        key: String,
        models: List<String>,
        messages: JSONArray,
        tools: JSONArray?
    ): Result {
        var last = Result(null, "Something went wrong.")
        for (m in models) {
            var r = once(baseUrl, key, m, messages, tools)
            if (r.message == null && tools != null && r.code == 400 &&
                r.body.contains("tool", ignoreCase = true)
            ) {
                r = once(baseUrl, key, m, messages, null)
            }
            val modelProblem = r.code == 404 ||
                (r.code == 400 && r.body.contains("model", ignoreCase = true) &&
                    !r.body.contains("tool", ignoreCase = true))
            if (r.message != null || !modelProblem) return r
            last = r
        }
        return last
    }

    private fun once(
        baseUrl: String,
        key: String,
        model: String,
        messages: JSONArray,
        tools: JSONArray?
    ): Result {
        return try {
            val conn = URL("$baseUrl/chat/completions").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("Authorization", "Bearer $key")
                conn.setRequestProperty("User-Agent", "JarvisApp/2.0")

                val body = JSONObject()
                    .put("model", model)
                    .put("messages", messages)
                    .put("temperature", 0.6)
                if (tools != null && tools.length() > 0) {
                    body.put("tools", tools)
                    body.put("tool_choice", "auto")
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code !in 200..299) {
                    return Result(null, errorMessage(code, text), code, text)
                }
                val msg = JSONObject(text)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                if (msg == null) {
                    Result(null, "The AI service returned an empty reply.", code, text)
                } else {
                    Result(msg, null, code, text)
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Result(null, "Can't reach the AI service. Check your internet connection.")
        }
    }

    fun errorMessage(code: Int, body: String): String = when (code) {
        401, 403 -> "The API key was rejected. Open Settings and check that it's pasted correctly."
        404 -> "The model name or base URL looks wrong. Check Settings."
        429 -> "The free usage limit is reached for now. Wait a bit, or switch provider in Settings."
        400 -> "The AI service rejected the request: " + detail(body)
        else -> "The AI service had a problem (HTTP $code)."
    }

    private fun detail(body: String): String = try {
        JSONObject(body).optJSONObject("error")?.optString("message")?.take(200) ?: body.take(160)
    } catch (e: Exception) {
        body.take(160)
    }

    fun textMessage(role: String, content: String): JSONObject =
        JSONObject().put("role", role).put("content", content)

    fun imageMessage(text: String, jpegBase64: String): JSONObject {
        val parts = JSONArray()
            .put(JSONObject().put("type", "text").put("text", text))
            .put(
                JSONObject().put("type", "image_url").put(
                    "image_url",
                    JSONObject().put("url", "data:image/jpeg;base64,$jpegBase64")
                )
            )
        return JSONObject().put("role", "user").put("content", parts)
    }
}
