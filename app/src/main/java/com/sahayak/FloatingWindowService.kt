package com.sahayak

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FloatingWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var gemmaEngine: LocalGemmaEngine
    private val serviceScope = CoroutineScope(Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        
        // Initialize Gemma Engine
        gemmaEngine = LocalGemmaEngine(this)
        // Note: In production, load the model once in an Application class to avoid reloading.
        
        startForegroundService()
        setupFloatingWindow()
    }

    private fun startForegroundService() {
        val channelId = "sahayak_sentinel_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Sahayak Sentinel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Sahayak is active")
            .setContentText("Tap the floating Help button anytime to check for scams.")
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Using system icon as fallback
            .build()

        startForeground(1, notification)
    }

    private fun setupFloatingWindow() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Create layout dynamically or inflate from XML
        // Assuming you have a simple R.layout.layout_floating_button with a Button id 'btn_help'
        // For copy-paste ease, I'll create it programmatically here:
        val button = Button(this).apply {
            text = "🛡️ Check Screen"
            setBackgroundColor(android.graphics.Color.BLUE)
            setTextColor(android.graphics.Color.WHITE)
            textSize = 18f
        }
        floatingView = button

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 100 // Default Y position
        }

        windowManager.addView(floatingView, params)

        button.setOnClickListener {
            handleHelpClick()
        }
        
        // Note: To make it draggable, you would add an OnTouchListener tracking ACTION_DOWN/MOVE/UP.
    }

    private fun handleHelpClick() {
        Toast.makeText(this, "Analyzing Screen...", Toast.LENGTH_SHORT).show()
        
        // Mocking screen capture. Real screen capture requires MediaProjection API 
        // which requires user consent every time (or root). 
        // For a hackathon, simulate it or use an AccessibilityService to read screen text.
        val mockBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        serviceScope.launch {
            val result = gemmaEngine.analyzeScreen(
                imageBitmap = mockBitmap,
                userContext = "User clicked the floating help button while browsing."
            )
            
            // Show result. In a real app, open an Activity or Dialog activity to show the result clearly.
            Toast.makeText(this@FloatingWindowService, "AI: ${"$"}result", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
    }
}
