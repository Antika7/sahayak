# RFC 005: Model Deployment Guide (Avoiding the Root /tmp Trap)

## Status
Accepted

## Context
Deploying large AI models (~2GB) directly to an Android emulator or physical device requires pushing the model binary to the device's file system so the application can access it at runtime without bundling it inside the APK.

During setup, developers frequently encounter the `write failed: No space left on device` error. This typically happens for two reasons:
1. **The Root `/tmp` Trap**: Developers see the path `/data/local/tmp/` and mistakenly attempt to upload the file to the root `/tmp` folder instead. On Android, the root `/tmp` directory is an in-memory RAM disk that is extremely small. Attempting to upload a multi-gigabyte file here immediately exhausts the RAM disk allocation and triggers the "No space left on device" error.
2. **Unaltered Partition Sizes**: The emulator was not cold-booted after increasing the internal storage allocation in the Android Virtual Device (AVD) manager, meaning the `/data` partition never actually expanded.

## Decision
We are updating the official `SETUP.md` documentation to explicitly document the correct usage of Android Studio's **Device Explorer** GUI, as it is often more reliable for newer developers than the command-line `adb` tool (which frequently suffers from `JAVA_HOME` or `PATH` configuration issues).

The documentation will now enforce:
- Navigating the strict folder hierarchy: `data` ➡️ `local` ➡️ `tmp`.
- Explicitly warning against the root `/tmp` directory.
- Ensuring the emulator undergoes a **Cold Boot** if the AVD internal storage is modified, so the partition changes take effect.

## Consequences
- **Positive**: Eliminates a major point of friction during the developer onboarding process and prevents confusing out-of-storage errors.
- **Negative**: Adds slightly more text to the setup guide.
