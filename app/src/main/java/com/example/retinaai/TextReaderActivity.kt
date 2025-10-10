package com.example.retinaai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.retinaai.databinding.ActivityTextReaderBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class TextReaderActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityTextReaderBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var tts: TextToSpeech

    // --- Used to avoid repeating the same text aloud ---
    private var lastSpokenText: String = ""
    private var lastAnalysisTimestamp: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTextReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Text-to-Speech
        tts = TextToSpeech(this, this)

        // Check and request permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // Executor for background image analysis
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    /**
     * Starts the camera and binds preview + text recognition analyzer.
     */
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // --- Camera Preview Setup ---
            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            // --- Image Analyzer for Text Recognition ---
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply { setAnalyzer(cameraExecutor, TextRecognitionAnalyzer()) }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Analyzer class that continuously reads frames from the camera
     * and performs on-device OCR using ML Kit’s Text Recognition API.
     */
    private inner class TextRecognitionAnalyzer : ImageAnalysis.Analyzer {

        private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        @androidx.camera.core.ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image ?: return imageProxy.close()

            // Limit OCR frequency to once per second for efficiency
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastAnalysisTimestamp < 1000) {
                imageProxy.close()
                return
            }
            lastAnalysisTimestamp = currentTime

            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    // Sort text blocks top-to-bottom for natural reading
                    val sortedBlocks = visionText.textBlocks.sortedBy { it.boundingBox?.top }
                    val extractedText = sortedBlocks.joinToString(" ") { it.text }
                        .replace("\n", " ")
                        .trim()

                    // Speak detected text if it’s new and TTS is not speaking
                    if (extractedText.isNotBlank() &&
                        extractedText != lastSpokenText &&
                        !tts.isSpeaking
                    ) {
                        tts.speak(extractedText, TextToSpeech.QUEUE_FLUSH, null, null)
                        lastSpokenText = extractedText
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Text recognition failed", e)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        }
    }

    /**
     * Initialize Text-to-Speech engine and set language.
     */
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "TTS: Language not supported.")
            }
        } else {
            Log.e(TAG, "TTS Initialization Failed!")
        }
    }

    /**
     * Checks if all required permissions are granted.
     */
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Handles runtime permission results.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    /**
     * Release TTS and camera resources on exit.
     */
    override fun onDestroy() {
        super.onDestroy()
        tts.stop()
        tts.shutdown()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "RetinaAI"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
