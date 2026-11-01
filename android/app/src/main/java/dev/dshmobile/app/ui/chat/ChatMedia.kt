package dev.dshmobile.app.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.dshmobile.app.R
import dev.dshmobile.app.model.*
import dev.dshmobile.app.ui.MobileIcons
import dev.dshmobile.app.ui.components.Spinner
import dev.dshmobile.app.ui.components.shortTime
import androidx.compose.animation.AnimatedVisibility
import dev.dshmobile.app.ui.theme.ControlSize
import dev.dshmobile.app.ui.theme.IconSize
import dev.dshmobile.app.ui.theme.Motion
import dev.dshmobile.app.ui.theme.Spacing
import dev.dshmobile.app.ui.theme.motionEnabled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch

/** Screen-owned, bounded memory cache. Nothing private is persisted in cleartext. */
internal class ImageMemoryCache(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val fetch: suspend (String) -> ByteArray,
) : RememberObserver {
    // This monitor protects metadata only: never fetch, await or decode under it.
    private val lane = Any()
    private val entries = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var entryBytes = 0L
    private val flights = mutableMapOf<String, Flight>()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val downloads = Semaphore(2)
    private var closed = false

    private class Flight {
        lateinit var task: Deferred<ByteArray>
        var waiters = 0
    }

    suspend fun get(id: String): ByteArray {
        currentCoroutineContext().ensureActive()
        val flight = synchronized(lane) {
            if (closed) throw CancellationException("Image cache closed")
            entries[id]?.let { return it }
            flights.getOrPut(id) {
                Flight().also { flight ->
                    // Lazy start keeps even an immediate dispatcher outside the monitor.
                    flight.task = scope.async(start = CoroutineStart.LAZY) {
                        try {
                            downloads.withPermit {
                                ensureActive()
                                val bytes = fetch(id)
                                ensureActive()
                                synchronized(lane) {
                                    if (closed || flights[id] !== flight) throw CancellationException("Image request released")
                                    // Oversized responses can be viewed, but must not exceed the cache budget.
                                    if (bytes.size <= MAX_BYTES) {
                                        while (entries.isNotEmpty() && entryBytes + bytes.size > MAX_BYTES) {
                                            entryBytes -= entries.remove(entries.keys.first())!!.size
                                        }
                                        entries[id] = bytes
                                        entryBytes += bytes.size
                                    }
                                }
                                bytes
                            }
                        } finally {
                            synchronized(lane) { if (flights[id] === flight) flights.remove(id) }
                        }
                    }
                }
            }.also { it.waiters++ }
        }
        try {
            flight.task.start()
            return flight.task.await()
        } finally {
            val cancel = synchronized(lane) {
                flight.waiters--
                (flight.waiters == 0).also { last ->
                    if (last && flights[id] === flight) flights.remove(id)
                }
            }
            if (cancel) flight.task.cancel()
        }
    }

    fun close() {
        synchronized(lane) {
            closed = true
            entries.clear()
            entryBytes = 0
            flights.clear()
        }
        scope.cancel()
    }

    override fun onRemembered() = Unit
    override fun onForgotten() = close()
    override fun onAbandoned() = close()

    private companion object { const val MAX_BYTES = 40L * 1024 * 1024 }
}

/** A relay or upstream image read can stall; keep the preview spinner bounded and retry once. */
internal suspend fun fetchImagePreview(fetch: suspend () -> ByteArray): ByteArray {
    var last: Throwable? = null
    repeat(2) { attempt ->
        try {
            return withTimeout(PREVIEW_FETCH_TIMEOUT_MS) { fetch() }
        } catch (timeout: TimeoutCancellationException) {
            last = timeout
            if (attempt == 0) delay(PREVIEW_RETRY_DELAY_MS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            last = failure
            if (attempt == 0) delay(PREVIEW_RETRY_DELAY_MS)
        }
    }
    throw last ?: IllegalStateException("image unavailable")
}

private const val PREVIEW_FETCH_TIMEOUT_MS = 12_000L
private const val PREVIEW_RETRY_DELAY_MS = 750L

@Composable
private fun MediaImage(ref: AttachmentRef, cache: ImageMemoryCache, modifier: Modifier, description: String, onOpen: () -> Unit) {
    var retry by remember(ref.id) { mutableIntStateOf(0) }
    var failed by remember(ref.id) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, ref.id, retry) {
        failed = false
        try { value = decodeImage(fetchImagePreview { cache.get(ref.id) }, 1024) } catch (_: Exception) { failed = true }
    }
    val retryLabel = stringResource(R.string.mobile_media_retry)
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest).clickable { if (failed) retry++ else if (bitmap != null) onOpen() }
        .semantics { contentDescription = if (failed) retryLabel else description; if (bitmap == null && !failed) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }.testTag("media_${ref.id}"), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: if (failed) Icon(MobileIcons.Refresh, null) else Spinner(size = 20.dp)
    }
}
private suspend fun decodeImage(bytes: ByteArray, max: Int): Bitmap = withContext(Dispatchers.Default) {
    decodeBoundedImage({ bytes.inputStream() }, max).bitmap
}

@Composable
internal fun MediaMessage(message: ChatMessage, cache: ImageMemoryCache, onCopy: (String) -> Unit, onFile: ((AttachmentRef) -> Unit)? = null, onOpen: (List<AttachmentRef>, Int) -> Unit) {
    MessageItem(message, onCopy, attachments = { MediaAttachments(message, cache, onFile, onOpen) })
}

@Composable
internal fun MediaAttachments(message: ChatMessage, cache: ImageMemoryCache, onFile: ((AttachmentRef) -> Unit)? = null, onOpen: (List<AttachmentRef>, Int) -> Unit) {
    val images = message.attachments.filter { it.kind == "image" }
    val files = message.attachments.filter { it.kind != "image" }
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (images.size == 1) MediaImage(images[0], cache, Modifier.fillMaxWidth().aspectRatio(1.3f).clip(RoundedCornerShape(16.dp)), stringResource(R.string.mobile_image_description, 1, 1, images[0].name)) { onOpen(images, 0) }
                else images.take(4).chunked(2).forEachIndexed { row, refs -> Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    refs.forEachIndexed { col, ref ->
                        val index = row * 2 + col
                        Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(12.dp))) {
                            MediaImage(ref, cache, Modifier.fillMaxSize(), stringResource(R.string.mobile_image_description, index + 1, images.size, ref.name)) { onOpen(images, index) }
                            if (index == 3 && images.size > 4) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable { onOpen(images, 3) }, contentAlignment = Alignment.Center) { Text("+${images.size - 4}", color = Color.White, style = MaterialTheme.typography.titleMedium) }
                        }
                    }
                    if (refs.size == 1) Spacer(Modifier.weight(1f))
                } }
                files.forEach { file -> Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(MobileIcons.File, null, Modifier.size(32.dp)); Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) { Text(file.name, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text(fileMeta(context, file.name, file.size), style = MaterialTheme.typography.labelSmall) }
                    if (onFile != null) IconButton(onClick = { onFile(file) }, modifier = Modifier.testTag("file_open_${file.id}")) { Icon(MobileIcons.Download, stringResource(R.string.mobile_file_open)) }
                } }
    }
}

/** Full-screen viewer, shown over the retained chat subtree. */
@Composable
internal fun ImageViewer(refs: List<AttachmentRef>, initial: Int, cache: ImageMemoryCache, timestamp: Long? = null, onClose: () -> Unit) {
    var index by remember { mutableIntStateOf(initial) }
    var scale by remember(index) { mutableFloatStateOf(1f) }
    var x by remember(index) { mutableFloatStateOf(0f) }; var y by remember(index) { mutableFloatStateOf(0f) }
    var panels by remember { mutableStateOf(false) }; var retry by remember { mutableIntStateOf(0) }
    var viewport by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope(); val snackbar = remember { SnackbarHostState() }
    val ref = refs[index]
    var bytes by remember(index) { mutableStateOf<ByteArray?>(null) }
    var failed by remember(index) { mutableStateOf(false) }
    val decoded by produceState<DecodedImage?>(null, index, retry) {
        value = null; bytes = null; failed = false
        try { val data = cache.get(ref.id); bytes = data; value = withContext(Dispatchers.Default) { decodeBoundedImage({ data.inputStream() }, 4096) } }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    val bitmap = decoded?.bitmap
    val saveDocument = rememberDocumentSaver(snackbar)
    BackHandler(onBack = onClose)
    val view = androidx.compose.ui.platform.LocalView.current
    val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        ?: generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<android.app.Activity>().firstOrNull()?.window
    val accessibility = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
    var exploring by remember { mutableStateOf(accessibility.isTouchExplorationEnabled) }
    DisposableEffect(accessibility) {
        val listener = android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener { exploring = it }
        accessibility.addTouchExplorationStateChangeListener(listener)
        onDispose { accessibility.removeTouchExplorationStateChangeListener(listener) }
    }
    DisposableEffect(window, view) {
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        val oldBehavior = controller?.systemBarsBehavior
        val oldStatus = controller?.isAppearanceLightStatusBars
        val oldNavigation = controller?.isAppearanceLightNavigationBars
        controller?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.isAppearanceLightStatusBars = false; controller?.isAppearanceLightNavigationBars = false
        onDispose {
            controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            if (oldBehavior != null) controller?.systemBarsBehavior = oldBehavior
            if (oldStatus != null) controller?.isAppearanceLightStatusBars = oldStatus
            if (oldNavigation != null) controller?.isAppearanceLightNavigationBars = oldNavigation
        }
    }
    LaunchedEffect(window, panels, exploring) { window?.let {
        val controller = androidx.core.view.WindowCompat.getInsetsController(it, view)
        if (panels || exploring) controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) else controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    } }
    fun zoomTo(next: Float, point: androidx.compose.ui.geometry.Offset = androidx.compose.ui.geometry.Offset(viewport.width / 2f, viewport.height / 2f)) {
        val offset = ViewerGeometry.zoom(x, y, scale, next, point.x, point.y, viewport.width.toFloat(), viewport.height.toFloat(), decoded?.width ?: 0, decoded?.height ?: 0)
        scale = next; x = offset.x; y = offset.y
    }
    val previous = stringResource(R.string.mobile_viewer_previous); val next = stringResource(R.string.mobile_viewer_next)
    val zoomIn = stringResource(R.string.mobile_viewer_zoom_in); val zoomOut = stringResource(R.string.mobile_viewer_zoom_out)
    val saved = stringResource(R.string.mobile_viewer_saved); val failure = stringResource(R.string.mobile_file_unavailable)
        Box(Modifier.fillMaxSize().background(Color.Black).testTag("image_viewer").semantics { paneTitle = ref.name }) {
            Box(Modifier.fillMaxSize().onSizeChanged { viewport = it }.pointerInput(index, decoded, viewport, exploring) {
                detectTapGestures(onDoubleTap = { zoomTo(if (scale == 1f) 2.5f else 1f, it) }, onTap = { if (!exploring) panels = !panels })
            }.pointerInput(index, decoded, viewport) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var swipeX = 0f; var swipeY = 0f
                    do {
                    val event = awaitPointerEvent()
                    val centroid = event.calculateCentroid(useCurrent = false)
                    val pan = event.calculatePan(); val zoom = event.calculateZoom()
                    val old = scale; val next = (scale * zoom).coerceIn(1f, 5f)
                    if (next > 1f || old > 1f) {
                        zoomTo(next, centroid)
                        val offset = ViewerGeometry.clamp(x + pan.x, y + pan.y, scale, viewport.width.toFloat(), viewport.height.toFloat(), decoded?.width ?: 0, decoded?.height ?: 0)
                        x = offset.x; y = offset.y; swipeX = 0f; swipeY = 0f
                    } else {
                        swipeX += pan.x; swipeY += pan.y
                        if (swipeX < -viewport.width * .25f && index < refs.lastIndex) { index++; swipeX = 0f }
                        else if (swipeX > viewport.width * .25f && index > 0) { index--; swipeX = 0f }
                        else if (swipeY > viewport.height * .2f) onClose()
                    }
                    if (zoom != 1f || pan != androidx.compose.ui.geometry.Offset.Zero) event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            }.testTag("viewer_image").semantics { customActions = buildList {
                if (index > 0) add(CustomAccessibilityAction(previous) { index--; true })
                if (index < refs.lastIndex) add(CustomAccessibilityAction(next) { index++; true })
                add(CustomAccessibilityAction(zoomIn) { zoomTo((scale + 0.5f).coerceAtMost(5f)); true })
                add(CustomAccessibilityAction(zoomOut) { zoomTo((scale - 0.5f).coerceAtLeast(1f)); true })
            } }, contentAlignment = Alignment.Center) {
                bitmap?.let { Image(it.asImageBitmap(), stringResource(R.string.mobile_image_description, index + 1, refs.size, ref.name), Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = if (scale > 1) x else 0f; translationY = if (scale > 1) y else 0f }, contentScale = ContentScale.Fit) }
                    ?: if (failed) TextButton(onClick = { retry++ }) { Text(stringResource(R.string.mobile_media_retry), color = Color.White) } else Spinner(size = 24.dp)
            }
            // Chrome fades instead of popping; with touch exploration it stays on so TalkBack can reach it.
            val chrome = panels || exploring
            val motion = motionEnabled
            AnimatedVisibility(chrome, Modifier.align(Alignment.TopCenter), enter = Motion.panelIn(motion), exit = Motion.panelOut(motion), label = "viewer top") {
                Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.4f)).statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose, modifier = Modifier.testTag("viewer_close")) { Icon(MobileIcons.Close, stringResource(R.string.mobile_activity_close), tint = Color.White) }
                    Text("${index + 1} / ${refs.size}", Modifier.weight(1f), color = Color.White)
                    IconButton(onClick = { scope.launch { try { shareImage(context, ref, requireNotNull(bytes)) } catch (_: Exception) { snackbar.showSnackbar(failure) } } }, enabled = bytes != null, modifier = Modifier.testTag("viewer_share")) { Icon(MobileIcons.Share, stringResource(R.string.mobile_viewer_share), tint = Color.White) }
                    IconButton(onClick = { if (android.os.Build.VERSION.SDK_INT < 29) saveDocument(ref, requireNotNull(bytes)) else scope.launch { try { saveImage(context, ref, requireNotNull(bytes)); snackbar.showSnackbar(saved) } catch (_: Exception) { snackbar.showSnackbar(failure) } } }, enabled = bytes != null, modifier = Modifier.testTag("viewer_save")) { Icon(MobileIcons.Download, stringResource(R.string.mobile_viewer_save), tint = Color.White) }
                }
            }
            AnimatedVisibility(chrome, Modifier.align(Alignment.BottomStart), enter = Motion.panelIn(motion), exit = Motion.panelOut(motion), label = "viewer bottom") {
                Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.4f)).navigationBarsPadding().padding(horizontal = Spacing.L, vertical = Spacing.M),
                    verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
                    // Explore-by-touch stepping: two equal outlined buttons, chevron on the outer edge, so the pair
                    // reads as previous/next at any length or font scale; a disabled end dims rather than vanishing.
                    if (exploring && refs.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.M)) {
                        ViewerStep(previous, MobileIcons.ChevronLeft, leading = true, enabled = index > 0, modifier = Modifier.weight(1f).testTag("viewer_previous")) { index-- }
                        ViewerStep(next, MobileIcons.ChevronRight, leading = false, enabled = index < refs.lastIndex, modifier = Modifier.weight(1f).testTag("viewer_next")) { index++ }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.XXS)) {
                        Text(ref.name, color = Color.White, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text("${decoded?.width ?: 0} × ${decoded?.height ?: 0} · ${android.text.format.Formatter.formatShortFileSize(context, ref.size)}" + (timestamp?.let { " · ${shortTime(it)}" } ?: ""), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
}

@Composable
private fun ViewerStep(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, leading: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = ControlSize.TouchTarget),
        shape = MaterialTheme.shapes.medium, border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = if (enabled) 0.5f else 0.18f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White, disabledContentColor = Color.White.copy(alpha = 0.38f)),
        contentPadding = PaddingValues(horizontal = Spacing.M, vertical = Spacing.S)) {
        if (leading) { Icon(icon, null, Modifier.size(IconSize.Row)); Spacer(Modifier.width(Spacing.XS)) }
        Text(label, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (!leading) { Spacer(Modifier.width(Spacing.XS)); Icon(icon, null, Modifier.size(IconSize.Row)) }
    }
}

/** SAF export on API26–28: persist only a private temporary path across recreation, never bytes. */
@Composable
internal fun rememberDocumentSaver(snackbar: SnackbarHostState): (AttachmentRef, ByteArray) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = stringResource(R.string.mobile_viewer_saved)
    val failure = stringResource(R.string.mobile_file_download_failed)
    var source by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = source?.let { java.io.File(TemporaryMedia.directory(context), it) }
        source = null
        scope.launch {
            try {
                if (uri != null && file != null) {
                    withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("save") }
                    snackbar.showSnackbar(saved)
                }
            } catch (_: Exception) { snackbar.showSnackbar(failure) }
            finally { if (file != null) TemporaryMedia.delete(context, file) }
        }
    }
    return { ref, data -> scope.launch {
        try {
            val file = withContext(Dispatchers.IO) { java.io.File.createTempFile("share-", ".export", TemporaryMedia.directory(context)).apply { writeBytes(data) } }
            TemporaryMedia.expireShare(context, file); source = file.name; launcher.launch(ref.name)
        } catch (_: Exception) { snackbar.showSnackbar(failure) }
    } }
}

internal suspend fun openDownloadedFile(context: android.content.Context, ref: AttachmentRef, bytes: ByteArray) = withContext(Dispatchers.IO) {
    val file = java.io.File.createTempFile("share-", ".${ref.name.substringAfterLast('.', "bin").filter { it.isLetterOrDigit() }.take(12)}", TemporaryMedia.directory(context)).apply { writeBytes(bytes) }
    TemporaryMedia.expireShare(context, file)
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.media", file)
    if (android.os.Build.VERSION.SDK_INT >= 29) {
        val values = android.content.ContentValues().apply { put(android.provider.MediaStore.Downloads.DISPLAY_NAME, ref.name); put(android.provider.MediaStore.Downloads.MIME_TYPE, ref.mime); put(android.provider.MediaStore.Downloads.IS_PENDING, 1) }
        val saved = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("save")
        try {
            context.contentResolver.openOutputStream(saved)?.use { it.write(bytes) } ?: error("save")
            context.contentResolver.update(saved, android.content.ContentValues().apply { put(android.provider.MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (failure: Exception) { context.contentResolver.delete(saved, null, null); throw failure }
    }
    withContext(Dispatchers.Main) {
        val open = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, ref.mime).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { context.startActivity(open) } catch (_: android.content.ActivityNotFoundException) {
            context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType(ref.mime).putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
        }
    }
}

internal suspend fun shareImage(context: android.content.Context, ref: AttachmentRef, data: ByteArray) = withContext(Dispatchers.IO) {
    val dir = TemporaryMedia.directory(context)
    TemporaryMedia.cleanupShares(context)
    val file = java.io.File.createTempFile("share-", ".${ref.mime.substringAfter('/')}", dir).apply { writeBytes(data) }
    TemporaryMedia.expireShare(context, file)
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.media", file)
    withContext(Dispatchers.Main) { context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType(ref.mime).putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), null)) }
}
@androidx.annotation.RequiresApi(29)
private suspend fun saveImage(context: android.content.Context, ref: AttachmentRef, data: ByteArray) = withContext(Dispatchers.IO) {
    val values = android.content.ContentValues().apply { put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, ref.name); put(android.provider.MediaStore.Images.Media.MIME_TYPE, ref.mime); put(android.provider.MediaStore.Images.Media.IS_PENDING, 1) }
    val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("save")
    try {
        context.contentResolver.openOutputStream(uri)?.use { it.write(data) } ?: error("save")
        context.contentResolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    } catch (e: Exception) { context.contentResolver.delete(uri, null, null); throw e }
}
