package com.jarvis.assistant

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Provider(
    val name: String,
    val baseUrl: String,
    val models: List<String>,
    val visionModel: String
)

val PROVIDERS: List<Provider> = listOf(
    Provider(
        "Groq",
        "https://api.groq.com/openai/v1",
        listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "openai/gpt-oss-20b"),
        "meta-llama/llama-4-scout-17b-16e-instruct"
    ),
    Provider(
        "OpenRouter",
        "https://openrouter.ai/api/v1",
        listOf("openrouter/free", "meta-llama/llama-3.3-70b-instruct:free"),
        "google/gemma-3-27b-it:free"
    ),
    Provider(
        "Gemini",
        "https://generativelanguage.googleapis.com/v1beta/openai",
        listOf("gemini-2.5-flash", "gemini-3-flash-preview"),
        "gemini-2.5-flash"
    ),
    Provider("Custom", "", listOf(""), "")
)

data class Note(val id: Long, val text: String, val createdAt: Long)

data class Task(val id: Long, val text: String, val done: Boolean)

class Store(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    private var idCounter = 0L

    fun newId(): Long {
        idCounter++
        return System.currentTimeMillis() * 100 + (idCounter % 100)
    }

    private fun s(k: String, d: String = ""): String = prefs.getString(k, d) ?: d
    private fun put(k: String, v: String) {
        prefs.edit().putString(k, v).apply()
    }

    private fun b(k: String, d: Boolean): Boolean = prefs.getBoolean(k, d)
    private fun putB(k: String, v: Boolean) {
        prefs.edit().putBoolean(k, v).apply()
    }

    private fun f(k: String, d: Float): Float = prefs.getFloat(k, d)
    private fun putF(k: String, v: Float) {
        prefs.edit().putFloat(k, v).apply()
    }

    // ------------------------------------------------------------ AI provider

    var provider: String
        get() = s("provider", "Groq")
        set(v) = put("provider", v)

    fun currentProvider(): Provider = PROVIDERS.firstOrNull { it.name == provider } ?: PROVIDERS[0]

    fun key(p: String): String = s("key_$p").trim()
    fun setKey(p: String, v: String) = put("key_$p", v.trim())

    fun model(p: Provider): String = s("model_${p.name}", p.models[0]).trim().ifEmpty { p.models[0] }
    fun setModel(p: Provider, v: String) = put("model_${p.name}", v.trim())

    fun visionModel(p: Provider): String =
        s("vmodel_${p.name}", p.visionModel).trim().ifEmpty { p.visionModel.ifEmpty { model(p) } }

    fun setVisionModel(p: Provider, v: String) = put("vmodel_${p.name}", v.trim())

    fun baseUrl(p: Provider): String =
        s("url_${p.name}", p.baseUrl).trim().trimEnd('/').ifEmpty { p.baseUrl }

    fun setBaseUrl(p: Provider, v: String) = put("url_${p.name}", v.trim())

    var toolsEnabled: Boolean
        get() = b("tools_enabled", true)
        set(v) = putB("tools_enabled", v)

    // ------------------------------------------------------------ voice

    var ttsEngine: String
        get() = s("tts_engine", "auto")
        set(v) = put("tts_engine", v)

    var groqVoice: String
        get() = s("groq_voice", "daniel")
        set(v) = put("groq_voice", v)

    var geminiVoice: String
        get() = s("gemini_voice", "Kore")
        set(v) = put("gemini_voice", v)

    var geminiTtsModel: String
        get() = s("gemini_tts_model", "gemini-2.5-flash-preview-tts").trim()
            .ifEmpty { "gemini-2.5-flash-preview-tts" }
        set(v) = put("gemini_tts_model", v.trim())

    var deviceVoice: String
        get() = s("device_voice")
        set(v) = put("device_voice", v)

    var speechRate: Float
        get() = f("speech_rate", 1.0f)
        set(v) = putF("speech_rate", v)

    var pitch: Float
        get() = f("pitch", 1.0f)
        set(v) = putF("pitch", v)

    var speakReplies: Boolean
        get() = b("speak_replies", true)
        set(v) = putB("speak_replies", v)

    var handsFree: Boolean
        get() = b("hands_free", false)
        set(v) = putB("hands_free", v)

    var autoListen: Boolean
        get() = b("auto_listen", false)
        set(v) = putB("auto_listen", v)

    var sttLang: String
        get() = s("stt_lang", "en-US")
        set(v) = put("stt_lang", v)

    // ------------------------------------------------------------ assistant

    var userName: String
        get() = s("user_name")
        set(v) = put("user_name", v.trim())

    var instructions: String
        get() = s("instructions")
        set(v) = put("instructions", v)

    var homeCity: String
        get() = s("home_city")
        set(v) = put("home_city", v.trim())

    var countryCode: String
        get() = s("country_code", "880")
        set(v) = put("country_code", v.filter { it.isDigit() })

    var directCall: Boolean
        get() = b("direct_call", false)
        set(v) = putB("direct_call", v)

    // ------------------------------------------------------------ notes

    fun loadNotes(): MutableList<Note> {
        val out = ArrayList<Note>()
        try {
            val a = JSONArray(s("notes", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Note(o.getLong("id"), o.getString("text"), o.optLong("t")))
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun saveNotes(list: List<Note>) {
        val a = JSONArray()
        for (n in list) {
            a.put(JSONObject().put("id", n.id).put("text", n.text).put("t", n.createdAt))
        }
        put("notes", a.toString())
    }

    // ------------------------------------------------------------ tasks

    fun loadTasks(): MutableList<Task> {
        val out = ArrayList<Task>()
        try {
            val a = JSONArray(s("tasks", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Task(o.getLong("id"), o.getString("text"), o.optBoolean("done")))
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun saveTasks(list: List<Task>) {
        val a = JSONArray()
        for (t in list) {
            a.put(JSONObject().put("id", t.id).put("text", t.text).put("done", t.done))
        }
        put("tasks", a.toString())
    }

    // ------------------------------------------------------------ memories

    fun loadMemories(): MutableList<String> {
        val out = ArrayList<String>()
        try {
            val a = JSONArray(s("memories", "[]"))
            for (i in 0 until a.length()) out.add(a.getString(i))
        } catch (e: Exception) {
        }
        return out
    }

    fun saveMemories(list: List<String>) {
        val a = JSONArray()
        for (m in list) a.put(m)
        put("memories", a.toString())
    }

    // ------------------------------------------------------------ chat history

    fun loadHistory(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        try {
            val a = JSONArray(s("history", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Pair(o.getString("r"), o.getString("t")))
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun saveHistory(list: List<Pair<String, String>>) {
        val a = JSONArray()
        for ((r, t) in list) {
            a.put(JSONObject().put("r", r).put("t", t))
        }
        put("history", a.toString())
    }
}
