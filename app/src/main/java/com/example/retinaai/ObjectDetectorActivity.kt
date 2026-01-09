package com.example.retinaai

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.retinaai.databinding.ActivityObjectDetectorBinding
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.min

data class DetectionResult(val box: RectF, val label: String, val confidence: Float)

class ObjectDetectorActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityObjectDetectorBinding
    private lateinit var tts: TextToSpeech
    private var interpreter: Interpreter? = null
    private var labels = mutableListOf<String>()

    private val modelFilename = "yolo_coco.tflite"
    private val labelFilename = "labels.txt"
    private var isScanning = false

    // --- QUANTIZATION PARAMS (The Missing Link) ---
    private var inputScale = 1.0f
    private var inputZeroPoint = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityObjectDetectorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tts = TextToSpeech(this, this)

        try {
            setupModel()
        } catch (e: Exception) {
            Log.e("ObjectDetector", "Model Error", e)
            Toast.makeText(this, "Model Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }

        startCamera()

        binding.viewFinder.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (!isScanning) {
                    isScanning = true
                    tts.speak("Scanning...", TextToSpeech.QUEUE_FLUSH, null, null)
                }
            }
            true
        }
    }

    private fun setupModel() {
        val model = FileUtil.loadMappedFile(this, modelFilename)
        val options = Interpreter.Options()
        options.setNumThreads(4)
        interpreter = Interpreter(model, options)

        val reader = BufferedReader(InputStreamReader(assets.open(labelFilename)))
        reader.useLines { lines -> lines.forEach { labels.add(it) } }

        // --- EXTRACT HIDDEN METADATA ---
        val inputTensor = interpreter!!.getInputTensor(0)
        val params = inputTensor.quantizationParams()

        // If scale is 0.0, the model is likely NOT quantized (or uses defaults),
        // but since you have an INT8 model, these should be valid.
        if (params.scale != 0.0f) {
            inputScale = params.scale
            inputZeroPoint = params.zeroPoint
        }

        Log.d("TFLiteDebug", "Model Params -> Scale: $inputScale, ZeroPoint: $inputZeroPoint")
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                        processImage(imageProxy)
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
            } catch (exc: Exception) {
                Log.e("ObjectDetector", "Camera failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImage(imageProxy: ImageProxy) {
        if (!isScanning || interpreter == null) {
            imageProxy.close()
            return
        }

        val bitmap = binding.viewFinder.bitmap
        if (bitmap == null) {
            imageProxy.close()
            return
        }

        val results = runInference(bitmap)
        val narration = buildNarratorSentence(results)

        if (narration.isNotEmpty()) {
            tts.speak(narration, TextToSpeech.QUEUE_FLUSH, null, null)
        }

        isScanning = false
        imageProxy.close()
    }

    private fun runInference(bitmap: Bitmap): List<DetectionResult> {
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 640, 640, true)

        // 1. Convert with DYNAMIC Quantization
        // This uses the scale/zeroPoint we read from the model file.
        val inputBuffer = convertBitmapToByteBuffer(resizedBitmap)

        val outputTensor = interpreter!!.getOutputTensor(0)
        val outputShape = outputTensor.shape()
        val outputBuffer = TensorBuffer.createFixedSize(outputShape, DataType.FLOAT32)

        interpreter?.run(inputBuffer, outputBuffer.buffer)

        val rawResults = parseOutput(outputBuffer.floatArray, outputShape)
        return applyNMS(rawResults)
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val width = 640
        val height = 640
        val byteBuffer = ByteBuffer.allocateDirect(1 * width * height * 3)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(width * height)
        bitmap.getPixels(intValues, 0, width, 0, 0, width, height)

        var pixel = 0
        for (i in 0 until width * height) {
            val value = intValues[pixel++]

            // Standardize to 0.0 - 1.0 (Most YOLO models want this base)
            val r = ((value shr 16) and 0xFF) / 255.0f
            val g = ((value shr 8) and 0xFF) / 255.0f
            val b = (value and 0xFF) / 255.0f

            // QUANTIZATION FORMULA:
            // q = r / S + Z
            val qR = (r / inputScale) + inputZeroPoint
            val qG = (g / inputScale) + inputZeroPoint
            val qB = (b / inputScale) + inputZeroPoint

            byteBuffer.put(qR.toInt().toByte())
            byteBuffer.put(qG.toInt().toByte())
            byteBuffer.put(qB.toInt().toByte())
        }
        return byteBuffer
    }

    private fun parseOutput(output: FloatArray, shape: IntArray): List<DetectionResult> {
        val results = ArrayList<DetectionResult>()

        val dim1 = shape[1]
        val dim2 = shape[2]
        val isChannelsLast = dim1 > dim2
        val numAnchors = if (isChannelsLast) dim1 else dim2
        val numChannels = if (isChannelsLast) dim2 else dim1

        // Auto-detect if Normalized (0-1) or Pixel Coords (0-640)
        // Usually, if values > 1.0, it's pixels.
        var isNormalized = true
        for(i in 0 until 50) {
            val idx = if (isChannelsLast) i * numChannels else i
            if (output[idx] > 5.0f) { // Check first coordinate (x)
                isNormalized = false
                break
            }
        }

        for (i in 0 until numAnchors) {
            var maxConf = 0f
            var maxClassIndex = -1

            for (c in 0 until (numChannels - 4)) {
                val value = if (isChannelsLast) {
                    output[i * numChannels + (4 + c)]
                } else {
                    output[(4 + c) * numAnchors + i]
                }

                if (value > maxConf) {
                    maxConf = value
                    maxClassIndex = c
                }
            }

            // Lower threshold to 0.3 for testing
            if (maxConf > 0.3f) {
                val cx: Float
                val cy: Float
                val w: Float
                val h: Float

                if (isChannelsLast) {
                    cx = output[i * numChannels + 0]
                    cy = output[i * numChannels + 1]
                    w  = output[i * numChannels + 2]
                    h  = output[i * numChannels + 3]
                } else {
                    cx = output[0 * numAnchors + i]
                    cy = output[1 * numAnchors + i]
                    w  = output[2 * numAnchors + i]
                    h  = output[3 * numAnchors + i]
                }

                val x = if (isNormalized) cx * 640 else cx
                val y = if (isNormalized) cy * 640 else cy
                val width = if (isNormalized) w * 640 else w
                val height = if (isNormalized) h * 640 else h

                val left = x - width / 2
                val top = y - height / 2
                val right = x + width / 2
                val bottom = y + height / 2

                results.add(DetectionResult(
                    RectF(left, top, right, bottom),
                    labels.getOrElse(maxClassIndex) { "Unknown" },
                    maxConf
                ))
            }
        }
        return results
    }

    private fun applyNMS(boxes: List<DetectionResult>): List<DetectionResult> {
        val sorted = boxes.sortedByDescending { it.confidence }
        val selected = ArrayList<DetectionResult>()

        for (box in sorted) {
            var overlaps = false
            for (existing in selected) {
                if (calculateIoU(box.box, existing.box) > 0.5f) {
                    overlaps = true
                    break
                }
            }
            if (!overlaps) selected.add(box)
        }
        return selected
    }

    private fun calculateIoU(a: RectF, b: RectF): Float {
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val intersection = RectF(
            kotlin.math.max(a.left, b.left), kotlin.math.max(a.top, b.top),
            kotlin.math.min(a.right, b.right), kotlin.math.min(a.bottom, b.bottom)
        )
        val areaInter = kotlin.math.max(0f, intersection.right - intersection.left) * kotlin.math.max(0f, intersection.bottom - intersection.top)
        return areaInter / (areaA + areaB - areaInter)
    }

    private fun buildNarratorSentence(results: List<DetectionResult>): String {
        if (results.isEmpty()) return "Nothing detected."
        val sorted = results.sortedBy { it.box.centerX() }
        val left = mutableListOf<String>()
        val center = mutableListOf<String>()
        val right = mutableListOf<String>()
        val w = 640f
        val leftZone = w * 0.33f
        val rightZone = w * 0.66f

        for (item in sorted) {
            val cx = item.box.centerX()
            if (cx < leftZone) left.add(item.label)
            else if (cx > rightZone) right.add(item.label)
            else center.add(item.label)
        }

        val sentence = StringBuilder()
        if (left.isNotEmpty()) sentence.append("Left: ${left.joinToString(", ")}. ")
        if (center.isNotEmpty()) sentence.append("Center: ${center.joinToString(", ")}. ")
        if (right.isNotEmpty()) sentence.append("Right: ${right.joinToString(", ")}.")
        return sentence.toString()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            tts.speak("Scanner Ready.", TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        interpreter?.close()
        tts.stop()
        tts.shutdown()
    }
}