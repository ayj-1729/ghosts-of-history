package com.ghosts.of.history.explorer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.transition.Slide
import androidx.transition.Transition
import androidx.transition.TransitionManager
import coil.load
import com.ghosts.of.history.R
import com.ghosts.of.history.data.AnchorsDataRepository
import com.ghosts.of.history.data.AnchorsDataState
import com.ghosts.of.history.dataimpl.AnchorsDataRepositoryImpl
import com.ghosts.of.history.model.AnchorData
import com.ghosts.of.history.utils.getFileURL
import com.ghosts.of.history.view.CloudAnchorActivity
import com.ghosts.of.history.viewmodel.MapsActivityViewModel
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine


private const val SPLASHSCREEN_TIMEOUT_MS = 1500L

class MapsActivity : AppCompatActivity() {
    private var cameraPosition: CameraPosition? = null

    // The entry point to the Fused Location Provider.
    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient

    // A default location (Sydney, Australia) and default zoom to use when location permission is
    // not granted.
    private val defaultLocation = LatLng(-33.8523341, 151.2106085)
    private var locationPermissionGranted = false

    // The geographical location where the device is currently located. That is, the last-known
    // location retrieved by the Fused Location Provider.
    private var lastKnownLocation: Location? = null
    private lateinit var popUpDialog: Dialog

    private lateinit var anchorsDataRepository: AnchorsDataRepository

    private val viewModel: MapsActivityViewModel by viewModels {
        MapsActivityViewModel.Factory
    }

    private lateinit var map: GoogleMap

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) {
            lastKnownLocation = savedInstanceState.getParcelable(KEY_LOCATION)
            cameraPosition = savedInstanceState.getParcelable(KEY_CAMERA_POSITION)
        }

        setContentView(R.layout.activity_maps)
        removeSplashScreenAfterTimeout()

        // Construct a FusedLocationProviderClient.
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)
        popUpDialog = Dialog(this)

        val arButton = findViewById<MaterialButton>(R.id.ar_button)
        arButton.setOnClickListener { onARButtonPressed() }
        supportActionBar?.hide()

        lifecycleScope.launch {
            anchorsDataRepository = AnchorsDataRepositoryImpl(applicationContext)

            // Obtain the SupportMapFragment and get notified when the map is ready to be used.
            val mapFragment = supportFragmentManager
                .findFragmentById(R.id.map) as SupportMapFragment? ?: return@launch
            map = mapFragment.getMap()
            prepareMap()

            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.anchorsDataState
                        .collect {
                            addMarkers(it)
                        }
                }
                launch {
                    map.clickedMarkers()
                        .collect {
                            onMarkerClick(it)
                        }
                }
            }
        }

    }

    private fun removeSplashScreenAfterTimeout() = lifecycleScope.launch {
        delay(SPLASHSCREEN_TIMEOUT_MS)
        val splashScreen = findViewById<View>(R.id.splash_screen)
        val root = findViewById<ViewGroup>(R.id.maps_root)

        val transition: Transition = Slide(Gravity.TOP)
        transition.setDuration(200)
        transition.addTarget(R.id.splash_screen)

        TransitionManager.beginDelayedTransition(root, transition)

        splashScreen.visibility = View.GONE
    }

    private fun onARButtonPressed() {
//        Intent(this, ExplorerLobbyActivity::class.java).also { intent ->
//            startActivity(intent)
//        }

        val intent: Intent = CloudAnchorActivity.newResolvingIntent(this)
        startActivity(intent)
    }

    private suspend fun addMarkers(anchorsDataState: AnchorsDataState) {
        map.clear()
        for ((anchorId, anchorData) in anchorsDataState.anchorsData) {
            val color = if (anchorsDataState.visitedAnchorIds.contains(anchorId)) {
                BitmapDescriptorFactory.HUE_GREEN
            } else {
                BitmapDescriptorFactory.HUE_RED
            }
            val markerPosition =
                anchorData.geoPosition?.let {
                    LatLng(
                        it.latitude,
                        it.longitude
                    )
                } ?: LatLng(0.0, 0.0)
            val marker = map.addMarker(
                MarkerOptions()
                    .position(markerPosition)
                    .title(anchorData.name)
                    .snippet(anchorId)
                    .icon(BitmapDescriptorFactory.defaultMarker(color))
            )

            marker?.tag = false
        }
    }

    /**
     * Saves the state of the map when the activity is paused.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        map.let { map ->
            outState.putParcelable(KEY_CAMERA_POSITION, map.cameraPosition)
            outState.putParcelable(KEY_LOCATION, lastKnownLocation)
        }
        super.onSaveInstanceState(outState)
    }

    private fun prepareMap() {
        // Add a marker in Sydney and move the camera
        map.setOnInfoWindowClickListener(InfoWindowActivity())
        map.setInfoWindowAdapter(InfoWindowAdapter())

        // Prompt the user for permission.
        getLocationPermission()

        // Turn on the My Location layer and the related control on the map.
        updateLocationUI()

        // Get the current location of the device and set the position of the map.
        getDeviceLocation()
    }

    private suspend fun showPopup(
        label: String, description: String,
        anchorId: String?, imagePath: String?
    ) {
        popUpDialog.setContentView(R.layout.activity_marker_popup)

        val popUpClose = popUpDialog.findViewById<View>(R.id.txtclose) as TextView
        popUpClose.setOnClickListener { popUpDialog.dismiss() }

        val popUpLabel = popUpDialog.findViewById<View>(R.id.name) as TextView
        popUpLabel.text = label

        val popUpDescription = popUpDialog.findViewById<View>(R.id.description) as TextView
        popUpDescription.text = description

        val url = imagePath?.let { getFileURL("images/$it") }
        if (url != null) {
            val popUpImage = popUpDialog.findViewById(R.id.popImage) as ImageView
            popUpImage.load(url) {
                crossfade(true)
            }
        }

        popUpDialog.window!!.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popUpDialog.show()
    }

    /** Called when the user clicks a marker.  */
    private suspend fun onMarkerClick(marker: Marker): Boolean {
        val anchorData: AnchorData? = viewModel.anchorsDataState.value.anchorsData[marker.snippet]
        val description = anchorData?.description
        val imageUrl = anchorData?.imageName

        showPopup(
            marker.title ?: "No title",
            description ?: "No description",
            marker.snippet,
            imageUrl
        )

        return true
    }

    internal inner class InfoWindowAdapter : GoogleMap.InfoWindowAdapter {
        var mWindow: View = layoutInflater.inflate(R.layout.info_window, null)

        private fun setInfoWindowText(marker: Marker) {
            val tvTitle = mWindow.findViewById<TextView>(R.id.tvTitle)
            if (marker.tag == false) {
                tvTitle.text = marker.title
                marker.tag = true
            } else if (marker.tag == true) {
                tvTitle.text = marker.snippet
                marker.tag = false
            }
        }

        override fun getInfoWindow(arg0: Marker): View {
            setInfoWindowText(arg0)
            return mWindow
        }

        override fun getInfoContents(arg0: Marker): View {
            setInfoWindowText(arg0)
            return mWindow
        }
    }

    internal inner class InfoWindowActivity : AppCompatActivity(),
        GoogleMap.OnInfoWindowClickListener,
        OnMapReadyCallback {
        override fun onMapReady(googleMap: GoogleMap) {
            // Add markers to the map and do other map setup.
            // ...
            // Set a listener for info window events0.
            googleMap.setOnInfoWindowClickListener(this)
        }

        override fun onInfoWindowClick(marker: Marker) {
            marker.showInfoWindow()
            println("Info Window Clicked")
        }
    }


    /**
     * Prompts the user for permission to use the device location.
     */
    private fun getLocationPermission() {
        /*
         * Request location permission, so that we can get the location of the
         * device. The result of the permission request is handled by a callback,
         * onRequestPermissionsResult.
         */
        if (ContextCompat.checkSelfPermission(
                this.applicationContext,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            == PackageManager.PERMISSION_GRANTED
        ) {
            locationPermissionGranted = true
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        locationPermissionGranted = false
        when (requestCode) {
            PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION -> {

                // If request is cancelled, the result arrays are empty.
                if (grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                ) {
                    locationPermissionGranted = true
                    updateLocationUI()
                    getDeviceLocation()
                }
            }

            else -> super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    /**
     * Updates the map's UI settings based on whether the user has granted location permission.
     */
    @SuppressLint("MissingPermission")
    private fun updateLocationUI() {
        try {
            if (locationPermissionGranted) {
                map.isMyLocationEnabled = true
                map.uiSettings.isMyLocationButtonEnabled = true
            } else {
                map.isMyLocationEnabled = false
                map.uiSettings.isMyLocationButtonEnabled = false
                lastKnownLocation = null
                getLocationPermission()
            }
        } catch (e: SecurityException) {
            Log.e("Exception: %s", e.message, e)
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
                    if (task.isSuccessful) {
                        // Set the map's camera position to the current location of the device.
                        lastKnownLocation = task.result
                        if (lastKnownLocation != null) {
                            map.moveCamera(
                                CameraUpdateFactory.newLatLngZoom(
                                    LatLng(
                                        lastKnownLocation!!.latitude,
                                        lastKnownLocation!!.longitude
                                    ), DEFAULT_ZOOM.toFloat()
                                )
                            )
                        }
                    } else {
                        Log.d(TAG, "Current location is null. Using defaults.")
                        Log.e(TAG, "Exception: %s", task.exception)
                        map.moveCamera(
                            CameraUpdateFactory
                                .newLatLngZoom(defaultLocation, DEFAULT_ZOOM.toFloat())
                        )
                        map.uiSettings.isMyLocationButtonEnabled = false
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e("Exception: %s", e.message, e)
        }
    }

    companion object {
        private val TAG = MapsActivity::class.java.simpleName
        private const val DEFAULT_ZOOM = 15
        private const val PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION = 1

        // Keys for storing activity state.
        private const val KEY_CAMERA_POSITION = "camera_position"
        private const val KEY_LOCATION = "location"

        // Used for selecting the current place.
        private const val M_MAX_ENTRIES = 5
    }
}

private fun GoogleMap.clickedMarkers(): Flow<Marker> =
    callbackFlow {
        setOnMarkerClickListener {
            trySend(it); true
        }
        awaitClose { setOnMarkerClickListener { true } }
    }

private suspend fun SupportMapFragment.getMap(): GoogleMap =
    suspendCoroutine { cont ->
        getMapAsync {
            cont.resume(it)
        }
    }