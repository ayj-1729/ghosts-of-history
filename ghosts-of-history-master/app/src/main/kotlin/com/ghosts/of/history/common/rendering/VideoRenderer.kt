package com.ghosts.of.history.common.rendering

import android.content.Context
import android.graphics.SurfaceTexture
import android.graphics.SurfaceTexture.OnFrameAvailableListener
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.ghosts.of.history.common.rendering.ShaderUtil.checkGLError
import com.ghosts.of.history.model.Color
import com.ghosts.of.history.model.VideoParams
import java.io.IOException
import java.lang.Float.max
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.opengles.GL10


class VideoRendererWithBackground {
    private val videoRendererMain = VideoRenderer()
    private val videoRendererBackground = VideoRenderer()
    fun createOnGlThread(context: Context) {
        videoRendererBackground.createOnGlThread(context)
        videoRendererMain.createOnGlThread(context)
    }

    fun draw(player: VideoPlayerWithBackground, cameraView: FloatArray, cameraPerspective: FloatArray) {
        videoRendererBackground.draw(player.playerBackground, cameraView, cameraPerspective)
        videoRendererMain.draw(player.playerMain, cameraView, cameraPerspective)
    }

    companion object {
        private val TAG = VideoRendererWithBackground::class.java.simpleName
    }
}

class VideoRenderer {
    private var mQuadProgram = 0
    private val modelViewProjection = FloatArray(16)
    private val modelView = FloatArray(16)
    private lateinit var mQuadVertices: FloatBuffer
    private val mTexCoordTransformationMatrix = FloatArray(16)
    private var mQuadPositionParam = 0
    private var mQuadTexCoordParam = 0
    private var mModelViewProjectionUniform = 0
    private var texSizeUniform = 0
    private var timeUniform = 0
    private var isBackgroundUniform = 0
    private var greenScreenColorUniform = 0
    private var chromakeyThresholdUniform = 0
    private var QUAD_COORDS = floatArrayOf(
            -1.0f, +1.0f, 0.0f,  // top left
            -1.0f, -1.0f, 0.0f,  // bottom left
            +1.0f, -1.0f, 0.0f,  // bottom right
            +1.0f, +1.0f, 0.0f) // top right

    fun createOnGlThread(context: Context) {
        val vertexShader = ShaderUtil.loadGLShader(
                tag = TAG,
                context = context,
                type = GLES20.GL_VERTEX_SHADER,
                filename = VERTEX_SHADER_NAME)
        val fragmentShader = ShaderUtil.loadGLShader(
                tag = TAG,
                context = context,
                type = GLES20.GL_FRAGMENT_SHADER,
                filename = FRAGMENT_SHADER_NAME)
        mQuadProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(mQuadProgram, vertexShader)
        GLES20.glAttachShader(mQuadProgram, fragmentShader)
        GLES20.glLinkProgram(mQuadProgram)
        GLES20.glUseProgram(mQuadProgram)
        checkGLError(TAG, "Program creation")
        mQuadPositionParam = GLES20.glGetAttribLocation(mQuadProgram, "a_Position")
        mQuadTexCoordParam = GLES20.glGetAttribLocation(mQuadProgram, "a_TexCoord")
        mModelViewProjectionUniform =
                GLES20.glGetUniformLocation(mQuadProgram, "u_ModelViewProjection")
        timeUniform = GLES20.glGetUniformLocation(mQuadProgram, "time")
        isBackgroundUniform = GLES20.glGetUniformLocation(mQuadProgram, "is_background")
        texSizeUniform = GLES20.glGetUniformLocation(mQuadProgram, "texSize")
        greenScreenColorUniform = GLES20.glGetUniformLocation(mQuadProgram, "keyColor")
        chromakeyThresholdUniform = GLES20.glGetUniformLocation(mQuadProgram, "threshold")
        checkGLError(TAG, "Program parameters")
    }

    fun draw(player: VideoPlayer, cameraView: FloatArray, cameraPerspective: FloatArray) {
        if (!player.isStarted) {
            return
        }
        if (!player.initialized) {
            player.initialize()
        }
        if (player.done || !player.prepared) {
            return
        }
        synchronized(player.lock) {
            if (player.frameAvailable) {
                player.videoTexture.updateTexImage()
                player.frameAvailable = false
                player.videoTexture.getTransformMatrix(mTexCoordTransformationMatrix)
                setVideoDimensions(player.VIDEO_QUAD_TEXTCOORDS_TRANSFORMED, mTexCoordTransformationMatrix)
            }
        }
        Matrix.multiplyMM(modelView, 0, cameraView, 0, player.mModelMatrix, 0)
        Matrix.multiplyMM(modelViewProjection, 0, cameraPerspective, 0, modelView, 0)

        GLES20.glEnable(GL10.GL_BLEND)
        GLES20.glBlendFunc(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GL10.GL_DEPTH_TEST)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, player.mTextureId)
//        GLES20.glUniform2f(texSizeUniform, player.videoWidth, player.videoHeight) Not needed now
        GLES20.glUseProgram(mQuadProgram)
        GLES20.glUniform3f(
                greenScreenColorUniform,
                player.videoParams.greenScreenColor.red,
                player.videoParams.greenScreenColor.green,
                player.videoParams.greenScreenColor.blue)
        GLES20.glUniform1f(chromakeyThresholdUniform, player.videoParams.chromakeyThreshold)
        GLES20.glUniform1f(timeUniform, SystemClock.uptimeMillis() / 1000.0f - player.startTime)
        GLES20.glUniform1i(isBackgroundUniform, player.videoParams.isBackground.compareTo(false))

        updateQuardCoord(player.videoWidth, player.videoHeight)

        // Set the vertex positions.
        GLES20.glVertexAttribPointer(
                /* indx = */ mQuadPositionParam,
                /* size = */ COORDS_PER_VERTEX,
                /* type = */ GLES20.GL_FLOAT,
                /* normalized = */ false,
                /* stride = */ 0,
                /* ptr = */ mQuadVertices)

        // Set the texture coordinates.
        GLES20.glVertexAttribPointer(
                /* indx = */ mQuadTexCoordParam,
                /* size = */ TEXCOORDS_PER_VERTEX,
                /* type = */ GLES20.GL_FLOAT,
                /* normalized = */ false,
                /* stride = */ 0,
                /* ptr = */ fillBuffer(player.VIDEO_QUAD_TEXTCOORDS_TRANSFORMED))

        // Enable vertex arrays
        GLES20.glEnableVertexAttribArray(mQuadPositionParam)
        GLES20.glEnableVertexAttribArray(mQuadTexCoordParam)
        GLES20.glUniformMatrix4fv(
                /* location = */ mModelViewProjectionUniform,
                /* count = */ 1,
                /* transpose = */ false,
                /* value = */ modelViewProjection,
                /* offset = */ 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Disable vertex arrays
        GLES20.glDisableVertexAttribArray(mQuadPositionParam)
        GLES20.glDisableVertexAttribArray(mQuadTexCoordParam)
        checkGLError(TAG, "Draw")
    }

    private fun setVideoDimensions(videoQuadTextcoordsTransformed: FloatArray, textureCoordMatrix: FloatArray) {
        var tempUVMultRes = uvMultMat4f(QUAD_TEXCOORDS[0], QUAD_TEXCOORDS[1], textureCoordMatrix)
        videoQuadTextcoordsTransformed[0] = tempUVMultRes[0]
        videoQuadTextcoordsTransformed[1] = tempUVMultRes[1]
        tempUVMultRes = uvMultMat4f(QUAD_TEXCOORDS[2], QUAD_TEXCOORDS[3], textureCoordMatrix)
        videoQuadTextcoordsTransformed[2] = tempUVMultRes[0]
        videoQuadTextcoordsTransformed[3] = tempUVMultRes[1]
        tempUVMultRes = uvMultMat4f(QUAD_TEXCOORDS[4], QUAD_TEXCOORDS[5], textureCoordMatrix)
        videoQuadTextcoordsTransformed[4] = tempUVMultRes[0]
        videoQuadTextcoordsTransformed[5] = tempUVMultRes[1]
        tempUVMultRes = uvMultMat4f(QUAD_TEXCOORDS[6], QUAD_TEXCOORDS[7], textureCoordMatrix)
        videoQuadTextcoordsTransformed[6] = tempUVMultRes[0]
        videoQuadTextcoordsTransformed[7] = tempUVMultRes[1]
    }

    // Make a quad to hold the movie
    private fun updateQuardCoord(width: Float, height: Float) {
        val scaleFactor = 1.8f
        val normalizedWidth = width / max(width, height) * scaleFactor
        val normalizedHeight = height / max(width, height) * scaleFactor

        // Calculate the quad vertex positions
        QUAD_COORDS = floatArrayOf(
                -normalizedWidth / 2.0f, 0.0f, 0.0f,
                -normalizedWidth / 2.0f, normalizedHeight, 0.0f,
                normalizedWidth / 2.0f, 0.0f, 0.0f,
                normalizedWidth / 2.0f, normalizedHeight, 0.0f
        )

        val bbVertices = ByteBuffer.allocateDirect(QUAD_COORDS.size * FLOAT_SIZE)
        bbVertices.order(ByteOrder.nativeOrder())
        mQuadVertices = bbVertices.asFloatBuffer()
        mQuadVertices.put(QUAD_COORDS)
        mQuadVertices.position(0)
    }

    private fun fillBuffer(array: FloatArray): Buffer {
        // Convert to floats because OpenGL doesnt work on doubles, and manually
        // casting each input value would take too much time.
        val bb = ByteBuffer.allocateDirect(4 * array.size) // each float takes 4 bytes
        bb.order(ByteOrder.LITTLE_ENDIAN)
        for (d in array) bb.putFloat(d)
        bb.rewind()
        return bb
    }

    private fun uvMultMat4f(u: Float,
                            v: Float,
                            pMat: FloatArray): FloatArray {
        val x = pMat[0] * u + pMat[4] * v + pMat[12] * 1f
        val y = pMat[1] * u + pMat[5] * v + pMat[13] * 1f
        return floatArrayOf(x, y)
    }

    companion object {
        private val TAG = VideoRenderer::class.java.simpleName

        private const val TEXCOORDS_PER_VERTEX = 2
        private const val COORDS_PER_VERTEX = 3
        private const val VERTEX_SHADER_NAME = "shaders/video.vert"
        private const val FRAGMENT_SHADER_NAME = "shaders/video.frag"
//        private val QUAD_COORDS = floatArrayOf(
//                -1.0f, 0.0f, 0.0f,
//                -1.0f, 3.0f, 0.0f,
//                +1.0f, 0.0f, 0.0f,
//                +1.0f, 3.0f, 0.0f)
        private val QUAD_TEXCOORDS = floatArrayOf(
                0.0f, 0.0f,
                0.0f, 1.0f,
                1.0f, 0.0f,
                1.0f, 1.0f)
        private const val FLOAT_SIZE = 4
    }
}

class VideoPlayerWithBackground(context: Context) {
    val playerMain = VideoPlayer(context)
    val playerBackground = VideoPlayer(context)

    fun play(fileMain: Uri, fileBackground: Uri, videoParamsMain: VideoParams, videoParamsBackground : VideoParams): Boolean {
        playerBackground.play(fileBackground, videoParamsBackground)
        return playerMain.play(fileMain, videoParamsMain)
    }

    fun pausePlaybackAndSeekToStart() {
        playerBackground.pausePlaybackAndSeekToStart()
        playerMain.pausePlaybackAndSeekToStart()
    }

    fun startPlayback() {
        playerBackground.startPlayback()
        playerMain.startPlayback()
    }

    fun update(modelMatrix: FloatArray, scaleFactor: Float) {
        playerBackground.update(modelMatrix, scaleFactor)
        playerMain.update(modelMatrix, scaleFactor)
    }

    fun release() {
        playerBackground.release()
        playerMain.release()
    }

    companion object {
        private val TAG = VideoPlayerWithBackground::class.java.simpleName
    }
}

class VideoPlayer(private val context: Context) : OnFrameAvailableListener {
    private val player = MediaPlayer()
    lateinit var videoTexture: SurfaceTexture
    var initialized = false
        private set
    var done = false
        private set
    var prepared = false
        private set
    @Volatile
    var isFetching = false
    var startTime = 0.0f
        private set
    var isStarted = false
        private set
    var mTextureId = 0
    var frameAvailable = false
    var hasLoaded = false
    val mModelMatrix = FloatArray(16)
    val VIDEO_QUAD_TEXTCOORDS_TRANSFORMED =
            floatArrayOf(0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, 1.0f)
    val lock = Object()
    @Volatile
    var videoWidth = 0.0f
        private set
    @Volatile
    var videoHeight = 0.0f
        private set
    var videoParams = VideoParams(Color(0.0f, 0.99f, 0.0f), 0.7f, false)

    fun initialize() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        mTextureId = textures[0]
        val mTextureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(mTextureTarget, mTextureId)
        GLES20.glTexParameteri(mTextureTarget, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(mTextureTarget, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        videoTexture = SurfaceTexture(mTextureId)
        videoTexture.setOnFrameAvailableListener(this)
        Matrix.setIdentityM(mModelMatrix, 0)
        player.setSurface(Surface(videoTexture))
        player.prepareAsync()
        initialized = true
    }

    fun play(file: Uri, videoParams: VideoParams): Boolean {
        this.videoParams = videoParams
        player.reset()
        done = false
        player.setOnPreparedListener {
            prepared = true
            videoWidth = it.videoWidth.toFloat()
            videoHeight = it.videoHeight.toFloat()
        }
        player.setOnErrorListener { _: MediaPlayer?, _: Int, _: Int ->
            done = true
            false
        }
        player.setOnCompletionListener { done = true }
        player.setOnInfoListener { _: MediaPlayer?, _: Int, _: Int -> false }
        try {
            player.setDataSource(context, file)
            player.isLooping = true
            synchronized(lock) {
                isStarted = true
                hasLoaded = false
            }
        } catch (e: IOException) {
            Log.e(TAG, "Exception preparing movie", e)
            return false
        }
        return true
    }

    fun pausePlaybackAndSeekToStart() {
        player.pause()
        player.seekTo(0)
    }

    fun startPlayback() {
        if (prepared) {
            player.start()
        }
    }

    fun release() {
        player.stop()
        player.release()
    }

    fun update(modelMatrix: FloatArray, scaleFactor: Float) {
        val scaleMatrix = FloatArray(16)
        Matrix.setIdentityM(scaleMatrix, 0)
        scaleMatrix[0] = scaleFactor
        scaleMatrix[5] = scaleFactor
        scaleMatrix[10] = scaleFactor
        Matrix.multiplyMM(mModelMatrix, 0, modelMatrix, 0, scaleMatrix, 0)
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        synchronized(lock) {
            frameAvailable = true
            if (!hasLoaded) {
                hasLoaded = true
                startTime = SystemClock.uptimeMillis() / 1000.0f
            }
        }
    }

    companion object {
        private val TAG = VideoPlayer::class.java.simpleName
    }
}