package dev.dshmobile.app.ui.pairing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import java.util.concurrent.Executors

/** Owns only the scan use cases. All camera calls/callbacks run on main; QR analysis runs on one worker. */
internal class CameraQrSession(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val view: PreviewView,
    private val onReady: (Boolean) -> Unit,
    private val onTorch: (Boolean) -> Unit,
    private val onResult: (InvitationScanResult) -> Unit,
) {
    private val main = ContextCompat.getMainExecutor(context)
    private val worker = Executors.newSingleThreadExecutor()
    private val delivery = ScanResultDelivery(
        isActive = { owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) },
        postToMain = { main.execute(it) }, stopCamera = ::releaseCamera, onResult = onResult,
    )
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> delivery.resumed()
            Lifecycle.Event.ON_DESTROY -> close()
            else -> Unit
        }
    }
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private val preview = Preview.Builder().build()
    private val analysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
        .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
            ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
        ).build()).build()
    private val stateObserver = Observer<CameraState> { state ->
        if (state.error != null) deliver(InvitationScanResult.Failed)
    }
    private val torchObserver = Observer<Int> { state -> onTorch(state == androidx.camera.core.TorchState.ON) }

    fun start() {
        owner.lifecycle.addObserver(lifecycleObserver)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            deliver(InvitationScanResult.Failed)
            return
        }
        try {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (!delivery.isEnded) try {
                    val cameras = future.get().also { provider = it }
                    val selector = when {
                        cameras.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                        cameras.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                        else -> { deliver(InvitationScanResult.Unavailable); return@addListener }
                    }
                    preview.surfaceProvider = view.surfaceProvider
                    val decoder = OfflineQrDecoder()
                    analysis.setAnalyzer(worker) { image ->
                        var payload: String? = null
                        try {
                            if (!delivery.isEnded) {
                                val plane = image.planes.firstOrNull()
                                if (plane != null) payload = decoder.decode(plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride)
                            }
                        } catch (_: Exception) { /* Bad/dropped frame: no exception details or image diagnostics. */ }
                        finally { image.close() }
                        payload?.let { deliver(InvitationScanResult.Scanned(it)) }
                    }
                    val bound = cameras.bindToLifecycle(owner, selector, preview, analysis)
                    camera = bound // Observing LiveData can synchronously deliver an error; release must already own it.
                    onReady(bound.cameraInfo.hasFlashUnit())
                    bound.cameraInfo.cameraState.observe(owner, stateObserver)
                    if (!delivery.isEnded) bound.cameraInfo.torchState.observe(owner, torchObserver)
                } catch (_: Exception) { deliver(InvitationScanResult.Failed) }
            }, main)
        } catch (_: Exception) { deliver(InvitationScanResult.Failed) }
    }

    fun torch(enabled: Boolean) {
        if (delivery.isEnded) return
        try { camera?.cameraControl?.enableTorch(enabled)?.addListener({}, main) }
        catch (_: Exception) { onTorch(false) }
    }

    private fun deliver(result: InvitationScanResult) { delivery.deliver(result) }

    fun close() {
        delivery.close()
        releaseCamera()
    }

    private fun releaseCamera() {
        owner.lifecycle.removeObserver(lifecycleObserver)
        camera?.cameraInfo?.cameraState?.removeObserver(stateObserver)
        camera?.cameraInfo?.torchState?.removeObserver(torchObserver)
        analysis.clearAnalyzer()
        try { provider?.unbind(preview, analysis) } catch (_: Exception) { }
        camera = null
        worker.shutdown() // Queued analyzers can still close their ImageProxy in finally.
    }
}
