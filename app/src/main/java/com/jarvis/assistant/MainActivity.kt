package com.jarvis.assistant

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
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
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    data class Provider(val name: String, val baseUrl: String, val models: List<String>)

    data class AiResult(
        val text: String?,
        val error: String?,
        val code: Int = 200,
        val body: String = ""
    )

    companion object {
        const val REQ_SPEECH = 101
        const val SYSTEM_PROMPT =
            "তুমি জার্ভিস (J.A.R.V.I.S), ব্যবহারকারীর ফোনের ব্যক্তিগত ভয়েস অ্যাসিস্ট্যান্ট। " +
            "ব্যবহারকারী যে ভাষায় কথা বলে সেই ভাষাতেই উত্তর দাও (বাংলায় বললে বাংলা অক্ষরে লিখবে)। " +
            "উত্তর ছোট ও কথোপকথনের মতো রাখো, সর্বোচ্চ তিন-চার বাক্য। " +
            "মার্কডাউন, তারকা চিহ্ন, তালিকা বা ইমোজি ব্যবহার করবে না, কারণ উত্তর জোরে পড়ে শোনানো হবে। " +
            "ভদ্র ও বন্ধুত্বপূর্ণ থাকো।"

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
    private lateinit var langBtn: Button

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var warnedBnVoice = false
    private var torchOn = false
    private var listenBengali = true
    private val history = JSONArray()

    private val cyan = Color.parseColor("#00E5FF")
    private val bgColor = Color.parseColor("#0A0F1C")

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        tts = TextToSpeech(this, this)
        buildUi()
        addMessage("আমি জার্ভিস। মাইক বাটনে চাপ দিয়ে কথা বলুন, অথবা নিচে লিখুন।", false)
        if ((prefs.getString("key_" + currentProvider().name, "") ?: "").isBlank()) {
            showSettings()
        }
    }

    override fun onInit(status: Int) {
        ttsReady = (status == TextToSpeech.SUCCESS)
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

    private fun smallButton(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setTextColor(cyan)
        b.minWidth = 0
        b.minimumWidth = 0
        b.minHeight = 0
        b.minimumHeight = 0
        b.setPadding(dp(14), dp(8), dp(14), dp(8))
        b.background = rounded(Color.parseColor("#14233A"), 12)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(dp(4), 0, 0, 0)
        b.layoutParams = lp
        b.setOnClickListener { onClick() }
        return b
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(bgColor)
        root.setPadding(dp(16), dp(16), dp(16), dp(16))

        // top bar
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(this)
        title.text = "J.A.R.V.I.S"
        title.setTextColor(cyan)
        title.textSize = 22f
        title.typeface = Typeface.DEFAULT_BOLD
        title.letterSpacing = 0.15f
        top.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        langBtn = smallButton("বাংলা") { toggleLang() }
        top.addView(langBtn)
        top.addView(smallButton("🔊") { openVoiceSettings() })
        top.addView(smallButton("⚙") { showSettings() })
        root.addView(top)

        // chat area
        scroll = ScrollView(this)
        logView = LinearLayout(this)
        logView.orientation = LinearLayout.VERTICAL
        scroll.addView(logView)
        val scrollLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        scrollLp.setMargins(0, dp(12), 0, dp(8))
        root.addView(scroll, scrollLp)

        // typed input row
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        input = EditText(this)
        input.hint = "এখানে লিখুন…"
        input.setHintTextColor(Color.GRAY)
        input.setTextColor(Color.WHITE)
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
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(smallButton("পাঠান") { sendTyped() })
        root.addView(row)

        // big mic button
        val mic = Button(this)
        mic.text = "🎤  কথা বলুন"
        mic.textSize = 20f
        mic.isAllCaps = false
        mic.setTextColor(bgColor)
        mic.typeface = Typeface.DEFAULT_BOLD
        mic.background = rounded(cyan, 28)
        mic.setPadding(dp(16), dp(16), dp(16), dp(16))
        mic.setOnClickListener { startListening() }
        val micLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        micLp.setMargins(0, dp(10), 0, 0)
        root.addView(mic, micLp)

        setContentView(root)
    }

    private fun addMessage(text: String, fromUser: Boolean): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 16f
        tv.setTextColor(if (fromUser) Color.WHITE else Color.parseColor("#7FE9FF"))
        tv.setPadding(dp(12), dp(8), dp(12), dp(8))
        tv.maxWidth = (resources.displayMetrics.widthPixels * 0.8).toInt()
        tv.background = rounded(
            if (fromUser) Color.parseColor("#1E2A44") else Color.parseColor("#102A3A"),
            14
        )
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, dp(4), 0, dp(4))
        lp.gravity = if (fromUser) Gravity.END else Gravity.START
        tv.layoutParams = lp
        logView.addView(tv)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        return tv
    }

    private fun toggleLang() {
        listenBengali = !listenBengali
        langBtn.text = if (listenBengali) "বাংলা" else "English"
    }

    private fun currentProvider(): Provider {
        val name = prefs.getString("provider", "Groq")
        return PROVIDERS.firstOrNull { it.name == name } ?: PROVIDERS[0]
    }

    private fun hintFor(p: Provider): String = when (p.name) {
        "Groq" -> "ফ্রি key পেতে ব্রাউজারে console.groq.com/keys খুলুন, গুগল বা গিটহাব দিয়ে লগইন করে Create API Key চাপুন, তারপর key কপি করে এখানে বসান।"
        "OpenRouter" -> "ফ্রি key পেতে ব্রাউজারে openrouter.ai/keys খুলুন, লগইন করে Create Key চাপুন, তারপর key কপি করে এখানে বসান। মডেলের নামের শেষে :free থাকলে সেটা ফ্রি।"
        "Gemini" -> "key পেতে ব্রাউজারে aistudio.google.com/apikey খুলুন, গুগল অ্যাকাউন্ট দিয়ে লগইন করে Create API key চাপুন।"
        else -> "যেকোনো OpenAI-সামঞ্জস্যপূর্ণ সেবার Base URL, key ও মডেলের নাম দিন।"
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
        modelEt.hint = "মডেলের নাম"
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
            .setTitle("সেটিংস")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("সংরক্ষণ") { _, _ ->
                val p = PROVIDERS[spinner.selectedItemPosition]
                prefs.edit()
                    .putString("provider", p.name)
                    .putString("key_" + p.name, keyEt.text.toString().trim())
                    .putString("model_" + p.name, modelEt.text.toString().trim().ifEmpty { p.models[0] })
                    .putString("url_" + p.name, urlEt.text.toString().trim().ifEmpty { p.baseUrl })
                    .apply()
            }
            .setNegativeButton("বাতিল", null)
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
            startActivityForResult(i, REQ_SPEECH)
        } catch (e: ActivityNotFoundException) {
            addMessage(
                "আপনার ফোনে ভয়েস টাইপিং সেবা পাওয়া যায়নি। Google অ্যাপ ইনস্টল ও আপডেট আছে কিনা দেখুন, অথবা নিচে লিখে কমান্ড দিন।",
                false
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SPEECH && resultCode == RESULT_OK) {
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val text = results?.firstOrNull()
            if (!text.isNullOrBlank()) {
                handleInput(text)
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
                    "(আপনার ফোনে বাংলা ভয়েস ইনস্টল করা নেই। সেটিংস > ভাষা > টেক্সট-টু-স্পিচ থেকে Google এর বাংলা ভয়েস ডাউনলোড করুন।)",
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
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            } catch (e2: Exception) {
                addMessage("ফোনের সেটিংস খুলতে পারলাম না। নিজে সেটিংস > ভাষা > টেক্সট-টু-স্পিচ এ যান।", false)
            }
        }
    }

    private fun respond(text: String) {
        addMessage(text, false)
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
        addMessage(text, true)
        val local = tryLocalCommand(text)
        if (local != null) {
            respond(local)
        } else {
            askAi(text)
        }
    }

    // ---------------------------------------------------------------- local phone commands

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

    private fun has(t: String, vararg words: String): Boolean = words.any { t.contains(it) }

    private fun launch(i: Intent, okMsg: String): String {
        return try {
            startActivity(i)
            okMsg
        } catch (e: Exception) {
            "দুঃখিত, কাজটা করতে পারলাম না।"
        }
    }

    private fun tryLocalCommand(raw: String): String? {
        val t = normalize(raw)

        // stop talking
        if (has(t, "চুপ", "থামো", "থামুন", "stop talking", "be quiet", "shut up")) {
            tts?.stop()
            return "ঠিক আছে।"
        }

        // flashlight
        if (has(t, "টর্চ", "torch", "flashlight", "ফ্ল্যাশলাইট", "ফ্ল্যাশ লাইট")) {
            val off = has(t, "নিভা", "বন্ধ", "off", "অফ")
            val ok = setTorch(!off)
            return when {
                !ok -> "দুঃখিত, টর্চ চালাতে পারলাম না।"
                off -> "টর্চ বন্ধ করলাম।"
                else -> "টর্চ জ্বালালাম।"
            }
        }

        // alarm
        if (has(t, "অ্যালার্ম", "এলার্ম", "অ্যালারাম", "আলার্ম", "alarm")) {
            return handleAlarm(t)
        }

        // time and date
        if (has(t, "কয়টা বাজে", "কটা বাজে", "কত বাজে", "what time is it", "what's the time", "current time", "এখন সময়")) {
            val f = SimpleDateFormat("hh:mm a", Locale.US)
            return "এখন সময় ${f.format(Date())}।"
        }
        if (has(t, "আজকের তারিখ", "আজ কত তারিখ", "today's date", "what's the date", "what is the date")) {
            val f = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.US)
            return "আজকের তারিখ ${f.format(Date())}।"
        }

        // call (only when a phone number is present)
        if (has(t, "কল করো", "কল দাও", "কল করুন", "ফোন করো", "ফোন দাও", "call", "dial")) {
            val m = Regex("\\+?\\d[\\d\\s-]{5,}").find(t)
            if (m != null) {
                val number = m.value.replace(" ", "").replace("-", "")
                return launch(
                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")),
                    "$number নম্বরে ডায়াল করছি।"
                )
            }
        }

        // open an app
        if (has(t, "খোলো", "খুলো", "খুলুন", "চালু করো", "চালাও", "open", "launch", "start")) {
            val r = openApp(t)
            if (r != null) return r
        }

        return null
    }

    private fun handleAlarm(t: String): String {
        val m = Regex("(\\d{1,2})(?:[:.\\s](\\d{2}))?").find(t)
        if (m == null) {
            return launch(Intent(AlarmClock.ACTION_SHOW_ALARMS), "অ্যালার্মের তালিকা খুলছি।")
        }
        var hour = m.groupValues[1].toInt()
        val minute = if (m.groupValues[2].isNotEmpty()) m.groupValues[2].toInt() else 0
        val pm = has(t, "pm", "রাত", "বিকাল", "সন্ধ্যা", "দুপুর")
        if (pm && hour in 1..11) hour += 12
        if (hour > 23 || minute > 59) return "সময়টা বুঝতে পারিনি, আবার বলুন।"
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return launch(i, String.format(Locale.US, "%02d:%02d এর জন্য অ্যালার্ম সেট করলাম।", hour, minute))
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
                torchOn = on
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
        "ঘড়ি" to "clock",
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
        return launch(launchIntent, "$label খুলছি।")
    }

    // ---------------------------------------------------------------- AI brain (OpenAI-compatible)

    private fun askAi(userText: String) {
        val p = currentProvider()
        val key = (prefs.getString("key_" + p.name, "") ?: "").trim()
        if (key.isBlank()) {
            respond("আগে সেটিংসে (⚙) API key বসান।")
            showSettings()
            return
        }
        val chosen = (prefs.getString("model_" + p.name, p.models[0]) ?: p.models[0]).trim()
        val model = if (chosen.isEmpty()) p.models[0] else chosen
        val baseUrl = (prefs.getString("url_" + p.name, p.baseUrl) ?: p.baseUrl).trim().trimEnd('/')
        if (baseUrl.isEmpty() || model.isEmpty()) {
            respond("সেটিংসে (⚙) Base URL ও মডেলের নাম দিন।")
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
        val thinking = addMessage("…", false)

        Thread {
            val result = try {
                callAi(baseUrl, key, models, snapshot)
            } catch (e: Exception) {
                AiResult(null, "ইন্টারনেট সংযোগে সমস্যা হয়েছে। নেট চালু আছে কিনা দেখুন।")
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
                    respond(result.error ?: "কিছু একটা সমস্যা হয়েছে।")
                }
            }
        }.start()
    }

    private fun callAi(baseUrl: String, key: String, models: List<String>, history: JSONArray): AiResult {
        var last = AiResult(null, "অজানা সমস্যা হয়েছে।")
        for (m in models) {
            val r = callOnce(baseUrl, key, m, history)
            val modelProblem = r.code == 404 || (r.code == 400 && r.body.contains("model", ignoreCase = true))
            if (!modelProblem) return r
            last = r
        }
        return last
    }

    private fun callOnce(baseUrl: String, key: String, model: String, history: JSONArray): AiResult {
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
            messages.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
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
                AiResult(null, "কোনো উত্তর পাইনি, আবার চেষ্টা করুন।")
            } else {
                AiResult(out, null)
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun errorMessage(code: Int): String = when (code) {
        400 -> "অনুরোধে সমস্যা হয়েছে। সেটিংসে (⚙) key ও মডেলের নাম ঠিক আছে কিনা দেখুন।"
        401, 403 -> "API key ঠিক নেই বা অনুমতি নেই। সেটিংসে (⚙) key আবার কপি করে বসান।"
        404 -> "মডেলের নাম বা Base URL ঠিক নেই। সেটিংসে (⚙) দেখুন।"
        429 -> "ফ্রি লিমিট আপাতত শেষ। কিছুক্ষণ পর আবার চেষ্টা করুন, অথবা সেটিংসে অন্য সেবা বেছে নিন।"
        else -> "সার্ভারে সমস্যা হয়েছে (কোড $code)। পরে আবার চেষ্টা করুন।"
    }
}
