package com.sahayak

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
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
                .setMaxTokens(512)
                .setMaxNumImages(1)
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
            val mpImage = BitmapImageBuilder(imageBitmap).build()

            val prompt = """
                You are a patient, helpful assistant for senior citizens.
                The user needs help filling out a physical form.
                Context: ${userContext}
                
                Please explain step-by-step what this form is for, what information is required, 
                and explicitly highlight any sections that ask for sensitive information (like SSN or bank details).
                Keep the language simple, respectful, and easy to read.
            """.trimIndent()
            val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setGraphOptions(GraphOptions.builder().setEnableVisionModality(true).build())
                .build()
            val session = LlmInferenceSession.createFromOptions(llmInference!!, sessionOptions)

            session.addImage(mpImage)
            session.addQueryChunk(prompt)

            val response = session.generateResponse()
            session.close()

            return@withContext response

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
            val mpImage = BitmapImageBuilder(imageBitmap).build()

            val prompt = """
                You are a cybersecurity expert protecting a senior citizen.
                Analyze the following image visible on their screen.
                Context: ${userContext}

                Strictly check for signs of phishing, scams, urgent fake warnings, or malicious requests.
                If it looks like a scam, output a clear, urgent WARNING in simple terms.
                If it looks safe, briefly summarize what is on the screen.
            """.trimIndent()
            val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setGraphOptions(GraphOptions.builder().setEnableVisionModality(true).build())
                .build()
            val session = LlmInferenceSession.createFromOptions(llmInference!!, sessionOptions)
            
            session.addImage(mpImage)
            session.addQueryChunk(prompt)
            
            val response = session.generateResponse()
            session.close()
            
            return@withContext response

        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM Error during screen analysis!", e)
            return@withContext "I'm out of memory analyzing this screen. Please close some apps and try again."
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing screen", e)
            return@withContext "An error occurred while analyzing the screen."
        }
    }
}
