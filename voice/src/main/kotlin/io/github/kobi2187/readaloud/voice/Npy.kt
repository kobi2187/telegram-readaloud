package io.github.kobi2187.readaloud.voice

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/** A float32 array read from a NumPy file. */
class NpyArray(val shape: IntArray, val data: FloatArray)

/** Minimal reader for little-endian float32 `.npy` arrays and `.npz` archives of them. */
object Npy {
    fun read(bytes: ByteArray): NpyArray {
        if (bytes.size < 10 || bytes[0] != 0x93.toByte() || String(bytes, 1, 5, Charsets.US_ASCII) != "NUMPY") {
            throw IOException("Not a .npy file")
        }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val major = bytes[6].toInt()
        val headerLen = if (major == 1) bb.getShort(8).toInt() and 0xFFFF else bb.getInt(8)
        val start = if (major == 1) 10 else 12
        val header = String(bytes, start, headerLen, Charsets.ISO_8859_1)
        val descr = Regex("'descr':\\s*'([^']+)'").find(header)?.groupValues?.get(1)
        if (descr != "<f4") throw IOException("Unsupported dtype $descr")
        if (Regex("'fortran_order':\\s*True").containsMatchIn(header)) throw IOException("Fortran order not supported")
        val shapeText = Regex("'shape':\\s*\\(([^)]*)\\)").find(header)?.groupValues?.get(1).orEmpty()
        val shape = shapeText.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { it.toInt() }.toIntArray()
        val count = shape.fold(1) { a, b -> a * b }
        val data = FloatArray(count)
        bb.position(start + headerLen)
        bb.asFloatBuffer().get(data)
        return NpyArray(shape, data)
    }

    /** All arrays in an `.npz` archive, by name without the `.npy` suffix. */
    fun readArchive(file: File): Map<String, NpyArray> = ZipFile(file).use { zip ->
        zip.entries().asSequence().filter { it.name.endsWith(".npy") }.associate { e ->
            e.name.removeSuffix(".npy") to read(zip.getInputStream(e).use { it.readBytes() })
        }
    }
}
