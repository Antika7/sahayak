# Sahayak

**[View project website](https://sahayak-lake.vercel.app/)**

Sahayak is a 100% offline, privacy-first Android app designed to help senior citizens navigate the digital and physical world safely. All AI inference runs on-device using Google's Gemma via the LiteRT LLM runtime — no data is ever sent to the cloud.

## Features

- **Form Helper** — Point the camera at any physical paper form. ML Kit OCR extracts the text, then the on-device Gemma model guides the user through every blank field one at a time, using their saved profile to pre-fill known values (name, DOB, city, PAN, etc.). Location can be fetched via GPS for address fields. Conversation is voice-first with full TTS + speech recognition.
- **Screen Sentinel** — A draggable floating button sits over all apps. Tap it anytime to have Gemma analyse the current screen for scams, phishing, or misleading content and return a colour-coded risk assessment (Safe / Caution / Warning) with a plain-language explanation. Powered by `ScamDetectorAccessibilityService` which reads the view hierarchy on demand without capturing screenshots.
- **Hindi support** — Both features respond entirely in Hindi (Devanagari) when the user selects Hindi as their preferred language.

## Requirements

- [Android Studio](https://developer.android.com/studio) Jellyfish or newer
- Physical Android device running Android 8.0 (API 26) or higher
- **Minimum 6 GB RAM** recommended — the Gemma model requires ~2 GB at runtime
- ADB (Android Debug Bridge) installed and configured

## Setup

### 1. Open the project

Open Android Studio, choose **File → Open**, and select the `sahayak-backend` folder.

### 2. Download the Gemma model

Sahayak uses the **LiteRT LLM** runtime (`com.google.ai.edge.litertlm:litertlm-android:0.11.0`).

1. Download the model from [litert-community/gemma-4-E2B-it-litert-lm](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) on Hugging Face.
2. The app is configured for `gemma-4-E2B-it.litertlm`. Download that file or update `DEFAULT_MODEL_PATH` in `LocalGemmaEngine.kt` to match your filename.

### 3. Push the model to the device

Model files are too large to bundle in the APK. Push directly to the device:

```bash
adb push /path/to/gemma-4-E2B-it.litertlm /data/local/tmp/gemma-4-E2B-it.litertlm
```

The default path in `LocalGemmaEngine.kt` is:
```kotlin
const val DEFAULT_MODEL_PATH = "/data/local/tmp/gemma-4-E2B-it.litertlm"
```

Update this constant if you push the file to a different location.

### 4. Build and run

1. Click **Sync Project with Gradle Files** in Android Studio.
2. Click **Run 'app'** (or `Shift + F10`) to install on your connected device.

## First Launch

1. Complete the onboarding screen — enter your name, preferred language, date of birth, city, spouse name, and PAN number. These are stored locally using Jetpack DataStore and used to pre-fill forms automatically.
2. Grant **Camera** permission when prompted (required for Form Helper).

## Usage

### Form Helper
1. From the home screen, tap **Form Helper**.
2. Point the camera at a physical form and tap the capture button.
3. The app extracts the form text via OCR, then the AI walks you through each field. Speak or type your responses.

### Screen Sentinel
1. From the home screen, tap **Screen Sentinel**.
2. Tap **Start Sentinel**. Grant the **Display over other apps** permission when prompted.
3. Enable **Sahayak** in **Settings → Accessibility** so the service can read screen content.
4. Navigate to any app. Tap the floating Sahayak button to analyse what's on screen.
5. A colour-coded card appears at the bottom of the screen:
   - **Blue — Safe**: nothing suspicious detected
   - **Orange — Caution**: potentially misleading content
   - **Red — Warning**: likely scam or phishing attempt
6. Tap **Why?** for a detailed explanation, the speaker icon to hear it read aloud, or **Ask more** to open a full conversation about what's on screen.
7. To stop Sentinel, return to the Sahayak home screen and tap **Stop Sentinel**.

## Permissions

| Permission | Used for |
|---|---|
| `CAMERA` | Capturing form photos in Form Helper |
| `RECORD_AUDIO` | Speech recognition during conversations |
| `SYSTEM_ALERT_WINDOW` | Drawing the floating Sentinel button and result overlay |
| `FOREGROUND_SERVICE` | Keeping the Sentinel overlay alive while in background |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Auto-filling address fields on forms |
| `BIND_ACCESSIBILITY_SERVICE` | Reading on-screen text for scam detection |

## Architecture

```
SahayakApp                  — Application singleton; owns the LocalGemmaEngine instance
│
├── MainActivity             — Single-activity host; Compose NavHost with 5 screens
│   ├── WelcomeScreen        — First-launch onboarding
│   ├── HomeHubScreen        — Central dashboard
│   ├── FormHelperScreen     — Camera preview + capture UI
│   ├── SentinelStatusScreen — Start/stop Sentinel, accessibility status
│   └── ProfileScreen        — Edit saved preferences
│
├── ConversationActivity     — Voice-first chat UI (Form Helper & Screen Helper modes)
│
├── FloatingWindowService    — Foreground service; draws overlay views via WindowManager
│
├── ScamDetectorAccessibilityService — Reads view hierarchy on demand; no event streaming
│
├── LocalGemmaEngine         — LiteRT LLM (Gemma) + ML Kit OCR; all inference on-device
│
├── UserPreferences          — Jetpack DataStore wrapper for user profile fields
│
├── PrivacyFilter            — Redacts credit card, SSN, and Aadhaar numbers before LLM input
│
└── ScreenAnalysis           — ScreenContext (input) and ScreenAnalysisResult (output) data classes
```
