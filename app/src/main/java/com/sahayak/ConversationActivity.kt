package com.sahayak

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.sahayak.ui.theme.SahayakTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.Calendar
import java.text.SimpleDateFormat

data class ChatMessage(val text: String, val isUser: Boolean)

class ConversationActivity : ComponentActivity() {

    private lateinit var tts: TextToSpeech
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var gemmaEngine: LocalGemmaEngine
    private val TAG = "ConversationActivity"

    private val messages = mutableStateListOf<ChatMessage>()
    private var listeningState = mutableStateOf(false)
    private var thinkingState = mutableStateOf(false)
    private var ttsReady = false
    private var isSpeaking = false
    private var formContext = ""
    private var screenContext = ""
    private val collectedFields = mutableMapOf<String, String>()

    companion object {
        const val EXTRA_FORM_CONTEXT = "form_context"
        const val EXTRA_SCREEN_CONTEXT = "screen_context"
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startListening() else addMessage("Microphone permission is needed to hear you. Please grant it in Settings.", false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        formContext = intent.getStringExtra(EXTRA_FORM_CONTEXT) ?: ""
        screenContext = intent.getStringExtra(EXTRA_SCREEN_CONTEXT) ?: ""

        gemmaEngine = (application as? SahayakApp)?.gemmaEngine
            ?: LocalGemmaEngine(this).also {
                CoroutineScope(Dispatchers.IO).launch { it.initialize() }
            }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.US
                tts.setSpeechRate(0.9f)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { isSpeaking = true }
                    override fun onDone(utteranceId: String?) {
                        isSpeaking = false
                        if (utteranceId == "ai_response") {
                            Handler(Looper.getMainLooper()).post { startListening() }
                        }
                    }
                    override fun onError(utteranceId: String?) { isSpeaking = false }
                })
                ttsReady = true
                if (screenContext.isNotBlank()) {
                    sendScreenGreeting(screenContext)
                } else {
                    sendInitialGreeting(formContext)
                }
            }
        }

        setContent {
            SahayakTheme {
                ConversationScreen(
                    messages = messages,
                    isListening = listeningState.value,
                    isThinking = thinkingState.value,
                    onDone = { finish() },
                    onSendText = { handleUserSpeech(it) },
                    title = if (screenContext.isNotBlank()) "Screen Helper" else "Form Helper"
                )
            }
        }
    }

    private fun sendInitialGreeting(formContext: String) {
        sendGreeting(
            "You are a warm, patient voice assistant helping a senior citizen fill out a form. " +
            "Keep responses SHORT (2-3 sentences max) and spoken — no markdown, no bullet points, no asterisks. " +
            "The form contains this text: $formContext\n\n" +
            "Greet the user warmly and in one sentence tell them what the form is about. " +
            "Then ask them what they'd like help with first."
        )
    }

    private fun sendScreenGreeting(screenContext: String) {
        sendGreeting(
            "You are a patient, friendly assistant helping a senior citizen understand their phone screen. " +
            "Keep responses SHORT (2-3 sentences max) and spoken — no markdown, no bullet points, no asterisks. " +
            "Here is what is currently on their screen:\n$screenContext\n\n" +
            "Greet them warmly, briefly explain what they're looking at, and ask if they have any questions about it."
        )
    }

    private fun sendGreeting(prompt: String) {
        thinkingState.value = true
        lifecycleScope.launch(Dispatchers.IO) {
            val greeting = gemmaEngine.chat(prompt)
            withContext(Dispatchers.Main) {
                thinkingState.value = false
                addMessage(greeting, false)
                speak(greeting)
            }
        }
    }

    fun handleUserSpeech(userText: String) {
        addMessage(userText, true)
        thinkingState.value = true
        lifecycleScope.launch(Dispatchers.IO) {
            val prompt = if (screenContext.isNotBlank()) {
                "You are a patient, friendly assistant helping a senior citizen understand their phone screen. " +
                "Keep responses SHORT (2-3 sentences max) and spoken — no markdown, no bullet points, no asterisks. " +
                "Answer their questions clearly and simply. If something is dangerous, warn them plainly. " +
                "Here is what is on their screen:\n$screenContext\n\n" +
                "The user asked: $userText"
            } else {
                val collectedSummary = if (collectedFields.isEmpty()) ""
                else "So far the user has provided: " +
                    collectedFields.entries.joinToString(", ") { "${it.key} = ${it.value}" } + ". "

                "You are a warm, patient voice assistant helping a senior citizen fill out a form. " +
                "Keep responses SHORT (2-3 sentences max) and spoken — no markdown, no bullet points, no asterisks. " +
                "Guide them through each field one at a time. " +
                "The current year is 2026. " +
                "IMPORTANT: If the user provides a value that is inconsistent with something already collected " +
                "(for example, an age that doesn't match a previously given date of birth), " +
                "gently flag the inconsistency and ask them to confirm before moving on. " +
                "After confirming a field value, reply with 'FIELD:<fieldname>=<value>' on a hidden line so it can be tracked — " +
                "but do NOT say this out loud. " +
                collectedSummary +
                "The form contains this text: $formContext\n\n" +
                "The user said: $userText"
            }

            val response = gemmaEngine.chat(prompt)

            val fieldRegex = Regex("FIELD:([^=]+)=(.+)", RegexOption.MULTILINE)
            fieldRegex.findAll(response).forEach { match ->
                collectedFields[match.groupValues[1].trim()] = match.groupValues[2].trim()
            }
            val cleanResponse = response.replace(fieldRegex, "").trim()

            withContext(Dispatchers.Main) {
                thinkingState.value = false
                addMessage(cleanResponse, false)
                speak(cleanResponse)
            }
        }
    }

    private fun speak(text: String) {
        // Strip markdown symbols so TTS doesn't read "asterisk asterisk"
        val clean = text.replace(Regex("[*_#`]"), "")
        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "ai_response")
    }

    private fun addMessage(text: String, isUser: Boolean) {
        messages.add(ChatMessage(text, isUser))
    }

    fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (thinkingState.value || isSpeaking) {
            Log.d(TAG, "startListening blocked: thinking=${thinkingState.value} speaking=$isSpeaking")
            return
        }
        Log.d(TAG, "startListening: creating recognizer")

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "onReadyForSpeech")
                listeningState.value = true
            }
            override fun onBeginningOfSpeech() { Log.d(TAG, "onBeginningOfSpeech") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                Log.d(TAG, "onEndOfSpeech")
                listeningState.value = false
            }
            override fun onError(error: Int) {
                listeningState.value = false
                Log.e(TAG, "onError: $error  thinking=${thinkingState.value} speaking=$isSpeaking")
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        if (!thinkingState.value && !isSpeaking) {
                            Handler(Looper.getMainLooper()).postDelayed({ startListening() }, 500)
                        }
                    }
                    SpeechRecognizer.ERROR_CLIENT -> {
                        Handler(Looper.getMainLooper()).postDelayed({ startListening() }, 500)
                    }
                    else -> Log.e(TAG, "SpeechRecognizer unhandled error: $error")
                }
            }
            override fun onResults(results: Bundle?) {
                listeningState.value = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                Log.d(TAG, "onResults: $matches")
                val text = matches?.firstOrNull()
                if (!text.isNullOrBlank()) handleUserSpeech(text)
                else startListening()
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                Log.d(TAG, "onPartialResults: $partial")
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        speechRecognizer?.startListening(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.stop()
        tts.shutdown()
        speechRecognizer?.destroy()
    }
}

@Composable
fun ConversationScreen(
    messages: List<ChatMessage>,
    isListening: Boolean,
    isThinking: Boolean,
    onDone: () -> Unit,
    onSendText: (String) -> Unit,
    title: String = "Form Helper"
) {
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }

    fun submit() {
        val text = inputText.trim()
        if (text.isNotEmpty()) {
            inputText = ""
            onSendText(text)
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF5F5F5))) {

        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                color = Color.White,
                fontSize = 20.sp,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDone) {
                Icon(Icons.Default.Close, contentDescription = "Done", tint = Color.White)
            }
        }

        // Chat messages
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(messages) { msg ->
                MessageBubble(msg)
            }
        }

        // Status bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                isThinking -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Thinking...", fontSize = 16.sp, color = Color.Gray)
                }
                isListening -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(Color.Red, RoundedCornerShape(50))
                    )
                    Text("Listening...", fontSize = 16.sp, color = Color.Red)
                }
                else -> Text(
                    "Waiting to listen...",
                    fontSize = 16.sp,
                    color = Color.Gray,
                    fontStyle = FontStyle.Italic
                )
            }
        }

        // Text input fallback (useful when mic unavailable, e.g. emulator)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message...") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() })
            )
            IconButton(
                onClick = { submit() },
                enabled = inputText.isNotBlank() && !isThinking
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(
                    color = if (message.isUser) Color(0xFF1565C0) else Color.White,
                    shape = RoundedCornerShape(
                        topStart = 16.dp, topEnd = 16.dp,
                        bottomStart = if (message.isUser) 16.dp else 4.dp,
                        bottomEnd = if (message.isUser) 4.dp else 16.dp
                    )
                )
                .padding(12.dp)
        ) {
            Text(
                text = message.text,
                color = if (message.isUser) Color.White else Color.Black,
                fontSize = 16.sp,
                lineHeight = 22.sp
            )
        }
    }
}
