package dev.dshmobile.app.data

import dev.dshmobile.app.model.ConnectionState

/** Compatibility helper: task watches never override the foreground repository connection. */
@Suppress("UNUSED_PARAMETER")
internal fun notificationHeaderConnection(connection: ConnectionState, stream: String?): ConnectionState = connection
