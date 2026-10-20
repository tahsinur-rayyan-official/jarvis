package com.jarvis.assistant

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

// ======================================================================= building blocks

@Composable
fun JField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1
) {
    Column(modifier) {
        Text(label, color = C.textDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(color = C.text, fontSize = 15.sp),
            cursorBrush = SolidColor(C.signal),
            singleLine = singleLine,
            minLines = minLines,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier
                .fillMaxWidth()
                .background(C.bg, RoundedCornerShape(12.dp))
                .border(1.dp, C.stroke, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 11.dp)
        )
    }
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            title,
            color = C.text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Column(
            Modifier
                .fillMaxWidth()
                .background(C.surface, RoundedCornerShape(18.dp))
                .border(1.dp, C.stroke, RoundedCornerShape(18.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content
        )
    }
}

@Composable
fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = C.text, fontSize = 15.sp)
            if (sub.isNotEmpty()) Text(sub, color = C.textDim, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = C.bg,
                checkedTrackColor = C.signal,
                uncheckedThumbColor = C.textDim,
                uncheckedTrackColor = C.bg,
                uncheckedBorderColor = C.stroke
            )
        )
    }
}

@Composable
fun ChipRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for ((value, label) in options) {
            Pill(label, value == selected) { onSelect(value) }
        }
    }
}

@Composable
fun ActionButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(C.signal.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
            .border(1.dp, C.signal, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = C.signal, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Hint(text: String) {
    Text(text, color = C.textDim, fontSize = 12.sp, lineHeight = 17.sp)
}

// ======================================================================= notes, tasks, memory

@Composable
fun LibraryScreen(vm: JarvisViewModel, onBack: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    var draft by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
    ) {
        TopBar("Notes and tasks", onBack)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Pill("Notes", tab == 0) { tab = 0; draft = "" }
            Pill("Tasks", tab == 1) { tab = 1; draft = "" }
            Pill("Memory", tab == 2) { tab = 2; draft = "" }
        }
        if (tab == 2) {
            Box(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Hint("Things Jarvis remembers about you. Say \"remember that I prefer tea\" and it shows up here.")
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (tab) {
                0 -> {
                    if (vm.notes.isEmpty()) item { EmptyHint("No notes yet. Add one below or say \"save a note\".") }
                    items(vm.notes, key = { it.id }) { n ->
                        ListCard {
                            Text(n.text, color = C.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            IconButton(onClick = { vm.deleteNote(n.id) }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Filled.Close, contentDescription = "Delete note", tint = C.textDim)
                            }
                        }
                    }
                }
                1 -> {
                    if (vm.tasks.isEmpty()) item { EmptyHint("Your to-do list is empty.") }
                    items(vm.tasks, key = { it.id }) { t ->
                        ListCard {
                            Checkbox(
                                checked = t.done,
                                onCheckedChange = { vm.toggleTask(t.id) },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = C.signal,
                                    uncheckedColor = C.textDim,
                                    checkmarkColor = C.bg
                                )
                            )
                            Text(
                                t.text,
                                color = if (t.done) C.textDim else C.text,
                                fontSize = 15.sp,
                                textDecoration = if (t.done) TextDecoration.LineThrough else TextDecoration.None,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { vm.deleteTask(t.id) }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Filled.Close, contentDescription = "Delete task", tint = C.textDim)
                            }
                        }
                    }
                }
                else -> {
                    if (vm.memories.isEmpty()) item { EmptyHint("Nothing remembered yet.") }
                    itemsIndexed(vm.memories) { i, m ->
                        ListCard {
                            Text(m, color = C.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            IconButton(onClick = { vm.deleteMemory(i) }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Filled.Close, contentDescription = "Forget this", tint = C.textDim)
                            }
                        }
                    }
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            JField(
                value = draft,
                onChange = { draft = it },
                label = when (tab) {
                    0 -> "New note"
                    1 -> "New task"
                    else -> "Something to remember"
                },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    when (tab) {
                        0 -> vm.addNote(draft)
                        1 -> vm.addTask(draft)
                        else -> vm.addMemory(draft)
                    }
                    draft = ""
                },
                modifier = Modifier
                    .size(46.dp)
                    .background(C.signal, RoundedCornerShape(12.dp))
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add", tint = C.bg)
            }
        }
    }
}

@Composable
fun ListCard(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(C.surface, RoundedCornerShape(14.dp))
            .border(1.dp, C.stroke, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
fun EmptyHint(text: String) {
    Text(
        text,
        color = C.textDim,
        fontSize = 14.sp,
        modifier = Modifier.padding(vertical = 24.dp, horizontal = 4.dp)
    )
}

// ======================================================================= settings

@Composable
fun KeyField(store: Store, provider: String, label: String) {
    var v by remember { mutableStateOf(store.key(provider)) }
    JField(v, { v = it; store.setKey(provider, it) }, label, secret = true)
}

@Composable
fun SettingsScreen(vm: JarvisViewModel, onRequestPermission: (String) -> Unit, onBack: () -> Unit) {
    val store = vm.store
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
    ) {
        TopBar("Settings", onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            AiSection(store)
            VoiceSection(vm)
            AssistantSection(vm)
            PermissionSection(vm, onRequestPermission)
            Section("Data") {
                ActionButton("Clear conversation") { vm.clearChat() }
                Hint("Notes, tasks and memories stay until you delete them. API keys are stored on this phone only.")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun AiSection(store: Store) {
    var provider by remember { mutableStateOf(store.provider) }
    val p = PROVIDERS.firstOrNull { it.name == provider } ?: PROVIDERS[0]
    var tools by remember { mutableStateOf(store.toolsEnabled) }

    Section("AI brain") {
        Text("Provider", color = C.textDim, fontSize = 12.sp)
        ChipRow(PROVIDERS.map { Pair(it.name, it.name) }, provider) {
            provider = it
            store.provider = it
        }
        KeyField(store, "Groq", "Groq API key (console.groq.com/keys)")
        KeyField(store, "OpenRouter", "OpenRouter API key")
        KeyField(store, "Gemini", "Gemini API key (aistudio.google.com/apikey)")
        if (provider == "Custom") KeyField(store, "Custom", "Custom API key")
        var model by remember(provider) { mutableStateOf(store.model(p)) }
        JField(model, { model = it; store.setModel(p, it) }, "Chat model")
        var vmodel by remember(provider) { mutableStateOf(store.visionModel(p)) }
        JField(vmodel, { vmodel = it; store.setVisionModel(p, it) }, "Photo understanding model")
        if (provider == "Custom") {
            var url by remember { mutableStateOf(store.baseUrl(p)) }
            JField(url, { url = it; store.setBaseUrl(p, it) }, "Base URL (OpenAI-compatible)")
        }
        SwitchRow(
            "Phone actions",
            "Lets the AI set alarms, make calls, open apps and more. Turn off if a model fails with tools.",
            tools
        ) {
            tools = it
            store.toolsEnabled = it
        }
    }
}

@Composable
fun VoiceSection(vm: JarvisViewModel) {
    val store = vm.store
    var engine by remember { mutableStateOf(store.ttsEngine) }
    var groqVoice by remember { mutableStateOf(store.groqVoice) }
    var geminiVoice by remember { mutableStateOf(store.geminiVoice) }
    var geminiModel by remember { mutableStateOf(store.geminiTtsModel) }
    var rate by remember { mutableStateOf(store.speechRate) }
    var pitch by remember { mutableStateOf(store.pitch) }
    var deviceVoice by remember { mutableStateOf(store.deviceVoice) }
    var showVoices by remember { mutableStateOf(false) }

    Section("Voice") {
        SwitchRow("Speak replies", "Jarvis talks back out loud.", vm.speakReplies) { vm.setSpeakReplies(it) }
        Text("Voice engine", color = C.textDim, fontSize = 12.sp)
        ChipRow(
            listOf(
                "auto" to "Auto",
                "groq" to "Groq natural",
                "gemini" to "Gemini natural",
                "device" to "Phone voice"
            ),
            engine
        ) {
            engine = it
            store.ttsEngine = it
            vm.speaker.settingsChanged()
        }
        Hint(
            "Auto uses Groq's Orpheus voices for English and Gemini voices for Bangla when keys are set, " +
                "and falls back to the phone's voice if they're unavailable. Groq needs a one-time terms " +
                "acceptance for the Orpheus model in the Groq console."
        )
        Text("Groq voice (English)", color = C.textDim, fontSize = 12.sp)
        ChipRow(
            listOf("daniel", "troy", "austin", "hannah", "diana", "autumn").map { Pair(it, it.replaceFirstChar { c -> c.uppercase() }) },
            groqVoice
        ) {
            groqVoice = it
            store.groqVoice = it
        }
        Text("Gemini voice (English and Bangla)", color = C.textDim, fontSize = 12.sp)
        ChipRow(
            listOf("Kore", "Puck", "Charon", "Aoede", "Zephyr", "Fenrir", "Leda", "Orus").map { Pair(it, it) },
            geminiVoice
        ) {
            geminiVoice = it
            store.geminiVoice = it
        }
        JField(geminiModel, { geminiModel = it; store.geminiTtsModel = it }, "Gemini voice model")
        Text("Phone voice", color = C.textDim, fontSize = 12.sp)
        Text(
            if (deviceVoice.isEmpty()) "Best available (automatic)" else deviceVoice,
            color = C.text,
            fontSize = 14.sp
        )
        ActionButton(if (showVoices) "Hide phone voices" else "Choose a phone voice") { showVoices = !showVoices }
        if (showVoices) {
            val voices = vm.speaker.deviceVoices()
            if (voices.isEmpty()) {
                Hint("No English voices found yet. Install Google Text-to-speech from the Play Store.")
            }
            Pill("Automatic", deviceVoice.isEmpty()) {
                deviceVoice = ""
                store.deviceVoice = ""
                vm.speaker.settingsChanged()
            }
            for (v in voices.take(14)) {
                val q = when {
                    v.quality >= 500 -> "very high"
                    v.quality >= 400 -> "high"
                    v.quality >= 300 -> "normal"
                    else -> "basic"
                }
                val label = v.name + ", " + v.locale.displayName + ", " + q +
                    (if (v.isNetworkConnectionRequired) ", online" else "")
                Pill(label, deviceVoice == v.name) {
                    deviceVoice = v.name
                    store.deviceVoice = v.name
                    vm.speaker.settingsChanged()
                }
            }
        }
        Text("Phone voice speed", color = C.textDim, fontSize = 12.sp)
        Slider(
            value = rate,
            onValueChange = { rate = it; store.speechRate = it; vm.speaker.settingsChanged() },
            valueRange = 0.6f..1.5f,
            colors = SliderDefaults.colors(
                thumbColor = C.signal,
                activeTrackColor = C.signal,
                inactiveTrackColor = C.stroke
            )
        )
        Text("Phone voice pitch", color = C.textDim, fontSize = 12.sp)
        Slider(
            value = pitch,
            onValueChange = { pitch = it; store.pitch = it; vm.speaker.settingsChanged() },
            valueRange = 0.7f..1.3f,
            colors = SliderDefaults.colors(
                thumbColor = C.signal,
                activeTrackColor = C.signal,
                inactiveTrackColor = C.stroke
            )
        )
        ActionButton("Test the voice") { vm.testVoice() }
    }
}

@Composable
fun AssistantSection(vm: JarvisViewModel) {
    val store = vm.store
    var name by remember { mutableStateOf(store.userName) }
    var city by remember { mutableStateOf(store.homeCity) }
    var cc by remember { mutableStateOf(store.countryCode) }
    var extra by remember { mutableStateOf(store.instructions) }
    var autoListen by remember { mutableStateOf(store.autoListen) }
    var direct by remember { mutableStateOf(store.directCall) }

    Section("Assistant") {
        JField(name, { name = it; store.userName = it }, "Your name")
        JField(city, { city = it; store.homeCity = it }, "Home city (for weather)")
        JField(cc, { cc = it; store.countryCode = it }, "Default country code for WhatsApp numbers")
        JField(
            extra, { extra = it; store.instructions = it },
            "Extra instructions (tone, language, anything)",
            singleLine = false, minLines = 3
        )
        SwitchRow("Hands-free conversation", "After Jarvis speaks, it listens again automatically.", vm.handsFree) {
            vm.setHandsFree(it)
        }
        SwitchRow("Listen on launch", "Start listening as soon as the app opens.", autoListen) {
            autoListen = it
            store.autoListen = it
        }
        SwitchRow("Call without the dial screen", "Needs the Phone permission. Off means you press call yourself.", direct) {
            direct = it
            store.directCall = it
        }
    }
}

@Composable
fun PermissionSection(vm: JarvisViewModel, onRequest: (String) -> Unit) {
    val ctx = LocalContext.current
    val tick = vm.permTick
    fun granted(p: String): Boolean =
        tick >= 0 && ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    Section("Permissions") {
        PermRow("Microphone", "Needed to hear you.", granted(Manifest.permission.RECORD_AUDIO)) {
            onRequest(Manifest.permission.RECORD_AUDIO)
        }
        PermRow("Contacts", "Lets Jarvis call or message people by name.", granted(Manifest.permission.READ_CONTACTS)) {
            onRequest(Manifest.permission.READ_CONTACTS)
        }
        PermRow("Phone calls", "Only used if you turn on direct calling.", granted(Manifest.permission.CALL_PHONE)) {
            onRequest(Manifest.permission.CALL_PHONE)
        }
    }
}

@Composable
fun PermRow(title: String, sub: String, granted: Boolean, onGrant: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = C.text, fontSize = 15.sp)
            Text(sub, color = C.textDim, fontSize = 12.sp)
        }
        if (granted) {
            Text("Allowed", color = C.signal, fontSize = 13.sp)
        } else {
            ActionButton("Allow", onGrant)
        }
    }
}
