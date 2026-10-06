package dev.dshmobile.app.ui

internal fun notificationTargetSelected(target: String, selected: String?, busy: Boolean, error: String?): Boolean =
    selected == target && !busy && error == null
