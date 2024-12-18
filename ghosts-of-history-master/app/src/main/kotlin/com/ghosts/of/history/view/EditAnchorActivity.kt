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
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ghosts.of.history.R
import com.ghosts.of.history.common.helpers.DisplayRotationHelper
import com.ghosts.of.history.model.AnchorData
import com.ghosts.of.history.model.GeoPosition
import com.ghosts.of.history.utils.fetchImageFromStorage
import com.ghosts.of.history.utils.fetchVideoThumbnailFromStorage
import com.ghosts.of.history.utils.uploadImageToStorage
import com.ghosts.of.history.utils.uploadVideoThumbnailToStorage
import com.ghosts.of.history.utils.uploadVideoToStorage
import com.ghosts.of.history.viewmodel.EditAnchorActivityViewModel
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch


/**
 * This activity is used for creating a new anchor but also for editing existing ones,
 * if a corresponding parameter is passed through the intent
 */
class EditAnchorActivity : AppCompatActivity() {
    /**
     * The anchor we are editing, or `null` if we are creating a new one.
     */
    private var editAnchorData: AnchorData? = null
    private lateinit var displayRotationHelper: DisplayRotationHelper

    private var newSelectedImage: Uri? = null
    private var newSelectedVideo: Uri? = null
    private var newSelectedVideoThumbnail: Bitmap? = null
    private var newScanAnchorData: Intent? = null

    private val viewModel: EditAnchorActivityViewModel by viewModels {
        EditAnchorActivityViewModel.Factory
    }

    private lateinit var imageView: ImageView
    private lateinit var videoPreview: ImageView
    private lateinit var nameEditText: EditText
    private lateinit var descriptionEditText: EditText
    private lateinit var startScanningButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.edit_anchor)
        imageView = findViewById(R.id.image_view)
        videoPreview = findViewById(R.id.video_preview)
        nameEditText = findViewById(R.id.name_input_text)
        descriptionEditText = findViewById(R.id.description_input_text)

        processEditOrCreateAnchorViews()

        displayRotationHelper = DisplayRotationHelper(this)
        startScanningButton = findViewById(R.id.start_scanning_button)
        startScanningButton.setOnClickListener { onStartScanning() }

        val btnPickImage = findViewById<MaterialCardView>(R.id.card_image_select)
        btnPickImage.setOnClickListener {
            checkAndPickMedia(IMAGE_PICK_CODE)
        }

        val btnPickVideo = findViewById<MaterialCardView>(R.id.card_video_select)
        btnPickVideo.setOnClickListener {
            checkAndPickMedia(VIDEO_PICK_CODE)
        }

        val saveButton = findViewById<Button>(R.id.save_button)
        saveButton.setOnClickListener {
            saveAnchorWithProgressBar()
        }

        val topAppBar = findViewById<MaterialToolbar>(R.id.topAppBar)
        topAppBar.setNavigationOnClickListener { finish() }

        supportActionBar?.hide()
    }

    private fun processEditOrCreateAnchorViews() {
        val editAnchorId = intent.extras?.getString(PARAM_EDIT_ANCHOR_ID)
        val deleteCancelButton = findViewById<Button>(R.id.delete_cancel_button)
        if (editAnchorId == null) {
            deleteCancelButton.setText(R.string.cancel)
            deleteCancelButton.setOnClickListener { finish() }
        } else {
            editAnchorData = viewModel.anchorsDataState.value.anchorsData[editAnchorId]?.also {
                showExistingAnchorData(it)
            }
            val hostTitle = findViewById<MaterialToolbar>(R.id.topAppBar)
            hostTitle.setTitle(R.string.edit_exhibit)

            deleteCancelButton.setOnClickListener { deleteAnchor() }
        }
    }

    private fun showExistingAnchorData(existingAnchorData: AnchorData) {
        nameEditText.setText(existingAnchorData.name)
        descriptionEditText.setText(existingAnchorData.description)

        existingAnchorData.imageName?.let { imageName ->
            lifecycleScope.launch {
                fetchImageFromStorage(imageName, this@EditAnchorActivity).onSuccess { bitmap ->
                    imageView.setImageBitmap(bitmap)
                }
            }
        }

        existingAnchorData.videoName.let { videoName ->
            lifecycleScope.launch {
                fetchVideoThumbnailFromStorage(videoName, this@EditAnchorActivity).onSuccess { bitmap ->
                    videoPreview.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun checkAndPickMedia(pickCode: Int) {
        //check runtime permission
        if (android.os.Build.VERSION.SDK_INT < 33) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_DENIED
            ) {
                //permission denied
                val permissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                //show popup to request runtime permission
                requestPermissions(permissions, PERMISSION_CODE)
            } else {
                //permission already granted
                pickMediaFromGallery(pickCode)
            }
        } else {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) ==
                PackageManager.PERMISSION_DENIED ||
                checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) ==
                PackageManager.PERMISSION_DENIED
            ) {
                //permission denied

                val permissions = arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
                )
                //show popup to request runtime permission
                requestPermissions(permissions, PERMISSION_CODE)
            } else {
                //permission already granted
                pickMediaFromGallery(pickCode)
            }
        }
    }

    private fun pickMediaFromGallery(pickCode: Int) {
        val intent = Intent(Intent.ACTION_PICK)
        intent.type = if (pickCode == IMAGE_PICK_CODE) "image/*" else "video/*"
        @Suppress("DEPRECATION")
        startActivityForResult(intent, pickCode)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    //permission from popup granted
                    pickMediaFromGallery(IMAGE_PICK_CODE)
                    pickMediaFromGallery(VIDEO_PICK_CODE)
                } else {
                    //permission from popup denied
                    Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK) {
            when (requestCode) {
                IMAGE_PICK_CODE -> {
                    imageView.setImageURI(data?.data)
                    newSelectedImage = data?.data
                }

                VIDEO_PICK_CODE -> {
                    val videoUri = data?.data ?: return
                    newSelectedVideo = videoUri

                    val metadataRetriever = MediaMetadataRetriever()
                    metadataRetriever.setDataSource(this@EditAnchorActivity, videoUri)
                    newSelectedVideoThumbnail = metadataRetriever.frameAtTime

                    videoPreview.setImageBitmap(newSelectedVideoThumbnail)
                }

                SCAN_ANCHOR_CODE -> {
                    if (data == null) {
                        return
                    }
                    newScanAnchorData = data
                    startScanningButton.setText(R.string.scan_successful)
                }
            }
        }
    }

    private fun saveAnchorWithProgressBar() = lifecycleScope.launch {
        val newAnchorId = newScanAnchorData?.getStringExtra("anchorId")

        val name = nameEditText.text.toString()
        val description = descriptionEditText.text.toString()

        if (name.isBlank()) {
            Toast.makeText(this@EditAnchorActivity, "Please enter a name", Toast.LENGTH_SHORT).show()
            return@launch
        }

        val newImageName = newSelectedImage?.lastPathSegment

        val newVideoName = newSelectedVideo?.lastPathSegment
        val videoName = newVideoName ?: editAnchorData?.videoName

        if (videoName.isNullOrBlank()) {
            Toast.makeText(this@EditAnchorActivity, "Please upload a video", Toast.LENGTH_SHORT).show()
            return@launch
        }

        val newGeoPosition = newScanAnchorData?.let { scanAnchorData ->
            if (scanAnchorData.hasExtra("latitude") && scanAnchorData.hasExtra("longitude")) {
                val latitude = scanAnchorData.getDoubleExtra("latitude", 0.0)
                val longitude = scanAnchorData.getDoubleExtra("longitude", 0.0)
                GeoPosition(latitude, longitude)
            } else {
                null
            }
        }
        val geoPosition = newGeoPosition ?: editAnchorData?.geoPosition
        if (geoPosition == null) {
            Toast.makeText(this@EditAnchorActivity, "Please scan a location", Toast.LENGTH_SHORT).show()
            return@launch
        }

        displayProgressBar()

        newSelectedImage?.let { uploadImageToStorage(this@EditAnchorActivity, it) }
        newSelectedVideo?.let {
            val progressText = findViewById<TextView>(R.id.progress_text)
            progressText.text = "Uploading video…"
            uploadVideoToStorage(it)
            progressText.text = "Saving…"
        }
        newSelectedVideoThumbnail?.let { uploadVideoThumbnailToStorage(it, videoName) }

        val anchorData = AnchorData(
            newAnchorId ?: checkNotNull(editAnchorData?.anchorId),
            name,
            description,
            newImageName ?: editAnchorData?.imageName,
            videoName,
            editAnchorData?.isEnabled ?: true,
            editAnchorData?.scalingFactor ?: 1.0f,
            geoPosition,
            editAnchorData?.videoParams,
        )

        viewModel.saveAnchorData(anchorData)

        editAnchorData?.let { previousAnchorData ->
            if (newVideoName != null && newVideoName != previousAnchorData.videoName) {
                viewModel.removeVideo(previousAnchorData)
            }
            if (newGeoPosition != null) {
                // We have changed the anchor id, so we have to delete the previous one
                viewModel.removeAnchorData(previousAnchorData, removeVideo = false)
            }
        }

        finish()
    }

    private fun displayProgressBar() {
        val restOfTheView = findViewById<View>(R.id.edit_anchor_content)
        restOfTheView.visibility = View.GONE
        val progressBar = findViewById<View>(R.id.save_progress_bar)
        progressBar.visibility = View.VISIBLE
    }

    private fun deleteAnchor() = lifecycleScope.launch {
        editAnchorData?.let { anchor ->
            viewModel.removeAnchorData(anchor, removeVideo = true)
            Toast.makeText(applicationContext, "Deleted", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        displayRotationHelper.onResume()
    }

    public override fun onPause() {
        super.onPause()
        displayRotationHelper.onPause()
    }

    private fun onStartScanning() {
        val intent: Intent = CloudAnchorActivity.newHostingIntent(this)

        startActivityForResult(intent, SCAN_ANCHOR_CODE)
    }

    companion object {
        const val PARAM_EDIT_ANCHOR_ID = "PARAM_EDIT_ANCHOR_ID"

        private const val IMAGE_PICK_CODE = 1000
        private const val VIDEO_PICK_CODE = 1001
        private const val PERMISSION_CODE = 1002
        private const val SCAN_ANCHOR_CODE = 2228
    }
}
