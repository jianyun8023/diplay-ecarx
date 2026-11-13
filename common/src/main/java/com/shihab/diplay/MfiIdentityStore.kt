package com.shihab.diplay

import com.shihab.diplay.mfi.LocalMfiAuthenticationClient
import java.io.File
import java.io.InputStream

/**
 * The local identity lives only in `noBackupFilesDir/offline-mfi`. Public APKs carry none, so an
 * upgrade keeps whatever a local build or an import put there.
 */
internal object MfiIdentityStore {
    internal const val KEY_FILE = "identity.pk8"
    internal const val CERTIFICATE_FILE = "certificate.p7b"
    private const val MAX_FILE_BYTES = 16 * 1024

    class Missing : IllegalStateException("No local authentication identity is installed")

    fun directory(root: File) = File(root, LocalMfiAuthenticationClient.DIRECTORY)

    fun installed(root: File): Boolean = directory(root).isDirectory

    /** Checks that the key signs for the certificate, then replaces the current identity in one rename. */
    fun install(root: File, key: ByteArray, certificate: ByteArray) {
        val staging = File(root, "offline-mfi-staging")
        val previous = File(root, "offline-mfi-previous")
        val target = directory(root)
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Could not prepare local authentication" }
        ownerOnly(staging, directory = true)
        try {
            for ((name, bytes) in listOf(KEY_FILE to key, CERTIFICATE_FILE to certificate)) {
                val file = File(staging, name)
                file.writeBytes(bytes)
                ownerOnly(file, directory = false)
            }
            LocalMfiAuthenticationClient.load(staging)
            previous.deleteRecursively()
            if (target.exists()) check(target.renameTo(previous)) { "Could not replace local authentication" }
            if (!staging.renameTo(target)) {
                previous.renameTo(target)
                error("Could not install local authentication")
            }
        } finally {
            staging.deleteRecursively()
            previous.deleteRecursively()
        }
    }

    /** The picker does not say which file is which, and names can be lost, so try both orders. */
    fun import(root: File, first: ByteArray, second: ByteArray) {
        val firstOrder = runCatching { install(root, first, second) }.exceptionOrNull() ?: return
        runCatching { install(root, second, first) }.onFailure {
            it.addSuppressed(firstOrder)
            throw it
        }
    }

    fun readBounded(stream: InputStream): ByteArray = stream.use {
        val bytes = it.readNBytesCompat(MAX_FILE_BYTES + 1)
        require(bytes.isNotEmpty()) { "Identity file is empty" }
        require(bytes.size <= MAX_FILE_BYTES) { "Identity file is too large" }
        bytes
    }

    private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (output.size() < limit) {
            val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun ownerOnly(file: File, directory: Boolean) {
        file.setReadable(false, false); file.setReadable(true, true)
        if (directory) { file.setExecutable(false, false); file.setExecutable(true, true) }
        else { file.setWritable(false, false); file.setWritable(true, true) }
    }
}
