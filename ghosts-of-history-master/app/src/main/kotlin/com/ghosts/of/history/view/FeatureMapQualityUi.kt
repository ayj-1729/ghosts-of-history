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
 */
package com.ghosts.of.history.view

import com.ghosts.of.history.common.rendering.ObjectRenderer
import com.google.ar.core.Pose
import com.google.ar.core.Session.FeatureMapQuality
import kotlin.math.*

/** Helper class to display the Feature Map Quality UI for the Persistent Cloud Anchor Sample.  */
internal class FeatureMapQualityUi private constructor(private val isHorizontal: Boolean,
                                                       var objectRenderer: ObjectRenderer) {
    private val numBars = (Math.PI / MAPPING_UI_SPACING_RADIANS).roundToInt()
    private val bars: Array<QualityBar>
    val radius = MAPPING_UI_RADIUS

    internal enum class Quality {
        UNKNOWN, INSUFFICIENT, SUFFICIENT, GOOD
    }

    internal inner class QualityBar(rad: Double) {
        private val localPose = computeLocalPose(rad)
        private val modelMatrix = FloatArray(16)
        var quality = Quality.UNKNOWN

        private fun computeLocalPose(rad: Double): Pose {
            // Rotate around y axis
            val rotation = floatArrayOf(0f, sin(rad / 2.0).toFloat(), 0f, cos(rad / 2.0).toFloat(), 0f)
            val translation = floatArrayOf(radius, 0f, 0f)
            return Pose.makeRotation(rotation).compose(Pose.makeTranslation(translation))
        }

        fun updateQuality(quality: FeatureMapQuality) {
            this.quality = when (quality) {
                FeatureMapQuality.INSUFFICIENT -> Quality.INSUFFICIENT
                FeatureMapQuality.SUFFICIENT -> Quality.SUFFICIENT
                else -> Quality.GOOD
            }
        }

        fun draw(uiPose: Pose,
                 viewMatrix: FloatArray?,
                 projectionMatrix: FloatArray?,
                 colorCorrectionRgba: FloatArray?) {
            uiPose.compose(localPose).toMatrix(modelMatrix, 0)
            objectRenderer.updateModelMatrix(modelMatrix, BAR_SCALE)
            val barColor = when (quality) {
                Quality.UNKNOWN -> BAR_COLOR_UNKNOWN_QUALITY
                Quality.INSUFFICIENT -> BAR_COLOR_LOW_QUALITY
                Quality.SUFFICIENT -> BAR_COLOR_MEDIUM_QUALITY
                else -> BAR_COLOR_HIGH_QUALITY
            }
            objectRenderer.draw(viewMatrix, projectionMatrix, colorCorrectionRgba, barColor)
        }
    }

    init {
        bars = Array(numBars) { QualityBar(Math.PI / numBars.toDouble() * it) }
    }

    val uiTransform: Pose
        get() = if (isHorizontal) HORIZONTAL_UI_TRANSFORM else VERTICAL_UI_TRANSFORM

    // Average quality value computed over all bars (0.0 for INSUFFICIENT, 0.6 for
    // SUFFICIENT, and 1.0 for GOOD)
    fun computeOverallQuality(): Float {
        var sumQuality = 0f
        for (bar in bars) {
            if (bar.quality == Quality.SUFFICIENT) {
                sumQuality += 0.6f
            } else if (bar.quality == Quality.GOOD) {
                sumQuality += 1.0f
            }
        }
        return sumQuality / numBars
    }

    fun updateQualityForViewpoint(cameraPosition: FloatArray, quality: FeatureMapQuality) {
        val idx = computeBarIndex(cameraPosition)
        if (idx in 0 until numBars) {
            val barInView = bars[idx]
            barInView.updateQuality(quality)
        }
    }

    fun drawUi(anchorPose: Pose,
               viewMatrix: FloatArray?,
               projectionMatrix: FloatArray?,
               colorCorrectionRgba: FloatArray?) {
        val uiTransform = if (isHorizontal) HORIZONTAL_UI_TRANSFORM else VERTICAL_UI_TRANSFORM
        val featureMapQualityUIPose = anchorPose.compose(uiTransform)
        for (bar in bars) {
            bar.draw(featureMapQualityUIPose, viewMatrix, projectionMatrix, colorCorrectionRgba)
        }
    }

    companion object {
        private val TAG = FeatureMapQualityUi::class.java.simpleName
        private val ROTATION_QUATERNION_180_Y = floatArrayOf(0f, sin(Math.PI / 2.0).toFloat(), 0f, cos(Math.PI / 2.0).toFloat())
        private val ROTATION_QUATERNION_90_Y = floatArrayOf(0f, sin(-Math.PI / 4.0).toFloat(), 0f, cos(-Math.PI / 4.0).toFloat())
        private val ROTATION_QUATERNION_90_X = floatArrayOf(sin(Math.PI / 4.0).toFloat(), 0f, 0f, cos(Math.PI / 4.0).toFloat())

        // For anchors on horizontal planes. The UI coordinate frame is rotated 180 degrees with respect
        // to the anchor frame to simplify calculations. The positive x-axis points to the left of the
        // screen and the positive z-axis points away from the camera.
        private val HORIZONTAL_UI_TRANSFORM = Pose.makeRotation(ROTATION_QUATERNION_180_Y)

        // For anchors on vertical planes. The UI coordinate frame rotated with respect to the anchor
        // to face outwards toward the user such that the positive x-axis points to the left of the screen
        // and the positive z-axis points away from the camera into the plane.
        private val VERTICAL_UI_TRANSFORM = Pose.makeRotation(ROTATION_QUATERNION_90_Y)
                .compose(Pose.makeRotation(ROTATION_QUATERNION_90_X))

        // Spacing between indicator bars.
        private val MAPPING_UI_SPACING_RADIANS = Math.toRadians(7.5)
        private const val MAPPING_UI_RADIUS = 0.2f
        private const val BAR_SCALE = 0.3f
        private val BAR_COLOR_UNKNOWN_QUALITY = floatArrayOf(218.0f, 220.0f, 240.0f, 255.0f)
        private val BAR_COLOR_LOW_QUALITY = floatArrayOf(234.0f, 67.0f, 53.0f, 255.0f)
        private val BAR_COLOR_MEDIUM_QUALITY = floatArrayOf(250.0f, 187.0f, 5.0f, 255.0f)
        private val BAR_COLOR_HIGH_QUALITY = floatArrayOf(52.0f, 168.0f, 82.0f, 255.0f)

        fun createHorizontalFeatureMapQualityUi(
                objectRenderer: ObjectRenderer): FeatureMapQualityUi {
            return FeatureMapQualityUi(true, objectRenderer)
        }

        fun createVerticalFeatureMapQualityUi(
                objectRenderer: ObjectRenderer): FeatureMapQualityUi {
            return FeatureMapQualityUi(false, objectRenderer)
        }

        private fun computeBarIndex(viewRay: FloatArray): Int {
            // positive indices.
            val rad = -atan2(viewRay[2].toDouble(), viewRay[0].toDouble())
            return floor(rad / MAPPING_UI_SPACING_RADIANS).toInt()
        }
    }
}