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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

class FloatingWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingButton: View
    private lateinit var floatingParams: WindowManager.LayoutParams
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val TAG = "FloatingWindowService"

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var resultOverlay: View? = null
    private var lastScreenContext: ScreenContext? = null

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
        val channelId = "sahayak_sentinel_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Sahayak Screen Helper", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Sahayak is active")
            .setContentText("Tap the floating help button anytime to understand your screen.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(1, notification)
    }

    private fun setupFloatingButton() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val button = TextView(this).apply {
            text = "?"
            textSize = 28f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#1565C0"))
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(28, 20, 28, 20)
        }
        floatingButton = button

        floatingParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
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
        val accessibility = ScamDetectorAccessibilityService.instance
        if (accessibility == null) {
            Toast.makeText(this, "Please enable Sahayak in Accessibility Settings first.", Toast.LENGTH_LONG).show()
            return
        }

        val screenContext = accessibility.getScreenContext()
        lastScreenContext = screenContext
        Toast.makeText(this, "Analyzing...", Toast.LENGTH_SHORT).show()

        serviceScope.launch {
            val initialized = gemmaEngine.initialize()
            if (!initialized) {
                Toast.makeText(this@FloatingWindowService, "AI engine not ready. Please wait.", Toast.LENGTH_LONG).show()
                return@launch
            }
            val result = gemmaEngine.analyzeScreenContext(screenContext)
            showResultOverlay(result)
        }
    }

    private data class ResultUI(val bgColor: Int, val icon: String)

    private fun resultUi(risk: RiskLevel) = when (risk) {
        RiskLevel.NONE -> ResultUI(Color.parseColor("#1565C0"), "i")
        RiskLevel.LOW -> ResultUI(Color.parseColor("#E65100"), "!")
        RiskLevel.HIGH -> ResultUI(Color.parseColor("#B71C1C"), "!!!")
    }

    private fun showResultOverlay(result: ScreenAnalysisResult) {
        dismissResultOverlay()

        val (bgColor, icon) = resultUi(result.riskLevel)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            setPadding(32, 28, 32, 20)
        }

        val titleText = when (result.riskLevel) {
            RiskLevel.NONE -> "[$icon]  Screen Explained"
            RiskLevel.LOW -> "[$icon]  Caution"
            RiskLevel.HIGH -> "[$icon]  WARNING"
        }

        val titleView = TextView(this).apply {
            text = titleText
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        }

        val explanationView = TextView(this).apply {
            text = result.explanation
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(0, 12, 0, 0)
        }

        card.addView(titleView)
        card.addView(explanationView)

        if (result.riskReason != null) {
            val riskView = TextView(this).apply {
                text = result.riskReason
                textSize = 14f
                setTextColor(Color.parseColor("#FFCDD2"))
                setPadding(0, 8, 0, 0)
                setTypeface(null, Typeface.ITALIC)
            }
            card.addView(riskView)
        }

        if (result.suggestedAction != null) {
            val actionView = TextView(this).apply {
                text = "Next step: ${result.suggestedAction}"
                textSize = 14f
                setTextColor(Color.parseColor("#E3F2FD"))
                setPadding(0, 8, 0, 16)
            }
            card.addView(actionView)
        }

        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 12, 0, 0)
        }

        val speakerBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setOnClickListener { speakResult(result) }
        }

        val tellMoreBtn = TextView(this).apply {
            text = "Tell me more"
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(24, 12, 24, 12)
            setBackgroundColor(Color.parseColor("#00000033"))
            setOnClickListener { launchConversation() }
        }

        val closeBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setOnClickListener { dismissResultOverlay() }
        }

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
        val intent = Intent(this, ConversationActivity::class.java).apply {
            putExtra(ConversationActivity.EXTRA_SCREEN_CONTEXT, screenSummary)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        dismissResultOverlay()
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
        if (::floatingButton.isInitialized) {
            try { windowManager.removeView(floatingButton) } catch (_: Exception) {}
        }
        dismissResultOverlay()
        tts?.shutdown()
        tts = null
        serviceScope.cancel()
    }
}
