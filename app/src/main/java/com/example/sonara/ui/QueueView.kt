package com.example.sonara.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.sonara.data.models.QueueTrack
import com.example.sonara.ui.theme.SpotifyGreen
import com.example.sonara.ui.theme.SpotifyLightGray
import kotlin.math.roundToInt

@Composable
fun QueueView(
    queueItems: List<QueueTrack>,
    currentIndex: Int,
    onDismiss: () -> Unit,
    onMoveItem: (Int, Int) -> Unit = { _, _ -> }
) {
    // Local mutable copy for visual reorder feedback
    val localItems = remember(queueItems) { queueItems.toMutableStateList() }

    // Drag state
    var draggedItemIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var currentOverIndex by remember { mutableIntStateOf(-1) }

    // Approximate item height for calculating target position
    val itemHeightPx = 72f // rough estimate in dp-equivalent pixels

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Queue",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            state = rememberLazyListState(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(localItems, key = { _, track -> track.videoId }) { index, track ->
                val isPlaying = index == currentIndex
                val isDragging = draggedItemIndex == index

                val elevation by animateDpAsState(
                    targetValue = if (isDragging) 8.dp else 0.dp,
                    label = "dragElevation"
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isDragging) {
                                Modifier.offset { IntOffset(0, dragOffsetY.roundToInt()) }
                            } else {
                                Modifier
                            }
                        )
                        .shadow(elevation)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Drag handle icon with long-press drag gesture
                    Icon(
                        imageVector = Icons.Default.DragHandle,
                        contentDescription = "Reorder",
                        tint = SpotifyLightGray,
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .pointerInput(index) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggedItemIndex = index
                                        dragOffsetY = 0f
                                        currentOverIndex = index
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragOffsetY += dragAmount.y

                                        // Calculate which index we're hovering over
                                        val rawTargetIndex =
                                            index + (dragOffsetY / itemHeightPx).roundToInt()
                                        val targetIndex =
                                            rawTargetIndex.coerceIn(0, localItems.size - 1)
                                        currentOverIndex = targetIndex
                                    },
                                    onDragEnd = {
                                        val fromIndex = draggedItemIndex
                                        val toIndex = currentOverIndex
                                        if (fromIndex != null && fromIndex != toIndex && toIndex in localItems.indices) {
                                            // Update local list for immediate visual feedback
                                            val item = localItems.removeAt(fromIndex)
                                            localItems.add(toIndex, item)
                                            // Notify parent to persist the reorder
                                            onMoveItem(fromIndex, toIndex)
                                        }
                                        draggedItemIndex = null
                                        dragOffsetY = 0f
                                        currentOverIndex = -1
                                    },
                                    onDragCancel = {
                                        draggedItemIndex = null
                                        dragOffsetY = 0f
                                        currentOverIndex = -1
                                    }
                                )
                            }
                    )

                    // Track info
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            color = if (isPlaying) SpotifyGreen else Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = track.artist,
                            color = SpotifyLightGray,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
