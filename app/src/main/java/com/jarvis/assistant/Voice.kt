package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

fun hasBengali(s: String): Boolean = s.any { it in '\u0980'..'\u09FF' }

// ======================================================================= speech to text

class SpeechInput(private val ctx: Context, private val cb: Listener) {

    interface Listener {
        fun onLevel(v: Float)
        fun onPartial(text: String)
        fun onResult(text: String)
        fun onFailure(code: Int)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(ctx)

    fun start(lang: String) {
        cancel()
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                if (rec === r) cb.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            }

            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (rec === r) cb.onFailure(error)
            }

            override fun onResults(results: Bundle?) {
                if (rec !== r) return
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (t.isBlank()) cb.onFailure(SpeechRecognizer.ERROR_NO_MATCH) else cb.onResult(t)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (rec !== r) return
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (t.isNotBlank()) cb.onPartial(t)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        try {
            r.startListening(i)
        } catch (e: Exception) {
            rec = null
            cb.onFailure(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    /** Stop listening and deliver what was heard so far. */
    fun stop() {
        try {
            rec?.stopListening()
        } catch (e: Exception) {
        }
    }

    fun cancel() {
        val r = rec ?: return
        rec = null
        try {
            r.cancel()
        } catch (e: Exception) {
        }
        handler.post {
            try {
                r.destroy()
            } catch (e: Exception) {
            }
        }
    }
}

// ======================================================================= text prep

object Chunker {
    private val emoji = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]")

    /** Turns chat text into something that sounds right when read aloud. */
    fun clean(raw: String): String {
        var t = raw
        t = t.replace(Regex("```[\\s\\S]*?```"), " ")
        t = t.replace(Regex("\\[([^\\]]*)\\]\\((https?://[^)]*)\\)"), "\$1")
        t = t.replace(Regex("https?://\\S+"), "")
        t = t.replace(emoji, "")
        t = t.replace(Regex("[*_`#~|]+"), " ")
        val lines = t.split("\n")
            .map { it.trim().trimStart('-', '\u2022', ' ') }
            .filter { it.isNotEmpty() }
        val sb = StringBuilder()
        for (l in lines) {
            sb.append(l)
            if (l.last() !in ".!?\u0964:;,") sb.append('.')
            sb.append(' ')
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Splits text into speakable chunks. The first chunk is short so audio starts quickly,
     * later chunks are longer so the voice keeps natural phrasing between sentences.
     */
    fun split(t: String): List<String> {
        val sentences = t.split(Regex("(?<=[.!?\u0964])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var cur = StringBuilder()
        for (s0 in sentences) {
            for (s in hardWrap(s0, 170)) {
                val limit = if (out.isEmpty()) 110 else 170
                if (cur.isNotEmpty() && cur.length + 1 + s.length > limit) {
                    out.add(cur.toString().trim())
                    cur = StringBuilder()
                }
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(s)
            }
        }
        if (cur.isNotBlank()) out.add(cur.toString().trim())
        return out
    }

    private fun hardWrap(s: String, max: Int): List<String> {
        if (s.length <= max) return listOf(s)
        val res = ArrayList<String>()
        var rest = s
        while (rest.length > max) {
            var cut = rest.lastIndexOf(", ", max)
            if (cut < 40) cut = rest.lastIndexOf(' ', max)
            if (cut < 1) cut = max
            res.add(rest.substring(0, cut + 1).trim())
            rest = rest.substring(cut + 1).trim()
        }
        if (rest.isNotEmpty()) res.add(rest)
        return res
    }
}

// ======================================================================= audio playback

internal class Clip(
    val pcm: ByteArray?,
    val rate: Int,
    val channels: Int,
    val text: String,
    val end: Boolean = false
)

/** Plays raw 16-bit PCM without blocking, and can be cancelled at any moment. */
internal class PcmPlayer(private val cancelled: () -> Boolean) {
    private var track: AudioTrack? = null
    private var rate = 0
    private var channels = 0
    private var frames = 0L

    fun play(c: Clip) {
        val data = c.pcm ?: return
        if (track == null || rate != c.rate || channels != c.channels) {
            drain()
            open(c.rate, c.channels)
        }
        val t = track ?: return
        var off = 0
        while (off < data.size && !cancelled()) {
            val n = t.write(data, off, minOf(data.size - off, 8192), AudioTrack.WRITE_NON_BLOCKING)
            if (n > 0) {
                off += n
            } else if (n == 0) {
                Thread.sleep(8)
            } else {
                break
            }
        }
        frames += (data.size / (2 * channels)).toLong()
    }

    private fun open(r: Int, ch: Int) {
        val mask = if (ch >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val min = AudioTrack.getMinBufferSize(r, mask, AudioFormat.ENCODING_PCM_16BIT)
        val size = maxOf(min * 2, r * 2 * ch / 2)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(r)
                    .setChannelMask(mask)
                    .build()
            )
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        track = t
        rate = r
        channels = ch
        frames = 0L
    }

    /** Waits for queued audio to finish playing, then frees the track. */
    fun drain() {
        val t = track ?: return
        try {
            val deadline = System.currentTimeMillis() + 60_000
            while (!cancelled() && t.playbackHeadPosition < frames.toInt() &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(15)
            }
            if (cancelled()) {
                t.pause()
                t.flush()
            } else {
                t.stop()
            }
        } catch (e: Exception) {
        }
        release()
    }

    fun release() {
        try {
            track?.release()
        } catch (e: Exception) {
        }
        track = null
        frames = 0L
    }
}

// ======================================================================= text to speech

class Speaker(
    private val ctx: Context,
    private val store: Store,
    private val notice: (String) -> Unit
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null

    @Volatile
    private var ttsReady = false

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var session = 0

    @Volatile
    private var deviceLatch: CountDownLatch? = null

    @Volatile
    var speaking = false
        private set

    private var groqBlockedUntil = 0L
    private var geminiBlockedUntil = 0L
    private var warnedBengali = false
    private var lastLang = ""

    init {
        tts = TextToSpeech(ctx.applicationContext, this)
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        val e = tts ?: return
        e.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        e.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                deviceLatch?.countDown()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                deviceLatch?.countDown()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                deviceLatch?.countDown()
            }
        })
    }

    /** Call after voice settings change so the next sentence picks them up. */
    fun settingsChanged() {
        lastLang = ""
    }

    fun stop() {
        session++
        speaking = false
        deviceLatch?.countDown()
        try {
            tts?.stop()
        } catch (e: Exception) {
        }
    }

    fun shutdown() {
        stop()
        try {
            tts?.shutdown()
        } catch (e: Exception) {
        }
    }

    // ------------------------------------------------------------ main entry

    fun speak(text: String, onFinished: () -> Unit) {
        stop()
        val my = session
        val chunks = Chunker.split(Chunker.clean(text))
        if (chunks.isEmpty()) {
            main.post(onFinished)
            return
        }
        speaking = true
        val queue = LinkedBlockingQueue<Clip>(3)

        // Producer: turns sentences into audio while the previous one is still playing.
        Thread {
            try {
                for (c in chunks) {
                    if (session != my) break
                    val clip = fetch(c)
                    while (session == my && !queue.offer(clip, 200, TimeUnit.MILLISECONDS)) {
                    }
                }
            } catch (e: Exception) {
            }
            while (session == my && !queue.offer(Clip(null, 0, 0, "", true), 200, TimeUnit.MILLISECONDS)) {
            }
        }.start()

        // Consumer: plays clips in order.
        Thread {
            val player = PcmPlayer { session != my }
            try {
                while (session == my) {
                    val c = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    if (c.end) break
                    if (c.pcm != null) {
                        player.play(c)
                    } else {
                        player.drain()
                        speakDevice(c.text, my)
                    }
                }
                player.drain()
            } catch (e: Exception) {
            } finally {
                player.release()
                if (session == my) {
                    speaking = false
                    main.post(onFinished)
                }
            }
        }.start()
    }

    // ------------------------------------------------------------ engine choice

    private fun pickEngine(bengali: Boolean): String {
        val now = System.currentTimeMillis()
        val groqOk = store.key("Groq").isNotEmpty() && now > groqBlockedUntil
        val geminiOk = store.key("Gemini").isNotEmpty() && now > geminiBlockedUntil
        return when (store.ttsEngine) {
            "device" -> "device"
            "groq" -> if (!bengali && groqOk) "groq" else if (bengali && geminiOk) "gemini" else "device"
            "gemini" -> if (geminiOk) "gemini" else "device"
            else -> when {
                bengali -> if (geminiOk) "gemini" else "device"
                groqOk -> "groq"
                geminiOk -> "gemini"
                else -> "device"
            }
        }
    }

    private fun fetch(text: String): Clip {
        val engine = pickEngine(hasBengali(text))
        var clip: Clip? = null
        try {
            clip = when (engine) {
                "groq" -> fetchGroq(text)
                "gemini" -> fetchGemini(text)
                else -> null
            }
        } catch (e: Exception) {
            if (engine == "groq") {
                groqBlockedUntil = System.currentTimeMillis() + 120_000
                notice("The natural voice couldn't connect, so I'm using the phone's voice for a moment.")
            } else if (engine == "gemini") {
                geminiBlockedUntil = System.currentTimeMillis() + 120_000
                notice("The Gemini voice couldn't connect, so I'm using the phone's voice for a moment.")
            }
        }
        return clip ?: Clip(null, 0, 0, text)
    }

    private fun onCloudError(engine: String, code: Int, body: String) {
        val ms: Long
        val msg: String
        if (code == 429) {
            ms = 60_000L
            msg = "The natural voice hit its free limit. Using the phone's voice for a minute."
        } else if (engine == "groq" && body.contains("terms", ignoreCase = true)) {
            ms = 600_000L
            msg = "To turn on the natural voice, accept the Orpheus model terms once at " +
                "console.groq.com/playground?model=canopylabs/orpheus-v1-english. Using the phone's voice for now."
        } else if (code == 401 || code == 403) {
            ms = 600_000L
            msg = "The ${if (engine == "groq") "Groq" else "Gemini"} key was rejected for voice. Using the phone's voice."
        } else {
            ms = 300_000L
            msg = "The natural voice is unavailable (HTTP $code). Using the phone's voice for now."
        }
        val until = System.currentTimeMillis() + ms
        if (engine == "groq") groqBlockedUntil = until else geminiBlockedUntil = until
        notice(msg)
    }

    // ------------------------------------------------------------ Groq Orpheus (English)

    private fun fetchGroq(text: String): Clip? {
        val conn = URL("https://api.groq.com/openai/v1/audio/speech").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 25000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer " + store.key("Groq"))
            conn.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject()
                .put("model", "canopylabs/orpheus-v1-english")
                .put("input", text)
                .put("voice", store.groqVoice)
                .put("response_format", "wav")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                onCloudError("groq", code, err)
                return null
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            return parseWav(bytes)
        } finally {
            conn.disconnect()
        }
    }

    private fun le16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, o: Int): Int =
        le16(b, o) or (le16(b, o + 2) shl 16)

    private fun parseWav(b: ByteArray): Clip? {
        if (b.size < 44 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF") return null
        var pos = 12
        var rate = 24000
        var ch = 1
        var bits = 16
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII)
            val size = le32(b, pos + 4)
            val body = pos + 8
            if (id == "fmt ") {
                ch = le16(b, body + 2)
                rate = le32(b, body + 4)
                bits = le16(b, body + 14)
            } else if (id == "data") {
                var len = size
                if (len <= 0 || body + len > b.size) len = b.size - body
                if (bits != 16 || ch < 1) return null
                return Clip(b.copyOfRange(body, body + len), rate, ch, "")
            }
            if (size < 0) return null
            pos = body + size + (size and 1)
        }
        return null
    }

    // ------------------------------------------------------------ Gemini TTS (English + Bengali)

    private fun fetchGemini(text: String): Clip? {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
            store.geminiTtsModel + ":generateContent"
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 30000
            conn.doOutput = true
            conn.setRequestProperty("x-goog-api-key", store.key("Gemini"))
            conn.setRequestProperty("Content-Type", "application/json")
            val prompt = "Say this naturally, in a warm, relaxed, conversational tone: $text"
            val body = JSONObject()
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                    )
                )
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("responseModalities", JSONArray().put("AUDIO"))
                        .put(
                            "speechConfig",
                            JSONObject().put(
                                "voiceConfig",
                                JSONObject().put(
                                    "prebuiltVoiceConfig",
                                    JSONObject().put("voiceName", store.geminiVoice)
                                )
                            )
                        )
                )
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                onCloudError("gemini", code, err)
                return null
            }
            val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val inline = JSONObject(resp)
                .optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
                ?.optJSONObject("inlineData") ?: return null
            val data = inline.optString("data")
            if (data.isEmpty()) return null
            val mime = inline.optString("mimeType")
            val rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: 24000
            return Clip(Base64.decode(data, Base64.DEFAULT), rate, 1, "")
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------ phone's own voice

    private fun speakDevice(text: String, my: Int) {
        val engine = tts ?: return
        if (!ttsReady) return
        val bengali = hasBengali(text)
        val tag = if (bengali) "bn" else "en"
        if (tag != lastLang) {
            lastLang = tag
            if (bengali) {
                val r = engine.setLanguage(Locale("bn", "BD"))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    lastLang = ""
                    if (!warnedBengali) {
                        warnedBengali = true
                        notice(
                            "This phone has no Bangla voice installed. Add a Gemini key for natural Bangla, " +
                                "or install Google's Bangla voice in Settings > Languages > Text-to-speech."
                        )
                    }
                    return
                }
            } else {
                val v = chosenDeviceVoice(engine)
                if (v != null) engine.setVoice(v) else engine.setLanguage(Locale.US)
            }
        }
        engine.setSpeechRate(store.speechRate)
        engine.setPitch(store.pitch)
        val latch = CountDownLatch(1)
        deviceLatch = latch
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "j" + System.nanoTime())
        while (session == my && !latch.await(100, TimeUnit.MILLISECONDS)) {
        }
        if (session != my) {
            try {
                engine.stop()
            } catch (e: Exception) {
            }
        }
    }

    private fun chosenDeviceVoice(engine: TextToSpeech): Voice? {
        val all: Set<Voice> = try {
            engine.voices ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
        val wanted = store.deviceVoice
        if (wanted.isNotEmpty()) {
            val hit = all.firstOrNull { it.name == wanted }
            if (hit != null) return hit
        }
        return bestVoices(all).firstOrNull()
    }

    private fun bestVoices(all: Set<Voice>): List<Voice> =
        all.filter {
            it.locale.language == "en" &&
                !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        }.sortedWith(
            compareByDescending<Voice> { it.quality }
                .thenByDescending { it.locale.country == "US" }
                .thenBy { it.isNetworkConnectionRequired }
        )

    /** English voices installed on this phone, best first. */
    fun deviceVoices(): List<Voice> {
        val e = tts ?: return emptyList()
        val all: Set<Voice> = try {
            e.voices ?: emptySet()
        } catch (ex: Exception) {
            emptySet()
        }
        return bestVoices(all)
    }
}
