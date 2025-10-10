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

/**
 * MainActivity — Entry point of the Retina AI app.
 *
 * This activity:
 *  - Displays the live camera feed
 *  - Handles Text-To-Speech (TTS) feedback
 *  - Uses gestures (single tap / double tap) for navigation
 *  - Provides access to feature modules: Object Finder, Currency Reader, Text Reader, Obstacle Alert
 */
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    // View binding for accessing layout elements easily
    private lateinit var binding: ActivityMainBinding

    // Text-to-Speech engine instance
    private lateinit var tts: TextToSpeech

    // Gesture detector for handling tap gestures
    private lateinit var gestureDetector: GestureDetector

    // Keeps track of which button is currently focused
    private var currentButtonIndex = -1

    // List of feature buttons and their corresponding descriptions
    private lateinit var buttons: List<AppCompatButton>
    private lateinit var buttonDescriptions: List<String>

    // Ensures welcome message is spoken only once
    private var isWelcomeMessageSpoken = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Text-To-Speech engine
        tts = TextToSpeech(this, this)

        // Check and request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // Setup button references and interactions
        initializeButtons()
        setupInteraction()
        setupButtonClickListeners()
    }

    /** Initializes the feature buttons and their spoken descriptions */
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

    /** Sets up gesture-based interaction: single tap to move focus, double tap to select */
    private fun setupInteraction() {
        val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                cycleFocus() // Move focus to the next button
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                selectCurrentButton() // Activate the currently focused button
                return true
            }
        }
        gestureDetector = GestureDetector(this, gestureListener)

        // Attach gesture detector to main layout
        binding.mainLayout.setOnTouchListener { view, event ->
            gestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_DOWN) {
                view.performClick()
            }
            true
        }
    }

    /** Moves focus to the next button and speaks its description */
    private fun cycleFocus() {
        currentButtonIndex = (currentButtonIndex + 1) % buttons.size
        val description = buttonDescriptions[currentButtonIndex]
        tts.speak(description, TextToSpeech.QUEUE_FLUSH, null, null)
        updateButtonFocus()
    }

    /** Visually updates which button is currently focused */
    private fun updateButtonFocus() {
        buttons.forEachIndexed { index, button ->
            if (index == currentButtonIndex) {
                button.setBackgroundResource(R.drawable.button_background_focused)
            } else {
                button.setBackgroundResource(R.drawable.button_background_normal)
            }
        }
    }

    /** Triggers the currently focused button’s click action */
    private fun selectCurrentButton() {
        if (currentButtonIndex != -1) {
            buttons[currentButtonIndex].performClick()
        }
    }

    /** Assigns click listeners to all buttons with TTS feedback */
    private fun setupButtonClickListeners() {
        binding.btnFindObject.setOnClickListener {
            tts.speak("Find Object selected.", TextToSpeech.QUEUE_FLUSH, null, null)
            showToast("Find Object feature coming soon!")
        }

        binding.btnReadCurrency.setOnClickListener {
            tts.speak("Read Currency selected.", TextToSpeech.QUEUE_FLUSH, null, null)
            showToast("Read Currency feature coming soon!")
        }

        binding.btnReadText.setOnClickListener {
            tts.speak("Opening Text Reader.", TextToSpeech.QUEUE_FLUSH, null, null)
            startActivity(Intent(this, TextReaderActivity::class.java))
        }

        binding.btnObstacleAlert.setOnClickListener {
            tts.speak("Obstacle Alert selected.", TextToSpeech.QUEUE_FLUSH, null, null)
            showToast("Obstacle Alert feature coming soon!")
        }
    }

    /** Starts the camera preview using CameraX */
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

    /** Handles TTS engine initialization and plays a welcome message once ready */
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

    /** Checks if all required permissions (like camera) are granted */
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Handles permission request results */
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

    /** Cleans up TTS resources when activity is destroyed */
    override fun onDestroy() {
        super.onDestroy()
        tts.stop()
        tts.shutdown()
    }

    /** Displays short toast messages */
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "RetinaMain"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
