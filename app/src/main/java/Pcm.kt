package com.phocorin.easyaudio

import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

object Pcm {
    const val RATE = 44100
    const val HEADER = 44
    fun header(bytes: Long, rate: Int = RATE): ByteArray {
        require(bytes in 0..0xfffffff0L && bytes % 2 == 0L)
        return ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((bytes + 36).toInt()); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(bytes.toInt())
        }.array()
    }
    fun repair(file: File) {
        RandomAccessFile(file, "rw").use { f ->
            val bytes = ((f.length() - HEADER).coerceAtLeast(0) / 2) * 2
            f.setLength(bytes + HEADER); f.seek(0); f.write(header(bytes)); f.fd.sync()
        }
    }
    fun duration(file: File) = ((file.length() - HEADER).coerceAtLeast(0) * 1000 / (RATE * 2))
    fun trim(input: File, output: File, startMs: Long, endMs: Long) {
        require(startMs >= 0 && endMs > startMs && endMs <= duration(input))
        val start = startMs * RATE / 1000 * 2
        val end = (endMs * RATE / 1000 * 2).coerceAtMost(input.length() - HEADER)
        RandomAccessFile(input, "r").use { src ->
            FileOutputStream(output).use { dst ->
                dst.write(header(end - start)); src.seek(HEADER + start)
                val buffer = ByteArray(65536); var remaining = end - start
                while (remaining > 0) { val n = src.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt()); check(n > 0); dst.write(buffer, 0, n); remaining -= n }
                dst.fd.sync()
            }
        }
    }
    fun peaks(file: File): List<Float> {
        val result = mutableListOf<Float>()
        FileInputStream(file).use { input ->
            check(input.skip(HEADER.toLong()) == HEADER.toLong())
            val bytes = ByteArray(RATE / 10 * 2)
            while (true) { val n = input.read(bytes); if (n <= 0) break
                var peak = 0
                for (i in 0 until n - 1 step 2) { val v = ((bytes[i].toInt() and 255) or (bytes[i + 1].toInt() shl 8)).toShort().toInt(); peak = max(peak, abs(v)) }
                result.add(peak / 32768f)
            }
        }
        return if (result.size <= 1200) result else (0 until 1200).map { i ->
            result.subList(i * result.size / 1200, (i + 1) * result.size / 1200).maxOrNull() ?: 0f
        }
    }
    fun formatTime(ms: Long): String { val s = ms.coerceAtLeast(0) / 1000; return "%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) }
}
