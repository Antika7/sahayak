# RFC 003: Asynchronous AI Engine Initialization

## Status
Accepted

## Context
The Sahayak app relies on Google's Gemma models (via MediaPipe), which require loading a ~1.3GB to 2.0GB `.bin` or `.task` file from the device's local storage (`/data/local/tmp/gemma_model.bin`) directly into memory. 

Originally, the call to `gemmaEngine.initialize()` was blocking the main thread during app launch. Because reading a 2GB file from flash storage into RAM takes several seconds, this caused the Android UI to completely freeze, leading to "Application Not Responding" (ANR) errors or a stalled splash screen.

## Decision
We refactored the initialization logic in `MainActivity.kt` to ensure the heavy I/O operations are strictly confined to a background thread.

We achieved this by wrapping the initialization in an executor service:
```kotlin
Executors.newSingleThreadExecutor().execute {
    kotlinx.coroutines.runBlocking {
        gemmaEngine.initialize("/data/local/tmp/gemma_model.bin")
    }
}
```

## Consequences
- **Positive**: The main thread remains unblocked, allowing Jetpack Compose to render the UI immediately. The app feels much more responsive on launch.
- **Negative**: The UI must now handle a "loading" or "uninitialized" state. If a user presses the "Help with Physical Form" button before the background thread finishes loading the 2GB model, the app needs to gracefully inform them that the AI is still "waking up."
