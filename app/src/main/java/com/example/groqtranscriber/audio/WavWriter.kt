package com.example.groqtranscriber.audio

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wraps raw PCM bytes (from Gemini TTS) in a valid WAV file header
 * so Android's MediaPlayer can play it without needing FFmpeg.
 *
 * Gemini TTS output: 16-bit signed PCM, mono, 24 000 Hz (L16).
 */
object WavWriter {

    private const val SAMPLE_RATE   = 24_000
    private const val CHANNELS      = 1
    private const val BITS_PER_SAMPLE = 16

    /**
     * Writes [pcmBytes] with a WAV header into [outputFile].
     * Creates or overwrites the file.
     */
    fun write(pcmBytes: ByteArray, outputFile: File) {
        val dataSize   = pcmBytes.size
        val totalSize  = 36 + dataSize          // header = 44 bytes, 44 - 8 = 36

        FileOutputStream(outputFile).use { fos ->
            fos.write(buildHeader(dataSize, totalSize))
            fos.write(pcmBytes)
        }
    }

    private fun buildHeader(dataSize: Int, totalSize: Int): ByteArray {
        val byteRate   = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8

        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            // RIFF chunk
            put("RIFF".toByteArray())
            putInt(totalSize)
            put("WAVE".toByteArray())

            // fmt sub-chunk
            put("fmt ".toByteArray())
            putInt(16)                  // PCM sub-chunk size
            putShort(1)                 // AudioFormat = PCM
            putShort(CHANNELS.toShort())
            putInt(SAMPLE_RATE)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(BITS_PER_SAMPLE.toShort())

            // data sub-chunk
            put("data".toByteArray())
            putInt(dataSize)
        }.array()
    }
}
