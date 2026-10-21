package com.example.vrplayer

import android.annotation.SuppressLint
import java.io.File
import android.content.Intent
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: VRRenderer
    private lateinit var player: ExoPlayer
    private lateinit var sm: SensorManager
    private lateinit var controls: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var timeText: TextView
    private lateinit var zoomBar: SeekBar
    private lateinit var modeBtn: Button
    private lateinit var gyroBtn: Button
    private lateinit var soundBtn: Button
    private lateinit var playBtn: Button

    private var surface: Surface? = null
    private var enhancer: LoudnessEnhancer? = null
    private var audioSession = 0
    private val gains = intArrayOf(0, 300, 600, 900)   // миллибел: +0/+3/+6/+9 дБ
    private var gainIdx = 1
    private var gyroOn = false
    private var lastTs = 0L
    private var seeking = false
    private val ui = Handler(Looper.getMainLooper())

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            play(uri)
        }
    }

    private val vaultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val path = r.data?.getStringExtra("path")
        if (r.resultCode == RESULT_OK && path != null) play(Uri.fromFile(File(path)), secure = true)
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!seeking) {
                val d = player.duration
                if (d > 0) {
                    seek.max = (d / 1000).toInt()
                    seek.progress = (player.currentPosition / 1000).toInt()
                    timeText.text = fmt(player.currentPosition) + " / " + fmt(d)
                }
            }
            ui.postDelayed(this, 500)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        sm = getSystemService(SENSOR_SERVICE) as SensorManager

        // ---------- плеер ----------
        val rf = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(this, rf).build()
        player.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true
        )
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) { attachEnhancer(audioSessionId) }
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@MainActivity, "Ошибка: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playBtn.text = if (isPlaying) "⏸" else "▶" }
        })

        // ---------- OpenGL ----------
        renderer = VRRenderer(
            onSurface = { s -> runOnUiThread { surface = s; player.setVideoSurface(s) } },
            requestRender = { glView.requestRender() }
        )
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }

        // ---------- жесты ----------
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                setFov(renderer.fov / d.scaleFactor)
                return true
            }
        })
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                val k = renderer.fov / 75f * 0.12f   // чем сильнее зум, тем тоньше наведение
                renderer.yaw += dx * k
                renderer.pitch = (renderer.pitch + dy * k).coerceIn(-89f, 89f)
                glView.requestRender()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean { resetView(); return true }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                controls.visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                return true
            }
        })
        glView.setOnTouchListener { _, e ->
            scale.onTouchEvent(e)
            if (!scale.isInProgress) gestures.onTouchEvent(e)
            true
        }

        // ---------- интерфейс ----------
        seek = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) timeText.text = fmt(p * 1000L) + " / " + fmt(player.duration.coerceAtLeast(0))
                }
                override fun onStartTrackingTouch(s: SeekBar?) { seeking = true }
                override fun onStopTrackingTouch(s: SeekBar?) {
                    player.seekTo((s?.progress ?: 0) * 1000L); seeking = false
                }
            })
        }
        timeText = TextView(this).apply { setTextColor(Color.WHITE); text = "00:00 / 00:00"; setPadding(16, 0, 16, 0) }
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(seek); addView(timeText)
        }

        fun btn(t: String, a: () -> Unit) = Button(this).apply {
            text = t; textSize = 12f; isAllCaps = false; minWidth = 0; minimumWidth = 0
            setOnClickListener { a() }
        }
        playBtn = btn("▶") { if (player.isPlaying) player.pause() else player.play() }
        modeBtn = btn(MODES[0].name) { cycleMode() }
        val eyeBtn = btn("Глаз: Л") {}
        eyeBtn.setOnClickListener {
            renderer.rightEye = !renderer.rightEye
            eyeBtn.text = if (renderer.rightEye) "Глаз: П" else "Глаз: Л"
            glView.requestRender()
        }
        gyroBtn = btn("Гиро: выкл") { toggleGyro() }
        soundBtn = btn("Звук +3дБ") { cycleGain() }
        zoomBar = SeekBar(this).apply {
            max = 105; progress = 35
            layoutParams = LinearLayout.LayoutParams(dp(180), -2)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) { renderer.fov = 110f - p; glView.requestRender() }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        val zoomLabel = TextView(this).apply { text = " 🔍 "; setTextColor(Color.WHITE) }

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(btn("Открыть") { picker.launch(arrayOf("video/*")) })
            addView(playBtn); addView(modeBtn); addView(eyeBtn); addView(gyroBtn); addView(soundBtn)
            addView(btn("🔒 Сейф") { vaultLauncher.launch(Intent(this@MainActivity, VaultActivity::class.java)) })
            addView(btn("Сброс") { resetView() })
            addView(zoomLabel); addView(zoomBar)
        }
        val scroller = HorizontalScrollView(this).apply { addView(row2) }

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xAA000000.toInt())
            addView(row1); addView(scroller)
        }

        val root = FrameLayout(this)
        root.addView(glView)
        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)

        WindowInsetsControllerCompat(window, root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        intent?.data?.let { play(it) }
        ui.post(ticker)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { play(it) }
    }

    private fun play(uri: Uri, secure: Boolean = false) {
        // для приватных видео запрещаем скриншоты и скрываем превью в «недавних»
        if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = true
        resetView()
    }

    private fun setFov(f: Float) {
        renderer.fov = f.coerceIn(5f, 110f)
        zoomBar.progress = (110f - renderer.fov).toInt().coerceIn(0, 105)
        glView.requestRender()
    }

    private fun resetView() {
        renderer.yaw = 0f; renderer.pitch = 0f
        setFov(75f)
    }

    private fun cycleMode() {
        renderer.modeIndex = (renderer.modeIndex + 1) % MODES.size
        modeBtn.text = MODES[renderer.modeIndex].name
        glView.requestRender()
    }

    private fun cycleGain() {
        gainIdx = (gainIdx + 1) % gains.size
        soundBtn.text = "Звук +" + (gains[gainIdx] / 100) + "дБ"
        applyGain()
    }

    private fun attachEnhancer(session: Int) {
        if (session == 0 || session == audioSession && enhancer != null) return
        audioSession = session
        try {
            enhancer?.release()
            enhancer = LoudnessEnhancer(session)
            applyGain()
        } catch (_: Exception) { enhancer = null }
    }

    private fun applyGain() {
        try {
            enhancer?.apply { setTargetGain(gains[gainIdx]); enabled = gains[gainIdx] > 0 }
        } catch (_: Exception) {}
    }

    // ---------- гироскоп: интегрируем угловую скорость вокруг осей экрана ----------
    private fun toggleGyro() {
        gyroOn = !gyroOn
        gyroBtn.text = if (gyroOn) "Гиро: вкл" else "Гиро: выкл"
        if (gyroOn) {
            lastTs = 0L
            val g = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            if (g == null) { Toast.makeText(this, "Нет гироскопа", Toast.LENGTH_SHORT).show(); gyroOn = false; return }
            sm.registerListener(this, g, SensorManager.SENSOR_DELAY_GAME)
        } else sm.unregisterListener(this)
    }

    @Suppress("DEPRECATION")
    override fun onSensorChanged(e: SensorEvent) {
        if (lastTs == 0L) { lastTs = e.timestamp; return }
        val dt = (e.timestamp - lastTs) / 1e9f
        lastTs = e.timestamp
        val gx = e.values[0]; val gy = e.values[1]
        val rot = windowManager.defaultDisplay.rotation
        val up: Float; val right: Float
        if (rot == Surface.ROTATION_270) { up = -gx; right = gy } else { up = gx; right = -gy }
        val k = (180.0 / Math.PI).toFloat() * dt
        renderer.yaw -= up * k
        renderer.pitch = (renderer.pitch - right * k).coerceIn(-89f, 89f)
        glView.requestRender()
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onResume() {
        super.onResume()
        glView.onResume()
        if (gyroOn) { lastTs = 0L; sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE), SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        super.onPause()
        player.pause()
        player.clearVideoSurface()
        sm.unregisterListener(this)
        glView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        enhancer?.release()
        player.release()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }
}
