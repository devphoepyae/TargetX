package com.targetx.app.ui.receipt

import android.graphics.BitmapFactory
import android.util.Base64
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

@Composable
fun ReceiptCameraScreen(
    modifier: Modifier = Modifier,
    onImageBase64Ready: (String) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }

    Column(modifier = modifier.fillMaxSize()) {
        AndroidView(factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

                val ic = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                imageCapture = ic

                try {
                    cameraProvider.unbindAll()
                    val lifecycleOwner = ctx as androidx.lifecycle.LifecycleOwner
                    cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, ic)
                } catch (exc: Exception) {
                    onError("Camera start failed: ${exc.message}")
                }

            }, ContextCompat.getMainExecutor(ctx))

            previewView
        }, modifier = Modifier
            .fillMaxWidth()
            .weight(1f))

        Row(modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp), horizontalArrangement = Arrangement.SpaceAround) {

            Button(onClick = {
                val ic = imageCapture
                if (ic == null) {
                    onError("Camera not ready")
                    return@Button
                }
                try {
                    val tmpFile = File(context.cacheDir, "receipt_capture_${System.currentTimeMillis()}.jpg")
                    val outputOptions = ImageCapture.OutputFileOptions.Builder(tmpFile).build()
                    ic.takePicture(outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            coroutineScope.launch {
                                try {
                                    val bytes = withContext(Dispatchers.IO) {
                                        tmpFile.readBytes()
                                    }
                                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                                    onImageBase64Ready(base64)
                                } catch (e: Exception) {
                                    onError("Failed to convert captured image: ${e.message}")
                                }
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            onError("Image capture failed: ${exception.message}")
                        }
                    })
                } catch (e: Exception) {
                    onError("Capture failed: ${e.message}")
                }
            }) {
                Text("Capture")
            }

            val galleryLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                coroutineScope.launch {
                    try {
                        val input = context.contentResolver.openInputStream(uri)
                        val bytes = withContext(Dispatchers.IO) { input?.readBytes() ?: ByteArray(0) }
                        if (bytes.isEmpty()) {
                            onError("Failed to read selected image")
                            return@launch
                        }
                        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        onImageBase64Ready(base64)
                    } catch (e: Exception) {
                        onError("Failed to load image: ${e.message}")
                    }
                }
            }

            Button(onClick = { galleryLauncher.launch("image/*") }) {
                Text("Pick Image")
            }
        }
    }
}
