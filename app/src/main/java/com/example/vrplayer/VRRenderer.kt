package com.example.vrplayer

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Режим проекции: охват по горизонтали и область текстуры (x, y, ширина, высота; y=0 снизу). */
class VrMode(val name: String, val span: Float, val region: FloatArray)

val TWO_PI = (2 * PI).toFloat()
val ONE_PI = PI.toFloat()

val MODES = listOf(
    VrMode("360° моно", TWO_PI, floatArrayOf(0f, 0f, 1f, 1f)),
    VrMode("180° моно", ONE_PI, floatArrayOf(0f, 0f, 1f, 1f)),
    VrMode("180° SBS (лево/право)", ONE_PI, floatArrayOf(0f, 0f, 0.5f, 1f)),
    VrMode("180° верх/низ", ONE_PI, floatArrayOf(0f, 0.5f, 1f, 0.5f)),
    VrMode("360° верх/низ", TWO_PI, floatArrayOf(0f, 0.5f, 1f, 0.5f)),
    VrMode("360° SBS", TWO_PI, floatArrayOf(0f, 0f, 0.5f, 1f)),
)

class VRRenderer(
    private val onSurface: (Surface) -> Unit,
    private val requestRender: () -> Unit
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    @Volatile var yaw = 0f
    @Volatile var pitch = 0f
    @Volatile var fov = 75f          // вертикальный угол обзора; меньше = сильнее приближение
    @Volatile var modeIndex = 0
    @Volatile var rightEye = false

    private var texId = 0
    private var st: SurfaceTexture? = null
    private var surface: Surface? = null
    private val frameReady = AtomicBoolean(false)

    private var prog = 0
    private var aPos = 0; private var aUV = 0
    private var uMVP = 0; private var uST = 0; private var uRegion = 0; private var uTex = 0

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)
    private val stMat = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private var aspect = 1f

    private lateinit var vb: FloatBuffer
    private lateinit var ib: ShortBuffer
    private var idxCount = 0
    private var builtSpan = -1f

    private val vs = """
        uniform mat4 uMVP;
        uniform mat4 uST;
        uniform vec4 uRegion;
        attribute vec4 aPos;
        attribute vec2 aUV;
        varying vec2 vUV;
        void main() {
            gl_Position = uMVP * aPos;
            vec2 uv = aUV * uRegion.zw + uRegion.xy;
            vUV = (uST * vec4(uv, 0.0, 1.0)).xy;
        }
    """.trimIndent()

    private val fs = """
        #extension GL_OES_EGL_image_external : require
        precision highp float;
        uniform samplerExternalOES uTex;
        varying vec2 vUV;
        void main() {
            gl_FragColor = texture2D(uTex, vUV);
        }
    """.trimIndent()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        texId = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surface?.release(); st?.release()
        val s = SurfaceTexture(texId)
        s.setOnFrameAvailableListener(this)
        st = s
        val sf = Surface(s)
        surface = sf
        onSurface(sf)

        prog = link(vs, fs)
        aPos = GLES20.glGetAttribLocation(prog, "aPos")
        aUV = GLES20.glGetAttribLocation(prog, "aUV")
        uMVP = GLES20.glGetUniformLocation(prog, "uMVP")
        uST = GLES20.glGetUniformLocation(prog, "uST")
        uRegion = GLES20.glGetUniformLocation(prog, "uRegion")
        uTex = GLES20.glGetUniformLocation(prog, "uTex")
        builtSpan = -1f
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        aspect = w.toFloat() / h.coerceAtLeast(1)
    }

    override fun onFrameAvailable(s: SurfaceTexture?) {
        frameReady.set(true)
        requestRender()
    }

    override fun onDrawFrame(gl: GL10?) {
        val mode = MODES[modeIndex]
        if (builtSpan != mode.span) { buildSphere(48, 96, mode.span); builtSpan = mode.span }

        if (frameReady.getAndSet(false)) {
            try {
                st?.updateTexImage()
                st?.getTransformMatrix(stMat)
            } catch (e: Exception) { Log.w("VR", "updateTexImage: $e") }
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        Matrix.perspectiveM(proj, 0, fov, aspect, 0.05f, 10f)
        Matrix.setIdentityM(view, 0)
        Matrix.rotateM(view, 0, pitch, 1f, 0f, 0f)
        Matrix.rotateM(view, 0, yaw, 0f, 1f, 0f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        GLES20.glUseProgram(prog)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uST, 1, false, stMat, 0)

        val r = mode.region.copyOf()
        if (rightEye) {
            if (r[2] == 0.5f) r[0] = 0.5f   // SBS: правая половина
            if (r[3] == 0.5f) r[1] = 0f     // верх/низ: нижняя половина
        }
        GLES20.glUniform4f(uRegion, r[0], r[1], r[2], r[3])

        vb.position(0)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 20, vb)
        GLES20.glEnableVertexAttribArray(aPos)
        vb.position(3)
        GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 20, vb)
        GLES20.glEnableVertexAttribArray(aUV)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, idxCount, GLES20.GL_UNSIGNED_SHORT, ib)
    }

    private fun buildSphere(rings: Int, sectors: Int, span: Float) {
        val v = FloatArray((rings + 1) * (sectors + 1) * 5)
        var p = 0
        for (r in 0..rings) {
            val phi = ONE_PI * r / rings
            for (s in 0..sectors) {
                val theta = span * s / sectors - span / 2f
                v[p++] = sin(phi) * sin(theta)
                v[p++] = cos(phi)
                v[p++] = -sin(phi) * cos(theta)
                v[p++] = s.toFloat() / sectors
                v[p++] = 1f - r.toFloat() / rings
            }
        }
        val idx = ShortArray(rings * sectors * 6)
        var q = 0
        for (r in 0 until rings) for (s in 0 until sectors) {
            val a = r * (sectors + 1) + s
            val b = a + sectors + 1
            idx[q++] = a.toShort(); idx[q++] = b.toShort(); idx[q++] = (a + 1).toShort()
            idx[q++] = b.toShort(); idx[q++] = (b + 1).toShort(); idx[q++] = (a + 1).toShort()
        }
        vb = ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(v); position(0) }
        ib = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            .apply { put(idx); position(0) }
        idxCount = idx.size
    }

    private fun link(v: String, f: String): Int {
        fun sh(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) Log.e("VR", "Shader error: " + GLES20.glGetShaderInfoLog(s))
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, sh(GLES20.GL_VERTEX_SHADER, v))
        GLES20.glAttachShader(p, sh(GLES20.GL_FRAGMENT_SHADER, f))
        GLES20.glLinkProgram(p)
        return p
    }
}
