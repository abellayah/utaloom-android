/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

private val AmbientSaturate = ColorFilter.colorMatrix(
    ColorMatrix().apply { setToSaturation(1.4f) }
)

/**
 * Ultra-lightweight iOS Apple Music style ambient background.
 * Uses hardware bilinear upscaling on a small 16px bitmap to create smooth color gradients
 * with ZERO GPU BlurEffect passes and ZERO per-frame redraw loops.
 * Keeps CPU and GPU idle, completely eliminating stutter and thermal throttling.
 */
@Composable
fun AppleMeshBackground(
    thumbnailUrl: String?,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    AnimatedContent<String?>(
        targetState = thumbnailUrl,
        transitionSpec = {
            fadeIn(tween(750)).togetherWith(fadeOut(tween(750)))
        },
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0C0D11)),
        label = "appleMeshBackground",
    ) { url ->
        Box(Modifier.fillMaxSize()) {
            if (url != null) {
                val request = remember<ImageRequest>(url) {
                    ImageRequest.Builder(context)
                        .data(url)
                        .size(16, 16)
                        .allowHardware(true)
                        .build()
                }

                // Base ambient color wash via ultra-fast bilinear upscaling
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.Low,
                    colorFilter = AmbientSaturate,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = 1.35f
                            scaleY = 1.35f
                            alpha = 0.65f
                        },
                )

                // Vignette gradient to guarantee high text contrast and Apple Music depth
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.0f to Color(0x66000000),
                                    0.45f to Color(0x33000000),
                                    0.85f to Color(0xCC0C0D11),
                                    1.0f to Color(0xFF0C0D11),
                                )
                            )
                        )
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0E0E12))
                )
            }
        }
    }
}
