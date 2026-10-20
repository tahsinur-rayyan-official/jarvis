package com.jarvis.assistant

import android.Manifest
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

// ---------------------------------------------------------------- schema helpers

private fun pStr(desc: String): JSONObject =
    JSONObject().put("type", "string").put("description", desc)

private fun pInt(desc: String): JSONObject =
    JSONObject().put("type", "integer").put("description", desc)

private fun pNum(desc: String): JSONObject =
    JSONObject().put("type", "number").put("description", desc)

private fun pBool(desc: String): JSONObject =
    JSONObject().put("type", "boolean").put("description", desc)

private fun pEnum(desc: String, vararg values: String): JSONObject =
    JSONObject().put("type", "string").put("description", desc)
        .put("enum", JSONArray(values.toList()))

private fun props(vararg p: Pair<String, JSONObject>): JSONObject {
    val o = JSONObject()
    for ((k, v) in p) o.put(k, v)
    return o
}

private fun fn(
    name: String,
    desc: String,
    p: JSONObject = JSONObject(),
    required: List<String> = emptyList()
): JSONObject {
    val f = JSONObject().put("name", name).put("description", desc)
    if (p.length() > 0) {
        val params = JSONObject().put("type", "object").put("properties", p)
        if (required.isNotEmpty()) params.put("required", JSONArray(required))
        f.put("parameters", params)
    }
    return JSONObject().put("type", "function").put("function", f)
}

// ---------------------------------------------------------------- tools

class Tools(
    private val ctx: Context,
    private val store: Store,
    private val askPermission: (String) -> Unit
) {
    private var torchOn = false

    fun schemas(): JSONArray {
        val a = JSONArray()
        a.put(
            fn(
                "set_alarm",
                "Set an alarm for a clock time. Also use it for time-based reminders (put the reminder text in label).",
                props(
                    "hour" to pInt("Hour 0-23"),
                    "minute" to pInt("Minute 0-59"),
                    "label" to pStr("Alarm label"),
                    "days" to JSONObject().put("type", "array")
                        .put("items", JSONObject().put("type", "string"))
                        .put("description", "Optional repeat days such as mon, tue")
                ),
                listOf("hour", "minute")
            )
        )
        a.put(
            fn(
                "set_timer", "Start a countdown timer.",
                props("seconds" to pInt("Duration in seconds"), "label" to pStr("Timer label")),
                listOf("seconds")
            )
        )
        a.put(fn("set_flashlight", "Turn the flashlight on or off.", props("on" to pBool("true = on, false = off"))))
        a.put(
            fn(
                "open_app", "Open an installed app by its name as shown on the phone (English), e.g. YouTube, Camera, Settings.",
                props("name" to pStr("App name")), listOf("name")
            )
        )
        a.put(
            fn(
                "call_contact", "Call a saved contact or a phone number.",
                props("contact" to pStr("Contact name or phone number")), listOf("contact")
            )
        )
        a.put(
            fn(
                "find_contact", "Look up a contact's phone number.",
                props("name" to pStr("Contact name")), listOf("name")
            )
        )
        a.put(
            fn(
                "send_message", "Prepare a text message, WhatsApp message or email. The user taps send.",
                props(
                    "app" to pEnum("Which app", "sms", "whatsapp", "email"),
                    "to" to pStr("Contact name, phone number or email address"),
                    "message" to pStr("Message body"),
                    "subject" to pStr("Email subject")
                ),
                listOf("app", "message")
            )
        )
        a.put(
            fn(
                "search_web", "Open a search in the browser, YouTube or a music app (to play a song).",
                props(
                    "query" to pStr("What to search or play"),
                    "where" to pEnum("Where to search", "google", "youtube", "music")
                ),
                listOf("query")
            )
        )
        a.put(
            fn(
                "open_map", "Show a place on the map or start navigation to it.",
                props(
                    "place" to pStr("Place or address"),
                    "navigate" to pBool("true to start turn-by-turn navigation"),
                    "mode" to pEnum("Travel mode", "driving", "walking", "bicycling")
                ),
                listOf("place")
            )
        )
        a.put(
            fn(
                "media", "Control music playback and media volume.",
                props(
                    "action" to pEnum(
                        "Action", "play_pause", "play", "pause", "next", "previous", "stop",
                        "volume_up", "volume_down", "mute", "set_volume"
                    ),
                    "percent" to pInt("Volume 0-100 for set_volume")
                ),
                listOf("action")
            )
        )
        a.put(fn("phone_status", "Get battery level, charging state, storage and network status."))
        a.put(
            fn(
                "open_settings", "Open a phone settings screen.",
                props(
                    "panel" to pEnum(
                        "Which screen", "wifi", "bluetooth", "airplane", "display", "sound", "battery",
                        "location", "apps", "storage", "nfc", "date", "language", "accessibility", "settings"
                    )
                ),
                listOf("panel")
            )
        )
        a.put(
            fn(
                "add_calendar_event", "Open a pre-filled calendar event. The user taps save.",
                props(
                    "title" to pStr("Event title"),
                    "start" to pStr("Start as YYYY-MM-DD HH:MM (24h, local time)"),
                    "duration_minutes" to pInt("Length in minutes, default 60"),
                    "location" to pStr("Location")
                ),
                listOf("title", "start")
            )
        )
        a.put(
            fn(
                "note", "Save, list or delete the user's notes.",
                props(
                    "action" to pEnum("Action", "add", "list", "delete"),
                    "text" to pStr("Note text for add"),
                    "match" to pStr("Number or words of the note to delete")
                ),
                listOf("action")
            )
        )
        a.put(
            fn(
                "task", "Manage the user's to-do list.",
                props(
                    "action" to pEnum("Action", "add", "list", "done", "delete"),
                    "text" to pStr("Task text for add"),
                    "match" to pStr("Number or words of the task for done/delete")
                ),
                listOf("action")
            )
        )
        a.put(
            fn(
                "memory", "Remember or forget a lasting fact about the user (name of family, preferences, etc.).",
                props(
                    "action" to pEnum("Action", "remember", "forget"),
                    "text" to pStr("The fact, or words of the fact to forget")
                ),
                listOf("action", "text")
            )
        )
        a.put(
            fn(
                "get_weather", "Current weather and a 3-day forecast for a city.",
                props("city" to pStr("City name; leave empty for the user's home city"))
            )
        )
        a.put(
            fn(
                "convert_currency", "Convert an amount between currencies using live rates.",
                props(
                    "amount" to pNum("Amount"),
                    "from" to pStr("Currency code like USD"),
                    "to" to pStr("Currency code like BDT")
                ),
                listOf("amount", "from", "to")
            )
        )
        a.put(
            fn(
                "wikipedia", "Look up a short factual summary on Wikipedia.",
                props(
                    "topic" to pStr("Topic"),
                    "lang" to pEnum("Language", "en", "bn")
                ),
                listOf("topic")
            )
        )
        a.put(
            fn(
                "calculate", "Evaluate a math expression exactly. Supports + - * / % ^ ( ) sqrt sin cos tan log ln abs pi e.",
                props("expression" to pStr("Expression")), listOf("expression")
            )
        )
        a.put(
            fn(
                "clipboard", "Copy text to the clipboard, read the clipboard, or share text to another app.",
                props(
                    "action" to pEnum("Action", "copy", "read", "share"),
                    "text" to pStr("Text for copy or share")
                ),
                listOf("action")
            )
        )
        a.put(
            fn(
                "random", "Flip a coin, roll a die or pick a random number.",
                props(
                    "kind" to pEnum("Kind", "coin", "dice", "number"),
                    "min" to pInt("Lowest number"),
                    "max" to pInt("Highest number or die sides")
                ),
                listOf("kind")
            )
        )
        return a
    }

    /** Short text for the action pill shown in the chat. */
    fun describe(name: String, a: JSONObject): String = when (name) {
        "set_alarm" -> String.format(Locale.US, "Setting an alarm for %02d:%02d", a.optInt("hour"), a.optInt("minute"))
        "set_timer" -> "Starting a timer"
        "set_flashlight" -> "Flashlight"
        "open_app" -> "Opening " + a.optString("name")
        "call_contact" -> "Calling " + a.optString("contact")
        "find_contact" -> "Looking up " + a.optString("name")
        "send_message" -> "Preparing a " + a.optString("app", "message")
        "search_web" -> "Searching for " + a.optString("query")
        "open_map" -> "Maps: " + a.optString("place")
        "media" -> "Media: " + a.optString("action").replace('_', ' ')
        "phone_status" -> "Checking phone status"
        "open_settings" -> "Opening settings"
        "add_calendar_event" -> "Creating calendar event"
        "note" -> "Notes: " + a.optString("action")
        "task" -> "Tasks: " + a.optString("action")
        "memory" -> "Updating memory"
        "get_weather" -> "Checking the weather"
        "convert_currency" -> "Converting currency"
        "wikipedia" -> "Looking up " + a.optString("topic")
        "calculate" -> "Calculating"
        "clipboard" -> "Clipboard: " + a.optString("action")
        "random" -> "Picking at random"
        else -> name.replace('_', ' ')
    }

    suspend fun run(name: String, a: JSONObject): String = when (name) {
        "get_weather" -> withContext(Dispatchers.IO) { guard { weather(a.optString("city")) } }
        "convert_currency" -> withContext(Dispatchers.IO) { guard { currency(a) } }
        "wikipedia" -> withContext(Dispatchers.IO) { guard { wiki(a.optString("topic"), a.optString("lang", "en")) } }
        else -> withContext(Dispatchers.Main) { guard { runLocal(name, a) } }
    }

    private fun guard(block: () -> String): String = try {
        block()
    } catch (e: Exception) {
        "Error: " + (e.message ?: e.javaClass.simpleName)
    }

    private fun runLocal(name: String, a: JSONObject): String = when (name) {
        "set_alarm" -> alarm(a)
        "set_timer" -> timer(a)
        "set_flashlight" -> flashlight(a)
        "open_app" -> openApp(a.optString("name"))
        "call_contact" -> call(a.optString("contact"))
        "find_contact" -> findContactTool(a.optString("name"))
        "send_message" -> sendMessage(a)
        "search_web" -> search(a)
        "open_map" -> openMap(a)
        "media" -> media(a)
        "phone_status" -> phoneStatus()
        "open_settings" -> openSettings(a.optString("panel"))
        "add_calendar_event" -> calendar(a)
        "note" -> note(a)
        "task" -> task(a)
        "memory" -> memory(a)
        "calculate" -> calculate(a.optString("expression"))
        "clipboard" -> clip(a)
        "random" -> random(a)
        else -> "Unknown tool: $name"
    }

    // ---------------------------------------------------------------- helpers

    private fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    private fun go(i: Intent): Boolean = try {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        true
    } catch (e: Exception) {
        false
    }

    private fun view(url: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun normDigits(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            if (c in '\u09E6'..'\u09EF') sb.append('0' + (c - '\u09E6')) else sb.append(c)
        }
        return sb.toString()
    }

    private fun fmt(d: Double): String = String.format(Locale.US, "%,.2f", d)

    private fun httpGet(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 12000
            c.readTimeout = 15000
            c.setRequestProperty("User-Agent", "JarvisApp/2.0 (Android)")
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun findIndex(texts: List<String>, match: String): Int {
        val m = match.trim()
        val n = normDigits(m).toIntOrNull()
        if (n != null && n in 1..texts.size) return n - 1
        if (m.isEmpty()) return -1
        return texts.indexOfFirst { it.contains(m, ignoreCase = true) }
    }

    // ---------------------------------------------------------------- clock

    private fun alarm(a: JSONObject): String {
        val h = a.optInt("hour", -1)
        val m = a.optInt("minute", 0)
        if (h !in 0..23 || m !in 0..59) return "That isn't a valid time."
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val label = a.optString("label")
        if (label.isNotBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        val days = a.optJSONArray("days")
        if (days != null && days.length() > 0) {
            val list = ArrayList<Int>()
            for (k in 0 until days.length()) {
                when (days.optString(k).lowercase(Locale.ROOT).take(3)) {
                    "sun" -> list.add(Calendar.SUNDAY)
                    "mon" -> list.add(Calendar.MONDAY)
                    "tue" -> list.add(Calendar.TUESDAY)
                    "wed" -> list.add(Calendar.WEDNESDAY)
                    "thu" -> list.add(Calendar.THURSDAY)
                    "fri" -> list.add(Calendar.FRIDAY)
                    "sat" -> list.add(Calendar.SATURDAY)
                }
            }
            if (list.isNotEmpty()) i.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, list)
        }
        return if (go(i)) String.format(Locale.US, "Alarm set for %02d:%02d.", h, m) else "Couldn't set the alarm."
    }

    private fun timer(a: JSONObject): String {
        val s = a.optInt("seconds", 0)
        if (s <= 0) return "I need a duration for the timer."
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, s)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val label = a.optString("label")
        if (label.isNotBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        return if (go(i)) "Timer started for $s seconds." else "Couldn't start the timer."
    }

    // ---------------------------------------------------------------- device

    private fun flashlight(a: JSONObject): String {
        val want = if (a.has("on")) a.optBoolean("on") else !torchOn
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (id == null) {
                "This phone has no flashlight."
            } else {
                cm.setTorchMode(id, want)
                torchOn = want
                if (want) "Flashlight is on." else "Flashlight is off."
            }
        } catch (e: Exception) {
            "Couldn't control the flashlight."
        }
    }

    private fun openApp(name: String): String {
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
        val q = name.lowercase(Locale.ROOT).trim()
        if (q.isEmpty()) return "Which app?"
        fun label(ri: android.content.pm.ResolveInfo): String =
            ri.loadLabel(pm).toString().lowercase(Locale.ROOT)

        val hit = apps.firstOrNull { label(it) == q }
            ?: apps.firstOrNull { label(it).startsWith(q) }
            ?: apps.firstOrNull { label(it).contains(q) }
            ?: apps.firstOrNull { val l = label(it); l.length >= 3 && q.contains(l) }
        if (hit == null) return "I couldn't find an app called $name."
        val pkg = hit.activityInfo.packageName
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return "Couldn't open $name."
        return if (go(intent)) "Opened " + hit.loadLabel(pm) + "." else "Couldn't open $name."
    }

    private fun phoneStatus(): String {
        val sb = StringBuilder()
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val st = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = st?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        sb.append("Battery $level percent").append(if (charging) ", charging. " else ", not charging. ")
        try {
            val sf = StatFs(Environment.getDataDirectory().path)
            val free = sf.availableBytes / 1_000_000_000.0
            val total = sf.totalBytes / 1_000_000_000.0
            sb.append(String.format(Locale.US, "Storage: %.1f GB free of %.0f GB. ", free, total))
        } catch (e: Exception) {
        }
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val active = cm.activeNetwork
            val caps = if (active == null) null else cm.getNetworkCapabilities(active)
            val net = when {
                caps == null -> "offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
                else -> "connected"
            }
            sb.append("Network: $net. ")
        } catch (e: Exception) {
        }
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            sb.append("Media volume ${am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max} percent.")
        } catch (e: Exception) {
        }
        return sb.toString()
    }

    private fun openSettings(panel: String): String {
        val action = when (panel) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "nfc" -> Settings.ACTION_NFC_SETTINGS
            "date" -> Settings.ACTION_DATE_SETTINGS
            "language" -> Settings.ACTION_LOCALE_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return if (go(Intent(action))) "Opened $panel settings." else "Couldn't open that settings screen."
    }

    private fun media(a: JSONObject): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val act = a.optString("action")
        val key = when (act) {
            "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> -1
        }
        if (key != -1) {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            return "Sent $act to the media player."
        }
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        when (act) {
            "volume_up" -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
            }
            "volume_down" -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
            }
            "mute" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            "set_volume" -> {
                val pct = a.optInt("percent", -1)
                if (pct !in 0..100) return "I need a volume from 0 to 100 percent."
                am.setStreamVolume(AudioManager.STREAM_MUSIC, pct * max / 100, AudioManager.FLAG_SHOW_UI)
            }
            else -> return "Unknown media action."
        }
        val now = am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
        return "Media volume is now about $now percent."
    }

    // ---------------------------------------------------------------- contacts, calls, messages

    private class Target(val name: String, val number: String?, val problem: String?)

    private fun findContacts(q: String): List<Pair<String, String>> {
        val out = LinkedHashMap<String, String>()
        val tokens = listOf(q) + q.split(" ").filter { it.length >= 2 }
        for (t in tokens) {
            val c = ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                arrayOf("%$t%"),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )
            if (c != null) {
                c.use {
                    while (it.moveToNext() && out.size < 8) {
                        val n = it.getString(0)
                        val num = it.getString(1)
                        if (n != null && num != null && !out.containsKey(n)) out[n] = num
                    }
                }
            }
            if (out.isNotEmpty()) break
        }
        return out.entries.map { Pair(it.key, it.value) }
    }

    private fun resolveTarget(query: String): Target {
        val raw = normDigits(query.trim())
        val letters = raw.count { it.isLetter() }
        val digits = raw.count { it.isDigit() }
        if (letters == 0 && digits >= 3) {
            return Target(raw, raw.filter { it.isDigit() || it == '+' }, null)
        }
        if (!has(Manifest.permission.READ_CONTACTS)) {
            askPermission(Manifest.permission.READ_CONTACTS)
            return Target(
                raw, null,
                "Contacts permission is needed. I've asked the user to allow it; tell them to repeat the request after allowing."
            )
        }
        val hits = findContacts(raw)
        if (hits.isEmpty()) return Target(raw, null, "No contact found matching \"$raw\".")
        val exact = hits.firstOrNull { it.first.equals(raw, ignoreCase = true) }
        val pick = exact ?: if (hits.size == 1) hits[0] else null
        if (pick == null) {
            return Target(
                raw, null,
                "Several contacts match: " + hits.joinToString { it.first } + ". Ask the user which one."
            )
        }
        return Target(pick.first, pick.second, null)
    }

    private fun findContactTool(name: String): String {
        val t = resolveTarget(name)
        return if (t.number != null) "${t.name}: ${t.number}" else (t.problem ?: "Not found.")
    }

    private fun call(contact: String): String {
        val t = resolveTarget(contact)
        val number = t.number ?: return t.problem ?: "Couldn't find that contact."
        val uri = Uri.fromParts("tel", number, null)
        val wantDirect = store.directCall
        if (wantDirect && !has(Manifest.permission.CALL_PHONE)) {
            askPermission(Manifest.permission.CALL_PHONE)
        }
        val direct = wantDirect && has(Manifest.permission.CALL_PHONE)
        val ok = go(Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri))
        return when {
            !ok -> "Couldn't start the call."
            direct -> "Calling ${t.name}."
            else -> "Opened the dialer for ${t.name}. The user still has to press call."
        }
    }

    private fun waNumber(raw: String): String {
        val cleaned = normDigits(raw).filter { it.isDigit() || it == '+' }
        if (cleaned.startsWith("+")) return cleaned.drop(1).filter { it.isDigit() }
        val d = cleaned.filter { it.isDigit() }
        if (d.startsWith("00")) return d.drop(2)
        if (d.startsWith("0")) return store.countryCode + d.drop(1)
        return d
    }

    private fun sendMessage(a: JSONObject): String {
        val app = a.optString("app", "sms").lowercase(Locale.ROOT)
        val msg = a.optString("message")
        val to = a.optString("to")
        if (app == "email") {
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + to.trim()))
                .putExtra(Intent.EXTRA_SUBJECT, a.optString("subject"))
                .putExtra(Intent.EXTRA_TEXT, msg)
            return if (go(i)) "Opened an email draft. The user taps send." else "No email app found."
        }
        val t = resolveTarget(to)
        val number = t.number ?: return t.problem ?: "Couldn't find that contact."
        return if (app == "whatsapp") {
            val url = "https://wa.me/" + waNumber(number) + "?text=" + Uri.encode(msg)
            if (go(view(url))) "Opened WhatsApp with the message to ${t.name} ready. The user taps send."
            else "Couldn't open WhatsApp."
        } else {
            val i = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))
                .putExtra("sms_body", msg)
            if (go(i)) "Opened a text message to ${t.name} with the message ready. The user taps send."
            else "Couldn't open the messages app."
        }
    }

    // ---------------------------------------------------------------- web, maps, calendar

    private fun search(a: JSONObject): String {
        val q = a.optString("query").trim()
        if (q.isEmpty()) return "What should I search for?"
        val yt = "https://www.youtube.com/results?search_query=" + enc(q)
        return when (a.optString("where", "google")) {
            "youtube" -> if (go(view(yt))) "Searching YouTube for $q." else "Couldn't open YouTube."
            "music" -> {
                val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                    .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    .putExtra(SearchManager.QUERY, q)
                if (go(i)) "Asked the music app to play $q."
                else if (go(view(yt))) "No music app handled it, so I searched YouTube for $q."
                else "No music app found."
            }
            else -> if (go(view("https://www.google.com/search?q=" + enc(q)))) "Searching the web for $q."
            else "Couldn't open the browser."
        }
    }

    private fun openMap(a: JSONObject): String {
        val place = a.optString("place").trim()
        if (place.isEmpty()) return "Which place?"
        val q = Uri.encode(place)
        val navigate = a.optBoolean("navigate", false)
        val mode = when (a.optString("mode")) {
            "walking" -> "w"
            "bicycling" -> "b"
            else -> "d"
        }
        val primary = if (navigate) view("google.navigation:q=$q&mode=$mode") else view("geo:0,0?q=$q")
        if (go(primary)) return if (navigate) "Starting navigation to $place." else "Showing $place on the map."
        return if (go(view("https://www.google.com/maps/search/?api=1&query=$q"))) "Opened $place in maps."
        else "No maps app found."
    }

    private fun calendar(a: JSONObject): String {
        val title = a.optString("title", "Event")
        val start = a.optString("start").replace('T', ' ').trim()
        val parsed: Date? = try {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(start)
        } catch (e: Exception) {
            null
        }
        if (parsed == null) return "I couldn't read that date. Use the format YYYY-MM-DD HH:MM."
        val minutes = a.optInt("duration_minutes", 60).coerceAtLeast(1)
        val begin = parsed.time
        val i = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + minutes * 60_000L)
            .putExtra(CalendarContract.Events.TITLE, title)
        val loc = a.optString("location")
        if (loc.isNotBlank()) i.putExtra(CalendarContract.Events.EVENT_LOCATION, loc)
        return if (go(i)) "Opened a calendar event for \"$title\". The user taps save." else "No calendar app found."
    }

    // ---------------------------------------------------------------- notes, tasks, memory

    private fun note(a: JSONObject): String {
        val list = store.loadNotes()
        when (a.optString("action")) {
            "add" -> {
                val t = a.optString("text").trim()
                if (t.isEmpty()) return "There's nothing to save."
                list.add(Note(store.newId(), t, System.currentTimeMillis()))
                store.saveNotes(list)
                return "Saved as note number ${list.size}."
            }
            "list" -> return if (list.isEmpty()) "There are no notes yet."
            else list.mapIndexed { i, n -> "${i + 1}. ${n.text}" }.joinToString("\n")
            "delete" -> {
                val idx = findIndex(list.map { it.text }, a.optString("match"))
                if (idx < 0) return "I couldn't find a matching note."
                val gone = list.removeAt(idx)
                store.saveNotes(list)
                return "Deleted the note: ${gone.text}"
            }
        }
        return "Unknown note action."
    }

    private fun task(a: JSONObject): String {
        val list = store.loadTasks()
        when (a.optString("action")) {
            "add" -> {
                val t = a.optString("text").trim()
                if (t.isEmpty()) return "What's the task?"
                list.add(Task(store.newId(), t, false))
                store.saveTasks(list)
                return "Added to your to-do list."
            }
            "list" -> return if (list.isEmpty()) "Your to-do list is empty."
            else list.mapIndexed { i, t -> "${i + 1}. ${if (t.done) "[done] " else ""}${t.text}" }.joinToString("\n")
            "done" -> {
                val idx = findIndex(list.map { it.text }, a.optString("match"))
                if (idx < 0) return "I couldn't find that task."
                list[idx] = list[idx].copy(done = true)
                store.saveTasks(list)
                return "Marked done: ${list[idx].text}"
            }
            "delete" -> {
                val idx = findIndex(list.map { it.text }, a.optString("match"))
                if (idx < 0) return "I couldn't find that task."
                val gone = list.removeAt(idx)
                store.saveTasks(list)
                return "Removed the task: ${gone.text}"
            }
        }
        return "Unknown task action."
    }

    private fun memory(a: JSONObject): String {
        val list = store.loadMemories()
        val text = a.optString("text").trim()
        if (text.isEmpty()) return "What should I remember?"
        return when (a.optString("action")) {
            "remember" -> {
                if (list.none { it.equals(text, ignoreCase = true) }) list.add(text)
                if (list.size > 40) list.removeAt(0)
                store.saveMemories(list)
                "Got it, I'll remember that."
            }
            "forget" -> {
                val idx = findIndex(list, text)
                if (idx < 0) {
                    "I don't have anything like that saved."
                } else {
                    list.removeAt(idx)
                    store.saveMemories(list)
                    "Okay, I've forgotten that."
                }
            }
            else -> "Unknown memory action."
        }
    }

    // ---------------------------------------------------------------- misc

    private fun clip(a: JSONObject): String {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = a.optString("text")
        when (a.optString("action")) {
            "copy" -> {
                cm.setPrimaryClip(ClipData.newPlainText("Jarvis", text))
                return "Copied to the clipboard."
            }
            "read" -> {
                val clipData = cm.primaryClip
                val t = if (clipData != null && clipData.itemCount > 0) {
                    clipData.getItemAt(0).coerceToText(ctx)?.toString().orEmpty()
                } else {
                    ""
                }
                return if (t.isEmpty()) "The clipboard is empty." else "Clipboard: " + t.take(600)
            }
            "share" -> {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                return if (go(Intent.createChooser(send, "Share"))) "Opened the share sheet." else "Couldn't share."
            }
        }
        return "Unknown clipboard action."
    }

    private fun random(a: JSONObject): String {
        return when (a.optString("kind")) {
            "coin" -> if (Random.nextBoolean()) "Heads." else "Tails."
            "dice" -> {
                val sides = a.optInt("max", 6).coerceAtLeast(2)
                "Rolled a ${Random.nextInt(1, sides + 1)} on a $sides-sided die."
            }
            else -> {
                val lo = a.optInt("min", 1)
                val hi = a.optInt("max", 100)
                if (hi < lo) "The maximum must be at least the minimum."
                else "Your number is ${Random.nextInt(lo, hi + 1)}."
            }
        }
    }

    private fun calculate(expr: String): String {
        val cleaned = normDigits(expr).replace('\u00D7', '*').replace('\u00F7', '/')
            .replace(",", "").lowercase(Locale.ROOT)
        return try {
            val v = Calc(cleaned).parse()
            if (v.isNaN() || v.isInfinite()) {
                "That has no valid result."
            } else if (v == Math.floor(v) && abs(v) < 1e15) {
                "Result: " + v.toLong().toString()
            } else {
                "Result: " + String.format(Locale.US, "%.8f", v).trimEnd('0').trimEnd('.')
            }
        } catch (e: Exception) {
            "I couldn't evaluate that: " + (e.message ?: "invalid expression")
        }
    }

    private class Calc(private val s: String) {
        private var pos = 0

        fun parse(): Double {
            val v = expr()
            skip()
            if (pos < s.length) throw IllegalArgumentException("unexpected '" + s[pos] + "'")
            return v
        }

        private fun skip() {
            while (pos < s.length && s[pos] == ' ') pos++
        }

        private fun eat(c: Char): Boolean {
            skip()
            if (pos < s.length && s[pos] == c) {
                pos++
                return true
            }
            return false
        }

        private fun expr(): Double {
            var v = term()
            while (true) {
                v = if (eat('+')) v + term() else if (eat('-')) v - term() else return v
            }
        }

        private fun term(): Double {
            var v = unary()
            while (true) {
                v = if (eat('*')) v * unary()
                else if (eat('/')) v / unary()
                else if (eat('%')) v % unary()
                else return v
            }
        }

        private fun unary(): Double =
            if (eat('-')) -unary() else if (eat('+')) unary() else power()

        private fun power(): Double {
            val base = atom()
            return if (eat('^')) Math.pow(base, unary()) else base
        }

        private fun atom(): Double {
            skip()
            if (eat('(')) {
                val v = expr()
                if (!eat(')')) throw IllegalArgumentException("missing )")
                return v
            }
            val start = pos
            if (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) {
                while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
                return s.substring(start, pos).toDouble()
            }
            while (pos < s.length && s[pos].isLetter()) pos++
            val id = s.substring(start, pos)
            if (id.isEmpty()) throw IllegalArgumentException("unexpected input")
            if (id == "pi") return Math.PI
            if (id == "e") return Math.E
            if (!eat('(')) throw IllegalArgumentException("unknown word '$id'")
            val arg = expr()
            if (!eat(')')) throw IllegalArgumentException("missing )")
            return when (id) {
                "sqrt" -> Math.sqrt(arg)
                "sin" -> Math.sin(Math.toRadians(arg))
                "cos" -> Math.cos(Math.toRadians(arg))
                "tan" -> Math.tan(Math.toRadians(arg))
                "log" -> Math.log10(arg)
                "ln" -> Math.log(arg)
                "abs" -> Math.abs(arg)
                else -> throw IllegalArgumentException("unknown function '$id'")
            }
        }
    }

    // ---------------------------------------------------------------- online lookups

    private fun wmo(code: Int): String = when (code) {
        0 -> "clear sky"
        1 -> "mostly clear"
        2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "foggy"
        51, 53, 55 -> "drizzle"
        56, 57 -> "freezing drizzle"
        61 -> "light rain"
        63 -> "rain"
        65 -> "heavy rain"
        66, 67 -> "freezing rain"
        71, 73, 75, 77 -> "snow"
        80, 81, 82 -> "rain showers"
        85, 86 -> "snow showers"
        95 -> "thunderstorm"
        96, 99 -> "thunderstorm with hail"
        else -> "unsettled weather"
    }

    private fun weather(city: String): String {
        val name = city.trim().ifEmpty { store.homeCity }
        if (name.isEmpty()) return "No city was given and no home city is set. Ask the user which city."
        val geo = JSONObject(
            httpGet("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name=" + enc(name))
        )
        val results = geo.optJSONArray("results") ?: return "I couldn't find a place called $name."
        val p = results.getJSONObject(0)
        val lat = p.getDouble("latitude")
        val lon = p.getDouble("longitude")
        val place = p.optString("name") + (if (p.has("country")) ", " + p.optString("country") else "")
        val f = JSONObject(
            httpGet(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&forecast_days=4&timezone=auto"
            )
        )
        val cur = f.getJSONObject("current")
        val sb = StringBuilder()
        sb.append("Weather in $place right now: ${wmo(cur.optInt("weather_code"))}, ")
        sb.append("${cur.optDouble("temperature_2m").roundToInt()} degrees Celsius ")
        sb.append("(feels like ${cur.optDouble("apparent_temperature").roundToInt()}), ")
        sb.append("humidity ${cur.optInt("relative_humidity_2m")} percent, ")
        sb.append("wind ${cur.optDouble("wind_speed_10m").roundToInt()} km/h.\n")
        val d = f.getJSONObject("daily")
        val days = d.getJSONArray("time")
        val codes = d.getJSONArray("weather_code")
        val hi = d.getJSONArray("temperature_2m_max")
        val lo = d.getJSONArray("temperature_2m_min")
        val rain = d.optJSONArray("precipitation_probability_max")
        for (i in 0 until days.length()) {
            val label = if (i == 0) "Today" else days.getString(i)
            sb.append("$label: ${wmo(codes.optInt(i))}, high ${hi.optDouble(i).roundToInt()}, ")
            sb.append("low ${lo.optDouble(i).roundToInt()}")
            if (rain != null) sb.append(", rain chance ${rain.optInt(i, 0)} percent")
            sb.append(".\n")
        }
        return sb.toString()
    }

    private fun currency(a: JSONObject): String {
        val amount = a.optDouble("amount", 1.0)
        val from = a.optString("from").uppercase(Locale.ROOT).trim()
        val to = a.optString("to").uppercase(Locale.ROOT).trim()
        if (from.length != 3 || to.length != 3) return "I need two 3-letter currency codes."
        val j = JSONObject(httpGet("https://open.er-api.com/v6/latest/$from"))
        if (j.optString("result") != "success") return "I couldn't get rates for $from."
        val rate = j.getJSONObject("rates").optDouble(to, Double.NaN)
        if (rate.isNaN()) return "I don't know the currency $to."
        return "${fmt(amount)} $from is about ${fmt(amount * rate)} $to (1 $from = ${fmt(rate)} $to)."
    }

    private fun wiki(topic: String, lang0: String): String {
        val lang = if (lang0 == "bn") "bn" else "en"
        val os = JSONArray(
            httpGet("https://$lang.wikipedia.org/w/api.php?action=opensearch&limit=1&format=json&search=" + enc(topic))
        )
        val titles = os.optJSONArray(1)
        if (titles == null || titles.length() == 0) return "I found no Wikipedia article for \"$topic\"."
        val title = titles.getString(0)
        val s = JSONObject(
            httpGet("https://$lang.wikipedia.org/api/rest_v1/page/summary/" + enc(title).replace("+", "%20"))
        )
        return "$title: " + s.optString("extract").take(900)
    }
}
