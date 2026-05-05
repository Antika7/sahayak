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
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.sahayak.ui.theme.SahayakTheme
import io.noties.markwon.Markwon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private val shutterSound = MediaActionSound()
    var imageCapture: ImageCapture? = null
    private lateinit var gemmaEngine: LocalGemmaEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraExecutor = Executors.newSingleThreadExecutor()
        gemmaEngine = LocalGemmaEngine(this)

        CoroutineScope(Dispatchers.IO).launch {
            gemmaEngine.initialize()
        }

        setContent {
            SahayakTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HubScreen(
                        onStartSentinel = { checkOverlayPermissionAndStart() },
                        onCaptureForm = { onCapture, onResult -> takePhoto(onCapture, onResult) }
                    )
                }
            }
        }

        requestCameraPermission()
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
            Toast.makeText(this, "Please grant overlay permission.", Toast.LENGTH_LONG).show()
            return
        }
        startService(Intent(this, FloatingWindowService::class.java))
    }

    // onCapture fires immediately with the bitmap; onResult fires when AI finishes
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

                    CoroutineScope(Dispatchers.IO).launch {
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

@Composable
fun HubScreen(
    onStartSentinel: () -> Unit,
    onCaptureForm: (onCapture: (Bitmap) -> Unit, onResult: (String) -> Unit) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    var isAnalyzing by remember { mutableStateOf(false) }
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var resultText by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {

        // Live camera preview (hidden while analyzing)
        if (!isAnalyzing) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val capture = ImageCapture.Builder().build()
                        (ctx as MainActivity).imageCapture = capture

                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                capture
                            )
                        } catch (exc: Exception) {
                            exc.printStackTrace()
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                }
            )
        }

        // Frozen captured image shown while analyzing
        capturedBitmap?.let { bmp ->
            if (isAnalyzing) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Captured form",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                // Dim overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(Modifier),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f))
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = androidx.compose.ui.graphics.Color.White,
                            strokeWidth = 4.dp,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Analyzing form...",
                            color = androidx.compose.ui.graphics.Color.White,
                            fontSize = 18.sp
                        )
                    }
                }
            }
        }

        // Buttons (only shown when not analyzing)
        if (!isAnalyzing) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = {
                        isAnalyzing = true
                        onCaptureForm(
                            { bitmap -> capturedBitmap = bitmap },
                            { response ->
                                isAnalyzing = false
                                resultText = response
                            }
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Help with Physical Form", fontSize = 24.sp)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onStartSentinel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("Start Screen Sentinel (Scam Blocker)", fontSize = 18.sp)
                }
            }
        }

        // Result dialog
        resultText?.let { text ->
            val markwon = remember { Markwon.create(context) }
            Dialog(onDismissRequest = {
                resultText = null
                capturedBitmap = null
            }) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.8f),
                    shape = MaterialTheme.shapes.large
                ) {
                    Column(
                        modifier = Modifier
                            .padding(24.dp)
                            .fillMaxSize()
                    ) {
                        Text(
                            text = "Form Analysis",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                        ) {
                            AndroidView(
                                factory = { ctx ->
                                    android.widget.TextView(ctx).apply {
                                        textSize = 16f
                                        setTextColor(android.graphics.Color.BLACK)
                                    }
                                },
                                update = { tv -> markwon.setMarkdown(tv, text) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                resultText = null
                                capturedBitmap = null
                            },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Close")
                        }
                    }
                }
            }
        }
    }
}
