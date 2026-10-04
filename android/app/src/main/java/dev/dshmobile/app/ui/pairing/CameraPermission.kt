package dev.dshmobile.app.ui.pairing

internal enum class CameraPermissionAction { Scan, Request, Settings, Unavailable }

/** Evaluated only after a scan tap. Import/paste never request or inspect camera permission. */
internal fun cameraPermissionAction(hasCamera: Boolean, granted: Boolean, requestedBefore: Boolean, shouldShowRationale: Boolean): CameraPermissionAction = when {
    !hasCamera -> CameraPermissionAction.Unavailable
    granted -> CameraPermissionAction.Scan
    !requestedBefore || shouldShowRationale -> CameraPermissionAction.Request
    else -> CameraPermissionAction.Settings
}
