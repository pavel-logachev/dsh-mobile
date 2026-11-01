package dev.dshmobile.app.ui.chat

/**
 * Keeps only questions that can still be acted on: pending on the host or carrying a local answer
 * draft. Resolved questions are rendered from the authoritative bounded resolved list instead of
 * accumulating in this per-screen map forever. Pending entries are never dropped by the limit.
 */
internal fun retainedQuestionIds(
    pendingIds: Set<String>,
    draftIds: Set<String>,
    existing: List<String>,
    limit: Int = 32,
): Set<String> {
    val kept = LinkedHashMap<String, Unit>()
    for (id in existing) if (id in pendingIds || id in draftIds) kept[id] = Unit
    for (id in pendingIds) kept[id] = Unit
    while (kept.size > limit) {
        val removable = kept.keys.firstOrNull { it !in pendingIds } ?: break
        kept.remove(removable)
    }
    return kept.keys
}
