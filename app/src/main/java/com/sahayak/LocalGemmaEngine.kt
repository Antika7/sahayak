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

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private val TAG = "LocalGemmaEngine"

    private val DEFAULT_MODEL_PATH = "/data/local/tmp/gemma-4-E2B-it.litertlm"

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

    private suspend fun runOcr(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                Log.d(TAG, "OCR extracted ${result.text.length} chars: ${result.text.take(200)}")
                cont.resume(result.text)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR failed.", e)
                cont.resume("")
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
            conv.sendMessage(prompt).contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString("") { it.text }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during form analysis.", e)
            "I'm sorry, the document is too large for my memory. Please try capturing a smaller section."
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing form.", e)
            "An error occurred while analyzing the form."
        }
    }

    suspend fun analyzeScreen(imageBitmap: Bitmap, userContext: String): String = withContext(Dispatchers.IO) {
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
            conv.sendMessage(prompt).contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString("") { it.text }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during screen analysis.", e)
            "I'm out of memory analyzing this screen. Please close some apps and try again."
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing screen.", e)
            "An error occurred while analyzing the screen."
        }
    }

    fun close() {
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
    }
}
