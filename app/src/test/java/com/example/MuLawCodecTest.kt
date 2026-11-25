package com.example

import com.example.audio.MuLawCodec
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

class MuLawCodecTest {

  @Test
  fun `encode and decode preserves speech waveform within quantization tolerance`() {
    val samples = shortArrayOf(-12000, -3200, -400, 0, 400, 3200, 12000)
    val encoded = MuLawCodec.encodeBuffer(samples)
    val decoded = MuLawCodec.decodeBuffer(encoded)

    assertTrue(encoded.size == samples.size)
    for (i in samples.indices) {
      val diff = abs(samples[i].toInt() - decoded[i].toInt())
      val tolerance = (abs(samples[i].toInt()) * 0.06).toInt().coerceAtLeast(32)
      assertTrue("Sample $i diff $diff exceeded $tolerance", diff <= tolerance)
    }
  }
}
