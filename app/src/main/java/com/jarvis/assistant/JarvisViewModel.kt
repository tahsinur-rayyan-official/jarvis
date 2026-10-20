package com.jarvis.assistant

import android.Manifest
import android.app.Application
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.speech.SpeechRecognizer
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Status { IDLE, LISTENING, THINKING, SPEAKING }

enum class Kind { USER, ASSISTANT, ACTION, ERROR }

data class ChatMessage(val id: Long, val kind: Kind, val text: String, val image: Bitmap? = null)

data class Turn(val reply: String?, val error: String?)

class JarvisViewModel(app: Application) : AndroidViewModel(app), SpeechInput.Listener {

    val store = Store(app)

    val messages = mutableStateListOf<ChatMessage>()
    val notes = mutableStateListOf<Note>()
    val tasks = mutableStateListOf<Task>()
    val memories = mutableStateListOf<String>()

    var status by mutableStateOf(Status.IDLE)
        private set
    var partial by mutableStateOf("")
        private set
    var level by mutableStateOf(0f)
        private set
    var handsFree by mutableStateOf(store.handsFree)
        private set
    var speakReplies by mutableStateOf(store.speakReplies)
        private set
    var sttLang by mutableStateOf(store.sttLang)
        private set
    var pendingImage by mutableStateOf<Bitmap?>(null)
        private set
    var permTick by mutableStateOf(0)
        private set

    var micPermissionRequest: (() -> Unit)? = null
    var permissionRequester: ((String) -> Unit)? = null

    private val appContext = app
    private val tools = Tools(app, store) { p -> permissionRequester?.invoke(p) }
    private val input = SpeechInput(app, this)
    val speaker = Speaker(app, store) { msg -> viewModelScope.launch { addMsg(Kind.ACTION, msg) } }

    private val chat = ArrayList<Pair<String, String>>()
    private var job: Job? = null
    private var idCounter = 0L
    private var silentTurns = 0

    init {
        chat.addAll(store.loadHistory())
        for ((r, t) in chat) {
            messages.add(ChatMessage(nextId(), if (r == "user") Kind.USER else Kind.ASSISTANT, t))
        }
        refreshLists()
    }

    private fun nextId(): Long {
        idCounter++
        return idCounter
    }

    fun addMsg(kind: Kind, text: String, image: Bitmap? = null) {
        messages.add(ChatMessage(nextId(), kind, text, image))
    }

    // ------------------------------------------------------------------ lifecycle

    fun onAppOpened() {
        if (store.autoListen) startListening()
    }

    fun onAppBackgrounded() {
        if (status == Status.LISTENING) {
            input.cancel()
            partial = ""
            level = 0f
            status = Status.IDLE
        }
    }

    override fun onCleared() {
        input.cancel()
        speaker.shutdown()
        super.onCleared()
    }

    // ------------------------------------------------------------------ settings hooks

    fun changeHandsFree(v: Boolean) {
        handsFree = v
        store.handsFree = v
        silentTurns = 0
    }

    fun changeSpeakReplies(v: Boolean) {
        speakReplies = v
        store.speakReplies = v
        if (!v) speaker.stop()
    }

    fun setLang(tag: String) {
        sttLang = tag
        store.sttLang = tag
    }

    fun onPermissionResult(permission: String, granted: Boolean) {
        permTick++
        if (granted && permission == Manifest.permission.RECORD_AUDIO) startListening()
    }

    fun testVoice() {
        speaker.settingsChanged()
        speaker.speak("Hi, I'm Jarvis. This is how I sound right now. Not too robotic, I hope.") {}
    }

    fun clearChat() {
        job?.cancel()
        speaker.stop()
        messages.clear()
        chat.clear()
        store.saveHistory(emptyList())
        status = Status.IDLE
    }

    // ------------------------------------------------------------------ notes / tasks / memory (UI side)

    fun refreshLists() {
        notes.clear()
        notes.addAll(store.loadNotes())
        tasks.clear()
        tasks.addAll(store.loadTasks())
        memories.clear()
        memories.addAll(store.loadMemories())
    }

    fun addNote(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val l = store.loadNotes()
        l.add(Note(store.newId(), t, System.currentTimeMillis()))
        store.saveNotes(l)
        refreshLists()
    }

    fun deleteNote(id: Long) {
        val l = store.loadNotes()
        l.removeAll { it.id == id }
        store.saveNotes(l)
        refreshLists()
    }

    fun addTask(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val l = store.loadTasks()
        l.add(Task(store.newId(), t, false))
        store.saveTasks(l)
        refreshLists()
    }

    fun toggleTask(id: Long) {
        val l = store.loadTasks()
        val i = l.indexOfFirst { it.id == id }
        if (i >= 0) {
            l[i] = l[i].copy(done = !l[i].done)
            store.saveTasks(l)
            refreshLists()
        }
    }

    fun deleteTask(id: Long) {
        val l = store.loadTasks()
        l.removeAll { it.id == id }
        store.saveTasks(l)
        refreshLists()
    }

    fun addMemory(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val l = store.loadMemories()
        if (l.none { it.equals(t, ignoreCase = true) }) l.add(t)
        store.saveMemories(l)
        refreshLists()
    }

    fun deleteMemory(index: Int) {
        val l = store.loadMemories()
        if (index in l.indices) {
            l.removeAt(index)
            store.saveMemories(l)
            refreshLists()
        }
    }

    // ------------------------------------------------------------------ images

    fun attachImage(cr: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) { decodeScaled(cr, uri) }
            if (bmp != null) pendingImage = bmp else addMsg(Kind.ERROR, "Couldn't open that image.")
        }
    }

    fun attachBitmap(bmp: Bitmap) {
        pendingImage = bmp
    }

    fun clearImage() {
        pendingImage = null
    }

    private fun decodeScaled(cr: ContentResolver, uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (e: Exception) {
        null
    }

    private fun toJpegBase64(b: Bitmap): String {
        val maxSide = 1024
        val scale = minOf(1f, maxSide.toFloat() / maxOf(b.width, b.height))
        val bm = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                b,
                (b.width * scale).toInt().coerceAtLeast(1),
                (b.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            b
        }
        val out = ByteArrayOutputStream()
        bm.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    // ------------------------------------------------------------------ voice input

    fun onMicClick() {
        when (status) {
            Status.LISTENING -> input.stop()
            Status.SPEAKING -> {
                speaker.stop()
                startListening()
            }
            Status.THINKING -> {
                job?.cancel()
                if (chat.isNotEmpty() && chat.last().first == "user") chat.removeAt(chat.size - 1)
                status = Status.IDLE
            }
            Status.IDLE -> startListening()
        }
    }

    fun startListening() {
        val granted = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            micPermissionRequest?.invoke()
            return
        }
        if (!input.available()) {
            addMsg(
                Kind.ERROR,
                "Speech recognition isn't available on this phone. Install or update the Google app."
            )
            return
        }
        speaker.stop()
        partial = ""
        level = 0f
        status = Status.LISTENING
        input.start(sttLang)
    }

    override fun onLevel(v: Float) {
        level = v
    }

    override fun onPartial(text: String) {
        partial = text
    }

    override fun onResult(text: String) {
        partial = ""
        level = 0f
        silentTurns = 0
        submit(text)
    }

    override fun onFailure(code: Int) {
        if (status != Status.LISTENING) return
        partial = ""
        level = 0f
        status = Status.IDLE
        when (code) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                if (handsFree) {
                    silentTurns++
                    if (silentTurns < 3) {
                        startListening()
                    } else {
                        changeHandsFree(false)
                        addMsg(Kind.ACTION, "Hands-free paused. Tap the mic when you're ready.")
                    }
                }
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> micPermissionRequest?.invoke()
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER -> addMsg(
                Kind.ERROR,
                "Speech recognition couldn't reach the network. Check your connection and try again."
            )
            else -> {}
        }
    }

    // ------------------------------------------------------------------ sending

    fun submit(text: String) {
        val t = text.trim()
        val img = pendingImage
        if (t.isEmpty() && img == null) return
        speaker.stop()
        input.cancel()
        job?.cancel()
        pendingImage = null
        val shown = if (t.isEmpty()) "Describe this image." else t
        addMsg(Kind.USER, shown, img)
        chat.add(Pair("user", if (img != null) "[Image] $shown" else shown))
        status = Status.THINKING
        job = viewModelScope.launch {
            val local = if (img == null) localCommand(shown) else null
            if (local != null) {
                if (local.isEmpty()) {
                    if (chat.isNotEmpty()) chat.removeAt(chat.size - 1)
                    status = Status.IDLE
                } else {
                    reply(local)
                }
            } else {
                val turn = converse(shown, img)
                refreshLists()
                val r = turn.reply
                if (r != null) {
                    reply(r)
                } else {
                    if (chat.isNotEmpty() && chat.last().first == "user") chat.removeAt(chat.size - 1)
                    addMsg(Kind.ERROR, turn.error ?: "Something went wrong.")
                    status = Status.IDLE
                }
            }
        }
    }

    private fun reply(text: String) {
        addMsg(Kind.ASSISTANT, text)
        chat.add(Pair("assistant", text))
        store.saveHistory(chat.takeLast(40))
        if (speakReplies) {
            status = Status.SPEAKING
            speaker.speak(text) { onSpeechDone() }
        } else {
            onSpeechDone()
        }
    }

    private fun onSpeechDone() {
        status = Status.IDLE
        partial = ""
        if (handsFree) startListening()
    }

    // ------------------------------------------------------------------ instant offline commands

    private suspend fun localCommand(raw: String): String? {
        val s = raw.lowercase(Locale.ROOT).trim().trimEnd('.', '!', '?')
        if (s.split(" ").size > 6) return null
        fun any(vararg k: String): Boolean = k.any { s.contains(it) }

        if (s == "stop" || s == "cancel" || any("stop talking", "be quiet", "shut up", "quiet please") ||
            any("\u099A\u09C1\u09AA", "\u09A5\u09BE\u09AE\u09CB", "\u09A5\u09BE\u09AE\u09C1\u09A8")
        ) {
            speaker.stop()
            return ""
        }
        if (any("stop listening", "goodbye", "good bye", "that's all", "that is all", "never mind") ||
            any("\u09AC\u09BF\u09A6\u09BE\u09AF\u09BC")
        ) {
            changeHandsFree(false)
            return "Okay, I'll be here when you need me."
        }
        if (any("torch", "flashlight", "\u099F\u09B0\u09CD\u099A")) {
            val off = any("off", "\u09A8\u09BF\u09AD\u09BE", "\u09AC\u09A8\u09CD\u09A7", "\u0985\u09AB")
            val r = tools.run("set_flashlight", JSONObject().put("on", !off))
            return if (r.startsWith("Error") || r.contains("couldn't", ignoreCase = true) || r.contains("no flashlight", ignoreCase = true)) {
                r
            } else if (off) {
                "Flashlight off."
            } else {
                "Flashlight on."
            }
        }
        if (any("what time", "the time")) {
            return "It's " + SimpleDateFormat("h:mm a", Locale.US).format(Date()) + "."
        }
        if (any("what's the date", "what is the date", "today's date", "what day is it")) {
            return "It's " + SimpleDateFormat("EEEE, MMMM d", Locale.US).format(Date()) + "."
        }
        return null
    }

    // ------------------------------------------------------------------ the AI conversation

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a (zzzz)", Locale.US).format(Date())
        val sb = StringBuilder()
        sb.append(
            "You are Jarvis, a warm, quick-witted voice assistant living on the user's Android phone. " +
                "Your replies are read aloud by a text-to-speech voice, so write the way people talk: " +
                "short natural sentences with contractions, usually one to three sentences, " +
                "no markdown, no lists, no emojis, no asterisks, and never read out URLs. " +
                "Say numbers, times and units the way you would speak them, for example 'six thirty PM' or '25 degrees'. " +
                "Reply in the language the user speaks; if they speak Bengali, answer in Bengali script. " +
                "When the user wants something done on the phone, call the matching tool instead of describing it, " +
                "then confirm in one short, natural sentence. Never claim an action succeeded unless the tool result says so. " +
                "If a tool reports a missing permission, tell the user briefly what to allow. " +
                "If a request is ambiguous, ask one short question. Be honest when you don't know something. " +
                "Current date and time: $now."
        )
        if (store.userName.isNotEmpty()) sb.append(" The user's name is ").append(store.userName).append('.')
        if (store.homeCity.isNotEmpty()) sb.append(" Their home city for weather is ").append(store.homeCity).append('.')
        val mem = store.loadMemories()
        if (mem.isNotEmpty()) {
            sb.append("\nThings to remember about the user:")
            for (m in mem) sb.append("\n- ").append(m)
        }
        if (store.instructions.isNotBlank()) {
            sb.append("\nExtra instructions from the user: ").append(store.instructions.trim())
        }
        return sb.toString()
    }

    private fun cleanReply(raw: String): String {
        var t = raw.replace(Regex("<think>[\\s\\S]*?</think>"), "")
        t = t.replace("**", "").replace("`", "")
        t = t.replace(Regex("(?m)^#+\\s*"), "")
        return t.trim()
    }

    private suspend fun converse(userText: String, img: Bitmap?): Turn {
        val p = store.currentProvider()
        val key = store.key(p.name)
        if (key.isEmpty()) return Turn(null, "Add your ${p.name} API key in Settings first.")
        val baseUrl = store.baseUrl(p)
        val model = if (img != null) store.visionModel(p) else store.model(p)
        if (baseUrl.isEmpty() || model.isEmpty()) {
            return Turn(null, "Set the base URL and model name in Settings.")
        }
        val models = if (img != null) {
            listOf(model)
        } else {
            (listOf(model) + p.models).filter { it.isNotEmpty() }.distinct()
        }

        val convo = JSONArray()
        convo.put(Llm.textMessage("system", systemPrompt()))
        val recent = chat.takeLast(16)
        for ((i, pair) in recent.withIndex()) {
            val isCurrent = i == recent.size - 1 && pair.first == "user"
            if (isCurrent && img != null) {
                val b64 = withContext(Dispatchers.Default) { toJpegBase64(img) }
                convo.put(Llm.imageMessage(userText, b64))
            } else {
                convo.put(Llm.textMessage(pair.first, pair.second))
            }
        }

        val toolSchemas = if (img == null && store.toolsEnabled) tools.schemas() else null

        var rounds = 0
        while (rounds < 6) {
            rounds++
            val r = withContext(Dispatchers.IO) { Llm.chat(baseUrl, key, models, convo, toolSchemas) }
            val msg = r.message ?: return Turn(null, r.error ?: "No reply.")
            val calls = msg.optJSONArray("tool_calls")
            val content = if (msg.isNull("content")) "" else msg.optString("content")
            if (calls != null && calls.length() > 0 && toolSchemas != null) {
                convo.put(
                    JSONObject().put("role", "assistant").put("content", content).put("tool_calls", calls)
                )
                for (i in 0 until calls.length()) {
                    val c = calls.getJSONObject(i)
                    val f = c.optJSONObject("function") ?: continue
                    val name = f.optString("name")
                    val argText = f.optString("arguments", "{}")
                    val args = try {
                        JSONObject(if (argText.isBlank()) "{}" else argText)
                    } catch (e: Exception) {
                        JSONObject()
                    }
                    addMsg(Kind.ACTION, tools.describe(name, args))
                    val result = try {
                        tools.run(name, args)
                    } catch (e: Exception) {
                        "Error: " + (e.message ?: "failed")
                    }
                    convo.put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", c.optString("id"))
                            .put("name", name)
                            .put("content", result)
                    )
                }
                continue
            }
            val text = cleanReply(content)
            return if (text.isEmpty()) Turn(null, "The AI returned an empty answer. Try again.") else Turn(text, null)
        }
        return Turn(null, "That took too many steps. Try asking in a simpler way.")
    }
}
