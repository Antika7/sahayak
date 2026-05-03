# RFC 001: Migration to Native Vision-Language Models (Gemma 4 Edge)

## Status
Accepted

## Context
The `LocalGemmaEngine` was originally designed to accept text-only input via the MediaPipe `LlmInference` API. Because Sahayak's primary features (Physical Form Helper and Screen Sentinel) depend on analyzing visual inputs, this architecture necessitated a two-step workaround:
1. Run a local OCR engine (like ML Kit) to extract text from an image.
2. Feed the extracted raw text into the LLM.

This approach was fragile. Extracted text loses visual context (e.g., the color of a warning button, the layout of a form), which is critical for identifying scams or accurately guiding a user through a physical document.

## Decision
With the release of MediaPipe's multimodal LLM inference support and Gemma 4 E2B's native vision capabilities, we have migrated away from the OCR-based workaround. 

We updated `LocalGemmaEngine.kt` to:
1. Convert the Android `Bitmap` directly into a MediaPipe `MPImage` using `BitmapImageBuilder`.
2. Pass the `MPImage` natively alongside the text prompt directly into `llmInference.generateResponse(prompt, mpImage)`.

## Consequences
**Positive:**
- Complete removal of the planned OCR dependency, reducing app complexity and APK size.
- The AI now receives the full visual context of the screen or form, drastically improving the accuracy of scam detection and form analysis.

**Negative/Risks:**
- Multimodal inference is highly memory-intensive. It requires the device to load both the vision encoder and the LLM into memory, enforcing a strict 4GB-6GB RAM minimum requirement for devices running the app.
