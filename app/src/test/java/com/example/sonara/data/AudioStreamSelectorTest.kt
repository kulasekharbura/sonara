package com.example.sonara.data

import com.example.sonara.network.PipedAudioStream
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/**
 * Property tests for AudioStreamSelector.selectBest().
 *
 * **Validates: Requirements 5.1, 5.2, 5.3, 5.4**
 */
class AudioStreamSelectorTest {

    // --- Helper factories ---

    private fun validStream(
        url: String = "https://proxy/stream",
        bitrate: Int = 128000,
        mimeType: String = "audio/webm",
        codec: String? = "opus",
        format: String? = "WEBMA_OPUS",
        quality: String? = "128 kbps"
    ) = PipedAudioStream(url, bitrate, mimeType, codec, format, quality)

    private fun randomValidStream(random: Random): PipedAudioStream {
        val mimeTypes = listOf("audio/webm", "audio/mp4", "audio/ogg", "audio/mpeg")
        val codecs = listOf("opus", "aac", "vorbis", null)
        val bitrate = random.nextInt(32000, 320001)
        return PipedAudioStream(
            url = "https://proxy/stream/${random.nextInt(10000)}",
            bitrate = bitrate,
            mimeType = mimeTypes[random.nextInt(mimeTypes.size)],
            codec = codecs[random.nextInt(codecs.size)],
            format = "FORMAT",
            quality = "$bitrate bps"
        )
    }

    private fun randomInvalidStream(random: Random): PipedAudioStream {
        // Randomly create an invalid stream (empty url, zero/negative bitrate, or non-audio mimeType)
        return when (random.nextInt(3)) {
            0 -> PipedAudioStream("", 128000, "audio/webm", "opus", null, null) // empty url
            1 -> PipedAudioStream("https://proxy/s", 0, "audio/mp4", "aac", null, null) // zero bitrate
            else -> PipedAudioStream("https://proxy/s", 128000, "video/webm", "opus", null, null) // non-audio mimeType
        }
    }

    // ========================================================================
    // Property 4: Stream Filtering Validity
    // selectBest() only returns a stream where url is non-empty, bitrate > 0,
    // and mimeType starts with "audio/". If no such stream exists, returns null.
    // **Validates: Requirements 5.1, 5.4**
    // ========================================================================

    @Test
    fun `property4 - empty list returns null`() {
        val result = AudioStreamSelector.selectBest(emptyList())
        assertNull(result)
    }

    @Test
    fun `property4 - list with only empty url streams returns null`() {
        val streams = listOf(
            validStream(url = ""),
            validStream(url = ""),
            validStream(url = "")
        )
        assertNull(AudioStreamSelector.selectBest(streams))
    }

    @Test
    fun `property4 - list with only zero bitrate streams returns null`() {
        val streams = listOf(
            validStream(bitrate = 0),
            validStream(bitrate = 0),
            validStream(bitrate = -1)
        )
        assertNull(AudioStreamSelector.selectBest(streams))
    }

    @Test
    fun `property4 - list with only non-audio mimeType returns null`() {
        val streams = listOf(
            validStream(mimeType = "video/webm"),
            validStream(mimeType = "video/mp4"),
            validStream(mimeType = "application/octet-stream")
        )
        assertNull(AudioStreamSelector.selectBest(streams))
    }

    @Test
    fun `property4 - mixed invalid streams all fail returns null`() {
        val streams = listOf(
            validStream(url = "", bitrate = 128000, mimeType = "audio/webm"),
            validStream(url = "https://x", bitrate = 0, mimeType = "audio/mp4"),
            validStream(url = "https://x", bitrate = 128000, mimeType = "video/mp4")
        )
        assertNull(AudioStreamSelector.selectBest(streams))
    }

    @Test
    fun `property4 - returned stream always has valid url, bitrate, and mimeType`() {
        // Property: for many random lists, the result is either null or satisfies all three validity conditions
        val random = Random(42)
        repeat(100) {
            val streams = List(random.nextInt(1, 20)) {
                if (random.nextBoolean()) randomValidStream(random) else randomInvalidStream(random)
            }
            val result = AudioStreamSelector.selectBest(streams)
            if (result != null) {
                assertTrue("url must be non-empty", result.url.isNotEmpty())
                assertTrue("bitrate must be > 0", result.bitrate > 0)
                assertTrue("mimeType must start with audio/", result.mimeType.startsWith("audio/"))
            }
        }
    }

    @Test
    fun `property4 - returns null when all streams are invalid across many random inputs`() {
        val random = Random(123)
        repeat(50) {
            val streams = List(random.nextInt(1, 15)) { randomInvalidStream(random) }
            val result = AudioStreamSelector.selectBest(streams)
            assertNull("Should return null for all-invalid list", result)
        }
    }

    @Test
    fun `property4 - single valid stream among many invalid is selected`() {
        val validOne = validStream(url = "https://proxy/theone", bitrate = 96000, mimeType = "audio/ogg", codec = "vorbis")
        val streams = listOf(
            validStream(url = "", bitrate = 256000, mimeType = "audio/webm"),
            validStream(url = "https://x", bitrate = 0, mimeType = "audio/mp4"),
            validStream(url = "https://y", bitrate = 128000, mimeType = "video/webm"),
            validOne
        )
        val result = AudioStreamSelector.selectBest(streams)
        assertNotNull(result)
        assertEquals(validOne, result)
    }

    // ========================================================================
    // Property 5: Stream Selection Preference Order
    // selectBest() returns the stream with highest codec preference score
    // (opus/webm=2, mp4/aac=1, other=0), and among equal preference, the
    // highest bitrate.
    // **Validates: Requirements 5.2, 5.3**
    // ========================================================================

    @Test
    fun `property5 - opus webm preferred over mp4 aac even at lower bitrate`() {
        val opusStream = validStream(bitrate = 128000, mimeType = "audio/webm", codec = "opus")
        val aacStream = validStream(bitrate = 256000, mimeType = "audio/mp4", codec = "aac")
        val result = AudioStreamSelector.selectBest(listOf(aacStream, opusStream))
        assertEquals(opusStream, result)
    }

    @Test
    fun `property5 - mp4 aac preferred over other codec even at lower bitrate`() {
        val aacStream = validStream(bitrate = 64000, mimeType = "audio/mp4", codec = "aac")
        val otherStream = validStream(bitrate = 256000, mimeType = "audio/ogg", codec = "vorbis")
        val result = AudioStreamSelector.selectBest(listOf(otherStream, aacStream))
        assertEquals(aacStream, result)
    }

    @Test
    fun `property5 - higher bitrate wins within same codec preference opus`() {
        val low = validStream(bitrate = 96000, mimeType = "audio/webm", codec = "opus")
        val high = validStream(url = "https://proxy/high", bitrate = 160000, mimeType = "audio/webm", codec = "opus")
        val result = AudioStreamSelector.selectBest(listOf(low, high))
        assertEquals(high, result)
    }

    @Test
    fun `property5 - higher bitrate wins within same codec preference aac`() {
        val low = validStream(bitrate = 128000, mimeType = "audio/mp4", codec = "aac")
        val high = validStream(url = "https://proxy/high", bitrate = 256000, mimeType = "audio/mp4", codec = "aac")
        val result = AudioStreamSelector.selectBest(listOf(low, high))
        assertEquals(high, result)
    }

    @Test
    fun `property5 - higher bitrate wins within same codec preference other`() {
        val low = validStream(bitrate = 64000, mimeType = "audio/ogg", codec = "vorbis")
        val high = validStream(url = "https://proxy/high", bitrate = 192000, mimeType = "audio/ogg", codec = "vorbis")
        val result = AudioStreamSelector.selectBest(listOf(low, high))
        assertEquals(high, result)
    }

    @Test
    fun `property5 - result always has highest codec score among valid streams`() {
        // Property: for many random lists, the selected stream has the highest codec preference score
        val random = Random(99)
        repeat(100) {
            val streams = List(random.nextInt(2, 15)) { randomValidStream(random) }
            val result = AudioStreamSelector.selectBest(streams)
            assertNotNull(result)

            val maxScore = streams.maxOf { codecScore(it) }
            val resultScore = codecScore(result!!)
            assertEquals("Result must have highest codec preference score", maxScore, resultScore)
        }
    }

    @Test
    fun `property5 - result has highest bitrate among streams with same max codec score`() {
        // Property: among streams with the max codec score, the result has the highest bitrate
        val random = Random(77)
        repeat(100) {
            val streams = List(random.nextInt(2, 15)) { randomValidStream(random) }
            val result = AudioStreamSelector.selectBest(streams)
            assertNotNull(result)

            val maxScore = streams.maxOf { codecScore(it) }
            val maxBitrateAtMaxScore = streams
                .filter { codecScore(it) == maxScore }
                .maxOf { it.bitrate }
            assertEquals(
                "Result must have highest bitrate among max-score streams",
                maxBitrateAtMaxScore,
                result!!.bitrate
            )
        }
    }

    @Test
    fun `property5 - ordering is correct with full mix of codec types`() {
        val streams = listOf(
            validStream(url = "https://a", bitrate = 320000, mimeType = "audio/ogg", codec = "vorbis"),   // score 0
            validStream(url = "https://b", bitrate = 256000, mimeType = "audio/mp4", codec = "aac"),      // score 1
            validStream(url = "https://c", bitrate = 128000, mimeType = "audio/webm", codec = "opus"),    // score 2
            validStream(url = "https://d", bitrate = 160000, mimeType = "audio/webm", codec = "opus"),    // score 2
        )
        val result = AudioStreamSelector.selectBest(streams)
        // Should pick the opus/webm at 160000, since it has highest score (2) + highest bitrate in that group
        assertEquals("https://d", result?.url)
        assertEquals(160000, result?.bitrate)
    }

    @Test
    fun `property5 - webm mimeType triggers score 2 even without opus codec`() {
        val webmNoCodec = validStream(bitrate = 128000, mimeType = "audio/webm", codec = null)
        val aacStream = validStream(bitrate = 256000, mimeType = "audio/mp4", codec = "aac")
        val result = AudioStreamSelector.selectBest(listOf(aacStream, webmNoCodec))
        assertEquals(webmNoCodec, result)
    }

    @Test
    fun `property5 - codec opus triggers score 2 even without webm mimeType`() {
        val opusNonWebm = validStream(bitrate = 128000, mimeType = "audio/ogg", codec = "opus")
        val aacStream = validStream(bitrate = 256000, mimeType = "audio/mp4", codec = "aac")
        val result = AudioStreamSelector.selectBest(listOf(aacStream, opusNonWebm))
        assertEquals(opusNonWebm, result)
    }

    // --- Helper to compute codec score matching the implementation logic ---

    private fun codecScore(stream: PipedAudioStream): Int {
        return when {
            stream.mimeType.contains("webm") || stream.codec?.contains("opus") == true -> 2
            stream.mimeType.contains("mp4") || stream.codec?.contains("aac") == true -> 1
            else -> 0
        }
    }
}
