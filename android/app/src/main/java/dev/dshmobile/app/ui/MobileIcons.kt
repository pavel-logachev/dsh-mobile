package dev.dshmobile.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Small authored outline set; no icon-font or material-icons dependency. */
object MobileIcons {
    val Menu = outline("Menu") {
        moveTo(4f, 6f); lineTo(20f, 6f)
        moveTo(4f, 12f); lineTo(20f, 12f)
        moveTo(4f, 18f); lineTo(20f, 18f)
    }
    val Back = outline("Back", true) {
        moveTo(19f, 12f); lineTo(5f, 12f)
        moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f)
    }
    val Send = outline("Send") {
        moveTo(12f, 20f); lineTo(12f, 4f)
        moveTo(5f, 11f); lineTo(12f, 4f); lineTo(19f, 11f)
    }
    val Search = outline("Search") {
        moveTo(17f, 17f); lineTo(21f, 21f)
        moveTo(18f, 10f); curveTo(18f, 14.4f, 14.4f, 18f, 10f, 18f); curveTo(5.6f, 18f, 2f, 14.4f, 2f, 10f)
        curveTo(2f, 5.6f, 5.6f, 2f, 10f, 2f); curveTo(14.4f, 2f, 18f, 5.6f, 18f, 10f); close()
    }
    val Settings = outline("Settings") {
        moveTo(9f, 3f); lineTo(15f, 3f); lineTo(16f, 6f); lineTo(19f, 7f); lineTo(21f, 11f)
        lineTo(19f, 14f); lineTo(18f, 18f); lineTo(14f, 19f); lineTo(12f, 21f); lineTo(9f, 19f)
        lineTo(5f, 18f); lineTo(4f, 14f); lineTo(2f, 11f); lineTo(4f, 7f); lineTo(8f, 6f); close()
        moveTo(16f, 12f); curveTo(16f, 14.2f, 14.2f, 16f, 12f, 16f); curveTo(9.8f, 16f, 8f, 14.2f, 8f, 12f)
        curveTo(8f, 9.8f, 9.8f, 8f, 12f, 8f); curveTo(14.2f, 8f, 16f, 9.8f, 16f, 12f); close()
    }
    val Chat = outline("Chat") {
        moveTo(5f, 3f); lineTo(19f, 3f); curveTo(20.1f, 3f, 21f, 3.9f, 21f, 5f); lineTo(21f, 16f)
        curveTo(21f, 17.1f, 20.1f, 18f, 19f, 18f); lineTo(8f, 18f); lineTo(3f, 22f); lineTo(3f, 5f); curveTo(3f, 3.9f, 3.9f, 3f, 5f, 3f); close()
        moveTo(7f, 8f); lineTo(17f, 8f); moveTo(7f, 12f); lineTo(13f, 12f)
    }
    val Folder = outline("Folder") {
        moveTo(3f, 6f); lineTo(3f, 19f); lineTo(21f, 19f); lineTo(21f, 7f); lineTo(11f, 7f); lineTo(9f, 4f); lineTo(3f, 4f); close()
    }
    val Edit = outline("Edit") {
        moveTo(15f, 4f); lineTo(20f, 9f); lineTo(8f, 21f); lineTo(3f, 21f); lineTo(3f, 16f); close()
        moveTo(13f, 6f); lineTo(18f, 11f)
    }
    val Stop = outline("Stop") { moveTo(6f, 6f); lineTo(18f, 6f); lineTo(18f, 18f); lineTo(6f, 18f); close() }
    val Copy = outline("Copy") {
        moveTo(8f, 8f); lineTo(21f, 8f); lineTo(21f, 21f); lineTo(8f, 21f); close()
        moveTo(16f, 4f); lineTo(16f, 3f); lineTo(3f, 3f); lineTo(3f, 16f); lineTo(4f, 16f)
    }
    val Check = outline("Check") { moveTo(5f, 12f); lineTo(10f, 17f); lineTo(20f, 6f) }
    val Close = outline("Close") { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) }
    val Refresh = outline("Refresh") {
        moveTo(20f, 8f); curveTo(18f, 3f, 11f, 1f, 6f, 5f); curveTo(1f, 9f, 3f, 18f, 9f, 20f); curveTo(14f, 22f, 19f, 19f, 20f, 15f)
        moveTo(20f, 3f); lineTo(20f, 8f); lineTo(15f, 8f)
    }
    val Computer = outline("Computer") {
        moveTo(3f, 3f); lineTo(21f, 3f); lineTo(21f, 16f); lineTo(3f, 16f); close()
        moveTo(12f, 16f); lineTo(12f, 21f); moveTo(7f, 21f); lineTo(17f, 21f)
    }
    val Shield = outline("Shield") {
        moveTo(12f, 2f); lineTo(21f, 6f); lineTo(20f, 14f); curveTo(19f, 18f, 15f, 21f, 12f, 22f)
        curveTo(9f, 21f, 5f, 18f, 4f, 14f); lineTo(3f, 6f); close()
        moveTo(8f, 12f); lineTo(11f, 15f); lineTo(17f, 9f)
    }
    val Mark = outline("DSH") {
        moveTo(9f, 4f); lineTo(3f, 10f); lineTo(9f, 16f); moveTo(15f, 8f); lineTo(21f, 14f); lineTo(15f, 20f)
        moveTo(14f, 3f); lineTo(10f, 21f)
    }
    private fun outline(name: String, mirrored: Boolean = false, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = mirrored).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block)
        }.build()
}
