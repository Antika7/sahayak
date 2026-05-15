package com.sahayak

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.compose.runtime.mutableStateOf
import com.sahayak.data.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

class FloatingWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingButton: ImageView
    private lateinit var floatingParams: WindowManager.LayoutParams
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private const val TAG = "FloatingWindowService"
        private const val CHANNEL_ID = "sahayak_sentinel_channel"

        val isRunning = mutableStateOf(false)
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var resultOverlay: View? = null
    private var lastScreenContext: ScreenContext? = null
    private var isAnalyzing = false

    private val gemmaEngine get() = (application as SahayakApp).gemmaEngine

    private val overlayLayoutFlag get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning.value = true
        initTts()
        startForegroundService()
        setupFloatingButton()
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            ttsReady = (status == TextToSpeech.SUCCESS)
            if (ttsReady) tts?.language = Locale.getDefault()
        }
    }

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Sahayak Screen Helper", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sahayak is active")
            .setContentText("Tap the floating help button anytime to understand your screen.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(1, notification)
    }

    private fun setupFloatingButton() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val buttonSizePx = (56 * resources.displayMetrics.density).toInt()
        val button = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher_round)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        floatingButton = button

        floatingParams = WindowManager.LayoutParams(
            buttonSizePx,
            buttonSizePx,
            overlayLayoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }

        windowManager.addView(floatingButton, floatingParams)
        setupDragAndClick(button)
    }

    private fun setupDragAndClick(view: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var hasMoved = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = floatingParams.x
                    initialY = floatingParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    hasMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 5 || abs(dy) > 5) {
                        hasMoved = true
                        floatingParams.x = initialX + dx
                        floatingParams.y = initialY + dy
                        windowManager.updateViewLayout(floatingButton, floatingParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!hasMoved) {
                        Log.d(TAG, "Button tapped — launching handleCheckClick")
                        handleCheckClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun handleCheckClick() {
        if (isAnalyzing) return

        val accessibility = ScamDetectorAccessibilityService.instance
        if (accessibility == null) {
            Toast.makeText(this, "Please enable Sahayak in Accessibility Settings first.", Toast.LENGTH_LONG).show()
            return
        }

        val screenContext = accessibility.getScreenContext()
        lastScreenContext = screenContext
        setAnalyzingState(true)

        serviceScope.launch {
            try {
                val initialized = gemmaEngine.initialize()
                if (!initialized) {
                    setAnalyzingState(false)
                    Toast.makeText(this@FloatingWindowService, "AI engine not ready. Please wait.", Toast.LENGTH_LONG).show()
                    return@launch
                }
                val result = gemmaEngine.analyzeScreenContext(screenContext)
                showResultOverlay(result)
            } catch (e: Exception) {
                Log.e(TAG, "Analysis failed", e)
            } finally {
                setAnalyzingState(false)
            }
        }
    }

    private fun setAnalyzingState(analyzing: Boolean) {
        isAnalyzing = analyzing
        floatingButton.alpha = if (analyzing) 0.4f else 1.0f
    }

    private data class ResultUI(val bgColor: Int)

    private fun resultUi(risk: RiskLevel) = when (risk) {
        RiskLevel.NONE -> ResultUI(Color.parseColor("#1565C0"))
        RiskLevel.LOW -> ResultUI(Color.parseColor("#E65100"))
        RiskLevel.HIGH -> ResultUI(Color.parseColor("#B71C1C"))
    }

    private fun showResultOverlay(result: ScreenAnalysisResult) {
        dismissResultOverlay()

        val bgColor = resultUi(result.riskLevel).bgColor

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            setPadding(32, 24, 32, 20)
        }

        val titleText = when (result.riskLevel) {
            RiskLevel.NONE -> "Safe"
            RiskLevel.LOW -> "Caution"
            RiskLevel.HIGH -> "WARNING"
        }

        val titleView = TextView(this).apply {
            text = titleText
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        }
        card.addView(titleView)

        val detailContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 12, 0, 0)
        }

        val explanationView = TextView(this).apply {
            text = result.explanation
            textSize = 15f
            setTextColor(Color.WHITE)
        }
        detailContainer.addView(explanationView)

        if (result.riskReason != null) {
            val riskView = TextView(this).apply {
                text = result.riskReason
                textSize = 14f
                setTextColor(Color.parseColor("#FFCDD2"))
                setPadding(0, 8, 0, 0)
                setTypeface(null, Typeface.ITALIC)
            }
            detailContainer.addView(riskView)
        }

        if (result.suggestedAction != null) {
            val actionView = TextView(this).apply {
                text = "Next: ${result.suggestedAction}"
                textSize = 14f
                setTextColor(Color.parseColor("#E3F2FD"))
                setPadding(0, 8, 0, 0)
            }
            detailContainer.addView(actionView)
        }

        card.addView(detailContainer)

        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, 12, 0, 0)
        }

        val whyBtn = TextView(this).apply {
            text = "Why?"
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(24, 10, 24, 10)
            setBackgroundColor(Color.parseColor("#00000033"))
            setOnClickListener {
                if (detailContainer.visibility == View.GONE) {
                    detailContainer.visibility = View.VISIBLE
                    this.text = "Less"
                } else {
                    detailContainer.visibility = View.GONE
                    this.text = "Why?"
                }
            }
        }

        val speakerBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setOnClickListener { speakResult(result) }
        }

        val tellMoreBtn = TextView(this).apply {
            text = "Ask more"
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(24, 10, 24, 10)
            setBackgroundColor(Color.parseColor("#00000033"))
            setOnClickListener { launchConversation() }
        }

        val closeBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setOnClickListener { dismissResultOverlay() }
        }

        bottomRow.addView(whyBtn)
        bottomRow.addView(speakerBtn)
        bottomRow.addView(tellMoreBtn)
        bottomRow.addView(closeBtn)
        card.addView(bottomRow)

        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayLayoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        resultOverlay = card
        windowManager.addView(card, overlayParams)

        if (result.riskLevel == RiskLevel.HIGH) {
            speakResult(result)
        }
    }

    private fun launchConversation() {
        val context = lastScreenContext ?: return
        lastScreenContext = null
        val screenSummary = buildString {
            appendLine("App: ${context.appPackage ?: "Unknown"}")
            appendLine("Screen text: ${context.visibleText.take(1500)}")
            if (context.interactiveElements.isNotEmpty()) {
                appendLine("Buttons/links: ${context.interactiveElements.joinToString(", ")}")
            }
        }
        val prefs = UserPreferences(this)
        serviceScope.launch(Dispatchers.IO) {
            val userName       = prefs.userName.first()       ?: ""
            val userLanguage   = prefs.userLanguage.first()
            val userDob        = prefs.userDob.first()
            val userCity       = prefs.userCity.first()
            val userSpouseName = prefs.userSpouseName.first()
            val userPan        = prefs.userPan.first()
            withContext(Dispatchers.Main) {
                val intent = Intent(this@FloatingWindowService, ConversationActivity::class.java).apply {
                    putExtra(ConversationActivity.EXTRA_SCREEN_CONTEXT, screenSummary)
                    putExtra(ConversationActivity.EXTRA_USER_NAME,      userName)
                    putExtra(ConversationActivity.EXTRA_USER_LANGUAGE,  userLanguage)
                    putExtra(ConversationActivity.EXTRA_USER_DOB,       userDob)
                    putExtra(ConversationActivity.EXTRA_USER_CITY,      userCity)
                    putExtra(ConversationActivity.EXTRA_USER_SPOUSE,    userSpouseName)
                    putExtra(ConversationActivity.EXTRA_USER_PAN,       userPan)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                dismissResultOverlay()
            }
        }
    }

    private fun dismissResultOverlay() {
        resultOverlay?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { Log.w(TAG, "Overlay already removed", e) }
        }
        resultOverlay = null
    }

    private fun speakResult(result: ScreenAnalysisResult) {
        if (!ttsReady) return
        val speech = buildString {
            append(result.explanation)
            if (result.riskReason != null) append(". ${result.riskReason}")
            if (result.suggestedAction != null) append(". ${result.suggestedAction}")
        }
        tts?.speak(speech, TextToSpeech.QUEUE_FLUSH, null, "screen_result")
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning.value = false
        if (::floatingButton.isInitialized) {
            try { windowManager.removeView(floatingButton) } catch (_: Exception) {}
        }
        dismissResultOverlay()
        tts?.shutdown()
        tts = null
        serviceScope.cancel()
    }
}
