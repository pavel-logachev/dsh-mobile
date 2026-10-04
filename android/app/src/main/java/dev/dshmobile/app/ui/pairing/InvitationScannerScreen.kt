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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
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
    val accents = dev.dshmobile.app.ui.theme.LocalMobileColors.current
    val session = remember(context, owner, preview) { CameraQrSession(context, owner, preview,
        onReady = { hasFlash -> ready = true; flash = hasFlash }, onTorch = { torch = it }, onResult = { currentResult(it) }) }
    DisposableEffect(session) {
        session.start()
        onDispose { session.close() }
    }
    val back = { session.close(); onBack() }
    BackHandler(onBack = back)
    Scaffold(containerColor = MaterialTheme.colorScheme.background, modifier = Modifier.testTag("pairing_scanner"), topBar = {
        TopAppBar(title = { Text(stringResource(R.string.mobile_scan_title), style = MaterialTheme.typography.titleMedium) },
            navigationIcon = { IconButton(onClick = back, modifier = Modifier.testTag("pairing_scan_back")) {
                Icon(MobileIcons.Back, stringResource(R.string.mobile_back))
            } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.mobile_scan_instructions), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val description = stringResource(R.string.mobile_scan_viewfinder)
            // Square viewfinder sized by the shorter window side so landscape keeps the hint and controls visible.
            val window = LocalWindowInfo.current.containerSize
            val density = LocalDensity.current
            val side = with(density) { minOf(window.width.toDp() - 40.dp, window.height.toDp() * 0.55f).coerceAtLeast(160.dp) }
            Box(Modifier.align(Alignment.CenterHorizontally).size(side)
                .clip(RoundedCornerShape(16.dp)).semantics { contentDescription = description },
                contentAlignment = Alignment.Center) {
                AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
                // The high-contrast frame is an aiming aid; the decoder still sees the full frame.
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
                if (!ready) Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text(stringResource(R.string.mobile_scan_loading), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (flash) OutlinedButton(onClick = { session.torch(!torch) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pairing_scan_torch")) {
                Text(stringResource(if (torch) R.string.mobile_scan_torch_off else R.string.mobile_scan_torch_on))
            }
            Text(stringResource(R.string.mobile_scan_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
