# Sahayak — Setup Guide

This guide walks you through running **Sahayak** on an Android device or emulator. The app uses on-device Gemma 4 via LiteRT LLM for AI inference, ML Kit for OCR, and Android's built-in Speech-to-Text and Text-to-Speech APIs.

---

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Android Studio | Hedgehog (2023.1.1) or newer | [Download](https://developer.android.com/studio) |
| JDK | 17 | Bundled with Android Studio |
| ADB | Any | Bundled with Android Studio |
| Android device or emulator | API 26+ (Android 8.0+) | Physical device strongly recommended — model is 2.5 GB |

---

## Step 1 — Open the Project

1. Open **Android Studio**.
2. Click **File > Open** and select the `sahayak-backend` folder.
3. Click **Sync Project with Gradle Files** (the elephant icon in the toolbar) and wait for it to complete.

---

## Step 2 — Download the Gemma 4 Model

The app uses **Gemma 4 E2B** in LiteRT LLM format (`.litertlm`).

1. Go to the [litert-community Gemma 4 page on Hugging Face](https://huggingface.co/litert-community/Gemma-4-E2B-IT-int4)
2. Download `gemma-4-E2B-it.litertlm` (~2.5 GB)
3. You will need a Hugging Face account and must accept the Gemma model license

---

## Step 3 — Push the Model to the Device

The model is too large to bundle in the APK. Push it directly using `adb`.

Connect your device via USB with **USB Debugging** enabled, then run:

```bash
adb push gemma-4-E2B-it.litertlm /data/local/tmp/gemma-4-E2B-it.litertlm
```

> This takes several minutes over USB. Verify it landed:
> ```bash
> adb shell ls -lh /data/local/tmp/gemma-4-E2B-it.litertlm
> ```

**Full ADB path on Mac (if `adb` is not in PATH):**
```bash
/Users/<your-username>/Library/Android/sdk/platform-tools/adb push ...
```

---

## Step 4 — Build and Run

1. Select your connected device from the device dropdown in Android Studio.
2. Click **Run 'app'** (the green ▶ button) or press `Shift + F10`.
3. On first launch:
   - Grant **Camera** permission when prompted.
   - Grant **Microphone** permission when prompted (required for voice conversation).
   - The AI engine loads the model in the background — this takes **30–60 seconds** on first run. Wait before tapping buttons.

---

## Step 5 — Using the App

### Help with Physical Form

1. Point the camera at any physical form or document.
2. Tap **"Help with Physical Form"**.
3. You will hear a shutter click — the photo is taken immediately. You can lower your phone.
4. The app runs OCR on the image to extract text, then opens the **Form Conversation screen**.
5. Gemma greets you and summarizes the form aloud via Text-to-Speech.
6. **Just speak** — the app is always listening. Ask questions like:
   - *"What is this form for?"*
   - *"What goes in the date of birth field?"*
   - *"Is there anything sensitive I should be careful about?"*
7. Gemma responds aloud and the conversation is shown as chat bubbles.
8. Tap **✕** to exit the conversation.

### Screen Sentinel (Scam Blocker)

1. Tap **"Start Screen Sentinel"**.
2. You will be redirected to the system **"Display over other apps"** settings — enable it for Sahayak.
3. Press back — the floating **"🛡️ Check Screen"** button appears on screen.
4. Navigate to any app and tap the floating button to analyze the screen for scams.

---

## Emulator Setup (if not using a physical device)

Running the 2.5 GB model on an emulator requires extra configuration:

1. Go to **Tools > Device Manager > Create Virtual Device**.
2. Select **Pixel 6** (or similar). Choose **API 35, x86_64**.
3. Click **Show Advanced Settings** and set:
   - **RAM:** 6144 MB (6 GB minimum)
   - **VM Heap:** 1024 MB
   - **Internal Storage:** 8192 MB
   - **Back Camera:** `VirtualScene` or `Webcam0`
4. Launch the AVD, then push the model file as in Step 3.

> Note: Speech-to-Text requires a Google account signed in on the emulator and may not work reliably. A physical device is strongly recommended for the voice conversation feature.

---

## Build Configuration

| Setting | Value |
|---|---|
| `compileSdk` / `targetSdk` | 35 |
| `minSdk` | 26 |
| Kotlin | 2.3.21 |
| AGP | 8.7.3 |
| Gradle wrapper | 8.9 |
| JVM target | 17 |

Key dependencies:
- `com.google.ai.edge.litertlm:litertlm-android:0.11.0` — on-device LLM inference
- `com.google.mlkit:text-recognition:16.0.1` — OCR for form text extraction
- `io.noties.markwon:core:4.6.2` — Markdown rendering in chat dialog

---

## Troubleshooting

| Problem | Cause | Solution |
|---|---|---|
| App crashes on launch | Device RAM too low | Needs 4 GB+ free RAM; close background apps |
| "Model file not found" in logcat | Model not pushed | Re-run the `adb push` command in Step 3 |
| Model push fails — "no space left" | Device/emulator storage full | Free space or increase AVD internal storage to 8 GB |
| AI gives no response | Model still loading | Wait 30–60 seconds after launch before using |
| Voice conversation doesn't start | Microphone permission denied | Go to **Settings > Apps > Sahayak > Permissions > Microphone** |
| TTS doesn't speak | No TTS engine installed | Go to **Settings > Accessibility > Text-to-speech** and install Google TTS |
| Camera shows black screen | Permission not granted or AVD camera not configured | Check camera permission; set AVD camera to `VirtualScene` |
| Overlay button doesn't appear | Overlay permission not granted | Go to **Settings > Apps > Sahayak > Display over other apps** |
| `adb: command not found` | ADB not in PATH | Use full path: `~/Library/Android/sdk/platform-tools/adb` (Mac) |
