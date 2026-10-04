package dev.dshmobile.app.ui

import dev.dshmobile.app.ui.pairing.CameraPermissionAction
import dev.dshmobile.app.ui.pairing.cameraPermissionAction
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraPermissionTest {
    @Test fun `granted camera opens scanner without another permission request`() {
        assertEquals(CameraPermissionAction.Scan, cameraPermissionAction(true, true, true, false))
    }
    @Test fun `first scan request and retryable denial show rationale before requesting`() {
        assertEquals(CameraPermissionAction.Request, cameraPermissionAction(true, false, false, false))
        assertEquals(CameraPermissionAction.Request, cameraPermissionAction(true, false, true, true))
    }
    @Test fun `permanent denial points to app settings instead of repeating requests`() {
        assertEquals(CameraPermissionAction.Settings, cameraPermissionAction(true, false, true, false))
    }
    @Test fun `camera is optional and a device without one keeps file and paste usable`() {
        assertEquals(CameraPermissionAction.Unavailable, cameraPermissionAction(false, false, false, false))
        assertEquals(CameraPermissionAction.Unavailable, cameraPermissionAction(false, true, true, false))
    }
}
