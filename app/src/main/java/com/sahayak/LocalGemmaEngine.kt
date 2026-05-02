package com.sahayak

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class LocalGemmaEngine(private val context: Context) {

    private var llmInference: LlmInference? = null
    private val TAG = "LocalGemmaEngine"

    /**
     * Initializes the LlmInference client from a local .bin file path.
     * Call this from a background thread (e.g., during a splash screen).
     */
    suspend fun initialize(modelPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(modelPath)
            if (!file.exists()) {
                Log.e(TAG, "Model file not found at \${modelPath}")
                return@withContext false
            }

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                // Adjust max tokens based on expected form/screen complexity and RAM limits
                .setMaxTokens(512) 
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            Log.d(TAG, "Gemma Engine Initialized Successfully.")
            return@withContext true

        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM Error during model initialization! Device lacks RAM.", e)
            // Handle gracefully: Notify UI to show "Device not supported" or fallback to a smaller model.
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Gemma Engine.", e)
            return@withContext false
        }
    }

    /**
     * Analyzes a physical form.
     * Note: If using a text-only Gemma, you must run ML Kit OCR on the Bitmap first,
     * then pass the extracted text here. If using a VLM via MediaPipe, pass the image natively.
     */
    suspend fun analyzeForm(imageBitmap: Bitmap, userContext: String): String = withContext(Dispatchers.IO) {
        if (llmInference == null) return@withContext "Error: AI not initialized."

        try {
            // TODO: If using pure text Gemma, extract text from Bitmap via OCR here first.
            // val extractedText = myOcrEngine.extract(imageBitmap)
            val extractedText = "[Simulated OCR Text from Form]" 

            val prompt = """
                You are a patient, helpful assistant for senior citizens.
                The user needs help filling out a physical form.
                Context: \${userContext}
                Form Text: \${extractedText}
                
                Please explain step-by-step what this form is for, what information is required, 
                and explicitly highlight any sections that ask for sensitive information (like SSN or bank details).
                Keep the language simple, respectful, and easy to read.
            """.trimIndent()

            return@withContext llmInference!!.generateResponse(prompt)
            
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM Error during form analysis!", e)
            return@withContext "I'm sorry, the document is too large for my memory. Please try capturing a smaller section."
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing form", e)
            return@withContext "An error occurred while analyzing the form."
        }
    }

    /**
     * Analyzes the current digital screen to detect scams.
     */
    suspend fun analyzeScreen(imageBitmap: Bitmap, userContext: String): String = withContext(Dispatchers.IO) {
        if (llmInference == null) return@withContext "Error: AI not initialized."

        try {
            // TODO: Extract text from screen capture Bitmap via OCR.
            val extractedText = "[Simulated OCR Text from Screen]"

            val prompt = """
                You are a cybersecurity expert protecting a senior citizen.
                Analyze the following text visible on their screen.
                Context: \${userContext}
                Screen Text: \${extractedText}
                
                Strictly check for signs of phishing, scams, urgent fake warnings, or malicious requests.
                If it looks like a scam, output a clear, urgent WARNING in simple terms.
                If it looks safe, briefly summarize what is on the screen.
            """.trimIndent()

            return@withContext llmInference!!.generateResponse(prompt)

        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM Error during screen analysis!", e)
            return@withContext "I'm out of memory analyzing this screen. Please close some apps and try again."
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing screen", e)
            return@withContext "An error occurred while analyzing the screen."
        }
    }
}
