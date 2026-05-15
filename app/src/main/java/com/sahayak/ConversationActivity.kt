package com.sahayak

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
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
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.sahayak.ui.theme.SahayakTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private fun resolveAddress(
    context: Context,
    lat: Double,
    lon: Double,
    onResult: (address: String, locality: String) -> Unit,
    onError: (String) -> Unit
) {
    val geocoder = Geocoder(context, Locale.getDefault())
    val format = { addresses: List<android.location.Address>? ->
        if (addresses.isNullOrEmpty()) {
            onError("Could not find address for your location.")
        } else {
            val addr = addresses[0]
            val parts = listOfNotNull(
                addr.subLocality,
                addr.locality,
                addr.subAdminArea,
                addr.adminArea,
                addr.countryName
            ).distinct().filter { it.isNotBlank() }
            onResult(parts.joinToString(", "), addr.locality ?: "")
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        geocoder.getFromLocation(lat, lon, 1) { format(it) }
    } else {
        try {
            @Suppress("DEPRECATION")
            format(geocoder.getFromLocation(lat, lon, 1))
        } catch (e: Exception) {
            onError("Geocoding failed: ${e.localizedMessage}")
        }
    }
}

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
    private var formContext   = ""
    private var screenContext = ""
    private var userName      = ""
    private var userLanguage  = "English"
    private var userDob       = ""
    private var userCity      = ""
    private var userSpouseName = ""
    private var userPan       = ""
    private var clientErrorRetries = 0
    private val collectedFields = mutableMapOf<String, String>()

    private enum class LocationState { IDLE, FETCHING, AWAITING_CONFIRMATION }
    private var locationState = LocationState.IDLE
    private var pendingLocationAddress = ""
    private var pendingLocationLocality = ""

    companion object {
        const val EXTRA_FORM_CONTEXT   = "form_context"
        const val EXTRA_SCREEN_CONTEXT = "screen_context"
        const val EXTRA_USER_NAME      = "user_name"
        const val EXTRA_USER_LANGUAGE  = "user_language"
        const val EXTRA_USER_DOB       = "user_dob"
        const val EXTRA_USER_CITY      = "user_city"
        const val EXTRA_USER_SPOUSE    = "user_spouse"
        const val EXTRA_USER_PAN       = "user_pan"
        private val FIELD_REGEX    = Regex("FIELD:([^=]+)=(.+)", RegexOption.MULTILINE)
        private val MARKDOWN_REGEX = Regex("[*_#`]")
        private val TOKEN_REGEX    = Regex("##[A-Z_]+##?")
        private val FORM_COMPLETE_REGEX = Regex("##FORM_COMPLETE#?#?")
        private const val LOCATION_TOKEN = "##REQUEST_LOCATION##"
    }

    private val originalTtsListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) { isSpeaking = true }
        override fun onDone(utteranceId: String?) {
            isSpeaking = false
            if (utteranceId == "ai_response") {
                Handler(Looper.getMainLooper()).post { startListening() }
            }
        }
        override fun onError(utteranceId: String?) { isSpeaking = false }
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startListening() else addMessage("Microphone permission is needed to hear you. Please grant it in Settings.", false)
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            fetchLocationForForm()
        } else {
            locationState = LocationState.IDLE
            thinkingState.value = false
            val msg = if (userLanguage == "हिंदी")
                "मुझे आपका पता जानने के लिए लोकेशन अनुमति चाहिए। कृपया सेटिंग में अनुमति दें, फिर जारी रखें।"
            else
                "I need location permission to find your address. Please grant it in Settings, then continue."
            addMessage(msg, false)
            speak(msg)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        formContext     = intent.getStringExtra(EXTRA_FORM_CONTEXT)   ?: ""
        screenContext   = intent.getStringExtra(EXTRA_SCREEN_CONTEXT)  ?: ""
        userName        = intent.getStringExtra(EXTRA_USER_NAME)       ?: ""
        userLanguage    = intent.getStringExtra(EXTRA_USER_LANGUAGE)   ?: "English"
        userDob         = intent.getStringExtra(EXTRA_USER_DOB)        ?: ""
        userCity        = intent.getStringExtra(EXTRA_USER_CITY)       ?: ""
        userSpouseName  = intent.getStringExtra(EXTRA_USER_SPOUSE)     ?: ""
        userPan         = intent.getStringExtra(EXTRA_USER_PAN)        ?: ""

        if (userName.isNotBlank())       collectedFields["name"]          = userName
        if (userDob.isNotBlank()) {
            collectedFields["date of birth"] = userDob
            val age = calculateAge(userDob)
            if (age > 0) collectedFields["age"] = "$age years"
        }
        if (userCity.isNotBlank())       collectedFields["city"]          = userCity
        if (userSpouseName.isNotBlank()) collectedFields["spouse name"]   = userSpouseName
        if (userPan.isNotBlank())        collectedFields["PAN number"]    = userPan

        gemmaEngine = (application as? SahayakApp)?.gemmaEngine
            ?: LocalGemmaEngine(this).also {
                CoroutineScope(Dispatchers.IO).launch { it.initialize() }
            }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val targetLocale = if (userLanguage == "हिंदी") Locale("hi", "IN") else Locale.US
                tts.language = targetLocale
                val bestVoice = tts.voices
                    ?.filter { it.locale.language == targetLocale.language && !it.isNetworkConnectionRequired }
                    ?.maxByOrNull { it.quality }
                if (bestVoice != null) tts.voice = bestVoice
                tts.setSpeechRate(0.9f)
                tts.setOnUtteranceProgressListener(originalTtsListener)
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
        val profile  = buildUserProfile()
        val langInst = buildLanguageInstruction()
        sendGreeting(
            "You are a patient, knowledgeable assistant helping a senior citizen fill out a physical paper form by hand. " +
            "Your job is to go through EVERY blank field on the form, one at a time, in order from top to bottom. " +
            "Do NOT skip any field. Do NOT declare the form complete until every single blank has been addressed.\n\n" +
            "Rules:\n" +
            "1. Read the form text carefully and identify ALL blank fields before you begin.\n" +
            "2. Go through them one by one, top to bottom.\n" +
            "3. If the value is already in the user profile, say exactly: 'In the [field name] field, write [value].' Do NOT ask — just tell them what to write.\n" +
            "4. Age fields: if date of birth is in the profile, calculate the age yourself and say 'In the age field, write [calculated age].' Never ask for age if you know the date of birth.\n" +
            "5. If a field is technical (PAN, Aadhaar, TIN, account number, etc.) AND the value is NOT in the profile, explain what it means in simple language, then ask.\n" +
            "6. If you do not know the value and it is not technical, ask for it plainly.\n" +
            "7. After the user provides a value, confirm it and immediately move to the next field.\n" +
            "8. If the user says 'done', 'okay', 'next', or similar — treat it as confirmation of the current field and move to the next one. Never treat these words as finishing the whole form.\n" +
            "9. Only emit ##FORM_COMPLETE## after you have addressed EVERY single blank field on the form — not after just one or two fields. Count all the blanks first.\n" +
            "10. If you need the user's address and it is not in the profile, emit exactly $LOCATION_TOKEN on its own line and stop. Do NOT invent other ##TOKEN## signals.\n\n" +
            "Example of a good response for a known field: 'In the PAN number field, write ABCDE1234F. Once you have written that, let me know and I will move to the next field.'\n" +
            "Example of a good response for an unknown field: 'The next field is your bank account number. Could you please tell me your account number?'\n\n" +
            "Keep responses spoken — no markdown, no bullet points, no asterisks. " +
            "Be brief for simple fields. For technical fields, explain clearly so the senior understands.\n" +
            (if (profile.isNotBlank())  "\n$profile\n"  else "") +
            (if (langInst.isNotBlank()) "\n$langInst\n" else "") +
            "\nThe form contains this text:\n$formContext\n\n" +
            "Start by greeting the user briefly by name if you know it, then say in one sentence what this form is for, " +
            "then immediately start with the very first blank field. Do NOT emit any ##TOKEN## in this first response."
        )
    }

    private fun sendScreenGreeting(screenContext: String) {
        sendGreeting(
            "You are a patient, friendly assistant helping a senior citizen understand their phone screen. " +
            "Keep responses clear and spoken — no markdown, no bullet points, no asterisks. " +
            "Be brief for simple questions (1-2 sentences). For anything confusing, take 3-4 sentences to explain clearly. " +
            "Here is what is currently on their screen:\n$screenContext\n\n" +
            "Greet them warmly, briefly explain what they're looking at, and ask if they have any questions about it."
        )
    }

    private fun sendGreeting(prompt: String) {
        thinkingState.value = true
        lifecycleScope.launch(Dispatchers.IO) {
            val raw = gemmaEngine.chat(prompt)
            val greeting = raw
                .replace(FIELD_REGEX, "")
                .replace(TOKEN_REGEX, "")
                .trim()
            withContext(Dispatchers.Main) {
                thinkingState.value = false
                addMessage(greeting, false)
                speak(greeting)
            }
        }
    }

    fun handleUserSpeech(userText: String) {
        // Gate 1: intercept yes/no confirmation for detected location
        if (locationState == LocationState.AWAITING_CONFIRMATION) {
            addMessage(userText, true)
            val t = userText.trim().lowercase()
            val confirmed = t.startsWith("yes") || t.startsWith("हाँ") || t.startsWith("ha") ||
                t == "ok" || t == "okay" || t == "sure" || t == "haan"
            if (confirmed) {
                locationState = LocationState.IDLE
                collectedFields["address / location"] = pendingLocationAddress
                if (userCity.isBlank() && pendingLocationLocality.isNotBlank()) {
                    userCity = pendingLocationLocality
                    collectedFields["city"] = pendingLocationLocality
                }
                val injected = "The user's address has been confirmed as: $pendingLocationAddress. " +
                    "Continue filling the form from where you left off."
                pendingLocationAddress = ""
                pendingLocationLocality = ""
                continueFormWithInjectedContext(injected)
            } else {
                locationState = LocationState.IDLE
                pendingLocationAddress = ""
                pendingLocationLocality = ""
                val msg = if (userLanguage == "हिंदी")
                    "ठीक है। कृपया मुझे अपना पता बताएं।"
                else
                    "No problem. Could you please tell me your address?"
                addMessage(msg, false)
                speak(msg)
            }
            return
        }

        addMessage(userText, true)
        thinkingState.value = true
        lifecycleScope.launch(Dispatchers.IO) {
            val prompt = if (screenContext.isNotBlank()) {
                "You are a patient, friendly assistant helping a senior citizen understand their phone screen. " +
                "Keep responses clear and spoken — no markdown, no bullet points, no asterisks. " +
                "Be brief for simple fields (1-2 sentences). For confusing or technical fields, take 3-4 sentences to explain clearly — never leave out information a senior citizen needs to understand what to write. " +
                "Answer their questions clearly and simply. If something is dangerous, warn them plainly. " +
                "Here is what is on their screen:\n$screenContext\n\n" +
                "The user asked: $userText"
            } else {
                val filledSummary = if (collectedFields.isEmpty()) ""
                    else "Fields already handled: " +
                        collectedFields.entries.joinToString(", ") { "${it.key} = ${it.value}" } + ". "

                val profile  = buildUserProfile()
                val langInst = buildLanguageInstruction()
                val profileSection = if (profile.isNotBlank()) "\n\n$profile" else ""

                "You are a patient, knowledgeable assistant helping a senior citizen fill out a physical paper form by hand. " +
                "Your job is to go through EVERY blank field on the form, one at a time, in order. Do NOT skip any field. " +
                "Do NOT declare the form complete until every single blank has been addressed.\n" +
                "Rules:\n" +
                "1. If the value is already in the user profile, say exactly: 'In the [field name] field, write [value].' Do NOT ask — just tell them what to write.\n" +
                "2. Age fields: if date of birth is in the profile, calculate the age yourself and say 'In the age field, write [calculated age].' Never ask for age if you know the date of birth.\n" +
                "3. If a field is technical (PAN, Aadhaar, TIN, account number, legal terms, etc.) AND the value is NOT in the profile, explain what it means simply, then ask.\n" +
                "4. If it is a straightforward unknown field, just ask for it plainly.\n" +
                "5. After the user provides a value, confirm it and immediately move to the next field.\n" +
                "6. If the user says 'done', 'okay', 'next', or similar — treat it as confirmation of the current field and move to the next one. Never treat these words as finishing the whole form.\n" +
                "7. Only emit ##FORM_COMPLETE## after you have addressed EVERY single blank field on the form — not after just one or two fields.\n" +
                "8. If you need the user's address or location and it is not in the profile, output exactly $LOCATION_TOKEN on its own line and stop. Do NOT invent other ##TOKEN## signals.\n" +
                "Example of a good response for a known field: 'In the PAN number field, write ABCDE1234F. Once you have written that, let me know and I will move to the next field.'\n" +
                "Example of a good response for an unknown field: 'The next field is your bank account number. Could you please tell me your account number?'\n" +
                "Keep responses spoken — no markdown, no bullet points, no asterisks. " +
                "Be brief for simple fields. For technical fields, explain clearly so the senior understands. " +
                "The current year is 2026." +
                profileSection +
                (if (langInst.isNotBlank()) "\n\n$langInst" else "") +
                "\n\n$filledSummary" +
                "The form contains this text: $formContext\n\n" +
                "The user said: $userText"
            }

            val response = gemmaEngine.chat(prompt)

            // Gate 2: intercept ##REQUEST_LOCATION## before normal processing
            if (response.contains(LOCATION_TOKEN)) {
                val cleanForDisplay = response
                    .replace(LOCATION_TOKEN, "")
                    .replace(FIELD_REGEX, "")
                    .trim()
                withContext(Dispatchers.Main) {
                    thinkingState.value = false
                    if (cleanForDisplay.isNotEmpty()) {
                        addMessage(cleanForDisplay, false)
                        val cleanTts = cleanForDisplay.replace(MARKDOWN_REGEX, "")
                        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {}
                            override fun onDone(utteranceId: String?) {
                                if (utteranceId == "pre_location_fetch") {
                                    tts.setOnUtteranceProgressListener(originalTtsListener)
                                    Handler(Looper.getMainLooper()).post { requestLocationForForm() }
                                }
                            }
                            override fun onError(utteranceId: String?) {
                                tts.setOnUtteranceProgressListener(originalTtsListener)
                                Handler(Looper.getMainLooper()).post { requestLocationForForm() }
                            }
                        })
                        tts.speak(cleanTts, TextToSpeech.QUEUE_FLUSH, null, "pre_location_fetch")
                    } else {
                        requestLocationForForm()
                    }
                }
                return@launch
            }

            FIELD_REGEX.findAll(response).forEach { match ->
                collectedFields[match.groupValues[1].trim()] = match.groupValues[2].trim()
            }

            val isDone = FORM_COMPLETE_REGEX.containsMatchIn(response)
            val cleanResponse = response
                .replace(FIELD_REGEX, "")
                .replace(FORM_COMPLETE_REGEX, "")
                .replace(TOKEN_REGEX, "")
                .trim()
            withContext(Dispatchers.Main) {
                thinkingState.value = false
                addMessage(cleanResponse, false)
                if (isDone) {
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            if (utteranceId == "ai_response_final") {
                                Handler(Looper.getMainLooper()).postDelayed({ finish() }, 500)
                            }
                        }
                        override fun onError(utteranceId: String?) { finish() }
                    })
                    val clean = cleanResponse.replace(MARKDOWN_REGEX, "")
                    tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "ai_response_final")
                } else {
                    speak(cleanResponse)
                }
            }
        }
    }

    private fun requestLocationForForm() {
        val fine   = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            fetchLocationForForm()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    private fun fetchLocationForForm() {
        locationState = LocationState.FETCHING
        thinkingState.value = true
        val fusedClient = LocationServices.getFusedLocationProviderClient(this)
        val cts = CancellationTokenSource()
        fusedClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    resolveAddress(this, loc.latitude, loc.longitude,
                        onResult = { addr, locality ->
                            Handler(Looper.getMainLooper()).post { geocodeAndConfirm(addr, locality) }
                        },
                        onError = { msg ->
                            Handler(Looper.getMainLooper()).post { onLocationFetchFailed(msg) }
                        }
                    )
                } else {
                    fusedClient.lastLocation
                        .addOnSuccessListener { last ->
                            if (last != null) {
                                resolveAddress(this, last.latitude, last.longitude,
                                    onResult = { addr, locality ->
                                        Handler(Looper.getMainLooper()).post { geocodeAndConfirm(addr, locality) }
                                    },
                                    onError = { msg ->
                                        Handler(Looper.getMainLooper()).post { onLocationFetchFailed(msg) }
                                    }
                                )
                            } else {
                                Handler(Looper.getMainLooper()).post {
                                    onLocationFetchFailed("Could not get your location. Please try again.")
                                }
                            }
                        }
                        .addOnFailureListener { e ->
                            Handler(Looper.getMainLooper()).post {
                                onLocationFetchFailed("Location unavailable: ${e.localizedMessage}")
                            }
                        }
                }
            }
            .addOnFailureListener { e ->
                Handler(Looper.getMainLooper()).post {
                    onLocationFetchFailed("Location unavailable: ${e.localizedMessage}")
                }
            }
    }

    private fun geocodeAndConfirm(address: String, locality: String) {
        pendingLocationAddress = address
        pendingLocationLocality = locality
        locationState = LocationState.AWAITING_CONFIRMATION
        thinkingState.value = false
        val msg = if (userLanguage == "हिंदी")
            "मैंने आपका पता पाया: $address। क्या मैं इसे उपयोग करूँ? हाँ या नहीं कहें।"
        else
            "I found your location as $address. Shall I use this? Please say yes or no."
        tts.setOnUtteranceProgressListener(originalTtsListener)
        addMessage(msg, false)
        speak(msg)
    }

    private fun onLocationFetchFailed(reason: String) {
        locationState = LocationState.IDLE
        thinkingState.value = false
        val msg = if (userLanguage == "हिंदी")
            "माफ़ करें, मैं आपका पता नहीं पा सका। क्या आप मुझे अपना पता बता सकते हैं?"
        else
            "Sorry, I couldn't detect your location. Could you please tell me your address?"
        Log.e(TAG, "Location fetch failed: $reason")
        addMessage(msg, false)
        speak(msg)
    }

    private fun continueFormWithInjectedContext(injectedContext: String) {
        thinkingState.value = true
        lifecycleScope.launch(Dispatchers.IO) {
            val filledSummary = if (collectedFields.isEmpty()) ""
                else "Fields already handled: " +
                    collectedFields.entries.joinToString(", ") { "${it.key} = ${it.value}" } + ". "
            val profile  = buildUserProfile()
            val langInst = buildLanguageInstruction()
            val profileSection = if (profile.isNotBlank()) "\n\n$profile" else ""
            val prompt =
                "You are a patient, knowledgeable assistant helping a senior citizen fill out a physical paper form by hand. " +
                "Your job is to go through EVERY blank field on the form, one at a time, in order. Do NOT skip any field. " +
                "Do NOT declare the form complete until every single blank has been addressed.\n" +
                "Rules:\n" +
                "1. If you already know the value from the user profile, say exactly: 'In the [field name] field, write [value].' Do not ask.\n" +
                "2. Age fields: if date of birth is in the profile, calculate the age yourself and say 'In the age field, write [calculated age].' Never ask for age if you know the date of birth.\n" +
                "3. If a field is technical, explain it simply then ask.\n" +
                "4. If it is a straightforward unknown field, just ask for it plainly.\n" +
                "5. After a value is provided, confirm it and immediately move to the next field.\n" +
                "6. Only emit ##FORM_COMPLETE## after you have addressed EVERY single blank field on the form — not after just one or two fields.\n" +
                "7. If you need the user's address or location and it is not in the profile, output exactly $LOCATION_TOKEN on its own line and stop. Do NOT invent other ##TOKEN## signals.\n" +
                "Example of a good response for a known field: 'In the PAN number field, write ABCDE1234F. Once you have written that, let me know and I will move to the next field.'\n" +
                "Keep responses spoken — no markdown, no bullet points, no asterisks. " +
                "Be brief for simple fields. For technical fields, explain clearly so the senior understands. " +
                "The current year is 2026." +
                profileSection +
                (if (langInst.isNotBlank()) "\n\n$langInst" else "") +
                "\n\n$filledSummary" +
                "The form contains this text: $formContext\n\n" +
                injectedContext

            val response = gemmaEngine.chat(prompt)

            if (response.contains(LOCATION_TOKEN)) {
                val cleanForDisplay = response.replace(TOKEN_REGEX, "").replace(FIELD_REGEX, "").trim()
                withContext(Dispatchers.Main) {
                    thinkingState.value = false
                    if (cleanForDisplay.isNotEmpty()) {
                        addMessage(cleanForDisplay, false)
                        val cleanTts = cleanForDisplay.replace(MARKDOWN_REGEX, "")
                        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {}
                            override fun onDone(utteranceId: String?) {
                                if (utteranceId == "pre_location_fetch") {
                                    tts.setOnUtteranceProgressListener(originalTtsListener)
                                    Handler(Looper.getMainLooper()).post { requestLocationForForm() }
                                }
                            }
                            override fun onError(utteranceId: String?) {
                                tts.setOnUtteranceProgressListener(originalTtsListener)
                                Handler(Looper.getMainLooper()).post { requestLocationForForm() }
                            }
                        })
                        tts.speak(cleanTts, TextToSpeech.QUEUE_FLUSH, null, "pre_location_fetch")
                    } else {
                        requestLocationForForm()
                    }
                }
                return@launch
            }

            FIELD_REGEX.findAll(response).forEach { match ->
                collectedFields[match.groupValues[1].trim()] = match.groupValues[2].trim()
            }
            val isDone = response.contains("##FORM_COMPLETE##")
            val cleanResponse = response
                .replace(FIELD_REGEX, "")
                .replace(TOKEN_REGEX, "")
                .trim()
            withContext(Dispatchers.Main) {
                thinkingState.value = false
                addMessage(cleanResponse, false)
                if (isDone) {
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            if (utteranceId == "ai_response_final") {
                                Handler(Looper.getMainLooper()).postDelayed({ finish() }, 500)
                            }
                        }
                        override fun onError(utteranceId: String?) { finish() }
                    })
                    tts.speak(cleanResponse.replace(MARKDOWN_REGEX, ""), TextToSpeech.QUEUE_FLUSH, null, "ai_response_final")
                } else {
                    speak(cleanResponse)
                }
            }
        }
    }

    private fun buildUserProfile(): String {
        val parts = mutableListOf<String>()
        if (userName.isNotBlank())       parts.add("Name: $userName")
        if (userDob.isNotBlank()) {
            parts.add("Date of birth: $userDob")
            val age = calculateAge(userDob)
            if (age > 0) parts.add("Age: $age years")
        }
        if (userCity.isNotBlank())       parts.add("City: $userCity")
        if (userSpouseName.isNotBlank()) parts.add("Spouse name: $userSpouseName")
        if (userPan.isNotBlank())        parts.add("PAN number: $userPan")
        if (userLanguage.isNotBlank())   parts.add("Preferred language: $userLanguage")
        return if (parts.isEmpty()) ""
        else "IMPORTANT — User profile (use these values directly, do not ask the user for any of these):\n" +
            parts.joinToString("\n") { "  - $it" }
    }

    private fun calculateAge(dob: String): Int {
        return try {
            val formats = listOf("d MMMM yyyy", "dd/MM/yyyy", "d/M/yyyy", "yyyy-MM-dd", "dd-MM-yyyy")
            var birthDate: java.util.Date? = null
            for (fmt in formats) {
                try {
                    birthDate = java.text.SimpleDateFormat(fmt, Locale.ENGLISH).parse(dob)
                    if (birthDate != null) break
                } catch (_: Exception) {}
            }
            if (birthDate == null) return 0
            val today = java.util.Calendar.getInstance()
            val birth = java.util.Calendar.getInstance().also { it.time = birthDate }
            var age = today.get(java.util.Calendar.YEAR) - birth.get(java.util.Calendar.YEAR)
            if (today.get(java.util.Calendar.DAY_OF_YEAR) < birth.get(java.util.Calendar.DAY_OF_YEAR)) age--
            age
        } catch (_: Exception) { 0 }
    }

    private fun buildLanguageInstruction(): String =
        if (userLanguage == "हिंदी")
            "IMPORTANT: The user speaks Hindi. Respond entirely in Hindi (Devanagari script)."
        else ""

    private fun speak(text: String) {
        val clean = text.replace(MARKDOWN_REGEX, "")
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
                        if (clientErrorRetries < 3) {
                            clientErrorRetries++
                            Handler(Looper.getMainLooper()).postDelayed({ startListening() }, 500L * clientErrorRetries)
                        } else {
                            Log.e(TAG, "SpeechRecognizer ERROR_CLIENT: max retries reached")
                        }
                    }
                    else -> Log.e(TAG, "SpeechRecognizer unhandled error: $error")
                }
            }
            override fun onResults(results: Bundle?) {
                listeningState.value = false
                clientErrorRetries = 0
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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (userLanguage == "हिंदी") "hi-IN" else Locale.US.toString())
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

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(messages) { msg ->
                MessageBubble(msg)
            }
        }

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
