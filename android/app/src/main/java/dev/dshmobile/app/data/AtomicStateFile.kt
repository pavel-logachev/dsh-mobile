package dev.dshmobile.app.data

import android.util.AtomicFile
import java.io.*
import kotlinx.coroutines.sync.Mutex

/** Atomic file boundary: all owners of the same canonical record share one lock. */
internal interface AtomicStateFile {
    val identity: String
    fun openRead(): InputStream
    fun startWrite(): OutputStream
    fun finishWrite(stream: OutputStream)
    fun failWrite(stream: OutputStream?)
    fun delete()
}
internal class AndroidAtomicStateFile(base: File) : AtomicStateFile {
    private val file = AtomicFile(base)
    override val identity = base.canonicalPath
    override fun openRead(): InputStream = file.openRead()
    override fun startWrite(): OutputStream = file.startWrite()
    override fun finishWrite(stream: OutputStream) = file.finishWrite(stream as FileOutputStream)
    override fun failWrite(stream: OutputStream?) = file.failWrite(stream as FileOutputStream?)
    override fun delete() = file.delete()
}
internal object StateFileOwners {
    private val locks = mutableMapOf<String, Mutex>()
    @Synchronized fun mutex(identity: String): Mutex = locks.getOrPut(identity) { Mutex() }
}
