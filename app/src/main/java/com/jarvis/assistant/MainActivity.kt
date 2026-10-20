package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

enum class JarvisState { IDLE, LISTENING, THINKING, SPEAKING }

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    data class Provider(val name: String, val baseUrl: String, val models: List<String>)

    data class AiResult(
        val text: String?,
        val error: String?,
        val code: Int = 200,
        val body: String = ""
    )

    data class Contact(val name: String, val number: String)

    companion object {
        const val REQ_SPEECH = 101
        const val REQ_CONTACTS = 102

        val PROVIDERS = listOf(
            Provider(
                "Groq",
                "https://api.groq.com/openai/v1",
                listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "openai/gpt-oss-20b")
            ),
            Provider(
                "OpenRouter",
                "https://openrouter.ai/api/v1",
                listOf("openrouter/free", "meta-llama/llama-3.3-70b-instruct:free")
            ),
            Provider(
                "Gemini",
                "https://generativelanguage.googleapis.com/v1beta/openai",
                listOf("gemini-2.5-flash", "gemini-3-flash-preview")
            ),
            Provider("Custom", "", listOf(""))
        )
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var logView: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var langChip: Button
    private lateinit var reactor: ArcReactorView
    private lateinit var statusView: TextView

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var warnedBnVoice = false
    private var listenBengali = true
    private var cmdBn = false
    private var pendingCommand: String? = null
    private val history = JSONArray()
    private val uiLog = JSONArray()

    private val cyan = Color.parseColor("#00E5FF")
    private val bgColor = Color.parseColor("#070B14")

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        buildUi()
        tts = TextToSpeech(this, this)
        restoreChat()
        if (uiLog.length() == 0) {
            addMessage(
                "Hello, I'm Jarvis. Tap the reactor or the mic button and speak in Bengali or English. Say \"help\" to see what I can do.",
                false
            )
        }
        if ((prefs.getString("key_" + currentProvider().name, "") ?: "").isBlank()) {
            showSettings()
        }
    }

    override fun onInit(status: Int) {
        ttsReady = (status == TextToSpeech.SUCCESS)
        if (ttsReady) {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    runOnUiThread { setStatus(JarvisState.SPEAKING) }
                }

                override fun onDone(utteranceId: String?) {
                    runOnUiThread { setStatus(JarvisState.IDLE) }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    runOnUiThread { setStatus(JarvisState.IDLE) }
                }
            })
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(radiusDp).toFloat()
        return d
    }

    private fun smallButton(label: String, onClick: (View) -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setTextColor(cyan)
        b.minWidth = 0
        b.minimumWidth = 0
        b.minHeight = 0
        b.minimumHeight = 0
        b.setPadding(dp(14), dp(8), dp(14), dp(8))
        val bg = rounded(Color.parseColor("#101B2E"), 14)
        bg.setStroke(dp(1), Color.parseColor("#3300E5FF"))
        b.background = bg
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(dp(6), 0, 0, 0)
        b.layoutParams = lp
        b.setOnClickListener { v -> onClick(v) }
        return b
    }

    private fun langLabel(): String = if (listenBengali) "Bengali" else "English"

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(bgColor)
        root.setPadding(dp(16), dp(14), dp(16), dp(14))

        // header
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL

        val titleBox = LinearLayout(this)
        titleBox.orientation = LinearLayout.VERTICAL
        val title = TextView(this)
        title.text = "J.A.R.V.I.S"
        title.setTextColor(cyan)
        title.textSize = 22f
        title.typeface = Typeface.DEFAULT_BOLD
        title.letterSpacing = 0.15f
        statusView = TextView(this)
        statusView.text = "ONLINE"
        statusView.setTextColor(Color.parseColor("#6BCBE0"))
        statusView.textSize = 11f
        statusView.letterSpacing = 0.25f
        titleBox.addView(title)
        titleBox.addView(statusView)
        top.addView(titleBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        langChip = smallButton(langLabel()) { toggleLang() }
        top.addView(langChip)
        top.addView(smallButton("⋮") { v -> showMenu(v) })
        root.addView(top)

        // arc reactor
        reactor = ArcReactorView(this)
        reactor.setOnClickListener { startListening() }
        val reactorLp = LinearLayout.LayoutParams(dp(170), dp(170))
        reactorLp.gravity = Gravity.CENTER_HORIZONTAL
        reactorLp.setMargins(0, dp(8), 0, dp(4))
        root.addView(reactor, reactorLp)

        // chat area
        scroll = ScrollView(this)
        logView = LinearLayout(this)
        logView.orientation = LinearLayout.VERTICAL
        scroll.addView(logView)
        val scrollLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        scrollLp.setMargins(0, dp(6), 0, dp(8))
        root.addView(scroll, scrollLp)

        // typed input row
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        input = EditText(this)
        input.hint = "Type a message…"
        input.setHintTextColor(Color.GRAY)
        input.setTextColor(Color.WHITE)
        input.background = rounded(Color.parseColor("#101B2E"), 16)
        input.setPadding(dp(14), dp(10), dp(14), dp(10))
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        input.setSingleLine(true)
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTyped()
                true
            } else {
                false
            }
        }
        // hide the reactor while typing so the chat keeps enough room
        input.setOnFocusChangeListener { _, hasFocus ->
            reactor.visibility = if (hasFocus) View.GONE else View.VISIBLE
        }
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(smallButton("Send") { sendTyped() })
        root.addView(row)

        // big mic button
        val mic = Button(this)
        mic.text = "🎤  Tap to speak"
        mic.textSize = 18f
        mic.isAllCaps = false
        mic.setTextColor(bgColor)
        mic.typeface = Typeface.DEFAULT_BOLD
        val micBg = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.parseColor("#00E5FF"), Color.parseColor("#3D8BFF"))
        )
        micBg.cornerRadius = dp(28).toFloat()
        mic.background = micBg
        mic.setPadding(dp(16), dp(14), dp(16), dp(14))
        mic.setOnClickListener { startListening() }
        val micLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        micLp.setMargins(0, dp(10), 0, 0)
        root.addView(mic, micLp)

        setContentView(root)
    }

    private fun setStatus(s: JarvisState) {
        reactor.state = s
        statusView.text = when (s) {
            JarvisState.IDLE -> "ONLINE"
            JarvisState.LISTENING -> "LISTENING"
            JarvisState.THINKING -> "THINKING"
            JarvisState.SPEAKING -> "SPEAKING"
        }
    }

    private fun addMessage(text: String, fromUser: Boolean, persist: Boolean = false): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 15f
        tv.setTextColor(if (fromUser) Color.WHITE else Color.parseColor("#9AF0FF"))
        tv.setPadding(dp(14), dp(10), dp(14), dp(10))
        tv.maxWidth = (resources.displayMetrics.widthPixels * 0.8).toInt()
        val bg = rounded(
            if (fromUser) Color.parseColor("#1B2B4A") else Color.parseColor("#0D2233"),
            16
        )
        if (!fromUser) bg.setStroke(dp(1), Color.parseColor("#3300E5FF"))
        tv.background = bg
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, dp(4), 0, dp(4))
        lp.gravity = if (fromUser) Gravity.END else Gravity.START
        tv.layoutParams = lp
        logView.addView(tv)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        if (persist) {
            uiLog.put(JSONObject().put("u", fromUser).put("t", text))
            while (uiLog.length() > 60) uiLog.remove(0)
            saveChat()
        }
        return tv
    }

    private fun saveChat() {
        prefs.edit()
            .putString("chat_ui", uiLog.toString())
            .putString("chat_ai", history.toString())
            .apply()
    }

    private fun restoreChat() {
        try {
            val ui = JSONArray(prefs.getString("chat_ui", "[]"))
            for (i in 0 until ui.length()) {
                val o = ui.getJSONObject(i)
                addMessage(o.getString("t"), o.getBoolean("u"), false)
                uiLog.put(o)
            }
            val ai = JSONArray(prefs.getString("chat_ai", "[]"))
            for (i in 0 until ai.length()) {
                history.put(ai.getJSONObject(i))
            }
        } catch (e: Exception) {
            // a damaged save is simply ignored
        }
    }

    private fun clearChat() {
        while (uiLog.length() > 0) uiLog.remove(0)
        while (history.length() > 0) history.remove(0)
        logView.removeAllViews()
        saveChat()
        addMessage("Chat cleared.", false)
    }

    private fun toggleLang() {
        listenBengali = !listenBengali
        langChip.text = langLabel()
    }

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "Listening language: " + langLabel())
        m.menu.add(0, 2, 1, "Phone voice settings")
        m.menu.add(0, 3, 2, "AI settings")
        m.menu.add(0, 4, 3, "Memory")
        m.menu.add(0, 5, 4, "What can I say?")
        m.menu.add(0, 6, 5, "Clear chat")
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> toggleLang()
                2 -> openVoiceSettings()
                3 -> showSettings()
                4 -> showMemory()
                5 -> showHelp()
                6 -> clearChat()
            }
            true
        }
        m.show()
    }

    private fun showHelp() {
        val msg = "Try saying, in Bengali or English:\n\n" +
            "• Ask anything, e.g. \"What is the capital of Japan?\"\n" +
            "• \"Set a timer for 5 minutes\"\n" +
            "• \"Set an alarm for 6:30 am\"\n" +
            "• \"Call Riad\" or \"Call 01712345678\"\n" +
            "• \"Send Riad a WhatsApp message saying I'm coming\"\n" +
            "• \"Search YouTube for relaxing music\"\n" +
            "• \"Navigate to Mymensingh\"\n" +
            "• \"Open WhatsApp\"\n" +
            "• \"Turn on the torch\"\n" +
            "• \"Volume up\", \"Volume down\", \"Mute\"\n" +
            "• \"How much battery do I have?\"\n" +
            "• \"Remember that my birthday is 10 March\"\n" +
            "• \"What do you remember?\"\n" +
            "• \"Stop talking\"\n\n" +
            "To call or message by name, allow contacts access when asked, and say the name in the same language it is saved in."
        AlertDialog.Builder(this)
            .setTitle("What can I say?")
            .setMessage(msg)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showMemory() {
        val facts = getFacts()
        val msg = if (facts.isEmpty()) {
            "Nothing yet. Say: \"Remember that my birthday is 10 March.\""
        } else {
            facts.joinToString("\n") { "• " + it }
        }
        AlertDialog.Builder(this)
            .setTitle("What I remember")
            .setMessage(msg)
            .setPositiveButton("Close", null)
            .setNegativeButton("Forget all") { _, _ ->
                saveFacts(emptyList())
                addMessage("Memory cleared.", false)
            }
            .show()
    }

    private fun currentProvider(): Provider {
        val name = prefs.getString("provider", "Groq")
        return PROVIDERS.firstOrNull { it.name == name } ?: PROVIDERS[0]
    }

    private fun hintFor(p: Provider): String = when (p.name) {
        "Groq" -> "Free key: open console.groq.com/keys in your browser, log in with Google or GitHub, tap Create API Key, then paste the key here."
        "OpenRouter" -> "Free key: open openrouter.ai/keys, log in, tap Create Key, then paste it here. Model names ending in :free are free."
        "Gemini" -> "Key: open aistudio.google.com/apikey, log in with a Google account and tap Create API key."
        else -> "Enter the Base URL, key and model name of any OpenAI-compatible service."
    }

    private fun showSettings() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(10), dp(20), 0)

        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            PROVIDERS.map { it.name }
        )

        val hint = TextView(this)
        hint.textSize = 13f

        val keyEt = EditText(this)
        keyEt.hint = "API key"
        keyEt.setSingleLine(true)

        val modelEt = EditText(this)
        modelEt.hint = "Model name"
        modelEt.setSingleLine(true)

        val urlEt = EditText(this)
        urlEt.hint = "Base URL"
        urlEt.setSingleLine(true)

        fun load(p: Provider) {
            hint.text = hintFor(p)
            keyEt.setText(prefs.getString("key_" + p.name, ""))
            modelEt.setText(prefs.getString("model_" + p.name, p.models[0]))
            urlEt.setText(prefs.getString("url_" + p.name, p.baseUrl))
        }

        val cur = currentProvider()
        spinner.setSelection(PROVIDERS.indexOf(cur))
        load(cur)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                load(PROVIDERS[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        box.addView(spinner)
        box.addView(hint)
        box.addView(keyEt)
        box.addView(modelEt)
        box.addView(urlEt)

        AlertDialog.Builder(this)
            .setTitle("AI settings")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                val p = PROVIDERS[spinner.selectedItemPosition]
                prefs.edit()
                    .putString("provider", p.name)
                    .putString("key_" + p.name, keyEt.text.toString().trim())
                    .putString("model_" + p.name, modelEt.text.toString().trim().ifEmpty { p.models[0] })
                    .putString("url_" + p.name, urlEt.text.toString().trim().ifEmpty { p.baseUrl })
                    .apply()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- voice in / out

    private fun startListening() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (listenBengali) "bn-BD" else "en-US")
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, if (listenBengali) "বলুন…" else "Speak…")
        try {
            setStatus(JarvisState.LISTENING)
            startActivityForResult(i, REQ_SPEECH)
        } catch (e: ActivityNotFoundException) {
            setStatus(JarvisState.IDLE)
            addMessage(
                "Voice typing isn't available on this phone. Install or update the Google app, or type your command below.",
                false
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SPEECH) {
            setStatus(JarvisState.IDLE)
            if (resultCode == RESULT_OK) {
                val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                val text = results?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    handleInput(text)
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CONTACTS) {
            val p = pendingCommand
            pendingCommand = null
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted && p != null) {
                val r = try {
                    tryLocalCommand(p)
                } catch (e: Exception) {
                    null
                }
                respond(r ?: tr("কাজটা করতে পারলাম না।", "Sorry, I couldn't do that."))
            } else {
                respond("Okay. I can't use your contacts without permission.")
            }
        }
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        val engine = tts ?: return
        val hasBengali = text.any { it in 'ঀ'..'৿' }
        val locale = if (hasBengali) Locale("bn", "BD") else Locale.US
        val r = engine.setLanguage(locale)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            if (hasBengali && !warnedBnVoice) {
                warnedBnVoice = true
                addMessage(
                    "(No Bengali voice is installed on this phone. Open the menu > Phone voice settings and download the Google Bengali voice.)",
                    false
                )
            }
            return
        }
        // a slightly slower pace sounds more natural than the default
        engine.setSpeechRate(0.92f)
        engine.setPitch(1.0f)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    private fun openVoiceSettings() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: Exception) {
                addMessage("Couldn't open settings. Go to Settings > Language > Text-to-speech yourself.", false)
            }
        }
    }

    private fun respond(text: String) {
        setStatus(JarvisState.IDLE)
        addMessage(text, false, true)
        speak(text)
    }

    // ---------------------------------------------------------------- input handling

    private fun sendTyped() {
        val t = input.text.toString().trim()
        if (t.isNotEmpty()) {
            input.setText("")
            handleInput(t)
        }
    }

    private fun handleInput(text: String) {
        addMessage(text, true, true)
        val local = try {
            tryLocalCommand(text)
        } catch (e: Exception) {
            null
        }
        if (local != null) {
            respond(local)
        } else {
            askAi(text)
        }
    }

    // ---------------------------------------------------------------- helpers for local commands

    private fun tr(bn: String, en: String): String = if (cmdBn) bn else en

    private fun normalize(s: String): String {
        val sb = StringBuilder()
        for (c in s.lowercase(Locale.ROOT)) {
            if (c in '০'..'৯') {
                sb.append('0' + (c - '০'))
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    private val bnWordNumbers = mapOf(
        "এক" to "1", "দুই" to "2", "দু" to "2",
        "তিন" to "3", "চার" to "4", "পাঁচ" to "5",
        "ছয়" to "6", "ছয়" to "6", "সাত" to "7",
        "আট" to "8", "নয়" to "9", "নয়" to "9",
        "দশ" to "10", "পনেরো" to "15", "পনের" to "15",
        "বিশ" to "20", "ত্রিশ" to "30",
        "ষাট" to "60"
    )

    // turns Bengali number words ("পাঁচ") into digits; used only for timers and alarms
    private fun wordNums(t: String): String =
        t.split(" ").joinToString(" ") { bnWordNumbers[it] ?: it }

    private fun has(t: String, vararg words: String): Boolean = words.any { t.contains(it) }

    private fun stripWords(t: String, drop: Set<String>): String =
        t.split(Regex("[\\s,।?!.]+"))
            .filter { it.isNotEmpty() && it !in drop }
            .joinToString(" ")

    private fun launch(i: Intent, okMsg: String): String {
        return try {
            startActivity(i)
            okMsg
        } catch (e: Exception) {
            tr("দুঃখিত, কাজটা করতে পারলাম না।", "Sorry, I couldn't do that.")
        }
    }

    // ---------------------------------------------------------------- facts the user asked us to remember

    private fun getFacts(): MutableList<String> {
        val out = mutableListOf<String>()
        try {
            val a = JSONArray(prefs.getString("facts", "[]"))
            for (i in 0 until a.length()) out.add(a.getString(i))
        } catch (e: Exception) {
            // ignore a damaged save
        }
        return out
    }

    private fun saveFacts(list: List<String>) {
        prefs.edit().putString("facts", JSONArray(list).toString()).apply()
    }

    // ---------------------------------------------------------------- contacts

    private fun hasContactsPermission(): Boolean =
        checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun askContactsPermission(raw: String): String {
        pendingCommand = raw
        requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQ_CONTACTS)
        return tr(
            "কন্টাক্টস দেখার অনুমতি দিন, তারপর আমি কাজটা করব।",
            "Please allow contacts access and I'll do that."
        )
    }

    private fun findContact(t: String): Contact? {
        val tokens = t.split(Regex("[\\s,।?!.]+")).filter { it.isNotEmpty() }
        var best: Contact? = null
        var bestScore = 0
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        ) ?: return null
        cursor.use {
            while (it.moveToNext()) {
                val name = (it.getString(0) ?: "").trim()
                val number = (it.getString(1) ?: "").trim()
                val n = normalize(name)
                if (n.length < 2 || number.isEmpty()) continue
                var score = 0
                if (n.length >= 3 && t.contains(n)) {
                    score = n.length * 2
                } else if (n.length < 3 && tokens.any { w -> w == n || (w.startsWith(n) && w.length <= n.length + 2) }) {
                    score = n.length * 2
                }
                if (score == 0) {
                    for (w in n.split(" ")) {
                        if (w.length >= 3 && t.contains(w)) score = maxOf(score, w.length)
                    }
                }
                if (score > bestScore) {
                    bestScore = score
                    best = Contact(name, number)
                }
            }
        }
        return best
    }

    // Bangladesh local format (01712...) -> international digits for wa.me
    private fun intlNumber(n: String): String {
        val d = n.filter { it.isDigit() || it == '+' }
        if (d.startsWith("+")) return d.substring(1)
        if (d.startsWith("00")) return d.substring(2)
        if (d.startsWith("0")) return "88$d"
        return d
    }

    private fun extractMessageBody(raw: String): String {
        val trig = Regex(
            "(লেখো|লিখো|লিখে দাও|লিখুন|লিখে|পাঠাও|পাঠান|জানাও|বলো|saying|that says|say|write|tell him|tell her)\\s*[:,-]?\\s*",
            RegexOption.IGNORE_CASE
        )
        val m = trig.find(raw) ?: return ""
        return raw.substring(m.range.last + 1).trim().trimEnd('.', '।')
    }

    private fun handleMessage(raw: String, t: String, whatsapp: Boolean): String {
        if (!hasContactsPermission()) return askContactsPermission(raw)
        val c = findContact(t) ?: return tr(
            "ওই নামে কাউকে খুঁজে পালাম না। কন্টাক্টসে যে ভাষায় নাম লেখা, সেই ভাষায় বলুন।",
            "I couldn't find that contact. Say the name in the same language it's saved in."
        )
        val body = extractMessageBody(raw)
        val okMsg = tr(
            "${c.name}-এর জন্য মেসেজ খুলছি। পাঠানোর বাটনটা আপনি চাপুন।",
            "Opening a message to ${c.name}. Tap send when you're ready."
        )
        if (whatsapp) {
            val enc = URLEncoder.encode(body, "UTF-8").replace("+", "%20")
            val uri = Uri.parse("https://wa.me/" + intlNumber(c.number) + "?text=" + enc)
            val i = Intent(Intent.ACTION_VIEW, uri)
            return try {
                i.setPackage("com.whatsapp")
                startActivity(i)
                okMsg
            } catch (e: Exception) {
                try {
                    i.setPackage(null)
                    startActivity(i)
                    okMsg
                } catch (e2: Exception) {
                    tr("হোয়াটসঅ্যাপ খুলতে পারলাম না।", "I couldn't open WhatsApp.")
                }
            }
        }
        val num = c.number.filter { it.isDigit() || it == '+' }
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$num")).putExtra("sms_body", body)
        return launch(i, okMsg)
    }

    // ---------------------------------------------------------------- local phone commands

    private fun tryLocalCommand(raw: String): String? {
        cmdBn = raw.any { it in 'ঀ'..'৿' }
        val t = normalize(raw)
        val shortCmd = t.length <= 40

        // stop talking
        if (has(t, "চুপ", "থামো", "থামুন", "stop talking", "stop speaking", "be quiet", "shut up")) {
            tts?.stop()
            setStatus(JarvisState.IDLE)
            return tr("ঠিক আছে।", "Okay.")
        }

        // help
        val bare = t.trim().trimEnd('.', '?', '!', '।')
        if (bare == "help" || bare == "help me" || bare == "সাহায্য" ||
            has(t, "what can you do", "what can i say", "কী কী পারো", "কি কি পারো")
        ) {
            showHelp()
            return tr("আমি যা যা পারি তার তালিকা দেখাচ্ছি।", "Here's what I can do.")
        }

        // memory: recall
        if (has(t, "what do you remember", "what do you know about me", "কী মনে আছে", "কি মনে আছে",
                "আমার সম্পর্কে কী জানো", "আমার সম্পর্কে কি জানো")
        ) {
            val f = getFacts()
            return if (f.isEmpty()) {
                tr("আমার কিছু মনে নেই।", "I don't remember anything yet.")
            } else {
                tr("আমার মনে আছে: ", "I remember: ") + f.joinToString(". ")
            }
        }

        // memory: forget everything
        if (has(t, "ভুলে যাও", "ভুলে যান", "forget") &&
            has(t, "সব", "everything", "all", "memory", "মেমোরি")
        ) {
            saveFacts(emptyList())
            return tr("সব ভুলে গেলাম।", "Okay, I've forgotten everything.")
        }

        // memory: remember
        val remTriggers = listOf(
            "মনে রাখো", "মনে রাখুন", "মনে রেখো",
            "remember that", "remember", "note that"
        )
        if (has(t, *remTriggers.toTypedArray())) {
            var fact = raw
            for (w in remTriggers) fact = fact.replace(w, " ", ignoreCase = true)
            fact = fact.replace(Regex("\\s+"), " ").trim(' ', '.', '।', ',')
            if (fact.isEmpty()) {
                return tr("কী মনে রাখব?", "What should I remember?")
            }
            val facts = getFacts()
            if (!facts.contains(fact)) facts.add(fact)
            while (facts.size > 30) facts.removeAt(0)
            saveFacts(facts)
            return tr("ঠিক আছে, মনে রাখলাম।", "Okay, I'll remember that.")
        }

        // flashlight
        if (has(t, "টর্চ", "torch", "flashlight", "ফ্ল্যাশলাইট", "ফ্ল্যাশ লাইট")) {
            val off = has(t, "নিভা", "বন্ধ", "off", "অফ")
            val ok = setTorch(!off)
            return when {
                !ok -> tr("দুঃখিত, টর্চ চালাতে পারলাম না।", "Sorry, I couldn't use the torch.")
                off -> tr("টর্চ বন্ধ করলাম।", "Torch off.")
                else -> tr("টর্চ জ্বালালাম।", "Torch on.")
            }
        }

        // timer (before alarm)
        if (has(t, "টাইমার", "timer", "countdown")) {
            return handleTimer(t)
        }

        // alarm
        if (has(t, "অ্যালার্ম", "এলার্ম", "অ্যালারাম", "আলার্ম", "alarm")) {
            return handleAlarm(t)
        }

        // volume
        if (has(t, "ভলিউম", "volume", "আওয়াজ", "আওয়াজ", "শব্দ")) {
            val r = handleVolume(t)
            if (r != null) return r
        }

        // battery
        if (has(t, "ব্যাটারি", "battery") &&
            has(t, "কত", "কেমন", "level", "how much", "percent", "শতাংশ", "status", "left", "remaining", "charge", "আছে")
        ) {
            return handleBattery()
        }

        // time and date
        if (has(t, "কয়টা বাজে", "কটা বাজে", "কত বাজে", "what time is it", "what's the time", "current time", "এখন সময়")) {
            val f = SimpleDateFormat("hh:mm a", Locale.US)
            return tr("এখন সময় ${f.format(Date())}।", "It's ${f.format(Date())}.")
        }
        if (has(t, "আজকের তারিখ", "আজ কত তারিখ", "today's date", "what's the date", "what is the date")) {
            val f = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.US)
            return tr("আজকের তারিখ ${f.format(Date())}।", "Today is ${f.format(Date())}.")
        }

        // message a contact (WhatsApp or SMS)
        val wa = has(t, "whatsapp", "হোয়াটসঅ্যাপ", "হোয়াটস অ্যাপ", "হোয়াটসআপ")
        val sms = has(t, "sms", "এসএমএস", "message", "মেসেজ", "text him", "text her")
        val sendVerb = has(
            t, "লেখো", "লিখো", "লিখে", "লিখুন", "পাঠাও", "পাঠান",
            "জানাও", "বলো", "send", "write", "text", "tell", "say"
        )
        if ((wa || sms) && sendVerb) {
            return handleMessage(raw, t, wa)
        }

        // call by number or by contact name
        if (has(t, "কল করো", "কল দাও", "কল করুন", "ফোন করো", "ফোন দাও", "ফোন করুন", "call", "dial")) {
            val m = Regex("\\+?\\d[\\d\\s-]{5,}").find(t)
            if (m != null) {
                val number = m.value.replace(" ", "").replace("-", "")
                return launch(
                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")),
                    tr("$number নম্বরে ডায়াল করছি।", "Dialing $number.")
                )
            }
            if (shortCmd) {
                if (!hasContactsPermission()) return askContactsPermission(raw)
                val c = findContact(t)
                return if (c != null) {
                    launch(
                        Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + c.number.filter { it.isDigit() || it == '+' })),
                        tr("${c.name}-কে ডায়াল করছি। কল বাটনটা আপনি চাপুন।", "Dialing ${c.name}. Tap the call button to ring.")
                    )
                } else {
                    tr(
                        "ওই নামে কাউকে খুঁজে পালাম না। কন্টাক্টসে যে ভাষায় নাম লেখা, সেই ভাষায় বলুন।",
                        "I couldn't find that contact. Say the name in the same language it's saved in."
                    )
                }
            }
        }

        // YouTube search
        if (has(t, "youtube", "ইউটিউব") &&
            has(t, "play", "search", "find", "চালাও", "চালান", "শোনাও", "দেখাও", "খোঁজো", "খুঁজো", "খুঁজে", "সার্চ")
        ) {
            val q = stripWords(
                t,
                setOf(
                    "ইউটিউবে", "ইউটিউব", "ইউটিউবের", "youtube", "on", "in", "from",
                    "চালাও", "চালান", "শোনাও", "দেখাও", "খোঁজো", "খুঁজো",
                    "খুঁজে", "সার্চ", "করো", "দাও", "play", "search", "find", "for", "me", "a", "the"
                )
            )
            if (q.isNotBlank()) return openYoutube(q)
        }

        // navigation
        val navStrong = has(t, "নেভিগেট", "navigate", "directions", "route to", "take me to", "how to get to", "how do i get to", "get me to")
        val navBn = has(t, "রাস্তা") &&
            has(t, "যাওয়ার", "যাওয়ার", "যাবার", "যেতে", "দেখাও", "দেখান", "ম্যাপে")
        val navMap = has(t, "ম্যাপে", "on maps", "on the map", "in maps")
        if (navStrong || navBn || navMap) {
            val dest = stripWords(
                t,
                setOf(
                    "ম্যাপে", "ম্যাপ", "রাস্তা", "দেখাও", "দেখান", "নেভিগেট", "করো",
                    "যাওয়ার", "যাওয়ার", "যাবার", "যেতে", "চাই", "দাও",
                    "directions", "direction", "navigate", "to", "route", "show", "me", "how", "get", "take", "the", "way",
                    "in", "on", "maps", "map", "google", "do", "i", "go", "please"
                )
            )
            if (dest.isNotBlank()) return openMaps(dest)
        }

        // open an app
        if (has(t, "খোলো", "খুলো", "খুলুন", "চালু করো", "চালাও", "open", "launch", "start")) {
            val r = openApp(t)
            if (r != null) return r
        }

        return null
    }

    private fun durationText(secs: Int): Pair<String, String> {
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        val en = listOfNotNull(
            if (h > 0) "$h hour" + (if (h > 1) "s" else "") else null,
            if (m > 0) "$m minute" + (if (m > 1) "s" else "") else null,
            if (s > 0) "$s second" + (if (s > 1) "s" else "") else null
        ).joinToString(" ")
        val bn = listOfNotNull(
            if (h > 0) "$h ঘণ্টা" else null,
            if (m > 0) "$m মিনিট" else null,
            if (s > 0) "$s সেকেন্ড" else null
        ).joinToString(" ")
        return Pair(bn, en)
    }

    private fun handleTimer(t: String): String {
        val t2 = wordNums(t)
        val re = Regex("(\\d+)\\s*(ঘণ্টা|ঘন্টা|hours?|hrs?|মিনিট|minutes?|mins?|সেকেন্ড|seconds?|secs?)")
        var secs = 0
        for (m in re.findAll(t2)) {
            val n = m.groupValues[1].toIntOrNull() ?: 0
            val u = m.groupValues[2]
            secs += when {
                u.startsWith("ঘ") || u.startsWith("h") -> n * 3600
                u.startsWith("ম") || u.startsWith("m") -> n * 60
                else -> n
            }
        }
        if (secs <= 0) {
            return tr("কতক্ষণের টাইমার? যেমন বলুন: ফাইভ মিনিটের টাইমার।", "How long should the timer be? For example, say: timer for 5 minutes.")
        }
        secs = secs.coerceAtMost(86400)
        val (bn, en) = durationText(secs)
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, secs)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return launch(i, tr("$bn এর টাইমার সেট করলাম।", "Timer set for $en."))
    }

    private fun handleAlarm(t: String): String {
        val t2 = wordNums(t)
        val m = Regex("(\\d{1,2})(?:[:.\\s](\\d{2}))?").find(t2)
        if (m == null) {
            return launch(Intent(AlarmClock.ACTION_SHOW_ALARMS), tr("অ্যালার্মের তালিকা খুলছি।", "Opening your alarms."))
        }
        var hour = m.groupValues[1].toInt()
        val minute = if (m.groupValues[2].isNotEmpty()) m.groupValues[2].toInt() else 0
        val pm = has(t2, "pm", "রাত", "বিকাল", "সন্ধ্যা", "দুপুর")
        if (pm && hour in 1..11) hour += 12
        if (hour > 23 || minute > 59) {
            return tr("সময়টা বুঝতে পারিনি, আবার বলুন।", "I didn't catch the time, please say it again.")
        }
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val hhmm = String.format(Locale.US, "%02d:%02d", hour, minute)
        return launch(i, tr("$hhmm এর জন্য অ্যালার্ম সেট করলাম।", "Alarm set for $hhmm."))
    }

    private fun handleVolume(t: String): String? {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = AudioManager.STREAM_MUSIC
        return when {
            has(t, "max", "full", "সর্বোচ্চ", "ফুল") -> {
                am.setStreamVolume(stream, am.getStreamMaxVolume(stream), AudioManager.FLAG_SHOW_UI)
                tr("ভলিউম সর্বোচ্চ করলাম।", "Volume at maximum.")
            }
            has(t, "mute", "মিউট", "নীরব", "বন্ধ") -> {
                am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                tr("মিউট করলাম।", "Muted.")
            }
            has(t, "বাড়া", "বাড়া", "up", "increase", "louder", "raise") -> {
                for (k in 0 until 3) {
                    am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, if (k == 2) AudioManager.FLAG_SHOW_UI else 0)
                }
                tr("ভলিউম বাড়ালাম।", "Volume up.")
            }
            has(t, "কমা", "down", "decrease", "lower", "quiet", "reduce") -> {
                for (k in 0 until 3) {
                    am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, if (k == 2) AudioManager.FLAG_SHOW_UI else 0)
                }
                tr("ভলিউম কমালাম।", "Volume down.")
            }
            else -> null
        }
    }

    private fun handleBattery(): String {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val st = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = st?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return if (charging) {
            tr("ব্যাটারি $pct শতাংশ, চার্জ হচ্ছে।", "Battery is at $pct percent and charging.")
        } else {
            tr("ব্যাটারি $pct শতাংশ আছে।", "Battery is at $pct percent.")
        }
    }

    private fun openYoutube(q: String): String {
        val i = Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", q)
        return try {
            startActivity(i)
            tr("ইউটিউবে খুঁজছি: $q", "Searching YouTube for $q.")
        } catch (e: Exception) {
            val web = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8"))
            )
            launch(web, tr("ইউটিউবে খুঁজছি: $q", "Searching YouTube for $q."))
        }
    }

    private fun openMaps(dest: String): String {
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(dest)))
            .setPackage("com.google.android.apps.maps")
        val okMsg = tr("$dest এর রাস্তা খুলছি।", "Getting directions to $dest.")
        return try {
            startActivity(nav)
            okMsg
        } catch (e: Exception) {
            val web = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(dest))
            )
            launch(web, okMsg)
        }
    }

    private fun setTorch(on: Boolean): Boolean {
        return try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (id == null) {
                false
            } else {
                cm.setTorchMode(id, on)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private val appAliases = listOf(
        "ইউটিউব" to "youtube",
        "ফেসবুক" to "facebook",
        "মেসেঞ্জার" to "messenger",
        "হোয়াটসঅ্যাপ" to "whatsapp",
        "ইনস্টাগ্রাম" to "instagram",
        "টেলিগ্রাম" to "telegram",
        "টিকটক" to "tiktok",
        "ক্যামেরা" to "camera",
        "ক্রোম" to "chrome",
        "সেটিংস" to "settings",
        "গ্যালারি" to "gallery",
        "ছবি" to "photos",
        "জিমেইল" to "gmail",
        "ম্যাপ" to "maps",
        "ক্যালকুলেটর" to "calculator",
        "ঘড়ি" to "clock",
        "মেসেজ" to "messages",
        "প্লে স্টোর" to "play store",
        "ক্যালেন্ডার" to "calendar",
        "ফাইল" to "files",
        "বিকাশ" to "bkash",
        "নগদ" to "nagad",
        "গুগল" to "google"
    )

    private fun openApp(t: String): String? {
        val pm = packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(query, 0)

        // names we are looking for (English labels)
        val wanted = mutableListOf<String>()
        for ((bn, en) in appAliases) {
            if (t.contains(bn) || t.contains(en)) wanted.add(en)
        }

        var pkg: String? = null
        var label = ""

        // 1) exact label match, 2) label contains alias, 3) spoken text contains the app label
        for (w in wanted) {
            val hit = apps.firstOrNull { it.loadLabel(pm).toString().lowercase(Locale.ROOT) == w }
            if (hit != null) {
                pkg = hit.activityInfo.packageName
                label = hit.loadLabel(pm).toString()
                break
            }
        }
        if (pkg == null) {
            for (w in wanted) {
                val hit = apps.firstOrNull { it.loadLabel(pm).toString().lowercase(Locale.ROOT).contains(w) }
                if (hit != null) {
                    pkg = hit.activityInfo.packageName
                    label = hit.loadLabel(pm).toString()
                    break
                }
            }
        }
        if (pkg == null) {
            val hit = apps.firstOrNull {
                val l = it.loadLabel(pm).toString().lowercase(Locale.ROOT)
                l.length >= 3 && t.contains(l)
            }
            if (hit != null) {
                pkg = hit.activityInfo.packageName
                label = hit.loadLabel(pm).toString()
            }
        }

        if (pkg == null) return null
        val launchIntent = pm.getLaunchIntentForPackage(pkg) ?: return null
        return launch(launchIntent, tr("$label খুলছি।", "Opening $label."))
    }

    // ---------------------------------------------------------------- AI brain (OpenAI-compatible)

    private fun buildSystemPrompt(): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale.US).format(Date())
        val sb = StringBuilder()
        sb.append("You are J.A.R.V.I.S, a personal voice assistant living on the user's Android phone. ")
        sb.append("Reply in the same language the user used. If the user writes or speaks Bengali, ")
        sb.append("reply in natural, simple spoken Bengali using Bengali script only, never romanized Bengali. ")
        sb.append("Keep replies short and conversational, at most three or four sentences, because they are read aloud. ")
        sb.append("Never use markdown, asterisks, bullet points, headings or emoji. ")
        sb.append("Be warm, polite and a little witty, like a helpful butler. ")
        sb.append("If you do not know something recent or current, say so honestly instead of guessing. ")
        sb.append("The current date and time on the user's phone is ").append(now).append(". ")
        val facts = getFacts()
        if (facts.isNotEmpty()) {
            sb.append("Things the user asked you to remember about them: ")
            sb.append(facts.joinToString("; ")).append(". ")
        }
        return sb.toString()
    }

    private fun askAi(userText: String) {
        val p = currentProvider()
        val key = (prefs.getString("key_" + p.name, "") ?: "").trim()
        if (key.isBlank()) {
            respond("Please add an API key in the menu > AI settings first.")
            showSettings()
            return
        }
        val chosen = (prefs.getString("model_" + p.name, p.models[0]) ?: p.models[0]).trim()
        val model = if (chosen.isEmpty()) p.models[0] else chosen
        val baseUrl = (prefs.getString("url_" + p.name, p.baseUrl) ?: p.baseUrl).trim().trimEnd('/')
        if (baseUrl.isEmpty() || model.isEmpty()) {
            respond("Please set the Base URL and model name in the menu > AI settings.")
            showSettings()
            return
        }

        history.put(JSONObject().put("role", "user").put("content", userText))
        while (history.length() > 20) {
            history.remove(0)
            history.remove(0)
        }
        val snapshot = JSONArray(history.toString())
        val models = (listOf(model) + p.models).filter { it.isNotEmpty() }.distinct()
        val system = buildSystemPrompt()
        val thinking = addMessage("…", false)
        setStatus(JarvisState.THINKING)

        Thread {
            val result = try {
                callAi(baseUrl, key, models, snapshot, system)
            } catch (e: Exception) {
                AiResult(null, tr("ইন্টারনেট সংযোগে সমস্যা হয়েছে। নেট চালু আছে কিনা দেখুন।", "Couldn't reach the internet. Please check your connection."))
            }
            runOnUiThread {
                logView.removeView(thinking)
                val reply = result.text
                if (reply != null) {
                    val clean = reply.replace("*", "").replace("#", "").trim()
                    history.put(JSONObject().put("role", "assistant").put("content", clean))
                    respond(clean)
                } else {
                    // drop the unanswered user message so the history stays in pairs
                    history.remove(history.length() - 1)
                    saveChat()
                    respond(result.error ?: tr("কিছু একটা সমস্যা হয়েছে।", "Something went wrong."))
                }
            }
        }.start()
    }

    private fun callAi(baseUrl: String, key: String, models: List<String>, history: JSONArray, system: String): AiResult {
        var last = AiResult(null, tr("অজানা সমস্যা হয়েছে।", "Unknown error."))
        for (m in models) {
            val r = callOnce(baseUrl, key, m, history, system)
            val modelProblem = r.code == 404 || (r.code == 400 && r.body.contains("model", ignoreCase = true))
            if (!modelProblem) return r
            last = r
        }
        return last
    }

    private fun callOnce(baseUrl: String, key: String, model: String, history: JSONArray, system: String): AiResult {
        val url = URL("$baseUrl/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 45000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("User-Agent", "JarvisApp/1.0")

            val messages = JSONArray()
            messages.put(JSONObject().put("role", "system").put("content", system))
            for (i in 0 until history.length()) {
                messages.put(history.getJSONObject(i))
            }
            val body = JSONObject()
            body.put("model", model)
            body.put("messages", messages)
            body.put("temperature", 0.7)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                return AiResult(null, errorMessage(code), code, text)
            }

            val content = JSONObject(text)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "") ?: ""
            val out = content.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
            return if (out.isEmpty()) {
                AiResult(null, tr("কোনো উত্তর পাইনি, আবার চেষ্টা করুন।", "I didn't get an answer, please try again."))
            } else {
                AiResult(out, null)
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun errorMessage(code: Int): String = when (code) {
        400 -> tr("অনুরোধে সমস্যা হয়েছে। মেনু > AI settings এ key ও মডেলের নাম দেখুন।", "The request failed. Check the key and model name in menu > AI settings.")
        401, 403 -> tr("API key ঠিক নেই বা অনুমতি নেই। মেনু > AI settings এ key আবার বসান।", "The API key is wrong or not allowed. Paste it again in menu > AI settings.")
        404 -> tr("মডেলের নাম বা Base URL ঠিক নেই। মেনু > AI settings দেখুন।", "The model name or Base URL is wrong. Check menu > AI settings.")
        429 -> tr("ফ্রি লিমিট আপাতত শেষ। কিছুক্ষণ পর আবার চেষ্টা করুন, অথবা অন্য সেবা বেছে নিন।", "The free limit is used up for now. Try again in a little while, or pick another service in AI settings.")
        else -> tr("সার্ভারে সমস্যা হয়েছে (কোড ${code})। পরে আবার চেষ্টা করুন।", "Server problem (code ${code}). Please try again later.")
    }
}

/** The animated blue "arc reactor" in the middle of the screen. */
class ArcReactorView(context: Context) : View(context) {

    var state: JarvisState = JarvisState.IDLE

    private var angle = 0f
    private var phase = 0f
    private var last = 0L
    private var running = false
    private val d = context.resources.displayMetrics.density

    private val cyan = Color.parseColor("#00E5FF")
    private val ice = Color.parseColor("#BDF7FF")

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        glow.style = Paint.Style.FILL
        ring.style = Paint.Style.STROKE
        arc.style = Paint.Style.STROKE
        arc.strokeCap = Paint.Cap.ROUND
        core.style = Paint.Style.FILL
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        last = 0L
        invalidate()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 16L else (now - last).coerceAtMost(64L)
        last = now

        val speed = when (state) {
            JarvisState.IDLE -> 0.03f
            JarvisState.LISTENING -> 0.09f
            JarvisState.THINKING -> 0.2f
            JarvisState.SPEAKING -> 0.07f
        }
        val pulseSpeed = when (state) {
            JarvisState.IDLE -> 0.0004f
            JarvisState.LISTENING -> 0.0012f
            JarvisState.THINKING -> 0.0015f
            JarvisState.SPEAKING -> 0.0025f
        }
        val pulseAmp = when (state) {
            JarvisState.IDLE -> 0.02f
            JarvisState.LISTENING -> 0.05f
            JarvisState.THINKING -> 0.03f
            JarvisState.SPEAKING -> 0.08f
        }

        angle = (angle + dt * speed) % 360f
        phase = (phase + dt * pulseSpeed) % 1f
        val p = sin(phase * 2.0 * PI).toFloat()
        val pulse = 1f + pulseAmp * p

        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f * 0.95f

        // soft glow
        for (i in 0..3) {
            glow.color = cyan
            glow.alpha = 16 - i * 3
            canvas.drawCircle(cx, cy, r * (1f - i * 0.06f) * pulse, glow)
        }

        // outer thin ring
        ring.color = cyan
        ring.alpha = 140
        ring.strokeWidth = 1.5f * d
        canvas.drawCircle(cx, cy, r * 0.97f, ring)

        // three bright arcs turning one way
        val ra = r * 0.86f
        rect.set(cx - ra, cy - ra, cx + ra, cy + ra)
        arc.color = cyan
        arc.alpha = 230
        arc.strokeWidth = 5f * d
        for (i in 0..2) {
            canvas.drawArc(rect, angle + i * 120f, 70f, false, arc)
        }

        // two softer arcs turning the other way
        val rb = r * 0.72f
        rect.set(cx - rb, cy - rb, cx + rb, cy + rb)
        arc.color = cyan
        arc.alpha = 150
        arc.strokeWidth = 3f * d
        for (i in 0..1) {
            canvas.drawArc(rect, -angle * 1.6f + i * 180f, 100f, false, arc)
        }

        // ring of small ticks
        canvas.save()
        canvas.rotate(angle * 0.5f, cx, cy)
        ring.color = cyan
        ring.alpha = 110
        ring.strokeWidth = 1.5f * d
        for (i in 0 until 36) {
            canvas.drawLine(cx, cy - r * 0.62f, cx, cy - r * 0.57f, ring)
            canvas.rotate(10f, cx, cy)
        }
        canvas.restore()

        // inner ring
        ring.color = cyan
        ring.alpha = 220
        ring.strokeWidth = 3f * d
        canvas.drawCircle(cx, cy, r * 0.46f * (1f + pulseAmp * 0.5f * p), ring)

        // glowing core
        core.color = ice
        core.alpha = (170 + 60 * p).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, r * 0.26f * pulse, core)
        core.color = Color.WHITE
        core.alpha = 235
        canvas.drawCircle(cx, cy, r * 0.12f, core)

        if (running) postInvalidateOnAnimation()
    }
}
