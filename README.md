# Sahayak 

Sahayak is a 100% offline, privacy-first Android application designed to help senior citizens navigate the digital and physical world safely. It uses on-device Machine Learning (Google's Gemma via MediaPipe) without sending any data to the cloud.

## Prerequisites
*   [Android Studio](https://developer.android.com/studio) (Jellyfish or newer recommended)
*   An Android device running Android 8.0 (API level 26) or higher. (A physical device with at least 4GB-6GB RAM is strongly recommended for running Gemma on-device).
*   ADB (Android Debug Bridge) installed and configured.

## Setup Instructions

### 1. Open the Project
Currently, the core code files are located in the `app/` directory of this folder. 
1. Open Android Studio.
2. Select **File > New > Import Project...** (or "Open" if you have a `settings.gradle.kts` file).
3. Navigate to this `sahayak-backend` folder (or the `app` folder specifically if you want to start fresh) and open it.
*Note: If Android Studio doesn't automatically recognize it as an Android project, you may need to create a new "Empty Activity (Compose)" project and copy the `app/src` and `app/build.gradle.kts` contents over to the new project.*

### 2. Download the Gemma Model
You need a MediaPipe-compatible Gemma model (`.bin` format) to run the AI engine.

1.  Go to the [Gemma models page on Kaggle](https://www.kaggle.com/models/google/gemma/frameworks/mediapipe).
2.  Download the **Gemma 2B IT (Instruction Tuned)** model optimized for CPU or GPU (e.g., `gemma-2b-it-cpu-int4.bin` or `gemma-2b-it-gpu-int4.bin`).

### 3. Push the Model to Your Device
Since the model files are very large (1.5GB+), it is highly recommended to push them directly to the device's local storage rather than bundling them inside the APK.

1. Connect your Android device to your computer via USB (ensure USB Debugging is enabled).
2. Open your terminal/command prompt.
3. Use ADB to push the model file to a temporary directory on the device:
   ```bash
   adb push /path/to/your/downloaded/gemma-2b-it-cpu-int4.bin /data/local/tmp/gemma_model.bin
   ```
4. **Important**: In `MainActivity.kt`, ensure the `gemmaEngine.initialize(...)` path matches where you pushed the file:
   ```kotlin
   // Inside MainActivity.kt onCreate
   gemmaEngine.initialize("/data/local/tmp/gemma_model.bin")
   ```

### 4. Build and Run
1. In Android Studio, click the **Sync Project with Gradle Files** button (the elephant icon).
2. Once synced, click the **Run 'app'** button (the green play button) or press `Shift + F10` to install the app on your connected device.

## Usage Guide
*   **Grant Permissions**: On first launch, grant the Camera permission. 
*   **Physical Form Helper**: Tap the large "Help with Physical Form" button. The app will capture an image using the camera and the AI will analyze it to provide guidance.
*   **Screen Sentinel**: Tap "Start Screen Sentinel". You will be prompted to grant the **"Display over other apps"** permission. Once granted, a floating "Check Screen" button will appear. You can navigate to any other app (like your browser or SMS) and tap this button to have the AI analyze the screen for scams.

## Hackathon Notes (Disclaimer)
*   **Multimodal Inference**: Standard `tasks-genai` primarily supports text input. For full image-to-text processing on forms/screens, you will need to implement an OCR step (like Google ML Kit Text Recognition) to extract text from the `Bitmap` before passing it to the Gemma prompt, or switch to a VLM (Vision-Language Model) task if supported in the latest MediaPipe release. The code contains `TODO` markers where this should be implemented.
*   **Screen Capture**: The floating button currently mocks screen capture. To capture real screens globally, you must implement the `MediaProjection` API or an `AccessibilityService`. 


