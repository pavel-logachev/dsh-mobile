package dev.dshmobile.app.data

import android.util.Log
import dev.dshmobile.app.BuildConfig

/** No throwable message, cause, payload, identifiers or credentials enter diagnostic logs. */
internal fun safeDebugDiagnostic(stage: String, failure: Exception) {
    if (!BuildConfig.DEBUG) return
    val className = failure.javaClass.name.take(120).replace(Regex("[^A-Za-z0-9_.$]"), "_")
    val site = failure.stackTrace.firstOrNull { it.className.startsWith("dev.dshmobile.app.data.") }
    val callsite = site?.let { "${it.className.take(120)}.${it.methodName.take(80)}:${it.lineNumber}" } ?: "none"
    runCatching { Log.e("DshMobileDiag", "stage=${stage.take(80)} exception=$className appSite=$callsite") }
}

/** Fixed read evidence only; no identifiers, request data or exception/cause text. */
internal fun traceReadFailure(headerEof: Boolean, socketClosed: Boolean, active: Boolean, cancelled: Boolean, retired: Boolean) {
    if (!BuildConfig.DEBUG) return
    runCatching { Log.i("DshMobileSelect", "phase=READ_FAILURE headerEof=$headerEof socketClosed=$socketClosed active=$active cancelled=$cancelled retired=$retired") }
}
/** Optional JVM-only observer of the existing retry branch; never changes its admission or policy. */
internal object ReadRetryObservation {
    @Volatile var onRetry: (() -> Unit)? = null
}
internal fun traceReadRetry() {
    if (!BuildConfig.DEBUG) return
    val observer = ReadRetryObservation.onRetry
    observer?.let { runCatching(it) }
    runCatching { Log.i("DshMobileSelect", "phase=READ_RETRY") }
}
