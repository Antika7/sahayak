package com.sahayak

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaActionSound
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sahayak.data.UserPreferences
import com.sahayak.ui.navigation.Screen
import com.sahayak.ui.screens.*
import com.sahayak.ui.theme.SahayakTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val shutterSound = MediaActionSound()
    var imageCapture: ImageCapture? = null
    private lateinit var gemmaEngine: LocalGemmaEngine
    private lateinit var userPreferences: UserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        gemmaEngine = (application as SahayakApp).gemmaEngine
        userPreferences = UserPreferences(this)

        setContent {
            SahayakTheme {
                val navController = rememberNavController()
                var startDestination by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(Unit) {
                    val savedName = userPreferences.userName.first()
                    startDestination = if (savedName.isNullOrBlank()) Screen.Welcome.route else Screen.Home.route
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    startDestination?.let { destination ->
                        NavHost(navController = navController, startDestination = destination) {

                            composable(Screen.Welcome.route) {
                                WelcomeScreen(
                                    onComplete = { name, lang, dob, city ->
                                        lifecycleScope.launch(Dispatchers.IO) {
                                            userPreferences.save(name, lang, dob, city)
                                        }
                                        navController.navigate(Screen.Home.route) {
                                            popUpTo(Screen.Welcome.route) { inclusive = true }
                                        }
                                    }
                                )
                            }

                            composable(Screen.Home.route) {
                                HomeHubScreen(
                                    userPreferences = userPreferences,
                                    onFormHelper = { navController.navigate(Screen.FormHelper.route) },
                                    onSentinel = { navController.navigate(Screen.Sentinel.route) },
                                    onSettings = { navController.navigate(Screen.Profile.route) },
                                    isSentinelActive = FloatingWindowService.isRunning.value
                                )
                            }

                            composable(Screen.Profile.route) {
                                ProfileScreen(
                                    userPreferences = userPreferences,
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable(Screen.FormHelper.route) {
                                LaunchedEffect(Unit) { requestCameraPermission() }
                                FormHelperScreen(
                                    onBack = { navController.popBackStack() },
                                    onResult = { /* unused: ConversationActivity launched directly from takePhoto */ },
                                    takePhoto = { onCapture, onResult -> takePhoto(onCapture, onResult) },
                                    setImageCapture = { imageCapture = it }
                                )
                            }

                            composable(Screen.Sentinel.route) {
                                SentinelStatusScreen(
                                    onBack = { navController.popBackStack() },
                                    onStartSentinel = { checkOverlayPermissionAndStart() },
                                    isSentinelActive = FloatingWindowService.isRunning.value,
                                    onStopSentinel = { stopSentinel() },
                                    isAccessibilityEnabled = isScamAccessibilityEnabled(),
                                    onOpenAccessibilitySettings = {
                                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun checkOverlayPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "Please grant overlay permission, then try again.", Toast.LENGTH_LONG).show()
            return
        }
        startService(Intent(this, FloatingWindowService::class.java))
    }

    private fun stopSentinel() {
        stopService(Intent(this, FloatingWindowService::class.java))
    }

    private fun isScamAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabled.any { it.id.contains("ScamDetectorAccessibilityService", ignoreCase = true) }
    }

    // onCapture fires immediately with the bitmap (so FormHelperScreen can show frozen frame)
    // onResult fires when OCR is done (clears the analyzing state)
    private fun takePhoto(onCapture: (Bitmap) -> Unit, onResult: (String) -> Unit) {
        val capture = imageCapture ?: run {
            onResult("Camera not ready. Please wait and try again.")
            return
        }

        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    shutterSound.play(MediaActionSound.SHUTTER_CLICK)
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    image.close()

                    onCapture(bitmap)

                    lifecycleScope.launch(Dispatchers.IO) {
                        val ocrText      = gemmaEngine.extractFormText(bitmap)
                        val userName     = userPreferences.userName.first()     ?: ""
                        val userLanguage = userPreferences.userLanguage.first()
                        val userDob      = userPreferences.userDob.first()
                        val userCity     = userPreferences.userCity.first()
                        withContext(Dispatchers.Main) {
                            onResult("")
                            val intent = Intent(this@MainActivity, ConversationActivity::class.java).apply {
                                putExtra(ConversationActivity.EXTRA_FORM_CONTEXT,  ocrText)
                                putExtra(ConversationActivity.EXTRA_USER_NAME,     userName)
                                putExtra(ConversationActivity.EXTRA_USER_LANGUAGE, userLanguage)
                                putExtra(ConversationActivity.EXTRA_USER_DOB,      userDob)
                                putExtra(ConversationActivity.EXTRA_USER_CITY,     userCity)
                            }
                            startActivity(intent)
                        }
                    }
                }

                override fun onError(exception: androidx.camera.core.ImageCaptureException) {
                    onResult("Failed to capture photo: ${exception.message}")
                }
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        shutterSound.release()
    }
}
