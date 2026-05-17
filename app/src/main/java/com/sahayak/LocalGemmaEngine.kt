package com.sahayak

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

//   AI subsystems:
//   1. LiteRT LLM (Gemma) — on-device large language model for chat, form analysis, and scam detection. Runs entirely offline; no network calls.
//   2. ML Kit Text Recognition — OCR engine that extracts printed text from Bitmaps before passing it to the LLM as context.
//
// A single instance is created in SahayakApp and shared across the process lifetime to avoid reloading the model.
class LocalGemmaEngine(private val context: Context) {

    private companion object {
        const val TAG = "LocalGemmaEngine"
        // Model file is pushed to the device manually (e.g. via adb push) during development
        // In production this path would come from a download manager.
        const val DEFAULT_MODEL_PATH = "/data/local/tmp/gemma-4-E2B-it.litertlm"
    }
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    // OCR client is only allocated when first needed (FormHelper flow)
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    // Create a LiteRT Engine using GPU (primary) and CPU fallback
    private fun tryCreateEngine(modelPath: String, backend: Backend): Engine? {
        return try {
            val config = EngineConfig(
                modelPath = modelPath,
                backend = backend,
                cacheDir = context.cacheDir.absolutePath
            )
            Engine(config).also { it.initialize() }
        } catch (e: Exception) {
            Log.w(TAG, "Backend ${backend::class.simpleName} unavailable, trying fallback.", e)
            null
        }
    }

    // Load Gemma model
    suspend fun initialize(modelPath: String = DEFAULT_MODEL_PATH): Boolean = withContext(Dispatchers.IO) {
        if (conversation != null) return@withContext true
        try {
            if (engine == null) {
                engine = tryCreateEngine(modelPath, Backend.GPU())
                    ?: tryCreateEngine(modelPath, Backend.CPU())
                    ?: return@withContext false   // Both backends failed — model unusable.
            }
            conversation = engine!!.createConversation()
            Log.d(TAG, "Gemma Engine initialized successfully.")
            true
        } catch (e: OutOfMemoryError) {
            // Model is ~2 GB; OOM is a realistic failure on low-RAM devices.
            Log.e(TAG, "OOM during model initialization.", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Gemma Engine.", e)
            false
        }
    }

    // Sends a prompt to the active Conversation and concatenates all Text content
    private fun sendAndExtract(conv: Conversation, prompt: String): String =
        conv.sendMessage(prompt).contents.contents
            .filterIsInstance<Content.Text>()
            .joinToString("") { it.text }

    // Wraps ML Kit's callback-based OCR API in a coroutine
    private suspend fun runOcr(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                cont.resume(result.text)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR failed.", e)
                cont.resume("")   // Empty string rather than propagating the exception.
            }
    }

    // Entry point used by MainActivity.takePhoto() to extract form text
    suspend fun extractFormText(bitmap: Bitmap): String = runOcr(bitmap)
    suspend fun chat(message: String): String = withContext(Dispatchers.IO) {
        val conv = conversation ?: return@withContext "I'm not ready yet. Please wait a moment."
        try {
            sendAndExtract(conv, message)
        } catch (e: Exception) {
            Log.e(TAG, "Error in chat.", e)
            "I'm sorry, I had trouble responding. Please try again."
        }
    }

    // Combined OCR + LLM analysis for the Form Helper feature.
    /*
        Runs OCR on the image first, then injects the extracted text into a
        structured prompt that instructs the model to explain the form step-by-step
        and flag sensitive fields (SSN, bank details, etc.) for the senior user.
     */
    suspend fun analyzeForm(imageBitmap: Bitmap, userContext: String): String = withContext(Dispatchers.IO) {
        val conv = conversation ?: return@withContext "Error: AI not initialized."
        try {
            val ocrText = runOcr(imageBitmap)
            // Gracefully handle the case where OCR found nothing (blank page, bad lighting).
            val formContext = if (ocrText.isBlank()) {
                "The user photographed a form but no text could be extracted. $userContext"
            } else {
                "The form contains the following text:\n\n$ocrText\n\n$userContext"
            }
            val prompt = """
                You are a patient, helpful assistant for senior citizens.
                The user needs help filling out a physical form.
                $formContext

                Please explain step-by-step what this form is for, what information is required,
                and explicitly highlight any sections that ask for sensitive information (like SSN or bank details).
                Keep the language simple, respectful, and easy to read.
            """.trimIndent()
            sendAndExtract(conv, prompt)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during form analysis.", e)
            "I'm sorry, the document is too large for my memory. Please try capturing a smaller section."
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing form.", e)
            "An error occurred while analyzing the form."
        }
    }

    // Scam-detection path used by the Sentinel overlay
    suspend fun analyzeScreenContext(context: ScreenContext): ScreenAnalysisResult = withContext(Dispatchers.IO) {
        val conv = conversation ?: return@withContext ScreenAnalysisResult(
            explanation = "AI not initialized. Please wait and try again.",
            riskLevel = RiskLevel.LOW,
            riskReason = null,
            suggestedAction = "Wait a moment and tap the button again."
        )
        try {
            val redactedText = PrivacyFilter.redact(context.visibleText.take(2000))
                .ifBlank { "(no text visible on screen)" }
            val appInfo = context.appPackage?.let { "APP: $it" } ?: "APP: Unknown"
            val elementsInfo = if (context.interactiveElements.isNotEmpty()) {
                "INTERACTIVE ELEMENTS: ${context.interactiveElements.joinToString(", ")}"
            } else ""

            val prompt = """
                You are a helpful assistant explaining a phone screen to a senior citizen.
                The user tapped a help button to understand what they're seeing.

                $appInfo
                SCREEN TEXT:
                $redactedText

                $elementsInfo

                Instructions:
                1. In 1-2 simple sentences, explain what this screen is showing.
                2. Assess risk: is there anything suspicious, misleading, or potentially dangerous?
                3. If there IS a risk, explain the danger in plain language.
                4. Suggest what the user should do next.

                Reply in EXACTLY this format (each on its own line):
                EXPLANATION: <1-2 sentences explaining the screen>
                RISK: NONE or LOW or HIGH
                RISK_REASON: <why it's risky, or "none">
                ACTION: <what the user should do next>
            """.trimIndent()

            val response = sendAndExtract(conv, prompt)
            parseScreenAnalysis(response)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during screen context analysis.", e)
            ScreenAnalysisResult(
                explanation = "Not enough memory to analyze. Please close some apps and try again.",
                riskLevel = RiskLevel.LOW,
                riskReason = null,
                suggestedAction = "Close some apps, then try again."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing screen context.", e)
            ScreenAnalysisResult(
                explanation = "Could not finish the analysis. Please try again.",
                riskLevel = RiskLevel.LOW,
                riskReason = null,
                suggestedAction = "Try tapping the button again."
            )
        }
    }

    // Parses the structured KEY: value response from analyzeScreenContext's prompt.
    private fun parseScreenAnalysis(response: String): ScreenAnalysisResult {
        val lines = response.trim().lines()
        val map = mutableMapOf<String, String>()
        for (line in lines) {
            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                val key = line.substring(0, colonIndex).trim().uppercase()
                val value = line.substring(colonIndex + 1).trim()
                if (key in setOf("EXPLANATION", "RISK", "RISK_REASON", "ACTION")) {
                    map[key] = value
                }
            }
        }

        val explanation = map["EXPLANATION"] ?: response.trim().lines().firstOrNull() ?: response.trim()
        val riskLevel = when {
            map["RISK"]?.contains("HIGH", ignoreCase = true) == true -> RiskLevel.HIGH
            map["RISK"]?.contains("LOW", ignoreCase = true) == true -> RiskLevel.LOW
            map["RISK"]?.contains("NONE", ignoreCase = true) == true -> RiskLevel.NONE
            else -> RiskLevel.LOW
        }
        // Treat "none" and blank as absent
        val riskReason = map["RISK_REASON"]?.takeIf {
            it.isNotBlank() && !it.equals("none", ignoreCase = true)
        }
        val suggestedAction = map["ACTION"]?.takeIf { it.isNotBlank() }

        return ScreenAnalysisResult(
            explanation = explanation,
            riskLevel = riskLevel,
            riskReason = riskReason,
            suggestedAction = suggestedAction
        )
    }
    fun close() {
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
        recognizer.close()
    }
}
