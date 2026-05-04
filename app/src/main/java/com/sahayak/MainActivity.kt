package com.sahayak

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.sahayak.ui.theme.SahayakTheme
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private lateinit var gemmaEngine: LocalGemmaEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        cameraExecutor = Executors.newSingleThreadExecutor()
        gemmaEngine = LocalGemmaEngine(this)
        
        // Initialize AI in background
        Executors.newSingleThreadExecutor().execute {
            kotlinx.coroutines.runBlocking {
                gemmaEngine.initialize("/data/local/tmp/gemma_model.task")
            }
        }

        setContent {
            SahayakTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HubScreen(
                        onStartSentinel = { checkOverlayPermissionAndStart() },
                        onCaptureForm = { takePhoto() }
                    )
                }
            }
        }

        requestCameraPermission()
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${"$"}packageName")
                )
                startActivity(intent)
                Toast.makeText(this, "Please grant overlay permission.", Toast.LENGTH_LONG).show()
                return
            }
        }
        startService(Intent(this, FloatingWindowService::class.java))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return

        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = imageProxyToBitmap(image)
                    image.close()
                    
                    // In a real app, update a Compose State variable to show loading...
                    Toast.makeText(this@MainActivity, "Form captured. Analyzing...", Toast.LENGTH_SHORT).show()

                    // Launch analysis
                    // NOTE: Wrap this in CoroutineScope tied to ViewModel or Lifecycle in production
                    Executors.newSingleThreadExecutor().execute {
                        kotlinx.coroutines.runBlocking {
                            val response = gemmaEngine.analyzeForm(bitmap, "User took a picture of a physical form.")
                            // Switch to Main thread to show result (or update Compose state)
                            runOnUiThread {
                                // Show dialog or new screen with response
                                Toast.makeText(this@MainActivity, response, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
                
                override fun onError(exception: androidx.camera.core.ImageCaptureException) {
                    Toast.makeText(this@MainActivity, "Failed to capture photo.", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // Helper function
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val planeProxy = image.planes[0]
        val buffer = planeProxy.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

@Composable
fun HubScreen(
    onStartSentinel: () -> Unit,
    onCaptureForm: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    // We use a Box to put UI elements over the camera preview
    Box(modifier = Modifier.fillMaxSize()) {
        
        // CameraX Preview Background
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
                    
                    // Note: We need to access MainActivity's imageCapture. 
                    // In a cleaner architecture, this would be managed by a ViewModel.
                    val imageCapture = ImageCapture.Builder().build()
                    (ctx as MainActivity).javaClass.getDeclaredField("imageCapture").apply {
                        isAccessible = true
                        set(ctx, imageCapture)
                    }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageCapture
                        )
                    } catch(exc: Exception) {
                        exc.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            }
        )

        // Overlay UI layer
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = onCaptureForm,
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
}
