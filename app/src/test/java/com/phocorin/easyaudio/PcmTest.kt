package com.phocorin.easyaudio

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmTest {
    private fun fixture(ms: Int): File = File.createTempFile("pcm-test", ".wav").apply {
        val bytes = ms * Pcm.RATE / 1000 * 2
        outputStream().use { it.write(Pcm.header(bytes.toLong())); it.write(ByteArray(bytes) { i -> (i % 127).toByte() }) }
        deleteOnExit()
    }
    @Test fun wavHeaderMatchesRiffSpec() {
        val header = Pcm.header(88200)
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(header, 0, 4)); assertEquals("WAVE", String(header, 8, 4))
        assertEquals(88236, b.getInt(4)); assertEquals(44100, b.getInt(24)); assertEquals(88200, b.getInt(40))
    }
    @Test fun trimPreservesExactSamplesAndSource() {
        val input = fixture(2000); val original = input.readBytes(); val output = File.createTempFile("trim-test", ".wav")
        try { Pcm.trim(input, output, 500, 1500)
            assertEquals(1000L, Pcm.duration(output)); assertArrayEquals(original, input.readBytes())
            assertArrayEquals(original.copyOfRange(44 + 44100, 44 + 132300), output.readBytes().copyOfRange(44, output.length().toInt()))
        } finally { input.delete(); output.delete() }
    }
    @Test fun repairsInterruptedHeaderAndOddTail() {
        val input = fixture(1000)
        try { input.appendBytes(byteArrayOf(1)); Pcm.repair(input); assertEquals(88244L, input.length()); assertEquals(1000L, Pcm.duration(input)) } finally { input.delete() }
    }
    @Test fun rejectsInvalidTrim() {
        val input = fixture(1000); val output = File.createTempFile("bad-trim", ".wav")
        try { listOf(-1L to 500L, 500L to 500L, 900L to 1100L).forEach { (a, b) -> assertThrows(IllegalArgumentException::class.java) { Pcm.trim(input, output, a, b) } } } finally { input.delete(); output.delete() }
    }
    @Test fun peakEnvelopeIsBoundedAndNormalized() {
        val input = fixture(1000)
        try { val peaks = Pcm.peaks(input); assertEquals(10, peaks.size); assertTrue(peaks.all { it in 0f..1f }); assertTrue(peaks.any { it > 0f }) } finally { input.delete() }
    }
    @Test fun qualityCannotSelectInvalidFormat() { assertEquals("m4a", Quality.STANDARD.extension); assertEquals("m4a", Quality.HIGH.extension); assertEquals("wav", Quality.LOSSLESS.extension); assertEquals(0, Quality.LOSSLESS.bitRate) }
}
