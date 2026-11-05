package tools.supernote.scan

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

@Composable
internal fun ZoomablePageImage(bitmap: Bitmap?, modifier: Modifier = Modifier) {
    var zoom by remember(bitmap) { mutableFloatStateOf(1f) }
    var pan by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var viewport by remember(bitmap) { mutableStateOf(IntSize.Zero) }
    val imageBitmap = remember(bitmap) { bitmap?.asImageBitmap() }

    fun setZoom(requested: Float) {
        val next = requested.coerceIn(1f, 5f)
        zoom = next
        if (next == 1f || bitmap == null || viewport == IntSize.Zero) {
            pan = Offset.Zero
        } else {
            val fit = min(
                viewport.width.toFloat() / bitmap.width,
                viewport.height.toFloat() / bitmap.height,
            )
            val maxX = max(0f, (bitmap.width * fit * next - viewport.width) / 2f)
            val maxY = max(0f, (bitmap.height * fit * next - viewport.height) / 2f)
            pan = Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, maxY))
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .onSizeChanged { viewport = it }
                .pointerInput(bitmap) {
                    detectTapGestures(onDoubleTap = { setZoom(1f) })
                }
                .pointerInput(bitmap, viewport) {
                    detectTransformGestures { centroid, panChange, zoomChange, _ ->
                        val image = bitmap ?: return@detectTransformGestures
                        val previous = zoom
                        val next = (previous * zoomChange).coerceIn(1f, 5f)
                        if (next == 1f) {
                            zoom = 1f
                            pan = Offset.Zero
                        } else {
                            val fit = if (viewport == IntSize.Zero) 0f else min(
                                viewport.width.toFloat() / image.width,
                                viewport.height.toFloat() / image.height,
                            )
                            val focalPoint = centroid - Offset(viewport.width / 2f, viewport.height / 2f)
                            val ratio = next / previous
                            val maxX = max(0f, (image.width * fit * next - viewport.width) / 2f)
                            val maxY = max(0f, (image.height * fit * next - viewport.height) / 2f)
                            val moved = pan * ratio + panChange + focalPoint * (1f - ratio)
                            zoom = next
                            pan = Offset(moved.x.coerceIn(-maxX, maxX), moved.y.coerceIn(-maxY, maxY))
                        }
                    }
                }
                .semantics {
                    contentDescription = "Page image. Pinch to zoom, drag to pan, double tap to reset."
                },
            contentAlignment = Alignment.Center,
        ) {
            if (imageBitmap != null) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = "Captured page image",
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = pan.x
                        translationY = pan.y
                    },
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text("Loading page…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { setZoom(zoom / 1.25f) },
                enabled = bitmap != null && zoom > 1f,
                modifier = Modifier.size(width = 52.dp, height = 48.dp)
                    .semantics { contentDescription = "Zoom out" }.testTag("preview_zoom_out"),
            ) { Text("−", style = MaterialTheme.typography.titleLarge) }
            OutlinedButton(
                onClick = { setZoom(zoom * 1.25f) },
                enabled = bitmap != null && zoom < 5f,
                modifier = Modifier.size(width = 52.dp, height = 48.dp)
                    .semantics { contentDescription = "Zoom in" }.testTag("preview_zoom_in"),
            ) { Text("+", style = MaterialTheme.typography.titleLarge) }
            OutlinedButton(
                onClick = { setZoom(1f) },
                enabled = bitmap != null && zoom != 1f,
                modifier = Modifier.height(48.dp).semantics { contentDescription = "Reset page zoom" }
                    .testTag("preview_zoom_reset"),
            ) { Text("Fit") }
            Text(
                "${(zoom * 100).toInt()}%",
                Modifier.weight(1f).padding(end = 2.dp).semantics {
                    contentDescription = "Page zoom ${(zoom * 100).toInt()} percent"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
    }
}
