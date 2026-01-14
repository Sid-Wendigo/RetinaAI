package com.example.retinaai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.retinaai.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tts: TextToSpeech
    private lateinit var gestureDetector: GestureDetector

    private var currentButtonIndex = -1

    private lateinit var buttons: List<AppCompatButton>
    private lateinit var buttonDescriptions: List<String>
    private var isWelcomeMessageSpoken = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tts = TextToSpeech(this, this)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        initializeButtons()
        setupInteraction()
        setupButtonClickListeners()
    }

    private fun initializeButtons() {
        buttons = listOf(
            binding.btnFindObject,
            binding.btnReadCurrency,
            binding.btnReadText,
            binding.btnObstacleAlert
        )
        buttonDescriptions = listOf(
            "Find Object",
            "Read Currency",
            "Read Text",
            "Obstacle Alert"
        )
    }

    private fun setupInteraction() {
        val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                cycleFocus()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                selectCurrentButton()
                return true
            }
        }
        gestureDetector = GestureDetector(this, gestureListener)

        binding.mainLayout.setOnTouchListener { view, event ->
            gestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_DOWN) {
                view.performClick()
            }
            true
        }
    }

    private fun cycleFocus() {
        currentButtonIndex = (currentButtonIndex + 1) % buttons.size
        val description = buttonDescriptions[currentButtonIndex]
        tts.speak(description, TextToSpeech.QUEUE_FLUSH, null, null)
        updateButtonFocus()
    }

    private fun updateButtonFocus() {
        buttons.forEachIndexed { index, button ->
            if (index == currentButtonIndex) {
                button.setBackgroundResource(R.drawable.button_background_focused)
            } else {
                button.setBackgroundResource(R.drawable.button_background_normal)
            }
        }
    }

    private fun selectCurrentButton() {
        if (currentButtonIndex != -1) {
            buttons[currentButtonIndex].performClick()
        }
    }

    private fun setupButtonClickListeners() {
        binding.btnFindObject.setOnClickListener {
            binding.btnFindObject.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            tts.speak("Opening Object Detector.", TextToSpeech.QUEUE_FLUSH, null, null)
            startActivity(Intent(this, ObjectDetectorActivity::class.java))
        }

        binding.btnReadCurrency.setOnClickListener {
            binding.btnReadCurrency.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            tts.speak("Opening Currency Reader.", TextToSpeech.QUEUE_FLUSH, null, null)
            startActivity(Intent(this, CurrencyReaderActivity::class.java))
        }

        binding.btnReadText.setOnClickListener {
            binding.btnReadText.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            tts.speak("Opening Text Reader.", TextToSpeech.QUEUE_FLUSH, null, null)
            startActivity(Intent(this, TextReaderActivity::class.java))
        }

        binding.btnObstacleAlert.setOnClickListener {
            binding.btnObstacleAlert.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            tts.speak("Obstacle Alert Active.", TextToSpeech.QUEUE_FLUSH, null, null)
            startActivity(Intent(this, ObstacleDetectorActivity::class.java))
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview)
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "The Language is not supported!")
            }

            if (!isWelcomeMessageSpoken) {
                tts.speak("Welcome to Retina AI Assistant.", TextToSpeech.QUEUE_FLUSH, null, null)
                isWelcomeMessageSpoken = true
            }

        } else {
            Log.e(TAG, "TTS Initialization Failed!")
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    // ====== FIX FOR CAMERA OVERLAY DISAPPEARING ======
    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()) {
            startCamera()
        }
    }
    // =================================================

    override fun onDestroy() {
        super.onDestroy()
        tts.stop()
        tts.shutdown()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "RetinaMain"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.VIBRATE)
    }
}
