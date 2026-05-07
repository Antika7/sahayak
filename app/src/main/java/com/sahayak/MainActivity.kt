package com.sahayak

import android.graphics.Bitmap
import android.media.MediaActionSound
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private val shutterSound = MediaActionSound()
    var imageCapture: ImageCapture? = null
    private lateinit var gemmaEngine: LocalGemmaEngine
    private lateinit var userPreferences: UserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraExecutor = Executors.newSingleThreadExecutor()
        gemmaEngine = LocalGemmaEngine(this)
        userPreferences = UserPreferences(this)

        lifecycleScope.launch(Dispatchers.IO) {
            gemmaEngine.initialize()
        }

        setContent {
            SahayakTheme {
                val navController = rememberNavController()
                var startDestination by remember { mutableStateOf<String?>(null) }

                // Holds the latest AI result so ResultScreen can read it without URL encoding limits
                var latestFormResult by remember { mutableStateOf("") }

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
                                    onComplete = { name, lang ->
                                        lifecycleScope.launch(Dispatchers.IO) {
                                            userPreferences.save(name, lang)
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
                                    onSentinel = { navController.navigate(Screen.Sentinel.route) }
                                )
                            }

                            composable(Screen.FormHelper.route) {
                                LaunchedEffect(Unit) { requestCameraPermission() }
                                FormHelperScreen(
                                    onBack = { navController.popBackStack() },
                                    onResult = { result ->
                                        latestFormResult = result
                                        navController.navigate(Screen.Result.route) {
                                            popUpTo(Screen.FormHelper.route) { inclusive = true }
                                        }
                                    },
                                    takePhoto = { onCapture, onResult -> takePhoto(onCapture, onResult) },
                                    setImageCapture = { imageCapture = it }
                                )
                            }

                            composable(Screen.Result.route) {
                                ResultScreen(
                                    resultText = latestFormResult,
                                    onScanAnother = {
                                        navController.navigate(Screen.FormHelper.route) {
                                            popUpTo(Screen.Home.route)
                                        }
                                    },
                                    onGoHome = {
                                        navController.popBackStack(Screen.Home.route, false)
                                    }
                                )
                            }

                            composable(Screen.Sentinel.route) {
                                SentinelStatusScreen(
                                    onBack = { navController.popBackStack() },
                                    onStartSentinel = { checkOverlayPermissionAndStart() }
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
                        val response = gemmaEngine.analyzeForm(
                            bitmap,
                            "User took a picture of a physical form and needs help understanding it."
                        )
                        withContext(Dispatchers.Main) {
                            onResult(response)
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
        cameraExecutor.shutdown()
        shutterSound.release()
        gemmaEngine.close()
    }
}
