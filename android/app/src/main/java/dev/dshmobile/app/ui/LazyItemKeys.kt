package dev.dshmobile.app.ui

/** Stable presentation identities shared by lazy lists and their JVM regression tests. */
internal object LazyItemKeys {
    const val ALL_PROJECTS = "filter:all"
    const val EMPTY_CHATS = "notice:empty-chats"
    const val TRUNCATED_CHATS = "notice:truncated-chats"
    const val EMPTY_PROJECTS = "notice:empty-projects"
    const val DEFAULT_PRESET = "filter:default"
    const val HISTORY_LIMIT = "notice:history-limit"
    const val EMPTY_CONVERSATION = "notice:empty-conversation"
    fun project(id: String) = "project:$id"
    fun section(section: String) = "section:$section"
    fun session(id: String) = "session:$id"
    fun preset(id: String) = "preset:$id"
    fun message(id: String) = "message:$id"
    fun pending(requestId: String) = "pending:$requestId"
}
