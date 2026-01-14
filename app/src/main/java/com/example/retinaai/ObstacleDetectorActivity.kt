package com.example.retinaai

import android.content.Context
import android.media.Image
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Surface
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ObstacleDetectorActivity : AppCompatActivity(), GLSurfaceView.Renderer, TextToSpeech.OnInitListener {

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var tvDebug: TextView
    private var session: Session? = null
    private var isDepthSupported = false

    // OpenGL Variables
    private var textureId = 0
    private var mQuadProgram = 0
    private lateinit var mQuadPositionParam: FloatBuffer
    private lateinit var mQuadTexCoordParam: FloatBuffer
    private lateinit var mTransformedTexCoordParam: FloatBuffer // For rotation fix

    // Feedback
    private lateinit var tts: TextToSpeech
    private var vibrator: Vibrator? = null
    private var lastSpeakTime = 0L
    private val speechCooldownMs = 1500L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_obstacle_detector)

        surfaceView = findViewById(R.id.viewFinder)
        tvDebug = findViewById(R.id.tvDebug)

        setupGLSurface()
        setupQuadCoords()

        tts = TextToSpeech(this, this)
        setupVibration()
    }

    private fun setupGLSurface() {
        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surfaceView.setRenderer(this)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    private fun setupQuadCoords() {
        // Standard Quad (The box we draw the video on)
        val quadPosition = floatArrayOf(-1.0f, -1.0f, -1.0f, 1.0f, 1.0f, -1.0f, 1.0f, 1.0f)
        mQuadPositionParam = ByteBuffer.allocateDirect(quadPosition.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        mQuadPositionParam.put(quadPosition)
        mQuadPositionParam.position(0)

        // Standard Texture Coords (Will be rotated later)
        val quadTexCoord = floatArrayOf(0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 0.0f)
        mQuadTexCoordParam = ByteBuffer.allocateDirect(quadTexCoord.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        mQuadTexCoordParam.put(quadTexCoord)
        mQuadTexCoordParam.position(0)

        // Buffer for the ROTATED coordinates
        mTransformedTexCoordParam = ByteBuffer.allocateDirect(quadTexCoord.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    }

    private fun setupVibration() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    override fun onResume() {
        super.onResume()
        if (session == null) {
            try {
                session = Session(this)
                val config = session!!.config
                config.depthMode = Config.DepthMode.AUTOMATIC
                config.focusMode = Config.FocusMode.AUTO
                session!!.configure(config)

                // Verify support
                isDepthSupported = session!!.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                if(isDepthSupported) updateDebugText("Depth Supported. Starting...")

            } catch (e: Exception) {
                updateDebugText("AR Error: ${e.message}")
            }
        }
        try {
            session?.resume()
            surfaceView.onResume()
        } catch (e: Exception) { }
    }

    override fun onPause() {
        super.onPause()
        session?.pause()
        surfaceView.onPause()
        tts.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.close()
        tts.shutdown()
    }

    // --- OPENGL RENDERER ---

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

        // Create Texture
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        val textureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(textureTarget, textureId)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)

        session?.setCameraTextureName(textureId)

        // Compile Shaders
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER,
            "attribute vec4 a_Position; attribute vec2 a_TexCoord; varying vec2 v_TexCoord; void main() { gl_Position = a_Position; v_TexCoord = a_TexCoord; }")
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER,
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 v_TexCoord; uniform samplerExternalOES u_Texture; void main() { gl_FragColor = texture2D(u_Texture, v_TexCoord); }")

        mQuadProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(mQuadProgram, vertexShader)
        GLES20.glAttachShader(mQuadProgram, fragmentShader)
        GLES20.glLinkProgram(mQuadProgram)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val displayRotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        session?.setDisplayGeometry(displayRotation, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (session == null) return
        if (textureId != 0) session?.setCameraTextureName(textureId)

        try {
            val frame = session!!.update()

            // FIX 1: TRANSFORM COORDINATES (Fixes the Landscape/Portrait Issue)
            frame.transformDisplayUvCoords(mQuadTexCoordParam, mTransformedTexCoordParam)

            // Draw Camera Background
            drawCameraBackground()

            val camera = frame.camera
            if (camera.trackingState != TrackingState.TRACKING) {
                updateDebugText("Status: ${camera.trackingState}\nMove phone slightly...")
                return
            }

            // Get Depth Image
            if (isDepthSupported) {
                try {
                    val depthImage = frame.acquireDepthImage16Bits()
                    analyzeZones(depthImage)
                    depthImage.close() // Close immediately after use
                } catch (e: Exception) {
                    // Ignore transient errors
                }
            }
        } catch (t: Throwable) {
            // Prevent crashes in loop
        }
    }

    private fun drawCameraBackground() {
        GLES20.glUseProgram(mQuadProgram)
        val position = GLES20.glGetAttribLocation(mQuadProgram, "a_Position")
        val texCoord = GLES20.glGetAttribLocation(mQuadProgram, "a_TexCoord")
        val texture = GLES20.glGetUniformLocation(mQuadProgram, "u_Texture")

        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, mQuadPositionParam)

        // USE TRANSFORMED COORDS HERE
        GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 0, mTransformedTexCoordParam)

        GLES20.glEnableVertexAttribArray(position)
        GLES20.glEnableVertexAttribArray(texCoord)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(texture, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(texCoord)
    }

    /**
     * SAFE MEMORY READER (Fixes the 9999 stuck issue)
     */
    private fun analyzeZones(image: Image) {
        val plane = image.planes[0]
        val buffer = plane.buffer.asShortBuffer() // Use ShortBuffer for safer 16-bit reads
        val width = image.width
        val height = image.height

        // Zone Setup
        val zoneWidth = width / 3
        val startY = (height * 0.4).toInt()
        val endY = (height * 0.6).toInt()
        val step = 4 // Check every 4th pixel

        var leftSum: Long = 0; var leftCount = 0
        var centerSum: Long = 0; var centerCount = 0
        var rightSum: Long = 0; var rightCount = 0

        // SAFE LOOP
        try {
            for (y in startY until endY step step) {
                for (x in 0 until width step step) {

                    // Direct ShortBuffer access avoids complicated byte shifting
                    val index = (y * width) + x

                    if (index >= buffer.limit()) continue // Safety Check

                    // Get value in mm (Short converts directly to Int)
                    var depthMM = buffer.get(index).toInt() and 0xFFFF

                    // 0 = Unknown/Too Close. 8191 = Too Far.
                    // Filter for Valid Range (10cm to 5m)
                    if (depthMM in 100..5000) {
                        if (x < zoneWidth) {
                            leftSum += depthMM; leftCount++
                        } else if (x < zoneWidth * 2) {
                            centerSum += depthMM; centerCount++
                        } else {
                            rightSum += depthMM; rightCount++
                        }
                    }
                }
            }

            // Calc Averages
            val leftAvg = if (leftCount > 0) (leftSum / leftCount).toInt() else 9999
            val centerAvg = if (centerCount > 0) (centerSum / centerCount).toInt() else 9999
            val rightAvg = if (rightCount > 0) (rightSum / rightCount).toInt() else 9999

            // Update UI
            updateDebugText("L: $leftAvg | C: $centerAvg | R: $rightAvg")

            // Run Logic
            processNavigationLogic(leftAvg, centerAvg, rightAvg)

        } catch (e: Exception) {
            // If memory fails, just keep running
        }
    }

    private fun processNavigationLogic(left: Int, center: Int, right: Int) {
        val stopThreshold = 900
        val warnThreshold = 1500

        if (center < stopThreshold) {
            triggerVibration()
            speakWithCooldown("Stop.")
            return
        }
        if (left < warnThreshold && right > warnThreshold) speakWithCooldown("Obstacle Left.")
        else if (right < warnThreshold && left > warnThreshold) speakWithCooldown("Obstacle Right.")
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }

    private fun triggerVibration() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(50)
        }
    }

    private fun speakWithCooldown(text: String) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSpeakTime > speechCooldownMs) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
            lastSpeakTime = currentTime
        }
    }

    private fun updateDebugText(text: String) {
        runOnUiThread { tvDebug.text = text }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            speakWithCooldown("Scanner Active")
        }
    }
}