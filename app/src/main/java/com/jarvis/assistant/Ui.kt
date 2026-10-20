package com.jarvis.assistant

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.sin

// ======================================================================= theme

object C {
    val bg = Color(0xFF0B0F17)
    val bg2 = Color(0xFF101726)
    val surface = Color(0xFF121826)
    val surfaceHi = Color(0xFF182136)
    val stroke = Color(0xFF212C45)
    val text = Color(0xFFE9EDF5)
    val textDim = Color(0xFF8C97AD)
    val signal = Color(0xFF3CCFE0)
    val violet = Color(0xFF7C6CF2)
    val userBubble = Color(0xFF1D2B4D)
    val danger = Color(0xFFF2766B)
    val micBrush = Brush.linearGradient(listOf(Color(0xFF3CCFE0), Color(0xFF7C6CF2)))
}

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.signal,
            onPrimary = C.bg,
            secondary = C.violet,
            background = C.bg,
            onBackground = C.text,
            surface = C.surface,
            onSurface = C.text,
            error = C.danger
        ),
        content = content
    )
}

enum class Screen { CHAT, LIBRARY, SETTINGS }

// ======================================================================= root

@Composable
fun JarvisApp(
    vm: JarvisViewModel,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit,
    onRequestPermission: (String) -> Unit
) {
    var screen by remember { mutableStateOf(Screen.CHAT) }
    BackHandler(enabled = screen != Screen.CHAT) { screen = Screen.CHAT }

    val view = LocalView.current
    val busy = vm.status != Status.IDLE || vm.handsFree
    SideEffect { view.keepScreenOn = busy }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(C.bg, C.bg2)))
    ) {
        when (screen) {
            Screen.CHAT -> ChatScreen(
                vm,
                onLibrary = { screen = Screen.LIBRARY },
                onSettings = { screen = Screen.SETTINGS },
                onPickImage = onPickImage,
                onTakePhoto = onTakePhoto
            )
            Screen.LIBRARY -> LibraryScreen(vm) { screen = Screen.CHAT }
            Screen.SETTINGS -> SettingsScreen(vm, onRequestPermission) { screen = Screen.CHAT }
        }
    }
}

// ======================================================================= small components

@Composable
fun Pill(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .background(if (selected) C.signal.copy(alpha = 0.16f) else C.surface, shape)
            .border(1.dp, if (selected) C.signal else C.stroke, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) C.signal else C.textDim,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun Orb(status: Status, level: Float, diameter: Dp) {
    val t = rememberInfiniteTransition(label = "orb")
    val phase by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "phase"
    )
    val lvl by animateFloatAsState(level, tween(120), label = "level")
    val energy = when (status) {
        Status.LISTENING -> 1f
        Status.SPEAKING -> 0.85f
        Status.THINKING -> 0.7f
        Status.IDLE -> 0.45f
    }
    Canvas(Modifier.size(diameter)) {
        val r = size.minDimension / 2f
        for (k in 0..2) {
            val p = (phase + k / 3f) % 1f
            val rr = r * (0.5f + 0.5f * p + 0.2f * lvl)
            drawCircle(
                color = C.signal.copy(alpha = (1f - p) * 0.30f * energy),
                radius = rr,
                center = center
            )
        }
        val breathe = 0.03f * sin(phase * 2f * PI.toFloat())
        val coreR = r * (0.40f + 0.16f * lvl + breathe)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(C.signal, C.violet),
                center = center,
                radius = coreR * 1.25f
            ),
            radius = coreR,
            center = center
        )
    }
}

@Composable
fun TypingDots() {
    val t = rememberInfiniteTransition(label = "dots")
    Row(
        Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        for (i in 0..2) {
            val a by t.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(600, delayMillis = i * 150), RepeatMode.Reverse),
                label = "dot$i"
            )
            Box(
                Modifier
                    .size(8.dp)
                    .background(C.signal.copy(alpha = a), CircleShape)
            )
        }
    }
}

private fun statusText(s: Status): String = when (s) {
    Status.IDLE -> "Ready"
    Status.LISTENING -> "Listening"
    Status.THINKING -> "Thinking"
    Status.SPEAKING -> "Speaking"
}

private fun greeting(): String {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when {
        h < 4 -> "Hello"
        h < 12 -> "Good morning"
        h < 17 -> "Good afternoon"
        else -> "Good evening"
    }
}

// ======================================================================= chat screen

@Composable
fun ChatScreen(
    vm: JarvisViewModel,
    onLibrary: () -> Unit,
    onSettings: () -> Unit,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
    ) {
        ChatHeader(vm, onLibrary, onSettings)
        ControlsRow(vm)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (vm.messages.isEmpty()) EmptyState(vm) else MessageList(vm)
        }
        BottomBar(vm, onPickImage, onTakePhoto)
    }
}

@Composable
fun ChatHeader(vm: JarvisViewModel, onLibrary: () -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Orb(vm.status, vm.level, 38.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Jarvis", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(statusText(vm.status), color = C.textDim, fontSize = 12.sp)
        }
        IconButton(onClick = onLibrary) {
            Icon(Icons.Filled.Create, contentDescription = "Notes and tasks", tint = C.textDim)
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = C.textDim)
        }
    }
}

@Composable
fun ControlsRow(vm: JarvisViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Pill("English", vm.sttLang == "en-US") { vm.setLang("en-US") }
        Pill("Bangla", vm.sttLang == "bn-BD") { vm.setLang("bn-BD") }
        Pill(if (vm.handsFree) "Hands-free on" else "Hands-free off", vm.handsFree) {
            vm.setHandsFree(!vm.handsFree)
        }
        Pill(if (vm.speakReplies) "Voice on" else "Voice off", vm.speakReplies) {
            vm.setSpeakReplies(!vm.speakReplies)
        }
    }
}

@Composable
fun EmptyState(vm: JarvisViewModel) {
    val name = vm.store.userName
    val suggestions = listOf(
        "What's the weather like today?",
        "Set a timer for 10 minutes",
        "Wake me up at 6:30 tomorrow",
        "How much battery is left?",
        "Convert 100 US dollars to taka",
        "Save a note: buy milk",
        "Tell me something interesting"
    )
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Orb(Status.IDLE, 0f, 150.dp)
        Spacer(Modifier.height(12.dp))
        Text(
            if (name.isEmpty()) greeting() else greeting() + ", " + name,
            color = C.text,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Tap the mic or type. I can control your phone, set alarms, check the weather and more.",
            color = C.textDim,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        for (s in suggestions.take(5)) {
            Box(
                Modifier
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(C.surface, RoundedCornerShape(14.dp))
                    .border(1.dp, C.stroke, RoundedCornerShape(14.dp))
                    .clickable { vm.submit(s) }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(s, color = C.text, fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun MessageList(vm: JarvisViewModel) {
    val state = rememberLazyListState()
    val total = vm.messages.size + if (vm.status == Status.THINKING) 1 else 0
    LaunchedEffect(total) {
        if (total > 0) state.animateScrollToItem(total - 1)
    }
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(vm.messages, key = { it.id }) { m -> Bubble(m) }
        if (vm.status == Status.THINKING) {
            item { TypingDots() }
        }
    }
}

@Composable
fun Bubble(m: ChatMessage) {
    when (m.kind) {
        Kind.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Column(horizontalAlignment = Alignment.End) {
                val img = m.image
                if (img != null) {
                    val bmp = remember(img) { img.asImageBitmap() }
                    Image(
                        bitmap = bmp,
                        contentDescription = "Attached image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .padding(bottom = 4.dp)
                            .size(170.dp)
                            .clip(RoundedCornerShape(16.dp))
                    )
                }
                Text(
                    m.text,
                    color = C.text,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .background(
                            C.userBubble,
                            RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 6.dp, bottomStart = 20.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
        Kind.ASSISTANT -> Row(Modifier.fillMaxWidth()) {
            val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 6.dp)
            SelectionContainer {
                Text(
                    m.text,
                    color = C.text,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .background(C.surface, shape)
                        .border(1.dp, C.stroke, shape)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
        Kind.ACTION -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text(
                m.text,
                color = C.signal,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .background(C.signal.copy(alpha = 0.10f), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            )
        }
        Kind.ERROR -> Row(Modifier.fillMaxWidth()) {
            val shape = RoundedCornerShape(16.dp)
            Text(
                m.text,
                color = C.danger,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .background(C.danger.copy(alpha = 0.10f), shape)
                    .border(1.dp, C.danger.copy(alpha = 0.4f), shape)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}

// ======================================================================= bottom bar

@Composable
fun BottomBar(vm: JarvisViewModel, onPickImage: () -> Unit, onTakePhoto: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val image = vm.pendingImage
    val hasText = text.isNotBlank() || image != null

    fun send() {
        if (text.isNotBlank() || vm.pendingImage != null) {
            vm.submit(text)
            text = ""
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        if (vm.status != Status.IDLE) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Orb(vm.status, vm.level, 72.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        statusText(vm.status),
                        color = C.text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (vm.status == Status.LISTENING) {
                        Text(
                            if (vm.partial.isEmpty()) "Go ahead, I'm listening" else vm.partial,
                            color = C.textDim,
                            fontSize = 14.sp,
                            maxLines = 3
                        )
                    }
                }
            }
        }

        if (image != null) {
            Row(
                Modifier.padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val bmp = remember(image) { image.asImageBitmap() }
                Image(
                    bitmap = bmp,
                    contentDescription = "Attached image",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
                IconButton(onClick = { vm.clearImage() }) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove image", tint = C.textDim)
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            val pillShape = RoundedCornerShape(28.dp)
            Row(
                Modifier
                    .weight(1f)
                    .background(C.surface, pillShape)
                    .border(1.dp, C.stroke, pillShape)
                    .padding(start = 4.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPickImage, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Image, contentDescription = "Attach a photo", tint = C.textDim)
                }
                IconButton(onClick = onTakePhoto, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = "Take a photo", tint = C.textDim)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .padding(start = 6.dp, top = 12.dp, bottom = 12.dp)
                ) {
                    if (text.isEmpty()) {
                        Text("Ask Jarvis anything", color = C.textDim, fontSize = 16.sp)
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        textStyle = TextStyle(color = C.text, fontSize = 16.sp),
                        cursorBrush = SolidColor(C.signal),
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(C.micBrush, CircleShape)
                    .clickable { if (hasText) send() else vm.onMicClick() },
                contentAlignment = Alignment.Center
            ) {
                if (hasText) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = C.bg)
                } else {
                    when (vm.status) {
                        Status.IDLE -> Icon(Icons.Filled.Mic, contentDescription = "Talk to Jarvis", tint = C.bg)
                        Status.LISTENING -> Icon(Icons.Filled.Stop, contentDescription = "Finish listening", tint = C.bg)
                        Status.SPEAKING -> Icon(Icons.Filled.Stop, contentDescription = "Stop speaking", tint = C.bg)
                        Status.THINKING -> Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = C.bg)
                    }
                }
            }
        }
    }
}

// ======================================================================= shared form pieces

@Composable
fun TopBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = C.text)
        }
        Text(title, color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}
