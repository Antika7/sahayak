# RFC 004: Model Format Selection (.task vs .litertlm)

## Status
Accepted

## Context
During the migration to the Gemma 4 E2B model, we evaluated multiple model bundle formats provided by the Google AI edge ecosystem:
1. **`.tar.gz` (Transformers)**: Large (~7.7GB), unquantized weights meant for Python server environments.
2. **`.litertlm` (LiteRT)**: Raw, quantized model weights optimized for the LiteRT (formerly TensorFlow Lite) engine.
3. **`.task` (MediaPipe Task)**: A unified bundle containing the quantized LiteRT model, the tokenizer, and execution metadata.

## Decision
We elected to exclusively use the **MediaPipe `.task` format** (`gemma-4-E2B-it-web.task`) for on-device inference instead of the raw `.litertlm` format.

## Justification
The Android application relies on `com.google.mediapipe:tasks-genai`, which offers a high-level API (`LlmInference.createFromOptions`) to handle complex AI operations effortlessly. 

If we opted for the `.litertlm` format, the app architecture would require:
- A custom, low-level C++ or Java LiteRT inference pipeline.
- Manual implementation of the Gemma tokenizer to encode raw text strings into token IDs before passing them to the model, and decoding the output IDs back into strings.

By using the `.task` format, the MediaPipe library automatically handles text tokenization and multimodal input parsing (like `MPImage`) entirely under the hood. This significantly reduces boilerplate code, minimizes the risk of tokenization bugs, and aligns with the existing architecture of `LocalGemmaEngine.kt`.

## Consequences
- **Positive**: Simplified inference logic, plug-and-play model updates, and guaranteed compatibility with MediaPipe's high-level `generateResponse()` API.
- **Negative**: The `.task` file abstracts away the underlying engine, slightly limiting our ability to write deeply custom hardware acceleration configurations compared to a raw LiteRT implementation.
