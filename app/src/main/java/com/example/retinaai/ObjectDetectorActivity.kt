package com.example.retinaai

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.retinaai.databinding.ActivityObjectDetectorBinding
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class ResultItem(
    val rect: RectF,
    val label: String,
    val score: Float,
    val centerX: Float
)

class BoundingBoxOverlay : View {

    private val boxes = mutableListOf<ResultItem>()
    private var imageWidth = 1
    private var imageHeight = 1

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        color = Color.GREEN
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 45f
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }

    private val textBuffer = StringBuilder(32)

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle)

    fun set(list: List<ResultItem>, imgW: Int, imgH: Int) {
        boxes.clear()
        boxes.addAll(list)
        imageWidth = imgW
        imageHeight = imgH
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val scaleX = width.toFloat() / imageWidth
        val scaleY = height.toFloat() / imageHeight

        for (b in boxes) {
            val rect = RectF(
                b.rect.left * scaleX,
                b.rect.top * scaleY,
                b.rect.right * scaleX,
                b.rect.bottom * scaleY
            )
            canvas.drawRect(rect, boxPaint)

            textBuffer.setLength(0)
            textBuffer.append(b.label)
            textBuffer.append(' ')
            textBuffer.append((b.score * 100).toInt())
            textBuffer.append('%')

            canvas.drawText(textBuffer.toString(), rect.left, rect.top - 12f, textPaint)
        }
    }
}

class ObjectDetectorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityObjectDetectorBinding
    private var tts: TextToSpeech? = null
    private var detector: ObjectDetector? = null
    private lateinit var overlay: BoundingBoxOverlay

    private val executor = Executors.newSingleThreadExecutor()
    private val shutdownLatch = CountDownLatch(1)

    @Volatile private var isDestroyed = false
    @Volatile private var detectionSessionId = 0

    private var cameraProvider: ProcessCameraProvider? = null

    private var lastSpeakTime = 0L
    private val SPEAK_COOLDOWN_MS = 6000L

    private lateinit var gestureDetector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityObjectDetectorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initBackNavigation()
        initGestureControls()
        initTextToSpeech()
        initModel()
        startCamera()
    }

    private fun initBackNavigation() {
        onBackPressedDispatcher.addCallback(this) {
            finishAfterTransition()
        }
    }

    private fun initGestureControls() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                tts?.stop()
                detectionSessionId++
                lastSpeakTime = 0L
                return true
            }
        })

        binding.root.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) v.performClick()
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun initTextToSpeech() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(0.8f)
            }
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun initModel() {
        try {
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setScoreThreshold(0.45f)
                .setMaxResults(5)
                .build()

            detector = ObjectDetector.createFromFileAndOptions(
                this,
                "efficientdet.tflite",
                options
            )
        } catch (e: Exception) {
            Log.e("ObjectDetector", "Model load failed", e)
            showToast("Error loading AI model!")
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()

                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(binding.viewFinderDetector.surfaceProvider)
                }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                analysis.setAnalyzer(executor) { frame ->
                    if (isDestroyed) {
                        frame.close()
                        shutdownLatch.countDown()
                        return@setAnalyzer
                    }
                    analyzeFrame(frame)
                    frame.close()
                }

                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )

                overlay = binding.overlayDetector

            } catch (e: Exception) {
                Log.e("CameraX", "Camera start failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(frame: ImageProxy) {
        val session = detectionSessionId
        val model = detector ?: return

        // Reusable buffer (created once)
        val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(frame.planes[0].buffer)

        val rotation = frame.imageInfo.rotationDegrees
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

        val tensor = TensorImage.fromBitmap(rotated)
        val detections = model.detect(tensor)

        val results = detections.map {
            val box = it.boundingBox
            val cat = it.categories.first()
            ResultItem(
                rect = RectF(box.left, box.top, box.right, box.bottom),
                label = cat.label,
                score = cat.score,
                centerX = box.centerX()
            )
        }

        runOnUiThread {
            overlay.set(results, rotated.width, rotated.height)
            if (session == detectionSessionId) speak(results, rotated.width)
        }
    }

    private fun speak(results: List<ResultItem>, w: Int) {
        val now = System.currentTimeMillis()
        if (now - lastSpeakTime < SPEAK_COOLDOWN_MS || results.isEmpty()) return

        val top = results.sortedByDescending { it.score }.take(3)
        val sb = StringBuilder("There is ")

        top.forEachIndexed { i, item ->
            val pos = when {
                item.centerX < w * 0.35f -> "on the left"
                item.centerX > w * 0.65f -> "on the right"
                else -> "in front of you"
            }
            sb.append("a ${item.label} $pos")
            if (i < top.size - 2) sb.append(", ")
            else if (i == top.size - 2) sb.append(", and ")
        }

        tts?.speak(sb.toString(), TextToSpeech.QUEUE_FLUSH, null, null)
        lastSpeakTime = now
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        isDestroyed = true

        cameraProvider?.unbindAll()

        executor.shutdown()
        executor.awaitTermination(500, TimeUnit.MILLISECONDS)
        shutdownLatch.await(500, TimeUnit.MILLISECONDS)

        detector?.close()
        tts?.shutdown()

        super.onDestroy()
    }
}
