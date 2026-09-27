/*
 * ShootOFF - Software for Laser Dry Fire Training
 * Copyright (C) 2016 phrack
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.shootoff.compose.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shootoff.compose.theme.NumberStyle
import com.shootoff.compose.theme.Range

enum class CalibrationStatus(val label: String) {
    NO_ARENA("no arena"),
    NEEDS_CALIBRATION("needs calibration"),
    CALIBRATING("calibrating"),
    CALIBRATED("calibrated"),
}

/**
 * What the status strip says about a feed.
 *
 * @param cameraFps the camera's own frame rate
 * @param shownFps how many frames the Compose app drew in the last second
 */
data class FeedStatus(
    val camera: String,
    val cameraFps: Double,
    val shownFps: Double,
    val width: Int,
    val height: Int,
    val calibration: CalibrationStatus,
    val recording: Boolean,
) {
    fun text(): String {
        val parts = mutableListOf(
            camera,
            "%.0f FPS".format(cameraFps),
            "shown %.0f".format(shownFps),
            "${width}×$height",
            calibration.label,
        )
        if (recording) parts += "● REC"
        return parts.joinToString(" · ")
    }
}

@Composable
fun StatusStrip(status: FeedStatus, modifier: Modifier = Modifier) {
    val colors = Range.colors
    val text = buildAnnotatedString {
        append(status.camera)
        append(" · ")
        withStyle(SpanStyle(color = if (status.cameraFps >= 20) colors.good else colors.warning)) {
            append("%.0f FPS".format(status.cameraFps))
        }
        append(" · shown %.0f · ${status.width}×${status.height} · ".format(status.shownFps))
        withStyle(SpanStyle(color = if (status.calibration == CalibrationStatus.CALIBRATED) colors.good else colors.muted)) {
            append(status.calibration.label)
        }
        if (status.recording) {
            append(" · ")
            withStyle(SpanStyle(color = colors.error)) { append("● REC") }
        }
    }
    Text(
        text,
        style = NumberStyle.copy(fontSize = 11.sp),
        color = colors.muted,
        modifier = modifier
            .background(colors.background.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .testTag("status-strip"),
    )
}
