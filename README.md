# Jarvis 2.0

A voice assistant for Android: modern Compose UI, natural-sounding voices, and an AI that
can actually operate your phone through tool calling.

## What's new in 2.0

**Interface**
- Fully English, rebuilt in Jetpack Compose with a dark theme and an animated voice orb that reacts to your voice
- Chat with action pills ("Setting an alarm for 06:30"), selectable text, suggestion shortcuts
- Notes, tasks and memory screen; full settings screen
- Photo attach and camera: ask Jarvis about a picture

**Voice**
- Natural voices: Groq Orpheus (English) and Gemini TTS (English and Bangla), with automatic fallback to the phone's own voice
- Sentence-by-sentence streaming playback, so speech starts quickly and has natural phrasing
- Replies are written for the ear (contractions, spoken numbers, no markdown)
- Live speech-to-text with partial results, English or Bangla
- Hands-free conversation mode: Jarvis listens again after it speaks
- Tap the mic while it talks to interrupt

**Brain**
- Tool calling instead of keyword matching, so you can say things naturally in any wording
- Conversation history, long-term memory ("remember that I prefer tea"), custom instructions
- Providers: Groq, OpenRouter, Gemini, or any OpenAI-compatible endpoint

**Phone actions (22 tools)**
Alarms, timers, flashlight, open any app, call by contact name, SMS / WhatsApp / email drafts,
find contacts, web / YouTube / music search, maps and navigation, media and volume control,
battery / storage / network status, settings screens, calendar events, notes, tasks,
weather, currency conversion, Wikipedia, calculator, clipboard and share, dice and coin.

## Setup

1. Push to GitHub. The Action builds `app-debug.apk` (Actions tab, artifact `jarvis-apk`).
2. Install the APK and open Settings in the app.
3. Add a key. Free options: Groq (console.groq.com/keys) or Gemini (aistudio.google.com/apikey).
4. For the natural English voice: accept the terms for the Orpheus model once at
   console.groq.com/playground?model=canopylabs/orpheus-v1-english
5. For natural Bangla: add a Gemini key. Gemini's free voice quota is small, so Jarvis falls back to the phone's voice when it runs out.

## Local build on Fedora

```
sudo dnf install android-tools
./gradlew installDebug      # phone connected with USB debugging
adb logcat | grep -i jarvis
```

## Privacy

Your messages and tool results (for example contact names you ask about) go to the AI provider you choose.
API keys are stored on the phone in app-private storage.
