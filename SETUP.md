# Sahayak — Local Emulator Setup Guide

This guide walks you through running **Sahayak** on an Android Emulator on your local machine using on-device Gemma via MediaPipe.

---

## Prerequisites

Before you begin, ensure the following are installed:

| Tool | Version | Download |
|---|---|---|
| Android Studio | Jellyfish (2023.3.1) or newer | [Download](https://developer.android.com/studio) |
| JDK | 17 or newer | Bundled with Android Studio |
| ADB | Any (bundled with Android Studio) | Bundled with Android Studio |
| Git | Any | [Download](https://git-scm.com/) |

---

## Step 1 — Open the Project in Android Studio

1. Open **Android Studio**.
2. Click **File > Open** and navigate to the `sahayak-backend` folder.
3. If Android Studio does not recognize it as an Android project automatically:
   - Create a new project: **File > New > New Project > Empty Activity (Compose)**.
   - Choose `com.sahayak` as the package name.
   - Copy the contents of `app/src` and `app/build.gradle.kts` from this repository into the new project.
4. Click the **Sync Project with Gradle Files** button (the elephant 🐘 icon in the top toolbar). Wait for the sync to complete.

---

## Step 2 — Create a High-RAM Android Virtual Device (AVD)

Running a 2B-parameter model on an emulator requires significantly more RAM than the default settings. Follow these steps carefully:

1. In Android Studio, go to **Tools > Device Manager**.
2. Click **Create Virtual Device** (the `+` button).
3. Select **Phone > Pixel 6** (or any device with a large screen). Click **Next**.
4. Under **System Image**, select **API Level 33 or 34 (Android 13/14)** with the **x86_64** architecture. Download it if necessary. Click **Next**.
5. On the final screen, click **Show Advanced Settings**.
6. Set the following:
   - **RAM:** `4096 MB` (4 GB) minimum. Set to `6144 MB` (6 GB) if your computer has 16GB+ RAM.
   - **VM Heap:** `1024 MB`
   - **Internal Storage:** `8192 MB` (8 GB) — needed for the model file.
   - **Camera (Front & Back):** Set to `VirtualScene` or `Webcam0` to enable camera capture.
7. Click **Finish** to create the AVD.
8. Launch the emulator by clicking the **Play ▶** button next to the AVD.

---

## Step 3 — Download the Gemma Model

## Step 3 — Download the Gemma Model

You need a MediaPipe-compatible Gemma `.task` model file.

1. Go to the [Gemma 4 LiteRT page on Hugging Face](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/blob/main/gemma-4-E2B-it-web.task).
2. Download the **Gemma 4 E2B Task Bundle**:
   - Filename: `gemma-4-E2B-it-web.task`
   - Size: ~2.0 GB
   - > ⚠️ Do **not** download `.tar.gz` (Transformers) or raw `.litertlm` files. You need the `.task` bundle.

---

## Step 4 — Push the Model to the Emulator

The model file is too large to bundle in the APK. Use Android Studio's **Device Explorer** to copy it directly to the emulator's storage.

1. Ensure the emulator is **running** (from Step 2).
2. In Android Studio, go to **View > Tool Windows > Device Explorer** (or click it on the right sidebar).
3. Follow this strict folder path: expand **`data`** ➡️ **`local`** ➡️ **`tmp`**.
   - > 🛑 **DANGER:** Do *not* upload to the root `/tmp` folder at the top of the list! The root `/tmp` is a tiny RAM disk and will instantly give you a "No space left on device" error.
4. Right-click the `tmp` folder (inside `/data/local/`) and select **Upload**.
5. Select your downloaded `gemma-4-E2B-it-web.task` file. (This takes a few minutes).
6. Once uploaded, right-click the file in the Device Explorer, select **Rename**, and change it exactly to:
   `gemma_model.bin`

---

## Step 5 — Enable the Model in the Code

The model initialization is commented out by default. Uncomment it in `MainActivity.kt`:

1. Open `app/src/main/java/com/sahayak/MainActivity.kt`.
2. Find these lines (around line 48):
   ```kotlin
   // Initialize AI in background (Ensure model file is pushed to device storage)
   // For hackathon: adb push model.bin /data/local/tmp/
   // gemmaEngine.initialize("/data/local/tmp/gemma_model.bin")
   ```
3. Replace them with:
   ```kotlin
   // Initialize AI in background
   Executors.newSingleThreadExecutor().execute {
       kotlinx.coroutines.runBlocking {
           gemmaEngine.initialize("/data/local/tmp/gemma_model.bin")
       }
   }
   ```

---

## Step 6 — Build and Run

1. In Android Studio, select your running emulator from the device dropdown in the top toolbar.
2. Click **Run 'app'** (the green ▶ button) or press `Shift + F10`.
3. The app will build and install on the emulator.
4. On first launch:
   - Grant the **Camera** permission.
   - The AI engine will load the model in the background (this takes ~10–30 seconds on first run).

---

## Step 7 — Test the Features

### Physical Form Helper
1. Tap the large **"Help with Physical Form"** button.
2. The camera viewfinder will open (showing the VirtualScene in the emulator).
3. Tap the button again to capture and analyze.

### Screen Sentinel (Floating Button)
1. Tap **"Start Screen Sentinel"**.
2. You will be redirected to the system **"Display over other apps"** settings screen.
3. Enable the permission for **Sahayak**.
4. Press the back button — the floating **"🛡️ Check Screen"** button should appear.
5. Navigate to any other app and tap the floating button to trigger a screen analysis.

---

## Troubleshooting

| Problem | Cause | Solution |
|---|---|---|
| App crashes on launch | Emulator RAM too low | Increase AVD RAM to 6GB in Device Manager |
| `adb devices` shows nothing | ADB not in PATH | Use the full path: `C:\Users\...\AppData\Local\Android\Sdk\platform-tools\adb.exe` |
| Model push fails with "no space left" | Emulator storage too small | Increase Internal Storage to 8192 MB in AVD settings |
| AI gives no response after 30s | Model path wrong or OOM | Check `adb logcat` for `LocalGemmaEngine` tag errors |
| Camera shows black screen | Camera not configured | Set AVD camera to `VirtualScene` in Advanced Settings |
| Overlay button doesn't appear | Permission not granted | Go to **Settings > Apps > Sahayak > Display over other apps** and enable it manually |
