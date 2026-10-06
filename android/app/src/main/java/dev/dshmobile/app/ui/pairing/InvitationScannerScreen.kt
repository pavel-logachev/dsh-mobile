package dev.dshmobile.app.ui.pairing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.dshmobile.app.R
import dev.dshmobile.app.ui.MobileIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InvitationScannerScreen(onBack: () -> Unit, onResult: (InvitationScanResult) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val preview = remember(context) { PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
    } }
    var ready by remember(context, owner, preview) { mutableStateOf(false) }
    var flash by remember(context, owner, preview) { mutableStateOf(false) }
    var torch by remember(context, owner, preview) { mutableStateOf(false) }
    val currentResult by rememberUpdatedState(onResult)
    val session = remember(context, owner, preview) { CameraQrSession(context, owner, preview,
        onReady = { hasFlash -> ready = true; flash = hasFlash }, onTorch = { torch = it }, onResult = { currentResult(it) }) }
    DisposableEffect(session) {
        session.start()
        onDispose { session.close() }
    }
    val back = { session.close(); onBack() }
    BackHandler(onBack = back)
    ScannerSurface(ready, flash, torch, back, { session.torch(!torch) }, { AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize()) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScannerSurface(ready: Boolean, flash: Boolean, torch: Boolean, onBack: () -> Unit, onTorch: () -> Unit,
    preview: @Composable () -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background, modifier = Modifier.testTag("pairing_scanner"), topBar = {
        TopAppBar(title = { Text(stringResource(R.string.mobile_scan_title), style = MaterialTheme.typography.titleMedium) },
            navigationIcon = { IconButton(onClick = onBack, modifier = Modifier.testTag("pairing_scan_back")) {
                Icon(MobileIcons.Back, stringResource(R.string.mobile_back))
            } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
    }) { padding ->
        ScannerContent(ready, flash, torch, onTorch, preview,
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding))
    }
}

/** Camera ownership stays above; constraints and scroll state belong to this previewable surface. */
@Composable
internal fun ScannerContent(ready: Boolean, flash: Boolean, torch: Boolean, onTorch: () -> Unit,
    preview: @Composable () -> Unit, modifier: Modifier = Modifier) {
    val accents = dev.dshmobile.app.ui.theme.LocalMobileColors.current
    BoxWithConstraints(modifier.padding(20.dp)) {
        val landscape = maxWidth > maxHeight
        val side = if (landscape) minOf(maxWidth * 0.45f, maxHeight) else minOf(maxWidth, 320.dp)
        val frame: @Composable () -> Unit = {
            val description = stringResource(R.string.mobile_scan_viewfinder)
            Box(Modifier.size(side).clip(RoundedCornerShape(16.dp)).testTag("pairing_scan_frame")
                .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
                preview()
                Canvas(Modifier.fillMaxSize()) {
                    val side = size.minDimension * 0.78f
                    val left = (size.width - side) / 2
                    val top = (size.height - side) / 2
                    val corner = side * 0.12f
                    for ((x, y, dx, dy) in listOf(
                        listOf(left, top, corner, corner), listOf(left + side, top, -corner, corner),
                        listOf(left, top + side, corner, -corner), listOf(left + side, top + side, -corner, -corner),
                    )) {
                        for ((color, width) in listOf(accents.onAction to 7.dp.toPx(), accents.action to 3.dp.toPx())) {
                            drawLine(color, Offset(x, y + dy), Offset(x, y), width, StrokeCap.Round)
                            drawLine(color, Offset(x, y), Offset(x + dx, y), width, StrokeCap.Round)
                        }
                    }
                }
            }
        }
        val hints: @Composable () -> Unit = {
            Text(stringResource(R.string.mobile_scan_instructions), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!ready) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(stringResource(R.string.mobile_scan_loading), style = MaterialTheme.typography.bodyMedium)
            }
            if (flash) OutlinedButton(onClick = onTorch, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pairing_scan_torch")) {
                Text(stringResource(if (torch) R.string.mobile_scan_torch_off else R.string.mobile_scan_torch_on))
            }
            Text(stringResource(R.string.mobile_scan_hint), Modifier.testTag("pairing_scan_hint"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (landscape) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            frame()
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) { hints() }
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) { frame(); hints() }
    }
}
