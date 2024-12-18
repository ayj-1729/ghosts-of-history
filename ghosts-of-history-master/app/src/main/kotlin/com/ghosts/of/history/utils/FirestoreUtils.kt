package com.ghosts.of.history.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.core.net.toUri
import com.ghosts.of.history.model.AnchorData
import com.ghosts.of.history.model.Color
import com.ghosts.of.history.model.GeoPosition
import com.ghosts.of.history.model.VideoParams
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.ktx.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.net.URL
import kotlin.math.ceil
import kotlin.math.max


private const val firebaseCollection = "AnchorBindings"

suspend fun saveAnchorSetToFirebase(anchor: AnchorData) {
    val videoParams = anchor.videoParams?.let {
        arrayOf(
            it.greenScreenColor.red,
            it.greenScreenColor.green,
            it.greenScreenColor.blue,
            it.chromakeyThreshold
        )
    }
    val document = mapOf(
        "id" to anchor.anchorId,
        "name" to anchor.name,
        "description" to anchor.description,
        "image_name" to anchor.imageName,
        "video_name" to anchor.videoName,
        "enabled" to anchor.isEnabled,
        "scaling_factor" to anchor.scalingFactor,
        "latitude" to anchor.geoPosition?.latitude,
        "longitude" to anchor.geoPosition?.longitude,
        "video_params" to videoParams?.toList(),
    )
    Firebase.firestore.collection(firebaseCollection).document(anchor.anchorId).set(document).await()
}

suspend fun removeAnchorDataFromFirebase(anchorData: AnchorData) {
    Firebase.firestore.collection(firebaseCollection)
        .document(anchorData.anchorId).delete().await()
}

suspend fun getAnchorsDataFromFirebase(): List<AnchorData> =
    Firebase.firestore.collection(firebaseCollection).whereNotEqualTo("video_name", "").get().await()
        .map {
            val latitude = it.get("latitude")
            val longitude = it.get("longitude")
            val geoPosition = if (latitude != null && longitude != null) {
                GeoPosition((latitude as Number).toDouble(), (longitude as Number).toDouble())
            } else {
                null
            }
            val enabled = it.get("enabled") as Boolean? ?: false
            val videoParams = it.get("video_params")?.let { array ->
                val arr = array as ArrayList<*>
                VideoParams(
                    Color(
                        (arr[0] as Number).toFloat(),
                        (arr[1] as Number).toFloat(),
                        (arr[2] as Number).toFloat()
                    ),
                    (arr[3] as Number).toFloat(),
                    false
                )
            }
            println(it.get("description"))
            AnchorData(
                anchorId = it.get("id") as String,
                name = it.get("name") as String,
                description = it.get("description") as String?,
                imageName = it.get("image_name") as String?,
                videoName = it.get("video_name") as String,
                isEnabled = enabled,
                scalingFactor = (it.get("scaling_factor") as Number).toFloat(),
                geoPosition = geoPosition,
                videoParams = videoParams,
            )
        }

suspend fun getVideoDownloadUri(videoName: String) = getFileURL("videos/$videoName")?.toUri()

suspend fun fetchImageFromStorage(imageName: String, context: Context): Result<Bitmap> =
    fetchBitmapFromStorage("images/$imageName", context)

suspend fun fetchVideoThumbnailFromStorage(videoName: String, context: Context): Result<Bitmap> =
    fetchBitmapFromStorage("video_thumbnails/$videoName", context)

suspend fun getFileURL(path: String): String? =
    try {
        Firebase.storage.reference.child(path).downloadUrl.await().toString()
    } catch (e: StorageException) {
        null
    }

suspend fun fetchBitmapFromStorage(path: String, context: Context): Result<Bitmap> = runCatching {
    println("FirestoreUtils: fetching $path with context $context")
    val fileUrl = getFileURL(path)
        ?: return Result.failure(FileNotFoundException("File URL for path $path is null!"))
    val url = URL(fileUrl)
    withContext(Dispatchers.IO) {
        val connection = url.openConnection()
        connection.connect()
        val stream = connection.getInputStream()
        BitmapFactory.decodeStream(stream)
    }
}

suspend fun uploadImageToStorage(context: Context, uri: Uri): Unit = withContext(Dispatchers.IO) {
    val downsizedImageBytes: ByteArray = getDownsizedImageBytes(context, uri)
    Firebase.storage.reference.child("images/${uri.lastPathSegment}")
        .putBytes(downsizedImageBytes)
        .await()
}

private fun getDownsizedImageBytes(context: Context, uri: Uri): ByteArray {
    val fullBitmap = MediaStore.Images.Media.getBitmap(context.contentResolver, uri)

    val targetMaxSide = 500
    val actualMaxSide = max(fullBitmap.width, fullBitmap.height)
    val scaleFactor = (targetMaxSide.toFloat() / actualMaxSide).coerceAtMost(1f)

    val scaleWidth: Int = ceil(fullBitmap.width * scaleFactor).toInt().coerceAtLeast(1)
    val scaleHeight: Int = ceil(fullBitmap.height * scaleFactor).toInt().coerceAtLeast(1)

    val scaledBitmap = Bitmap.createScaledBitmap(fullBitmap, scaleWidth, scaleHeight, true)
    val scaledAndRotatedBitmap = rotateImageIfRequired(context, scaledBitmap, uri)

    val byteArrayOutputStream = ByteArrayOutputStream()
    scaledAndRotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 75, byteArrayOutputStream)
    return byteArrayOutputStream.toByteArray()
}

private fun rotateImageIfRequired(context: Context, img: Bitmap, selectedImage: Uri): Bitmap {
    val orientation = context.contentResolver.openInputStream(selectedImage)!!.use {
        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }

    return when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> rotateImage(img, 90)
        ExifInterface.ORIENTATION_ROTATE_180 -> rotateImage(img, 180)
        ExifInterface.ORIENTATION_ROTATE_270 -> rotateImage(img, 270)
        else -> img
    }
}

private fun rotateImage(img: Bitmap, degree: Int): Bitmap {
    val matrix = Matrix()
    matrix.postRotate(degree.toFloat())
    return Bitmap.createBitmap(img, 0, 0, img.width, img.height, matrix, true)
}

suspend fun uploadVideoToStorage(uri: Uri) {
    Firebase.storage.reference.child("videos/${uri.lastPathSegment}").putFile(uri).await()
}

suspend fun uploadVideoThumbnailToStorage(bitmap: Bitmap, videoName: String): Unit = withContext(Dispatchers.IO) {
    val byteArrayOutputStream = ByteArrayOutputStream()
    val compressionQuality = 75
    bitmap.compress(Bitmap.CompressFormat.JPEG, compressionQuality, byteArrayOutputStream)

    Firebase.storage.reference.child("video_thumbnails/$videoName")
        .putBytes(byteArrayOutputStream.toByteArray()).await()
}

suspend fun removeVideoFromStorage(anchorData: AnchorData) {
    anchorData.videoName.takeIf { it.isNotEmpty() }?.let { videoName ->
        try {
            Firebase.storage.reference.child("videos/$videoName").delete().await()
        } catch (e: StorageException) {
            Log.e("FirestoreUtils", "Exception on deleting video", e)
        }
    }
}
