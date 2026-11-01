package com.zenith.auxcam.ui

import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.view.ViewTreeObserver
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.graphics.Insets
import android.net.Uri
import android.provider.MediaStore
import android.widget.VideoView
import android.util.Size
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import com.zenith.auxcam.ui.theme.CameraMotion
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import com.zenith.auxcam.ui.icons.NotiIcons
import com.zenith.auxcam.VideoPolicy
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zenith.auxcam.ui.effects.cameraGlass
import com.zenith.auxcam.ui.effects.SceneAtmosphere
import com.zenith.auxcam.ui.theme.CameraColors
import com.zenith.auxcam.ui.theme.CameraType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.selected
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private data class GalleryItem(val uri: Uri, val video: Boolean, val date: Long, val name: String)

private fun loadMedia(context: Context): List<GalleryItem> {
    val columns = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DISPLAY_NAME)
    val result = mutableListOf<GalleryItem>()
    val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?) AND ${MediaStore.MediaColumns.IS_PENDING}=0 AND " +
        "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
    context.contentResolver.query(MediaStore.Files.getContentUri("external"), columns, selection,
        arrayOf("Pictures/NotiCam/", "Movies/NotiCam/", context.packageName,
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(), MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()),
        "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.Files.FileColumns._ID} DESC")?.use { cursor ->
        while (cursor.moveToNext()) {
            val video = cursor.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
            val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            result.add(GalleryItem(ContentUris.withAppendedId(base, cursor.getLong(0)), video, cursor.getLong(2) * 1000, cursor.getString(3) ?: "Capture"))
        }
    }
    return result
}

/** Photos-inspired, app-owned library. Originals are fit, never center-cropped or re-encoded. */
@Composable
fun GalleryScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val media by produceState<List<GalleryItem>?>(null, revision) {
        value = withContext(Dispatchers.IO) {
            try { loadMedia(context) } catch (e: Exception) { error = "Could not load your captures"; emptyList() }
        }
    }
    var delete by remember { mutableStateOf<GalleryItem?>(null) }
    val prefs = remember { context.getSharedPreferences("gallery-favorites", Context.MODE_PRIVATE) }
    var favorites by remember { mutableStateOf(prefs.getStringSet("uris", emptySet())?.toSet() ?: emptySet()) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(onBack = onClose)
        val list = media
        val pager = rememberPagerState(pageCount = { media?.size ?: 0 })
        val selected = list?.takeIf { it.isNotEmpty() }?.let { it[pager.currentPage.coerceIn(0, it.lastIndex)] }
        val backdrop by produceState<ImageBitmap?>(null, selected?.uri) {
            value = withContext(Dispatchers.IO) {
                try { selected?.let { context.contentResolver.loadThumbnail(it.uri, Size(128, 128), null).asImageBitmap() } }
                catch (_: Exception) { null }
            }
        }
        Box(Modifier.fillMaxSize().background(CameraColors.Black)) {
            SceneAtmosphere(backdrop, Modifier.matchParentSize())
            Column(Modifier.fillMaxSize().padding(gallerySafePadding())) {
                if (list == null) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    TextButton(onClick = onClose) { Text("Back to Camera") }
                } else if (list.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(error ?: "Your photos and videos will appear here", color = Color.White)
                    }
                    TextButton(onClick = onClose) { Text("Back to Camera") }
                } else {
                    val current = list[pager.currentPage.coerceIn(0, list.lastIndex)]
                    Row(Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        GalleryButton(NotiIcons.Back, "Back to camera", onClose)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(current.date)),
                                style = CameraType.Heading, color = CameraColors.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(current.date)),
                                style = CameraType.Caption, color = CameraColors.LabelSecondary)
                        }
                        Text("${pager.currentPage + 1} / ${list.size}", style = CameraType.Caption,
                            color = CameraColors.LabelSecondary, modifier = Modifier.padding(end = 8.dp))
                    }
                    HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(24.dp)), key = { list[it].uri.toString() }) { index ->
                        val item = list[index]
                        if (item.video) GalleryVideo(item, active = pager.currentPage == index)
                        else GalleryPhoto(item)
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp)) {
                        Text(current.name.substringBeforeLast('.'), style = CameraType.Heading,
                            color = CameraColors.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (current.video) "Video · MP4" else "Photo · JPEG",
                            style = CameraType.Caption, color = CameraColors.LabelSecondary)
                    }
                    LazyRow(Modifier.fillMaxWidth().height(62.dp), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        itemsIndexed(list, key = { _, item -> item.uri.toString() }) { index, item ->
                            val thumbnail by produceState<ImageBitmap?>(null, item.uri) {
                                value = withContext(Dispatchers.IO) {
                                    try { context.contentResolver.loadThumbnail(item.uri, Size(96, 96), null).asImageBitmap() }
                                    catch (_: Exception) { null }
                                }
                            }
                            val tileWidth by animateDpAsState(if (index == pager.currentPage) 46.dp else 34.dp,
                                CameraMotion.select(), label = "filmstrip-selection")
                            Box(Modifier.size(tileWidth, 54.dp)
                                .clip(RoundedCornerShape(10.dp)).background(CameraColors.Matte)
                                .semantics {
                                    contentDescription = "${if (item.video) "Video" else "Photo"} ${index + 1}"
                                    this.selected = index == pager.currentPage
                                }
                                .clickable { scope.launch { pager.animateScrollToPage(index) } }, contentAlignment = Alignment.Center) {
                                thumbnail?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                                if (item.video) Text("▶", color = Color.White)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        GalleryButton(NotiIcons.Share, "Share", {
                            try {
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = if (current.video) "video/mp4" else "image/jpeg"
                                    putExtra(Intent.EXTRA_STREAM, current.uri)
                                    clipData = ClipData.newRawUri("NotiCam capture", current.uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, "Share capture"))
                            } catch (_: Exception) { error = "No app available for sharing" }
                        })
                        GalleryButton(if (current.uri.toString() in favorites) NotiIcons.HeartFilled else NotiIcons.Heart, if (current.uri.toString() in favorites) "Remove favorite" else "Favorite", {
                            val key = current.uri.toString()
                            favorites = if (key in favorites) favorites - key else favorites + key
                            prefs.edit().putStringSet("uris", favorites).apply()
                        })
                        GalleryButton(NotiIcons.Trash, "Delete", { delete = current })
                    }
                    error?.let { Text(it, color = Color.Gray, modifier = Modifier.padding(horizontal = 16.dp)) }
                }
            }
        }
        delete?.let { item ->
            AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete this capture?") },
                text = { Text("This permanently removes the original from your device.") },
                confirmButton = { TextButton(onClick = {
                    delete = null
                    scope.launch {
                        val removed = withContext(Dispatchers.IO) { try { context.contentResolver.delete(item.uri, null, null) > 0 } catch (_: Exception) { false } }
                        if (removed) revision++ else error = "Could not delete capture"
                    }
                }) { Text("Delete", color = Color.Red) } },
                dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } })
        }
    }
}

@Composable
private fun GalleryButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .88f else 1f,
        if (pressed) CameraMotion.press() else CameraMotion.release(), label = "viewer-button")
    Box(Modifier.size(52.dp).semantics { contentDescription = label }
        .clickable(interactionSource = interaction, indication = null, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(38.dp).graphicsLayer { scaleX = scale; scaleY = scale }.cameraGlass(CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = CameraColors.Label, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun GalleryPhoto(item: GalleryItem) {
    val context = LocalContext.current
    var loading by remember(item.uri) { mutableStateOf(true) }
    val image by produceState<ImageBitmap?>(null, item.uri) {
        value = withContext(Dispatchers.IO) {
            try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, item.uri)) { decoder, info, _ ->
                    var sample = 1
                    while (maxOf(info.size.width, info.size.height) / sample > 2048) sample *= 2
                    decoder.setTargetSampleSize(sample)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }.asImageBitmap() // ImageDecoder applies EXIF rotation/mirroring.
            } catch (_: Exception) { null }
        }
        loading = false
    }
    var zoom by remember(item.uri) { mutableFloatStateOf(1f) }
    var pan by remember(item.uri) { mutableStateOf(Offset.Zero) }
    val bitmap = image
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(0.dp))
        .pointerInput(item.uri) { detectTapGestures(onDoubleTap = { zoom = if (zoom > 1f) 1f else 2f; pan = Offset.Zero }) }
        .pointerInput(item.uri) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed } > 1 || zoom > 1f) {
                        zoom = (zoom * event.calculateZoom()).coerceIn(1f, 5f)
                        val changePan = event.calculatePan()
                        val limitX = size.width * (zoom - 1f) / 2f
                        val limitY = size.height * (zoom - 1f) / 2f
                        pan = Offset((pan.x + changePan.x).coerceIn(-limitX, limitX), (pan.y + changePan.y).coerceIn(-limitY, limitY))
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        }, contentAlignment = Alignment.Center) {
        if (bitmap == null) Text(if (loading) "Loading photo…" else "Could not load photo", color = Color.Gray)
        else Image(bitmap, contentDescription = item.name, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y })
    }
}

@Composable
private fun GalleryVideo(item: GalleryItem, active: Boolean) {
    var view by remember(item.uri) { mutableStateOf<VideoView?>(null) }
    var playing by remember(item.uri) { mutableStateOf(false) }
    val context = LocalContext.current
    val displayAspect by produceState<Float?>(null, item.uri) {
        value = withContext(Dispatchers.IO) {
            val metadata = MediaMetadataRetriever()
            try {
                metadata.setDataSource(context, item.uri)
                val w = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rotation = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                VideoPolicy.displayAspect(w, h, rotation)
            } catch (_: Exception) { null } finally { try { metadata.release() } catch (_: Exception) { } }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val aspect = displayAspect ?: (3f / 4f)
        val videoWidth = minOf(maxWidth, maxHeight * aspect)
        val videoHeight = videoWidth / aspect
        AndroidView(factory = { context -> VideoView(context).also { video ->
            view = video
            video.setVideoURI(item.uri)
            video.setOnPreparedListener { player -> player.isLooping = false; video.seekTo(1) }
            video.setOnCompletionListener { playing = false }
        } }, modifier = Modifier.size(videoWidth, videoHeight))
        TextButton(onClick = { if (playing) view?.pause() else view?.start(); playing = !playing },
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).cameraGlass(RoundedCornerShape(24.dp))) {
            Text(if (playing) "Pause" else "Play", color = Color.White)
        }
    }
    LaunchedEffect(active) { if (!active) { view?.pause(); playing = false } }
    val lifecycle = remember(context) { context.viewerLifecycle() }
    DisposableEffect(item.uri, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) { view?.pause(); playing = false }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer); view?.stopPlayback() }
    }

}

private fun Context.viewerLifecycle(): Lifecycle? {
    var current = this
    while (true) {
        if (current is LifecycleOwner) return current.lifecycle
        if (current !is ContextWrapper) return null
        val next = current.baseContext
        if (next === current) return null
        current = next
    }
}

/** Reserve actual dialog system bars/cutouts even when parent Compose consumed
 * safeDrawing. Keep the actions another 20 dp above the navigation/gesture area.
 */
@Composable
private fun gallerySafePadding(): PaddingValues {
    val view = LocalView.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val fallback = WindowInsets.safeDrawing
    var physical by remember(view) { mutableStateOf(Insets.NONE) }
    DisposableEffect(view) {
        val update = Runnable {
            val root = ViewCompat.getRootWindowInsets(view)
            if (root != null) physical = root.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        }
        val observer = ViewTreeObserver.OnGlobalLayoutListener { update.run() }
        view.viewTreeObserver.addOnGlobalLayoutListener(observer)
        view.post(update)
        onDispose {
            view.removeCallbacks(update)
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnGlobalLayoutListener(observer)
        }
    }
    val left = maxOf(physical.left, fallback.getLeft(density, direction))
    val right = maxOf(physical.right, fallback.getRight(density, direction))
    return with(density) {
        PaddingValues(
            start = (if (direction == LayoutDirection.Ltr) left else right).toDp(),
            end = (if (direction == LayoutDirection.Ltr) right else left).toDp(),
            top = maxOf(physical.top, fallback.getTop(density)).toDp(),
            bottom = maxOf(physical.bottom, fallback.getBottom(density)).toDp() + 20.dp,
        )
    }
}
