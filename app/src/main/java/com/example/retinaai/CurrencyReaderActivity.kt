package com.example.retinaai

import android.graphics.Bitmap
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.retinaai.databinding.ActivityCurrencyReaderBinding
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class CurrencyReaderActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityCurrencyReaderBinding
    private lateinit var tflite: Interpreter
    private lateinit var tts: TextToSpeech
    private lateinit var gestureDetector: GestureDetector

    private val executor = Executors.newSingleThreadExecutor()
    private var allowScan = false

    private val INPUT_SIZE = 640

    // NMS THRESHOLD: 0.70f
    // Allows notes to overlap significantly (for spreading them out).
    private val IOU_THRESHOLD = 0.70f

    // CONFLICT THRESHOLD: 0.85f
    // If two different boxes overlap by 85%, they are fighting for the same spot.
    // We will delete the loser.
    private val CONFLICT_THRESHOLD = 0.85f

    // CLASS ORDER: Alphabetical (10, 100, 20, 200, 50, 500)
    private val CLASS_VALUES = intArrayOf(10, 100, 20, 200, 50, 500)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCurrencyReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tts = TextToSpeech(this, this)
        tflite = Interpreter(loadModel())

        setupGestures()
        startCamera()
    }

    /* ---------------- GESTURES ---------------- */

    private fun setupGestures() {
        gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    allowScan = true
                    binding.resultText.text = "Scanning..."
                    return true
                }
            }
        )

        binding.currencyRoot.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    /* ---------------- CAMERA ---------------- */

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(executor) { image ->
                if (allowScan) {
                    analyzeFrame()
                    allowScan = false
                }
                image.close()
            }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )

        }, ContextCompat.getMainExecutor(this))
    }

    /* ---------------- FRAME ANALYSIS ---------------- */

    private fun analyzeFrame() {
        runOnUiThread {
            val bitmap = binding.viewFinder.bitmap ?: return@runOnUiThread

            val resized = Bitmap.createScaledBitmap(
                bitmap,
                INPUT_SIZE,
                INPUT_SIZE,
                true
            )

            val input = bitmapToBuffer(resized)
            val output = Array(1) { Array(10) { FloatArray(8400) } }

            tflite.run(input, output)

            val detections = decode(output[0])
            val finalDetections = nms(detections)

            val totalAmount = finalDetections.sumOf {
                CLASS_VALUES[it.classId]
            }

            binding.resultText.text = "₹$totalAmount"

            tts.speak(
                "Total amount is $totalAmount rupees",
                TextToSpeech.QUEUE_FLUSH,
                null,
                null
            )
        }
    }

    /* ---------------- MODEL IO ---------------- */

    private fun loadModel(): ByteBuffer {
        val bytes = assets.open("currency_detector.tflite").readBytes()
        return ByteBuffer.allocateDirect(bytes.size)
            .order(ByteOrder.nativeOrder())
            .put(bytes)
            .apply { rewind() }
    }

    private fun bitmapToBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer =
            ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(
            pixels,
            0,
            INPUT_SIZE,
            0,
            0,
            INPUT_SIZE,
            INPUT_SIZE
        )

        for (p in pixels) {
            buffer.putFloat(((p shr 16) and 0xFF) / 255f)
            buffer.putFloat(((p shr 8) and 0xFF) / 255f)
            buffer.putFloat((p and 0xFF) / 255f)
        }

        buffer.rewind()
        return buffer
    }

    /* ---------------- YOLO DECODING ---------------- */

    data class Detection(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val score: Float,
        val classId: Int
    )

    private fun decode(output: Array<FloatArray>): List<Detection> {
        val detections = mutableListOf<Detection>()

        for (i in 0 until 8400) {

            var bestScore = 0f
            var bestClass = -1

            for (c in 0 until 6) {
                val score = output[4 + c][i]
                if (score > bestScore) {
                    bestScore = score
                    bestClass = c
                }
            }

            // --- TUNED THRESHOLDS ---
            // Class 4 (50): Hard to see. Keep LOW (0.25)
            // Class 5 (500): High value. Be STRICT (0.50).
            // Raising 500 to 0.50 prevents a "weak 50" from being counted as a 500.
            val threshold = when (bestClass) {
                4 -> 0.25f
                5 -> 0.50f
                else -> 0.40f
            }

            if (bestScore < threshold) continue

            val cx = output[0][i]
            val cy = output[1][i]
            val w = output[2][i]
            val h = output[3][i]

            // REJECT GIANT BOXES
            if (w > INPUT_SIZE * 0.95f || h > INPUT_SIZE * 0.95f) continue

            detections.add(
                Detection(
                    cx - w / 2,
                    cy - h / 2,
                    cx + w / 2,
                    cy + h / 2,
                    bestScore,
                    bestClass
                )
            )
        }

        return detections
    }

    private fun nms(detections: List<Detection>): List<Detection> {
        val result = mutableListOf<Detection>()
        val sorted = detections.sortedByDescending { it.score }.toMutableList()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            result.add(best)

            val iterator = sorted.iterator()
            while (iterator.hasNext()) {
                val other = iterator.next()
                val overlap = iou(best, other)

                // --- NEW CONFLICT LOGIC ---

                // 1. DUPLICATE CHECK:
                // If same note, and overlap > 70%, it's a duplicate. Delete it.
                if (best.classId == other.classId && overlap > IOU_THRESHOLD) {
                    iterator.remove()
                    continue
                }

                // 2. CONFLICT CHECK (The fix for 50+500=1000):
                // If two boxes overlap PERFECTLY (> 85%), even if they are different notes,
                // it means the AI is confused and detecting one note as two things.
                // Since 'best' has the higher score, we trust 'best' and delete 'other'.
                if (overlap > CONFLICT_THRESHOLD) {
                    iterator.remove()
                }
            }
        }
        return result
    }

    private fun iou(a: Detection, b: Detection): Float {
        val x1 = max(a.left, b.left)
        val y1 = max(a.top, b.top)
        val x2 = min(a.right, b.right)
        val y2 = min(a.bottom, b.bottom)

        val interArea =
            max(0f, x2 - x1) * max(0f, y2 - y1)

        val unionArea =
            (a.right - a.left) * (a.bottom - a.top) +
                    (b.right - b.left) * (b.bottom - b.top) -
                    interArea

        return if (unionArea == 0f) 0f else interArea / unionArea
    }

    /* ---------------- TTS ---------------- */

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
        }
    }

    override fun onDestroy() {
        tts.shutdown()
        super.onDestroy()
    }
}                                                                                                                                                                                