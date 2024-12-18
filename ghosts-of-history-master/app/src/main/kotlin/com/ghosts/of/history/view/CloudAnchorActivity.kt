/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ghosts.of.history.view

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.*
import android.view.GestureDetector.SimpleOnGestureListener
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.GuardedBy
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.ghosts.of.history.R
import com.ghosts.of.history.common.helpers.*
import com.ghosts.of.history.common.rendering.*
import com.ghosts.of.history.model.*
import com.ghosts.of.history.utils.getAnchorsDataFromFirebase
import com.ghosts.of.history.utils.getVideoDownloadUri
import com.ghosts.of.history.view.PrivacyNoticeDialogFragment.HostResolveListener
import com.ghosts.of.history.viewmodel.CloudAnchorManager
import com.ghosts.of.history.viewmodel.CloudAnchorManager.CloudAnchorListener
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.ar.core.*
import com.google.ar.core.ArCoreApk.InstallStatus
import com.google.ar.core.Config.CloudAnchorMode
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.SessionPausedException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.IOException
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.hypot


/**
 * Main Activity for the Persistent Cloud Anchor Sample.
 *
 *
 * This is a simple example that shows how to host and resolve anchors using ARCore Cloud Anchors
 * API calls. This app only has at most one anchor at a time, to focus more on the cloud aspect of
 * anchors.
 */
class CloudAnchorActivity : AppCompatActivity(), GLSurfaceView.Renderer {
    private enum class HostResolveMode {
        HOSTING, RESOLVING
    }

    // Geolocation
    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient
    private var locationPermissionGranted = false


    // Rendering. The Renderers are created here, and initialized when the GL surface is created.
    private lateinit var surfaceView: GLSurfaceView
    private val backgroundRenderer = BackgroundRenderer()
    private val featureMapQualityBarObject = ObjectRenderer()
    private val objectRenderer = ObjectRenderer()
    private val planeRenderer = PlaneRenderer()
    private val pointCloudRenderer = PointCloudRenderer()
    private val videoRendererWithBackground = VideoRendererWithBackground()
    private val videoPlayers = mutableMapOf<String, VideoPlayerWithBackground>()
    private var installRequested = false

    // Temporary matrices allocated here to reduce number of allocations for each frame.
    private val anchorMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val anchorTranslation = FloatArray(3)

    // Locks needed for synchronization
    private val singleTapLock = Any()
    private val anchorLock = Any()

    // Tap handling and UI.
    private val trackingStateHelper = TrackingStateHelper(this)
    private lateinit var displayRotationHelper: DisplayRotationHelper
    private lateinit var debugText: TextView
    private lateinit var userMessageText: TextView
    private lateinit var sharedPreferences: SharedPreferences
    @Volatile
    private var weAreClosing = false

    // Feature Map Quality Indicator UI
    private lateinit var featureMapQualityUi: FeatureMapQualityUi
    private lateinit var anchorPose: Pose
    private var hostedAnchor = false
    private var lastEstimateTimestampMillis: Long = 0

    @GuardedBy("singleTapLock")
    private var queuedSingleTap: MotionEvent? = null
    private var session: Session? = null

    @GuardedBy("anchorLock")
    private var anchor: Anchor? = null

    @GuardedBy("anchorLock")
    private val resolvedAnchors: MutableList<Anchor> = ArrayList()

    @GuardedBy("anchorLock")
    private var playingAnchor: Anchor? = null

    @GuardedBy("anchorLock")
    private var unresolvedAnchorIds: MutableList<String> = ArrayList()

    @GuardedBy("anchorLock")
    private var anchorIdToAnchorData: Map<String, AnchorData> = emptyMap()
    private var cloudAnchorManager: CloudAnchorManager? = null
    private var currentMode: HostResolveMode? = null
    private var lastKnownLocation: Location? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.cloud_anchor)
        surfaceView = findViewById(R.id.surfaceview)
        displayRotationHelper = DisplayRotationHelper(this)
        setUpTapListener()
        // Set up renderer.
        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0) // Alpha used for plane blending.
        surfaceView.setRenderer(this)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        surfaceView.setWillNotDraw(false)
        installRequested = false

        // Initialize Cloud Anchor variables.
        currentMode = if (intent.getBooleanExtra(EXTRA_HOSTING_MODE, false)) {
            HostResolveMode.HOSTING
        } else {
            HostResolveMode.RESOLVING
        }

        // Initialize UI components.
        debugText = findViewById(R.id.debug_message)
        userMessageText = findViewById(R.id.user_message)
        if (currentMode == HostResolveMode.RESOLVING) {
            debugText.visibility = View.GONE
            userMessageText.visibility = View.GONE
        }

        val cancelButton = findViewById<FloatingActionButton>(R.id.cancel_scanning_button)
        cancelButton.setOnClickListener {
            weAreClosing = true
            finish()
        }

        showPrivacyDialog()
        getLocationPermission()
    }

    private fun getLocationPermission() {
        /*
         * Request location permission, so that we can get the location of the
         * device. The result of the permission request is handled by a callback,
         * onRequestPermissionsResult.
         */
        if (ContextCompat.checkSelfPermission(this.applicationContext,
                        Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            locationPermissionGranted = true
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CAMERA),
                    1)
        }
    }

    @SuppressLint("MissingPermission")
    private fun getDeviceLocation() {
        /*
         * Get the best and most recent location of the device, which may be null in rare
         * cases when a location is not available.
         */
        try {
            if (locationPermissionGranted) {
                val locationResult = fusedLocationProviderClient.lastLocation
                locationResult.addOnCompleteListener(this) { task ->
                    if (task.isSuccessful && task.result != null) {
                        lastKnownLocation = task.result
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e("Exception: %s", e.message, e)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun getDeviceLocationAsync(): Location? {
        if (locationPermissionGranted) {
            return fusedLocationProviderClient.lastLocation.await()
        }
        return null
    }

    private fun setUpTapListener() {
        val simpleOnGestureListener = object : SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                synchronized(singleTapLock) {
                    if (currentMode == HostResolveMode.HOSTING) {
                        queuedSingleTap = e
                    }
                }
                return true
            }

            override fun onDown(e: MotionEvent): Boolean = true
        }
        val gestureDetector = GestureDetector(this, simpleOnGestureListener)
        surfaceView.setOnTouchListener { _: View?, event: MotionEvent ->
            gestureDetector.onTouchEvent(event)
        }
    }

    private fun showPrivacyDialog() {
        sharedPreferences = getSharedPreferences(PREFERENCE_FILE_KEY, MODE_PRIVATE)
        val allowShareImages = sharedPreferences.getBoolean(ALLOW_SHARE_IMAGES_KEY, false)
        if (currentMode == HostResolveMode.HOSTING) {
            if (!allowShareImages) {
                showNoticeDialog { onPrivacyAcceptedForHost() }
            } else {
                onPrivacyAcceptedForHost()
            }
        } else {
            if (!allowShareImages) {
                showNoticeDialog { onPrivacyAcceptedForResolve() }
            } else {
                onPrivacyAcceptedForResolve()
            }
        }
    }

    override fun onDestroy() {
        session?.let {
            // Explicitly close ARCore Session to release native resources.
            // Review the API reference for important considerations before calling close() in apps with
            // more complicated lifecycle requirements:
            // https://developers.google.com/ar/reference/java/arcore/reference/com/google/ar/core/Session#close()
            it.close()
            session = null
        }

        videoPlayers.values.forEach {
            it.release()
        }

        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        surfaceView.onResume()
    }

    override fun onResume() {
        super.onResume()
        if (sharedPreferences.getBoolean(ALLOW_SHARE_IMAGES_KEY, false)) {
            createSession()
        }
        displayRotationHelper.onResume()
    }

    private fun createSession() {
        if (session == null) {
            var exception: Exception? = null
            var messageId = -1
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }
                    InstallStatus.INSTALLED -> {}
                }
                // ARCore requires camera permissions to operate. If we did not yet obtain runtime
                // permission on Android M and above, now is a good time to ask the user for it.
                if (!CameraPermissionHelper.hasCameraPermission(this)) {
                    CameraPermissionHelper.requestCameraPermission(this)
                    return
                }
                val session = Session(this)
                this.session = session
                cloudAnchorManager = CloudAnchorManager(session)
            } catch (e: UnavailableArcoreNotInstalledException) {
                messageId = R.string.arcore_unavailable
                exception = e
            } catch (e: UnavailableApkTooOldException) {
                messageId = R.string.arcore_too_old
                exception = e
            } catch (e: UnavailableSdkTooOldException) {
                messageId = R.string.arcore_sdk_too_old
                exception = e
            } catch (e: Exception) {
                messageId = R.string.arcore_exception
                exception = e
            }
            if (exception != null) {
                userMessageText.setText(messageId)
                debugText.setText(messageId)
                Log.e(TAG, "Exception creating session", exception)
                return
            }

            // Create default config and check if supported.
            val config = Config(session)
            config.cloudAnchorMode = CloudAnchorMode.ENABLED
            session!!.configure(config)
        }

        try {
            session!!.resume()
        } catch (e: CameraNotAvailableException) {
            userMessageText.setText(R.string.camera_unavailable)
            debugText.setText(R.string.camera_unavailable)
            session = null
            cloudAnchorManager = null
        }
    }

    public override fun onPause() {
        Log.i("CloudAnchorActivity", "onPause")
        super.onPause()

        session?.let {
            displayRotationHelper.onPause()
            it.pause()
        }
        playingAnchor?.let {
            videoPlayers[it.cloudAnchorId]?.pausePlaybackAndSeekToStart()
        }
        playingAnchor = null
    }

    override fun onStop() {
        Log.i("CloudAnchorActivity", "onStop")
        super.onStop()
        surfaceView.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
                    .show()
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                CameraPermissionHelper.launchPermissionSettings(this)
            }
            finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
    }

    /**
     * Handles the most recent user tap.
     *
     *
     * We only ever handle one tap at a time, since this app only allows for a single anchor.
     *
     * @param frame the current AR frame
     * @param cameraTrackingState the current camera tracking state
     */
    private fun handleTap(frame: Frame, cameraTrackingState: TrackingState) {
        // Handle taps. Handling only one tap per frame, as taps are usually low frequency
        // compared to frame rate.
        synchronized(singleTapLock) {
            synchronized(anchorLock) {
                // Only handle a tap if the anchor is currently null, the queued tap is non-null and the
                // camera is currently tracking.
                if (anchor == null && queuedSingleTap != null && cameraTrackingState == TrackingState.TRACKING) {
                    check(currentMode == HostResolveMode.HOSTING) {
                        "We should only be creating an anchor in hosting mode."
                    }
                    for (hit in frame.hitTest(queuedSingleTap)) {
                        if (shouldCreateAnchorWithHit(hit)) {
                            debugText.text = getString(R.string.debug_hosting_save, QUALITY_INSUFFICIENT_STRING)
                            // Create an anchor using a hit test with plane
                            val newAnchor = hit.createAnchor()
                            val plane = hit.trackable as Plane
                            featureMapQualityUi = if (plane.type == Plane.Type.VERTICAL) {
                                FeatureMapQualityUi.createVerticalFeatureMapQualityUi(
                                    featureMapQualityBarObject
                                )
                            } else {
                                FeatureMapQualityUi.createHorizontalFeatureMapQualityUi(
                                    featureMapQualityBarObject
                                )
                            }
                            setNewAnchor(newAnchor)
                            break // Only handle the first valid hit.
                        }
                    }
                }
            }
            queuedSingleTap = null
        }
    }

    override fun onSurfaceCreated(gl: GL10, config: EGLConfig) {
        Log.i("CloudAnchorActivity", "onSurfaceCreated")
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

        // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
        try {
            // Create the texture and pass it to ARCore session to be filled during update().
            backgroundRenderer.createOnGlThread(this)
            planeRenderer.createOnGlThread(this, "models/trigrid.png")
            pointCloudRenderer.createOnGlThread(this)
            videoRendererWithBackground.createOnGlThread(this)
            featureMapQualityBarObject.createOnGlThread(
                    this, "models/map_quality_bar.obj", "models/map_quality_bar.png")
            featureMapQualityBarObject.setMaterialProperties(0.0f, 2.0f, 0.02f, 0.5f)
            objectRenderer.createOnGlThread(this, "models/anchor.obj", "models/anchor.png")
            objectRenderer.setMaterialProperties(0.0f, 0.75f, 0.1f, 0.5f)
        } catch (ex: IOException) {
            Log.e(TAG, "Failed to read an asset file", ex)
        }
    }

    override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
        displayRotationHelper.onSurfaceChanged(width, height)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !weAreClosing) return

        // Clear screen to notify driver it should not load any pixels from previous frame.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val session = session ?: return

        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.
        displayRotationHelper.updateSessionIfNeeded(session)
        try {
            session.setCameraTextureName(backgroundRenderer.textureId)

            // Obtain the current frame from ARSession. When the configuration is set to
            // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
            // camera framerate.
            val frame = try {
                session.update()
            } catch (_: SessionPausedException) {
                return
            }
            val camera = frame.camera
            val cameraTrackingState = camera.trackingState

            // Notify the cloudAnchorManager of all the updates.
            cloudAnchorManager!!.onUpdate()

            // Handle user input.
            handleTap(frame, cameraTrackingState)

            // If frame is ready, render camera preview image to the GL surface.
            backgroundRenderer.draw(frame)

            // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
            trackingStateHelper.updateKeepScreenOnFlag(camera.trackingState)

            // If not tracking, don't draw 3d objects.
            if (cameraTrackingState == TrackingState.PAUSED) {
                return
            }

            // Get camera and projection matrices.
            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)
            if (currentMode == HostResolveMode.HOSTING) {
                frame.acquirePointCloud().use { pointCloud ->
                    pointCloudRenderer.update(pointCloud)
                    pointCloudRenderer.draw(viewMatrix, projectionMatrix)
                }
            }
            val colorCorrectionRgba = FloatArray(4)
            frame.lightEstimate.getColorCorrection(colorCorrectionRgba, 0)
            var shouldDrawFeatureMapQualityUi = false
            synchronized(anchorLock) {
                for (resolvedAnchor in resolvedAnchors) {
                    // Update the poses of resolved anchors that can be drawn and render them.
                    if (resolvedAnchor.trackingState == TrackingState.TRACKING) {
                        // Get the current pose of an Anchor in world space. The Anchor pose is updated
                        // during calls to session.update() as ARCore refines its estimate of the world.
                        anchorPose = resolvedAnchor.pose

                        val objectNativeFacing = listOf(1f, 0f, 0f).toFloatArray() // direction object faces in model

                        val anchorPoseTranslation = anchorPose.extractTranslation()
                        val objectToCamera = anchorPoseTranslation
                                .inverse()
                                .compose(camera.pose).let { pose ->
                                    FloatArray(3).also { pose.getTranslation(it, 0) }
                                }.also { it[1] = 0f }

                        val cameraFacingPose = anchorPoseTranslation
                                .compose(MathHelpers.rotateBetween(objectNativeFacing, objectToCamera))
                                .compose(Pose.makeRotation(0.0f, 0.707f, 0.0f, 0.707f)) // rotate 90 degrees around OY
                        cameraFacingPose.toMatrix(anchorMatrix, 0)
                        // Update and draw the model and its shadow.
                        drawAnchor(resolvedAnchor, anchorMatrix, colorCorrectionRgba)
                    }
                }
                val closestAnchor = resolvedAnchors.filter { anchor ->
                    val cameraPose = camera.pose
                    val anchorPose = anchor.pose
                    val x = cameraPose.tx() - anchorPose.tx()
                    val y = cameraPose.ty() - anchorPose.ty()
                    val z = cameraPose.tz() - anchorPose.tz()
                    val distMeters = x * x + y * y + z * z
                    anchor.pose.getTranslation(anchorTranslation, 0)
                    isWorldPositionVisible(anchorTranslation) || distMeters < DISAPPEAR_DISTANCE
                }.minByOrNull { anchor ->
                    val cameraPose = camera.pose
                    val anchorPose = anchor.pose
                    val x = cameraPose.tx() - anchorPose.tx()
                    val y = cameraPose.ty() - anchorPose.ty()
                    val z = cameraPose.tz() - anchorPose.tz()
                    x * x + y * y + z * z
                }

                if (closestAnchor != playingAnchor) {
                    playingAnchor?.let {
                        videoPlayers[it.cloudAnchorId]?.pausePlaybackAndSeekToStart()
                    }
                    playingAnchor = null
                }
                closestAnchor?.let {
                    videoPlayers[closestAnchor.cloudAnchorId]?.startPlayback()
                    playingAnchor = closestAnchor
                }
                anchor?.let { anchor ->
                    if (anchor.trackingState == TrackingState.TRACKING) {
                        anchorPose = anchor.pose
                        anchorPose.toMatrix(anchorMatrix, 0)
                        anchorPose.getTranslation(anchorTranslation, 0)
                        drawAnchor(anchor, anchorMatrix, colorCorrectionRgba)
                        if (!hostedAnchor) {
                            shouldDrawFeatureMapQualityUi = true
                        }
                    }
                } ?: run { // anchor == null
                    // Visualize planes.
                    if (currentMode == HostResolveMode.HOSTING) {
                        planeRenderer.drawPlanes(
                                session.getAllTrackables(Plane::class.java),
                                camera.displayOrientedPose,
                                projectionMatrix)
                    }
                }
                // Update the pose of the anchor (to be) hosted if it can be drawn and render the anchor.
            }

            // Render the Feature Map Quality Indicator UI.
            // Adaptive UI is drawn here, using the values from the mapping quality API.
            if (shouldDrawFeatureMapQualityUi) {
                updateFeatureMapQualityUi(camera, colorCorrectionRgba)
            }
        } catch (t: Throwable) {
            // Avoid crashing the application due to unhandled exceptions.
            Log.e(TAG, "Exception on the OpenGL thread", t)
        }
    }

    private fun isWorldPositionVisible(worldPosition: FloatArray): Boolean {
        val viewProjectionMatrix = FloatArray(16)
        Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        val temp =
                worldPosition[0] * viewProjectionMatrix[3] +
                worldPosition[1] * viewProjectionMatrix[7] +
                worldPosition[2] * viewProjectionMatrix[11] +
                1.0f * viewProjectionMatrix[15]
        if (temp <= 0f) {
            return false
        }
        var x = worldPosition[0] * viewProjectionMatrix[0] +
                worldPosition[1] * viewProjectionMatrix[4] +
                worldPosition[2] * viewProjectionMatrix[8] +
                1.0f * viewProjectionMatrix[12]
        x /= temp
        if (x !in -1f..1f) {
            return false
        }

        var y = worldPosition[0] * viewProjectionMatrix[1] +
                worldPosition[1] * viewProjectionMatrix[5] +
                worldPosition[2] * viewProjectionMatrix[9] +
                1.0f * viewProjectionMatrix[13]
        y /= temp
        return y in -1f..1f
    }

    private fun updateFeatureMapQualityUi(camera: Camera, colorCorrectionRgba: FloatArray) {
        val featureMapQualityUiPose = anchorPose.compose(featureMapQualityUi.uiTransform)
        val cameraUiFrame = featureMapQualityUiPose.inverse().compose(camera.pose).translation
        val distance = hypot(cameraUiFrame[0].toDouble(), cameraUiFrame[2].toDouble())
        runOnUiThread {
            if (distance < MIN_DISTANCE) {
                userMessageText.setText(R.string.too_close)
            } else if (distance > MAX_DISTANCE) {
                userMessageText.setText(R.string.too_far)
            } else {
                userMessageText.setText(R.string.hosting_save)
            }
        }
        val now = SystemClock.uptimeMillis()
        // Call estimateFeatureMapQualityForHosting() every 500ms.
        if (now - lastEstimateTimestampMillis > 500 // featureMapQualityUi.updateQualityForViewpoint() calculates the angle (and intersected
                // quality bar) using the vector going from the phone to the anchor. If the person is
                // looking away from the anchor and we would incorrectly update the intersected angle with
                // the FeatureMapQuality from their current view. So we check isAnchorInView() here.
                && isWorldPositionVisible(anchorTranslation)) {
            lastEstimateTimestampMillis = now
            // Update the FeatureMapQuality for the current camera viewpoint. Can pass in ANY valid camera
            // pose to estimateFeatureMapQualityForHosting(). Ideally, the pose should represent users’
            // expected perspectives.
            val currentQuality = session!!.estimateFeatureMapQualityForHosting(camera.pose)
            featureMapQualityUi.updateQualityForViewpoint(cameraUiFrame, currentQuality)
            val averageQuality = featureMapQualityUi.computeOverallQuality()
            Log.i(TAG, "History of average mapping quality calls: $averageQuality")
            if (averageQuality >= QUALITY_THRESHOLD) {
                // Host the anchor automatically if the FeatureMapQuality threshold is reached.
                Log.i(TAG, "FeatureMapQuality has reached SUFFICIENT-GOOD, triggering hostCloudAnchor()")
                synchronized(anchorLock) {
                    hostedAnchor = true
                    cloudAnchorManager!!.hostCloudAnchor(anchor, HostListener())
                }
                runOnUiThread {
                    userMessageText.setText(R.string.hosting_processing)
                    debugText.setText(R.string.debug_hosting_processing)
                }
            }
        }

        // Render the mapping quality UI.
        featureMapQualityUi.drawUi(anchorPose, viewMatrix, projectionMatrix, colorCorrectionRgba)
    }

    private fun drawAnchor(
        anchor: Anchor,
        anchorMatrix: FloatArray,
        colorCorrectionRgba: FloatArray,
    ) {
        if (currentMode == HostResolveMode.HOSTING) {
            objectRenderer.updateModelMatrix(anchorMatrix, 1.0f)
            objectRenderer.draw(viewMatrix, projectionMatrix, colorCorrectionRgba)
        } else {
            val videoPlayer = videoPlayers[anchor.cloudAnchorId] ?: return
            val anchorData = checkNotNull(anchorIdToAnchorData[anchor.cloudAnchorId])

            var shouldFetch = false
            synchronized(videoPlayer.playerMain.lock) {
                if (!videoPlayer.playerMain.isFetching) {
                    videoPlayer.playerMain.isFetching = true
                    shouldFetch = true
                }
            }
            if (shouldFetch) {
                val videoName = anchorData.videoName
                val videoParams = anchorData.videoParams ?: VideoParams(DEFAULT_GREENSCREEN_COLOR, DEFAULT_CHROMAKEY_THRESHOLD, false)
                lifecycleScope.launch {
                    Toast.makeText(this@CloudAnchorActivity, "You found a Ghost of History!", Toast.LENGTH_SHORT).show()
                    val videoUri = getVideoDownloadUri(videoName)
                    Log.i("CloudAnchorActivity", "video download url: $videoUri")
                    val videoBackground = getUriFromRawFile(this@CloudAnchorActivity, R.raw.background)
                    if (videoUri != null) {
                        videoPlayer.play(videoUri, videoBackground, videoParams, VideoParams(Color(0.0f, 0.0f, 0.0f), DEFAULT_CHROMAKEY_THRESHOLD, true))
                    } else {
                        Toast.makeText(
                            this@CloudAnchorActivity,
                            "Couldn't load the video for anchor ${anchorData.name}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            videoPlayer.update(anchorMatrix, anchorData.scalingFactor)
            videoRendererWithBackground.draw(videoPlayer, viewMatrix, projectionMatrix)
        }
    }

    private fun getUriFromRawFile(context: Context, rawResourceId: Int): Uri {
        return Uri.Builder()
            .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .authority(context.packageName)
            .path(rawResourceId.toString())
            .build()
    }

    /** Sets the new value of the current anchor. Detaches the old anchor, if it was non-null.  */
    private fun setNewAnchor(newAnchor: Anchor) {
        synchronized(anchorLock) {
            anchor?.detach()
            anchor = newAnchor
        }
    }

    /** Adds a new anchor to the set of resolved anchors.  */
    private fun setAnchorAsResolved(newAnchor: Anchor) {
        synchronized(anchorLock) {
            if (unresolvedAnchorIds.contains(newAnchor.cloudAnchorId)) {
                resolvedAnchors.add(newAnchor)
                videoPlayers[newAnchor.cloudAnchorId] = VideoPlayerWithBackground(applicationContext)
                unresolvedAnchorIds.remove(newAnchor.cloudAnchorId)
            }
        }
    }

    /** Callback function invoked when the privacy notice is accepted.  */
    private fun onPrivacyAcceptedForHost() {
        if (!sharedPreferences.edit().putBoolean(ALLOW_SHARE_IMAGES_KEY, true).commit()) {
            throw AssertionError("Could not save the user preference to SharedPreferences!")
        }
        createSession()
        debugText.setText(R.string.debug_hosting_place_anchor)
        userMessageText.setText(R.string.hosting_place_anchor)
    }

    private fun onPrivacyAcceptedForResolve() {
        if (!sharedPreferences.edit().putBoolean(ALLOW_SHARE_IMAGES_KEY, true).commit()) {
            throw AssertionError("Could not save the user preference to SharedPreferences!")
        }
        createSession()
        val resolveListener = ResolveListener()
        lifecycleScope.launch {
            val anchorsData = getAnchorsDataFromFirebase().filter { it.isEnabled }
            synchronized(anchorLock) {
                unresolvedAnchorIds = anchorsData.map { it.anchorId }.toMutableList()
                anchorIdToAnchorData = anchorsData.associateBy { it.anchorId }
                debugText.text = getString(R.string.debug_resolving_processing, unresolvedAnchorIds.size)
                // Encourage the user to look at a previously mapped area.
                userMessageText.setText(R.string.resolving_processing)
                Log.i(
                    TAG, String.format(
                        "Attempting to resolve %d anchor(s): %s",
                        unresolvedAnchorIds.size, unresolvedAnchorIds))
                for (cloudAnchorId in unresolvedAnchorIds) {
                    cloudAnchorManager?.resolveCloudAnchor(cloudAnchorId, resolveListener)
                }
            }
            delay(1000L)
            Toast.makeText(
                this@CloudAnchorActivity,
                "Find a Ghost of History and point your camera at it!",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /* Listens for a resolved anchor. */
    private inner class ResolveListener : CloudAnchorListener {
        override fun onComplete(anchor: Anchor) {
            runOnUiThread {
                val state = anchor.cloudAnchorState
                if (state.isError) {
                    Log.e(TAG, "Error hosting a cloud anchor, state $state")
                    userMessageText.text = getString(R.string.resolving_error, state)
                    return@runOnUiThread
                }
                setAnchorAsResolved(anchor)
                userMessageText.text = getString(R.string.resolving_success)
                synchronized(anchorLock) {
                    if (unresolvedAnchorIds.isEmpty()) {
                        debugText.text = getString(R.string.debug_resolving_success)
                    } else {
                        Log.i(
                                TAG, String.format(
                                "Attempting to resolve %d anchor(s): %s",
                                unresolvedAnchorIds.size, unresolvedAnchorIds))
                        debugText.text = getString(R.string.debug_resolving_processing, unresolvedAnchorIds.size)
                    }
                }
            }
        }
    }

    /* Listens for a hosted anchor. */
    private inner class HostListener : CloudAnchorListener {
        private var cloudAnchorId: String? = null
        override fun onComplete(anchor: Anchor) {
            runOnUiThread {
                val state = anchor.cloudAnchorState
                if (state.isError) {
                    Log.e(TAG, "Error hosting a cloud anchor, state $state")
                    userMessageText.text = getString(R.string.hosting_error, state)
                    return@runOnUiThread
                }
                check(cloudAnchorId == null) {
                    "The cloud anchor ID cannot have been set before."
                }
                cloudAnchorId = anchor.cloudAnchorId
                setNewAnchor(anchor)
                Log.i(TAG, "Anchor $cloudAnchorId created.")
                userMessageText.text = getString(R.string.hosting_success)
                debugText.text = getString(R.string.debug_hosting_success, cloudAnchorId)
                saveAnchorWithNickname()
            }
        }

        private fun saveAnchorWithNickname() {
            if (cloudAnchorId == null) {
                return
            }
            lifecycleScope.launch {
                val location = getDeviceLocationAsync()
                val intent = Intent()
                intent.putExtra("anchorId", cloudAnchorId)
                intent.putExtra("latitude", location?.latitude)
                intent.putExtra("longitude", location?.longitude)
                setResult(Activity.RESULT_OK, intent)
                weAreClosing = true
                finish()
            }
//            getDeviceLocation()
//            val hostDialogFragment = HostDialogFragment()
//            // Supply num input as an argument.
//            val args = Bundle()
//            args.putString("nickname", getString(R.string.nickname_default))
//            hostDialogFragment.setOkListener(::onAnchorNameEntered)
//            hostDialogFragment.arguments = args
//            hostDialogFragment.show(supportFragmentManager, "HostDialog")
        }
    }

    fun showNoticeDialog(listener: HostResolveListener?) {
        val dialog: DialogFragment = PrivacyNoticeDialogFragment.createDialog(listener)
        dialog.show(supportFragmentManager, PrivacyNoticeDialogFragment::class.java.name)
    }

    companion object {
        private val TAG = CloudAnchorActivity::class.java.simpleName
        private const val EXTRA_HOSTING_MODE = "persistentcloudanchor.hosting_mode"
        private const val QUALITY_INSUFFICIENT_STRING = "INSUFFICIENT"
        private const val QUALITY_SUFFICIENT_GOOD_STRING = "SUFFICIENT-GOOD"
        private const val ALLOW_SHARE_IMAGES_KEY = "ALLOW_SHARE_IMAGES"
        const val PREFERENCE_FILE_KEY = "CLOUD_ANCHOR_PREFERENCES"
        private const val MIN_DISTANCE = 0.2
        private const val MAX_DISTANCE = 10.0
        private const val DISAPPEAR_DISTANCE =55.0
        private val DEFAULT_GREENSCREEN_COLOR = Color(0.0f, 1.0f, 0.0f)
        private const val DEFAULT_CHROMAKEY_THRESHOLD = 0.42f

        fun newHostingIntent(packageContext: Context?): Intent {
            val intent = Intent(packageContext, CloudAnchorActivity::class.java)
            intent.putExtra(EXTRA_HOSTING_MODE, true)
            return intent
        }

        fun newResolvingIntent(packageContext: Context?): Intent {
            val intent = Intent(packageContext, CloudAnchorActivity::class.java)
            intent.putExtra(EXTRA_HOSTING_MODE, false)
            return intent
        }

        private const val QUALITY_THRESHOLD = 0.6f

        /** Returns `true` if and only if the hit can be used to create an Anchor reliably.  */
        private fun shouldCreateAnchorWithHit(hit: HitResult): Boolean {
            val trackable = hit.trackable
            return if (trackable is Plane) {
                // Check if the hit was within the plane's polygon.
                trackable.isPoseInPolygon(hit.hitPose)
            } else false
        }
    }
}