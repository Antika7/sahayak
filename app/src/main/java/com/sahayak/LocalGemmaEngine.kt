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

class LocalGemmaEngine(private val context: Context) {

    private companion object {
        const val TAG = "LocalGemmaEngine"
        const val DEFAULT_MODEL_PATH = "/data/local/tmp/gemma-4-E2B-it.litertlm"
    }

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

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

    suspend fun initialize(modelPath: String = DEFAULT_MODEL_PATH): Boolean = withContext(Dispatchers.IO) {
        if (conversation != null) return@withContext true
        try {
            if (engine == null) {
                engine = tryCreateEngine(modelPath, Backend.GPU())
                    ?: tryCreateEngine(modelPath, Backend.CPU())
                    ?: return@withContext false
            }
            conversation = engine!!.createConversation()
            Log.d(TAG, "Gemma Engine initialized successfully.")
            true
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during model initialization.", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Gemma Engine.", e)
            false
        }
    }

    private fun sendAndExtract(conv: Conversation, prompt: String): String =
        conv.sendMessage(prompt).contents.contents
            .filterIsInstance<Content.Text>()
            .joinToString("") { it.text }

    private suspend fun runOcr(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                cont.resume(result.text)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR failed.", e)
                cont.resume("")
            }
    }

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

    suspend fun analyzeForm(imageBitmap: Bitmap, userContext: String): String = withContext(Dispatchers.IO) {
        val conv = conversation ?: return@withContext "Error: AI not initialized."
        try {
            val ocrText = runOcr(imageBitmap)
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

    suspend fun analyzeScreen(userContext: String): String = withContext(Dispatchers.IO) {
        val conv = conversation ?: return@withContext "Error: AI not initialized."
        try {
            val prompt = """
                You are a cybersecurity expert protecting a senior citizen.
                Analyze the following screen content.
                Context: $userContext

                Strictly check for signs of phishing, scams, urgent fake warnings, or malicious requests.
                If it looks like a scam, output a clear, urgent WARNING in simple terms.
                If it looks safe, briefly summarize what is on the screen.
            """.trimIndent()
            sendAndExtract(conv, prompt)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during screen analysis.", e)
            "I'm out of memory analyzing this screen. Please close some apps and try again."
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing screen.", e)
            "An error occurred while analyzing the screen."
        }
    }

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
