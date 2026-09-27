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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.shootoff.compose.theme.Range

/**
 * The feed's banners (camera warnings, calibration messages, the drill's messages), newest last. Each
 * can be dismissed.
 */
@Composable
fun FeedBanners(feed: FeedState, modifier: Modifier = Modifier) {
    val banners by feed.banners.collectAsState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        for (banner in banners) {
            BannerView(banner, onDismiss = { feed.removeBanner(banner) })
        }
    }
}

@Composable
fun BannerView(banner: Banner, onDismiss: () -> Unit) {
    val colors = Range.colors
    val accent = when (banner.kind) {
        BannerKind.WARNING -> colors.warning
        BannerKind.ERROR -> colors.error
        BannerKind.CALIBRATION -> colors.accent
        BannerKind.INFO -> colors.good
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = colors.card.copy(alpha = 0.92f),
        contentColor = colors.text,
        border = BorderStroke(1.dp, accent),
        modifier = Modifier.testTag("banner-${banner.id}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
            Text(banner.text, color = colors.text)
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp).testTag("dismiss-${banner.id}")) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = colors.muted)
            }
        }
    }
}
