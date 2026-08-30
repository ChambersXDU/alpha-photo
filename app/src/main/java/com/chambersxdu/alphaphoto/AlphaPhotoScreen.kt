package com.chambersxdu.alphaphoto

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal enum class CameraConnectionState {
    UNASSOCIATED,
    OFFLINE,
    AVAILABLE,
    CONNECTING,
    CONNECTED,
    ERROR,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlphaPhotoScreen(
    model: CameraModel,
    connectionState: CameraConnectionState,
    status: String,
    photos: List<PtpObjectInfo>,
    thumbnailStore: CameraThumbnailStore,
    snackbarHostState: SnackbarHostState,
    exportingHandle: Int?,
    onConnect: () -> Unit,
    onRefreshPhotos: () -> Unit,
    onExport: (PtpObjectInfo) -> Unit,
) {
    val shots = remember(photos) { CameraPhotoCatalog.group(photos) }
    var selectedShot by remember { mutableStateOf<CameraShot?>(null) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val drawerHeight = maxHeight * 0.92f
        val peekHeight = maxHeight * if (shots.isEmpty()) 0.21f else 0.57f
        val scaffoldState = rememberBottomSheetScaffoldState(
            snackbarHostState = snackbarHostState,
        )
        val drawerExpanded =
            scaffoldState.bottomSheetState.targetValue == SheetValue.Expanded

        BottomSheetScaffold(
            modifier = Modifier.fillMaxSize(),
            scaffoldState = scaffoldState,
            sheetPeekHeight = peekHeight,
            sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            sheetContainerColor = AlphaSurface,
            sheetShadowElevation = 10.dp,
            sheetDragHandle = null,
            sheetContent = {
                PhotoDrawer(
                    modifier = Modifier.height(drawerHeight),
                    shots = shots,
                    fileCount = photos.size,
                    expanded = drawerExpanded,
                    thumbnailStore = thumbnailStore,
                    canRefresh = connectionState == CameraConnectionState.CONNECTED,
                    onPhotoClick = { shot -> selectedShot = shot },
                    onRefreshPhotos = onRefreshPhotos,
                )
            },
            snackbarHost = { hostState ->
                SnackbarHost(
                    hostState = hostState,
                    modifier = Modifier.navigationBarsPadding(),
                )
            },
        ) { contentPadding ->
            CameraHero(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                model = model,
                connectionState = connectionState,
                status = status,
                onConnect = onConnect,
            )
        }
    }

    selectedShot?.let { shot ->
        PhotoDetailSheet(
            shot = shot,
            thumbnailStore = thumbnailStore,
            exportingHandle = exportingHandle,
            onDismiss = { selectedShot = null },
            onExport = onExport,
        )
    }
}

@Composable
private fun CameraHero(
    modifier: Modifier,
    model: CameraModel,
    connectionState: CameraConnectionState,
    status: String,
    onConnect: () -> Unit,
) {
    val productName = stringResource(model.productName)

    Box(
        modifier = modifier.background(
            Brush.verticalGradient(
                listOf(Color(0xFFF8F8F6), AlphaBackground),
            ),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 24.dp, vertical = 18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.brand_name),
                    style = MaterialTheme.typography.labelMedium,
                    letterSpacing = MaterialTheme.typography.labelMedium.letterSpacing * 2,
                    color = AlphaInk,
                )
                CameraStatusPill(
                    model = model,
                    connectionState = connectionState,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = productName,
                        style = MaterialTheme.typography.displaySmall,
                        color = AlphaInk,
                    )
                    Text(
                        text = model.associationName,
                        style = MaterialTheme.typography.labelMedium,
                        color = AlphaMuted,
                    )
                }
                Image(
                    painter = painterResource(model.heroImage),
                    contentDescription = productName,
                    modifier = Modifier.size(width = 72.dp, height = 52.dp),
                    contentScale = ContentScale.Fit,
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp)
                    .animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AlphaMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                AnimatedVisibility(
                    visible = connectionState != CameraConnectionState.CONNECTED,
                ) {
                    Button(
                        onClick = onConnect,
                        enabled = connectionState != CameraConnectionState.CONNECTING,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AlphaInk,
                            contentColor = Color.White,
                        ),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
                    ) {
                        Text(
                            text = when (connectionState) {
                                CameraConnectionState.UNASSOCIATED ->
                                    stringResource(R.string.action_associate_camera)
                                CameraConnectionState.CONNECTING ->
                                    stringResource(R.string.action_connecting)
                                CameraConnectionState.ERROR ->
                                    stringResource(R.string.action_reconnect)
                                else -> stringResource(R.string.action_connect_camera)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraStatusPill(
    model: CameraModel,
    connectionState: CameraConnectionState,
) {
    val connected = connectionState == CameraConnectionState.CONNECTED
    val label = when (connectionState) {
        CameraConnectionState.CONNECTED ->
            stringResource(R.string.connection_connected, model.shortName)
        CameraConnectionState.CONNECTING -> stringResource(R.string.action_connecting)
        CameraConnectionState.AVAILABLE -> stringResource(R.string.connection_found)
        CameraConnectionState.UNASSOCIATED -> stringResource(R.string.connection_unassociated)
        CameraConnectionState.ERROR -> stringResource(R.string.connection_interrupted)
        CameraConnectionState.OFFLINE -> stringResource(R.string.connection_waiting_for_camera)
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(Color.White.copy(alpha = 0.86f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(
                    color = if (connected) AlphaConnected else AlphaMuted,
                    shape = CircleShape,
                ),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = AlphaInk,
        )
    }
}

@Composable
private fun PhotoDrawer(
    modifier: Modifier,
    shots: List<CameraShot>,
    fileCount: Int,
    expanded: Boolean,
    thumbnailStore: CameraThumbnailStore,
    canRefresh: Boolean,
    onPhotoClick: (CameraShot) -> Unit,
    onRefreshPhotos: () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .padding(top = 10.dp)
                .size(width = 38.dp, height = 4.dp)
                .clip(CircleShape)
                .background(AlphaLine)
                .align(Alignment.CenterHorizontally),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Text(
                    text = stringResource(R.string.recent_photos),
                    style = MaterialTheme.typography.titleLarge,
                    color = AlphaInk,
                )
                Text(
                    text = if (fileCount == 0) {
                        stringResource(R.string.photos_loaded_hint)
                    } else {
                        stringResource(R.string.photo_counts, shots.size, fileCount)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = AlphaMuted,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (expanded) {
                        stringResource(R.string.collapse_drawer)
                    } else {
                        stringResource(R.string.expand_drawer)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = AlphaMuted,
                )
                if (canRefresh) {
                    TextButton(
                        onClick = onRefreshPhotos,
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(stringResource(R.string.action_refresh))
                    }
                }
            }
        }

        HorizontalDivider(color = AlphaLine)

        val visibleShots = if (expanded) shots else shots.take(8)

        if (shots.isEmpty()) {
            EmptyPhotoDrawer()
        } else {
            LazyVerticalGrid(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
                columns = GridCells.Fixed(3),
                userScrollEnabled = expanded,
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                gridItems(
                    items = visibleShots,
                    key = { shot -> shot.previewFile.handle },
                ) { shot ->
                    PhotoGridTile(
                        shot = shot,
                        thumbnailStore = thumbnailStore,
                        onClick = { onPhotoClick(shot) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyPhotoDrawer() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(5) {
            Box(
                modifier = Modifier
                    .size(62.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFF0F0ED)),
            )
        }
    }
}

@Composable
private fun PhotoGridTile(
    shot: CameraShot,
    thumbnailStore: CameraThumbnailStore,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        CameraThumbnail(
            modifier = Modifier.fillMaxSize(),
            photo = shot.previewFile,
            thumbnailStore = thumbnailStore,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.28f)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f)),
                    ),
                ),
        )
        Text(
            text = shot.displayName,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(9.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            maxLines = 1,
        )
        FormatBadge(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(7.dp),
            shot = shot,
        )
    }
}

@Composable
private fun FormatBadge(
    modifier: Modifier,
    shot: CameraShot,
) {
    Text(
        text = shot.files.joinToString("+") { file ->
            file.filename.substringAfterLast('.').uppercase()
        },
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(Color.Black.copy(alpha = 0.68f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
    )
}

@Composable
private fun CameraThumbnail(
    modifier: Modifier,
    photo: PtpObjectInfo,
    thumbnailStore: CameraThumbnailStore,
) {
    val state = thumbnailStore.stateFor(photo.handle)
    val image = thumbnailStore.imageFor(photo.handle)

    LaunchedEffect(photo.handle, state, image) {
        if (image == null && state != ThumbnailLoadState.FAILED) {
            thumbnailStore.load(photo)
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFE9E9E5)),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = photo.filename,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else if (state == ThumbnailLoadState.LOADING) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = AlphaMuted,
                strokeWidth = 2.dp,
            )
        } else {
            Text(
                text = photo.filename.substringAfterLast('.').uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = AlphaMuted,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoDetailSheet(
    shot: CameraShot,
    thumbnailStore: CameraThumbnailStore,
    exportingHandle: Int?,
    onDismiss: () -> Unit,
    onExport: (PtpObjectInfo) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = AlphaSurface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .size(width = 38.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(AlphaLine),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 2f)
                    .clip(RoundedCornerShape(18.dp)),
            ) {
                CameraThumbnail(
                    modifier = Modifier.fillMaxSize(),
                    photo = shot.previewFile,
                    thumbnailStore = thumbnailStore,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = shot.displayName,
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        text = formatCaptureDate(shot.captureDate),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AlphaMuted,
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_done))
                }
            }

            Text(
                text = stringResource(R.string.original_export_explanation),
                style = MaterialTheme.typography.bodyMedium,
                color = AlphaMuted,
            )

            shot.files.forEach { file ->
                val exporting = exportingHandle == file.handle
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = exportingHandle == null,
                    onClick = { onExport(file) },
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(horizontalAlignment = Alignment.Start) {
                            Text(
                                text = if (exporting) {
                                    stringResource(R.string.exporting_file, file.filename)
                                } else {
                                    stringResource(
                                        R.string.export_original_file,
                                        file.filename.substringAfterLast('.').uppercase(),
                                    )
                                },
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                text = "${file.width} × ${file.height} · ${formatBytes(file.size)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = AlphaMuted,
                            )
                        }
                        if (exporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatCaptureDate(value: String): String {
    if (value.length < 15) {
        return value
    }
    return value.substring(0, 4) + "." +
        value.substring(4, 6) + "." +
        value.substring(6, 8) + "  " +
        value.substring(9, 11) + ":" +
        value.substring(11, 13)
}

private fun formatBytes(bytes: Long): String {
    val mebibytes = bytes.toDouble() / (1024.0 * 1024.0)
    return if (mebibytes >= 10) {
        "%.1f MB".format(mebibytes)
    } else {
        "%.2f MB".format(mebibytes)
    }
}
